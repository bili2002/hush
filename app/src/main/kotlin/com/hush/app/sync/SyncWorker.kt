package com.hush.app.sync

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.hush.app.db.NotificationRepository
import com.hush.app.settings.SettingsStore
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.time.Clock
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException

/**
 * Pushes unsynced rows to the server in batches, then runs the housekeeping that must happen even
 * when no server is configured: 30-day retention, Focus delivery safety net, listener watchdog.
 *
 * Scheduled by [SyncInitializer] (periodic, 15 min) and [WorkSyncScheduler.syncNow] (one-time).
 * WorkManager defers periodic work in Doze; that is accepted (the server is an archive, not a
 * live feed) and there is nothing to test about it.
 */
@HiltWorker
class SyncWorker @AssistedInject constructor(
    @Assisted ctx: Context,
    @Assisted params: WorkerParameters,
    private val repo: NotificationRepository,
    private val api: HushApi,
    private val settings: SettingsStore,
    private val clock: Clock,
    private val rebinder: ListenerRebinder,
    private val deliverHooks: Set<@JvmSuppressWildcards DeliverDueHook>,
) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        val s = settings.current()
        val url = s.serverUrl.trim()
        val token = s.token.trim()

        return try {
            if (url.isBlank() || token.isBlank()) {
                // Unconfigured is not an error: the UI shows "Server not set up (optional)".
                settings.update { it.copy(lastSyncError = null) }
                Result.success()
            } else {
                pushAll(url, token, s.excludedPackages)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // A bug or a DB failure must still leave a visible cause next to the retry button.
            // failure(), not retry(): a deterministic error would only burn the backoff ladder.
            Log.w(TAG, "sync failed: ${e::class.simpleName}: ${e.message}")
            fail("Sync failed: ${e::class.simpleName}", retry = false)
        } finally {
            housekeeping()
        }
    }

    private suspend fun pushAll(url: String, token: String, excluded: Set<String>): Result {
        val device = Build.MODEL?.takeIf { it.isNotBlank() } ?: "unknown"
        while (true) {
            val batch = repo.selectUnsynced(BATCH_SIZE)
            if (batch.isEmpty()) break

            // Defensive: the listener never stores excluded packages, but if a row slipped in before
            // the exclusion was set it must not reach the server. Excluded rows are still stamped
            // synced_at below so they leave the unsynced queue (and retention can drop them);
            // otherwise an all-excluded batch would be re-selected forever.
            val toSend = batch.filter { it.packageName !in excluded }
            if (toSend.isNotEmpty()) {
                when (val r = api.push(url, token, device, toSend.map { it.toDto() })) {
                    is PushResult.Ok -> Unit
                    is PushResult.SyncError.Transient -> return fail("Can't reach server: ${r.message}", retry = true)
                    is PushResult.SyncError.Unauthorized -> return fail("Wrong token (401)", retry = false)
                    // Poison batch: fail now, the periodic run tries again next cycle. No backoff loop.
                    is PushResult.SyncError.Rejected -> return fail("Server rejected the batch: ${r.message}", retry = false)
                }
            }
            repo.markSynced(batch.map { it.id }, clock.millis())
        }
        settings.update { it.copy(lastSyncAt = clock.millis(), lastSyncError = null) }
        return Result.success()
    }

    private suspend fun fail(message: String, retry: Boolean): Result {
        settings.update { it.copy(lastSyncError = message) }
        return if (retry) Result.retry() else Result.failure()
    }

    /** Runs after every sync attempt, configured or not. Each step is best-effort and never changes the sync result. */
    private suspend fun housekeeping() {
        // Retention: the query only deletes rows that are synced AND (not held OR already delivered),
        // so an unsynced row or a held-but-undelivered row is never lost (see Notifications.sq).
        guard("retention") { repo.deleteSyncedOlderThan(clock.millis() - RETENTION_MS) }

        // Focus safety net: deliver a digest whose alarm was missed.
        deliverHooks.forEach { hook -> guard("deliverDue") { hook.deliverDue() } }

        // Listener watchdog: if the user has granted notification access, ask the OS to rebind.
        // requestRebind is a no-op when the service is already bound.
        guard("watchdog") {
            val ctx = applicationContext
            if (ctx.packageName in NotificationManagerCompat.getEnabledListenerPackages(ctx)) {
                rebinder.requestRebind(ctx)
            }
        }
    }

    private suspend fun guard(step: String, block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Message only: never notification content or the token.
            Log.w(TAG, "$step failed: ${e::class.simpleName}: ${e.message}")
        }
    }

    companion object {
        const val BATCH_SIZE = 500
        val RETENTION_MS: Long = TimeUnit.DAYS.toMillis(30)
        private const val TAG = "SyncWorker"
    }
}
