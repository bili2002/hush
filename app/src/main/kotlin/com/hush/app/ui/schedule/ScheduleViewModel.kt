package com.hush.app.ui.schedule

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hush.app.focus.FocusActions
import com.hush.app.focus.ScheduleEntry
import com.hush.app.focus.profileList
import com.hush.app.focus.withScheduleEntry
import com.hush.app.focus.withoutScheduleEntry
import com.hush.app.settings.SettingsStore
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

/** A profile a schedule entry can name. One-time profiles are not offered: they do not outlive their use. */
data class ProfileChoice(val id: String, val name: String)

data class ScheduleUiState(
    val loading: Boolean = true,
    val entries: List<ScheduleEntry> = emptyList(),
    val profiles: List<ProfileChoice> = emptyList(),
    /** The real cause of an edit that could not be saved or applied, until dismissed. */
    val error: String? = null,
)

@HiltViewModel
class ScheduleViewModel(
    settings: SettingsStore,
    private val actions: FocusActions,
    private val newId: () -> String,
) : ViewModel() {

    @Inject
    constructor(settings: SettingsStore, actions: FocusActions) : this(settings, actions, { UUID.randomUUID().toString() })

    private val error = MutableStateFlow<String?>(null)

    val state: StateFlow<ScheduleUiState> = combine(settings.settings, error) { s, err ->
        val profiles = s.profileList.filterNot { it.oneTime }.map { ProfileChoice(it.id, it.name) }
        ScheduleUiState(
            loading = false,
            // An entry whose profile is gone can never take effect; it is not shown either.
            entries = s.profiles.schedule.filter { e -> profiles.any { it.id == e.profileId } },
            profiles = profiles,
            error = err,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ScheduleUiState())

    /**
     * A working-hours entry for the first profile with no day chosen yet, so it does nothing until
     * the owner has set it up: every edit of the schedule takes effect at once, and a ready-made
     * Mon–Fri entry would switch Focus on or off the moment "Add" is tapped.
     */
    fun add() = edit {
        actions.updateRules { s ->
            val profile = s.profileList.firstOrNull { !it.oneTime } ?: return@updateRules s
            s.withScheduleEntry(ScheduleEntry(newId(), profile.id, days = emptySet(), start = 9 * 60, end = 17 * 60))
        }
    }

    fun setProfile(id: String, profileId: String) = change(id) { it.copy(profileId = profileId) }

    /** [day] is ISO, 1 = Monday. */
    fun toggleDay(id: String, day: Int) = change(id) { it.copy(days = if (day in it.days) it.days - day else it.days + day) }

    fun setStart(id: String, minutes: Int) = change(id) { it.copy(start = minutes.coerceIn(0, MINUTES_PER_DAY - 1)) }

    fun setEnd(id: String, minutes: Int) = change(id) { it.copy(end = minutes.coerceIn(0, MINUTES_PER_DAY - 1)) }

    fun remove(id: String) = edit { actions.updateRules { it.withoutScheduleEntry(id) } }

    fun dismissError() {
        error.value = null
    }

    private fun change(id: String, change: (ScheduleEntry) -> ScheduleEntry) = edit {
        actions.updateRules { s -> s.profiles.schedule.firstOrNull { it.id == id }?.let { s.withScheduleEntry(change(it)) } ?: s }
    }

    /** A schedule edit takes effect at once (it may switch profile or turn Focus on or off); a failure says why. */
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

    companion object {
        const val MINUTES_PER_DAY = 24 * 60
    }
}
