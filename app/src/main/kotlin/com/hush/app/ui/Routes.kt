package com.hush.app.ui

object Routes {
    const val HISTORY = "history"
    const val FOCUS = "focus"
    const val SETTINGS = "settings"

    /** "Apps & throttles": categories, intervals and chat rules. */
    const val FOCUS_RULES = "focus/rules"

    /** [list] is `"hold"` or `"excluded"`. */
    fun appPicker(list: String) = "apps/$list"

    const val APP_PICKER_PATTERN = "apps/{list}"
    const val ARG_LIST = "list"
}
