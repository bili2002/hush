package com.hush.app.listener

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.hush.app.db.NotificationRepository
import com.hush.app.di.ApplicationScope
import com.hush.app.focus.Decision
import com.hush.app.focus.FocusGate
import com.hush.app.settings.SettingsStore
import java.time.Clock
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * All listener logic, with no Android service state: the service forwards every callback here.
 *
 * Invariant ("never lose a notification"): persist first, hide second. The insert runs under
 * [NonCancellable]; a cancel is attempted only after the insert returned, and only for HOLD. If the
 * insert throws we log and leave the notification visible (fail open).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class NotificationIngest @Inject constructor(
    private val repo: NotificationRepository,
    private val gate: FocusGate,
    private val settings: SettingsStore,
    private val clock: Clock,
    @ApplicationScope private val scope: CoroutineScope,
    private val mapper: NotificationMapper,
) {
    private val dedupe = DedupeCache()

    /** Callbacks run one at a time, in arrival order: a POSTED/REMOVED pair for one key can never invert. */
    private val serial = Dispatchers.IO.limitedParallelism(1)

    /**
     * `(key, postTime)` of every notification recorded by the last backfill. A HOLD for exactly that
     * posting is downgraded to PASS_THROUGH: its row already exists with `held = false` and the owner
     * has seen it, so cancelling it would hide a notification we never marked as held.
     */
    private val backfilled = HashSet<Pair<String, Long>>()

    /**
     * Runs [block] serially in the application scope, so an in-flight insert survives the service being
     * destroyed. Any failure is logged and dropped: the listener process must never die because of one
     * notification. [what] is a short label (callback + package), never notification text.
     */
    fun launch(what: String, block: suspend () -> Unit): Job = scope.launch(serial) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "$what failed: ${e.javaClass.simpleName}")
        }
    }

    /**
     * Returns the gate's decision when a row was written, null when the event was filtered, collapsed
     * as a duplicate, or the insert failed. [cancel] is invoked with the notification key only after a
     * successful insert of a HOLD row; its failures are logged and leave the row `held`.
     */
    suspend fun onPosted(sbn: StatusBarNotification, importance: Int?, cancel: (key: String) -> Unit): Decision? {
        val event = mapper.map(sbn, importance, settings.current()) ?: return null
        var decision = gate.decide(event)
        if (decision == Decision.HOLD && synchronized(backfilled) { (event.key to event.postTime) in backfilled }) {
            Log.i(TAG, "HOLD downgraded for ${sbn.packageName}: already recorded by backfill as visible")
            decision = Decision.PASS_THROUGH
        }
        // A re-posted held notification must be cancelled again, so HOLD is never collapsed.
        if (decision != Decision.HOLD && dedupe.isDuplicate(event)) return null

        val row = event.copy(held = decision == Decision.HOLD)
        try {
            withContext(NonCancellable) { repo.insert(row) }
        } catch (e: Exception) {
            Log.e(TAG, "insert failed for ${sbn.packageName}; leaving notification visible", e)
            return null
        }
        dedupe.remember(row)

        if (decision == Decision.HOLD) {
            try {
                cancel(sbn.key)
            } catch (e: RuntimeException) {
                // SecurityException / listener not connected: the row stays held and the Focus max-age guard
                // delivers it. Forget the key so an identical re-post is recorded (and cancelled) again.
                Log.w(TAG, "cancelNotification failed for ${sbn.packageName}: ${e.javaClass.simpleName}")
                dedupe.forget(sbn.key)
            }
        }
        return decision
    }

    suspend fun onRemoved(sbn: StatusBarNotification, reason: Int) {
        dedupe.forget(sbn.key)
        if (reason == NotificationListenerService.REASON_LISTENER_CANCEL) return // our own hold
        val event = mapper.mapRemoved(sbn, reason, settings.current()) ?: return
        try {
            withContext(NonCancellable) { repo.insert(event) }
        } catch (e: Exception) {
            Log.e(TAG, "insert of REMOVED failed for ${sbn.packageName}", e)
        }
    }

    /**
     * Listener bound: stamp the settings, then record what was already visible. The two steps are
     * independent: a failing stamp must not cost the backfill, and vice versa.
     */
    suspend fun onConnected(active: List<StatusBarNotification>) {
        val now = clock.millis()
        runCatching { settings.update { it.copy(listenerConnectedAt = now) } }
            .onFailure { Log.e(TAG, "connected stamp failed: ${it.javaClass.simpleName}") }
        runCatching { onBackfill(active) }
            .onFailure { Log.e(TAG, "backfill failed: ${it.javaClass.simpleName}") }
    }

    suspend fun onDisconnected() {
        val now = clock.millis()
        settings.update { it.copy(listenerDisconnectedAt = now) }
    }

    /**
     * Records notifications that were already visible when the listener bound. They bypass the gate
     * and are stored with `held = false`: the owner has seen them, holding now would be a lie.
     */
    suspend fun onBackfill(active: List<StatusBarNotification>) {
        synchronized(backfilled) { backfilled.clear() }
        val snapshot = settings.current()
        for (sbn in active) {
            val event = try {
                mapper.map(sbn, importance = null, settings = snapshot) ?: continue
            } catch (e: RuntimeException) {
                Log.e(TAG, "backfill map failed for ${sbn.packageName}: ${e.javaClass.simpleName}")
                continue
            }
            try {
                withContext(NonCancellable) { repo.insert(event) }
                dedupe.remember(event)
                synchronized(backfilled) { backfilled += event.key to event.postTime }
            } catch (e: Exception) {
                Log.e(TAG, "backfill insert failed for ${sbn.packageName}", e)
            }
        }
    }

    private companion object {
        const val TAG = "HushIngest"
    }
}
