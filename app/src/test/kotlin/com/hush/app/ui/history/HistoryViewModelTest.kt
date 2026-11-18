package com.hush.app.ui.history

import com.hush.app.db.EventType
import com.hush.app.db.NotificationRepository
import com.hush.app.db.ChatSummary
import com.hush.app.focus.ChatRule
import com.hush.app.focus.INSTANT
import com.hush.app.open.OpenResult
import com.hush.app.settings.Settings
import com.hush.app.testing.FakeFocusActions
import com.hush.app.testing.FakeSettingsStore
import com.hush.app.testing.FakeOpener
import com.hush.app.testing.FakeSystemStatus
import com.hush.app.testing.MainDispatcherRule
import com.hush.app.testing.cancelAndJoin
import com.hush.app.testing.event
import com.hush.app.testing.fixedClock
import com.hush.app.testing.testRepository
import com.hush.app.ui.components.SheetItem
import com.hush.app.ui.components.UiSignals
import com.hush.app.ui.components.filterSheetItems
import com.hush.app.ui.rules.ChatThrottle
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Rule

@OptIn(ExperimentalCoroutinesApi::class)
class HistoryViewModelTest {

    @get:Rule
    val mainDispatcher = MainDispatcherRule()

    private val repo: NotificationRepository = testRepository(fixedClock())
    private val system = FakeSystemStatus()
    private val signals = UiSignals()
    private val opener = FakeOpener()
    private val settings = FakeSettingsStore(Settings(slotMinutes = 60, holdPackages = setOf("com.example.alpha")))
    private val focusActions = FakeFocusActions(settings)
    private val vms = mutableListOf<HistoryViewModel>()

    private fun vm(): HistoryViewModel =
        HistoryViewModel(repo, system, signals, opener, settings, focusActions, ZoneOffset.UTC, debounceMillis = HistoryViewModel.DEBOUNCE_MILLIS).also { vms += it }

    @After
    fun tearDown() = vms.forEach { it.cancelAndJoin() }

    private fun at(iso: String) = Instant.parse(iso).toEpochMilli()

    private suspend fun HistoryViewModel.loaded(): HistoryUiState = state.first { !it.loading }

    private fun HistoryUiState.ids() = days.flatMap { it.items }.map { it.id }

    @Test
    fun `the muted filter shows what is muted now, combines with the app filter, and the count offers it`() = runTest {
        repo.insert(event("m1", packageName = "com.example.alpha", postTime = 10, held = true), muted = true)
        repo.insert(event("m2", packageName = "com.example.beta", postTime = 20, held = true), muted = true)
        repo.insert(event("h", packageName = "com.example.alpha", postTime = 30, held = true))
        repo.insert(event("p", packageName = "com.example.alpha", postTime = 40))
        val vm = vm()
        assertEquals(2, vm.loaded().mutedCount)

        vm.setMutedFilter(true)
        val muted = vm.state.first { (it.mode as? HistoryMode.Browse)?.muted == true }
        assertEquals(listOf("m2", "m1"), muted.ids())
        assertEquals(2, muted.total)

        vm.setAppFilter("com.example.alpha")
        assertEquals(listOf("m1"), vm.state.first { (it.mode as? HistoryMode.Browse)?.app == "com.example.alpha" }.ids())

        repo.unmute(listOf("m1"))
        val emptied = vm.state.first { it.mutedCount == 1 && it.days.isEmpty() }
        assertEquals(EmptyKind.APP_EMPTY, emptied.empty)

        vm.setMutedFilter(false)
        assertEquals(listOf("p", "h", "m1"), vm.state.first { (it.mode as? HistoryMode.Browse)?.muted == false }.ids())
    }

    @Test
    fun `a request from the Focus screen shows the muted ones, dropping the digest view and other filters`() = runTest {
        repo.insert(event("m", packageName = "com.example.beta", postTime = 10, held = true), muted = true)
        repo.insert(event("h", held = true, postTime = 20))
        repo.markDelivered(listOf("h"), deliveredAt = 1_000)
        signals.digestLink.value = DigestLink(deliveredAt = 1_000)
        val vm = vm()
        vm.setAppFilter("com.example.alpha")
        assertEquals(listOf("h"), vm.loaded().ids())

        signals.showMuted.value = true

        val s = vm.state.first { (it.mode as? HistoryMode.Browse)?.muted == true }
        assertEquals(HistoryMode.Browse(muted = true), s.mode)
        assertEquals(listOf("m"), s.ids())
        assertNull(signals.digestLink.value)
        assertEquals(false, signals.showMuted.value, "taken once")
    }

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
    fun `app filter narrows to one package and null clears it`() = runTest {
        repo.insert(event("a1", packageName = "com.example.alpha", postTime = 30))
        repo.insert(event("b1", packageName = "com.example.beta", postTime = 20))
        repo.insert(event("a2", packageName = "com.example.alpha", postTime = 10))
        val vm = vm()
        assertEquals(listOf("com.example.alpha", "com.example.beta"), vm.loaded().apps.map { it.packageName })

        vm.setAppFilter("com.example.beta")
        val filtered = vm.state.first { (it.mode as? HistoryMode.Browse)?.app == "com.example.beta" }
        assertEquals(listOf("b1"), filtered.ids())
        assertEquals(1, filtered.total)

        vm.setAppFilter(null)
        assertEquals(listOf("a1", "b1", "a2"), vm.state.first { (it.mode as? HistoryMode.Browse)?.app == null }.ids())
    }

    @Test
    fun `the filtered app's chats are listed, and a chat filter narrows inside the app`() = runTest {
        val pkg = "com.example.alpha"
        repo.insert(event("g1", packageName = pkg, title = "Sender A", conversationId = "c1", conversationTitle = "Group one", postTime = 50))
        repo.insert(event("g2", packageName = pkg, title = "Sender B", conversationId = "c1", conversationTitle = "Group one", postTime = 40))
        repo.insert(event("legacy", packageName = pkg, title = "Sender A", postTime = 30))
        repo.insert(event("beta", packageName = "com.example.beta", title = "Sender A", postTime = 20))
        val vm = vm()
        assertTrue(vm.loaded().chats.isEmpty(), "no app selected, no chat list")

        // A chat filter without an app is ignored: the next mode (forced by a search) still has no chat.
        vm.setChatFilter("c1")
        vm.setQuery("Sender")
        assertNull((vm.state.first { (it.mode as? HistoryMode.Browse)?.query == "Sender" }.mode as HistoryMode.Browse).chat)
        vm.setQuery("")
        vm.state.first { (it.mode as? HistoryMode.Browse)?.query == "" }

        vm.setAppFilter(pkg)
        val app = vm.state.first { it.chats.isNotEmpty() }
        assertEquals(listOf(ChatSummary("c1", "Group one", 2, 50), ChatSummary("Sender A", "Sender A", 1, 30)), app.chats)

        vm.setChatFilter("c1")
        assertEquals(listOf("g1", "g2"), vm.state.first { (it.mode as? HistoryMode.Browse)?.chat == "c1" }.ids())

        vm.setChatFilter("Sender A")
        assertEquals(listOf("legacy"), vm.state.first { (it.mode as? HistoryMode.Browse)?.chat == "Sender A" }.ids())

        vm.setAppFilter("com.example.beta")
        val other = vm.state.first { (it.mode as? HistoryMode.Browse)?.app == "com.example.beta" }
        assertNull((other.mode as HistoryMode.Browse).chat, "a chat belongs to its app")
        assertEquals(listOf("beta"), other.ids())
    }

    @Test
    fun `a tap opens the row, and a failure names the app until dismissed`() = runTest {
        val row = event("a", appLabel = "Alpha")
        repo.insert(row)
        val vm = vm()
        vm.loaded()

        vm.open(row)
        assertEquals(listOf("a"), opener.opened)
        assertNull(vm.state.first { !it.loading }.openFailure)

        opener.result = OpenResult.FAILED
        vm.open(row)
        assertEquals("Alpha", vm.state.first { it.openFailure != null }.openFailure)

        vm.dismissOpenFailure()
        assertNull(vm.state.first { it.openFailure == null }.openFailure)
    }

    @Test
    fun `digest link shows the held rows delivered since its time and clearing returns to browse`() = runTest {
        repo.insert(event("h1", held = true, postTime = 1))
        repo.insert(event("h2", held = true, postTime = 2))
        repo.insert(event("h3", held = true, postTime = 3))
        repo.insert(event("p", held = false, postTime = 4))
        repo.markDelivered(listOf("h1", "h2"), deliveredAt = 1_000)
        repo.markDelivered(listOf("h3"), deliveredAt = 2_000)
        val vm = vm()
        assertEquals(listOf("p", "h3", "h2", "h1"), vm.loaded().ids())

        signals.digestLink.value = DigestLink(deliveredAt = 2_000)

        val batch = vm.state.first { it.mode is HistoryMode.HeldBatch }
        assertEquals(HistoryMode.HeldBatch(2_000), batch.mode)
        assertEquals(listOf("h3"), batch.ids())
        assertEquals(2_000L, batch.days.single().items.first().deliveredAt)

        // A digest that replaced an unread one links to the older delivery time and covers both runs.
        signals.digestLink.value = DigestLink(deliveredAt = 1_000)
        val carried = vm.state.first { it.mode == HistoryMode.HeldBatch(1_000) }
        assertEquals(listOf("h3", "h2", "h1"), carried.ids(), "batch is newest-first like the rest of the list")

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
    fun `throttle this chat shows what the chat gets now, and the choice becomes a chat rule`() = runTest {
        val row = event("g1", conversationId = "c1", conversationTitle = "Group one", appLabel = "Alpha")
        repo.insert(row)
        val vm = vm()
        vm.loaded()

        vm.askChatThrottle(row)
        val dialog = vm.state.first { it.chatThrottle != null }.chatThrottle
        assertEquals(ChatThrottle("com.example.alpha", "c1", "Group one", "Alpha", ruleMinutes = null, appMinutes = 60), dialog)

        vm.setChatThrottle(240)
        assertNull(vm.state.first { it.chatThrottle == null }.chatThrottle, "picking closes the dialog")
        assertEquals(listOf(ChatRule("com.example.alpha", "c1", "Group one", 240)), settings.state.value.rules.chatRules)

        vm.askChatThrottle(row)
        assertEquals(240, vm.state.first { it.chatThrottle != null }.chatThrottle?.ruleMinutes)
        vm.setChatThrottle(INSTANT)
        assertEquals(INSTANT, settings.state.first { it.rules.chatRules.singleOrNull()?.minutes == INSTANT }.rules.chatRules.single().minutes)

        vm.askChatThrottle(row)
        vm.state.first { it.chatThrottle != null }
        vm.setChatThrottle(null)
        assertTrue(settings.state.first { it.rules.chatRules.isEmpty() }.rules.chatRules.isEmpty(), "same as app = no rule")
    }

    @Test
    fun `a chat of an app that is not held reports the app as not held, and a row without a chat opens no dialog`() = runTest {
        val vm = vm()
        vm.loaded()

        vm.askChatThrottle(event("b1", packageName = "com.example.beta", title = "Sender A"))
        val dialog = vm.state.first { it.chatThrottle != null }.chatThrottle
        assertEquals(INSTANT, dialog?.appMinutes)
        assertEquals("Sender A", dialog?.chatKey, "no conversation id: the title is the chat")
        vm.dismissChatThrottle()
        vm.state.first { it.chatThrottle == null }

        vm.askChatThrottle(event("n", title = null))
        vm.setQuery("x")
        assertNull(vm.state.first { (it.mode as? HistoryMode.Browse)?.query == "x" }.chatThrottle)
    }

    @Test
    fun `a chat rule that cannot be saved shows its cause until dismissed`() = runTest {
        val row = event("g1", conversationId = "c1", conversationTitle = "Group one")
        val vm = vm()
        vm.loaded()
        focusActions.rulesError = IllegalStateException("disk full")

        vm.askChatThrottle(row)
        vm.state.first { it.chatThrottle != null }
        vm.setChatThrottle(15)

        assertEquals("disk full", vm.state.first { it.ruleError != null }.ruleError)
        assertTrue(settings.state.value.rules.chatRules.isEmpty())
        vm.dismissRuleError()
        assertNull(vm.state.first { it.ruleError == null }.ruleError)
    }

    @Test
    fun `a short list is shown whole, with nothing to unroll`() = runTest {
        repeat(3) { i -> repo.insert(event("r$i", postTime = 1_000L + i)) }

        val s = vm().loaded()

        assertEquals(3, s.ids().size)
        assertEquals(3, s.total)
        assertTrue(!s.collapsed && !s.canLoadMore)
    }

    @Test
    fun `first view is the ten newest rows with the total, and REMOVED rows never take a slot`() = runTest {
        repeat(25) { i ->
            repo.insert(event("p$i", postTime = 1_000L + 2 * i))
            repo.insert(event("x$i", postTime = 1_001L + 2 * i, event = EventType.REMOVED))
        }

        val s = vm().loaded()

        assertEquals((24 downTo 15).map { "p$it" }, s.ids())
        assertEquals(25, s.total)
        assertTrue(s.collapsed, "more rows exist: offer Show all")
        assertTrue(!s.canLoadMore, "the preview does not page on scroll")
    }

    @Test
    fun `show all unrolls into the paged list`() = runTest {
        val page = HistoryViewModel.PAGE
        repeat(page + 15) { i -> repo.insert(event("r$i", postTime = 1_000L + i)) }
        val vm = vm()
        assertEquals(HistoryViewModel.PREVIEW, vm.loaded().ids().size)

        // Load more does nothing while collapsed: had it grown the limit, the unrolled list below would hold 2 pages' worth.
        vm.loadMore()
        vm.showAll()
        val unrolled = vm.state.first { !it.collapsed }
        assertEquals(page, unrolled.ids().size)
        assertTrue(unrolled.canLoadMore)

        vm.loadMore()
        val all = vm.state.first { it.ids().size == page + 15 }
        assertEquals("r0", all.ids().last())
        assertTrue(!all.canLoadMore && !all.collapsed)
    }

    @Test
    fun `changing the filter goes back to the ten-row preview`() = runTest {
        repeat(30) { i -> repo.insert(event("r$i", postTime = 1_000L + i)) }
        val vm = vm()
        vm.loaded()
        vm.showAll()
        assertEquals(30, vm.state.first { it.ids().size == 30 }.total)

        // Every fixture title starts with "title", so the search matches all rows.
        vm.setQuery("title")

        val searched = vm.state.first { (it.mode as? HistoryMode.Browse)?.query == "title" }
        assertEquals(HistoryViewModel.PREVIEW, searched.ids().size)
        assertEquals(30, searched.total)
        assertTrue(searched.collapsed)
    }

    @Test
    fun `coming back after Hush was in the background keeps the filter and the unrolled list`() = runTest {
        repeat(30) { i -> repo.insert(event("r$i", postTime = 1_000L + i)) }
        val vm = vm()
        vm.loaded()
        vm.setQuery("title")
        vm.state.first { (it.mode as? HistoryMode.Browse)?.query == "title" }
        vm.showAll()
        assertEquals(30, vm.state.first { !it.collapsed }.ids().size)

        // Nobody collects for longer than the 5 s grace (the tapped app is in front): the upstream is torn down.
        advanceTimeBy(6_000)
        runCurrent()
        // Back in Hush. A new row forces a fresh emission, so this is the re-collected state, not the cached one.
        repo.insert(event("new", postTime = 9_000L))

        val back = vm.state.first { it.total == 31 }
        assertEquals(HistoryMode.Browse(query = "title"), back.mode)
        assertEquals(31, back.ids().size, "still unrolled")
        assertTrue(!back.collapsed && !back.loading)
    }

    @Test
    fun `show all and load more belong to the mode they happened in`() = runTest {
        repeat(30) { i -> repo.insert(event("a$i", packageName = "com.example.alpha", postTime = 1_000L + i)) }
        repeat(30) { i -> repo.insert(event("b$i", packageName = "com.example.beta", postTime = 5_000L + i)) }
        val vm = vm()
        vm.loaded()
        vm.setAppFilter("com.example.alpha")
        vm.state.first { (it.mode as? HistoryMode.Browse)?.app == "com.example.alpha" }
        vm.showAll()
        assertEquals(30, vm.state.first { !it.collapsed }.ids().size)

        vm.setAppFilter("com.example.beta")
        val beta = vm.state.first { (it.mode as? HistoryMode.Browse)?.app == "com.example.beta" }
        assertEquals(HistoryViewModel.PREVIEW, beta.ids().size)
        assertTrue(beta.collapsed)
    }

    @Test
    fun `a digest batch also starts as a ten-row preview`() = runTest {
        val ids = (0 until 12).map { "h$it" }
        ids.forEachIndexed { i, id -> repo.insert(event(id, held = true, postTime = 1L + i)) }
        repo.markDelivered(ids, deliveredAt = 1_000)
        signals.digestLink.value = DigestLink(deliveredAt = 1_000)
        val vm = vm()

        val preview = vm.loaded()
        assertEquals((11 downTo 2).map { "h$it" }, preview.ids())
        assertEquals(12, preview.total)
        assertTrue(preview.collapsed)

        vm.showAll()
        val all = vm.state.first { it.ids().size == 12 }
        assertTrue(!all.collapsed && !all.canLoadMore)
    }

    @Test
    fun `sheet search matches label or key, ignoring case`() {
        val items = listOf(
            SheetItem("com.example.alpha", "Alpha", 3, "com.example.alpha"),
            SheetItem("com.example.beta", "Beta", 1, "com.example.beta"),
        )
        assertEquals(items, filterSheetItems(items, "  "))
        assertEquals(listOf(items[1]), filterSheetItems(items, "bET"))
        assertEquals(listOf(items[0]), filterSheetItems(items, "example.al"))
        assertTrue(filterSheetItems(items, "zzz").isEmpty())
    }
}
