package com.hush.app.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

interface SettingsStore {
    val settings: Flow<Settings>
    suspend fun current(): Settings
    suspend fun update(block: (Settings) -> Settings)
}

data class Settings(
    val serverUrl: String = "",
    val token: String = "",
    val focusEnabled: Boolean = false,
    val slotMinutes: Int = 60,
    val holdPackages: Set<String> = emptySet(),
    val excludedPackages: Set<String> = emptySet(),
    val logOngoing: Boolean = false,
    val listenerConnectedAt: Long? = null,
    val listenerDisconnectedAt: Long? = null,
    val lastSyncAt: Long? = null,
    val lastSyncError: String? = null,
    val lastFocusError: String? = null,
    val setupDone: Boolean = false,
)

/** Preferences-DataStore backed [SettingsStore]. Absent keys read as [Settings] defaults. */
class DataStoreSettingsStore(private val dataStore: DataStore<Preferences>) : SettingsStore {

    override val settings: Flow<Settings> = dataStore.data.map { it.toSettings() }

    override suspend fun current(): Settings = settings.first()

    override suspend fun update(block: (Settings) -> Settings) {
        dataStore.edit { prefs -> prefs.write(block(prefs.toSettings())) }
    }

    private object Keys {
        val serverUrl = stringPreferencesKey("server_url")
        val token = stringPreferencesKey("token")
        val focusEnabled = booleanPreferencesKey("focus_enabled")
        val slotMinutes = intPreferencesKey("slot_minutes")
        val holdPackages = stringSetPreferencesKey("hold_packages")
        val excludedPackages = stringSetPreferencesKey("excluded_packages")
        val logOngoing = booleanPreferencesKey("log_ongoing")
        val listenerConnectedAt = longPreferencesKey("listener_connected_at")
        val listenerDisconnectedAt = longPreferencesKey("listener_disconnected_at")
        val lastSyncAt = longPreferencesKey("last_sync_at")
        val lastSyncError = stringPreferencesKey("last_sync_error")
        val lastFocusError = stringPreferencesKey("last_focus_error")
        val setupDone = booleanPreferencesKey("setup_done")
    }

    private fun Preferences.toSettings(): Settings {
        val d = Settings()
        return Settings(
            serverUrl = this[Keys.serverUrl] ?: d.serverUrl,
            token = this[Keys.token] ?: d.token,
            focusEnabled = this[Keys.focusEnabled] ?: d.focusEnabled,
            slotMinutes = this[Keys.slotMinutes] ?: d.slotMinutes,
            holdPackages = this[Keys.holdPackages] ?: d.holdPackages,
            excludedPackages = this[Keys.excludedPackages] ?: d.excludedPackages,
            logOngoing = this[Keys.logOngoing] ?: d.logOngoing,
            listenerConnectedAt = this[Keys.listenerConnectedAt],
            listenerDisconnectedAt = this[Keys.listenerDisconnectedAt],
            lastSyncAt = this[Keys.lastSyncAt],
            lastSyncError = this[Keys.lastSyncError],
            lastFocusError = this[Keys.lastFocusError],
            setupDone = this[Keys.setupDone] ?: d.setupDone,
        )
    }

    private fun androidx.datastore.preferences.core.MutablePreferences.write(s: Settings) {
        this[Keys.serverUrl] = s.serverUrl
        this[Keys.token] = s.token
        this[Keys.focusEnabled] = s.focusEnabled
        this[Keys.slotMinutes] = s.slotMinutes
        this[Keys.holdPackages] = s.holdPackages
        this[Keys.excludedPackages] = s.excludedPackages
        this[Keys.logOngoing] = s.logOngoing
        setOrRemove(Keys.listenerConnectedAt, s.listenerConnectedAt)
        setOrRemove(Keys.listenerDisconnectedAt, s.listenerDisconnectedAt)
        setOrRemove(Keys.lastSyncAt, s.lastSyncAt)
        setOrRemove(Keys.lastSyncError, s.lastSyncError)
        setOrRemove(Keys.lastFocusError, s.lastFocusError)
        this[Keys.setupDone] = s.setupDone
    }

    private fun <T> androidx.datastore.preferences.core.MutablePreferences.setOrRemove(key: Preferences.Key<T>, value: T?) {
        if (value == null) remove(key) else this[key] = value
    }
}
