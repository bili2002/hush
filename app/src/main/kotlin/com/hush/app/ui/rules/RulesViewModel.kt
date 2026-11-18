package com.hush.app.ui.rules

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hush.app.focus.CategoryGroup
import com.hush.app.focus.ChatRule
import com.hush.app.focus.FocusActions
import com.hush.app.focus.appThrottleMinutes
import com.hush.app.focus.categoryGroups
import com.hush.app.focus.seededWithPresets
import com.hush.app.focus.withAppInCategory
import com.hush.app.focus.withCategoryAdded
import com.hush.app.focus.withCategoryMinutes
import com.hush.app.focus.withCategoryRenamed
import com.hush.app.focus.withChatRule
import com.hush.app.focus.withoutCategory
import com.hush.app.focus.withoutChatRule
import com.hush.app.focus.withoutHeldApp
import com.hush.app.settings.Settings
import com.hush.app.settings.SettingsStore
import com.hush.app.ui.Routes
import com.hush.app.ui.components.AppCategoryResolver
import com.hush.app.ui.components.AppLabelResolver
import com.hush.app.ui.components.UiSignals
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** One chat rule as the screen lists it. [appMinutes] is what the chat would get without the rule. */
data class ChatRuleRow(val rule: ChatRule, val appLabel: String, val appMinutes: Int)

data class RulesUiState(
    val loading: Boolean = true,
    val defaultMinutes: Int = 60,
    /** Every category (empty ones too, so they can be filled), then the uncategorised held apps. */
    val groups: List<CategoryGroup> = emptyList(),
    val chatRules: List<ChatRuleRow> = emptyList(),
    /** The real cause of a rule edit that could not be saved or applied, until dismissed. */
    val error: String? = null,
)

@HiltViewModel
class RulesViewModel(
    private val settings: SettingsStore,
    private val actions: FocusActions,
    private val labels: AppLabelResolver,
    private val categories: AppCategoryResolver,
    private val signals: UiSignals,
    private val newId: () -> String,
) : ViewModel() {

    @Inject
    constructor(
        settings: SettingsStore,
        actions: FocusActions,
        labels: AppLabelResolver,
        categories: AppCategoryResolver,
        signals: UiSignals,
    ) : this(settings, actions, labels, categories, signals, { UUID.randomUUID().toString() })

    private val error = MutableStateFlow<String?>(null)

    val state: StateFlow<RulesUiState> = combine(settings.settings, error) { s, err ->
        RulesUiState(
            loading = !s.rules.seeded,
            defaultMinutes = s.slotMinutes,
            groups = s.categoryGroups(labels::label),
            chatRules = s.rules.chatRules
                .map { ChatRuleRow(it, labels.label(it.packageName), s.appThrottleMinutes(it.packageName)) }
                .sortedWith(compareBy({ it.appLabel.lowercase() }, { it.rule.title.lowercase() })),
            error = err,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RulesUiState())

    init {
        seed()
    }

    /**
     * First visit: create the presets and sort the already-held apps into them. The OS category
     * lookups happen here, outside the settings transform. Also the "Retry" of a failed first visit.
     */
    fun seed() {
        edit {
            val current = settings.current()
            if (!current.rules.seeded) {
                val osCategories = current.holdPackages.associateWith(categories::osCategory)
                actions.updateRules { it.seededWithPresets(osCategories::get) }
            }
        }
    }

    fun setDefaultMinutes(minutes: Int) = edit { actions.setSlotMinutes(minutes) }

    /** [minutes] null = follow the default interval. */
    fun setCategoryMinutes(id: String, minutes: Int?) = update { it.withCategoryMinutes(id, minutes) }

    fun addCategory(name: String) {
        if (name.isNotBlank()) update { it.withCategoryAdded(newId(), name) }
    }

    fun renameCategory(id: String, name: String) {
        if (name.isNotBlank()) update { it.withCategoryRenamed(id, name) }
    }

    fun deleteCategory(id: String) = update { it.withoutCategory(id) }

    /** [categoryId] null = no category (default interval). */
    fun moveApp(packageName: String, categoryId: String?) = update { it.withAppInCategory(packageName, categoryId) }

    fun stopHolding(packageName: String) = update { it.withoutHeldApp(packageName) }

    fun setChatRuleMinutes(rule: ChatRule, minutes: Int) = update { it.withChatRule(rule.copy(minutes = minutes)) }

    fun removeChatRule(rule: ChatRule) = update { it.withoutChatRule(rule.packageName, rule.chatKey) }

    fun addApps() {
        signals.requestNavigation(Routes.appPicker("hold"))
    }

    fun dismissError() {
        error.value = null
    }

    private fun update(transform: (Settings) -> Settings) = edit { actions.updateRules(transform) }

    /** A rule edit also re-runs delivery; if that (or the write) fails, say why instead of silently keeping the old rules. */
    private fun edit(block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
                error.value = null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error.value = e.message ?: e.javaClass.simpleName
            }
        }
    }
}
