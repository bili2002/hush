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
 * Arms the one-shot digest alarm for the next slot boundary of any interval in use (the default,
 * a category's, a chat rule's): whichever comes first is when something can next become due. `setAndAllowWhileIdle` fires in Doze
 * (possibly a few minutes late, never early) and needs no exact-alarm permission. The alarm is
 * one-shot, so [Deliverer] re-arms it after every alarm-triggered run.
 */
@Singleton
class DeliveryScheduler @Inject constructor(
    @ApplicationContext private val ctx: Context,
    private val settings: SettingsStore,
) {
    private val alarmManager get() = ctx.getSystemService(AlarmManager::class.java)

    /** Schedules the alarm at the earliest upcoming boundary in the device zone and returns that trigger time. */
    suspend fun schedule(nowMillis: Long): Long {
        val triggerAt = nextBoundary(nowMillis, settings.current(), ZoneId.systemDefault())
        alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent())
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

        /**
         * The earliest slot boundary after [nowMillis] over the intervals the rules use. With no
         * interval in use (nothing would be held) it falls back to the default slot, so rows held
         * under earlier rules still get their retry.
         */
        fun nextBoundary(nowMillis: Long, settings: Settings, zone: ZoneId): Long =
            settings.activeThrottles().ifEmpty { setOf(settings.slotMinutes) }.minOf { nextSlot(nowMillis, it, zone) }
    }
}
