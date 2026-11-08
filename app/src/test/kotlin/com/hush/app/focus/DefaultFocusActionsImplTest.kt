package com.hush.app.focus

import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import org.robolectric.RuntimeEnvironment
import com.hush.app.db.NotificationRepository
import com.hush.app.settings.Settings
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

    @BeforeTest
    fun setUp() {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        repo = testRepository(clock)
        settings = FakeSettingsStore(Settings(holdPackages = setOf("com.example.alpha")))
        val scheduler = DeliveryScheduler(ctx, settings)
        actions = DefaultFocusActionsImpl(ctx, settings, Deliverer(ctx, repo, settings, clock, scheduler), scheduler, clock)
    }

    @AfterTest fun tearDown() = TimeZone.setDefault(originalZone)

    @Test
    fun enableWithEmptyHoldListRefuses() = runTest {
        settings.update { it.copy(holdPackages = emptySet()) }
        actions.setEnabled(true)
        assertFalse(settings.state.value.focusEnabled)
        assertEquals("Add at least one app to hold", settings.state.value.lastFocusError)
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
    fun setSlotMinutesReschedulesOnlyWhenEnabled() = runTest {
        actions.setSlotMinutes(30)
        assertEquals(30, settings.state.value.slotMinutes)
        assertEquals(0, alarms.size)

        actions.setEnabled(true)
        actions.setSlotMinutes(15)
        assertEquals(1, alarms.size)
        assertEquals(nextSlot(now, 15, ZoneId.of("UTC")), alarms.single().triggerAtTime)
    }

    @Test
    fun deliverNowForcesDelivery() = runTest {
        repo.insert(event("a1", postTime = now - 60_000L, held = true))
        assertEquals(DeliverResult(1), actions.deliverNow())
        assertEquals(1, shadowOf(nm).allNotifications.size)
    }
}
