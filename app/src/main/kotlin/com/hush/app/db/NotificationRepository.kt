package com.hush.app.db

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.coroutines.mapToOne
import java.time.Clock
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/** Single access point to the local archive. Suspend funs run on [Dispatchers.IO]; Flows emit on it. */
@Singleton
class NotificationRepository @Inject constructor(
    private val db: HushDatabase,
    private val clock: Clock,
) {
    private val q get() = db.notificationsQueries

    suspend fun insert(e: NotificationEvent) = withContext(Dispatchers.IO) {
        q.insert(
            id = e.id,
            key = e.key,
            package_name = e.packageName,
            app_label = e.appLabel,
            post_time = e.postTime,
            title = e.title,
            text = e.text,
            big_text = e.bigText,
            sub_text = e.subText,
            channel_id = e.channelId,
            category = e.category,
            flags = e.flags.toLong(),
            importance = e.importance?.toLong(),
            event = e.event.name,
            removed_reason = e.removedReason?.toLong(),
            held = e.held,
            created_at = clock.millis(),
        )
    }

    suspend fun selectHeldUndelivered(): List<NotificationEvent> = withContext(Dispatchers.IO) {
        q.selectHeldUndelivered().executeAsList().map { it.toEvent() }
    }

    suspend fun markDelivered(ids: List<String>, deliveredAt: Long) = withContext(Dispatchers.IO) {
        if (ids.isNotEmpty()) q.markDelivered(ts = deliveredAt, ids = ids)
    }

    suspend fun selectHeldBatch(deliveredAt: Long): List<NotificationEvent> = withContext(Dispatchers.IO) {
        q.selectHeldBatch(deliveredAt).executeAsList().map { it.toEvent() }
    }

    fun countHeld(): Flow<Long> = q.countHeld().asFlow().mapToOne(Dispatchers.IO)

    fun countUnsynced(): Flow<Long> = q.countUnsynced().asFlow().mapToOne(Dispatchers.IO)

    suspend fun selectUnsynced(limit: Int): List<NotificationEvent> = withContext(Dispatchers.IO) {
        q.selectUnsynced(limit.toLong()).executeAsList().map { it.toEvent() }
    }

    suspend fun markSynced(ids: List<String>, syncedAt: Long) = withContext(Dispatchers.IO) {
        if (ids.isNotEmpty()) q.markSynced(ts = syncedAt, ids = ids)
    }

    /** Deletes synced rows older than [ts] that are not held-and-undelivered. Returns the number deleted. */
    suspend fun deleteSyncedOlderThan(ts: Long): Int = withContext(Dispatchers.IO) {
        q.transactionWithResult {
            q.deleteSyncedOlderThan(ts)
            q.changes().executeAsOne().toInt()
        }
    }

    fun selectRecent(limit: Int, before: Long): Flow<List<NotificationEvent>> =
        q.selectRecent(before = before, limit = limit.toLong()).asFlow().mapToList(Dispatchers.IO)
            .map { rows -> rows.map { it.toEvent() } }

    fun search(q: String, packageName: String?, limit: Int): Flow<List<NotificationEvent>> =
        this.q.search(q = q, packageName = packageName, limit = limit.toLong()).asFlow().mapToList(Dispatchers.IO)
            .map { rows -> rows.map { it.toEvent() } }

    fun selectApps(): Flow<List<AppSummary>> =
        q.selectApps().asFlow().mapToList(Dispatchers.IO).map { rows ->
            rows.map { AppSummary(it.package_name, it.app_label, it.count, it.last_seen ?: 0L) }
        }

    private fun Notification_event.toEvent() = NotificationEvent(
        id = id,
        key = key,
        packageName = package_name,
        appLabel = app_label,
        postTime = post_time,
        title = title,
        text = text,
        bigText = big_text,
        subText = sub_text,
        channelId = channel_id,
        category = category,
        flags = flags.toInt(),
        importance = importance?.toInt(),
        event = EventType.valueOf(event),
        removedReason = removed_reason?.toInt(),
        held = held,
    )
}
