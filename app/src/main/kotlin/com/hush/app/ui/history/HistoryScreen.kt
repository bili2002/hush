package com.hush.app.ui.history

import android.content.Intent
import android.provider.Settings
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hush.app.R
import com.hush.app.db.NotificationEvent
import com.hush.app.ui.components.AppIcon
import com.hush.app.ui.components.dayLabel
import com.hush.app.ui.components.formatClock
import com.hush.app.ui.components.startActivitySafely
import com.hush.app.ui.components.throttleEvery
import com.hush.app.ui.rules.ThrottleOptions
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter

data class HistoryCallbacks(
    val onQueryChange: (String) -> Unit = {},
    val onAppFilter: (String?) -> Unit = {},
    val onChatFilter: (String?) -> Unit = {},
    val onOpen: (NotificationEvent) -> Unit = {},
    val onToggleExpand: (String) -> Unit = {},
    val onShowApp: (NotificationEvent) -> Unit = {},
    val onShowChat: (NotificationEvent) -> Unit = {},
    val onShowAll: () -> Unit = {},
    val onAllHistory: () -> Unit = {},
    val onLoadMore: () -> Unit = {},
    val onEnableListener: () -> Unit = {},
    val onDismissOpenFailure: () -> Unit = {},
    val onAskChatThrottle: (NotificationEvent) -> Unit = {},
    val onSetChatThrottle: (Int?) -> Unit = {},
    val onDismissChatThrottle: () -> Unit = {},
    val onDismissRuleError: () -> Unit = {},
    /** Whether a tap on the row still reaches the notification's own target (the chat), not just the app. */
    val opensOriginal: (NotificationEvent) -> Boolean = { false },
)

@Composable
fun HistoryScreen(vm: HistoryViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val query by vm.query.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var launchFailed by rememberSaveable { mutableStateOf(false) }

    LifecycleResumeEffect(Unit) {
        vm.refreshSystemState()
        onPauseOrDispose { }
    }
    // Navigating away clears the digest filter: HushRoot nulls UiSignals.digestLink when History is left.

    HistoryContent(
        state = state,
        query = query,
        zone = ZoneId.systemDefault(),
        today = LocalDate.now(ZoneId.systemDefault()),
        launchFailed = launchFailed,
        callbacks = HistoryCallbacks(
            onQueryChange = vm::setQuery,
            onAppFilter = vm::setAppFilter,
            onChatFilter = vm::setChatFilter,
            onOpen = vm::open,
            onToggleExpand = vm::toggleExpanded,
            onShowApp = vm::showApp,
            onShowChat = vm::showChat,
            onShowAll = vm::showAll,
            onAllHistory = vm::clearDigestLink,
            onLoadMore = vm::loadMore,
            onEnableListener = {
                launchFailed = !context.startActivitySafely(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            },
            onDismissOpenFailure = vm::dismissOpenFailure,
            onAskChatThrottle = vm::askChatThrottle,
            onSetChatThrottle = vm::setChatThrottle,
            onDismissChatThrottle = vm::dismissChatThrottle,
            onDismissRuleError = vm::dismissRuleError,
            opensOriginal = vm::opensOriginal,
        ),
    )
}

private enum class Sheet { APPS, CHATS }

@Composable
fun HistoryContent(
    state: HistoryUiState,
    query: String,
    zone: ZoneId,
    today: LocalDate,
    callbacks: HistoryCallbacks,
    modifier: Modifier = Modifier,
    launchFailed: Boolean = false,
) {
    var sheet by rememberSaveable { mutableStateOf<Sheet?>(null) }
    val browse = state.mode as? HistoryMode.Browse
    val appLabel = browse?.app?.let { pkg -> state.apps.firstOrNull { it.packageName == pkg }?.appLabel ?: pkg }
    val chatTitle = browse?.chat?.let { key -> state.chats.firstOrNull { it.key == key }?.title ?: key }

    Column(modifier = modifier.fillMaxSize()) {
        if (launchFailed) {
            Text(
                stringResource(R.string.history_launch_failed),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
        state.openFailure?.let {
            ErrorLine(stringResource(R.string.history_open_failed, it), onDismiss = callbacks.onDismissOpenFailure)
        }
        state.ruleError?.let {
            ErrorLine(stringResource(R.string.rules_error, it), onDismiss = callbacks.onDismissRuleError)
        }

        val batch = state.mode as? HistoryMode.HeldBatch
        if (batch != null) {
            DigestBanner(deliveredAt = batch.deliveredAt, zone = zone, onAllHistory = callbacks.onAllHistory)
        } else {
            SearchBar(query = query, onQueryChange = callbacks.onQueryChange)
            // Also shown with no apps left (after a purge) while a filter is set, or there would be no way to clear it.
            if (browse != null && (state.apps.isNotEmpty() || browse.app != null)) {
                FilterBar(
                    app = browse.app,
                    appLabel = appLabel,
                    chatTitle = chatTitle,
                    hasChats = state.chats.isNotEmpty(),
                    onPickApp = { sheet = Sheet.APPS },
                    onPickChat = { sheet = Sheet.CHATS },
                    onClearApp = { callbacks.onAppFilter(null) },
                    onClearChat = { callbacks.onChatFilter(null) },
                )
            }
        }

        when {
            state.loading -> Centered {
                CircularProgressIndicator()
                Spacer(Modifier.height(12.dp))
                Text(stringResource(R.string.history_loading))
            }
            state.empty != null -> EmptyState(state.empty, state.mode, callbacks.onEnableListener)
            else -> NotificationList(state, zone, today, callbacks)
        }
    }

    when (sheet) {
        Sheet.APPS -> FilterSheet(
            title = stringResource(R.string.history_sheet_apps_title),
            allLabel = stringResource(R.string.history_all_apps),
            searchHint = stringResource(R.string.history_sheet_search_apps),
            items = state.apps.map { SheetItem(it.packageName, it.appLabel?.takeIf { l -> l.isNotBlank() } ?: it.packageName, it.count, it.packageName) },
            selectedKey = browse?.app,
            onPick = {
                callbacks.onAppFilter(it)
                sheet = null
            },
            onDismiss = { sheet = null },
        )
        Sheet.CHATS -> FilterSheet(
            title = stringResource(R.string.history_sheet_chats_title, appLabel.orEmpty()),
            allLabel = stringResource(R.string.history_all_chats),
            searchHint = stringResource(R.string.history_sheet_search_chats),
            items = state.chats.map { SheetItem(it.key, it.title, it.count, iconPackage = null) },
            selectedKey = browse?.chat,
            onPick = {
                callbacks.onChatFilter(it)
                sheet = null
            },
            onDismiss = { sheet = null },
        )
        null -> Unit
    }

    state.chatThrottle?.let { ChatThrottleDialog(it, onPick = callbacks.onSetChatThrottle, onDismiss = callbacks.onDismissChatThrottle) }
}

/** One chat's own interval: follow the app, never hold, or one of the intervals. Picking applies and closes. */
@Composable
private fun ChatThrottleDialog(dialog: ChatThrottle, onPick: (Int?) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.history_throttle_title)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    stringResource(R.string.history_throttle_subject, dialog.title, dialog.appLabel),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                ThrottleOptions(
                    selected = dialog.ruleMinutes,
                    inheritLabel = stringResource(R.string.rules_same_as_app, throttleEvery(dialog.appMinutes)),
                    onPick = onPick,
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.rules_cancel)) } },
    )
}

@Composable
private fun ErrorLine(message: String, onDismiss: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onDismiss) { Text(stringResource(R.string.history_dismiss)) }
    }
}

@Composable
private fun DigestBanner(deliveredAt: Long, zone: ZoneId, onAllHistory: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.history_banner_delivered, formatClock(deliveredAt, zone)),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
            )
            TextButton(onClick = onAllHistory) { Text(stringResource(R.string.history_all_history)) }
        }
    }
}

@Composable
private fun SearchBar(query: String, onQueryChange: (String) -> Unit) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        singleLine = true,
        placeholder = { Text(stringResource(R.string.history_search_hint)) },
        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(Icons.Filled.Clear, contentDescription = stringResource(R.string.history_clear_search))
                }
            }
        },
    )
}

/**
 * One chip per filter level instead of a chip per app: the app chip opens a searchable sheet, and
 * once an app is chosen a second chip does the same for that app's chats.
 */
@Composable
private fun FilterBar(
    app: String?,
    appLabel: String?,
    chatTitle: String?,
    hasChats: Boolean,
    onPickApp: () -> Unit,
    onPickChat: () -> Unit,
    onClearApp: () -> Unit,
    onClearChat: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .padding(bottom = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FilterDropChip(
            label = appLabel ?: stringResource(R.string.history_all_apps),
            active = app != null,
            onClick = onPickApp,
            onClear = onClearApp,
            avatar = app?.let { pkg -> { AppIcon(packageName = pkg, size = 24.dp) } },
        )
        if (app != null && (hasChats || chatTitle != null)) {
            FilterDropChip(
                label = chatTitle ?: stringResource(R.string.history_all_chats),
                active = chatTitle != null,
                onClick = onPickChat,
                onClear = onClearChat,
            )
        }
    }
}

/** Inactive: "All apps ▾". Active: "[icon] Name ✕" — the body reopens the sheet, the ✕ clears. */
@Composable
private fun FilterDropChip(
    label: String,
    active: Boolean,
    onClick: () -> Unit,
    onClear: () -> Unit,
    avatar: (@Composable () -> Unit)? = null,
) {
    InputChip(
        selected = active,
        onClick = onClick,
        label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 180.dp)) },
        avatar = avatar,
        trailingIcon = {
            if (active) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = stringResource(R.string.history_clear_filter),
                    // As tall as the chip itself, so a tap meant to clear does not land on the chip body and open the sheet.
                    modifier = Modifier.size(32.dp).clip(CircleShape).clickable(onClick = onClear).padding(7.dp),
                )
            } else {
                Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
            }
        },
    )
}

internal data class SheetItem(val key: String, val label: String, val count: Long, val iconPackage: String?)

/** Sheet search: case-insensitive substring over the label and the key (package name / conversation id). */
internal fun filterSheetItems(items: List<SheetItem>, query: String): List<SheetItem> {
    val q = query.trim()
    if (q.isEmpty()) return items
    return items.filter { it.label.contains(q, ignoreCase = true) || it.key.contains(q, ignoreCase = true) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FilterSheet(
    title: String,
    allLabel: String,
    searchHint: String,
    items: List<SheetItem>,
    selectedKey: String?,
    onPick: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    var search by rememberSaveable { mutableStateOf("") }
    val shown = filterSheetItems(items, search)
    // Fully expanded from the start: half height plus the keyboard would leave no room for the list.
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 24.dp))
        OutlinedTextField(
            value = search,
            onValueChange = { search = it },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            singleLine = true,
            placeholder = { Text(searchHint) },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            trailingIcon = {
                if (search.isNotEmpty()) {
                    IconButton(onClick = { search = "" }) {
                        Icon(Icons.Filled.Clear, contentDescription = stringResource(R.string.history_clear_search))
                    }
                }
            },
        )
        LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
            if (search.isBlank()) {
                item(key = "all") {
                    SheetRow(label = allLabel, count = null, selected = selectedKey == null, iconPackage = null, onClick = { onPick(null) })
                }
            }
            items(shown, key = { "item-${it.key}" }) { item ->
                SheetRow(
                    label = item.label,
                    count = item.count,
                    selected = item.key == selectedKey,
                    iconPackage = item.iconPackage,
                    onClick = { onPick(item.key) },
                )
            }
            if (shown.isEmpty()) {
                item(key = "none") {
                    Text(
                        stringResource(R.string.history_sheet_no_matches),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun SheetRow(label: String, count: Long?, selected: Boolean, iconPackage: String?, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (iconPackage != null) {
            AppIcon(packageName = iconPackage, size = 32.dp)
            Spacer(Modifier.width(16.dp))
        }
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (count != null) {
            Spacer(Modifier.width(12.dp))
            Text(count.toString(), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (selected) {
            Spacer(Modifier.width(12.dp))
            Icon(Icons.Filled.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun NotificationList(state: HistoryUiState, zone: ZoneId, today: LocalDate, callbacks: HistoryCallbacks) {
    val listState = rememberLazyListState()
    if (state.canLoadMore) {
        LaunchedEffect(listState, state.days) {
            snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
                .distinctUntilChanged()
                // totalItemsCount is 0 before the first layout pass; "0 >= -5" must not request a page.
                .filter { last -> listState.layoutInfo.totalItemsCount.let { total -> total > 0 && last >= total - 5 } }
                .collect { callbacks.onLoadMore() }
        }
    }
    val browse = state.mode as? HistoryMode.Browse
    LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
        state.days.forEach { day ->
            item(key = "day-${day.date}") {
                Text(
                    dayLabel(day.date, today),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            items(day.items, key = { it.id }) { row ->
                NotificationRow(
                    row = row,
                    expanded = state.expandedId == row.id,
                    zone = zone,
                    // Offer only the jumps that would change what is shown.
                    canShowApp = browse?.app != row.packageName || browse.chat != null,
                    canShowChat = row.chatKey != null && !(browse?.app == row.packageName && browse.chat == row.chatKey),
                    callbacks = callbacks,
                )
                HorizontalDivider(modifier = Modifier.padding(start = 72.dp))
            }
        }
        if (state.collapsed) {
            item(key = "show-all") {
                OutlinedButton(
                    onClick = callbacks.onShowAll,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                ) { Text(stringResource(R.string.history_show_all_count, state.total)) }
            }
        }
    }
}

/**
 * Behaves like a notification in the shade: the body opens what the notification pointed at, the
 * chevron unfolds the full text plus the "only this chat / app" jumps.
 */
@Composable
private fun NotificationRow(
    row: NotificationEvent,
    expanded: Boolean,
    zone: ZoneId,
    canShowApp: Boolean,
    canShowChat: Boolean,
    callbacks: HistoryCallbacks,
) {
    val appName = row.appLabel?.takeIf { it.isNotBlank() } ?: row.packageName
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClickLabel = stringResource(R.string.history_open_label, appName)) { callbacks.onOpen(row) }
            .padding(start = 16.dp, top = 10.dp, bottom = 10.dp, end = 4.dp)
            .animateContentSize(),
        verticalAlignment = Alignment.Top,
    ) {
        AppIcon(packageName = row.packageName)
        Spacer(Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    appName,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    formatClock(row.postTime, zone),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            row.title?.takeIf { it.isNotBlank() }?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    maxLines = if (expanded) Int.MAX_VALUE else 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            val body = if (expanded) (row.bigText?.takeIf { it.isNotBlank() } ?: row.text) else row.text
            body?.takeIf { it.isNotBlank() }?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = if (expanded) Int.MAX_VALUE else 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (expanded) {
                row.subText?.takeIf { it.isNotBlank() }?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (row.held) {
                Spacer(Modifier.height(6.dp))
                HeldChip(deliveredAt = row.deliveredAt, zone = zone)
            }
            if (expanded) {
                Spacer(Modifier.height(6.dp))
                Text(
                    if (callbacks.opensOriginal(row)) stringResource(R.string.history_tap_opens_original)
                    else stringResource(R.string.history_tap_opens_app, appName),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (canShowChat || canShowApp || row.chatKey != null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                        if (canShowChat) {
                            TextButton(onClick = { callbacks.onShowChat(row) }, contentPadding = PaddingValues(horizontal = 8.dp)) {
                                Text(stringResource(R.string.history_only_chat))
                            }
                        }
                        if (canShowApp) {
                            TextButton(onClick = { callbacks.onShowApp(row) }, contentPadding = PaddingValues(horizontal = 8.dp)) {
                                Text(stringResource(R.string.history_only_app))
                            }
                        }
                        if (row.chatKey != null) {
                            TextButton(onClick = { callbacks.onAskChatThrottle(row) }, contentPadding = PaddingValues(horizontal = 8.dp)) {
                                Text(stringResource(R.string.history_throttle_chat))
                            }
                        }
                    }
                }
            }
        }
        IconButton(onClick = { callbacks.onToggleExpand(row.id) }) {
            Icon(
                if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = stringResource(if (expanded) R.string.history_collapse else R.string.history_expand),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun HeldChip(deliveredAt: Long?, zone: ZoneId) {
    Surface(
        color = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        shape = RoundedCornerShape(8.dp),
    ) {
        Text(
            if (deliveredAt == null) stringResource(R.string.history_held)
            else stringResource(R.string.history_held_delivered, formatClock(deliveredAt, zone)),
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}

@Composable
private fun EmptyState(kind: EmptyKind, mode: HistoryMode, onEnableListener: () -> Unit) {
    Centered {
        when (kind) {
            EmptyKind.LISTENER_OFF -> {
                Text(stringResource(R.string.history_empty_listener_off), style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.height(12.dp))
                Button(onClick = onEnableListener) { Text(stringResource(R.string.history_enable_listener)) }
            }
            EmptyKind.NO_NOTIFICATIONS -> Text(stringResource(R.string.history_empty_none), style = MaterialTheme.typography.bodyLarge)
            EmptyKind.NO_MATCHES -> Text(
                stringResource(R.string.history_empty_no_matches, (mode as? HistoryMode.Browse)?.query.orEmpty()),
                style = MaterialTheme.typography.bodyLarge,
            )
            EmptyKind.APP_EMPTY -> Text(stringResource(R.string.history_empty_app), style = MaterialTheme.typography.bodyLarge)
            EmptyKind.BATCH_EMPTY -> Text(stringResource(R.string.history_empty_batch), style = MaterialTheme.typography.bodyLarge)
        }
    }
}

@Composable
private fun Centered(content: @Composable () -> Unit) {
    Box(modifier = Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) { content() }
    }
}
