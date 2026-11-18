package com.hush.app.focus

import android.content.Context
import com.hush.app.R
import com.hush.app.settings.Settings
import com.hush.app.settings.SettingsStore
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Clock
import java.time.ZoneId
import javax.inject.Inject
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * The UI's Focus controls; the [FocusActions] binding. Every action is "write the settings, then
 * one delivery run": the run applies the schedule, releases what became due and re-arms (or
 * clears) the alarm, so no action has to know about the alarm itself.
 */
class DefaultFocusActionsImpl @Inject constructor(
    @ApplicationContext private val ctx: Context,
    private val settings: SettingsStore,
    private val deliverer: Deliverer,
    private val clock: Clock,
) : FocusActions {

    override suspend fun setEnabled(enabled: Boolean) {
        if (enabled && !settings.current().holdsAnything) {
            val error = ctx.getString(R.string.focus_error_no_hold_apps)
            settings.update { it.copy(focusEnabled = false, lastFocusError = error) }
            return
        }
        val now = clock.millis()
        // Off: the gate stops holding new rows before the forced run flushes the ones already held. If the
        // flush is blocked (notifications denied) the rows stay held and the run leaves the alarm armed to retry.
        apply(force = !enabled) {
            it.copy(focusEnabled = enabled, lastFocusError = if (enabled) null else it.lastFocusError).withManualHold(now, ZoneId.systemDefault())
        }
    }

    override suspend fun updateRules(edit: (Settings) -> Settings) = apply(force = false, edit)

    override suspend fun selectProfile(id: String) {
        val now = clock.millis()
        apply(force = false) { it.withActiveProfile(id).withManualHold(now, ZoneId.systemDefault()) }
    }

    override suspend fun resumeSchedule() = apply(force = false) { it.withoutManualHold() }

    override suspend fun deliverNow(): DeliverResult = deliverer.deliverDue(force = true)

    private suspend fun apply(force: Boolean, edit: (Settings) -> Settings) {
        // Callers are screens that may be left right after the tap. Saved settings without the run would
        // leave the alarm on the old intervals, so the two steps are not separable by a cancellation.
        withContext(NonCancellable) {
            settings.update(edit)
            deliverer.deliverDue(force)
        }
    }
}
