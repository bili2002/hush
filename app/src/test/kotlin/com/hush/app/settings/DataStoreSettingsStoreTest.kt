package com.hush.app.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class DataStoreSettingsStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val scope = TestScope(UnconfinedTestDispatcher() + Job())

    // One DataStore per file: construct exactly once per test.
    private fun store(file: File = File(tmp.root, "settings.preferences_pb")): DataStoreSettingsStore =
        DataStoreSettingsStore(PreferenceDataStoreFactory.create(scope = scope) { file })

    @Test
    fun `fresh store reads defaults`() = runTest(scope.testScheduler) {
        val store = store()
        assertEquals(Settings(), store.current())
        assertEquals(Settings(), store.settings.first())
    }

    @Test
    fun `update round-trips every field type and clears nullables`() = runTest(scope.testScheduler) {
        val store = store()
        val changed = Settings(
            serverUrl = "https://example.invalid:8443",
            token = "synthetic-token",
            focusEnabled = true,
            slotMinutes = 30,
            holdPackages = setOf("com.example.alpha", "com.example.beta"),
            excludedPackages = setOf("com.example.gamma"),
            logOngoing = true,
            listenerConnectedAt = 123L,
            listenerDisconnectedAt = 456L,
            lastSyncAt = 789L,
            lastSyncError = "boom",
            lastFocusError = "bang",
            setupDone = true,
        )

        store.update { changed }
        assertEquals(changed, store.current())

        store.update { it.copy(lastSyncError = null, listenerConnectedAt = null) }
        val after = store.current()
        assertEquals(null, after.lastSyncError)
        assertEquals(null, after.listenerConnectedAt)
        assertEquals(changed.copy(lastSyncError = null, listenerConnectedAt = null), after)
    }
}
