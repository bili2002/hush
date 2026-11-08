package com.hush.app.ui.focus

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hush.app.db.NotificationRepository
import com.hush.app.focus.DeliverResult
import com.hush.app.focus.FocusActions
import com.hush.app.focus.nextSlot
import com.hush.app.settings.Settings
import com.hush.app.settings.SettingsStore
import com.hush.app.ui.Routes
import com.hush.app.ui.components.AppLabelResolver
import com.hush.app.ui.components.SystemSnapshot
import com.hush.app.ui.components.SystemStatus
import com.hush.app.ui.components.UiSignals
import com.hush.app.ui.components.snapshot
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Clock
import java.time.ZoneId
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Why the Focus toggle is disabled. Each maps to one tappable row that fixes it. */
enum class GateReason { NO_LISTENER_ACCESS, NO_POST_PERMISSION, EMPTY_HOLD_LIST }

data class Checklist(
    val listenerAccess: Boolean,
    val postPermission: Boolean,
    val batteryExempt: Boolean,
    val holdListNonEmpty: Boolean,
) {
    val allDone: Boolean get() = listenerAccess && postPermission && batteryExempt && holdListNonEmpty
}

sealed interface ListenerHealth {
    /** [agoMillis] = now − `listenerConnectedAt`, refreshed by the minute ticker. */
    data class Connected(val agoMillis: Long) : ListenerHealth
    data object Disconnected : ListenerHealth
}

data class FocusUiState(
    val loading: Boolean = true,
    val enabled: Boolean = false,
    val slotMinutes: Int = 60,
    val heldCount: Long = 0,
    /** Epoch millis of the next digest slot; the screen formats it in the device zone. */
    val nextSlotAt: Long? = null,
    val holdLabels: List<String> = emptyList(),
    /** Toggle is disabled iff non-empty. */
    val gateReasons: List<GateReason> = emptyList(),
    /** Null = card collapsed (setup already done, or all four rows satisfied). */
    val checklist: Checklist? = null,
    val listener: ListenerHealth = ListenerHealth.Disconnected,
    val lastFocusError: String? = null,
    val delivering: Boolean = false,
    /** Last "Deliver now" outcome until dismissed. */
    val deliverResult: DeliverResult? = null,
) {
    /** OFF → ON is allowed only when nothing gates it. */
    val canToggle: Boolean get() = !loading && gateReasons.isEmpty()

    /** The switch itself: turning Focus OFF is always allowed, so a gated-while-on state can be exited. */
    val switchEnabled: Boolean get() = !loading && (enabled || gateReasons.isEmpty())

    /** Focus is on but a gate reappeared (access revoked, hold list emptied): nothing will be held/delivered. */
    val onButGated: Boolean get() = enabled && gateReasons.isNotEmpty()
}

private data class Local(val delivering: Boolean = false, val deliverResult: DeliverResult? = null)

val SLOT_CHOICES = listOf(15, 30, 60, 120)

@HiltViewModel
class FocusViewModel(
    private val settings: SettingsStore,
    repo: NotificationRepository,
    private val actions: FocusActions,
    private val systemStatus: SystemStatus,
    private val labels: AppLabelResolver,
    private val signals: UiSignals,
    private val clock: Clock,
    private val zone: ZoneId,
    tickMillis: Long,
) : ViewModel() {

    @Inject
    constructor(
        settings: SettingsStore,
        repo: NotificationRepository,
        actions: FocusActions,
        systemStatus: SystemStatus,
        labels: AppLabelResolver,
        signals: UiSignals,
        clock: Clock,
    ) : this(settings, repo, actions, systemStatus, labels, signals, clock, ZoneId.systemDefault(), 60_000L)

    private val system = MutableStateFlow(systemStatus.snapshot())
    private val local = MutableStateFlow(Local())

    /** Emits `now` immediately and then once per [tickMillis]; drives "Next digest at" and "connected N min ago". */
    private val ticker = flow {
        while (true) {
            emit(clock.millis())
            delay(tickMillis)
        }
    }

    val state: StateFlow<FocusUiState> =
        combine(settings.settings, repo.countHeld(), system, ticker, local) { s, held, sys, now, loc ->
            build(s, held, sys, now, loc)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), FocusUiState())

    init {
        // The checklist card collapses for good the first time every row is green. Kept out of the
        // state combine so the derivation stays pure; a failed write just leaves the card for next time.
        viewModelScope.launch {
            combine(settings.settings, system) { s, sys -> !s.setupDone && checklistOf(s, sys).allDone }.first { it }
            runCatching { settings.update { it.copy(setupDone = true) } }
        }
    }

    private fun checklistOf(s: Settings, sys: SystemSnapshot) = Checklist(
        listenerAccess = sys.listenerEnabled,
        postPermission = sys.postNotificationsGranted,
        batteryExempt = sys.batteryExempt,
        holdListNonEmpty = s.holdPackages.isNotEmpty(),
    )

    private fun build(s: Settings, held: Long, sys: SystemSnapshot, now: Long, loc: Local): FocusUiState {
        val gate = buildList {
            if (!sys.listenerEnabled) add(GateReason.NO_LISTENER_ACCESS)
            if (!sys.postNotificationsGranted) add(GateReason.NO_POST_PERMISSION)
            if (s.holdPackages.isEmpty()) add(GateReason.EMPTY_HOLD_LIST)
        }
        val checklist = checklistOf(s, sys)
        val connectedAt = s.listenerConnectedAt
        val disconnectedAt = s.listenerDisconnectedAt
        val listener = if (connectedAt == null || (disconnectedAt != null && disconnectedAt > connectedAt)) {
            ListenerHealth.Disconnected
        } else {
            ListenerHealth.Connected(agoMillis = (now - connectedAt).coerceAtLeast(0))
        }
        return FocusUiState(
            loading = false,
            enabled = s.focusEnabled,
            slotMinutes = s.slotMinutes,
            heldCount = held,
            nextSlotAt = nextSlot(now, s.slotMinutes, zone),
            holdLabels = s.holdPackages.map(labels::label).sortedBy { it.lowercase() },
            gateReasons = gate,
            checklist = if (s.setupDone || checklist.allDone) null else checklist,
            listener = listener,
            lastFocusError = s.lastFocusError,
            delivering = loc.delivering,
            deliverResult = loc.deliverResult,
        )
    }

    /** Re-reads the OS facts; the screen calls this on resume so a settings round-trip is reflected. */
    fun refreshSystemState() {
        system.value = systemStatus.snapshot()
    }

    fun setEnabled(enabled: Boolean) {
        viewModelScope.launch { actions.setEnabled(enabled) }
    }

    fun setSlotMinutes(minutes: Int) {
        viewModelScope.launch { actions.setSlotMinutes(minutes) }
    }

    fun deliverNow() {
        if (local.value.delivering) return
        viewModelScope.launch {
            local.update { it.copy(delivering = true, deliverResult = null) }
            val result = try {
                actions.deliverNow()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                DeliverResult(0, e.message ?: e.javaClass.simpleName)
            }
            local.update { it.copy(delivering = false, deliverResult = result) }
        }
    }

    fun dismissDeliverResult() {
        local.update { it.copy(deliverResult = null) }
    }

    fun editHoldList() {
        signals.requestNavigation(Routes.appPicker("hold"))
    }
}
