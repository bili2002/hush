package com.hush.app.focus

import android.app.AlarmManager
import android.content.Context
import org.robolectric.RuntimeEnvironment
import com.hush.app.settings.Settings
import com.hush.app.testing.FakeSettingsStore
import java.time.ZoneId
import java.util.TimeZone
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class DeliverySchedulerTest {
    private val ctx: Context = RuntimeEnvironment.getApplication()
    private val alarms get() = shadowOf(ctx.getSystemService(AlarmManager::class.java)).scheduledAlarms
    private val originalZone = TimeZone.getDefault()

    // 2023-11-14T22:13:20Z
    private val now = 1_700_000_000_000L

    @BeforeTest fun pinZone() = TimeZone.setDefault(TimeZone.getTimeZone("UTC"))

    @AfterTest fun restoreZone() = TimeZone.setDefault(originalZone)

    @Test
    fun schedulesExactlyOneWakeupAlarmAtNextSlot() = runTest {
        val scheduler = DeliveryScheduler(ctx, FakeSettingsStore(Settings(slotMinutes = 60)))
        val trigger = scheduler.rearm(now, held = true)
        assertEquals(nextSlot(now, 60, ZoneId.of("UTC")), trigger)
        assertEquals(1, alarms.size)
        assertEquals(AlarmManager.RTC_WAKEUP, alarms.single().type)
        assertEquals(trigger, alarms.single().triggerAtTime)
    }

    @Test
    fun reschedulingReplacesInsteadOfStacking() = runTest {
        val store = FakeSettingsStore(Settings(slotMinutes = 60))
        val scheduler = DeliveryScheduler(ctx, store)
        scheduler.rearm(now, held = true)
        store.update { it.copy(slotMinutes = 15) }
        val trigger = scheduler.rearm(now, held = true)
        assertEquals(1, alarms.size)
        assertEquals(nextSlot(now, 15, ZoneId.of("UTC")), alarms.single().triggerAtTime)
        assertEquals(trigger, alarms.single().triggerAtTime)
    }

    @Test
    fun withFocusOffAndNothingHeldThereIsNothingToWakeFor() = runTest {
        val scheduler = DeliveryScheduler(ctx, FakeSettingsStore(Settings(slotMinutes = 60)))
        scheduler.rearm(now, held = true)
        assertEquals(null, scheduler.rearm(now, held = false))
        assertEquals(0, alarms.size)
    }

    @Test
    fun wakesForTheScheduleEvenWhileFocusIsOff_andForWhicheverComesFirstWhileOn() = runTest {
        // Tuesday 22:13 UTC. An entry starts at 22:30.
        val entry = ScheduleEntry("e", DEFAULT_PROFILE_ID, setOf(2), 22 * 60 + 30, 23 * 60 + 30)
        val off = Settings(slotMinutes = 60, holdPackages = setOf("com.example.alpha")).withScheduleEntry(entry)
        val start = now - (13 * 60_000L + 20_000L) + 30 * 60_000L
        val utc = ZoneId.of("UTC")

        assertEquals(start, DeliveryScheduler(ctx, FakeSettingsStore(off)).rearm(now, held = false))
        assertEquals(start, alarms.single().triggerAtTime)

        assertEquals(start, DeliveryScheduler.nextWake(now, off.copy(focusEnabled = true), utc, held = false), "22:30 start before the 23:00 digest")
        assertEquals(nextSlot(now, 15, utc), DeliveryScheduler.nextWake(now, off.copy(focusEnabled = true, slotMinutes = 15), utc, held = false))
    }

    @Test
    fun aDefaultOfInstantlyStillLeavesARetryBoundary() {
        val utc = ZoneId.of("UTC")
        // Nothing has an interval: the retry for rows held under earlier rules is hourly.
        val instant = Settings(slotMinutes = INSTANT, holdPackages = setOf("com.example.alpha"))
        assertEquals(nextSlot(now, 60, utc), DeliveryScheduler.nextBoundary(now, instant, utc))
        // One group with its own interval is what the alarm follows.
        val oneGroup = instant.copy(rules = FocusRules(categories = listOf(FocusCategory("g", "Group", 15)), appCategory = mapOf("com.example.alpha" to "g")))
        assertEquals(nextSlot(now, 15, utc), DeliveryScheduler.nextBoundary(now, oneGroup, utc))
    }

    @Test
    fun cancelClearsTheAlarm() = runTest {
        val scheduler = DeliveryScheduler(ctx, FakeSettingsStore())
        scheduler.rearm(now, held = true)
        scheduler.cancel()
        assertEquals(0, alarms.size)
    }

    @Test
    fun armsAtTheEarliestBoundaryOfAnyIntervalInUse() = runTest {
        // Default 60, one held app in a 15-minute group, one chat rule at 240: 22:15 comes first.
        val rules = FocusRules(
            categories = listOf(FocusCategory("fast", "Fast", 15)),
            appCategory = mapOf("com.example.alpha" to "fast"),
            chatRules = listOf(ChatRule("com.example.gamma", "c1", "Group one", 240)),
        )
        val store = FakeSettingsStore(Settings(slotMinutes = 60, holdPackages = setOf("com.example.alpha", "com.example.beta"), rules = rules))
        val trigger = DeliveryScheduler(ctx, store).rearm(now, held = true)
        assertEquals(nextSlot(now, 15, ZoneId.of("UTC")), trigger)
        assertEquals(trigger, alarms.single().triggerAtTime)
    }

    @Test
    fun aGroupIntervalNobodyUsesDoesNotWakeThePhone() {
        val utc = ZoneId.of("UTC")
        val unused = Settings(slotMinutes = 60, holdPackages = setOf("com.example.alpha"), rules = FocusRules(categories = listOf(FocusCategory("fast", "Fast", 15))))
        assertEquals(nextSlot(now, 60, utc), DeliveryScheduler.nextBoundary(now, unused, utc))
        // Nothing would be held at all: fall back to the default slot so earlier holds still get their retry.
        assertEquals(nextSlot(now, 30, utc), DeliveryScheduler.nextBoundary(now, Settings(slotMinutes = 30), utc))
    }
}
