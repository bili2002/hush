package com.hush.app.listener

import android.app.Notification
import android.service.notification.StatusBarNotification
import com.hush.app.settings.Settings

/**
 * Pure predicates that decide whether a notification is noise the ledger should not record.
 * Each one is tested on its own; [reason] composes them in a fixed order.
 */
object NoiseFilters {

    enum class Reason { OWN_PACKAGE, GROUP_SUMMARY, ONGOING, EXCLUDED }

    fun isOwnPackage(sbn: StatusBarNotification, ownPackage: String): Boolean = sbn.packageName == ownPackage

    fun isGroupSummary(sbn: StatusBarNotification): Boolean =
        sbn.notification.flags and Notification.FLAG_GROUP_SUMMARY != 0

    /** Ongoing / foreground-service notifications (progress bars, media players) unless the owner opted in. */
    fun isOngoing(sbn: StatusBarNotification, settings: Settings): Boolean {
        if (settings.logOngoing) return false
        val mask = Notification.FLAG_ONGOING_EVENT or Notification.FLAG_FOREGROUND_SERVICE
        return sbn.notification.flags and mask != 0
    }

    fun isExcluded(sbn: StatusBarNotification, settings: Settings): Boolean =
        sbn.packageName in settings.excludedPackages

    /** The first matching reason, or null when the notification should be recorded. */
    fun reason(sbn: StatusBarNotification, settings: Settings, ownPackage: String): Reason? = when {
        isOwnPackage(sbn, ownPackage) -> Reason.OWN_PACKAGE
        isGroupSummary(sbn) -> Reason.GROUP_SUMMARY
        isOngoing(sbn, settings) -> Reason.ONGOING
        isExcluded(sbn, settings) -> Reason.EXCLUDED
        else -> null
    }
}
