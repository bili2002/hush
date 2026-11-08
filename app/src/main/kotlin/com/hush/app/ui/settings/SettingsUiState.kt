package com.hush.app.ui.settings

import com.hush.app.settings.Settings
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** What the Server card's status line says. Derived from [Settings] by [serverStatus]. */
sealed interface ServerStatus {
    /** URL or token blank: sync is optional, so this is neutral, not an error. */
    data object NotConfigured : ServerStatus
    data object NeverSynced : ServerStatus
    data class Ok(val lastSyncAt: Long) : ServerStatus
    data class Error(val message: String, val lastSyncAt: Long?) : ServerStatus
}

/** What the Listener card shows. Derived by [listenerState]. */
sealed interface ListenerState {
    data class Connected(val at: Long) : ListenerState
    data class Disconnected(val at: Long) : ListenerState
    data object NotEnabled : ListenerState
    /** Access granted but the system has not bound the service yet (or never did). */
    data object EnabledNeverConnected : ListenerState
}

data class SettingsUiState(
    /** True once the URL/token fields were seeded from the store; keeps the fields from flashing empty. */
    val loaded: Boolean = false,
    val serverUrl: String = "",
    val token: String = "",
    val tokenVisible: Boolean = false,
    val serverStatus: ServerStatus = ServerStatus.NotConfigured,
    val unsyncedCount: Long = 0,
    val testing: Boolean = false,
    val testResult: TestResult? = null,
    val listener: ListenerState = ListenerState.NotEnabled,
    val batteryExempt: Boolean = false,
    val holdCount: Int = 0,
    val excludedCount: Int = 0,
    val logOngoing: Boolean = false,
    /** Wall clock at the time this state was built; used to render "2 min ago". */
    val nowMillis: Long = 0,
)

fun serverStatus(s: Settings): ServerStatus = when {
    s.serverUrl.isBlank() || s.token.isBlank() -> ServerStatus.NotConfigured
    s.lastSyncError != null -> ServerStatus.Error(s.lastSyncError, s.lastSyncAt)
    s.lastSyncAt != null -> ServerStatus.Ok(s.lastSyncAt)
    else -> ServerStatus.NeverSynced
}

fun listenerState(enabled: Boolean, connectedAt: Long?, disconnectedAt: Long?): ListenerState = when {
    !enabled -> ListenerState.NotEnabled
    connectedAt != null && (disconnectedAt == null || connectedAt >= disconnectedAt) -> ListenerState.Connected(connectedAt)
    disconnectedAt != null -> ListenerState.Disconnected(disconnectedAt)
    else -> ListenerState.EnabledNeverConnected
}

/** "13:42" in [zone]. */
fun formatClockTime(millis: Long, zone: ZoneId): String =
    Instant.ofEpochMilli(millis).atZone(zone).toLocalTime().format(DateTimeFormatter.ofPattern("HH:mm"))

/** Coarse relative age for "Connected 2 min ago"; the UI picks the string resource. */
sealed interface Age {
    data object JustNow : Age
    data class Minutes(val n: Long) : Age
    data class Hours(val n: Long) : Age
    data class Days(val n: Long) : Age
}

fun relativeAge(then: Long, now: Long): Age {
    val seconds = ((now - then) / 1000).coerceAtLeast(0)
    return when {
        seconds < 60 -> Age.JustNow
        seconds < 3600 -> Age.Minutes(seconds / 60)
        seconds < 86_400 -> Age.Hours(seconds / 3600)
        else -> Age.Days(seconds / 86_400)
    }
}
