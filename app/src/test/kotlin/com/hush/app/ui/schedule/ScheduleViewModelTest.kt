package com.hush.app.ui.schedule

import com.hush.app.focus.ScheduleEntry
import com.hush.app.focus.hasSchedule
import com.hush.app.focus.withProfileAdded
import com.hush.app.settings.Settings
import com.hush.app.testing.FakeFocusActions
import com.hush.app.testing.FakeSettingsStore
import com.hush.app.testing.MainDispatcherRule
import com.hush.app.testing.cancelAndJoin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Rule

class ScheduleViewModelTest {

    @get:Rule
    val mainDispatcher = MainDispatcherRule()

    private val vms = mutableListOf<ScheduleViewModel>()
    private val profiles = Settings()
        .withProfileAdded("p2", "Evening", copyOf = null, oneTime = false)
        .withProfileAdded("once", "Tonight", copyOf = null, oneTime = true)

    private fun vm(settings: FakeSettingsStore, actions: FakeFocusActions = FakeFocusActions(settings)): ScheduleViewModel {
        var n = 0
        return ScheduleViewModel(settings, actions, newId = { "e${++n}" }).also { vms += it }
    }

    @After
    fun tearDown() = vms.forEach { it.cancelAndJoin() }

    @Test
    fun `one-time profiles are not offered, and an entry for a profile that is gone is not listed`() = runTest {
        val settings = FakeSettingsStore(
            profiles.let { it.copy(profiles = it.profiles.copy(schedule = listOf(ScheduleEntry("dead", "gone", setOf(1), 0, 60)))) },
        )

        val s = vm(settings).state.first { !it.loading }

        assertEquals(listOf(ProfileChoice("default", "Default"), ProfileChoice("p2", "Evening")), s.profiles)
        assertTrue(s.entries.isEmpty())
    }

    @Test
    fun `add starts from working hours on no day yet, and every part can be changed or removed`() = runTest {
        val settings = FakeSettingsStore(profiles)
        val actions = FakeFocusActions(settings)
        val vm = vm(settings, actions)

        vm.add()
        assertEquals(
            listOf(ScheduleEntry("e1", "default", emptySet(), 9 * 60, 17 * 60)),
            vm.state.first { it.entries.size == 1 }.entries,
        )
        assertFalse(settings.state.value.hasSchedule, "a new entry does nothing until a day is chosen")

        vm.setProfile("e1", "p2")
        (1..5).forEach { vm.toggleDay("e1", it) }
        vm.toggleDay("e1", 5)
        vm.toggleDay("e1", 7)
        vm.setStart("e1", 22 * 60)
        vm.setEnd("e1", 7 * 60 + 30)
        assertEquals(
            ScheduleEntry("e1", "p2", setOf(1, 2, 3, 4, 7), 22 * 60, 7 * 60 + 30),
            vm.state.first { it.entries.singleOrNull()?.end == 7 * 60 + 30 }.entries.single(),
        )
        assertEquals(11, actions.rulesUpdates, "every edit goes through FocusActions, which is what applies the schedule")

        vm.setStart("no-such-entry", 0)
        vm.remove("e1")
        assertTrue(vm.state.first { it.entries.isEmpty() }.entries.isEmpty())
    }

    @Test
    fun `an edit that fails shows its cause until dismissed`() = runTest {
        val settings = FakeSettingsStore(profiles)
        val actions = FakeFocusActions(settings, rulesError = IllegalStateException("disk full"))
        val vm = vm(settings, actions)

        vm.add()
        assertEquals("disk full", vm.state.first { it.error != null }.error)

        vm.dismissError()
        assertNull(vm.state.first { it.error == null }.error)
        assertTrue(settings.state.value.profiles.schedule.isEmpty())
    }
}
