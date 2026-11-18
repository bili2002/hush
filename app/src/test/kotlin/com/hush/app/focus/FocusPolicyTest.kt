package com.hush.app.focus

import android.app.Notification
import com.hush.app.BuildConfig
import com.hush.app.db.EventType
import com.hush.app.db.NotificationEvent
import com.hush.app.settings.Settings
import com.hush.app.testing.FakeSettingsStore
import com.hush.app.testing.event
import com.hush.app.testing.fixedClock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest

class FocusPolicyTest {
    private val focusOn = Settings(focusEnabled = true, holdPackages = setOf("com.example.alpha", "com.example.beta"))
    private val base = event("e1", packageName = "com.example.alpha")

    private val chat = base.copy(conversationId = "c1", conversationTitle = "Group one")

    private fun grouped(minutes: Int?) = focusOn.copy(
        rules = FocusRules(categories = listOf(FocusCategory("g", "Group", minutes)), appCategory = mapOf("com.example.alpha" to "g")),
    )

    private fun chatRule(packageName: String, minutes: Int) =
        focusOn.copy(rules = FocusRules(chatRules = listOf(ChatRule(packageName, "c1", "Group one", minutes))))

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
        Case("held app in an instant group", base, grouped(INSTANT), Decision.PASS_THROUGH),
        Case("held app in a group with its own interval", base, grouped(15), Decision.HOLD),
        Case("held app in a group that follows the default", base, grouped(null), Decision.HOLD),
        Case("chat rule holds a chat of an app that is not held", chat.copy(packageName = "com.example.gamma"), chatRule("com.example.gamma", 60), Decision.HOLD),
        Case("another chat of that app is not held", chat.copy(packageName = "com.example.gamma", conversationId = "other"), chatRule("com.example.gamma", 60), Decision.PASS_THROUGH),
        Case("instant chat rule lets one chat of a held app through", chat, chatRule("com.example.alpha", INSTANT), Decision.PASS_THROUGH),
        Case("the rest of that held app is still held", chat.copy(conversationId = "other"), chatRule("com.example.alpha", INSTANT), Decision.HOLD),
        Case("chat rule keyed by title when there is no conversation id", base.copy(title = "c1"), chatRule("com.example.alpha", INSTANT), Decision.PASS_THROUGH),
        Case("a call in a ruled chat is still never held", chat.copy(packageName = "com.example.gamma", category = Notification.CATEGORY_CALL), chatRule("com.example.gamma", 60), Decision.PASS_THROUGH),
        Case("chat rule does nothing while focus is off", chat.copy(packageName = "com.example.gamma"), chatRule("com.example.gamma", 60).copy(focusEnabled = false), Decision.PASS_THROUGH),
        Case("held app in a muted group", base, grouped(MUTED), Decision.MUTE),
        Case("muted chat of an app that is not held", chat.copy(packageName = "com.example.gamma"), chatRule("com.example.gamma", MUTED), Decision.MUTE),
        Case("muted chat of a held app, the rest is held", chat.copy(conversationId = "other"), chatRule("com.example.alpha", MUTED), Decision.HOLD),
        Case("a call in a muted group is never muted", base.copy(category = Notification.CATEGORY_CALL), grouped(MUTED), Decision.PASS_THROUGH),
        Case("an ongoing notification in a muted group is never muted", base.copy(flags = Notification.FLAG_ONGOING_EVENT), grouped(MUTED), Decision.PASS_THROUGH),
        Case("a removal in a muted group is recorded as usual", base.copy(event = EventType.REMOVED), grouped(MUTED), Decision.PASS_THROUGH),
        Case("muted group while focus is off", base, grouped(MUTED).copy(focusEnabled = false), Decision.PASS_THROUGH),
        Case("hush itself, even on the hold list", base.copy(packageName = BuildConfig.APPLICATION_ID), focusOn.copy(holdPackages = setOf(BuildConfig.APPLICATION_ID)), Decision.PASS_THROUGH),
    )

    @Test
    fun table() {
        for (c in table) assertEquals(c.expected, FocusPolicy.decide(c.event, c.settings), c.name)
    }

    @Test
    fun `the gate applies the schedule as of now, without waiting for the alarm to write it down`() = runTest {
        // 2023-11-14 is a Tuesday; the clock says 22:13 UTC.
        val clock = fixedClock(1_700_000_000_000L)
        val zone = java.time.ZoneId.systemDefault()
        val minuteOfDay = java.time.Instant.ofEpochMilli(clock.millis()).atZone(zone).let { it.hour * 60 + it.minute }
        val today = java.time.Instant.ofEpochMilli(clock.millis()).atZone(zone).dayOfWeek.value
        val running = ScheduleEntry("e", DEFAULT_PROFILE_ID, setOf(today), minuteOfDay, (minuteOfDay + 30) % (24 * 60))

        // Stored as off, but an entry started this very minute: held.
        val startedJustNow = FakeSettingsStore(focusOn.copy(focusEnabled = false).withScheduleEntry(running))
        assertEquals(Decision.HOLD, HoldGate(startedJustNow, clock).decide(base))
        assertEquals(false, startedJustNow.state.value.focusEnabled, "the gate decides, it does not write")

        // Stored as on, but the schedule's only entry is on another day: not held.
        val over = FakeSettingsStore(focusOn.withScheduleEntry(running.copy(days = setOf(today % 7 + 1), start = 0, end = 1)))
        assertEquals(Decision.PASS_THROUGH, HoldGate(over, clock).decide(base))
    }

    @Test
    fun holdGateReadsCurrentSettings() = runTest {
        val store = FakeSettingsStore(focusOn)
        val gate = HoldGate(store, fixedClock(1_700_000_000_000L))
        assertEquals(Decision.HOLD, gate.decide(base))
        store.update { it.copy(focusEnabled = false) }
        assertEquals(Decision.PASS_THROUGH, gate.decide(base))
    }
}
