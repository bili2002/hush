package com.hush.app.ui.focus

import androidx.lifecycle.viewModelScope
import com.hush.app.db.NotificationRepository
import com.hush.app.focus.DeliverResult
import com.hush.app.settings.Settings
import com.hush.app.testing.FakeFocusActions
import com.hush.app.testing.FakeSettingsStore
import com.hush.app.testing.FakeSystemStatus
import com.hush.app.testing.MainDispatcherRule
import com.hush.app.testing.event
import com.hush.app.testing.testRepository
import com.hush.app.ui.components.UiSignals
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Rule

class FocusViewModelTest {

    @get:Rule
    val mainDispatcher = MainDispatcherRule()

    private val now: Long = Instant.parse("2024-01-10T13:17:00Z").toEpochMilli()
    private val clock: Clock = Clock.fixed(Instant.ofEpochMilli(now), ZoneOffset.UTC)
    private val repo: NotificationRepository = testRepository(clock)
    private val system = FakeSystemStatus()
    private val signals = UiSignals()
    private val vms = mutableListOf<FocusViewModel>()

    private fun vm(
        settings: FakeSettingsStore = FakeSettingsStore(Settings(holdPackages = setOf("com.example.alpha"))),
        actions: FakeFocusActions = FakeFocusActions(settings),
    ): FocusViewModel = FocusViewModel(
        settings = settings,
        repo = repo,
        actions = actions,
        systemStatus = system,
        labels = { it.substringAfterLast('.').replaceFirstChar(Char::uppercase) },
        signals = signals,
        clock = clock,
        zone = ZoneOffset.UTC,
        // Never ticks in tests: the first emission is immediate and the clock is fixed anyway.
        tickMillis = Long.MAX_VALUE,
    ).also { vms += it }

    @After
    fun tearDown() = vms.forEach { it.viewModelScope.cancel() }

    private suspend fun FocusViewModel.loaded(): FocusUiState = state.first { !it.loading }

    private fun at(iso: String) = Instant.parse(iso).toEpochMilli()

    @Test
    fun `next slot follows the slot size from a fixed clock`() = runTest {
        val settings = FakeSettingsStore(Settings(focusEnabled = true, slotMinutes = 60, holdPackages = setOf("com.example.alpha")))
        val vm = vm(settings)

        assertEquals(at("2024-01-10T14:00:00Z"), vm.loaded().nextSlotAt)

        settings.update { it.copy(slotMinutes = 15) }
        assertEquals(at("2024-01-10T13:30:00Z"), vm.state.first { it.slotMinutes == 15 }.nextSlotAt)

        settings.update { it.copy(slotMinutes = 120) }
        assertEquals(at("2024-01-10T14:00:00Z"), vm.state.first { it.slotMinutes == 120 }.nextSlotAt)
    }

    @Test
    fun `held count comes from the repository`() = runTest {
        repo.insert(event("h1", held = true))
        repo.insert(event("h2", held = true))
        repo.insert(event("p", held = false))
        repo.insert(event("d", held = true))
        repo.markDelivered(listOf("d"), deliveredAt = now)

        assertEquals(2L, vm().loaded().heldCount)
    }

    @Test
    fun `all clear means no gate reasons and the toggle is enabled`() = runTest {
        val s = vm().loaded()
        assertTrue(s.gateReasons.isEmpty())
        assertTrue(s.canToggle)
    }

    @Test
    fun `missing notification access gates the toggle`() = runTest {
        system.listener = false
        val s = vm().loaded()
        assertEquals(listOf(GateReason.NO_LISTENER_ACCESS), s.gateReasons)
        assertFalse(s.canToggle)
    }

    @Test
    fun `focus already on with a gate reappearing is reported as on-but-gated and can still be switched off`() = runTest {
        system.listener = false
        val s = vm(FakeSettingsStore(Settings(focusEnabled = true, holdPackages = setOf("com.example.alpha")))).loaded()

        assertTrue(s.enabled)
        assertEquals(listOf(GateReason.NO_LISTENER_ACCESS), s.gateReasons)
        assertTrue(s.onButGated)
        assertFalse(s.canToggle, "OFF -> ON would still be refused")
        assertTrue(s.switchEnabled, "but the switch stays usable so Focus can be turned off")
    }

    @Test
    fun `switch is disabled only when off and gated`() = runTest {
        system.postPermission = false
        val off = vm(FakeSettingsStore(Settings(focusEnabled = false, holdPackages = setOf("com.example.alpha")))).loaded()
        assertFalse(off.switchEnabled)
        assertFalse(off.onButGated)

        system.postPermission = true
        val clear = vm(FakeSettingsStore(Settings(holdPackages = setOf("com.example.alpha")))).loaded()
        assertTrue(clear.switchEnabled)
    }

    @Test
    fun `missing POST_NOTIFICATIONS gates the toggle`() = runTest {
        system.postPermission = false
        val s = vm().loaded()
        assertEquals(listOf(GateReason.NO_POST_PERMISSION), s.gateReasons)
        assertFalse(s.canToggle)
    }

    @Test
    fun `empty hold list gates the toggle`() = runTest {
        val s = vm(FakeSettingsStore(Settings())).loaded()
        assertEquals(listOf(GateReason.EMPTY_HOLD_LIST), s.gateReasons)
        assertFalse(s.canToggle)
    }

    @Test
    fun `refreshSystemState picks up a changed OS fact`() = runTest {
        system.listener = false
        val vm = vm()
        assertEquals(listOf(GateReason.NO_LISTENER_ACCESS), vm.loaded().gateReasons)

        system.listener = true
        vm.refreshSystemState()

        assertTrue(vm.state.first { it.gateReasons.isEmpty() }.canToggle)
    }

    @Test
    fun `checklist reports each row and stays visible while incomplete`() = runTest {
        system.batteryExempt = false
        system.postPermission = false
        val settings = FakeSettingsStore(Settings())

        val checklist = vm(settings).loaded().checklist

        assertNotNull(checklist)
        assertEquals(
            Checklist(listenerAccess = true, postPermission = false, batteryExempt = false, holdListNonEmpty = false),
            checklist,
        )
        assertFalse(checklist.allDone)
        assertFalse(settings.state.value.setupDone)
    }

    @Test
    fun `checklist collapses and setupDone is written once all four are satisfied`() = runTest {
        val settings = FakeSettingsStore(Settings(holdPackages = setOf("com.example.alpha")))

        val s = vm(settings).state.first { !it.loading && it.checklist == null }

        assertNull(s.checklist)
        assertTrue(settings.state.value.setupDone)
    }

    @Test
    fun `setupDone hides the checklist even when rows are incomplete`() = runTest {
        system.listener = false
        val s = vm(FakeSettingsStore(Settings(setupDone = true))).loaded()

        assertNull(s.checklist)
        assertEquals(listOf(GateReason.NO_LISTENER_ACCESS, GateReason.EMPTY_HOLD_LIST), s.gateReasons)
    }

    @Test
    fun `deliverNow surfaces the delivered count`() = runTest {
        val settings = FakeSettingsStore(Settings(focusEnabled = true, holdPackages = setOf("com.example.alpha")))
        val actions = FakeFocusActions(settings, nextResult = DeliverResult(7))
        val vm = vm(settings, actions)
        vm.loaded()

        vm.deliverNow()

        val s = vm.state.first { it.deliverResult != null }
        assertEquals(DeliverResult(7), s.deliverResult)
        assertFalse(s.delivering)
        assertEquals(1, actions.deliverCalls)

        vm.dismissDeliverResult()
        assertNull(vm.state.first { it.deliverResult == null }.deliverResult)
    }

    @Test
    fun `deliverNow surfaces an error result verbatim`() = runTest {
        val settings = FakeSettingsStore(Settings(focusEnabled = true, holdPackages = setOf("com.example.alpha")))
        val vm = vm(settings, FakeFocusActions(settings, nextResult = DeliverResult(0, "digest channel blocked")))
        vm.loaded()

        vm.deliverNow()

        assertEquals("digest channel blocked", vm.state.first { it.deliverResult != null }.deliverResult?.error)
    }

    @Test
    fun `deliverNow turns a thrown exception into an error result instead of crashing`() = runTest {
        val settings = FakeSettingsStore(Settings(focusEnabled = true, holdPackages = setOf("com.example.alpha")))
        val vm = vm(settings, FakeFocusActions(settings, nextError = IllegalStateException("alarm manager unavailable")))
        vm.loaded()

        vm.deliverNow()

        assertEquals("alarm manager unavailable", vm.state.first { it.deliverResult != null }.deliverResult?.error)
    }

    @Test
    fun `toggle and slot go through FocusActions and persist`() = runTest {
        val settings = FakeSettingsStore(Settings(holdPackages = setOf("com.example.alpha")))
        val vm = vm(settings)
        vm.loaded()

        vm.setEnabled(true)
        vm.setSlotMinutes(30)

        val s = vm.state.first { it.enabled && it.slotMinutes == 30 }
        assertTrue(s.enabled)
        assertEquals(30, settings.state.value.slotMinutes)
    }

    @Test
    fun `listener health is red when never connected or disconnected after connecting`() = runTest {
        assertIs<ListenerHealth.Disconnected>(vm(FakeSettingsStore(Settings())).loaded().listener)

        val dropped = FakeSettingsStore(Settings(listenerConnectedAt = now - 10 * 60_000L, listenerDisconnectedAt = now - 60_000L))
        assertIs<ListenerHealth.Disconnected>(vm(dropped).loaded().listener)
    }

    @Test
    fun `listener health is green with elapsed time when connected most recently`() = runTest {
        val s = vm(FakeSettingsStore(Settings(listenerConnectedAt = now - 2 * 60_000L, listenerDisconnectedAt = now - 60 * 60_000L))).loaded()

        val health = assertIs<ListenerHealth.Connected>(s.listener)
        assertEquals(2 * 60_000L, health.agoMillis)
    }

    @Test
    fun `lastFocusError and hold labels pass through`() = runTest {
        val s = vm(
            FakeSettingsStore(Settings(holdPackages = setOf("com.example.beta", "com.example.alpha"), lastFocusError = "boom")),
        ).loaded()

        assertEquals("boom", s.lastFocusError)
        assertEquals(listOf("Alpha", "Beta"), s.holdLabels)
    }

    @Test
    fun `editHoldList asks the shell to open the hold picker`() = runTest {
        var route: String? = null
        val job = launch(start = CoroutineStart.UNDISPATCHED) { signals.navigate.collect { route = it } }

        vm().editHoldList()
        runCurrent()

        assertEquals("apps/hold", route)
        job.cancel()
    }
}
