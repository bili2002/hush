package com.hush.server

import kotlinx.serialization.Serializable

/**
 * One notification event as the phone pushes it. Field names and nullability are IDENTICAL to the
 * app's `NotificationDto` (issue #5 pins the wire JSON); change both together or nothing syncs.
 */
@Serializable
data class NotificationDto(
    val id: String,
    val key: String,
    val packageName: String,
    val appLabel: String? = null,
    val postTime: Long,
    val title: String? = null,
    val text: String? = null,
    val bigText: String? = null,
    val subText: String? = null,
    val channelId: String? = null,
    val category: String? = null,
    val flags: Int,
    val importance: Int? = null,
    val event: String,
    val removedReason: Int? = null,
)

@Serializable
data class PushRequest(val device: String, val items: List<NotificationDto>)

@Serializable
data class PushResponse(val accepted: Int, val duplicates: Int, val serverTime: Long)

/** A stored row as `GET /notifications` returns it: the pushed fields plus the pushing device. */
@Serializable
data class ArchiveItem(
    val id: String,
    val device: String,
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
    val event: String,
    val removedReason: Int?,
)

/** [nextBefore] is the cursor for the next page, or null when this page was the last. */
@Serializable
data class PageResponse(val items: List<ArchiveItem>, val nextBefore: Long?)

@Serializable
data class AppSummary(val packageName: String, val appLabel: String?, val count: Long, val lastSeen: Long)

@Serializable
data class Health(val ok: Boolean, val rows: Long, val service: String = "hush-server")

@Serializable
data class ErrorBody(val error: String)
