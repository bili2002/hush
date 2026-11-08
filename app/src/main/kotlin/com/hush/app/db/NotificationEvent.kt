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
)

enum class EventType { POSTED, REMOVED }

data class AppSummary(val packageName: String, val appLabel: String?, val count: Long, val lastSeen: Long)
