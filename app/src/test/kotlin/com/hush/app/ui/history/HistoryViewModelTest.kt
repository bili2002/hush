package com.hush.app.ui.history

import androidx.lifecycle.viewModelScope
import com.hush.app.db.EventType
import com.hush.app.db.NotificationRepository
import com.hush.app.testing.FakeSystemStatus
import com.hush.app.testing.MainDispatcherRule
import com.hush.app.testing.event
import com.hush.app.testing.fixedClock
import com.hush.app.testing.testRepository
import com.hush.app.ui.components.UiSignals
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Rule

class HistoryViewModelTest {

    @get:Rule
    val mainDispatcher = MainDispatcherRule()

    private val repo: NotificationRepository = testRepository(fixedClock())
    private val system = FakeSystemStatus()
    private val signals = UiSignals()
    private val vms = mutableListOf<HistoryViewModel>()

    private fun vm(): HistoryViewModel =
        HistoryViewModel(repo, system, signals, ZoneOffset.UTC, debounceMillis = HistoryViewModel.DEBOUNCE_MILLIS).also { vms += it }

    @After
    fun tearDown() = vms.forEach { it.viewModelScope.cancel() }

    private fun at(iso: String) = Instant.parse(iso).toEpochMilli()

    private suspend fun HistoryViewModel.loaded(): HistoryUiState = state.first { !it.loading }

    private fun HistoryUiState.ids() = days.flatMap { it.items }.map { it.id }

    @Test
    fun `loading until the first page arrives, then rows are grouped by day newest first`() = runTest {
        repo.insert(event("y1", postTime = at("2024-01-09T08:00:00Z")))
        repo.insert(event("t1", postTime = at("2024-01-10T09:00:00Z")))
        repo.insert(event("t2", postTime = at("2024-01-10T11:30:00Z")))
        repo.insert(event("y2", postTime = at("2024-01-09T20:00:00Z")))
        val vm = vm()

        assertTrue(vm.state.value.loading)
        val s = vm.loaded()

        assertEquals(listOf(LocalDate.of(2024, 1, 10), LocalDate.of(2024, 1, 9)), s.days.map { it.date })
        assertEquals(listOf("t2", "t1"), s.days[0].items.map { it.id })
        assertEquals(listOf("y2", "y1"), s.days[1].items.map { it.id })
        assertNull(s.empty)
        assertIs<HistoryMode.Browse>(s.mode)
    }

    @Test
    fun `REMOVED events are not listed`() = runTest {
        repo.insert(event("posted", postTime = 10))
        repo.insert(event("removed", postTime = 20, event = EventType.REMOVED))

        assertEquals(listOf("posted"), vm().loaded().ids())
    }

    @Test
    fun `search is debounced and filters by title or text`() = runTest {
        repo.insert(event("a", title = "needle in title", text = "x", postTime = 30))
        repo.insert(event("b", title = "y", text = "a needle", postTime = 20))
        repo.insert(event("c", title = "nothing", text = "else", postTime = 10))
        val vm = vm()
        assertEquals(listOf("a", "b", "c"), vm.loaded().ids())

        vm.setQuery("needle")
        assertEquals("needle", vm.query.value)

        val s = vm.state.first { (it.mode as? HistoryMode.Browse)?.query == "needle" }
        assertEquals(listOf("a", "b"), s.ids())
    }

    @Test
    fun `no matches empty state carries the query`() = runTest {
        repo.insert(event("a", title = "alpha", text = "x"))
        val vm = vm()
        vm.loaded()

        vm.setQuery("zzz")

        val s = vm.state.first { (it.mode as? HistoryMode.Browse)?.query == "zzz" }
        assertEquals(EmptyKind.NO_MATCHES, s.empty)
        assertTrue(s.days.isEmpty())
    }

    @Test
    fun `app chip filters to one package and tapping it again clears`() = runTest {
        repo.insert(event("a1", packageName = "com.example.alpha", postTime = 30))
        repo.insert(event("b1", packageName = "com.example.beta", postTime = 20))
        repo.insert(event("a2", packageName = "com.example.alpha", postTime = 10))
        val vm = vm()
        assertEquals(listOf("com.example.alpha", "com.example.beta"), vm.loaded().apps.map { it.packageName })

        vm.setAppFilter("com.example.beta")
        assertEquals(listOf("b1"), vm.state.first { (it.mode as? HistoryMode.Browse)?.app == "com.example.beta" }.ids())

        vm.setAppFilter("com.example.beta")
        assertEquals(listOf("a1", "b1", "a2"), vm.state.first { (it.mode as? HistoryMode.Browse)?.app == null }.ids())
    }

    @Test
    fun `digest link shows only the held batch delivered at that time and clearing returns to browse`() = runTest {
        repo.insert(event("h1", held = true, postTime = 1))
        repo.insert(event("h2", held = true, postTime = 2))
        repo.insert(event("h3", held = true, postTime = 3))
        repo.insert(event("p", held = false, postTime = 4))
        repo.markDelivered(listOf("h1", "h2"), deliveredAt = 1_000)
        repo.markDelivered(listOf("h3"), deliveredAt = 2_000)
        val vm = vm()
        assertEquals(listOf("p", "h3", "h2", "h1"), vm.loaded().ids())

        signals.digestLink.value = DigestLink(deliveredAt = 1_000)

        val batch = vm.state.first { it.mode is HistoryMode.HeldBatch }
        assertEquals(HistoryMode.HeldBatch(1_000), batch.mode)
        assertEquals(listOf("h1", "h2"), batch.ids())
        assertEquals(1_000L, batch.days.single().items.first().deliveredAt)

        vm.clearDigestLink()

        assertNull(signals.digestLink.value)
        assertEquals(listOf("p", "h3", "h2", "h1"), vm.state.first { it.mode is HistoryMode.Browse }.ids())
    }

    @Test
    fun `digest link set before the ViewModel exists is applied on start`() = runTest {
        repo.insert(event("h1", held = true, postTime = 1))
        repo.markDelivered(listOf("h1"), deliveredAt = 1_000)
        signals.digestLink.value = DigestLink(deliveredAt = 1_000)

        val s = vm().loaded()

        assertEquals(HistoryMode.HeldBatch(1_000), s.mode)
        assertEquals(listOf("h1"), s.ids())
    }

    @Test
    fun `empty batch has its own empty kind`() = runTest {
        signals.digestLink.value = DigestLink(deliveredAt = 999)

        assertEquals(EmptyKind.BATCH_EMPTY, vm().loaded().empty)
    }

    @Test
    fun `empty archive with listener off asks to enable access`() = runTest {
        system.listener = false

        val s = vm().loaded()

        assertEquals(EmptyKind.LISTENER_OFF, s.empty)
        assertTrue(!s.listenerEnabled)
    }

    @Test
    fun `empty archive with listener on says nothing yet, and refresh flips the kind`() = runTest {
        val vm = vm()
        assertEquals(EmptyKind.NO_NOTIFICATIONS, vm.loaded().empty)

        system.listener = false
        vm.refreshSystemState()

        assertEquals(EmptyKind.LISTENER_OFF, vm.state.first { !it.listenerEnabled }.empty)
    }

    @Test
    fun `expanding a row toggles expandedId`() = runTest {
        repo.insert(event("a"))
        val vm = vm()
        vm.loaded()

        vm.toggleExpanded("a")
        assertEquals("a", vm.state.first { it.expandedId != null }.expandedId)

        vm.toggleExpanded("a")
        assertNull(vm.state.first { it.expandedId == null }.expandedId)
    }

    @Test
    fun `canLoadMore only when the page is full`() = runTest {
        repo.insert(event("a"))
        assertTrue(!vm().loaded().canLoadMore)
    }
}
