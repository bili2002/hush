package com.hush.app.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hush.app.db.AppSummary
import com.hush.app.db.EventType
import com.hush.app.db.NotificationEvent
import com.hush.app.db.NotificationRepository
import com.hush.app.ui.components.SystemStatus
import com.hush.app.ui.components.UiSignals
import com.hush.app.ui.components.dayOf
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn

sealed interface HistoryMode {
    /** Browsing the archive, optionally narrowed by a (debounced) search [query] and/or one [app] package. */
    data class Browse(val query: String = "", val app: String? = null) : HistoryMode

    /** The digest deep link: only the held rows delivered at [deliveredAt]. */
    data class HeldBatch(val deliveredAt: Long) : HistoryMode
}

enum class EmptyKind { LISTENER_OFF, NO_NOTIFICATIONS, NO_MATCHES, APP_EMPTY, BATCH_EMPTY }

data class DayGroup(val date: LocalDate, val items: List<NotificationEvent>)

data class HistoryUiState(
    val loading: Boolean = true,
    val mode: HistoryMode = HistoryMode.Browse(),
    /** Newest day first; items inside a day newest first. REMOVED events are never listed. */
    val days: List<DayGroup> = emptyList(),
    val apps: List<AppSummary> = emptyList(),
    val expandedId: String? = null,
    val empty: EmptyKind? = null,
    val listenerEnabled: Boolean = true,
    /** True when the list is cut at the current page size and more rows may exist. */
    val canLoadMore: Boolean = false,
)

/**
 * Rows tagged with the mode they were loaded for, so the state never pairs a new mode with stale rows.
 * [rawCount] is the row count before the POSTED filter: paging is decided on what the query returned,
 * not on what survived the filter, or a page full of REMOVED rows would look like the end of the archive.
 */
private data class Page(val mode: HistoryMode, val rows: List<NotificationEvent>, val limit: Int, val rawCount: Int)

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
@HiltViewModel
class HistoryViewModel(
    private val repo: NotificationRepository,
    private val systemStatus: SystemStatus,
    private val signals: UiSignals,
    private val zone: ZoneId,
    debounceMillis: Long,
) : ViewModel() {

    @Inject
    constructor(
        repo: NotificationRepository,
        systemStatus: SystemStatus,
        signals: UiSignals,
    ) : this(repo, systemStatus, signals, ZoneId.systemDefault(), DEBOUNCE_MILLIS)

    private val _query = MutableStateFlow("")

    /** The raw search text (undebounced) for the text field. */
    val query: StateFlow<String> = _query.asStateFlow()

    private val appFilter = MutableStateFlow<String?>(null)
    private val limit = MutableStateFlow(PAGE)
    private val expandedId = MutableStateFlow<String?>(null)
    private val listenerEnabled = MutableStateFlow(systemStatus.listenerEnabled())

    private val mode: Flow<HistoryMode> = combine(
        signals.digestLink,
        _query.debounce(debounceMillis).onStart { emit("") }.map { it.trim() }.distinctUntilChanged(),
        appFilter,
    ) { link, q, app ->
        link?.let { HistoryMode.HeldBatch(it.deliveredAt) } ?: HistoryMode.Browse(q, app)
    }.distinctUntilChanged()
        // A new mode starts over at one page; "load more" growth belongs to the mode it happened in.
        .onEach { limit.value = PAGE }

    private val page: Flow<Page?> = combine(mode, limit) { m, l -> m to l }
        .flatMapLatest { (m, l) ->
            source(m, l).map { rows -> Page(m, rows.filter { it.event == EventType.POSTED }, l, rawCount = rows.size) }
        }
        .map<Page, Page?> { it }
        .onStart { emit(null) }

    val state: StateFlow<HistoryUiState> =
        combine(page, repo.selectApps(), expandedId, listenerEnabled) { page, apps, expanded, listenerOn ->
            if (page == null) {
                HistoryUiState(loading = true, apps = apps, listenerEnabled = listenerOn)
            } else {
                HistoryUiState(
                    loading = false,
                    mode = page.mode,
                    days = groupByDay(page.rows),
                    apps = apps,
                    expandedId = expanded,
                    empty = if (page.rows.isEmpty()) emptyKind(page.mode, listenerOn) else null,
                    listenerEnabled = listenerOn,
                    canLoadMore = page.mode is HistoryMode.Browse && page.rawCount >= page.limit,
                )
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HistoryUiState())

    private fun source(mode: HistoryMode, limit: Int): Flow<List<NotificationEvent>> = when (mode) {
        // The repository returns a batch oldest-first (delivery order); the list is newest-first like the rest.
        is HistoryMode.HeldBatch -> flow { emit(repo.selectHeldBatch(mode.deliveredAt).sortedByDescending { it.postTime }) }
        is HistoryMode.Browse ->
            if (mode.query.isEmpty() && mode.app == null) repo.selectRecent(limit, Long.MAX_VALUE)
            // `search("")` matches every row with any text, which is also how an app-only filter stays
            // bounded by the app's own most recent rows rather than by the global page.
            else repo.search(mode.query, mode.app, limit)
    }

    private fun groupByDay(rows: List<NotificationEvent>): List<DayGroup> =
        rows.groupBy { dayOf(it.postTime, zone) }.map { (date, items) -> DayGroup(date, items) }

    private fun emptyKind(mode: HistoryMode, listenerOn: Boolean): EmptyKind = when (mode) {
        is HistoryMode.HeldBatch -> EmptyKind.BATCH_EMPTY
        is HistoryMode.Browse -> when {
            mode.query.isNotEmpty() -> EmptyKind.NO_MATCHES
            mode.app != null -> EmptyKind.APP_EMPTY
            !listenerOn -> EmptyKind.LISTENER_OFF
            else -> EmptyKind.NO_NOTIFICATIONS
        }
    }

    fun setQuery(text: String) {
        _query.value = text
    }

    /** Toggles the app chip: selecting the current one clears it. */
    fun setAppFilter(packageName: String?) {
        appFilter.value = if (appFilter.value == packageName) null else packageName
    }

    fun toggleExpanded(id: String) {
        expandedId.value = if (expandedId.value == id) null else id
    }

    fun loadMore() {
        if (state.value.canLoadMore) limit.value += PAGE
    }

    /** Leaves the digest batch view ("Show all" or navigating away). */
    fun clearDigestLink() {
        signals.digestLink.value = null
    }

    fun refreshSystemState() {
        listenerEnabled.value = systemStatus.listenerEnabled()
    }

    companion object {
        const val PAGE = 200
        const val DEBOUNCE_MILLIS = 300L
    }
}
