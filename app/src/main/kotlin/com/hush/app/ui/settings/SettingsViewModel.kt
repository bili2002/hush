package com.hush.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hush.app.db.NotificationRepository
import com.hush.app.di.ApplicationScope
import com.hush.app.settings.SettingsStore
import com.hush.app.sync.SyncScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Clock
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.launch

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsStore: SettingsStore,
    repository: NotificationRepository,
    private val syncScheduler: SyncScheduler,
    private val connectionTester: ConnectionTester,
    private val probe: SystemStateProbe,
    private val clock: Clock,
    @ApplicationScope private val appScope: CoroutineScope,
) : ViewModel() {

    /** Screen-local state: field edits in flight, toggles, probe results. Everything else comes from the store. */
    private data class Local(
        val loaded: Boolean = false,
        val url: String = "",
        val token: String = "",
        val tokenVisible: Boolean = false,
        val testing: Boolean = false,
        val testResult: TestResult? = null,
        val listenerEnabled: Boolean = false,
        val batteryExempt: Boolean = false,
        /** When the probes last ran; also the "now" for "2 min ago", so a resume re-renders the ages. */
        val probedAt: Long = 0,
    )

    private data class ServerFields(val url: String, val token: String)

    private val local = MutableStateFlow(Local(probedAt = clock.millis()))

    /** Edited URL/token waiting for the 500 ms debounce before they hit the store. */
    private val pendingWrite = MutableStateFlow<ServerFields?>(null)
    @Volatile private var lastWritten: ServerFields? = null

    val state: StateFlow<SettingsUiState> =
        combine(settingsStore.settings, repository.countUnsynced(), local) { s, unsynced, l ->
            SettingsUiState(
                loaded = l.loaded,
                serverUrl = l.url,
                token = l.token,
                tokenVisible = l.tokenVisible,
                serverStatus = serverStatus(s),
                unsyncedCount = unsynced,
                testing = l.testing,
                testResult = l.testResult,
                listener = listenerState(l.listenerEnabled, s.listenerConnectedAt, s.listenerDisconnectedAt),
                batteryExempt = l.batteryExempt,
                holdCount = s.holdPackages.size,
                excludedCount = s.excludedPackages.size,
                logOngoing = s.logOngoing,
                nowMillis = l.probedAt,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

    init {
        viewModelScope.launch {
            val s = settingsStore.current()
            lastWritten = ServerFields(s.serverUrl, s.token)
            local.update { it.copy(loaded = true, url = s.serverUrl, token = s.token) }
        }
        viewModelScope.launch {
            @OptIn(FlowPreview::class)
            pendingWrite.filterNotNull().debounce(DEBOUNCE_MS).collect { write(it) }
        }
    }

    fun onUrlChange(value: String) = editServer { it.copy(url = value) }

    fun onTokenChange(value: String) = editServer { it.copy(token = value) }

    private inline fun editServer(change: (Local) -> Local) {
        pendingWrite.value = local.updateAndGet(change).serverFields()
    }

    /** The one place URL and token are trimmed, so "Test connection" and the saved values are identical. */
    private fun Local.serverFields() = ServerFields(url.trim(), token.trim())

    fun toggleTokenVisible() = local.update { it.copy(tokenVisible = !it.tokenVisible) }

    fun testConnection() {
        val fields = local.value.serverFields()
        viewModelScope.launch {
            local.update { it.copy(testing = true, testResult = null) }
            val result = connectionTester.test(fields.url, fields.token)
            local.update { it.copy(testing = false, testResult = result) }
        }
    }

    fun syncNow() = syncScheduler.syncNow()

    fun setLogOngoing(enabled: Boolean) {
        viewModelScope.launch { settingsStore.update { it.copy(logOngoing = enabled) } }
    }

    /**
     * Called on every `ON_RESUME` (which includes first composition): the user may have just come back from
     * system settings. [Local.probedAt] changes each time, so the state re-emits even when the probes did not.
     */
    fun refreshSystemState() {
        local.update {
            it.copy(listenerEnabled = probe.isListenerEnabled(), batteryExempt = probe.isBatteryExempt(), probedAt = clock.millis())
        }
    }

    private suspend fun write(fields: ServerFields) {
        if (fields == lastWritten) return
        settingsStore.update { it.copy(serverUrl = fields.url, token = fields.token) }
        lastWritten = fields
    }

    /** A pending debounced edit must not be lost when the screen goes away: flush it on the app scope. */
    override fun onCleared() {
        val pending = pendingWrite.value ?: return
        if (pending != lastWritten) appScope.launch { write(pending) }
    }

    private companion object {
        const val DEBOUNCE_MS = 500L
    }
}
