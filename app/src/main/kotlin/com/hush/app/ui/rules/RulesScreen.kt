package com.hush.app.ui.rules

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hush.app.R
import com.hush.app.focus.CategoryGroup
import com.hush.app.focus.ChatRule
import com.hush.app.focus.HeldApp
import com.hush.app.focus.INSTANT
import com.hush.app.focus.MUTED
import com.hush.app.focus.THROTTLE_CHOICES
import com.hush.app.focus.THROTTLE_RANGE
import com.hush.app.focus.customThrottle
import com.hush.app.ui.HushTheme
import com.hush.app.ui.components.AppIcon
import com.hush.app.ui.components.HushCard
import com.hush.app.ui.components.PickerSheet
import com.hush.app.ui.components.SheetItem
import com.hush.app.ui.components.throttleEvery
import com.hush.app.ui.components.throttleLabel

/** Everything the rules screen can ask for; previews no-op it. */
data class RulesCallbacks(
    val onBack: () -> Unit = {},
    val onDefault: (Int) -> Unit = {},
    val onDnd: (Boolean) -> Unit = {},
    val onCategoryMinutes: (String, Int?) -> Unit = { _, _ -> },
    val onAddCategory: (String) -> Unit = {},
    val onRenameCategory: (String, String) -> Unit = { _, _ -> },
    val onDeleteCategory: (String) -> Unit = {},
    val onMoveApp: (String, String?) -> Unit = { _, _ -> },
    val onStopHolding: (String) -> Unit = {},
    val onChatRuleMinutes: (ChatRule, Int) -> Unit = { _, _ -> },
    val onRemoveChatRule: (ChatRule) -> Unit = {},
    val onAddApps: () -> Unit = {},
    val onAutoSort: () -> Unit = {},
    /** Null = start from the app list. */
    val onAddChatRule: (String?) -> Unit = {},
    /** The step the row was shown in (null = the app list), and the row. */
    val onPick: (String?, SheetItem) -> Unit = { _, _ -> },
    val onDismissPicker: () -> Unit = {},
    val onChatThrottle: (Int?) -> Unit = {},
    val onDismissChatThrottle: () -> Unit = {},
    val onDismissError: () -> Unit = {},
    val onRetrySeed: () -> Unit = {},
)

@Composable
fun RulesScreen(onBack: () -> Unit, vm: RulesViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    // The profile was deleted under the screen: there is nothing left here to edit.
    LaunchedEffect(state.gone) { if (state.gone) onBack() }
    RulesContent(
        state = state,
        callbacks = RulesCallbacks(
            onBack = onBack,
            onDefault = vm::setDefaultMinutes,
            onDnd = vm::setDnd,
            onCategoryMinutes = vm::setCategoryMinutes,
            onAddCategory = vm::addCategory,
            onRenameCategory = vm::renameCategory,
            onDeleteCategory = vm::deleteCategory,
            onMoveApp = vm::moveApp,
            onStopHolding = vm::stopHolding,
            onChatRuleMinutes = vm::setChatRuleMinutes,
            onRemoveChatRule = vm::removeChatRule,
            onAddApps = vm::addApps,
            onAutoSort = vm::autoSort,
            onAddChatRule = vm::addChatRule,
            onPick = vm::pick,
            onDismissPicker = vm::dismissChatPicker,
            onChatThrottle = vm::setChatThrottle,
            onDismissChatThrottle = vm::dismissChatThrottle,
            onDismissError = vm::dismissError,
            onRetrySeed = vm::seed,
        ),
    )
}

/** The name dialog is used for "new group" ([id] null) and "rename". */
private data class NameDialog(val id: String?, val initial: String)

/** Survives rotation like the text typed into the dialog does. */
private val NameDialogSaver = listSaver<NameDialog?, String>(
    save = { d -> if (d == null) emptyList() else listOf(d.id ?: "", d.initial, if (d.id == null) "new" else "rename") },
    restore = { l -> if (l.isEmpty()) null else NameDialog(l[0].takeIf { l[2] == "rename" }, l[1]) },
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun RulesContent(state: RulesUiState, callbacks: RulesCallbacks) {
    var nameDialog by rememberSaveable(stateSaver = NameDialogSaver) { mutableStateOf<NameDialog?>(null) }
    // The shell's Scaffold already consumed the status-bar inset; zero both so the bar is not padded twice.
    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                windowInsets = WindowInsets(0),
                title = {
                    Text(
                        state.profileName?.let { stringResource(R.string.rules_title_profile, it) } ?: stringResource(R.string.rules_title),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = callbacks.onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.apps_back))
                    }
                },
            )
        },
    ) { padding ->
        if (state.loading) {
            Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    // Normally a blink while the preset groups are created. If that failed, say why and offer the retry.
                    val error = state.error
                    if (error == null) {
                        CircularProgressIndicator()
                    } else {
                        Text(
                            stringResource(R.string.rules_error, error),
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(horizontal = 24.dp),
                        )
                        Button(onClick = callbacks.onRetrySeed) { Text(stringResource(R.string.rules_retry)) }
                    }
                }
            }
            return@Scaffold
        }
        val chatRuleCounts = remember(state.chatRules) { state.chatRules.groupingBy { it.rule.packageName }.eachCount() }
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            state.error?.let { message ->
                item(key = "error") { ErrorLine(message, callbacks.onDismissError) }
            }
            item(key = "default") { DefaultCard(state.defaultMinutes, state.dnd, callbacks.onDefault, callbacks.onDnd) }
            // Above the groups: below them, a screen full of apps kept the chat rules out of sight.
            item(key = "chats") { ChatRulesCard(state.chatRules, callbacks) }
            items(state.groups, key = { "group-${it.id}" }) { group ->
                GroupCard(
                    group = group,
                    chatRuleCounts = chatRuleCounts,
                    defaultMinutes = state.defaultMinutes,
                    categories = state.groups.filter { it.id != null },
                    callbacks = callbacks,
                    onRename = { nameDialog = NameDialog(group.id, group.name.orEmpty()) },
                )
            }
            item(key = "actions") {
                Column {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Button(onClick = callbacks.onAddApps) { Text(stringResource(R.string.rules_add_apps)) }
                        OutlinedButton(onClick = { nameDialog = NameDialog(null, "") }) { Text(stringResource(R.string.rules_new_category)) }
                        OutlinedButton(onClick = callbacks.onAutoSort) { Text(stringResource(R.string.rules_auto_sort)) }
                    }
                    state.autoSorted?.let { added ->
                        Text(
                            if (added == 0) stringResource(R.string.rules_auto_sorted_none)
                            else pluralStringResource(R.plurals.rules_auto_sorted, added, added),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }

    state.chatPicker?.let { picker ->
        // Keyed by step, so the search typed for the app list does not carry over to the chat list.
        key(picker.packageName) {
            PickerSheet(
                title = if (picker.packageName == null) stringResource(R.string.rules_pick_app)
                else stringResource(R.string.history_sheet_chats_title, picker.appLabel.orEmpty()),
                allLabel = null,
                searchHint = stringResource(if (picker.packageName == null) R.string.history_sheet_search_apps else R.string.history_sheet_search_chats),
                items = picker.items,
                selectedKey = null,
                emptyLabel = stringResource(if (picker.packageName == null) R.string.history_sheet_no_matches else R.string.rules_no_chats),
                onPick = { key -> picker.items.firstOrNull { it.key == key }?.let { callbacks.onPick(picker.packageName, it) } },
                onDismiss = callbacks.onDismissPicker,
            )
        }
    }
    state.chatThrottle?.let { ChatThrottleDialog(it, onPick = callbacks.onChatThrottle, onDismiss = callbacks.onDismissChatThrottle) }

    nameDialog?.let { dialog ->
        NameCategoryDialog(
            dialog = dialog,
            onConfirm = { name ->
                if (dialog.id == null) callbacks.onAddCategory(name) else callbacks.onRenameCategory(dialog.id, name)
                nameDialog = null
            },
            onDismiss = { nameDialog = null },
        )
    }
}

@Composable
private fun ErrorLine(message: String, onDismiss: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer), modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.rules_error, message), modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.focus_dismiss)) }
        }
    }
}

@Composable
private fun DefaultCard(minutes: Int, dnd: Boolean, onPick: (Int) -> Unit, onDnd: (Boolean) -> Unit) {
    HushCard(modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.padding(start = 16.dp, end = 12.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.rules_default_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            // The same chip as every other throttle on this screen. The default has nothing to inherit from, and
            // cannot be "Muted": that would hide every held app that has no throttle of its own.
            ThrottleChip(minutes = minutes, inheritLabel = null, offerMuted = false, onPick = { it?.let(onPick) })
        }
        HorizontalDivider()
        // One click target: the row toggles, the switch only shows the state.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .toggleable(value = dnd, role = Role.Switch, onValueChange = onDnd)
                .padding(start = 16.dp, end = 12.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.rules_dnd), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Switch(checked = dnd, onCheckedChange = null)
        }
    }
}

@Composable
private fun GroupCard(
    group: CategoryGroup,
    /** Package → how many chat rules it has, for the note under an app's name. */
    chatRuleCounts: Map<String, Int>,
    defaultMinutes: Int,
    categories: List<CategoryGroup>,
    callbacks: RulesCallbacks,
    onRename: () -> Unit,
) {
    val id = group.id
    HushCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(vertical = 8.dp)) {
            Row(modifier = Modifier.padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    group.name ?: stringResource(R.string.rules_uncategorised),
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (id != null) {
                    ThrottleChip(
                        minutes = group.minutes,
                        inheritLabel = stringResource(R.string.rules_use_default, throttleLabel(defaultMinutes)),
                        onPick = { callbacks.onCategoryMinutes(id, it) },
                    )
                    CategoryMenu(onRename = onRename, onDelete = { callbacks.onDeleteCategory(id) })
                } else {
                    Text(
                        throttleEvery(defaultMinutes),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(end = 12.dp, top = 12.dp, bottom = 12.dp),
                    )
                }
            }
            if (group.apps.isEmpty()) {
                Text(
                    stringResource(R.string.rules_no_apps),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            } else {
                HorizontalDivider(modifier = Modifier.padding(top = 4.dp))
                group.apps.forEach { app ->
                    // Keyed: each row remembers whether its menu is open, and rows move when an app changes group.
                    key(app.packageName) {
                        AppRow(
                            app = app,
                            chatRules = chatRuleCounts[app.packageName] ?: 0,
                            currentCategory = id,
                            categories = categories,
                            callbacks = callbacks,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CategoryMenu(onRename: () -> Unit, onDelete: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.rules_category_menu))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.rules_rename)) },
                onClick = {
                    open = false
                    onRename()
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.rules_delete_category)) },
                onClick = {
                    open = false
                    onDelete()
                },
            )
        }
    }
}

@Composable
private fun AppRow(app: HeldApp, chatRules: Int, currentCategory: String?, categories: List<CategoryGroup>, callbacks: RulesCallbacks) {
    var open by remember { mutableStateOf(false) }
    // One click target per row (the whole row); the trailing icon is only the visual hint for the menu.
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClickLabel = stringResource(R.string.rules_app_options, app.label), role = Role.Button) { open = true }
            .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppIcon(packageName = app.packageName, size = 32.dp)
        Spacer(Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(app.label, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (chatRules > 0) {
                Text(
                    pluralStringResource(R.plurals.focus_rules_chat_rules, chatRules, chatRules),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Box {
            Icon(Icons.Filled.MoreVert, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                categories.filter { it.id != currentCategory }.forEach { target ->
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.rules_move_to, target.name.orEmpty())) },
                        onClick = {
                            open = false
                            callbacks.onMoveApp(app.packageName, target.id)
                        },
                    )
                }
                if (currentCategory != null) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.rules_move_to_none)) },
                        onClick = {
                            open = false
                            callbacks.onMoveApp(app.packageName, null)
                        },
                    )
                }
                HorizontalDivider()
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.rules_chat_rule_for)) },
                    onClick = {
                        open = false
                        callbacks.onAddChatRule(app.packageName)
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.rules_stop_holding)) },
                    onClick = {
                        open = false
                        callbacks.onStopHolding(app.packageName)
                    },
                )
            }
        }
    }
}

/** Every chat rule of the profile in one card: the header adds one, each row changes or (with "Same as app") removes one. */
@Composable
private fun ChatRulesCard(rules: List<ChatRuleRow>, callbacks: RulesCallbacks) {
    HushCard(modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.rules_chats_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = { callbacks.onAddChatRule(null) }) { Text(stringResource(R.string.rules_add_chat)) }
        }
        if (rules.isNotEmpty()) HorizontalDivider()
        rules.forEach { row ->
            // Keyed: each row remembers whether its menu or custom dialog is open, and rows come and go.
            key(row.rule.packageName, row.rule.chatKey) { ChatRuleRowItem(row, callbacks) }
        }
    }
}

@Composable
private fun ChatRuleRowItem(row: ChatRuleRow, callbacks: RulesCallbacks) {
    Row(modifier = Modifier.padding(start = 16.dp, end = 12.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        AppIcon(packageName = row.rule.packageName, size = 32.dp)
        Spacer(Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(row.rule.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                row.appLabel,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        ThrottleChip(
            minutes = row.rule.minutes,
            inheritLabel = stringResource(R.string.rules_same_as_app, throttleEvery(row.appMinutes)),
            onPick = { picked -> if (picked == null) callbacks.onRemoveChatRule(row.rule) else callbacks.onChatRuleMinutes(row.rule, picked) },
        )
    }
}

/**
 * The one throttle picker: a chip showing the current choice that drops down "inherit" (null),
 * "Instantly", the intervals and "Muted". [minutes] null = inheriting; [inheritLabel] null =
 * nothing to inherit from, so that option is not offered. [offerMuted] false leaves "Muted" out
 * (the default interval). "Custom…" opens [CustomThrottleDialog].
 */
@Composable
fun ThrottleChip(minutes: Int?, inheritLabel: String?, offerMuted: Boolean = true, onPick: (Int?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    var custom by rememberSaveable { mutableStateOf(false) }
    if (custom) {
        CustomThrottleDialog(
            initial = minutes,
            onConfirm = {
                custom = false
                onPick(it)
            },
            onDismiss = { custom = false },
        )
    }
    Box {
        AssistChip(
            onClick = { open = true },
            label = { Text(if (minutes == null) inheritLabel.orEmpty() else throttleLabel(minutes), maxLines = 1) },
            trailingIcon = { Icon(Icons.Filled.ArrowDropDown, contentDescription = null) },
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            ThrottleOptions(
                selected = minutes,
                inheritLabel = inheritLabel,
                offerMuted = offerMuted,
                onCustom = {
                    open = false
                    custom = true
                },
            ) {
                open = false
                onPick(it)
            }
        }
    }
}

/**
 * The option rows themselves, shared by [ThrottleChip] and the chat-throttle dialog: inherit,
 * "Instantly", the intervals (a custom one in force is listed in its place), "Custom…", "Muted".
 */
@Composable
fun ThrottleOptions(
    selected: Int?,
    inheritLabel: String?,
    offerInstant: Boolean = true,
    offerMuted: Boolean = true,
    onCustom: () -> Unit,
    onPick: (Int?) -> Unit,
) {
    val intervals = (THROTTLE_CHOICES + listOfNotNull(selected?.takeIf { it > INSTANT })).distinct().sorted()
    val inherit: List<Int?> = if (inheritLabel != null) listOf(null) else emptyList()

    @Composable
    fun option(option: Int?) = DropdownMenuItem(
        text = { Text(if (option == null) inheritLabel.orEmpty() else throttleLabel(option)) },
        trailingIcon = { if (option == selected) Icon(Icons.Filled.Check, contentDescription = null) },
        onClick = { onPick(option) },
        // The check mark alone says nothing to a screen reader.
        modifier = Modifier.semantics { this.selected = option == selected },
    )

    (inherit + listOfNotNull(INSTANT.takeIf { offerInstant }) + intervals).forEach { option(it) }
    DropdownMenuItem(text = { Text(stringResource(R.string.throttle_custom)) }, onClick = onCustom)
    if (offerMuted) option(MUTED)
}

/** Any interval within [THROTTLE_RANGE], typed as hours and minutes. [initial] prefills it when it is an interval. */
@Composable
fun CustomThrottleDialog(initial: Int?, onConfirm: (Int) -> Unit, onDismiss: () -> Unit) {
    val start = initial?.takeIf { it in THROTTLE_RANGE }
    var hours by rememberSaveable { mutableStateOf(start?.let { it / 60 }?.takeIf { it > 0 }?.toString().orEmpty()) }
    var minutes by rememberSaveable { mutableStateOf(start?.let { it % 60 }?.takeIf { it > 0 }?.toString().orEmpty()) }
    val value = customThrottle(hours, minutes)
    val typed = hours.isNotBlank() || minutes.isNotBlank()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.throttle_custom_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    val numbers = KeyboardOptions(keyboardType = KeyboardType.Number)
                    OutlinedTextField(
                        value = hours,
                        onValueChange = { text -> hours = text.filter(Char::isDigit).take(2) },
                        label = { Text(stringResource(R.string.throttle_custom_hours)) },
                        singleLine = true,
                        keyboardOptions = numbers,
                        isError = typed && value == null,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = minutes,
                        onValueChange = { text -> minutes = text.filter(Char::isDigit).take(4) },
                        label = { Text(stringResource(R.string.throttle_custom_minutes)) },
                        singleLine = true,
                        keyboardOptions = numbers,
                        isError = typed && value == null,
                        modifier = Modifier.weight(1f),
                    )
                }
                // The one thing the fields cannot say themselves; red only once something invalid was typed.
                Text(
                    stringResource(R.string.throttle_custom_range),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (typed && value == null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { value?.let(onConfirm) }, enabled = value != null) { Text(stringResource(R.string.rules_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.rules_cancel)) } },
    )
}

@Composable
private fun NameCategoryDialog(dialog: NameDialog, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var name by rememberSaveable(dialog) { mutableStateOf(dialog.initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (dialog.id == null) R.string.rules_new_category else R.string.rules_rename)) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                label = { Text(stringResource(R.string.rules_category_name)) },
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name) }, enabled = name.isNotBlank()) { Text(stringResource(R.string.rules_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.rules_cancel)) } },
    )
}

// ---- previews (compile-only) ----

@Preview(showBackground = true)
@Composable
private fun RulesPreview() {
    HushTheme {
        RulesContent(
            state = RulesUiState(
                loading = false,
                defaultMinutes = 60,
                groups = listOf(
                    CategoryGroup("messaging", "Messaging", 15, 15, listOf(HeldApp("com.example.alpha", "Alpha"), HeldApp("com.example.beta", "Beta"))),
                    CategoryGroup("social", "Social", null, 60, listOf(HeldApp("com.example.gamma", "Gamma"))),
                    CategoryGroup("news", "News", INSTANT, INSTANT, emptyList()),
                    CategoryGroup(null, null, null, 60, listOf(HeldApp("com.example.delta", "Delta"))),
                ),
                chatRules = listOf(
                    ChatRuleRow(ChatRule("com.example.alpha", "c1", "Synthetic group", 240), "Alpha", 15),
                    ChatRuleRow(ChatRule("com.example.alpha", "c2", "Sender A", INSTANT), "Alpha", 15),
                ),
            ),
            callbacks = RulesCallbacks(),
        )
    }
}
