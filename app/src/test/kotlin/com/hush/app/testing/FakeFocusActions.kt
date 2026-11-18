package com.hush.app.testing

import com.hush.app.focus.DeliverResult
import com.hush.app.focus.FocusActions
import com.hush.app.focus.withActiveProfile
import com.hush.app.focus.withoutManualHold
import com.hush.app.settings.Settings
import com.hush.app.settings.SettingsStore

/** Persists the toggles like the foundation default; `deliverNow` returns [nextResult] or throws [nextError]. */
class FakeFocusActions(
    private val settings: SettingsStore,
    var nextResult: DeliverResult = DeliverResult(0),
    var nextError: Throwable? = null,
    /** When set, every settings-changing action throws it instead of writing. */
    var rulesError: Throwable? = null,
) : FocusActions {
    var deliverCalls = 0
        private set

    /** How many rule edits came through [updateRules] (and were not written to the store behind its back). */
    var rulesUpdates = 0
        private set

    override suspend fun setEnabled(enabled: Boolean) = settings.update { it.copy(focusEnabled = enabled) }

    override suspend fun updateRules(edit: (Settings) -> Settings) {
        rulesError?.let { throw it }
        rulesUpdates++
        settings.update(edit)
    }

    override suspend fun selectProfile(id: String) {
        rulesError?.let { throw it }
        settings.update { it.withActiveProfile(id) }
    }

    override suspend fun resumeSchedule() {
        rulesError?.let { throw it }
        settings.update { it.withoutManualHold() }
    }

    override suspend fun deliverNow(): DeliverResult {
        deliverCalls++
        nextError?.let { throw it }
        return nextResult
    }
}
