package com.hush.app.focus

import com.hush.app.settings.Settings

data class DeliverResult(val delivered: Int, val error: String? = null)

/** The UI's Focus controls. Bound to [DefaultFocusActionsImpl]. */
interface FocusActions {
    suspend fun setEnabled(enabled: Boolean)
    suspend fun setSlotMinutes(minutes: Int)
    suspend fun deliverNow(): DeliverResult

    /**
     * Applies an edit of the hold rules (categories, intervals, chat rules, hold list). While Focus
     * is on it then releases whatever the new rules make due and re-arms the alarm for the new
     * set of intervals.
     */
    suspend fun updateRules(edit: (Settings) -> Settings)
}
