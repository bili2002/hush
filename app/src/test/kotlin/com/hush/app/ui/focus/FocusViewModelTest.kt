package com.hush.app.ui.focus

import com.hush.app.db.NotificationRepository
import com.hush.app.focus.DeliverResult
import com.hush.app.focus.CategoryGroup
import com.hush.app.focus.ChatRule
import com.hush.app.focus.FocusCategory
import com.hush.app.focus.FocusRules
import com.hush.app.focus.HeldApp
import com.hush.app.focus.INSTANT
import com.hush.app.focus.ScheduleEntry
import com.hush.app.focus.withManualHold
import com.hush.app.focus.withProfileAdded
import com.hush.app.focus.withProfileDnd
import com.hush.app.focus.withScheduleEntry
import com.hush.app.settings.Settings
import com.hush.app.testing.FakeFocusActions
import com.hush.app.testing.FakeSettingsStore
import com.hush.app.testing.FakeSystemStatus
import com.hush.app.testing.MainDispatcherRule
import com.hush.app.testing.cancelAndJoin
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
        newId = { "new-id" },
    ).also { vms += it }

    @After
    fun tearDown() = vms.forEach { it.cancelAndJoin() }

    private suspend fun FocusViewModel.loaded(): FocusUiState = state.first { !it.loading }

    private fun at(iso: String) = Instant.parse(iso).toEpochMilli()

    @Test
    fun `next slot follows the slot size from a fixed clock`() = runTest {
        val settings = FakeSettingsStore(Settings(focusEnabled = true, slotMinutes = 60, holdPackages = setOf("com.example.alpha")))
        val vm = vm(settings)

        assertEquals(at("2024-01-10T14:00:00Z"), vm.loaded().nextSlotAt)

        settings.update { it.copy(slotMinutes = 15) }
        val quarter = at("2024-01-10T13:30:00Z")
        assertEquals(quarter, vm.state.first { it.nextSlotAt == quarter }.nextSlotAt)

        settings.update { it.copy(slotMinutes = 120) }
        val evenHour = at("2024-01-10T14:00:00Z")
        assertEquals(evenHour, vm.state.first { it.nextSlotAt == evenHour }.nextSlotAt)
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
        assertEquals(listOf(GateReason.NOTHING_HELD), s.gateReasons)
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
            Checklist(listenerAccess = true, postPermission = false, batteryExempt = false, holdsSomething = false),
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
        assertEquals(listOf(GateReason.NO_LISTENER_ACCESS, GateReason.NOTHING_HELD), s.gateReasons)
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
    fun `toggle goes through FocusActions and persists`() = runTest {
        val settings = FakeSettingsStore(Settings(holdPackages = setOf("com.example.alpha")))
        val vm = vm(settings)
        vm.loaded()

        vm.setEnabled(true)

        assertTrue(vm.state.first { it.enabled }.enabled)
        assertTrue(settings.state.value.focusEnabled)
    }

    @Test
    fun `next digest follows the held rows' own intervals`() = runTest {
        // alpha is in a 15-minute group, beta uses the 60-minute default. Now is 13:17.
        val settings = FakeSettingsStore(
            Settings(
                focusEnabled = true,
                holdPackages = setOf("com.example.alpha", "com.example.beta"),
                rules = FocusRules(categories = listOf(FocusCategory("fast", "Fast", 15)), appCategory = mapOf("com.example.alpha" to "fast")),
            ),
        )
        val vm = vm(settings)
        assertEquals(at("2024-01-10T13:30:00Z"), vm.loaded().nextSlotAt, "nothing held: the next alarm, i.e. the shortest interval's boundary")

        repo.insert(event("b1", packageName = "com.example.beta", postTime = at("2024-01-10T13:10:00Z"), held = true))
        assertEquals(at("2024-01-10T14:00:00Z"), vm.state.first { it.heldCount == 1L }.nextSlotAt, "the only held row waits for the hour")

        repo.insert(event("a1", packageName = "com.example.alpha", postTime = at("2024-01-10T13:16:00Z"), held = true))
        assertEquals(at("2024-01-10T13:30:00Z"), vm.state.first { it.heldCount == 2L }.nextSlotAt, "the 15-minute row comes first")

        repo.insert(event("a0", packageName = "com.example.alpha", postTime = at("2024-01-10T12:50:00Z"), held = true))
        assertEquals(at("2024-01-10T13:30:00Z"), vm.state.first { it.heldCount == 3L }.nextSlotAt, "an overdue row goes out with the next alarm")
    }

    @Test
    fun `gate looks at what would actually be held, not at the hold list alone`() = runTest {
        val instantOnly = Settings(
            holdPackages = setOf("com.example.alpha"),
            rules = FocusRules(categories = listOf(FocusCategory("off", "Off", INSTANT)), appCategory = mapOf("com.example.alpha" to "off")),
        )
        assertEquals(listOf(GateReason.NOTHING_HELD), vm(FakeSettingsStore(instantOnly)).loaded().gateReasons)

        val chatOnly = Settings(rules = FocusRules(chatRules = listOf(ChatRule("com.example.alpha", "c1", "Group one", 60))))
        assertTrue(vm(FakeSettingsStore(chatOnly)).loaded().gateReasons.isEmpty(), "a holding chat rule is enough")
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
    fun `lastFocusError and the rule summary pass through`() = runTest {
        val s = vm(
            FakeSettingsStore(
                Settings(
                        holdPackages = setOf("com.example.beta", "com.example.alpha", "com.example.gamma"),
                    lastFocusError = "boom",
                    rules = FocusRules(
                        categories = listOf(FocusCategory("fast", "Fast", 15), FocusCategory("empty", "Empty", 30)),
                        appCategory = mapOf("com.example.gamma" to "fast"),
                        chatRules = listOf(ChatRule("com.example.alpha", "c1", "Group one", 240)),
                    ),
                ),
            ),
        ).loaded()

        assertEquals("boom", s.lastFocusError)
        assertEquals(
            listOf(
                CategoryGroup("fast", "Fast", 15, 15, listOf(HeldApp("com.example.gamma", "Gamma"))),
                CategoryGroup(null, null, null, 60, listOf(HeldApp("com.example.alpha", "Alpha"), HeldApp("com.example.beta", "Beta"))),
            ),
            s.groups,
            "empty groups are left out of the summary",
        )
        assertEquals(1, s.chatRuleCount)
    }

    @Test
    fun `manageRules asks the shell to open Apps and throttles`() = runTest {
        var route: String? = null
        val job = launch(start = CoroutineStart.UNDISPATCHED) { signals.navigate.collect { route = it } }

        vm().manageRules()
        runCurrent()

        assertEquals("focus/rules", route)
        job.cancel()
    }

    // ---- profiles and the schedule ----

    private val twoProfiles = Settings(holdPackages = setOf("com.example.alpha"))
        .withProfileAdded("p2", "Evening", copyOf = null, oneTime = false)
        .withProfileDnd("p2", true)

    @Test
    fun `the profile list marks the active one, and a pick goes through FocusActions`() = runTest {
        val settings = FakeSettingsStore(twoProfiles)
        val vm = vm(settings)

        assertEquals(
            listOf(ProfileRow("default", "Default", active = true, oneTime = false, dnd = false), ProfileRow("p2", "Evening", active = false, oneTime = false, dnd = true)),
            vm.loaded().profiles,
        )
        assertNull(vm.loaded().schedule)

        vm.selectProfile("p2")
        assertEquals(listOf(false, true), vm.state.first { it.profiles.last().active }.profiles.map { it.active })
        assertEquals("p2", settings.state.value.profiles.activeId)
    }

    @Test
    fun `settings without profiles still list one, called Default`() = runTest {
        assertEquals(listOf(ProfileRow("default", "Default", active = true, oneTime = false, dnd = false)), vm().loaded().profiles)
    }

    @Test
    fun `a new profile is opened for editing, a one-time one is also switched to, and a blank name adds nothing`() = runTest {
        val routes = mutableListOf<String>()
        val job = launch(start = CoroutineStart.UNDISPATCHED) { signals.navigate.collect { routes += it } }
        val settings = FakeSettingsStore(twoProfiles)
        val vm = vm(settings)
        vm.loaded()

        vm.addProfile("  ", copyOf = null, oneTime = false)
        vm.addProfile("Trip", copyOf = "p2", oneTime = true)
        val s = vm.state.first { it.profiles.size == 3 && it.profiles.last().active }

        assertEquals(ProfileRow("new-id", "Trip", active = true, oneTime = true, dnd = true), s.profiles.last())
        runCurrent()
        assertEquals(listOf("focus/rules?profile=new-id"), routes)

        vm.editProfile("p2")
        runCurrent()
        vm.openSchedule()
        runCurrent()
        assertEquals(listOf("focus/rules?profile=new-id", "focus/rules?profile=p2", "focus/schedule"), routes)
        job.cancel()
    }

    @Test
    fun `rename and delete are persisted, and a failure shows its cause until the next action works`() = runTest {
        val settings = FakeSettingsStore(twoProfiles)
        val actions = FakeFocusActions(settings)
        val vm = vm(settings, actions)
        vm.loaded()

        vm.renameProfile("p2", "Night")
        assertEquals("Night", vm.state.first { it.profiles.last().name == "Night" }.profiles.last().name)

        actions.rulesError = IllegalStateException("disk full")
        vm.deleteProfile("p2")
        assertEquals("disk full", vm.state.first { it.lastFocusError != null }.lastFocusError)
        assertEquals(2, settings.state.value.profiles.list.size)

        actions.rulesError = null
        vm.deleteProfile("p2")
        val s = vm.state.first { it.profiles.size == 1 }
        assertNull(s.lastFocusError)
    }

    @Test
    fun `the schedule shows what is in force now, when it changes next, and when a manual choice stands`() = runTest {
        // The clock says Wednesday 13:17 UTC; stored as off, but an entry has been running since 13:00.
        val entry = ScheduleEntry("e", "p2", setOf(3), 13 * 60, 15 * 60)
        val settings = FakeSettingsStore(twoProfiles.withScheduleEntry(entry))
        val vm = vm(settings)

        val s = vm.loaded()
        assertTrue(s.enabled, "shown as the gate treats it, before the alarm wrote it down")
        assertEquals(listOf(false, true), s.profiles.map { it.active })
        assertEquals(ScheduleStatus.Following(now + 103 * 60_000L), s.schedule)

        val midnight = Instant.parse("2024-01-11T00:00:00Z").toEpochMilli()
        settings.update { it.copy(focusEnabled = false).withManualHold(now, ZoneOffset.UTC) }
        val manual = vm.state.first { it.schedule is ScheduleStatus.Manual }
        assertEquals(ScheduleStatus.Manual(midnight), manual.schedule)
        assertFalse(manual.enabled)

        vm.resumeSchedule()
        assertIs<ScheduleStatus.Following>(vm.state.first { it.schedule is ScheduleStatus.Following }.schedule)
    }

    @Test
    fun `a Do Not Disturb failure is shown`() = runTest {
        val settings = FakeSettingsStore(twoProfiles.let { it.copy(profiles = it.profiles.copy(dndError = "no access")) })
        assertEquals("no access", vm(settings).loaded().dndError)
    }
}
