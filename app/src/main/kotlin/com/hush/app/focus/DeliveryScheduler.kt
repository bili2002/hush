package com.hush.app.focus

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.hush.app.settings.Settings
import com.hush.app.settings.SettingsStore
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Arms the one-shot alarm for the next moment something has to happen: the next slot boundary of
 * any interval in use (the default, a category's, a chat rule's), or the next change the profile
 * schedule brings, whichever comes first. `setAndAllowWhileIdle` fires in Doze (possibly a few
 * minutes late, never early) and needs no exact-alarm permission. The alarm is one-shot, so
 * [Deliverer] re-arms it at the end of every run.
 */
@Singleton
class DeliveryScheduler @Inject constructor(
    @ApplicationContext private val ctx: Context,
    private val settings: SettingsStore,
) {
    private val alarmManager get() = ctx.getSystemService(AlarmManager::class.java)

    /**
     * Arms the alarm for [nextWake] in the device zone, or clears it when there is nothing to wake
     * for. [held]: rows are still held, so a digest retry is owed even with Focus off. Returns the
     * trigger time, or null when the alarm was cleared.
     */
    suspend fun rearm(nowMillis: Long, held: Boolean): Long? {
        val triggerAt = nextWake(nowMillis, settings.current(), ZoneId.systemDefault(), held)
        if (triggerAt == null) cancel() else alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent())
        return triggerAt
    }

    fun cancel() {
        alarmManager.cancel(pendingIntent())
    }

    // Same request code + same intent every time, so UPDATE_CURRENT replaces instead of stacking alarms.
    private fun pendingIntent(): PendingIntent = PendingIntent.getBroadcast(
        ctx,
        REQUEST_CODE,
        Intent(ctx, DeliveryReceiver::class.java).setAction(DeliveryReceiver.ACTION_DELIVER),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    companion object {
        private const val REQUEST_CODE = 4001
        private const val RETRY_MINUTES = 60

        /**
         * The earliest slot boundary after [nowMillis] over the intervals the rules use. With no
         * interval in use (nothing would be held) it falls back to the default slot, or to an hour
         * when the default itself is "Instantly", so rows held under earlier rules still get their
         * retry.
         */
        fun nextBoundary(nowMillis: Long, settings: Settings, zone: ZoneId): Long =
            settings.activeThrottles()
                .ifEmpty { setOf(settings.slotMinutes.takeIf { it > INSTANT } ?: RETRY_MINUTES) }
                .minOf { nextSlot(nowMillis, it, zone) }

        /**
         * When the alarm has to fire next: the digest boundary while Focus is on or rows are
         * [held], and the next schedule change (which is what turns Focus on while it is off).
         * Null when neither applies.
         */
        fun nextWake(nowMillis: Long, settings: Settings, zone: ZoneId, held: Boolean): Long? = listOfNotNull(
            nextBoundary(nowMillis, settings, zone).takeIf { settings.focusEnabled || held },
            settings.nextScheduleChange(nowMillis, zone),
        ).minOrNull()
    }
}
