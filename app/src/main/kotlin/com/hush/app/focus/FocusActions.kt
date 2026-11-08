package com.hush.app.focus

import com.hush.app.settings.SettingsStore
import javax.inject.Inject

data class DeliverResult(val delivered: Int, val error: String? = null)

interface FocusActions {
    suspend fun setEnabled(enabled: Boolean)
    suspend fun setSlotMinutes(minutes: Int)
    suspend fun deliverNow(): DeliverResult
}

/** Foundation default: persists the toggles, delivers nothing. The Focus issue replaces it in [FocusModule]. */
class DefaultFocusActions @Inject constructor(private val settings: SettingsStore) : FocusActions {
    override suspend fun setEnabled(enabled: Boolean) = settings.update { it.copy(focusEnabled = enabled) }

    override suspend fun setSlotMinutes(minutes: Int) = settings.update { it.copy(slotMinutes = minutes) }

    override suspend fun deliverNow(): DeliverResult = DeliverResult(0)
}
