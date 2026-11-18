package com.hush.app.ui.focus

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DoNotDisturbOn
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hush.app.R
import com.hush.app.focus.CategoryGroup
import com.hush.app.ui.components.HushCard
import com.hush.app.ui.components.appNotificationSettingsIntent
import com.hush.app.ui.components.findActivity
import com.hush.app.ui.components.formatClock
import com.hush.app.ui.components.formatDayClock
import com.hush.app.ui.components.relativeAgo
import com.hush.app.ui.components.startActivitySafely
import com.hush.app.ui.components.throttleEvery
import java.time.ZoneId

/** Everything the Focus screen can ask the outside world to do; stateless content takes this so previews can no-op it. */
data class FocusCallbacks(
    val onToggle: (Boolean) -> Unit = {},
    val onDeliverNow: () -> Unit = {},
    val onDismissResult: () -> Unit = {},
    val onManageRules: () -> Unit = {},
    val onSelectProfile: (String) -> Unit = {},
    /** Name, the profile to copy (null = empty), one-time. */
    val onAddProfile: (String, String?, Boolean) -> Unit = { _, _, _ -> },
    val onRenameProfile: (String, String) -> Unit = { _, _ -> },
    val onDeleteProfile: (String) -> Unit = {},
    val onEditProfile: (String) -> Unit = {},
    val onOpenSchedule: () -> Unit = {},
    val onShowMuted: () -> Unit = {},
    val onResumeSchedule: () -> Unit = {},
    val onOpenDndAccess: () -> Unit = {},
    val onOpenListenerSettings: () -> Unit = {},
    val onRequestPostPermission: () -> Unit = {},
    val onOpenBatterySettings: () -> Unit = {},
)

@Composable
fun FocusScreen(vm: FocusViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val activity = context.findActivity()
    // Set when a system settings page could not be opened on this device; shown as one line, cleared on the next attempt.
    var launchFailed by rememberSaveable { mutableStateOf(false) }
    val open: (Intent) -> Unit = { launchFailed = !context.startActivitySafely(it) }

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
            open(appNotificationSettingsIntent(context))
        }
    }

    FocusContent(
        state = state,
        zone = ZoneId.systemDefault(),
        launchFailed = launchFailed,
        callbacks = FocusCallbacks(
            onToggle = vm::setEnabled,
            onDeliverNow = vm::deliverNow,
            onDismissResult = vm::dismissDeliverResult,
            onManageRules = vm::manageRules,
            onSelectProfile = vm::selectProfile,
            onAddProfile = vm::addProfile,
            onRenameProfile = vm::renameProfile,
            onDeleteProfile = vm::deleteProfile,
            onEditProfile = vm::editProfile,
            onOpenSchedule = vm::openSchedule,
            onShowMuted = vm::showMuted,
            onResumeSchedule = vm::resumeSchedule,
            onOpenDndAccess = { open(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)) },
            onOpenListenerSettings = { open(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) },
            onRequestPostPermission = {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return@FocusCallbacks
                val perm = Manifest.permission.POST_NOTIFICATIONS
                if (ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED) {
                    vm.refreshSystemState()
                } else {
                    permissionLauncher.launch(perm)
                }
            },
            onOpenBatterySettings = { open(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) },
        ),
    )
}

@Composable
fun FocusContent(
    state: FocusUiState,
    zone: ZoneId,
    callbacks: FocusCallbacks,
    modifier: Modifier = Modifier,
    launchFailed: Boolean = false,
) {
    var dialog by rememberSaveable(stateSaver = ProfileDialogSaver) { mutableStateOf<ProfileDialog?>(null) }
    var deleting by rememberSaveable { mutableStateOf<String?>(null) }
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.focus_title), style = MaterialTheme.typography.headlineMedium)

        if (launchFailed) {
            Text(
                stringResource(R.string.focus_launch_failed),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }

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

        // The cause, and the one place it can be fixed.
        state.dndError?.let { ErrorCard(title = stringResource(R.string.focus_dnd_error, it), message = null, onClick = callbacks.onOpenDndAccess) }

        ProfilesCard(
            profiles = state.profiles,
            callbacks = callbacks,
            onNew = { copyOf -> dialog = ProfileDialog(renameId = null, name = "", copyOf = copyOf?.id) },
            onRename = { dialog = ProfileDialog(renameId = it.id, name = it.name, copyOf = null) },
            onDelete = { deleting = it.id },
        )

        RulesSummaryCard(state.groups, state.chatRuleCount, callbacks.onManageRules)

        state.checklist?.let { ChecklistCard(it, callbacks) }

        ListenerHealthLine(state.listener)
    }

    // A profile's throttles and schedule entries go with it and there is no undo: ask once.
    state.profiles.firstOrNull { it.id == deleting }?.let { profile ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.profiles_delete_confirm, profile.name)) },
            confirmButton = {
                TextButton(onClick = {
                    callbacks.onDeleteProfile(profile.id)
                    deleting = null
                }) { Text(stringResource(R.string.profiles_delete)) }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.rules_cancel)) } },
        )
    }

    dialog?.let { d ->
        ProfileNameDialog(
            dialog = d,
            profiles = state.profiles,
            onConfirm = { name, copyOf, oneTime ->
                if (d.renameId == null) callbacks.onAddProfile(name, copyOf, oneTime) else callbacks.onRenameProfile(d.renameId, name)
                dialog = null
            },
            onDismiss = { dialog = null },
        )
    }
}

/** The one dialog for "new profile" ([renameId] null; [copyOf] preselects the source) and "rename". */
private data class ProfileDialog(val renameId: String?, val name: String, val copyOf: String?)

/** Survives rotation like the text typed into the dialog does. Ids are never empty, so "" stands for null. */
private val ProfileDialogSaver = listSaver<ProfileDialog?, String>(
    save = { d -> if (d == null) emptyList() else listOf(d.renameId.orEmpty(), d.name, d.copyOf.orEmpty()) },
    restore = { l -> if (l.isEmpty()) null else ProfileDialog(l[0].ifEmpty { null }, l[1], l[2].ifEmpty { null }) },
)

@Composable
private fun ProfilesCard(
    profiles: List<ProfileRow>,
    callbacks: FocusCallbacks,
    onNew: (copyOf: ProfileRow?) -> Unit,
    onRename: (ProfileRow) -> Unit,
    onDelete: (ProfileRow) -> Unit,
) {
    HushCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(vertical = 4.dp)) {
            Row(modifier = Modifier.padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.profiles_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = callbacks.onOpenSchedule) { Text(stringResource(R.string.schedule_title)) }
            }
            profiles.forEach { profile ->
                // Keyed: each row remembers whether its menu is open, and rows come and go.
                key(profile.id) {
                    ProfileRowItem(
                        profile = profile,
                        canDelete = profiles.size > 1,
                        callbacks = callbacks,
                        onDuplicate = { onNew(profile) },
                        onRename = { onRename(profile) },
                        onDelete = { onDelete(profile) },
                    )
                }
            }
            TextButton(onClick = { onNew(null) }, modifier = Modifier.padding(start = 4.dp)) { Text(stringResource(R.string.profiles_new)) }
        }
    }
}

@Composable
private fun ProfileRowItem(
    profile: ProfileRow,
    canDelete: Boolean,
    callbacks: FocusCallbacks,
    onDuplicate: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = profile.active, role = Role.RadioButton) { callbacks.onSelectProfile(profile.id) }
            .padding(start = 16.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = profile.active, onClick = null)
        Spacer(Modifier.width(12.dp))
        Text(profile.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        if (profile.oneTime) {
            Text(
                stringResource(R.string.profiles_one_time),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
        if (profile.dnd) {
            Icon(
                Icons.Filled.DoNotDisturbOn,
                contentDescription = stringResource(R.string.profiles_dnd),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 8.dp).size(18.dp),
            )
        }
        Box {
            IconButton(onClick = { open = true }) {
                Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.profiles_options, profile.name))
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                @Composable
                fun item(label: Int, enabled: Boolean = true, action: () -> Unit) = DropdownMenuItem(
                    text = { Text(stringResource(label)) },
                    enabled = enabled,
                    onClick = {
                        open = false
                        action()
                    },
                )
                item(R.string.profiles_edit) { callbacks.onEditProfile(profile.id) }
                item(R.string.profiles_rename, action = onRename)
                item(R.string.profiles_duplicate, action = onDuplicate)
                item(R.string.profiles_delete, enabled = canDelete, action = onDelete)
            }
        }
    }
}

@Composable
private fun ProfileNameDialog(
    dialog: ProfileDialog,
    profiles: List<ProfileRow>,
    onConfirm: (name: String, copyOf: String?, oneTime: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val creating = dialog.renameId == null
    val copyName = profiles.firstOrNull { it.id == dialog.copyOf }?.let { stringResource(R.string.profiles_copy_name, it.name) }
    var name by rememberSaveable(dialog) { mutableStateOf(dialog.name.ifEmpty { copyName.orEmpty() }) }
    var copyOf by rememberSaveable(dialog) { mutableStateOf(dialog.copyOf) }
    var oneTime by rememberSaveable(dialog) { mutableStateOf(false) }
    var sourceOpen by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (creating) R.string.profiles_new else R.string.profiles_rename)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.profiles_name)) },
                )
                if (creating) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.profiles_copy_of), modifier = Modifier.weight(1f))
                        Box {
                            AssistChip(
                                onClick = { sourceOpen = true },
                                label = { Text(profiles.firstOrNull { it.id == copyOf }?.name ?: stringResource(R.string.profiles_copy_none), maxLines = 1) },
                                trailingIcon = { Icon(Icons.Filled.ArrowDropDown, contentDescription = null) },
                            )
                            DropdownMenu(expanded = sourceOpen, onDismissRequest = { sourceOpen = false }) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.profiles_copy_none)) },
                                    onClick = {
                                        sourceOpen = false
                                        copyOf = null
                                    },
                                )
                                profiles.forEach { p ->
                                    DropdownMenuItem(
                                        text = { Text(p.name) },
                                        onClick = {
                                            sourceOpen = false
                                            copyOf = p.id
                                        },
                                    )
                                }
                            }
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth().toggleable(value = oneTime, role = Role.Checkbox) { oneTime = it },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(stringResource(R.string.profiles_one_time), modifier = Modifier.weight(1f))
                        Checkbox(checked = oneTime, onCheckedChange = null)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name, copyOf, oneTime) }, enabled = name.isNotBlank()) { Text(stringResource(R.string.rules_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.rules_cancel)) } },
    )
}

@Composable
private fun ToggleCard(state: FocusUiState, zone: ZoneId, callbacks: FocusCallbacks) {
    HushCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.focus_toggle_label), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                // Turning OFF is always possible; only OFF → ON is gated.
                Switch(checked = state.enabled, onCheckedChange = callbacks.onToggle, enabled = state.switchEnabled)
            }

            state.schedule?.let { ScheduleLine(it, zone, callbacks.onResumeSchedule) }

            if (state.mutedCount > 0) {
                TextButton(onClick = callbacks.onShowMuted, contentPadding = PaddingValues(horizontal = 8.dp)) {
                    Text(stringResource(R.string.focus_muted, state.mutedCount))
                }
            }

            if (state.enabled) {
                if (state.onButGated) {
                    Text(
                        stringResource(R.string.focus_on_but_gated),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                } else {
                    state.nextSlotAt?.let { at ->
                        Text(
                            stringResource(R.string.focus_next_digest, formatClock(at, zone), state.heldCount),
                            style = MaterialTheme.typography.titleMedium,
                        )
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
private fun ScheduleLine(status: ScheduleStatus, zone: ZoneId, onResume: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            when (status) {
                is ScheduleStatus.Manual -> stringResource(R.string.schedule_manual)
                is ScheduleStatus.Following ->
                    status.nextChangeAt?.let { stringResource(R.string.schedule_following, formatDayClock(it, zone)) }
                        ?: stringResource(R.string.schedule_following_idle)
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        if (status is ScheduleStatus.Manual) TextButton(onClick = onResume) { Text(stringResource(R.string.schedule_resume)) }
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
                    GateReason.NOTHING_HELD -> R.string.focus_gate_hold_list to callbacks.onManageRules
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(role = Role.Button, onClick = action)
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Filled.ErrorOutline, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.width(12.dp))
                    Text(stringResource(label), modifier = Modifier.weight(1f))
                    // The row is the fix; the arrow is what says so now that the words "tap to …" are gone.
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
                }
            }
        }
    }
}

/** [onClick]: the card leads to where the error is fixed, and says so with an arrow. */
@Composable
private fun ErrorCard(title: String, message: String?, onClick: (() -> Unit)? = null) {
    val colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
    val content: @Composable () -> Unit = {
        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                if (message != null) Text(message, style = MaterialTheme.typography.bodyMedium)
            }
            if (onClick != null) Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
        }
    }
    if (onClick == null) {
        Card(modifier = Modifier.fillMaxWidth(), colors = colors) { content() }
    } else {
        Card(onClick = onClick, modifier = Modifier.fillMaxWidth(), colors = colors) { content() }
    }
}

/** Who is held and how often, at a glance; the whole card opens "Apps & throttles". */
@Composable
private fun RulesSummaryCard(groups: List<CategoryGroup>, chatRuleCount: Int, onManage: () -> Unit) {
    // One click target: the card. The label on the right is text, not a second button doing the same thing.
    HushCard(onClick = onManage, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.focus_rules_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Text(
                    stringResource(R.string.focus_rules_manage),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            if (groups.isEmpty()) {
                Text(
                    stringResource(R.string.focus_rules_none),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            groups.forEach { group ->
                Column {
                    Text(
                        stringResource(
                            R.string.focus_rules_group,
                            group.name ?: stringResource(R.string.rules_uncategorised),
                            throttleEvery(group.effectiveMinutes),
                        ),
                        style = MaterialTheme.typography.labelLarge,
                    )
                    Text(
                        group.apps.joinToString(", ") { it.label },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (chatRuleCount > 0) {
                Text(
                    pluralStringResource(R.plurals.focus_rules_chat_rules, chatRuleCount, chatRuleCount),
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
    }
}

@Composable
private fun ChecklistCard(checklist: Checklist, callbacks: FocusCallbacks) {
    HushCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(vertical = 12.dp)) {
            Text(
                stringResource(R.string.focus_checklist_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
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
                done = checklist.holdsSomething,
                label = stringResource(R.string.focus_checklist_hold_list),
                onClick = callbacks.onManageRules,
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
