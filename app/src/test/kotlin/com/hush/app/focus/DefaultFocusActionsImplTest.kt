package com.hush.app.focus

import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import org.robolectric.RuntimeEnvironment
import com.hush.app.db.NotificationRepository
import com.hush.app.settings.Settings
import com.hush.app.testing.FakeDnd
import com.hush.app.testing.FakeSettingsStore
import com.hush.app.testing.event
import com.hush.app.testing.fixedClock
import com.hush.app.testing.testRepository
import java.time.ZoneId
import java.util.TimeZone
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class DefaultFocusActionsImplTest {
    private val ctx: Context = RuntimeEnvironment.getApplication()
    private val nm = ctx.getSystemService(NotificationManager::class.java)
    private val alarms get() = shadowOf(ctx.getSystemService(AlarmManager::class.java)).scheduledAlarms
    private val originalZone = TimeZone.getDefault()
    private val now = 1_700_000_000_000L
    private val clock = fixedClock(now)

    private lateinit var repo: NotificationRepository
    private lateinit var settings: FakeSettingsStore
    private lateinit var actions: DefaultFocusActionsImpl
    private lateinit var dnd: FakeDnd

    @BeforeTest
    fun setUp() {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        repo = testRepository(clock)
        settings = FakeSettingsStore(Settings(holdPackages = setOf("com.example.alpha")))
        dnd = FakeDnd()
        val deliverer = Deliverer(ctx, repo, settings, clock, DeliveryScheduler(ctx, settings), FocusAutomation(settings, dnd))
        actions = DefaultFocusActionsImpl(ctx, settings, deliverer, clock)
    }

    @AfterTest fun tearDown() = TimeZone.setDefault(originalZone)

    @Test
    fun enableWithEmptyHoldListRefuses() = runTest {
        settings.update { it.copy(holdPackages = emptySet()) }
        actions.setEnabled(true)
        assertFalse(settings.state.value.focusEnabled)
        assertEquals("Nothing would be held — add an app, or give a group or the default an interval", settings.state.value.lastFocusError)
        assertEquals(0, alarms.size)
    }

    @Test
    fun enableSchedulesAndClearsError() = runTest {
        settings.update { it.copy(lastFocusError = "Add at least one app to hold") }
        actions.setEnabled(true)
        assertTrue(settings.state.value.focusEnabled)
        assertNull(settings.state.value.lastFocusError)
        assertEquals(1, alarms.size)
        assertEquals(nextSlot(now, 60, ZoneId.of("UTC")), alarms.single().triggerAtTime)
    }

    @Test
    fun disableDeliversEverythingHeldAndCancelsAlarm() = runTest {
        actions.setEnabled(true)
        repo.insert(event("a1", postTime = now - 60_000L, held = true)) // inside the current slot: not due, but forced
        actions.setEnabled(false)
        assertFalse(settings.state.value.focusEnabled)
        assertEquals(1, shadowOf(nm).allNotifications.size)
        assertEquals(0, repo.selectHeldUndelivered().size)
        assertEquals(0, alarms.size)
    }

    @Test
    fun disableKeepsTheAlarmWhenHeldRowsCouldNotBeDelivered() = runTest {
        actions.setEnabled(true)
        repo.insert(event("a1", postTime = now - 60_000L, held = true))
        shadowOf(nm).setNotificationsEnabled(false)
        actions.setEnabled(false)
        assertFalse(settings.state.value.focusEnabled)
        assertEquals(0, shadowOf(nm).allNotifications.size)
        assertEquals(1, repo.selectHeldUndelivered().size)
        assertEquals(1, alarms.size, "alarm kept so the next slot retries")
        assertEquals("Notifications permission denied — digests can't be shown", settings.state.value.lastFocusError)
    }

    @Test
    fun aRuleEditArmsTheAlarmOnlyWhenEnabled() = runTest {
        actions.updateRules { it.copy(slotMinutes = 30) }
        assertEquals(30, settings.state.value.slotMinutes)
        assertEquals(0, alarms.size)

        actions.setEnabled(true)
        actions.updateRules { it.copy(slotMinutes = 15) }
        assertEquals(1, alarms.size)
        assertEquals(nextSlot(now, 15, ZoneId.of("UTC")), alarms.single().triggerAtTime)
    }

    @Test
    fun deliverNowForcesDelivery() = runTest {
        repo.insert(event("a1", postTime = now - 60_000L, held = true))
        assertEquals(DeliverResult(1), actions.deliverNow())
        assertEquals(1, shadowOf(nm).allNotifications.size)
    }

    @Test
    fun enablingNeedsSomethingThatWouldActuallyBeHeld() = runTest {
        // The only held app sits in an "instant" group: turning Focus on would hold nothing.
        settings.update {
            it.copy(rules = FocusRules(categories = listOf(FocusCategory("off", "Off", INSTANT)), appCategory = mapOf("com.example.alpha" to "off")))
        }
        actions.setEnabled(true)
        assertEquals(false, settings.state.value.focusEnabled)
        assertNotNull(settings.state.value.lastFocusError)

        // A holding chat rule alone is enough, even with an empty hold list.
        settings.update { it.copy(holdPackages = emptySet(), rules = FocusRules(chatRules = listOf(ChatRule("com.example.beta", "c1", "Group one", 60)))) }
        actions.setEnabled(true)
        assertEquals(true, settings.state.value.focusEnabled)
    }

    @Test
    fun aRuleEditReleasesWhatItMakesDueAndReArmsForTheNewIntervals() = runTest {
        actions.setEnabled(true)
        repo.insert(event("a1", packageName = "com.example.alpha", postTime = now - 60_000L, held = true, conversationId = "c1"))

        actions.updateRules { it.withChatRule(ChatRule("com.example.alpha", "c1", "Group one", INSTANT)) }
        assertEquals(0, repo.selectHeldUndelivered().size, "the chat is no longer held, so its row is delivered now")
        assertEquals(1, shadowOf(nm).allNotifications.size)

        actions.updateRules { it.withCategoryAdded("fast", "Fast").withCategoryMinutes("fast", 15).withAppInCategory("com.example.alpha", "fast") }
        assertEquals(nextSlot(now, 15, ZoneId.of("UTC")), alarms.single().triggerAtTime)
    }

    @Test
    fun aRuleEditWhileFocusIsOffSavesAndLeavesNoAlarm() = runTest {
        actions.updateRules { it.withoutHeldApp("com.example.alpha") }
        assertEquals(emptySet(), settings.state.value.holdPackages)
        assertEquals(0, shadowOf(nm).allNotifications.size)
        assertEquals(0, alarms.size)
    }

    @Test
    fun anyEditWhileFocusIsOffReleasesARowLeftHeld() = runTest {
        // Focus is off, so nothing should be waiting; a row that still is (an earlier flush was blocked) goes out
        // with the delivery run every edit ends in, instead of waiting for the periodic safety net.
        repo.insert(event("a1", packageName = "com.example.alpha", postTime = now - 60_000L, held = true))
        actions.updateRules { it.copy(slotMinutes = 30) }
        assertEquals(1, shadowOf(nm).allNotifications.size)
        assertEquals(0, repo.selectHeldUndelivered().size)
        assertEquals(0, alarms.size)
    }

    // ---- profiles and the schedule ----

    /** `now` is Tuesday 2023-11-14 22:13:20 UTC. */
    private fun entry(profileId: String, start: Int, end: Int, days: Set<Int> = setOf(2)) = ScheduleEntry("e-$profileId", profileId, days, start, end)

    private suspend fun twoProfiles() {
        // "default" holds alpha every 60 min; "quiet" (a copy) lets its group through instantly.
        settings.update {
            it.copy(rules = FocusRules(categories = listOf(FocusCategory("g", "Group", null)), appCategory = mapOf("com.example.alpha" to "g"), seeded = true))
                .withProfileAdded("quiet", "Quiet", copyOf = DEFAULT_PROFILE_ID, oneTime = false)
                .withProfileEdited("quiet") { s -> s.withCategoryMinutes("g", INSTANT) }
        }
    }

    @Test
    fun selectingAProfileReleasesWhatItNoLongerHolds() = runTest {
        twoProfiles()
        actions.setEnabled(true)
        repo.insert(event("a1", packageName = "com.example.alpha", postTime = now - 60_000L, held = true))

        actions.selectProfile("quiet")

        assertEquals("quiet", settings.state.value.activeProfile.id)
        assertEquals(INSTANT, settings.state.value.throttleMinutes("com.example.alpha", null))
        assertEquals(0, repo.selectHeldUndelivered().size, "the row the new profile does not hold is delivered at once")
        assertNull(settings.state.value.profiles.manualUntil, "without a schedule there is nothing to hold off")

        actions.selectProfile(DEFAULT_PROFILE_ID)
        assertEquals(60, settings.state.value.throttleMinutes("com.example.alpha", null), "and the first profile has its throttles back")
    }

    @Test
    fun aScheduleTurnsFocusOnInsideItsWindowAndOffOutside_andWakesForTheNextChange() = runTest {
        twoProfiles()
        // 22:00–23:00 today: we are inside.
        actions.updateRules { it.withScheduleEntry(entry("quiet", 22 * 60, 23 * 60)) }

        var s = settings.state.value
        assertTrue(s.focusEnabled)
        assertEquals("quiet", s.activeProfile.id)
        val end = now - (13 * 60_000L + 20_000L) + 60 * 60_000L // 23:00
        assertEquals(end, alarms.single().triggerAtTime, "nothing with an interval is held, so the next thing to wake for is the end of the window")

        // Move the window to 23:00–23:30: now outside. Focus goes off, the alarm waits for 23:00.
        actions.updateRules { it.withScheduleEntry(entry("quiet", 23 * 60, 23 * 60 + 30)) }
        s = settings.state.value
        assertFalse(s.focusEnabled)
        assertEquals(end, alarms.single().triggerAtTime, "armed for the start although Focus is off")
    }

    @Test
    fun aManualChoiceStandsUntilMidnightAndResumeEndsIt() = runTest {
        twoProfiles()
        actions.updateRules { it.withScheduleEntry(entry("quiet", 22 * 60, 23 * 60)) }
        assertTrue(settings.state.value.focusEnabled)

        actions.setEnabled(false)
        val midnight = now - (22 * 3_600_000L + 13 * 60_000L + 20_000L) + 24 * 3_600_000L
        assertFalse(settings.state.value.focusEnabled, "the schedule does not turn it straight back on")
        assertEquals(midnight, settings.state.value.profiles.manualUntil)
        assertEquals(midnight, alarms.single().triggerAtTime, "the alarm waits for the end of the manual choice")

        actions.selectProfile(DEFAULT_PROFILE_ID)
        assertEquals(DEFAULT_PROFILE_ID, settings.state.value.activeProfile.id, "a pick also stands")

        actions.resumeSchedule()
        val s = settings.state.value
        assertNull(s.profiles.manualUntil)
        assertTrue(s.focusEnabled)
        assertEquals("quiet", s.activeProfile.id)
    }

    @Test
    fun aTapOnTheProfileAlreadyInForceDoesNotSuspendTheSchedule() = runTest {
        twoProfiles()
        actions.updateRules { it.withScheduleEntry(entry("quiet", 22 * 60, 23 * 60)) }

        actions.selectProfile("quiet")
        assertNull(settings.state.value.profiles.manualUntil)

        actions.selectProfile("no-such-profile")
        assertNull(settings.state.value.profiles.manualUntil)
        assertEquals("quiet", settings.state.value.activeProfile.id)
    }

    @Test
    fun theEndOfAScheduledWindowReleasesEverythingHeldInTheSameRun() = runTest {
        // Default holds alpha hourly; its window is 22:00–23:00 and we are inside it.
        actions.updateRules { it.withScheduleEntry(entry(DEFAULT_PROFILE_ID, 22 * 60, 23 * 60)) }
        assertTrue(settings.state.value.focusEnabled)
        repo.insert(event("a1", packageName = "com.example.alpha", postTime = now - 60_000L, held = true))

        // The window is moved away from now: the same edit turns Focus off and delivers the row.
        actions.updateRules { it.withScheduleEntry(entry(DEFAULT_PROFILE_ID, 23 * 60 + 30, 23 * 60 + 45)) }

        assertFalse(settings.state.value.focusEnabled)
        assertEquals(0, repo.selectHeldUndelivered().size)
        assertEquals(1, shadowOf(nm).allNotifications.size)
    }

    @Test
    fun doNotDisturbFollowsTheProfileAndIsOnlyTurnedOffIfHushTurnedItOn() = runTest {
        twoProfiles()
        settings.update { it.withProfileDnd(DEFAULT_PROFILE_ID, true) }

        actions.setEnabled(true)
        assertTrue(dnd.on)
        assertTrue(settings.state.value.profiles.dndByHush)

        actions.selectProfile("quiet")
        assertFalse(dnd.on, "the other profile does not ask for it")
        assertFalse(settings.state.value.profiles.dndByHush)

        // The owner's own Do Not Disturb is left alone both ways.
        dnd.on = true
        actions.selectProfile(DEFAULT_PROFILE_ID)
        actions.setEnabled(false)
        assertTrue(dnd.on)
        assertEquals(listOf(true, false), dnd.calls)
    }

    @Test
    fun aDoNotDisturbFailureIsKeptUntilItWorks() = runTest {
        settings.update { it.withProfileDnd(DEFAULT_PROFILE_ID, true) }
        dnd.failWith = "Do Not Disturb access not granted"

        actions.setEnabled(true)
        assertEquals("Do Not Disturb access not granted", settings.state.value.profiles.dndError)
        assertTrue(settings.state.value.focusEnabled, "Focus itself still runs")

        dnd.failWith = null
        actions.deliverNow()
        assertNull(settings.state.value.profiles.dndError)
        assertTrue(dnd.on)
    }
}
