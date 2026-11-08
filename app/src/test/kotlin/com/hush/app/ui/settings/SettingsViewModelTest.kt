package com.hush.app.ui.settings

import com.hush.app.settings.Settings
import com.hush.app.settings.SettingsStore
import com.hush.app.sync.SyncScheduler
import com.hush.app.testing.FakeSettingsStore
import com.hush.app.testing.event
import com.hush.app.testing.fixedClock
import com.hush.app.testing.testRepository
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpStatusCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Rule

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {

    @get:Rule
    val mainRule = MainDispatcherRule()

    private class FakeProbe(var listenerEnabled: Boolean = false, var batteryExempt: Boolean = false) : SystemStateProbe {
        override fun isListenerEnabled() = listenerEnabled
        override fun isBatteryExempt() = batteryExempt
    }

    private class RecordingSyncScheduler : SyncScheduler {
        var calls = 0
        override fun syncNow() { calls++ }
    }

    private class CountingStore(private val delegate: FakeSettingsStore) : SettingsStore by delegate {
        var writes = 0
        override suspend fun update(block: (Settings) -> Settings) { writes++; delegate.update(block) }
        override val settings: Flow<Settings> get() = delegate.settings
    }

    private val configured = Settings(serverUrl = "https://example.invalid:8443", token = "synthetic-token")
    private val now = 1_700_000_000_000L

    private fun TestScope.viewModel(
        store: SettingsStore,
        probe: FakeProbe = FakeProbe(),
        sync: RecordingSyncScheduler = RecordingSyncScheduler(),
        status: HttpStatusCode = HttpStatusCode.OK,
        repo: com.hush.app.db.NotificationRepository = testRepository(),
    ): SettingsViewModel {
        val client = HttpClient(MockEngine { if (status.value < 400) respond("ok") else respondError(status) })
        val vm = SettingsViewModel(store, repo, sync, ConnectionTester(client), probe, fixedClock(now), backgroundScope)
        backgroundScope.launch { vm.state.collect {} } // keep WhileSubscribed alive
        return vm
    }

    @Test
    fun `status line maps unconfigured, ok and error`() = runTest {
        val store = FakeSettingsStore()
        val vm = viewModel(store)
        assertEquals(ServerStatus.NotConfigured, vm.state.first { it.loaded }.serverStatus)

        store.state.value = configured.copy(lastSyncAt = now)
        assertEquals(ServerStatus.Ok(now), vm.state.first { it.serverStatus is ServerStatus.Ok }.serverStatus)

        store.state.value = configured.copy(lastSyncAt = now, lastSyncError = "HTTP 503")
        assertEquals(ServerStatus.Error("HTTP 503", now), vm.state.first { it.serverStatus is ServerStatus.Error }.serverStatus)
    }

    @Test
    fun `unsynced count comes from the repository and ignores synced rows`() = runTest {
        val repo = testRepository()
        repo.insert(event("a"))
        repo.insert(event("b", packageName = "com.example.beta"))
        repo.insert(event("c"))
        repo.markSynced(listOf("a"), syncedAt = now)

        val vm = viewModel(FakeSettingsStore(configured), repo = repo)
        assertEquals(2L, vm.state.first { it.unsyncedCount > 0 }.unsyncedCount)
    }

    @Test
    fun `fields seed from the store and the counts follow it`() = runTest {
        val store = FakeSettingsStore(
            configured.copy(holdPackages = setOf("com.example.a", "com.example.b"), excludedPackages = setOf("com.example.c"), logOngoing = true),
        )
        val vm = viewModel(store)
        val s = vm.state.first { it.loaded }
        assertEquals(configured.serverUrl, s.serverUrl)
        assertEquals(configured.token, s.token)
        assertEquals(2, s.holdCount)
        assertEquals(1, s.excludedCount)
        assertTrue(s.logOngoing)
    }

    @Test
    fun `url and token edits reach the store once, debounced, with the final values`() = runTest {
        val fake = FakeSettingsStore()
        val store = CountingStore(fake)
        val vm = viewModel(store)
        vm.state.first { it.loaded }

        vm.onUrlChange("https://a")
        advanceTimeBy(100)
        vm.onUrlChange("https://ab")
        advanceTimeBy(100)
        vm.onTokenChange("synthetic")
        assertEquals("", fake.state.value.serverUrl, "nothing written before the debounce elapses")

        advanceTimeBy(600)
        runCurrent()
        assertEquals("https://ab", fake.state.value.serverUrl)
        assertEquals("synthetic", fake.state.value.token)
        assertEquals(1, store.writes)
        assertEquals("https://ab", vm.state.value.serverUrl, "local field keeps the typed value")
    }

    @Test
    fun `test connection reports ok and wrong token inline`() = runTest {
        val okVm = viewModel(FakeSettingsStore(configured), status = HttpStatusCode.OK)
        okVm.state.first { it.loaded }
        okVm.testConnection()
        assertEquals(TestResult.Ok, okVm.state.first { it.testResult != null }.testResult)
        assertFalse(okVm.state.value.testing)

        val badVm = viewModel(FakeSettingsStore(configured), status = HttpStatusCode.Unauthorized)
        badVm.state.first { it.loaded }
        badVm.testConnection()
        assertEquals(TestResult.WrongToken, badVm.state.first { it.testResult != null }.testResult)
    }

    @Test
    fun `sync now delegates to the scheduler`() = runTest {
        val sync = RecordingSyncScheduler()
        val vm = viewModel(FakeSettingsStore(configured), sync = sync)
        vm.syncNow()
        assertEquals(1, sync.calls)
    }

    @Test
    fun `listener state combines the probe with the store and refreshes on demand`() = runTest {
        val store = FakeSettingsStore(configured.copy(listenerConnectedAt = now - 120_000))
        val probe = FakeProbe(listenerEnabled = false)
        val vm = viewModel(store, probe = probe)
        assertEquals(ListenerState.NotEnabled, vm.state.first { it.loaded }.listener)
        assertFalse(vm.state.value.batteryExempt)

        probe.listenerEnabled = true
        probe.batteryExempt = true
        vm.refreshSystemState()
        val s = vm.state.first { it.listener is ListenerState.Connected }
        assertEquals(ListenerState.Connected(now - 120_000), s.listener)
        assertTrue(s.batteryExempt)

        store.state.value = store.state.value.copy(listenerDisconnectedAt = now - 60_000)
        assertEquals(ListenerState.Disconnected(now - 60_000), vm.state.first { it.listener is ListenerState.Disconnected }.listener)
    }

    @Test
    fun `log ongoing switch writes through`() = runTest {
        val store = FakeSettingsStore(configured)
        val vm = viewModel(store)
        vm.setLogOngoing(true)
        assertTrue(store.state.value.logOngoing)
        vm.setLogOngoing(false)
        assertFalse(store.state.value.logOngoing)
    }
}
