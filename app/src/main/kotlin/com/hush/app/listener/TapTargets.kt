package com.hush.app.listener

import android.app.PendingIntent
import android.util.LruCache
import com.hush.app.db.NotificationEvent
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The original tap targets (`contentIntent`) of recently seen notifications, so a History row can
 * open the very chat it came from. Memory only: a [PendingIntent] is a live system token that
 * cannot be written to the database, so after the Hush process restarts older rows fall back to
 * opening the app. Bounded; never persisted, never synced.
 */
@Singleton
class TapTargets @Inject constructor() {
    private val byEvent = LruCache<String, PendingIntent>(MAX_EVENTS)
    private val byChat = LruCache<String, PendingIntent>(MAX_CHATS)

    fun remember(event: NotificationEvent, intent: PendingIntent?) {
        intent ?: return
        byEvent.put(event.id, intent)
        chatSlot(event)?.let { byChat.put(it, intent) }
    }

    /** The row's own target, else the newest target seen for the same conversation of the same app. */
    fun find(event: NotificationEvent): PendingIntent? =
        byEvent.get(event.id) ?: chatSlot(event)?.let(byChat::get)

    /** Drops a target the system reported as cancelled, wherever it is referenced for [event]. */
    fun forget(event: NotificationEvent, intent: PendingIntent) {
        if (byEvent.get(event.id) == intent) byEvent.remove(event.id)
        chatSlot(event)?.let { if (byChat.get(it) == intent) byChat.remove(it) }
    }

    // Only a real conversation id shares targets: two unrelated notifications with the same title must not.
    private fun chatSlot(event: NotificationEvent): String? =
        event.conversationId?.let { "${event.packageName}\u0000$it" }

    private companion object {
        const val MAX_EVENTS = 400
        const val MAX_CHATS = 200
    }
}
