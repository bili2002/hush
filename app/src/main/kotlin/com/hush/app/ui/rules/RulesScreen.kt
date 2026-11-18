package com.hush.app.ui.rules

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
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
import com.hush.app.focus.THROTTLE_CHOICES
import com.hush.app.ui.HushTheme
import com.hush.app.ui.components.AppIcon
import com.hush.app.ui.components.throttleEvery
import com.hush.app.ui.components.throttleLabel

/** Everything the rules screen can ask for; previews no-op it. */
data class RulesCallbacks(
    val onBack: () -> Unit = {},
    val onDefault: (Int) -> Unit = {},
    val onCategoryMinutes: (String, Int?) -> Unit = { _, _ -> },
    val onAddCategory: (String) -> Unit = {},
    val onRenameCategory: (String, String) -> Unit = { _, _ -> },
    val onDeleteCategory: (String) -> Unit = {},
    val onMoveApp: (String, String?) -> Unit = { _, _ -> },
    val onStopHolding: (String) -> Unit = {},
    val onChatRuleMinutes: (ChatRule, Int) -> Unit = { _, _ -> },
    val onRemoveChatRule: (ChatRule) -> Unit = {},
    val onAddApps: () -> Unit = {},
    val onDismissError: () -> Unit = {},
)

@Composable
fun RulesScreen(onBack: () -> Unit, vm: RulesViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    RulesContent(
        state = state,
        callbacks = RulesCallbacks(
            onBack = onBack,
            onDefault = vm::setDefaultMinutes,
            onCategoryMinutes = vm::setCategoryMinutes,
            onAddCategory = vm::addCategory,
            onRenameCategory = vm::renameCategory,
            onDeleteCategory = vm::deleteCategory,
            onMoveApp = vm::moveApp,
            onStopHolding = vm::stopHolding,
            onChatRuleMinutes = vm::setChatRuleMinutes,
            onRemoveChatRule = vm::removeChatRule,
            onAddApps = vm::addApps,
            onDismissError = vm::dismissError,
        ),
    )
}

/** The name dialog is used for "new category" ([id] null) and "rename". */
private data class NameDialog(val id: String?, val initial: String)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RulesContent(state: RulesUiState, callbacks: RulesCallbacks) {
    var nameDialog by remember { mutableStateOf<NameDialog?>(null) }
    // The shell's Scaffold already consumed the status-bar inset; zero both so the bar is not padded twice.
    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                windowInsets = WindowInsets(0),
                title = { Text(stringResource(R.string.rules_title)) },
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
                    CircularProgressIndicator()
                    // Normally a blink; if seeding the presets failed this is where the cause shows.
                    state.error?.let { ErrorLine(it, callbacks.onDismissError) }
                }
            }
            return@Scaffold
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "intro") {
                Text(
                    stringResource(R.string.rules_intro),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            state.error?.let { message ->
                item(key = "error") { ErrorLine(message, callbacks.onDismissError) }
            }
            item(key = "default") { DefaultCard(state.defaultMinutes, callbacks.onDefault) }
            items(state.groups, key = { "group-${it.id}" }) { group ->
                GroupCard(
                    group = group,
                    defaultMinutes = state.defaultMinutes,
                    categories = state.groups.filter { it.id != null },
                    callbacks = callbacks,
                    onRename = { nameDialog = NameDialog(group.id, group.name.orEmpty()) },
                )
            }
            item(key = "actions") {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = callbacks.onAddApps) { Text(stringResource(R.string.rules_add_apps)) }
                    OutlinedButton(onClick = { nameDialog = NameDialog(null, "") }) { Text(stringResource(R.string.rules_new_category)) }
                }
            }
            item(key = "chat-header") {
                Column(modifier = Modifier.padding(top = 8.dp)) {
                    Text(stringResource(R.string.rules_chats_title), style = MaterialTheme.typography.titleMedium)
                    Text(
                        stringResource(R.string.rules_chats_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(state.chatRules, key = { "chat-${it.rule.packageName}-${it.rule.chatKey}" }) { row ->
                ChatRuleCard(row, callbacks)
            }
        }
    }

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
private fun DefaultCard(minutes: Int, onPick: (Int) -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.rules_default_title), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(R.string.rules_default_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // A default saved by an older build (or any value off the list) still shows as its own segment.
            val choices = (THROTTLE_CHOICES + minutes).distinct().sorted()
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                choices.forEachIndexed { index, choice ->
                    SegmentedButton(
                        selected = minutes == choice,
                        onClick = { onPick(choice) },
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = choices.size),
                        // No check icon: five segments need the width for their labels.
                        icon = {},
                    ) { Text(throttleLabel(choice), maxLines = 1) }
                }
            }
        }
    }
}

@Composable
private fun GroupCard(
    group: CategoryGroup,
    defaultMinutes: Int,
    categories: List<CategoryGroup>,
    callbacks: RulesCallbacks,
    onRename: () -> Unit,
) {
    val id = group.id
    Card(modifier = Modifier.fillMaxWidth()) {
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
            if (group.minutes == INSTANT) {
                Text(
                    stringResource(R.string.rules_instant_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
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
                    AppRow(app = app, currentCategory = id, categories = categories, callbacks = callbacks)
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
private fun AppRow(app: HeldApp, currentCategory: String?, categories: List<CategoryGroup>, callbacks: RulesCallbacks) {
    var open by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth().clickable { open = true }.padding(start = 16.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppIcon(packageName = app.packageName, size = 32.dp)
        Spacer(Modifier.width(16.dp))
        Text(app.label, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Box {
            IconButton(onClick = { open = true }) {
                Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.rules_app_menu, app.label))
            }
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

@Composable
private fun ChatRuleCard(row: ChatRuleRow, callbacks: RulesCallbacks) {
    Card(modifier = Modifier.fillMaxWidth()) {
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
}

/**
 * The one throttle picker: a chip showing the current choice that drops down "inherit" (null),
 * "Instantly" and the intervals. [minutes] null = inheriting.
 */
@Composable
fun ThrottleChip(minutes: Int?, inheritLabel: String, onPick: (Int?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        AssistChip(
            onClick = { open = true },
            label = { Text(if (minutes == null) inheritLabel else throttleLabel(minutes), maxLines = 1) },
            trailingIcon = { Icon(Icons.Filled.ArrowDropDown, contentDescription = null) },
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            ThrottleOptions(selected = minutes, inheritLabel = inheritLabel) {
                open = false
                onPick(it)
            }
        }
    }
}

/** The option rows themselves, shared by [ThrottleChip] and History's "Throttle this chat" dialog. */
@Composable
fun ThrottleOptions(selected: Int?, inheritLabel: String, onPick: (Int?) -> Unit) {
    val options: List<Int?> = listOf<Int?>(null, INSTANT) + THROTTLE_CHOICES + listOfNotNull(selected?.takeIf { it > INSTANT && it !in THROTTLE_CHOICES })
    options.forEach { option ->
        DropdownMenuItem(
            text = { Text(if (option == null) inheritLabel else throttleLabel(option)) },
            trailingIcon = { if (option == selected) Icon(Icons.Filled.Check, contentDescription = null) },
            onClick = { onPick(option) },
        )
    }
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
