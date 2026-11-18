package com.hush.app.focus

import android.app.Notification
import com.hush.app.BuildConfig
import com.hush.app.db.EventType
import com.hush.app.db.NotificationEvent
import com.hush.app.settings.Settings

/**
 * Pure hold policy. A notification is hidden iff Focus is on, the rules give it something other
 * than [INSTANT] (its chat rule, else its held app's category, else the default; see
 * [throttleMinutes]), the event is a fresh post, and the notification is not one of the kinds that
 * must never be hidden (ongoing, foreground service, group summary, call, alarm, navigation) or
 * from Hush itself. Hidden means HOLD for an interval and MUTE for [MUTED].
 */
object FocusPolicy {
    private val neverHeldCategories = setOf(
        Notification.CATEGORY_CALL,
        Notification.CATEGORY_ALARM,
        Notification.CATEGORY_NAVIGATION,
    )

    private const val NEVER_HELD_FLAGS =
        Notification.FLAG_ONGOING_EVENT or Notification.FLAG_FOREGROUND_SERVICE or Notification.FLAG_GROUP_SUMMARY

    fun decide(event: NotificationEvent, settings: Settings): Decision {
        val minutes = settings.throttleMinutes(event.packageName, event.chatKey)
        val hide = settings.focusEnabled &&
            minutes != INSTANT &&
            event.event == EventType.POSTED &&
            event.flags and NEVER_HELD_FLAGS == 0 &&
            event.category !in neverHeldCategories &&
            event.packageName != BuildConfig.APPLICATION_ID
        return when {
            !hide -> Decision.PASS_THROUGH
            minutes == MUTED -> Decision.MUTE
            else -> Decision.HOLD
        }
    }
}
