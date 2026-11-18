package com.hush.app.ui.rules

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hush.app.db.NotificationRepository
import com.hush.app.focus.CategoryGroup
import com.hush.app.focus.ChatRule
import com.hush.app.focus.FocusActions
import com.hush.app.focus.INSTANT
import com.hush.app.focus.Presets
import com.hush.app.focus.appThrottleMinutes
import com.hush.app.focus.categoryGroups
import com.hush.app.focus.chatRule
import com.hush.app.focus.seededWithPresets
import com.hush.app.focus.withAutoSorted
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
import com.hush.app.ui.apps.InstalledAppsSource
import com.hush.app.ui.components.AppCategoryResolver
import com.hush.app.ui.components.AppLabelResolver
import com.hush.app.ui.components.SheetItem
import com.hush.app.ui.components.UiSignals
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One chat rule as the screen lists it. [appMinutes] is what the chat would get without the rule. */
data class ChatRuleRow(val rule: ChatRule, val appLabel: String, val appMinutes: Int)

/**
 * The "add a chat rule" sheet. Without a [packageName] it lists the apps that have recorded
 * notifications; with one, that app's chats.
 */
data class ChatPicker(val packageName: String?, val appLabel: String?, val items: List<SheetItem>)

data class RulesUiState(
    val loading: Boolean = true,
    val defaultMinutes: Int = 60,
    /** Every category (empty ones too, so they can be filled), then the uncategorised held apps. */
    val groups: List<CategoryGroup> = emptyList(),
    val chatRules: List<ChatRuleRow> = emptyList(),
    /** The real cause of a rule edit that could not be saved or applied, until dismissed. */
    val error: String? = null,
    val chatPicker: ChatPicker? = null,
    /** The chat picked in the sheet, waiting for its throttle. */
    val chatThrottle: ChatThrottle? = null,
    /** How many apps the last automatic sort added, until the next edit; null = it has not run. */
    val autoSorted: Int? = null,
)

/** What is open on top of the list: the picker sheet, then the throttle dialog for the picked chat. */
private data class Overlay(
    val picking: Boolean = false,
    val pickerApp: String? = null,
    val throttleFor: ChatRule? = null,
    val error: String? = null,
    val autoSorted: Int? = null,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class RulesViewModel(
    private val settings: SettingsStore,
    private val actions: FocusActions,
    private val repo: NotificationRepository,
    private val appsSource: InstalledAppsSource,
    private val labels: AppLabelResolver,
    private val categories: AppCategoryResolver,
    private val signals: UiSignals,
    private val newId: () -> String,
) : ViewModel() {

    @Inject
    constructor(
        settings: SettingsStore,
        actions: FocusActions,
        repo: NotificationRepository,
        appsSource: InstalledAppsSource,
        labels: AppLabelResolver,
        categories: AppCategoryResolver,
        signals: UiSignals,
    ) : this(settings, actions, repo, appsSource, labels, categories, signals, { UUID.randomUUID().toString() })

    private val overlay = MutableStateFlow(Overlay())

    /** What the open sheet lists; queried only while it is open. */
    private val picker: Flow<ChatPicker?> = overlay
        .map { if (it.picking) it.pickerApp to true else null to false }
        .distinctUntilChanged()
        .flatMapLatest { (app, open) ->
            when {
                !open -> flowOf(null)
                app == null -> repo.selectApps().map { apps ->
                    ChatPicker(null, null, apps.map { SheetItem(it.packageName, labels.label(it.packageName), it.count, it.packageName) })
                }
                else -> repo.selectChats(app).map { chats ->
                    ChatPicker(app, labels.label(app), chats.map { SheetItem(it.key, it.title, it.count, iconPackage = null) })
                }
            }
        }

    val state: StateFlow<RulesUiState> = combine(settings.settings, overlay, picker) { s, o, p ->
        RulesUiState(
            loading = !s.rules.seeded,
            defaultMinutes = s.slotMinutes,
            groups = s.categoryGroups(labels::label),
            chatRules = s.rules.chatRules
                .map { ChatRuleRow(it, labels.label(it.packageName), s.appThrottleMinutes(it.packageName)) }
                .sortedWith(compareBy({ it.appLabel.lowercase() }, { it.rule.title.lowercase() })),
            error = o.error,
            chatPicker = p,
            chatThrottle = o.throttleFor?.let { chat ->
                ChatThrottle(
                    packageName = chat.packageName,
                    chatKey = chat.chatKey,
                    title = chat.title,
                    appLabel = labels.label(chat.packageName),
                    ruleMinutes = s.chatRule(chat.packageName, chat.chatKey)?.minutes,
                    appMinutes = s.appThrottleMinutes(chat.packageName),
                )
            },
            autoSorted = o.autoSorted,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RulesUiState())

    init {
        seed()
    }

    /**
     * First visit: create the presets and sort the already-held apps into them. With nothing held
     * yet and Focus off, the automatic sort then fills the groups; with Focus already on (through a
     * chat rule) it is left to the button, because it would start hiding apps on the spot. The OS
     * category lookups happen here, outside the settings transform. Also the "Retry" of a failed
     * first visit.
     */
    fun seed() {
        edit {
            val current = settings.current()
            if (!current.rules.seeded) {
                val osCategories = current.holdPackages.associateWith(categories::osCategory)
                actions.updateRules { it.seededWithPresets(osCategories::get) }
                // Unprompted, so it only speaks up when it did something.
                if (current.holdPackages.isEmpty() && !current.focusEnabled) sortAutomatically(reportNothing = false)
            }
        }
    }

    /** Holds every app Hush can put in a preset group and is not held yet. */
    fun autoSort() = edit(keepAutoSorted = true) { sortAutomatically(reportNothing = true) }

    private suspend fun sortAutomatically(reportNothing: Boolean) {
        val before = settings.current().holdPackages
        // One package-manager call per app on the phone: not on the main thread.
        val suggestions = withContext(Dispatchers.IO) {
            val sms = appsSource.defaultSmsPackage()
            // Installed apps plus anything that has notified: an app without a launcher icon can still be noisy.
            (appsSource.launchable().map { it.packageName } + repo.selectApps().first().map { it.packageName })
                .distinct()
                .filterNot { it in before || it == sms }
                .mapNotNull { pkg -> Presets.suggest(pkg, categories.osCategory(pkg), automatic = true)?.let { pkg to it } }
                .toMap()
        }
        actions.updateRules { it.withAutoSorted(suggestions) }
        val added = (settings.current().holdPackages - before).size
        if (added > 0 || reportNothing) overlay.update { it.copy(autoSorted = added) }
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

    /** Opens the chat sheet: for [packageName], or with null on the app list first. */
    fun addChatRule(packageName: String?) {
        overlay.update { it.copy(picking = true, pickerApp = packageName) }
    }

    /**
     * A row of the sheet was picked: an app leads on to its chats, a chat to its throttle. [step] is
     * the [ChatPicker.packageName] of the list the row was shown in; a tap on a list that is no
     * longer the current step (a second tap before the chats loaded) is ignored, or an app row
     * would be taken for a chat.
     */
    fun pick(step: String?, item: SheetItem) {
        overlay.update { o ->
            when {
                !o.picking || step != o.pickerApp -> o
                o.pickerApp == null -> o.copy(pickerApp = item.key)
                // Minutes are a placeholder: the rule does not exist until the dialog's choice is applied.
                else -> o.copy(picking = false, pickerApp = null, throttleFor = ChatRule(o.pickerApp, item.key, item.label, minutes = INSTANT))
            }
        }
    }

    fun dismissChatPicker() {
        overlay.update { it.copy(picking = false, pickerApp = null) }
    }

    /** The dialog's choice for the picked chat: a throttle, or null = no rule of its own. */
    fun setChatThrottle(minutes: Int?) {
        val chat = overlay.value.throttleFor ?: return
        overlay.update { it.copy(throttleFor = null) }
        update { if (minutes == null) it.withoutChatRule(chat.packageName, chat.chatKey) else it.withChatRule(chat.copy(minutes = minutes)) }
    }

    fun dismissChatThrottle() {
        overlay.update { it.copy(throttleFor = null) }
    }

    fun dismissError() {
        overlay.update { it.copy(error = null) }
    }

    private fun update(transform: (Settings) -> Settings) = edit { actions.updateRules(transform) }

    /**
     * A rule edit also re-runs delivery; if that (or the write) fails, say why instead of silently
     * keeping the old rules. Any edit clears the "added N apps" line, except the sort that sets it.
     */
    private fun edit(keepAutoSorted: Boolean = false, block: suspend () -> Unit) {
        viewModelScope.launch {
            if (!keepAutoSorted) overlay.update { it.copy(autoSorted = null) }
            try {
                block()
                overlay.update { it.copy(error = null) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                overlay.update { it.copy(error = e.message ?: e.javaClass.simpleName) }
            }
        }
    }
}
