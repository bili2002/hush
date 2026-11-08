package com.hush.app.listener

import android.app.Notification
import android.content.Context
import android.content.pm.PackageManager
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.core.app.NotificationCompat
import com.hush.app.db.EventType
import com.hush.app.db.NotificationEvent
import com.hush.app.settings.Settings
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Turns a [StatusBarNotification] into a [NotificationEvent]. Pure apart from the app-label lookup,
 * which goes through [PackageManager] once per package and is cached.
 *
 * Returns null when one of the [NoiseFilters] fires; the caller then records nothing.
 */
@Singleton
class NotificationMapper @Inject constructor(@ApplicationContext context: Context) {

    private val packageManager: PackageManager = context.packageManager
    private val ownPackage: String = context.packageName
    private val labels = ConcurrentHashMap<String, String>()

    /** A POSTED event with `held = false`; the ingest flips `held` after the gate decides. */
    fun map(sbn: StatusBarNotification, importance: Int?, settings: Settings): NotificationEvent? {
        if (NoiseFilters.reason(sbn, settings, ownPackage) != null) return null
        val n = sbn.notification
        val extras = n.extras
        val textLines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
            ?.filterNotNull()
            ?.joinToString("\n") { it.toString() }
            ?.ifEmpty { null }
        val bigText = listOfNotNull(
            extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString(),
            textLines,
            messagingLines(n),
        ).joinToString("\n").ifEmpty { null }?.take(MAX_BIG_TEXT_CHARS)

        return base(sbn, EventType.POSTED).copy(
            title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString(),
            text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString(),
            bigText = bigText,
            subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString(),
            importance = importance,
        )
    }

    /** A REMOVED row: package, key, times, reason. No text, so a removal never re-archives content. */
    fun mapRemoved(sbn: StatusBarNotification, reason: Int, settings: Settings): NotificationEvent? {
        if (NoiseFilters.reason(sbn, settings, ownPackage) != null) return null
        return base(sbn, EventType.REMOVED).copy(removedReason = reason)
    }

    /** The text-free skeleton shared by both event types: identity, package, times, channel, category, flags. */
    private fun base(sbn: StatusBarNotification, event: EventType): NotificationEvent {
        val n = sbn.notification
        return NotificationEvent(
            id = eventId(sbn.key, sbn.postTime, event),
            key = sbn.key,
            packageName = sbn.packageName,
            appLabel = appLabel(sbn.packageName),
            postTime = sbn.postTime,
            title = null,
            text = null,
            bigText = null,
            subText = null,
            channelId = n.channelId,
            category = n.category,
            flags = n.flags,
            importance = null,
            event = event,
            removedReason = null,
            held = false,
        )
    }

    /** `messages + historicMessages` as "sender: text" lines, or null when the notification has no MessagingStyle. */
    private fun messagingLines(n: Notification): String? {
        val style = try {
            NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(n)
        } catch (e: RuntimeException) {
            // Malformed extras from a third-party app must never cost us the row.
            Log.w(TAG, "MessagingStyle extraction failed: ${e.javaClass.simpleName}")
            null
        } ?: return null
        val lines = (style.messages + style.historicMessages).map { m ->
            val sender = m.person?.name?.toString() ?: "?"
            "$sender: ${m.text?.toString().orEmpty()}"
        }
        return lines.joinToString("\n").ifEmpty { null }
    }

    /** Cached per package on success only; a lookup failure falls back to the package name and is retried next time. */
    private fun appLabel(packageName: String): String {
        labels[packageName]?.let { return it }
        return try {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(packageName, 0)).toString()
                .also { labels[packageName] = it }
        } catch (e: PackageManager.NameNotFoundException) {
            packageName
        } catch (e: RuntimeException) {
            // PackageManager died / binder hiccup: the row still gets written, with the package as its label.
            Log.w(TAG, "label lookup failed for $packageName: ${e.javaClass.simpleName}")
            packageName
        }
    }

    companion object {
        private const val TAG = "HushMapper"
        const val MAX_BIG_TEXT_CHARS = 4 * 1024

        /** Deterministic per (key, postTime, event type): replays, backfill and server re-push are idempotent. */
        fun eventId(key: String, postTime: Long, event: EventType): String =
            UUID.nameUUIDFromBytes("$key|$postTime|${event.name}".toByteArray()).toString()
    }
}
