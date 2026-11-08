package com.hush.app.focus

import android.app.Notification
import com.hush.app.BuildConfig
import com.hush.app.db.EventType
import com.hush.app.db.NotificationEvent
import com.hush.app.settings.Settings
import com.hush.app.testing.FakeSettingsStore
import com.hush.app.testing.event
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest

class FocusPolicyTest {
    private val focusOn = Settings(focusEnabled = true, holdPackages = setOf("com.example.alpha", "com.example.beta"))
    private val base = event("e1", packageName = "com.example.alpha")

    private data class Case(val name: String, val event: NotificationEvent, val settings: Settings, val expected: Decision)

    private val table = listOf(
        Case("held app, plain post, focus on", base, focusOn, Decision.HOLD),
        Case("second held app", base.copy(packageName = "com.example.beta"), focusOn, Decision.HOLD),
        Case("focus off", base, focusOn.copy(focusEnabled = false), Decision.PASS_THROUGH),
        Case("app not on hold list", base.copy(packageName = "com.example.gamma"), focusOn, Decision.PASS_THROUGH),
        Case("empty hold list", base, focusOn.copy(holdPackages = emptySet()), Decision.PASS_THROUGH),
        Case("removal event", base.copy(event = EventType.REMOVED), focusOn, Decision.PASS_THROUGH),
        Case("ongoing", base.copy(flags = Notification.FLAG_ONGOING_EVENT), focusOn, Decision.PASS_THROUGH),
        Case("foreground service", base.copy(flags = Notification.FLAG_FOREGROUND_SERVICE), focusOn, Decision.PASS_THROUGH),
        Case("group summary", base.copy(flags = Notification.FLAG_GROUP_SUMMARY), focusOn, Decision.PASS_THROUGH),
        Case("ongoing mixed with harmless flags", base.copy(flags = Notification.FLAG_AUTO_CANCEL or Notification.FLAG_ONGOING_EVENT), focusOn, Decision.PASS_THROUGH),
        Case("harmless flags only", base.copy(flags = Notification.FLAG_AUTO_CANCEL or Notification.FLAG_SHOW_LIGHTS), focusOn, Decision.HOLD),
        Case("call", base.copy(category = Notification.CATEGORY_CALL), focusOn, Decision.PASS_THROUGH),
        Case("alarm", base.copy(category = Notification.CATEGORY_ALARM), focusOn, Decision.PASS_THROUGH),
        Case("navigation", base.copy(category = Notification.CATEGORY_NAVIGATION), focusOn, Decision.PASS_THROUGH),
        Case("message category is holdable", base.copy(category = Notification.CATEGORY_MESSAGE), focusOn, Decision.HOLD),
        Case("hush itself, even on the hold list", base.copy(packageName = BuildConfig.APPLICATION_ID), focusOn.copy(holdPackages = setOf(BuildConfig.APPLICATION_ID)), Decision.PASS_THROUGH),
    )

    @Test
    fun table() {
        for (c in table) assertEquals(c.expected, FocusPolicy.decide(c.event, c.settings), c.name)
    }

    @Test
    fun holdGateReadsCurrentSettings() = runTest {
        val store = FakeSettingsStore(focusOn)
        val gate = HoldGate(store)
        assertEquals(Decision.HOLD, gate.decide(base))
        store.update { it.copy(focusEnabled = false) }
        assertEquals(Decision.PASS_THROUGH, gate.decide(base))
    }
}
