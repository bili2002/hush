package com.hush.app.focus

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Bundle
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
 * Owns the HELD → DELIVERED transition: selects the held-undelivered rows that are due, posts ONE
 * digest notification, marks those rows delivered, and re-arms the slot alarm. Callers: the slot alarm ([DeliveryReceiver]), boot/time-change
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
     * Delivers the held rows that are due as one digest (or every held row, when [force]).
     *
     * "Due" is decided per row with that row's own interval ([throttleMinutes] under the current
     * rules): a slot boundary of its interval has passed since it was posted, or it is older than
     * the max-age guard, or the rules no longer hold it at all (its app left the hold list, its
     * category or chat was set to instant). Rows that are not due stay held, so a short interval's
     * digest never drags a longer interval's rows along. Nothing is ever dropped: whatever the
     * rules become, a held row is due at the latest after [maxAgeMillis].
     *
     * If the previous digest is still sitting unread in the shade, the new one replaces it (same
     * notification id) but also lists the previous one's rows and links to all of them, so a
     * frequent small digest cannot bury a bigger one the owner has not looked at yet.
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
            val zone = ZoneId.systemDefault()
            // With Focus off nothing is being throttled any more: whatever is still held (a flush that was
            // blocked earlier) goes out on the first run that can post, not on each row's old interval.
            val due = if (force || !current.focusEnabled) rows
            else rows.filter { isDue(it.postTime, now, current.throttleMinutes(it.packageName, it.chatKey), zone) }
            if (due.isEmpty()) return@withLock DeliverResult(0)

            ensureChannel()
            if (!canPost()) {
                val error = ctx.getString(R.string.focus_error_notifications_denied)
                settings.update { it.copy(lastFocusError = error) }
                return@withLock DeliverResult(0, error)
            }

            val since = unreadDigestSince()?.takeIf { it < now } ?: now
            val carried = if (since < now) repo.selectHeldBatch(since) else emptyList()

            // Once the digest is on screen the rows must be stamped delivered, even if the caller
            // (receiver, worker, UI scope) is cancelled mid-way; otherwise the next run re-posts them.
            withContext(NonCancellable) {
                notificationManager.notify(DIGEST_NOTIFICATION_ID, buildDigest(fresh = due, carried = carried, coversSince = since))
                repo.markDelivered(due.map { it.id }, now)
                if (current.lastFocusError != null) settings.update { it.copy(lastFocusError = null) }
            }
            stillHeld = due.size < rows.size
            DeliverResult(due.size)
        } finally {
            if (current.focusEnabled || stillHeld) scheduler.schedule(now)
        }
    }

    /** The `since` of the digest still showing in the shade, or null when there is none (tapped, swiped, never posted). */
    private fun unreadDigestSince(): Long? = try {
        notificationManager.activeNotifications
            .firstOrNull { it.id == DIGEST_NOTIFICATION_ID }
            ?.notification?.extras?.getLong(EXTRA_SINCE, 0L)
            ?.takeIf { it > 0L }
    } catch (e: RuntimeException) {
        // A binder hiccup only costs the carry-over; the rows are in History either way.
        null
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

    /**
     * [fresh] are the rows delivered by this run, [carried] those of the unread digest it replaces.
     * [coversSince] = the delivery time of the oldest batch covered; History shows every held row
     * delivered from then on.
     */
    private fun buildDigest(fresh: List<NotificationEvent>, carried: List<NotificationEvent>, coversSince: Long): Notification {
        val rows = carried + fresh
        val count = rows.size
        val title = ctx.resources.getQuantityString(R.plurals.focus_digest_title, count, count)

        val perApp = rows.groupingBy { it.label() }.eachCount().entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
        val text = perApp.joinToString(" · ") { "${it.key} ${it.value}" }

        val oldest = rows.minOf { it.postTime }
        // Device 12/24-hour preference and zone, same as the status bar clock.
        val since = ctx.getString(R.string.focus_digest_since, DateFormat.getTimeFormat(ctx).format(Date(oldest)))
        // New rows first: with a long carried list the expanded text would otherwise repeat the previous digest.
        val lines = (fresh.sortedBy { it.postTime } + carried.sortedBy { it.postTime }).take(MAX_BIG_TEXT_LINES).map { it.line() } + since

        val open = Intent(ctx, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(MainActivity.EXTRA_FILTER, MainActivity.FILTER_HELD)
            .putExtra(MainActivity.EXTRA_DELIVERED_AT, coversSince)
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
            .addExtras(Bundle().apply { putLong(EXTRA_SINCE, coversSince) })
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
        private const val EXTRA_SINCE = "com.hush.app.digest.SINCE"
        private const val MAX_BIG_TEXT_LINES = 6
        private val whitespace = Regex("\\s+")

        /** Max age after which a held row is delivered regardless of schedule. */
        fun maxAgeMillis(minutes: Int): Long = maxOf(2L * minutes, 120L) * 60_000L

        /**
         * A row held for [minutes] is due once a slot boundary of that interval has passed since it
         * was posted, once it is older than [maxAgeMillis], when the rules no longer hold it
         * ([minutes] is [INSTANT]), or when its post time is in the future (the clock was set back
         * since it was held; waiting for "its" boundary could take hours). For a fixed zone the
         * max-age clause is implied by the slot clause (maxAge ≥ 2 slots); it stays as an
         * independent guard against zone changes and `nextSlot` edge cases.
         */
        fun isDue(postTime: Long, now: Long, minutes: Int, zone: ZoneId): Boolean =
            minutes <= INSTANT ||
                postTime > now ||
                nextSlot(postTime, minutes, zone) <= now ||
                now - postTime >= maxAgeMillis(minutes)

        /** When a row held for [minutes] becomes due: its interval's next boundary, capped by the max age. */
        fun dueAt(postTime: Long, minutes: Int, zone: ZoneId): Long =
            if (minutes <= INSTANT) postTime else minOf(nextSlot(postTime, minutes, zone), postTime + maxAgeMillis(minutes))
    }
}
