package com.hush.app.ui.history

import android.content.Intent
import android.provider.Settings
import androidx.compose.animation.animateContentSize
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
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
import com.hush.app.ui.focus.startActivitySafely
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter

data class HistoryCallbacks(
    val onQueryChange: (String) -> Unit = {},
    val onAppFilter: (String?) -> Unit = {},
    val onToggleExpand: (String) -> Unit = {},
    val onShowAll: () -> Unit = {},
    val onLoadMore: () -> Unit = {},
    val onEnableListener: () -> Unit = {},
)

@Composable
fun HistoryScreen(vm: HistoryViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val query by vm.query.collectAsStateWithLifecycle()
    val context = LocalContext.current

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
        callbacks = HistoryCallbacks(
            onQueryChange = vm::setQuery,
            onAppFilter = vm::setAppFilter,
            onToggleExpand = vm::toggleExpanded,
            onShowAll = vm::clearDigestLink,
            onLoadMore = vm::loadMore,
            onEnableListener = { context.startActivitySafely(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) },
        ),
    )
}

@Composable
fun HistoryContent(
    state: HistoryUiState,
    query: String,
    zone: ZoneId,
    today: LocalDate,
    callbacks: HistoryCallbacks,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        val batch = state.mode as? HistoryMode.HeldBatch
        if (batch != null) {
            DigestBanner(deliveredAt = batch.deliveredAt, zone = zone, onShowAll = callbacks.onShowAll)
        } else {
            SearchBar(query = query, onQueryChange = callbacks.onQueryChange)
            if (state.apps.isNotEmpty()) {
                AppChips(state, callbacks.onAppFilter)
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
}

@Composable
private fun DigestBanner(deliveredAt: Long, zone: ZoneId, onShowAll: () -> Unit) {
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
            TextButton(onClick = onShowAll) { Text(stringResource(R.string.history_show_all)) }
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

@Composable
private fun AppChips(state: HistoryUiState, onAppFilter: (String?) -> Unit) {
    val selected = (state.mode as? HistoryMode.Browse)?.app
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.padding(bottom = 4.dp),
    ) {
        item {
            FilterChip(
                selected = selected == null,
                onClick = { onAppFilter(null) },
                label = { Text(stringResource(R.string.history_all_apps)) },
            )
        }
        items(state.apps, key = { it.packageName }) { app ->
            FilterChip(
                selected = selected == app.packageName,
                onClick = { onAppFilter(app.packageName) },
                label = { Text(app.appLabel ?: app.packageName) },
            )
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
                .filter { it >= listState.layoutInfo.totalItemsCount - 5 }
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
                    onClick = { callbacks.onToggleExpand(row.id) },
                )
                HorizontalDivider(modifier = Modifier.padding(start = 72.dp))
            }
        }
    }
}

@Composable
private fun NotificationRow(row: NotificationEvent, expanded: Boolean, zone: ZoneId, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .animateContentSize(),
        verticalAlignment = Alignment.Top,
    ) {
        AppIcon(packageName = row.packageName)
        Spacer(Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    row.appLabel ?: row.packageName,
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
