package com.hush.app.settings

import android.util.Log
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
import com.hush.app.focus.FocusProfiles
import com.hush.app.focus.INSTANT
import com.hush.app.focus.FocusRules
import kotlinx.coroutines.flow.map
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json

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
    /**
     * Categories, per-category intervals and chat rules; see `focus/FocusRules.kt`. Together with
     * [slotMinutes] this is the configuration of the active Focus profile.
     */
    val rules: FocusRules = FocusRules(),
    /** The other profiles, the schedule and their bookkeeping; see `focus/FocusProfiles.kt`. */
    val profiles: FocusProfiles = FocusProfiles(),
)

/** Preferences-DataStore backed [SettingsStore]. Absent keys read as [Settings] defaults. */
class DataStoreSettingsStore(private val dataStore: DataStore<Preferences>) : SettingsStore {

    override val settings: Flow<Settings> = dataStore.data.map { it.toSettings() }

    override suspend fun current(): Settings = settings.first()

    override suspend fun update(block: (Settings) -> Settings) {
        dataStore.edit { prefs ->
            val before = prefs.toSettings()
            val after = block(before)
            // The rules and profiles blobs are rewritten only by an edit of theirs. Every other write (listener and sync stamps
            // land here all day) leaves it alone, so a blob this build could not read is not silently
            // replaced by "no rules" and is still there for a build that can read it.
            prefs.write(after, writeRules = after.rules != before.rules, writeProfiles = after.profiles != before.profiles)
        }
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
        val rules = stringPreferencesKey("focus_rules")
        val profiles = stringPreferencesKey("focus_profiles")
    }

    /** A blob this build cannot read (corrupt, or a shape from a newer build) means "no rules", never a crash. */
    private fun decodeRules(raw: String?): FocusRules = decode(raw, FocusRules.serializer(), "focus rules") ?: FocusRules()

    /** Likewise: unreadable profiles mean the live settings are the only profile. */
    private fun decodeProfiles(raw: String?): FocusProfiles = decode(raw, FocusProfiles.serializer(), "focus profiles") ?: FocusProfiles()

    private fun <T> decode(raw: String?, serializer: KSerializer<T>, what: String): T? {
        raw ?: return null
        return try {
            json.decodeFromString(serializer, raw)
        } catch (e: IllegalArgumentException) { // SerializationException is one
            Log.w(TAG, "$what unreadable, falling back to none: ${e.javaClass.simpleName}")
            null
        }
    }

    private fun Preferences.toSettings(): Settings {
        val d = Settings()
        return Settings(
            serverUrl = this[Keys.serverUrl] ?: d.serverUrl,
            token = this[Keys.token] ?: d.token,
            focusEnabled = this[Keys.focusEnabled] ?: d.focusEnabled,
            // Never below "Instantly": a negative default would read as "Muted" for every held app without a group.
            slotMinutes = (this[Keys.slotMinutes] ?: d.slotMinutes).coerceAtLeast(INSTANT),
            holdPackages = this[Keys.holdPackages] ?: d.holdPackages,
            excludedPackages = this[Keys.excludedPackages] ?: d.excludedPackages,
            logOngoing = this[Keys.logOngoing] ?: d.logOngoing,
            listenerConnectedAt = this[Keys.listenerConnectedAt],
            listenerDisconnectedAt = this[Keys.listenerDisconnectedAt],
            lastSyncAt = this[Keys.lastSyncAt],
            lastSyncError = this[Keys.lastSyncError],
            lastFocusError = this[Keys.lastFocusError],
            setupDone = this[Keys.setupDone] ?: d.setupDone,
            rules = decodeRules(this[Keys.rules]),
            profiles = decodeProfiles(this[Keys.profiles]),
        )
    }

    private fun androidx.datastore.preferences.core.MutablePreferences.write(s: Settings, writeRules: Boolean, writeProfiles: Boolean) {
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
        if (writeRules) this[Keys.rules] = json.encodeToString(FocusRules.serializer(), s.rules)
        if (writeProfiles) this[Keys.profiles] = json.encodeToString(FocusProfiles.serializer(), s.profiles)
    }

    private fun <T> androidx.datastore.preferences.core.MutablePreferences.setOrRemove(key: Preferences.Key<T>, value: T?) {
        if (value == null) remove(key) else this[key] = value
    }

    private companion object {
        const val TAG = "HushSettings"
        val json = Json { ignoreUnknownKeys = true }
    }
}
