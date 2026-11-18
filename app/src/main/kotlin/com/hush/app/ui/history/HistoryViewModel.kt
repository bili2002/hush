package com.hush.app.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hush.app.db.AppSummary
import com.hush.app.db.ChatSummary
import com.hush.app.db.NotificationEvent
import com.hush.app.db.NotificationRepository
import com.hush.app.focus.ChatRule
import com.hush.app.focus.FocusActions
import com.hush.app.focus.appThrottleMinutes
import com.hush.app.focus.chatRule
import com.hush.app.focus.withChatRule
import com.hush.app.focus.withoutChatRule
import com.hush.app.open.NotificationOpener
import com.hush.app.open.OpenResult
import com.hush.app.settings.Settings
import com.hush.app.settings.SettingsStore
import com.hush.app.ui.components.SystemStatus
import com.hush.app.ui.components.UiSignals
import com.hush.app.ui.rules.ChatThrottle
import com.hush.app.ui.components.dayOf
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
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
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface HistoryMode {
    /**
     * Browsing the archive, optionally narrowed by a (debounced) search [query], one [app] package and,
     * inside that app, one [chat] (a [NotificationEvent.chatKey]).
     */
    data class Browse(val query: String = "", val app: String? = null, val chat: String? = null) : HistoryMode

    /**
     * The digest deep link: the held rows delivered at or after [deliveredAt]. Normally that is one
     * delivery run; a digest that replaced an unread one carries the older run's time and covers both.
     */
    data class HeldBatch(val deliveredAt: Long) : HistoryMode
}

enum class EmptyKind { LISTENER_OFF, NO_NOTIFICATIONS, NO_MATCHES, APP_EMPTY, BATCH_EMPTY }

data class DayGroup(val date: LocalDate, val items: List<NotificationEvent>)

data class HistoryUiState(
    val loading: Boolean = true,
    val mode: HistoryMode = HistoryMode.Browse(),
    /** Newest day first; items inside a day newest first. REMOVED events are never listed. */
    val days: List<DayGroup> = emptyList(),
    /** Every app with recorded notifications, most recent first; feeds the app filter sheet. */
    val apps: List<AppSummary> = emptyList(),
    /** The chats of the filtered app, most recent first; empty when no app is selected. */
    val chats: List<ChatSummary> = emptyList(),
    val expandedId: String? = null,
    val empty: EmptyKind? = null,
    val listenerEnabled: Boolean = true,
    /** How many rows match the current mode in total, shown or not. */
    val total: Int = 0,
    /** True while only the first [HistoryViewModel.PREVIEW] rows are shown and more exist: offer "Show all". */
    val collapsed: Boolean = false,
    /** True when the unrolled list is cut at the current page size and more rows exist. */
    val canLoadMore: Boolean = false,
    /** Label of the app a tap could not open (uninstalled / no launcher), until dismissed. */
    val openFailure: String? = null,
    /** The "Throttle this chat" dialog, while it is open. */
    val chatThrottle: ChatThrottle? = null,
    /** The real cause of a chat rule that could not be saved, until dismissed. */
    val ruleError: String? = null,
)

/** Rows tagged with the mode they were loaded for, so the state never pairs a new mode with stale rows. */
private data class Page(val mode: HistoryMode, val rows: List<NotificationEvent>, val total: Int, val showAll: Boolean)

/**
 * How much of [mode] is shown: the short preview, or the unrolled list up to [limit] rows. Tagged
 * with its mode so that "show all" / "load more" only ever apply to the mode they happened in: a
 * paging whose tag is not the current mode reads as the default (the preview).
 */
private data class Paging(val mode: HistoryMode? = null, val showAll: Boolean = false, val limit: Int = HistoryViewModel.PAGE)

private data class Filters(val app: String? = null, val chat: String? = null)

private data class Local(
    val expandedId: String? = null,
    val listenerEnabled: Boolean,
    val openFailure: String? = null,
    val throttleFor: NotificationEvent? = null,
    val ruleError: String? = null,
)

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
@HiltViewModel
class HistoryViewModel(
    private val repo: NotificationRepository,
    private val systemStatus: SystemStatus,
    private val signals: UiSignals,
    private val opener: NotificationOpener,
    private val settings: SettingsStore,
    private val focusActions: FocusActions,
    private val zone: ZoneId,
    debounceMillis: Long,
) : ViewModel() {

    @Inject
    constructor(
        repo: NotificationRepository,
        systemStatus: SystemStatus,
        signals: UiSignals,
        opener: NotificationOpener,
        settings: SettingsStore,
        focusActions: FocusActions,
    ) : this(repo, systemStatus, signals, opener, settings, focusActions, ZoneId.systemDefault(), DEBOUNCE_MILLIS)

    private val _query = MutableStateFlow("")

    /** The raw search text (undebounced) for the text field. */
    val query: StateFlow<String> = _query.asStateFlow()

    private val filters = MutableStateFlow(Filters())
    private val paging = MutableStateFlow(Paging())
    private val local = MutableStateFlow(Local(listenerEnabled = systemStatus.listenerEnabled()))

    // Hot, so a collector that comes back (Hush reopened after the tapped app) sees the current query at once
    // instead of an empty one for the length of the debounce.
    private val debouncedQuery: StateFlow<String> = _query.debounce(debounceMillis).map { it.trim() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")

    private val mode: Flow<HistoryMode> = combine(signals.digestLink, debouncedQuery, filters) { link, q, f ->
        link?.let { HistoryMode.HeldBatch(it.deliveredAt) } ?: HistoryMode.Browse(q, f.app, f.chat)
    }.distinctUntilChanged()

    // No side effects in here: `state` stops collecting 5 s after Hush leaves the screen and starts again on
    // return, and what the owner had unrolled or filtered must still be there.
    private val page: Flow<Page> = combine(mode, paging) { m, p -> m to (p.takeIf { it.mode == m } ?: Paging(m)) }
        .distinctUntilChanged()
        .flatMapLatest { (m, p) -> source(m, p) }

    private val chats: Flow<List<ChatSummary>> = filters.map { it.app }.distinctUntilChanged()
        .flatMapLatest { app -> if (app == null) flowOf(emptyList()) else repo.selectChats(app) }

    val state: StateFlow<HistoryUiState> =
        combine(page, repo.selectApps(), chats, local, settings.settings) { page, apps, chats, loc, s ->
            val more = page.rows.size < page.total
            HistoryUiState(
                loading = false,
                mode = page.mode,
                days = groupByDay(page.rows),
                apps = apps,
                chats = chats,
                expandedId = loc.expandedId,
                empty = if (page.rows.isEmpty()) emptyKind(page.mode, loc.listenerEnabled) else null,
                listenerEnabled = loc.listenerEnabled,
                total = page.total,
                collapsed = more && !page.showAll,
                canLoadMore = more && page.showAll,
                openFailure = loc.openFailure,
                chatThrottle = loc.throttleFor?.let { chatThrottleOf(it, s) },
                ruleError = loc.ruleError,
            )
            // The initial value is the loading state; after that the last state stays up until fresh rows arrive.
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HistoryUiState())

    private fun source(mode: HistoryMode, paging: Paging): Flow<Page> {
        val limit = if (paging.showAll) paging.limit else PREVIEW
        return when (mode) {
            // A batch is small and fixed, so it is read whole and cut in memory. The repository returns it
            // oldest-first (delivery order); the list is newest-first like the rest.
            is HistoryMode.HeldBatch -> flow {
                val all = repo.selectHeldBatch(mode.deliveredAt).sortedByDescending { it.postTime }
                emit(Page(mode, if (paging.showAll) all else all.take(PREVIEW), all.size, paging.showAll))
            }
            is HistoryMode.Browse -> combine(
                repo.selectHistory(mode.query, mode.app, mode.chat, limit),
                repo.countHistory(mode.query, mode.app, mode.chat),
            ) { rows, total -> Page(mode, rows, total.toInt(), paging.showAll) }
        }
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

    /** Narrows to one app (null = all apps). A chat belongs to its app, so changing the app drops the chat. */
    fun setAppFilter(packageName: String?) {
        filters.update { if (it.app == packageName) it else Filters(app = packageName) }
    }

    /** Narrows to one chat of the already selected app (null = all its chats). */
    fun setChatFilter(chatKey: String?) {
        filters.update { if (it.app == null) it else it.copy(chat = chatKey) }
    }

    fun toggleExpanded(id: String) {
        local.update { it.copy(expandedId = if (it.expandedId == id) null else id) }
    }

    /** Unrolls the 10-row preview of the mode on screen into the full, paged list. */
    fun showAll() {
        val shown = state.value
        if (shown.collapsed) paging.value = Paging(shown.mode, showAll = true)
    }

    fun loadMore() {
        val shown = state.value
        if (shown.canLoadMore) paging.update { p -> if (p.mode == shown.mode) p.copy(limit = p.limit + PAGE) else p }
    }

    /** A row was tapped: open the chat it pointed at, else the app; a failure is shown until dismissed. */
    fun open(event: NotificationEvent) {
        val failed = opener.open(event) == OpenResult.FAILED
        local.update { it.copy(openFailure = if (failed) event.appLabel?.takeIf { l -> l.isNotBlank() } ?: event.packageName else null) }
    }

    fun dismissOpenFailure() {
        local.update { it.copy(openFailure = null) }
    }

    private fun chatThrottleOf(event: NotificationEvent, s: Settings): ChatThrottle? {
        val key = event.chatKey ?: return null
        return ChatThrottle(
            packageName = event.packageName,
            chatKey = key,
            title = chatTitle(event, key),
            appLabel = event.appLabel?.takeIf { it.isNotBlank() } ?: event.packageName,
            ruleMinutes = s.chatRule(event.packageName, key)?.minutes,
            appMinutes = s.appThrottleMinutes(event.packageName),
        )
    }

    private fun chatTitle(event: NotificationEvent, key: String): String =
        event.conversationTitle?.takeIf { it.isNotBlank() } ?: event.title?.takeIf { it.isNotBlank() } ?: key

    /** "Throttle this chat" on a row: opens the dialog for the row's chat. */
    fun askChatThrottle(event: NotificationEvent) {
        if (event.chatKey != null) local.update { it.copy(throttleFor = event) }
    }

    fun dismissChatThrottle() {
        local.update { it.copy(throttleFor = null) }
    }

    /**
     * Applies the dialog's choice: an interval, [com.hush.app.focus.INSTANT] (never held),
     * [com.hush.app.focus.MUTED] (hidden for good), or null = no rule, follow the app.
     */
    fun setChatThrottle(minutes: Int?) {
        // The subject comes from the dialog's own source, not from `state`, which lags it and is empty without a collector.
        val event = local.value.throttleFor ?: return
        val key = event.chatKey ?: return
        local.update { it.copy(throttleFor = null) }
        viewModelScope.launch {
            try {
                focusActions.updateRules { s ->
                    if (minutes == null) s.withoutChatRule(event.packageName, key)
                    else s.withChatRule(ChatRule(event.packageName, key, chatTitle(event, key), minutes))
                }
                local.update { it.copy(ruleError = null) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                local.update { it.copy(ruleError = e.message ?: e.javaClass.simpleName) }
            }
        }
    }

    fun dismissRuleError() {
        local.update { it.copy(ruleError = null) }
    }

    /** Leaves the digest batch view ("All history" or navigating away). */
    fun clearDigestLink() {
        signals.digestLink.value = null
    }

    fun refreshSystemState() {
        local.update { it.copy(listenerEnabled = systemStatus.listenerEnabled()) }
    }

    companion object {
        /** Rows in the first view, before "Show all". */
        const val PREVIEW = 10
        const val PAGE = 200
        const val DEBOUNCE_MILLIS = 300L
    }
}
