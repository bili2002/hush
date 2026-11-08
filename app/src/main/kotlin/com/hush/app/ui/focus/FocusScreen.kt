package com.hush.app.ui.focus

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hush.app.R
import com.hush.app.ui.components.formatClock
import com.hush.app.ui.components.relativeAgo
import java.time.ZoneId

/** Everything the Focus screen can ask the outside world to do; stateless content takes this so previews can no-op it. */
data class FocusCallbacks(
    val onToggle: (Boolean) -> Unit = {},
    val onSlot: (Int) -> Unit = {},
    val onDeliverNow: () -> Unit = {},
    val onDismissResult: () -> Unit = {},
    val onEditHoldList: () -> Unit = {},
    val onOpenListenerSettings: () -> Unit = {},
    val onRequestPostPermission: () -> Unit = {},
    val onOpenBatterySettings: () -> Unit = {},
)

@Composable
fun FocusScreen(vm: FocusViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val activity = context.findActivity()

    LifecycleResumeEffect(Unit) {
        vm.refreshSystemState()
        onPauseOrDispose { }
    }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        vm.refreshSystemState()
        // Denied without a dialog = "don't ask again" (or a fresh deny with the rationale window closed):
        // the only way forward is the app's notification settings page.
        if (!granted && activity != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.POST_NOTIFICATIONS)
        ) {
            context.startActivitySafely(appNotificationSettingsIntent(context))
        }
    }

    FocusContent(
        state = state,
        zone = ZoneId.systemDefault(),
        callbacks = FocusCallbacks(
            onToggle = vm::setEnabled,
            onSlot = vm::setSlotMinutes,
            onDeliverNow = vm::deliverNow,
            onDismissResult = vm::dismissDeliverResult,
            onEditHoldList = vm::editHoldList,
            onOpenListenerSettings = { context.startActivitySafely(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) },
            onRequestPostPermission = {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return@FocusCallbacks
                val perm = Manifest.permission.POST_NOTIFICATIONS
                if (ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED) {
                    vm.refreshSystemState()
                } else {
                    permissionLauncher.launch(perm)
                }
            },
            onOpenBatterySettings = { context.startActivitySafely(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) },
        ),
    )
}

@Composable
fun FocusContent(state: FocusUiState, zone: ZoneId, callbacks: FocusCallbacks, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.focus_title), style = MaterialTheme.typography.headlineMedium)

        if (state.loading) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(12.dp))
                Text(stringResource(R.string.history_loading))
            }
            return@Column
        }

        ToggleCard(state, zone, callbacks)

        if (state.gateReasons.isNotEmpty()) GateCard(state.gateReasons, callbacks)

        state.lastFocusError?.let { ErrorCard(title = stringResource(R.string.focus_error_title), message = it) }

        HoldListRow(state.holdLabels, callbacks.onEditHoldList)

        state.checklist?.let { ChecklistCard(it, callbacks) }

        ListenerHealthLine(state.listener)
    }
}

@Composable
private fun ToggleCard(state: FocusUiState, zone: ZoneId, callbacks: FocusCallbacks) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.focus_toggle_label), style = MaterialTheme.typography.titleLarge)
                    Text(
                        if (state.enabled) stringResource(R.string.focus_toggle_on_summary) else stringResource(R.string.focus_toggle_off_summary),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = state.enabled, onCheckedChange = callbacks.onToggle, enabled = state.canToggle)
            }

            if (state.enabled) {
                state.nextSlotAt?.let { at ->
                    Text(
                        stringResource(R.string.focus_next_digest, formatClock(at, zone), state.heldCount),
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
                Text(stringResource(R.string.focus_slot_label), style = MaterialTheme.typography.labelLarge)
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    SLOT_CHOICES.forEachIndexed { index, minutes ->
                        SegmentedButton(
                            selected = state.slotMinutes == minutes,
                            onClick = { callbacks.onSlot(minutes) },
                            shape = SegmentedButtonDefaults.itemShape(index = index, count = SLOT_CHOICES.size),
                        ) { Text(stringResource(R.string.focus_slot_minutes, minutes)) }
                    }
                }
                Button(onClick = callbacks.onDeliverNow, enabled = !state.delivering, modifier = Modifier.fillMaxWidth()) {
                    Text(if (state.delivering) stringResource(R.string.focus_delivering) else stringResource(R.string.focus_deliver_now))
                }
                state.deliverResult?.let { result ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val error = result.error
                        Text(
                            if (error == null) stringResource(R.string.focus_delivered, result.delivered)
                            else stringResource(R.string.focus_deliver_failed, error),
                            modifier = Modifier.weight(1f),
                            color = if (error == null) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error,
                        )
                        TextButton(onClick = callbacks.onDismissResult) { Text(stringResource(R.string.focus_dismiss)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun GateCard(reasons: List<GateReason>, callbacks: FocusCallbacks) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Column(modifier = Modifier.padding(vertical = 8.dp)) {
            Text(
                stringResource(R.string.focus_gate_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            reasons.forEach { reason ->
                val (label, action) = when (reason) {
                    GateReason.NO_LISTENER_ACCESS -> R.string.focus_gate_listener to callbacks.onOpenListenerSettings
                    GateReason.NO_POST_PERMISSION -> R.string.focus_gate_post to callbacks.onRequestPostPermission
                    GateReason.EMPTY_HOLD_LIST -> R.string.focus_gate_hold_list to callbacks.onEditHoldList
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = action)
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Filled.ErrorOutline, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.width(12.dp))
                    Text(stringResource(label))
                }
            }
        }
    }
}

@Composable
private fun ErrorCard(title: String, message: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(message, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun HoldListRow(labels: List<String>, onEdit: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            if (labels.isEmpty()) stringResource(R.string.focus_holding_none)
            else stringResource(R.string.focus_holding, labels.joinToString(", ")),
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyLarge,
        )
        TextButton(onClick = onEdit) { Text(stringResource(R.string.focus_edit)) }
    }
}

@Composable
private fun ChecklistCard(checklist: Checklist, callbacks: FocusCallbacks) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(vertical = 12.dp)) {
            Text(
                stringResource(R.string.focus_checklist_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            Text(
                stringResource(R.string.focus_checklist_intro),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            HorizontalDivider()
            ChecklistRow(
                done = checklist.listenerAccess,
                label = stringResource(R.string.focus_checklist_listener),
                hint = if (checklist.listenerAccess) null else stringResource(R.string.focus_checklist_listener_hint),
                onClick = callbacks.onOpenListenerSettings,
            )
            ChecklistRow(
                done = checklist.postPermission,
                label = stringResource(R.string.focus_checklist_post),
                onClick = callbacks.onRequestPostPermission,
            )
            ChecklistRow(
                done = checklist.batteryExempt,
                label = stringResource(R.string.focus_checklist_battery),
                onClick = callbacks.onOpenBatterySettings,
            )
            ChecklistRow(
                done = checklist.holdListNonEmpty,
                label = stringResource(R.string.focus_checklist_hold_list),
                onClick = callbacks.onEditHoldList,
            )
        }
    }
}

private val DoneGreen = Color(0xFF2E7D32)

@Composable
private fun ChecklistRow(done: Boolean, label: String, onClick: () -> Unit, hint: String? = null) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !done, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (done) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked,
            contentDescription = if (done) stringResource(R.string.focus_checklist_done) else stringResource(R.string.focus_checklist_todo),
            tint = if (done) DoneGreen else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(12.dp))
        Column {
            Text(label, color = if (done) DoneGreen else MaterialTheme.colorScheme.onSurface)
            if (hint != null) {
                Spacer(Modifier.height(2.dp))
                Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun ListenerHealthLine(health: ListenerHealth) {
    when (health) {
        is ListenerHealth.Connected -> Text(
            stringResource(R.string.focus_listener_connected, relativeAgo(health.agoMillis)),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        ListenerHealth.Disconnected -> Text(
            stringResource(R.string.focus_listener_disconnected),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
        )
    }
}

internal fun Context.findActivity(): Activity? {
    var ctx: Context = this
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}

internal fun Context.startActivitySafely(intent: Intent) {
    runCatching { startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

/** The app's own notification settings page (minSdk 26, so the O+ action is always available). */
internal fun appNotificationSettingsIntent(context: Context): Intent =
    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
