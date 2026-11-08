package com.hush.app.focus

import android.content.Context
import com.hush.app.R
import com.hush.app.db.NotificationRepository
import com.hush.app.settings.SettingsStore
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Clock
import javax.inject.Inject

/** The UI's Focus controls; the [FocusActions] binding. */
class DefaultFocusActionsImpl @Inject constructor(
    @ApplicationContext private val ctx: Context,
    private val settings: SettingsStore,
    private val repo: NotificationRepository,
    private val deliverer: Deliverer,
    private val scheduler: DeliveryScheduler,
    private val clock: Clock,
) : FocusActions {

    override suspend fun setEnabled(enabled: Boolean) {
        if (enabled) {
            if (settings.current().holdPackages.isEmpty()) {
                val error = ctx.getString(R.string.focus_error_no_hold_apps)
                settings.update { it.copy(focusEnabled = false, lastFocusError = error) }
                return
            }
            settings.update { it.copy(focusEnabled = true, lastFocusError = null) }
            scheduler.schedule(clock.millis())
        } else {
            // Off first: the gate stops holding new rows before we flush the ones already held.
            settings.update { it.copy(focusEnabled = false) }
            val result = deliverer.deliverDue(force = true)
            if (result.error == null && repo.selectHeldUndelivered().isEmpty()) {
                scheduler.cancel()
            } else {
                // Something is still held (e.g. notifications denied): keep retrying at slot boundaries.
                scheduler.schedule(clock.millis())
            }
        }
    }

    override suspend fun setSlotMinutes(minutes: Int) {
        require(minutes > 0) { "slotMinutes must be positive, was $minutes" }
        settings.update { it.copy(slotMinutes = minutes) }
        if (settings.current().focusEnabled) scheduler.schedule(clock.millis())
    }

    override suspend fun deliverNow(): DeliverResult = deliverer.deliverDue(force = true)
}
