package com.hush.app.ui.rules

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.hush.app.R
import com.hush.app.focus.INSTANT
import com.hush.app.ui.components.throttleEvery

/**
 * What the chat-throttle dialog shows for one chat. [ruleMinutes] is the chat's own rule (null =
 * none, it follows the app); [appMinutes] is what the app gives it (0 = the app is not held).
 */
data class ChatThrottle(
    val packageName: String,
    val chatKey: String,
    val title: String,
    val appLabel: String,
    val ruleMinutes: Int?,
    val appMinutes: Int,
)

/**
 * One chat's own throttle: follow the app, never hold, an interval, or muted. Picking applies and
 * closes. Shared by History ("Throttle this chat") and the rules screen ("Add chat rule").
 */
@Composable
fun ChatThrottleDialog(dialog: ChatThrottle, onPick: (Int?) -> Unit, onDismiss: () -> Unit) {
    // "Custom…" swaps this dialog for the entry dialog; cancelling that comes back to the list.
    var custom by rememberSaveable { mutableStateOf(false) }
    if (custom) {
        CustomThrottleDialog(initial = dialog.ruleMinutes, onConfirm = onPick, onDismiss = { custom = false })
        return
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.history_throttle_title)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    stringResource(R.string.history_throttle_subject, dialog.title, dialog.appLabel),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                ThrottleOptions(
                    selected = dialog.ruleMinutes,
                    inheritLabel = stringResource(R.string.rules_same_as_app, throttleEvery(dialog.appMinutes)),
                    // For a chat of an app that is not held, "Instantly" would be a rule that changes nothing.
                    offerInstant = dialog.appMinutes != INSTANT || dialog.ruleMinutes == INSTANT,
                    onCustom = { custom = true },
                    onPick = onPick,
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.rules_cancel)) } },
    )
}
