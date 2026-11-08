package com.hush.app.ui.settings

import com.hush.app.settings.Settings
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals

class SettingsStateMappingTest {

    private val configured = Settings(serverUrl = "https://example.invalid:8443", token = "synthetic-token")

    @Test
    fun `blank url or token is NotConfigured regardless of sync history`() {
        assertEquals(ServerStatus.NotConfigured, serverStatus(Settings()))
        assertEquals(ServerStatus.NotConfigured, serverStatus(configured.copy(token = "  ", lastSyncAt = 1L)))
        assertEquals(ServerStatus.NotConfigured, serverStatus(configured.copy(serverUrl = "", lastSyncError = "boom")))
    }

    @Test
    fun `error wins over a previous ok sync and carries its time`() {
        assertEquals(
            ServerStatus.Error("HTTP 503", 42L),
            serverStatus(configured.copy(lastSyncAt = 42L, lastSyncError = "HTTP 503")),
        )
        assertEquals(ServerStatus.Error("HTTP 503", null), serverStatus(configured.copy(lastSyncError = "HTTP 503")))
    }

    @Test
    fun `ok and never-synced`() {
        assertEquals(ServerStatus.Ok(42L), serverStatus(configured.copy(lastSyncAt = 42L)))
        assertEquals(ServerStatus.NeverSynced, serverStatus(configured))
    }

    @Test
    fun `listener state table`() {
        assertEquals(ListenerState.NotEnabled, listenerState(enabled = false, connectedAt = 10L, disconnectedAt = null))
        assertEquals(ListenerState.EnabledNeverConnected, listenerState(enabled = true, connectedAt = null, disconnectedAt = null))
        assertEquals(ListenerState.Connected(10L), listenerState(enabled = true, connectedAt = 10L, disconnectedAt = null))
        assertEquals(ListenerState.Connected(20L), listenerState(enabled = true, connectedAt = 20L, disconnectedAt = 10L))
        assertEquals(ListenerState.Connected(20L), listenerState(enabled = true, connectedAt = 20L, disconnectedAt = 20L))
        assertEquals(ListenerState.Disconnected(30L), listenerState(enabled = true, connectedAt = 20L, disconnectedAt = 30L))
        assertEquals(ListenerState.Disconnected(30L), listenerState(enabled = true, connectedAt = null, disconnectedAt = 30L))
    }

    @Test
    fun `clock time formats in the given zone`() {
        // 2023-11-14T22:13:20Z
        assertEquals("22:13", formatClockTime(1_700_000_000_000L, ZoneId.of("UTC")))
        assertEquals("00:13", formatClockTime(1_700_000_000_000L, ZoneId.of("Europe/Sofia")))
    }

    @Test
    fun `relative age buckets`() {
        val now = 1_700_000_000_000L
        assertEquals(Age.JustNow, relativeAge(now - 59_000, now))
        assertEquals(Age.JustNow, relativeAge(now + 5_000, now)) // clock skew never goes negative
        assertEquals(Age.Minutes(2), relativeAge(now - 2 * 60_000 - 5_000, now))
        assertEquals(Age.Hours(5), relativeAge(now - 5 * 3_600_000L, now))
        assertEquals(Age.Days(2), relativeAge(now - 2 * 86_400_000L, now))
    }
}
