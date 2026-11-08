package com.hush.app.ui.apps

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hush.app.db.AppSummary
import com.hush.app.db.NotificationRepository
import com.hush.app.settings.Settings
import com.hush.app.settings.SettingsStore
import com.hush.app.ui.Routes
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Which [Settings] set the picker edits; `"hold"` / `"excluded"` are the route arguments of [Routes.appPicker]. */
enum class PickerList(val arg: String) {
    HOLD("hold"),
    EXCLUDED("excluded");

    fun selectedIn(s: Settings): Set<String> = if (this == HOLD) s.holdPackages else s.excludedPackages

    companion object {
        fun from(arg: String?): PickerList = entries.firstOrNull { it.arg == arg } ?: HOLD
    }
}

data class AppRow(val packageName: String, val label: String, val checked: Boolean)

data class AppPickerUiState(
    val list: PickerList = PickerList.HOLD,
    /** True until the package scan returns; the list is hidden behind a spinner meanwhile. */
    val loading: Boolean = true,
    val query: String = "",
    /** Already filtered by [query] and ordered: selected first, then alphabetical by label. */
    val apps: List<AppRow> = emptyList(),
    val selectedCount: Int = 0,
    val loadError: String? = null,
)

/**
 * Merges launchable apps with apps seen in history (and anything still on the list though uninstalled),
 * de-duplicated by package; label falls back to the package name. Selected rows come first, then A→Z.
 */
fun orderApps(installed: List<InstalledApp>, seen: List<AppSummary>, selected: Set<String>, query: String): List<AppRow> {
    val labels = LinkedHashMap<String, String>()
    installed.forEach { labels.putIfAbsent(it.packageName, it.label.ifBlank { it.packageName }) }
    seen.forEach { labels.putIfAbsent(it.packageName, it.appLabel?.takeIf { l -> l.isNotBlank() } ?: it.packageName) }
    selected.forEach { labels.putIfAbsent(it, it) }
    val q = query.trim()
    return labels
        .map { (pkg, label) -> AppRow(pkg, label, checked = pkg in selected) }
        .filter { q.isEmpty() || it.label.contains(q, ignoreCase = true) || it.packageName.contains(q, ignoreCase = true) }
        .sortedWith(compareBy<AppRow> { !it.checked }.thenBy { it.label.lowercase() }.thenBy { it.packageName })
}

@HiltViewModel
class AppPickerViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val appsSource: InstalledAppsSource,
    repository: NotificationRepository,
    private val settingsStore: SettingsStore,
) : ViewModel() {

    val list: PickerList = PickerList.from(savedStateHandle[Routes.ARG_LIST])

    private val query = MutableStateFlow("")
    private val installed = MutableStateFlow<List<InstalledApp>?>(null)
    private val loadError = MutableStateFlow<String?>(null)

    val state: StateFlow<AppPickerUiState> =
        combine(installed, repository.selectApps(), settingsStore.settings, query, loadError) { inst, seen, settings, q, err ->
            val selected = list.selectedIn(settings)
            AppPickerUiState(
                list = list,
                loading = inst == null,
                query = q,
                apps = orderApps(inst.orEmpty(), seen, selected, q),
                selectedCount = selected.size,
                loadError = err,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppPickerUiState(list = list))

    init {
        viewModelScope.launch {
            try {
                installed.value = appsSource.launchable()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Surface the cause; the seen-in-history apps still show so the list is usable.
                loadError.value = e.message ?: e::class.simpleName
                installed.value = emptyList()
            }
        }
    }

    fun setQuery(value: String) {
        query.value = value
    }

    /** Persists immediately; the row order follows from the store update. */
    fun setChecked(packageName: String, checked: Boolean) {
        viewModelScope.launch {
            settingsStore.update { s ->
                val next = if (checked) list.selectedIn(s) + packageName else list.selectedIn(s) - packageName
                when (list) {
                    PickerList.HOLD -> s.copy(holdPackages = next)
                    PickerList.EXCLUDED -> s.copy(excludedPackages = next)
                }
            }
        }
    }
}
