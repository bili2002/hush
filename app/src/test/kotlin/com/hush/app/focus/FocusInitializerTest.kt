package com.hush.app.focus

import android.app.AlarmManager
import android.app.Application
import com.hush.app.settings.Settings
import com.hush.app.testing.FakeSettingsStore
import com.hush.app.testing.fixedClock
import java.time.ZoneId
import java.util.TimeZone
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
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

    @Test
    fun reArmsTheAlarmAtProcessStartWhenFocusIsOn() = runTest {
        val settings = FakeSettingsStore(Settings(focusEnabled = true, slotMinutes = 30, holdPackages = setOf("com.example.alpha")))
        FocusInitializer(settings, DeliveryScheduler(app, settings), fixedClock(now), this).init(app)
        testScheduler.advanceUntilIdle()
        assertEquals(1, alarms.size)
        assertEquals(nextSlot(now, 30, ZoneId.of("UTC")), alarms.single().triggerAtTime)
    }

    @Test
    fun doesNothingWhenFocusIsOff() = runTest {
        val settings = FakeSettingsStore(Settings(focusEnabled = false))
        FocusInitializer(settings, DeliveryScheduler(app, settings), fixedClock(now), this).init(app)
        testScheduler.advanceUntilIdle()
        assertEquals(0, alarms.size)
    }
}
