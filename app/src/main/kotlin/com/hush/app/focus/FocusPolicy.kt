package com.hush.app.focus

import android.app.Notification
import com.hush.app.BuildConfig
import com.hush.app.db.EventType
import com.hush.app.db.NotificationEvent
import com.hush.app.settings.Settings

/**
 * Pure hold policy. HOLD iff Focus is on, the app is on the hold list, the event is a fresh post,
 * and the notification is not one of the kinds that must never be held (ongoing, foreground
 * service, group summary, call, alarm, navigation) or from Hush itself.
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
        val hold = settings.focusEnabled &&
            event.packageName in settings.holdPackages &&
            event.event == EventType.POSTED &&
            event.flags and NEVER_HELD_FLAGS == 0 &&
            event.category !in neverHeldCategories &&
            event.packageName != BuildConfig.APPLICATION_ID
        return if (hold) Decision.HOLD else Decision.PASS_THROUGH
    }
}
