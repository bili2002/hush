package com.hush.app.sync

import android.content.Context
import android.provider.Settings as SystemSettings
import org.robolectric.RuntimeEnvironment
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import com.hush.app.db.NotificationRepository
import com.hush.app.settings.Settings
import com.hush.app.testing.FakeSettingsStore
import com.hush.app.testing.event
import com.hush.app.testing.fixedClock
import com.hush.app.testing.testRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The worker is built directly through a [WorkerFactory]; no Hilt, no WorkManager instance needed
 * (robolectric.properties pins a plain Application, so HushApp's Hilt graph never boots here).
 */
@RunWith(RobolectricTestRunner::class)
class SyncWorkerTest {

    private val ctx: Context = RuntimeEnvironment.getApplication()
    private val now = 1_700_000_000_000L
    private val clock = fixedClock(now)
    private val repo: NotificationRepository = testRepository(clock)
    private val configured = Settings(serverUrl = "https://example.test", token = "synthetic-token")
    private val settings = FakeSettingsStore(configured)

    private class FakeApi(var reply: (List<NotificationDto>) -> PushResult = { PushResult.Ok(it.size, 0) }) : HushApi {
        val calls = mutableListOf<List<NotificationDto>>()
        var lastToken: String? = null
        var lastUrl: String? = null
        override suspend fun push(baseUrl: String, token: String, device: String, items: List<NotificationDto>): PushResult {
            calls += items
            lastToken = token
            lastUrl = baseUrl
            return reply(items)
        }
    }

    private class FakeRebinder : ListenerRebinder {
        var calls = 0
        override fun requestRebind(ctx: Context) { calls++ }
    }

    private class FakeHook : DeliverDueHook {
        var calls = 0
        override suspend fun deliverDue() { calls++ }
    }

    private val api = FakeApi()
    private val rebinder = FakeRebinder()
    private val hook = FakeHook()

    private fun worker(): SyncWorker = TestListenableWorkerBuilder<SyncWorker>(ctx)
        .setWorkerFactory(object : WorkerFactory() {
            override fun createWorker(appContext: Context, workerClassName: String, params: WorkerParameters): ListenableWorker =
                SyncWorker(appContext, params, repo, api, settings, clock, rebinder, setOf(hook))
        })
        .build()

    private fun run(): ListenableWorker.Result = runBlocking { worker().doWork() }

    private fun seed(count: Int, packageName: String = "com.example.alpha", postTime: Long = now) = runBlocking {
        repeat(count) { i -> repo.insert(event("$packageName-$i", packageName = packageName, postTime = postTime + i)) }
    }

    private fun unsynced(): Long = runBlocking { repo.countUnsynced().first() }

    @Test
    fun `pushes in batches of 500 marks synced and records lastSyncAt`() {
        seed(1_200)

        assertEquals(ListenableWorker.Result.success(), run())

        assertEquals(listOf(500, 500, 200), api.calls.map { it.size })
        assertEquals(0L, unsynced())
        assertEquals(now, settings.state.value.lastSyncAt)
        assertNull(settings.state.value.lastSyncError)
        assertEquals("synthetic-token", api.lastToken)
        assertEquals("https://example.test", api.lastUrl)
    }

    @Test
    fun `empty queue succeeds without calling the server`() {
        assertEquals(ListenableWorker.Result.success(), run())
        assertTrue(api.calls.isEmpty())
        assertEquals(now, settings.state.value.lastSyncAt)
    }

    @Test
    fun `unconfigured is success with no error and lastSyncAt untouched`() {
        settings.state.value = Settings(serverUrl = "", token = "", lastSyncAt = 123L, lastSyncError = "stale")
        seed(3)

        assertEquals(ListenableWorker.Result.success(), run())

        assertTrue(api.calls.isEmpty())
        assertNull(settings.state.value.lastSyncError)
        assertEquals(123L, settings.state.value.lastSyncAt)
        assertEquals(3L, unsynced())
    }

    @Test
    fun `blank token alone counts as unconfigured`() {
        settings.state.value = configured.copy(token = "   ")
        seed(1)
        assertEquals(ListenableWorker.Result.success(), run())
        assertTrue(api.calls.isEmpty())
    }

    @Test
    fun `transient error retries with a reachability message and keeps rows unsynced`() {
        seed(2)
        api.reply = { PushResult.SyncError.Transient("Connection refused") }
        settings.state.value = configured.copy(lastSyncAt = 5L)

        assertEquals(ListenableWorker.Result.retry(), run())

        assertEquals("Can't reach server: Connection refused", settings.state.value.lastSyncError)
        assertEquals(5L, settings.state.value.lastSyncAt)
        assertEquals(2L, unsynced())
    }

    @Test
    fun `unauthorized fails with wrong token message`() {
        seed(1)
        api.reply = { PushResult.SyncError.Unauthorized }

        assertEquals(ListenableWorker.Result.failure(), run())

        assertEquals("Wrong token (401)", settings.state.value.lastSyncError)
        assertEquals(1L, unsynced())
    }

    @Test
    fun `rejected batch fails without retry and keeps earlier progress`() {
        seed(600)
        var n = 0
        api.reply = { if (n++ == 0) PushResult.Ok(it.size, 0) else PushResult.SyncError.Rejected("too many items") }

        assertEquals(ListenableWorker.Result.failure(), run())

        assertEquals("Server rejected the batch: too many items", settings.state.value.lastSyncError)
        assertEquals(100L, unsynced()) // first 500 stayed synced
        assertNull(settings.state.value.lastSyncAt)
    }

    @Test
    fun `excluded packages never reach the server but leave the queue`() {
        settings.state.value = configured.copy(excludedPackages = setOf("com.example.secret"))
        seed(2, packageName = "com.example.secret")
        seed(3, packageName = "com.example.alpha")

        assertEquals(ListenableWorker.Result.success(), run())

        assertEquals(1, api.calls.size)
        assertEquals(setOf("com.example.alpha"), api.calls.single().map { it.packageName }.toSet())
        assertEquals(3, api.calls.single().size)
        assertEquals(0L, unsynced())
    }

    @Test
    fun `an all-excluded batch is stamped synced without an HTTP call`() {
        settings.state.value = configured.copy(excludedPackages = setOf("com.example.secret"))
        seed(2, packageName = "com.example.secret")

        assertEquals(ListenableWorker.Result.success(), run())

        assertTrue(api.calls.isEmpty())
        assertEquals(0L, unsynced())
    }

    @Test
    fun `retention runs when configured and keeps held-undelivered rows`() = runBlocking {
        val old = now - SyncWorker.RETENTION_MS - 1
        repo.insert(event("old-plain", postTime = old))
        repo.insert(event("old-held", postTime = old, held = true))
        repo.insert(event("fresh", postTime = now))

        assertEquals(ListenableWorker.Result.success(), run())

        val ids = repo.selectRecent(10, Long.MAX_VALUE).first().map { it.id }.toSet()
        assertEquals(setOf("old-held", "fresh"), ids)
    }

    @Test
    fun `retention runs when unconfigured too`() = runBlocking {
        settings.state.value = Settings()
        val old = now - SyncWorker.RETENTION_MS - 1
        repo.insert(event("old-synced", postTime = old))
        repo.insert(event("old-unsynced", postTime = old))
        repo.markSynced(listOf("old-synced"), syncedAt = old)

        assertEquals(ListenableWorker.Result.success(), run())

        val ids = repo.selectRecent(10, Long.MAX_VALUE).first().map { it.id }
        assertEquals(listOf("old-unsynced"), ids)
    }

    @Test
    fun `deliverDue hooks run after every attempt including failures`() {
        run()
        assertEquals(1, hook.calls)

        seed(1)
        api.reply = { PushResult.SyncError.Unauthorized }
        run()
        assertEquals(2, hook.calls)
    }

    @Test
    fun `unexpected exception fails with a visible cause and still runs housekeeping`() {
        seed(1)
        api.reply = { error("boom") }

        assertEquals(ListenableWorker.Result.failure(), run())

        assertEquals("Sync failed: IllegalStateException", settings.state.value.lastSyncError)
        assertEquals(1, hook.calls)
        assertEquals(1L, unsynced())
    }

    @Test
    fun `rebinder is called only when notification access is granted`() {
        run()
        assertEquals(0, rebinder.calls)

        SystemSettings.Secure.putString(
            ctx.contentResolver,
            "enabled_notification_listeners",
            "${ctx.packageName}/${AndroidListenerRebinder.COLLECTOR_CLASS}:com.other/com.other.Svc",
        )
        run()
        assertEquals(1, rebinder.calls)
    }

    @Test
    fun `a failing hook does not change the sync result`() {
        val throwing = DeliverDueHook { error("boom") }
        val w = TestListenableWorkerBuilder<SyncWorker>(ctx)
            .setWorkerFactory(object : WorkerFactory() {
                override fun createWorker(appContext: Context, workerClassName: String, params: WorkerParameters): ListenableWorker =
                    SyncWorker(appContext, params, repo, api, settings, clock, rebinder, setOf(throwing, hook))
            })
            .build()

        assertEquals(ListenableWorker.Result.success(), runBlocking { w.doWork() })
        assertEquals(1, hook.calls)
        assertNull(settings.state.value.lastSyncError)
    }
}
