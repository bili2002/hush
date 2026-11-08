package com.hush.app.focus

data class DeliverResult(val delivered: Int, val error: String? = null)

/** The UI's Focus controls. Bound to [DefaultFocusActionsImpl]. */
interface FocusActions {
    suspend fun setEnabled(enabled: Boolean)
    suspend fun setSlotMinutes(minutes: Int)
    suspend fun deliverNow(): DeliverResult
}
