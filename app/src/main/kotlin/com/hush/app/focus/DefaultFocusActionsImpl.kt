package com.hush.app.focus

import android.content.Context
import com.hush.app.R
import com.hush.app.settings.SettingsStore
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Clock
import javax.inject.Inject

/** The UI's Focus controls. Replaces the foundation's no-op [DefaultFocusActions] in [FocusModule]. */
class DefaultFocusActionsImpl @Inject constructor(
    @ApplicationContext private val ctx: Context,
    private val settings: SettingsStore,
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
            // Off first so the forced delivery below does not re-arm the alarm we are about to cancel.
            settings.update { it.copy(focusEnabled = false) }
            deliverer.deliverDue(force = true)
            scheduler.cancel()
        }
    }

    override suspend fun setSlotMinutes(minutes: Int) {
        require(minutes > 0) { "slotMinutes must be positive, was $minutes" }
        settings.update { it.copy(slotMinutes = minutes) }
        if (settings.current().focusEnabled) scheduler.schedule(clock.millis())
    }

    override suspend fun deliverNow(): DeliverResult = deliverer.deliverDue(force = true)
}
