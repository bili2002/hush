package com.hush.app.focus

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import org.robolectric.RuntimeEnvironment
import com.hush.app.db.NotificationRepository
import com.hush.app.settings.Settings
import com.hush.app.testing.FakeSettingsStore
import com.hush.app.testing.event
import com.hush.app.testing.fixedClock
import com.hush.app.testing.testRepository
import com.hush.app.ui.MainActivity
import java.time.ZoneId
import java.util.TimeZone
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class DelivererTest {
    private val ctx: Context = RuntimeEnvironment.getApplication()
    private val nm = ctx.getSystemService(NotificationManager::class.java)
    private val alarms get() = shadowOf(ctx.getSystemService(AlarmManager::class.java)).scheduledAlarms
    private val originalZone = TimeZone.getDefault()

    // 2023-11-14T22:13:20Z. Slot = 60 min, so the current slot opened at 22:00 and closes at 23:00.
    private val now = 1_700_000_000_000L
    private val slotOpen = now - 13 * 60_000L - 20_000L
    private val clock = fixedClock(now)
    private val focusOn = Settings(focusEnabled = true, slotMinutes = 60, holdPackages = setOf("com.example.alpha", "com.example.beta"))

    private lateinit var repo: NotificationRepository
    private lateinit var settings: FakeSettingsStore
    private lateinit var deliverer: Deliverer

    @BeforeTest
    fun setUp() {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        repo = testRepository(clock)
        settings = FakeSettingsStore(focusOn)
        deliverer = Deliverer(ctx, repo, settings, clock, DeliveryScheduler(ctx, settings))
    }

    @AfterTest fun tearDown() = TimeZone.setDefault(originalZone)

    private suspend fun seedPreviousSlot() {
        // Three alpha + one beta, all posted in the previous slot (21:xx), oldest at 21:02.
        val h21 = slotOpen - 60 * 60_000L
        repo.insert(event("a1", packageName = "com.example.alpha", appLabel = "Alpha", postTime = h21 + 2 * 60_000L, held = true, title = "T1", text = "X1"))
        repo.insert(event("a2", packageName = "com.example.alpha", appLabel = "Alpha", postTime = h21 + 10 * 60_000L, held = true, title = "T2", text = null))
        repo.insert(event("a3", packageName = "com.example.alpha", appLabel = "Alpha", postTime = h21 + 20 * 60_000L, held = true, title = null, text = null))
        repo.insert(event("b1", packageName = "com.example.beta", appLabel = "Beta", postTime = h21 + 30 * 60_000L, held = true, title = "B", text = "Y"))
        // Not held: must never appear in a digest.
        repo.insert(event("p1", packageName = "com.example.gamma", postTime = h21 + 40 * 60_000L, held = false))
    }

    @Test
    fun nothingHeldPostsNothingButReArmsAlarm() = runTest {
        assertEquals(DeliverResult(0), deliverer.deliverDue())
        assertEquals(0, shadowOf(nm).allNotifications.size)
        assertEquals(1, alarms.size)
    }

    @Test
    fun deliversOneDigestWithCountsLinesExtrasAndMarksDelivered() = runTest {
        seedPreviousSlot()
        assertEquals(DeliverResult(4), deliverer.deliverDue())

        val posted = shadowOf(nm).allNotifications
        assertEquals(1, posted.size)
        val n = posted.single()
        assertEquals(Deliverer.CHANNEL_ID, n.channelId)
        assertEquals("4 held notifications", n.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertEquals("Alpha 3 · Beta 1", n.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
        assertEquals(
            listOf("Alpha: T1 — X1", "Alpha: T2", "Alpha: (no text)", "Beta: B — Y", "since 21:02"),
            n.extras.getCharSequence(Notification.EXTRA_BIG_TEXT).toString().split("\n"),
        )
        val intent = shadowOf(n.contentIntent).savedIntent
        assertEquals(MainActivity::class.java.name, intent.component?.className)
        assertEquals(MainActivity.FILTER_HELD, intent.getStringExtra(MainActivity.EXTRA_FILTER))
        assertEquals(now, intent.getLongExtra(MainActivity.EXTRA_DELIVERED_AT, -1))
        assertTrue(shadowOf(n.contentIntent).isActivityIntent)

        assertEquals(0, repo.selectHeldUndelivered().size)
        assertEquals(setOf("a1", "a2", "a3", "b1"), repo.selectHeldBatch(now).map { it.id }.toSet())
        assertNotNull(nm.getNotificationChannel(Deliverer.CHANNEL_ID))
        assertEquals(NotificationManager.IMPORTANCE_DEFAULT, nm.getNotificationChannel(Deliverer.CHANNEL_ID).importance)
        assertEquals(1, alarms.size)
        assertEquals(nextSlot(now, 60, ZoneId.of("UTC")), alarms.single().triggerAtTime)
    }

    @Test
    fun singularTitleForOneRow() = runTest {
        repo.insert(event("a1", postTime = slotOpen - 5 * 60_000L, held = true))
        deliverer.deliverDue()
        val n = shadowOf(nm).allNotifications.single()
        assertEquals("1 held notification", n.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
    }

    @Test
    fun bigTextCapsAtSixLinesPlusSince() = runTest {
        repeat(8) { repo.insert(event("e$it", postTime = slotOpen - (60 - it) * 60_000L, held = true)) }
        deliverer.deliverDue()
        val lines = shadowOf(nm).allNotifications.single().extras.getCharSequence(Notification.EXTRA_BIG_TEXT).toString().split("\n")
        assertEquals(7, lines.size)
        assertEquals("since 21:00", lines.last())
    }

    @Test
    fun secondRunDeliversNothing() = runTest {
        seedPreviousSlot()
        deliverer.deliverDue()
        assertEquals(DeliverResult(0), deliverer.deliverDue())
        assertEquals(1, shadowOf(nm).allNotifications.size)
    }

    @Test
    fun rowsInsideTheCurrentSlotAreNotDueUntilForced() = runTest {
        repo.insert(event("a1", postTime = slotOpen + 60_000L, held = true))
        repo.insert(event("a2", postTime = now - 1_000L, held = true))
        assertEquals(DeliverResult(0), deliverer.deliverDue())
        assertEquals(0, shadowOf(nm).allNotifications.size)
        assertEquals(2, repo.selectHeldUndelivered().size)

        assertEquals(DeliverResult(2), deliverer.deliverDue(force = true))
        assertEquals(1, shadowOf(nm).allNotifications.size)
        assertEquals(0, repo.selectHeldUndelivered().size)
    }

    @Test
    fun oneDueRowPullsTheStragglersIntoTheSameDigest() = runTest {
        repo.insert(event("old", postTime = slotOpen - 60_000L, held = true))
        repo.insert(event("fresh", postTime = now - 1_000L, held = true))
        assertEquals(DeliverResult(2), deliverer.deliverDue())
        assertEquals(1, shadowOf(nm).allNotifications.size)
    }

    @Test
    fun maxAgeGuardDeliversRegardlessOfSchedule() = runTest {
        // 3 h old with a 60-minute slot: past max(2 * 60, 120) = 120 minutes.
        repo.insert(event("a1", postTime = now - 3 * 60 * 60_000L, held = true))
        assertEquals(DeliverResult(1), deliverer.deliverDue())
    }

    @Test
    fun isDueTable() {
        val utc = ZoneId.of("UTC")
        assertTrue(Deliverer.isDue(slotOpen - 1, now, 60, utc), "posted just before the boundary")
        assertTrue(!Deliverer.isDue(slotOpen, now, 60, utc), "posted exactly on the boundary belongs to this slot")
        assertTrue(!Deliverer.isDue(now - 1, now, 60, utc), "posted a moment ago")
        assertTrue(Deliverer.isDue(now - Deliverer.maxAgeMillis(60), now, 60, utc), "max-age reached")
        assertEquals(120 * 60_000L, Deliverer.maxAgeMillis(15))
        assertEquals(120 * 60_000L, Deliverer.maxAgeMillis(60))
        assertEquals(240 * 60_000L, Deliverer.maxAgeMillis(120))
    }

    @Test
    fun heldRowsOfAPackageRemovedFromTheHoldListAreStillDelivered() = runTest {
        seedPreviousSlot()
        settings.update { it.copy(holdPackages = setOf("com.example.beta")) }
        assertEquals(DeliverResult(4), deliverer.deliverDue())
    }

    @Test
    fun heldRowsAreDeliveredEvenIfFocusWasSwitchedOffMeanwhile() = runTest {
        seedPreviousSlot()
        settings.update { it.copy(focusEnabled = false) }
        assertEquals(DeliverResult(4), deliverer.deliverDue())
        assertEquals(0, alarms.size, "no alarm re-armed while Focus is off")
    }

    @Test
    fun notificationsDisabledKeepsRowsHeldAndSurfacesError() = runTest {
        seedPreviousSlot()
        shadowOf(nm).setNotificationsEnabled(false)
        val result = deliverer.deliverDue()
        assertEquals(0, result.delivered)
        assertEquals("Notifications permission denied — digests can't be shown", result.error)
        assertEquals(result.error, settings.state.value.lastFocusError)
        assertEquals(0, shadowOf(nm).allNotifications.size)
        assertEquals(4, repo.selectHeldUndelivered().size)
        assertEquals(1, alarms.size, "alarm still re-armed so the next slot retries")

        shadowOf(nm).setNotificationsEnabled(true)
        assertEquals(DeliverResult(4), deliverer.deliverDue())
        assertNull(settings.state.value.lastFocusError)
    }

    @Test
    fun channelSilencedByUserKeepsRowsHeld() = runTest {
        seedPreviousSlot()
        nm.createNotificationChannel(NotificationChannel(Deliverer.CHANNEL_ID, "digest", NotificationManager.IMPORTANCE_NONE))
        val result = deliverer.deliverDue()
        assertEquals(0, result.delivered)
        assertNotNull(result.error)
        assertEquals(4, repo.selectHeldUndelivered().size)
    }

    @Test
    fun concurrentCallsDeliverOnce() = runTest {
        seedPreviousSlot()
        val results = (1..8).map { async(Dispatchers.Default) { deliverer.deliverDue(force = true) } }.awaitAll()
        assertEquals(4, results.sumOf { it.delivered })
        assertEquals(1, results.count { it.delivered > 0 })
        assertEquals(1, shadowOf(nm).allNotifications.size)
        assertEquals(0, repo.selectHeldUndelivered().size)
    }
}
