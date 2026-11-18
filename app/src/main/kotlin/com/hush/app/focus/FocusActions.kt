package com.hush.app.focus

import com.hush.app.settings.Settings

data class DeliverResult(val delivered: Int, val error: String? = null)

/** The UI's Focus controls. Bound to [DefaultFocusActionsImpl]. */
interface FocusActions {
    /** The switch. With a profile schedule this is a manual choice that stands until midnight. */
    suspend fun setEnabled(enabled: Boolean)

    suspend fun deliverNow(): DeliverResult

    /**
     * Applies an edit of anything that decides what is held and when: rules (groups, intervals,
     * chat rules, hold list), profiles, the schedule. It then releases whatever the edit made due,
     * lets the schedule take effect, and re-arms the alarm.
     */
    suspend fun updateRules(edit: (Settings) -> Settings)

    /** Makes [id] the active profile. With a schedule this is a manual choice that stands until midnight. */
    suspend fun selectProfile(id: String)

    /** Ends a manual choice early: the schedule decides again from now. */
    suspend fun resumeSchedule()
}
