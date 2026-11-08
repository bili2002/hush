package com.hush.app.testing

import com.hush.app.focus.DeliverResult
import com.hush.app.focus.FocusActions
import com.hush.app.settings.SettingsStore

/** Persists the toggles like the foundation default; `deliverNow` returns [nextResult] or throws [nextError]. */
class FakeFocusActions(
    private val settings: SettingsStore,
    var nextResult: DeliverResult = DeliverResult(0),
    var nextError: Throwable? = null,
) : FocusActions {
    var deliverCalls = 0
        private set

    override suspend fun setEnabled(enabled: Boolean) = settings.update { it.copy(focusEnabled = enabled) }

    override suspend fun setSlotMinutes(minutes: Int) = settings.update { it.copy(slotMinutes = minutes) }

    override suspend fun deliverNow(): DeliverResult {
        deliverCalls++
        nextError?.let { throw it }
        return nextResult
    }
}
