package com.hush.app.sync

import com.hush.app.db.NotificationEvent
import kotlinx.serialization.Serializable

/**
 * Wire shape of one notification event, identical to the server's `POST /notifications` item
 * (issue #8). No field has a default so every field, including nulls, is always encoded.
 */
@Serializable
data class NotificationDto(
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
    /** `"posted"` or `"removed"`. */
    val event: String,
    val removedReason: Int?,
)

@Serializable
data class PushRequest(val device: String, val items: List<NotificationDto>)

@Serializable
data class PushResponse(val accepted: Int, val duplicates: Int, val serverTime: Long)

fun NotificationEvent.toDto(): NotificationDto = NotificationDto(
    id = id,
    key = key,
    packageName = packageName,
    appLabel = appLabel,
    postTime = postTime,
    title = title,
    text = text,
    bigText = bigText,
    subText = subText,
    channelId = channelId,
    category = category,
    flags = flags,
    importance = importance,
    event = event.name.lowercase(),
    removedReason = removedReason,
)
