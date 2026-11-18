package com.hush.app.db

data class NotificationEvent(
    val id: String,
    val key: String,
    val packageName: String,
    val appLabel: String?,
    val postTime: Long,
    val title: String?,
    val text: String?,
    val bigText: String?,
    val subText: String?,
    val channelId: String?,
    val category: String?,
    val flags: Int,
    val importance: Int?,
    val event: EventType,
    val removedReason: Int?,
    val held: Boolean,
    // Stamps are set by markDelivered / markSynced, never by insert; defaults keep construction sites short.
    val deliveredAt: Long? = null,
    val syncedAt: Long? = null,
    /** Stable id of the conversation (shortcut id, or the MessagingStyle title); null when the notification is not one. */
    val conversationId: String? = null,
    /** Display name of that conversation at post time. */
    val conversationTitle: String? = null,
) {
    /**
     * What "the same chat" means everywhere (History filter, chat list): the
     * conversation id, else the title. Mirrors `COALESCE(conversation_id, title)` in Notifications.sq,
     * so rows captured before conversations were recorded still group by title.
     */
    val chatKey: String? get() = (conversationId ?: title)?.takeIf { it.isNotEmpty() }

    /** Hidden by a "Muted" rule: it was never in a digest and never will be. */
    val muted: Boolean get() = held && deliveredAt == MUTED_AT

    companion object {
        /**
         * The `deliveredAt` of a muted row. A muted row is finished the moment it is recorded, so it
         * carries a delivery stamp like any other released row (nothing treats it as still held), and
         * this value, which no real delivery can have, tells it apart from one that was in a digest.
         */
        const val MUTED_AT = 0L
    }
}

enum class EventType { POSTED, REMOVED }

data class AppSummary(val packageName: String, val appLabel: String?, val count: Long, val lastSeen: Long)

/** One chat of one app: [key] is the [NotificationEvent.chatKey], [title] its newest display name. */
data class ChatSummary(val key: String, val title: String, val count: Long, val lastSeen: Long)
