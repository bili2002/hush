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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hush.app.R
import com.hush.app.db.NotificationEvent
import com.hush.app.ui.components.AppIcon
import com.hush.app.ui.components.PickerSheet
import com.hush.app.ui.components.SheetItem
import com.hush.app.ui.components.dayLabel
import com.hush.app.ui.components.formatClock
import com.hush.app.ui.components.startActivitySafely
import com.hush.app.ui.rules.ChatThrottleDialog
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter

data class HistoryCallbacks(
    val onQueryChange: (String) -> Unit = {},
    val onAppFilter: (String?) -> Unit = {},
    val onChatFilter: (String?) -> Unit = {},
    val onMutedFilter: (Boolean) -> Unit = {},
    val onOpen: (NotificationEvent) -> Unit = {},
    val onToggleExpand: (String) -> Unit = {},
    val onShowAll: () -> Unit = {},
    val onAllHistory: () -> Unit = {},
    val onLoadMore: () -> Unit = {},
    val onEnableListener: () -> Unit = {},
    val onDismissOpenFailure: () -> Unit = {},
    val onAskChatThrottle: (NotificationEvent) -> Unit = {},
    val onSetChatThrottle: (Int?) -> Unit = {},
    val onDismissChatThrottle: () -> Unit = {},
    val onDismissRuleError: () -> Unit = {},
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
            onMutedFilter = vm::setMutedFilter,
            onOpen = vm::open,
            onToggleExpand = vm::toggleExpanded,
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
            if (browse != null && (state.apps.isNotEmpty() || browse.app != null || browse.muted)) {
                FilterBar(
                    // Offered while something is muted, and kept while it is on so that it can be switched off.
                    muted = browse.muted.takeIf { it || state.mutedCount > 0 },
                    // The count is of everything muted; next to another filter it would promise more than the list shows.
                    mutedCount = state.mutedCount.takeIf { browse.app == null && browse.query.isEmpty() },
                    onMuted = callbacks.onMutedFilter,
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
        Sheet.APPS -> PickerSheet(
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
        Sheet.CHATS -> PickerSheet(
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
    /** Null = the "Muted" chip is not shown. */
    muted: Boolean?,
    /** Null = show the chip without a number. */
    mutedCount: Int?,
    onMuted: (Boolean) -> Unit,
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
        if (muted != null) {
            FilterChip(
                selected = muted,
                onClick = { onMuted(!muted) },
                label = { Text(mutedCount?.let { stringResource(R.string.history_muted_count, it) } ?: stringResource(R.string.history_muted)) },
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

// One step below the Material body styles: a History row is read by the dozen, not one at a time.
private val RowTitle @Composable get() = MaterialTheme.typography.bodyMedium
private val RowBody @Composable get() = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp, lineHeight = 18.sp)

/**
 * Behaves like a notification in the shade: the body opens what the notification pointed at, the
 * chevron unfolds the full text and, for a chat, its throttle.
 */
@Composable
private fun NotificationRow(
    row: NotificationEvent,
    expanded: Boolean,
    zone: ZoneId,
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
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    formatClock(row.postTime, zone),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            row.title?.takeIf { it.isNotBlank() }?.let {
                Text(
                    it,
                    style = RowTitle,
                    fontWeight = FontWeight.Medium,
                    maxLines = if (expanded) Int.MAX_VALUE else 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            val body = if (expanded) (row.bigText?.takeIf { it.isNotBlank() } ?: row.text) else row.text
            body?.takeIf { it.isNotBlank() }?.let {
                Text(
                    it,
                    style = RowBody,
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
                HeldChip(row = row, zone = zone)
            }
            if (expanded && row.chatKey != null) {
                TextButton(onClick = { callbacks.onAskChatThrottle(row) }, contentPadding = PaddingValues(horizontal = 8.dp)) {
                    Text(stringResource(R.string.history_throttle_chat))
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
private fun HeldChip(row: NotificationEvent, zone: ZoneId) {
    val deliveredAt = row.deliveredAt
    Surface(
        color = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        shape = RoundedCornerShape(8.dp),
    ) {
        Text(
            when {
                row.muted -> stringResource(R.string.history_muted)
                deliveredAt == null -> stringResource(R.string.history_held)
                else -> stringResource(R.string.history_held_delivered, formatClock(deliveredAt, zone))
            },
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
