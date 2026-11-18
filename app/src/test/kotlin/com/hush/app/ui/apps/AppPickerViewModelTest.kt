package com.hush.app.ui.apps

import androidx.lifecycle.SavedStateHandle
import com.hush.app.db.AppSummary
import com.hush.app.focus.FocusRules
import com.hush.app.focus.Presets
import com.hush.app.settings.Settings
import com.hush.app.ui.components.AppCategoryResolver
import com.hush.app.testing.FakeFocusActions
import com.hush.app.testing.FakeSettingsStore
import com.hush.app.testing.event
import com.hush.app.testing.testRepository
import com.hush.app.ui.Routes
import com.hush.app.testing.MainDispatcherRule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Rule

@OptIn(ExperimentalCoroutinesApi::class)
class AppPickerViewModelTest {

    private val noCategories = AppCategoryResolver { null }


    @get:Rule
    val mainRule = MainDispatcherRule()

    private val installed = listOf(
        InstalledApp("com.example.zulu", "Zulu"),
        InstalledApp("com.example.photos", "Photos"),
        InstalledApp("com.example.nolabel", "com.example.nolabel"),
        InstalledApp("com.example.alpha", "alpha"),
    )

    private class FakeAppsSource(private val apps: List<InstalledApp>) : InstalledAppsSource {
        val gate = CompletableDeferred<Unit>()
        override suspend fun launchable(): List<InstalledApp> { gate.await(); return apps }
    }

    // ---- pure ordering ----

    @Test
    fun `selected first then alphabetical, labels case-insensitive`() {
        val rows = orderApps(installed, emptyList(), selected = setOf("com.example.zulu"), query = "")
        assertEquals(
            listOf("com.example.zulu", "com.example.alpha", "com.example.nolabel", "com.example.photos"),
            rows.map { it.packageName },
        )
        assertEquals(listOf(true, false, false, false), rows.map { it.checked })
        assertEquals("Zulu", rows[0].label)
    }

    @Test
    fun `merges seen-in-history and uninstalled-but-selected apps, de-duplicated, with label fallback`() {
        val seen = listOf(
            AppSummary("com.example.photos", "Photos (old label)", count = 3, lastSeen = 1L), // duplicate: installed label wins
            AppSummary("com.example.seenonly", "Seen Only", count = 1, lastSeen = 2L),
            AppSummary("com.example.unlabeled", null, count = 1, lastSeen = 3L),
        )
        val rows = orderApps(installed, seen, selected = setOf("com.example.gone"), query = "")
        assertEquals(1, rows.count { it.packageName == "com.example.photos" })
        assertEquals("Photos", rows.first { it.packageName == "com.example.photos" }.label)
        assertEquals("Seen Only", rows.first { it.packageName == "com.example.seenonly" }.label)
        assertEquals("com.example.unlabeled", rows.first { it.packageName == "com.example.unlabeled" }.label)
        assertEquals("com.example.gone", rows.first().packageName) // selected, even though neither installed nor seen
        assertTrue(rows.first().checked)
    }

    @Test
    fun `search matches label or package, case-insensitive, and can match nothing`() {
        assertEquals(listOf("com.example.photos"), orderApps(installed, emptyList(), emptySet(), "PHOT").map { it.packageName })
        assertEquals(listOf("com.example.nolabel"), orderApps(installed, emptyList(), emptySet(), "nolab").map { it.packageName })
        assertTrue(orderApps(installed, emptyList(), emptySet(), "instagram").isEmpty())
        assertEquals(4, orderApps(installed, emptyList(), emptySet(), "  ").size)
    }

    // ---- view model ----

    private fun savedState(list: String) = SavedStateHandle(mapOf(Routes.ARG_LIST to list))

    @Test
    fun `shows loading until the scan returns, then the merged ordered list`() = runTest {
        val source = FakeAppsSource(installed)
        val repo = testRepository()
        repo.insert(event("x", packageName = "com.example.seenonly", appLabel = "Seen Only"))
        val store = FakeSettingsStore(Settings(holdPackages = setOf("com.example.photos")))
        val vm = AppPickerViewModel(savedState("hold"), source, repo, store, noCategories, FakeFocusActions(store))
        backgroundScope.launch { vm.state.collect {} }

        assertTrue(vm.state.value.loading)
        source.gate.complete(Unit)
        val s = vm.state.first { !it.loading }
        assertEquals(PickerList.HOLD, s.list)
        assertEquals(1, s.selectedCount)
        assertEquals(
            listOf("com.example.photos", "com.example.alpha", "com.example.nolabel", "com.example.seenonly", "com.example.zulu"),
            s.apps.map { it.packageName },
        )
        assertTrue(s.apps.first().checked)
    }

    @Test
    fun `hold picker writes holdPackages only and re-orders`() = runTest {
        val source = FakeAppsSource(installed).also { it.gate.complete(Unit) }
        val store = FakeSettingsStore()
        val vm = AppPickerViewModel(savedState("hold"), source, testRepository(), store, noCategories, FakeFocusActions(store))
        backgroundScope.launch { vm.state.collect {} }
        vm.state.first { !it.loading }

        vm.setChecked("com.example.zulu", true)
        assertEquals(setOf("com.example.zulu"), store.state.value.holdPackages)
        assertEquals(emptySet(), store.state.value.excludedPackages)
        assertEquals("com.example.zulu", vm.state.first { it.selectedCount == 1 }.apps.first().packageName)

        vm.setChecked("com.example.zulu", false)
        assertEquals(emptySet(), store.state.value.holdPackages)
        assertFalse(vm.state.first { it.selectedCount == 0 }.apps.any { it.checked })
    }

    @Test
    fun `excluded picker writes excludedPackages only`() = runTest {
        val source = FakeAppsSource(installed).also { it.gate.complete(Unit) }
        val store = FakeSettingsStore(Settings(holdPackages = setOf("com.example.alpha")))
        val vm = AppPickerViewModel(savedState("excluded"), source, testRepository(), store, noCategories, FakeFocusActions(store))
        backgroundScope.launch { vm.state.collect {} }
        val s = vm.state.first { !it.loading }
        assertEquals(PickerList.EXCLUDED, s.list)
        assertEquals(0, s.selectedCount, "the hold list does not leak into the excluded picker")

        vm.setChecked("com.example.photos", true)
        assertEquals(setOf("com.example.photos"), store.state.value.excludedPackages)
        assertEquals(setOf("com.example.alpha"), store.state.value.holdPackages)
    }

    @Test
    fun `search filters and reports no matches`() = runTest {
        val source = FakeAppsSource(installed).also { it.gate.complete(Unit) }
        val store = FakeSettingsStore()
        val vm = AppPickerViewModel(savedState("hold"), source, testRepository(), store, noCategories, FakeFocusActions(store))
        backgroundScope.launch { vm.state.collect {} }
        vm.state.first { !it.loading }

        vm.setQuery("pho")
        assertEquals(listOf("com.example.photos"), vm.state.first { it.query == "pho" }.apps.map { it.packageName })
        vm.setQuery("nothing-here")
        assertTrue(vm.state.first { it.query == "nothing-here" }.apps.isEmpty())
    }

    @Test
    fun `scan failure surfaces its cause and still shows history apps`() = runTest {
        val source = object : InstalledAppsSource {
            override suspend fun launchable(): List<InstalledApp> = throw IllegalStateException("package manager died")
        }
        val repo = testRepository()
        repo.insert(event("x", packageName = "com.example.seenonly", appLabel = "Seen Only"))
        val store = FakeSettingsStore()
        val vm = AppPickerViewModel(savedState("hold"), source, repo, store, noCategories, FakeFocusActions(store))
        backgroundScope.launch { vm.state.collect {} }
        val s = vm.state.first { !it.loading && it.apps.isNotEmpty() }
        assertEquals("package manager died", s.loadError)
        assertEquals(listOf("com.example.seenonly"), s.apps.map { it.packageName })
    }

    @Test
    fun `unknown list argument falls back to hold`() {
        assertEquals(PickerList.HOLD, PickerList.from(null))
        assertEquals(PickerList.HOLD, PickerList.from("bogus"))
        assertEquals(PickerList.EXCLUDED, PickerList.from("excluded"))
    }

    @Test
    fun `a newly held app lands in its suggested group and un-holding forgets the group`() = runTest {
        val source = FakeAppsSource(installed).also { it.gate.complete(Unit) }
        val store = FakeSettingsStore(Settings(rules = FocusRules(categories = Presets.categories, seeded = true)))
        // ApplicationInfo.CATEGORY_NEWS = 5
        val actions = FakeFocusActions(store)
        val vm = AppPickerViewModel(savedState("hold"), source, testRepository(), store, { pkg -> if (pkg == "com.example.zulu") 5 else null }, actions)
        backgroundScope.launch { vm.state.collect {} }
        vm.state.first { !it.loading }

        vm.setChecked("com.example.zulu", true)
        vm.setChecked("com.example.alpha", true)
        assertEquals(setOf("com.example.zulu", "com.example.alpha"), store.state.value.holdPackages)
        assertEquals(mapOf("com.example.zulu" to Presets.NEWS), store.state.value.rules.appCategory, "no suggestion = no group")

        vm.setChecked("com.example.zulu", false)
        assertEquals(setOf("com.example.alpha"), store.state.value.holdPackages)
        assertEquals(emptyMap(), store.state.value.rules.appCategory)
        assertEquals(3, actions.rulesUpdates, "every hold-list change is a rule edit: FocusActions re-arms and releases")
    }

    @Test
    fun `the excluded list is plain settings, and a hold change that fails says why`() = runTest {
        val source = FakeAppsSource(installed).also { it.gate.complete(Unit) }
        val store = FakeSettingsStore()
        val actions = FakeFocusActions(store)
        val excluded = AppPickerViewModel(savedState("excluded"), source, testRepository(), store, noCategories, actions)
        excluded.setChecked("com.example.alpha", true)
        assertEquals(setOf("com.example.alpha"), store.state.value.excludedPackages)
        assertEquals(0, actions.rulesUpdates)

        actions.rulesError = IllegalStateException("disk full")
        val hold = AppPickerViewModel(savedState("hold"), source, testRepository(), store, noCategories, actions)
        backgroundScope.launch { hold.state.collect {} }
        hold.setChecked("com.example.zulu", true)
        assertEquals("disk full", hold.state.first { it.saveError != null }.saveError)
        assertEquals(emptySet(), store.state.value.holdPackages)
    }
}
