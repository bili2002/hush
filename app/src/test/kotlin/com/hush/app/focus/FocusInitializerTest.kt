package com.hush.app.focus

import android.app.AlarmManager
import android.app.Application
import com.hush.app.settings.Settings
import com.hush.app.testing.FakeDnd
import com.hush.app.testing.FakeSettingsStore
import com.hush.app.testing.fixedClock
import com.hush.app.testing.testRepository
import java.time.ZoneId
import java.util.TimeZone
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.job
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class FocusInitializerTest {
    private val app: Application = RuntimeEnvironment.getApplication()
    private val alarms get() = shadowOf(app.getSystemService(AlarmManager::class.java)).scheduledAlarms
    private val originalZone = TimeZone.getDefault()
    private val now = 1_700_000_000_000L

    @BeforeTest fun pinZone() = TimeZone.setDefault(TimeZone.getTimeZone("UTC"))

    @AfterTest fun restoreZone() = TimeZone.setDefault(originalZone)

    private fun initializer(settings: FakeSettingsStore, scope: kotlinx.coroutines.CoroutineScope): FocusInitializer {
        val clock = fixedClock(now)
        val deliverer = Deliverer(app, testRepository(clock), settings, clock, DeliveryScheduler(app, settings), FocusAutomation(settings, FakeDnd()))
        return FocusInitializer(settings, deliverer, scope)
    }

    @Test
    fun reArmsTheAlarmAtProcessStartWhenFocusIsOn() = runTest {
        val settings = FakeSettingsStore(Settings(focusEnabled = true, slotMinutes = 30, holdPackages = setOf("com.example.alpha")))
        initializer(settings, this).init(app)
        // The run does its database work on Dispatchers.IO, which the test scheduler does not drive: wait for it.
        coroutineContext.job.children.forEach { it.join() }
        assertEquals(1, alarms.size)
        assertEquals(nextSlot(now, 30, ZoneId.of("UTC")), alarms.single().triggerAtTime)
    }

    @Test
    fun doesNothingWhenFocusIsOffAndThereIsNoSchedule() = runTest {
        val settings = FakeSettingsStore(Settings(focusEnabled = false))
        initializer(settings, this).init(app)
        // The run does its database work on Dispatchers.IO, which the test scheduler does not drive: wait for it.
        coroutineContext.job.children.forEach { it.join() }
        assertEquals(0, alarms.size)
    }

    @Test
    fun aScheduledStartThatWasMissedIsAppliedAtProcessStart() = runTest {
        // Tuesday 22:13 UTC, stored as off; an entry has been running since 22:00 but its alarm was lost.
        val entry = ScheduleEntry("e", DEFAULT_PROFILE_ID, setOf(2), 22 * 60, 23 * 60 + 30)
        val settings = FakeSettingsStore(Settings(slotMinutes = 60, holdPackages = setOf("com.example.alpha")).withScheduleEntry(entry))

        initializer(settings, this).init(app)
        // The run does its database work on Dispatchers.IO, which the test scheduler does not drive: wait for it.
        coroutineContext.job.children.forEach { it.join() }

        assertEquals(true, settings.state.value.focusEnabled)
        assertEquals(nextSlot(now, 60, ZoneId.of("UTC")), alarms.single().triggerAtTime, "armed for the 23:00 digest, not only for the 23:30 end")
    }
}
