package com.hush.app.ui.rules

import com.hush.app.focus.ChatRule
import com.hush.app.focus.FocusCategory
import com.hush.app.focus.FocusRules
import com.hush.app.focus.INSTANT
import com.hush.app.focus.MUTED
import com.hush.app.focus.Presets
import com.hush.app.settings.Settings
import com.hush.app.testing.FakeFocusActions
import com.hush.app.testing.FakeSettingsStore
import com.hush.app.testing.MainDispatcherRule
import com.hush.app.testing.cancelAndJoin
import com.hush.app.testing.event
import com.hush.app.testing.testRepository
import com.hush.app.ui.Routes
import com.hush.app.ui.apps.InstalledApp
import com.hush.app.ui.apps.InstalledAppsSource
import com.hush.app.ui.components.SheetItem
import com.hush.app.ui.components.UiSignals
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Rule

@OptIn(ExperimentalCoroutinesApi::class)
class RulesViewModelTest {

    @get:Rule
    val mainDispatcher = MainDispatcherRule()

    private val alpha = "com.example.alpha"
    private val beta = "com.example.beta"
    private val signals = UiSignals()
    private val vms = mutableListOf<RulesViewModel>()
    private val repo = testRepository()

    /** What the phone has installed; a test that needs the scan to fail sets [installedError]. */
    private var installed = emptyList<String>()
    private var installedError: Exception? = null
    private val appsSource = object : InstalledAppsSource {
        override suspend fun launchable(): List<InstalledApp> {
            installedError?.let { throw it }
            return installed.map { InstalledApp(it, it) }
        }
    }

    /** Already seeded with one group, so most tests start from a small known state. */
    private val seeded = Settings(
        slotMinutes = 60,
        holdPackages = setOf(alpha, beta),
        rules = FocusRules(
            categories = listOf(FocusCategory("fast", "Fast", 15)),
            appCategory = mapOf(alpha to "fast"),
            chatRules = listOf(ChatRule(alpha, "c1", "Group one", 240)),
            seeded = true,
        ),
    )

    private fun vm(
        settings: FakeSettingsStore = FakeSettingsStore(seeded),
        actions: FakeFocusActions = FakeFocusActions(settings),
        osCategory: (String) -> Int? = { null },
    ): RulesViewModel = RulesViewModel(
        settings = settings,
        actions = actions,
        repo = repo,
        appsSource = appsSource,
        labels = { it.substringAfterLast('.').replaceFirstChar(Char::uppercase) },
        categories = osCategory,
        signals = signals,
        newId = { "new-id" },
    ).also { vms += it }

    @After
    fun tearDown() = vms.forEach { it.cancelAndJoin() }

    private suspend fun RulesViewModel.loaded(): RulesUiState = state.first { !it.loading }

    @Test
    fun `first visit seeds the presets and sorts the held apps, leaving intervals alone`() = runTest {
        val settings = FakeSettingsStore(Settings(slotMinutes = 30, holdPackages = setOf(alpha, beta)))
        // ApplicationInfo.CATEGORY_SOCIAL = 4
        val s = vm(settings, osCategory = { if (it == alpha) 4 else null }).loaded()

        assertTrue(settings.state.value.rules.seeded)
        assertEquals(Presets.categories.map { it.name } + listOf(null), s.groups.map { it.name })
        assertEquals(listOf("Alpha"), s.groups.single { it.id == Presets.SOCIAL }.apps.map { it.label })
        assertEquals(listOf("Beta"), s.groups.single { it.id == null }.apps.map { it.label })
        assertTrue(s.groups.all { it.effectiveMinutes == 30 }, "presets follow the default interval")
        assertEquals(30, s.defaultMinutes)
    }

    @Test
    fun `state lists groups, the uncategorised apps and the chat rules with what the app would give them`() = runTest {
        val s = vm().loaded()

        assertEquals(listOf("Fast" to listOf("Alpha"), null to listOf("Beta")), s.groups.map { g -> g.name to g.apps.map { it.label } })
        assertEquals(listOf(ChatRuleRow(ChatRule(alpha, "c1", "Group one", 240), "Alpha", appMinutes = 15)), s.chatRules)
    }

    @Test
    fun `group interval, rename, add and delete are persisted`() = runTest {
        val settings = FakeSettingsStore(seeded)
        val vm = vm(settings)
        vm.loaded()

        vm.setCategoryMinutes("fast", INSTANT)
        assertEquals(INSTANT, vm.state.first { it.groups.first().minutes == INSTANT }.groups.first().effectiveMinutes)

        vm.setCategoryMinutes("fast", null)
        assertEquals(60, vm.state.first { it.groups.first().minutes == null }.groups.first().effectiveMinutes)

        vm.renameCategory("fast", "Quick")
        vm.addCategory("Later")
        vm.addCategory("   ")
        assertEquals(
            listOf(FocusCategory("fast", "Quick", null), FocusCategory("new-id", "Later", null)),
            settings.state.first { it.rules.categories.size == 2 }.rules.categories,
            "a blank name adds nothing",
        )

        vm.deleteCategory("fast")
        val after = settings.state.first { it.rules.categories.size == 1 }
        assertEquals(setOf(alpha, beta), after.holdPackages, "deleting a group keeps its apps held")
        assertTrue(after.rules.appCategory.isEmpty())
    }

    @Test
    fun `moving an app changes its group and stop holding removes it`() = runTest {
        val settings = FakeSettingsStore(seeded)
        val vm = vm(settings)
        vm.loaded()

        vm.moveApp(beta, "fast")
        assertEquals(mapOf(alpha to "fast", beta to "fast"), settings.state.first { it.rules.appCategory.size == 2 }.rules.appCategory)

        vm.moveApp(alpha, null)
        assertEquals(mapOf(beta to "fast"), settings.state.first { it.rules.appCategory.size == 1 }.rules.appCategory)

        vm.stopHolding(beta)
        val after = settings.state.first { beta !in it.holdPackages }
        assertEquals(setOf(alpha), after.holdPackages)
        assertTrue(after.rules.appCategory.isEmpty())
    }

    @Test
    fun `every edit goes through FocusActions, which is what re-arms the alarm and releases rows`() = runTest {
        val settings = FakeSettingsStore(seeded)
        val actions = FakeFocusActions(settings)
        val vm = vm(settings, actions)
        val rule = vm.loaded().chatRules.single().rule

        vm.setDefaultMinutes(30)
        vm.setCategoryMinutes("fast", 30)
        vm.addCategory("Later")
        vm.renameCategory("fast", "Quick")
        vm.moveApp(beta, "fast")
        vm.stopHolding(beta)
        vm.setChatRuleMinutes(rule, 15)
        vm.removeChatRule(rule)
        vm.deleteCategory("fast")

        assertEquals(9, actions.rulesUpdates)
    }

    @Test
    fun `a failed first visit shows its cause and Retry seeds the presets`() = runTest {
        val settings = FakeSettingsStore(Settings(holdPackages = setOf(alpha)))
        val actions = FakeFocusActions(settings, rulesError = IllegalStateException("disk full"))
        val vm = vm(settings, actions)

        val failed = vm.state.first { it.error != null }
        assertTrue(failed.loading, "nothing to show yet")
        assertEquals("disk full", failed.error)

        actions.rulesError = null
        vm.seed()

        val s = vm.loaded()
        assertNull(s.error)
        assertEquals(Presets.categories.map { it.id } + listOf(null), s.groups.map { it.id })
    }

    @Test
    fun `chat rules can be changed and removed, and the default interval goes through FocusActions`() = runTest {
        val settings = FakeSettingsStore(seeded)
        val vm = vm(settings)
        val rule = vm.loaded().chatRules.single().rule

        vm.setChatRuleMinutes(rule, INSTANT)
        assertEquals(listOf(rule.copy(minutes = INSTANT)), settings.state.first { it.rules.chatRules.single().minutes == INSTANT }.rules.chatRules)

        vm.removeChatRule(rule)
        assertTrue(settings.state.first { it.rules.chatRules.isEmpty() }.rules.chatRules.isEmpty())

        vm.setDefaultMinutes(120)
        assertEquals(120, vm.state.first { it.defaultMinutes == 120 }.defaultMinutes)
    }

    @Test
    fun `an edit that fails shows its cause and leaves the rules as they were`() = runTest {
        val settings = FakeSettingsStore(seeded)
        val actions = FakeFocusActions(settings, rulesError = IllegalStateException("disk full"))
        val vm = vm(settings, actions)
        vm.loaded()

        vm.setCategoryMinutes("fast", 30)

        assertEquals("disk full", vm.state.first { it.error != null }.error)
        assertEquals(seeded, settings.state.value)

        actions.rulesError = null
        vm.setCategoryMinutes("fast", 30)
        assertNull(vm.state.first { it.groups.first().minutes == 30 }.error, "the next successful edit clears it")
    }

    @Test
    fun `a first visit with nothing held sorts the installed and the seen apps into the presets`() = runTest {
        installed = listOf("com.whatsapp", "com.example.game", "com.example.bank", Presets.neverAutomatic.first())
        repo.insert(event("n1", packageName = "com.example.paper"))
        val settings = FakeSettingsStore(Settings(excludedPackages = setOf("com.example.game")))
        // CATEGORY_GAME = 0, CATEGORY_NEWS = 5
        val s = vm(settings, osCategory = { mapOf("com.example.game" to 0, "com.example.paper" to 5)[it] }).state.first { it.autoSorted != null }

        assertEquals(2, s.autoSorted)
        assertEquals(setOf("com.whatsapp", "com.example.paper"), settings.state.value.holdPackages, "no category, excluded and SMS apps are left alone")
        assertEquals(
            mapOf("com.whatsapp" to Presets.MESSAGING, "com.example.paper" to Presets.NEWS),
            settings.state.value.rules.appCategory,
        )
    }

    @Test
    fun `a first visit with apps already held does not add any`() = runTest {
        installed = listOf("com.whatsapp")
        val settings = FakeSettingsStore(Settings(holdPackages = setOf(alpha)))

        val s = vm(settings).loaded()

        assertNull(s.autoSorted)
        assertEquals(setOf(alpha), settings.state.value.holdPackages)
    }

    @Test
    fun `auto-sort adds what it can, says how many, and the next edit clears the line`() = runTest {
        installed = listOf("com.whatsapp", alpha)
        val settings = FakeSettingsStore(seeded)
        val vm = vm(settings)
        vm.loaded()

        vm.autoSort()
        assertEquals(1, vm.state.first { it.autoSorted != null }.autoSorted)
        assertEquals(setOf(alpha, beta, "com.whatsapp"), settings.state.value.holdPackages)
        assertEquals(Presets.MESSAGING, settings.state.value.rules.appCategory["com.whatsapp"])
        assertTrue(settings.state.value.rules.categories.any { it.id == Presets.MESSAGING }, "the preset group it needed is created")

        vm.autoSort()
        runCurrent()
        assertEquals(0, vm.state.first { it.autoSorted == 0 }.autoSorted, "a second run finds nothing")

        vm.setDefaultMinutes(30)
        assertNull(vm.state.first { it.defaultMinutes == 30 }.autoSorted)
    }

    @Test
    fun `auto-sort that cannot list the installed apps says why`() = runTest {
        installedError = IllegalStateException("package manager died")
        val settings = FakeSettingsStore(seeded)
        val vm = vm(settings)
        vm.loaded()

        vm.autoSort()

        assertEquals("package manager died", vm.state.first { it.error != null }.error)
        assertEquals(seeded, settings.state.value)
    }

    @Test
    fun `adding a chat rule walks app, chat, throttle`() = runTest {
        repo.insert(event("a1", packageName = alpha, conversationId = "c9", conversationTitle = "Group nine", postTime = 30))
        repo.insert(event("a2", packageName = alpha, conversationId = "c9", conversationTitle = "Group nine", postTime = 20))
        repo.insert(event("b1", packageName = beta, title = "Sender B", postTime = 10))
        val settings = FakeSettingsStore(seeded)
        val vm = vm(settings)
        vm.loaded()

        vm.addChatRule(null)
        val apps = vm.state.first { it.chatPicker != null }.chatPicker!!
        assertNull(apps.packageName)
        assertEquals(listOf(SheetItem(alpha, "Alpha", 2, alpha), SheetItem(beta, "Beta", 1, beta)), apps.items)

        vm.pick(apps.items.first())
        val chats = vm.state.first { it.chatPicker?.packageName == alpha }.chatPicker!!
        assertEquals("Alpha", chats.appLabel)
        assertEquals(listOf(SheetItem("c9", "Group nine", 2, null)), chats.items)

        vm.pick(chats.items.single())
        val asking = vm.state.first { it.chatThrottle != null }
        assertNull(asking.chatPicker, "the sheet closes when the chat is picked")
        assertEquals(ChatThrottle(alpha, "c9", "Group nine", "Alpha", ruleMinutes = null, appMinutes = 15), asking.chatThrottle)
        assertEquals(1, settings.state.value.rules.chatRules.size, "nothing is saved before a throttle is chosen")

        vm.setChatThrottle(MUTED)
        val after = settings.state.first { it.rules.chatRules.size == 2 }
        assertTrue(ChatRule(alpha, "c9", "Group nine", MUTED) in after.rules.chatRules)
        assertNull(vm.state.first { it.chatThrottle == null }.chatThrottle)
    }

    @Test
    fun `a chat rule can be started from an app, and both steps can be dismissed`() = runTest {
        repo.insert(event("b1", packageName = beta, title = "Sender B"))
        val settings = FakeSettingsStore(seeded)
        val vm = vm(settings)
        vm.loaded()

        vm.addChatRule(beta)
        val chats = vm.state.first { it.chatPicker != null }.chatPicker!!
        assertEquals(beta, chats.packageName)
        assertEquals(listOf("Sender B"), chats.items.map { it.label })

        vm.dismissChatPicker()
        assertNull(vm.state.first { it.chatPicker == null }.chatPicker)
        vm.pick(chats.items.single())
        runCurrent()
        assertNull(vm.state.value.chatThrottle, "a pick with no sheet open does nothing")

        vm.addChatRule(beta)
        vm.pick(chats.items.single())
        vm.state.first { it.chatThrottle != null }
        vm.dismissChatThrottle()
        assertNull(vm.state.first { it.chatThrottle == null }.chatThrottle)
        assertEquals(seeded, settings.state.value)

        // "Same as app" for a chat that has a rule removes it.
        val existing = seeded.rules.chatRules.single()
        vm.addChatRule(alpha)
        vm.pick(SheetItem(existing.chatKey, existing.title, 1, null))
        assertEquals(240, vm.state.first { it.chatThrottle != null }.chatThrottle?.ruleMinutes)
        vm.setChatThrottle(null)
        assertTrue(settings.state.first { it.rules.chatRules.isEmpty() }.rules.chatRules.isEmpty())
    }

    @Test
    fun `add apps asks the shell for the hold picker`() = runTest {
        var route: String? = null
        val job = launch(start = CoroutineStart.UNDISPATCHED) { signals.navigate.collect { route = it } }

        vm().addApps()
        runCurrent()

        assertEquals(Routes.appPicker("hold"), route)
        job.cancel()
    }
}
