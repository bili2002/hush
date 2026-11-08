package com.hush.app.focus

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.hush.app.settings.SettingsStore
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Arms the one-shot digest alarm for the next slot boundary. `setAndAllowWhileIdle` fires in Doze
 * (possibly a few minutes late, never early) and needs no exact-alarm permission. The alarm is
 * one-shot, so [Deliverer] re-arms it after every alarm-triggered run.
 */
@Singleton
class DeliveryScheduler @Inject constructor(
    @ApplicationContext private val ctx: Context,
    private val settings: SettingsStore,
) {
    private val alarmManager get() = ctx.getSystemService(AlarmManager::class.java)

    /** Schedules the alarm at `nextSlot(nowMillis)` in the device zone and returns that trigger time. */
    suspend fun schedule(nowMillis: Long): Long {
        val triggerAt = nextSlot(nowMillis, settings.current().slotMinutes, ZoneId.systemDefault())
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

    private companion object {
        const val REQUEST_CODE = 4001
    }
}
