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
        val trigger = scheduler.schedule(now)
        assertEquals(nextSlot(now, 60, ZoneId.of("UTC")), trigger)
        assertEquals(1, alarms.size)
        assertEquals(AlarmManager.RTC_WAKEUP, alarms.single().type)
        assertEquals(trigger, alarms.single().triggerAtTime)
    }

    @Test
    fun reschedulingReplacesInsteadOfStacking() = runTest {
        val store = FakeSettingsStore(Settings(slotMinutes = 60))
        val scheduler = DeliveryScheduler(ctx, store)
        scheduler.schedule(now)
        store.update { it.copy(slotMinutes = 15) }
        val trigger = scheduler.schedule(now)
        assertEquals(1, alarms.size)
        assertEquals(nextSlot(now, 15, ZoneId.of("UTC")), alarms.single().triggerAtTime)
        assertEquals(trigger, alarms.single().triggerAtTime)
    }

    @Test
    fun cancelClearsTheAlarm() = runTest {
        val scheduler = DeliveryScheduler(ctx, FakeSettingsStore())
        scheduler.schedule(now)
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
        val trigger = DeliveryScheduler(ctx, store).schedule(now)
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
