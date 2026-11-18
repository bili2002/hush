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
    private val queries get() = db.notificationsQueries

    /**
     * Stamps are never taken from [e]. [muted] records the row as hidden-for-good in the same
     * statement ([NotificationEvent.MUTED_AT]), so there is no moment in which it looks held and a
     * delivery run could pick it up.
     */
    suspend fun insert(e: NotificationEvent, muted: Boolean = false) = withContext(Dispatchers.IO) {
        queries.insert(
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
            delivered_at = if (muted) NotificationEvent.MUTED_AT else null,
            created_at = clock.millis(),
            conversation_id = e.conversationId,
            conversation_title = e.conversationTitle,
        )
    }

    suspend fun selectHeldUndelivered(): List<NotificationEvent> = withContext(Dispatchers.IO) {
        queries.selectHeldUndelivered().executeAsList().map { it.toEvent() }
    }

    suspend fun markDelivered(ids: List<String>, deliveredAt: Long) = withContext(Dispatchers.IO) {
        chunked(ids) { queries.markDelivered(ts = deliveredAt, ids = it) }
    }

    /** The rows that are muted right now, oldest first. */
    suspend fun selectMuted(): List<NotificationEvent> = withContext(Dispatchers.IO) {
        queries.selectMuted().executeAsList().map { it.toEvent() }
    }

    fun countMuted(): Flow<Long> = queries.countMuted().asFlow().mapToOne(Dispatchers.IO)

    /** Makes muted rows ordinary held rows again, to be delivered by the normal due logic. Rows that are not muted are left alone. */
    suspend fun unmute(ids: List<String>) = withContext(Dispatchers.IO) {
        chunked(ids) { queries.unmute(it) }
    }

    /** Held rows delivered at or after [since], oldest first: what the digest carrying that `since` covers. */
    suspend fun selectHeldBatch(since: Long): List<NotificationEvent> = withContext(Dispatchers.IO) {
        queries.selectHeldBatch(since).executeAsList().map { it.toEvent() }
    }

    /** Live view of the rows currently held back, oldest first. */
    fun heldUndelivered(): Flow<List<NotificationEvent>> =
        queries.selectHeldUndelivered().asFlow().mapToList(Dispatchers.IO).map { rows -> rows.map { it.toEvent() } }

    fun countUnsynced(): Flow<Long> = queries.countUnsynced().asFlow().mapToOne(Dispatchers.IO)

    suspend fun selectUnsynced(limit: Int): List<NotificationEvent> = withContext(Dispatchers.IO) {
        queries.selectUnsynced(limit.toLong()).executeAsList().map { it.toEvent() }
    }

    suspend fun markSynced(ids: List<String>, syncedAt: Long) = withContext(Dispatchers.IO) {
        chunked(ids) { queries.markSynced(ts = syncedAt, ids = it) }
    }

    /** Deletes synced rows older than [ts] that are not held-and-undelivered. Returns the number deleted. */
    suspend fun deleteSyncedOlderThan(ts: Long): Int = withContext(Dispatchers.IO) {
        queries.transactionWithResult {
            queries.deleteSyncedOlderThan(ts)
            queries.changes().executeAsOne().toInt()
        }
    }

    fun selectRecent(limit: Int, before: Long): Flow<List<NotificationEvent>> =
        queries.selectRecent(before = before, limit = limit.toLong()).asFlow().mapToList(Dispatchers.IO)
            .map { rows -> rows.map { it.toEvent() } }

    /**
     * The History list: POSTED rows, newest first. [q] is a substring matched over title, text,
     * big_text and the conversation title (`%`, `_` and `\` are literal; empty = no search);
     * [packageName] and [chat] (a [NotificationEvent.chatKey]) narrow it further when non-null, and
     * [mutedOnly] to the rows that are muted right now.
     */
    fun selectHistory(q: String, packageName: String?, chat: String?, limit: Int, mutedOnly: Boolean = false): Flow<List<NotificationEvent>> =
        queries.selectHistory(mutedOnly = mutedOnly.toLong(), packageName = packageName, chat = chat, q = escapeLike(q), limit = limit.toLong())
            .asFlow().mapToList(Dispatchers.IO)
            .map { rows -> rows.map { it.toEvent() } }

    /** How many rows [selectHistory] would return without its limit. */
    fun countHistory(q: String, packageName: String?, chat: String?, mutedOnly: Boolean = false): Flow<Long> =
        queries.countHistory(mutedOnly = mutedOnly.toLong(), packageName = packageName, chat = chat, q = escapeLike(q))
            .asFlow().mapToOne(Dispatchers.IO)

    private fun Boolean.toLong(): Long = if (this) 1L else 0L

    /** The chats of one app, most recent first; the title is the newest name the chat was seen under. */
    fun selectChats(packageName: String): Flow<List<ChatSummary>> =
        queries.selectChats(packageName).asFlow().mapToList(Dispatchers.IO).map { rows ->
            rows.mapNotNull { row ->
                val key = row.chat_key ?: return@mapNotNull null
                val title = row.conversation_title?.takeIf { it.isNotBlank() } ?: row.title?.takeIf { it.isNotBlank() } ?: key
                ChatSummary(key, title, row.count, row.last_seen ?: 0L)
            }
        }

    fun selectApps(): Flow<List<AppSummary>> =
        queries.selectApps().asFlow().mapToList(Dispatchers.IO).map { rows ->
            rows.map { AppSummary(it.package_name, it.app_label, it.count, it.last_seen ?: 0L) }
        }

    /**
     * Runs [block] over [ids] in chunks inside one transaction. SQLite on Android < 31 caps a
     * statement at 999 bound variables, so an `IN ?` list must never be passed whole.
     */
    private fun chunked(ids: List<String>, block: (List<String>) -> Unit) {
        if (ids.isEmpty()) return
        queries.transaction { ids.chunked(IN_CHUNK).forEach(block) }
    }

    private fun escapeLike(q: String): String =
        q.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

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
        deliveredAt = delivered_at,
        syncedAt = synced_at,
        conversationId = conversation_id,
        conversationTitle = conversation_title,
    )

    private companion object {
        const val IN_CHUNK = 500
    }
}
