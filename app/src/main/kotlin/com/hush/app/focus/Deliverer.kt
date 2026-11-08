package com.hush.app.focus

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.text.format.DateFormat
import com.hush.app.R
import com.hush.app.db.NotificationEvent
import com.hush.app.db.NotificationRepository
import com.hush.app.settings.SettingsStore
import com.hush.app.ui.MainActivity
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Clock
import java.time.ZoneId
import java.util.Date
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Owns the HELD → DELIVERED transition: selects held-undelivered rows, posts ONE digest
 * notification, marks the rows delivered with the same timestamp the digest carries, and re-arms
 * the slot alarm. Callers: the slot alarm ([DeliveryReceiver]), boot/time-change
 * ([RescheduleReceiver]), the UI ([DefaultFocusActionsImpl]) and the Sync worker's periodic
 * safety net. All of them can overlap, hence the [Mutex].
 */
@Singleton
class Deliverer @Inject constructor(
    @ApplicationContext private val ctx: Context,
    private val repo: NotificationRepository,
    private val settings: SettingsStore,
    private val clock: Clock,
    private val scheduler: DeliveryScheduler,
) {
    private val mutex = Mutex()
    private val notificationManager get() = ctx.getSystemService(NotificationManager::class.java)

    /**
     * Delivers held rows as one digest when they are due (or always, when [force]).
     *
     * Non-forced "due" = at least one held row has had a slot boundary pass since it was posted,
     * or is older than the max-age guard. When anything is due, every held row goes into the one
     * digest, so a straggler posted between the boundary and a late alarm is not left waiting a
     * whole extra slot. Rows are selected regardless of the current hold list: a package removed
     * from the hold list while it had held rows still gets them delivered on the next run, and so
     * does anything held while Focus was later switched off. Nothing is ever dropped.
     *
     * The alarm is one-shot, so it is re-armed on every exit path while Focus is enabled, and also
     * while anything is still held (Focus off but delivery blocked), so the next slot retries.
     */
    suspend fun deliverDue(force: Boolean = false): DeliverResult = mutex.withLock {
        val now = clock.millis()
        val current = settings.current()
        var stillHeld = false
        try {
            val rows = repo.selectHeldUndelivered()
            if (rows.isEmpty()) return@withLock DeliverResult(0)
            stillHeld = true
            if (!force && rows.none { isDue(it.postTime, now, current.slotMinutes, ZoneId.systemDefault()) }) {
                return@withLock DeliverResult(0)
            }

            ensureChannel()
            if (!canPost()) {
                val error = ctx.getString(R.string.focus_error_notifications_denied)
                settings.update { it.copy(lastFocusError = error) }
                return@withLock DeliverResult(0, error)
            }

            // Once the digest is on screen the rows must be stamped delivered, even if the caller
            // (receiver, worker, UI scope) is cancelled mid-way; otherwise the next run re-posts them.
            withContext(NonCancellable) {
                notificationManager.notify(DIGEST_NOTIFICATION_ID, buildDigest(rows, deliveredAt = now))
                repo.markDelivered(rows.map { it.id }, now)
                if (current.lastFocusError != null) settings.update { it.copy(lastFocusError = null) }
            }
            stillHeld = false
            DeliverResult(rows.size)
        } finally {
            if (current.focusEnabled || stillHeld) scheduler.schedule(now)
        }
    }

    private fun canPost(): Boolean {
        val nm = notificationManager
        if (!nm.areNotificationsEnabled()) return false
        val channel = nm.getNotificationChannel(CHANNEL_ID) ?: return false
        return channel.importance != NotificationManager.IMPORTANCE_NONE
    }

    /** Created at first use; the system keeps the user's later changes (importance, sound, off). */
    private fun ensureChannel() {
        val nm = notificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            ctx.getString(R.string.focus_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply { description = ctx.getString(R.string.focus_channel_description) }
        nm.createNotificationChannel(channel)
    }

    private fun buildDigest(rows: List<NotificationEvent>, deliveredAt: Long): Notification {
        val count = rows.size
        val title = ctx.resources.getQuantityString(R.plurals.focus_digest_title, count, count)

        val perApp = rows.groupingBy { it.label() }.eachCount().entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
        val text = perApp.joinToString(" · ") { "${it.key} ${it.value}" }

        val oldest = rows.minOf { it.postTime }
        // Device 12/24-hour preference and zone, same as the status bar clock.
        val since = ctx.getString(R.string.focus_digest_since, DateFormat.getTimeFormat(ctx).format(Date(oldest)))
        val lines = rows.sortedBy { it.postTime }.take(MAX_BIG_TEXT_LINES).map { it.line() } + since

        val open = Intent(ctx, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(MainActivity.EXTRA_FILTER, MainActivity.FILTER_HELD)
            .putExtra(MainActivity.EXTRA_DELIVERED_AT, deliveredAt)
        val contentIntent = PendingIntent.getActivity(
            ctx,
            OPEN_REQUEST_CODE,
            open,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        return Notification.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(lines.joinToString("\n")))
            .setContentIntent(contentIntent)
            .setWhen(oldest)
            .setShowWhen(true)
            .setAutoCancel(true)
            .build()
    }

    private fun NotificationEvent.label(): String = appLabel?.squash()?.takeIf { it.isNotEmpty() } ?: packageName

    /** One BigText line; embedded newlines/runs of whitespace are collapsed so a row stays one line. */
    private fun NotificationEvent.line(): String {
        val body = listOfNotNull(title?.squash()?.takeIf { it.isNotEmpty() }, text?.squash()?.takeIf { it.isNotEmpty() })
            .joinToString(" — ")
            .ifEmpty { ctx.getString(R.string.focus_digest_no_text) }
        return "${label()}: $body"
    }

    private fun String.squash(): String = replace(whitespace, " ").trim()

    companion object {
        const val CHANNEL_ID = "hush.digest"

        /** Fixed id: the newest digest replaces the previous one in the shade; History keeps them all. */
        const val DIGEST_NOTIFICATION_ID = 4002
        private const val OPEN_REQUEST_CODE = 4003
        private const val MAX_BIG_TEXT_LINES = 6
        private val whitespace = Regex("\\s+")

        /** Max age after which a held row is delivered regardless of schedule. */
        fun maxAgeMillis(slotMinutes: Int): Long = maxOf(2L * slotMinutes, 120L) * 60_000L

        /**
         * A row is due once a slot boundary has passed since it was posted, once it is older than
         * [maxAgeMillis], or when its post time is in the future (the clock was set back since it
         * was held; waiting for "its" boundary could take hours). For a fixed zone the max-age
         * clause is implied by the slot clause (maxAge ≥ 2 slots); it stays as an independent
         * guard against zone changes and `nextSlot` edge cases.
         */
        fun isDue(postTime: Long, now: Long, slotMinutes: Int, zone: ZoneId): Boolean =
            postTime > now ||
                nextSlot(postTime, slotMinutes, zone) <= now ||
                now - postTime >= maxAgeMillis(slotMinutes)
    }
}
