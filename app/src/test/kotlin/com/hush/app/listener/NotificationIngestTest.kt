package com.hush.app.listener

import android.service.notification.NotificationListenerService
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import com.hush.app.db.EventType
import com.hush.app.db.HushDatabase
import com.hush.app.db.NotificationEvent
import com.hush.app.db.NotificationRepository
import com.hush.app.focus.Decision
import com.hush.app.focus.FocusGate
import com.hush.app.settings.Settings
import com.hush.app.testing.FakeSettingsStore
import com.hush.app.testing.fixedClock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class NotificationIngestTest {

    private class CountingGate(var decision: Decision) : FocusGate {
        var calls = 0
        override suspend fun decide(event: NotificationEvent): Decision { calls++; return decision }
    }

    // Production driver on Robolectric's SQLite, in memory (name = null). The JDBC driver is avoided here:
    // java.sql.DriverManager is classloader-sensitive and flakes inside the Robolectric sandbox.
    private val driver = AndroidSqliteDriver(HushDatabase.Schema, appContext(), name = null)
    private val clock = fixedClock(5_000L)
    private val repo = NotificationRepository(HushDatabase(driver), clock)
    private val settings = FakeSettingsStore(Settings())
    private val gate = CountingGate(Decision.PASS_THROUGH)
    private val cancelled = mutableListOf<String>()
    private val cancel: (String) -> Unit = { cancelled += it }

    private val ingest = NotificationIngest(
        repo = repo,
        gate = gate,
        settings = settings,
        clock = clock,
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
        mapper = NotificationMapper(appContext()),
    )

    private suspend fun rows() = repo.selectRecent(limit = 100, before = Long.MAX_VALUE).first()

    @Test
    fun `PASS_THROUGH inserts a visible row and never cancels`() = runTest {
        val decision = ingest.onPosted(sbn(), importance = 3, cancel = cancel)

        assertEquals(Decision.PASS_THROUGH, decision)
        val row = rows().single()
        assertEquals(false, row.held)
        assertEquals(3, row.importance)
        assertTrue(cancelled.isEmpty())
    }

    @Test
    fun `HOLD inserts a held row then fires cancel with the key`() = runTest {
        gate.decision = Decision.HOLD
        val s = sbn()

        assertEquals(Decision.HOLD, ingest.onPosted(s, null, cancel))

        assertEquals(true, rows().single().held)
        assertEquals(listOf(s.key), cancelled)
    }

    @Test
    fun `HOLD with a failing insert does not cancel (fail open)`() = runTest {
        gate.decision = Decision.HOLD
        driver.execute(null, "DROP TABLE notification_event", 0)

        assertNull(ingest.onPosted(sbn(), null, cancel))

        assertTrue(cancelled.isEmpty())
    }

    @Test
    fun `cancel throwing SecurityException leaves the held row and does not propagate`() = runTest {
        gate.decision = Decision.HOLD

        val decision = ingest.onPosted(sbn(), null) { throw SecurityException("listener not connected") }

        assertEquals(Decision.HOLD, decision)
        assertEquals(true, rows().single().held)
    }

    @Test
    fun `filtered notification writes nothing and never reaches the gate`() = runTest {
        assertNull(ingest.onPosted(sbn(packageName = appContext().packageName), null, cancel))
        assertTrue(rows().isEmpty())
        assertEquals(0, gate.calls)
    }

    @Test
    fun `identical re-post is collapsed when passing through`() = runTest {
        ingest.onPosted(sbn(postTime = 1_000L), null, cancel)
        assertNull(ingest.onPosted(sbn(postTime = 2_000L), null, cancel))
        assertEquals(1, rows().size)

        // Changed text is a new row.
        ingest.onPosted(sbn(notification(text = "updated"), postTime = 3_000L), null, cancel)
        assertEquals(2, rows().size)
    }

    @Test
    fun `identical re-post is NOT collapsed when held - cancel fires again, id dedupes the row`() = runTest {
        gate.decision = Decision.HOLD
        val s = sbn(postTime = 1_000L)

        assertEquals(Decision.HOLD, ingest.onPosted(s, null, cancel))
        assertEquals(Decision.HOLD, ingest.onPosted(s, null, cancel))

        assertEquals(listOf(s.key, s.key), cancelled)
        assertEquals(1, rows().size) // same postTime: INSERT OR IGNORE
        assertEquals(true, rows().single().held)
    }

    @Test
    fun `removal with REASON_LISTENER_CANCEL writes no row`() = runTest {
        ingest.onRemoved(sbn(), NotificationListenerService.REASON_LISTENER_CANCEL)
        assertTrue(rows().isEmpty())
    }

    @Test
    fun `removal for any other reason writes a REMOVED row without text`() = runTest {
        val s = sbn(notification(title = "t", text = "x", bigText = "b"))
        ingest.onRemoved(s, NotificationListenerService.REASON_CANCEL)

        val row = rows().single()
        assertEquals(EventType.REMOVED, row.event)
        assertEquals(NotificationListenerService.REASON_CANCEL, row.removedReason)
        assertNull(row.title)
        assertNull(row.text)
        assertNull(row.bigText)
        assertEquals(s.key, row.key)
    }

    @Test
    fun `removal applies the noise filters`() = runTest {
        ingest.onRemoved(sbn(notification(flags = android.app.Notification.FLAG_GROUP_SUMMARY)), NotificationListenerService.REASON_CANCEL)
        assertTrue(rows().isEmpty())
    }

    @Test
    fun `removal forgets the key so the next identical post is recorded again`() = runTest {
        ingest.onPosted(sbn(postTime = 1_000L), null, cancel)
        ingest.onRemoved(sbn(postTime = 1_000L), NotificationListenerService.REASON_CANCEL)
        ingest.onPosted(sbn(postTime = 2_000L), null, cancel)

        assertEquals(2, rows().count { it.event == EventType.POSTED })
    }

    @Test
    fun `backfill inserts held=false without consulting the gate, and twice without duplicates`() = runTest {
        gate.decision = Decision.HOLD
        val active = listOf(sbn(id = 1), sbn(id = 2, packageName = "com.example.beta"), sbn(packageName = appContext().packageName))

        ingest.onBackfill(active)
        ingest.onBackfill(active)

        val all = rows()
        assertEquals(2, all.size)
        assertTrue(all.all { !it.held })
        assertTrue(all.all { it.importance == null })
        assertEquals(0, gate.calls)
        assertTrue(cancelled.isEmpty())
    }

    @Test
    fun `backfill survives a failing insert`() = runTest {
        driver.execute(null, "DROP TABLE notification_event", 0)
        ingest.onBackfill(listOf(sbn())) // must not throw
    }

    @Test
    fun `connected and disconnected stamp the settings with the clock`() = runTest {
        ingest.onConnected()
        assertEquals(5_000L, settings.state.value.listenerConnectedAt)
        assertNull(settings.state.value.listenerDisconnectedAt)

        ingest.onDisconnected()
        assertEquals(5_000L, settings.state.value.listenerDisconnectedAt)
    }

    @Test
    fun `settings snapshot is read per event`() = runTest {
        settings.state.value = Settings(excludedPackages = setOf("com.example.alpha"))
        assertNull(ingest.onPosted(sbn(), null, cancel))

        settings.state.value = Settings()
        assertEquals(Decision.PASS_THROUGH, ingest.onPosted(sbn(), null, cancel))
    }
}
