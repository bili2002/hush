package com.hush.app.ui.apps

import androidx.compose.runtime.Composable
import com.hush.app.ui.PlaceholderScreen

/** [list] is `"hold"` or `"excluded"` (see [com.hush.app.ui.Routes.appPicker]). */
@Composable
fun AppPickerScreen(list: String) {
    PlaceholderScreen("Apps: $list")
}
