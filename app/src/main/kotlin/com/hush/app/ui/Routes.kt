package com.hush.app.ui

object Routes {
    const val HISTORY = "history"
    const val FOCUS = "focus"
    const val SETTINGS = "settings"

    /** "Apps & throttles" for the active profile: groups, intervals and chat rules. */
    const val FOCUS_RULES = "focus/rules"

    /** The same screen for one profile, active or not. Profile ids are "default" or a UUID: nothing to escape. */
    fun focusRules(profileId: String) = "$FOCUS_RULES?$ARG_PROFILE=$profileId"

    const val FOCUS_RULES_PATTERN = "$FOCUS_RULES?profile={profile}"
    const val ARG_PROFILE = "profile"

    /** The weekly schedule of profiles. */
    const val FOCUS_SCHEDULE = "focus/schedule"

    /** [list] is `"hold"` or `"excluded"`. */
    fun appPicker(list: String) = "apps/$list"

    const val APP_PICKER_PATTERN = "apps/{list}"
    const val ARG_LIST = "list"
}
