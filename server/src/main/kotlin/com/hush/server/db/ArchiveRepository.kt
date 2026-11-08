package com.hush.server.db

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import com.hush.server.AppSummary
import com.hush.server.ArchiveItem
import com.hush.server.NotificationDto

/** Outcome of one push batch: rows newly stored vs rows whose id was already in the archive. */
data class PushCounts(val accepted: Int, val duplicates: Int)

/**
 * Everything the routes need from the archive. Blocking JDBC; callers run it on `Dispatchers.IO`.
 */
class ArchiveRepository(private val archive: Archive) {

    private val queries get() = archive.db.archiveQueries

    /**
     * Stores a batch in ONE transaction. `INSERT OR IGNORE` on the primary key is the whole
     * idempotency story: the phone's ids are deterministic, so a retry of a half-acknowledged batch
     * lands on the same rows and is counted as duplicates, never stored twice.
     *
     * Idempotency over last-write-wins, deliberately: a row whose id already exists keeps its
     * FIRST content even if the new body differs. The id identifies an event the phone saw once;
     * a later body with the same id is a replay (or a bug), and overwriting would let a stale
     * retry rewrite history. The caller is told via `duplicates`, not via an error.
     *
     * The count comes from `SELECT changes()` on the same connection after each insert, so an id
     * repeated INSIDE one batch is also a duplicate. A hard failure rolls back the whole batch and
     * surfaces as 500 — the phone treats 5xx as transient and retries, so nothing is half-applied.
     */
    fun insertAll(device: String, items: List<NotificationDto>, receivedAt: Long): PushCounts =
        archive.db.transactionWithResult {
            var accepted = 0
            for (n in items) {
                queries.insertOrIgnore(
                    id = n.id, device = device, key = n.key, packageName = n.packageName,
                    appLabel = n.appLabel, postTime = n.postTime, title = n.title, text = n.text,
                    bigText = n.bigText, subText = n.subText, channelId = n.channelId,
                    category = n.category, flags = n.flags.toLong(), importance = n.importance?.toLong(),
                    event = n.event, removedReason = n.removedReason?.toLong(), receivedAt = receivedAt,
                )
                if (ArchiveDatabaseFactory.scalarLong(archive.driver, "SELECT changes();") > 0L) accepted++
            }
            PushCounts(accepted = accepted, duplicates = items.size - accepted)
        }

    /**
     * One page, newest first, `before` exclusive on `post_time`. A blank [q] is a plain page through
     * the generated query; otherwise the FTS5 index is joined in via raw SQL (SQLDelight cannot
     * express `MATCH`). The optional filters are appended as fixed clauses with `?` bindings only —
     * no user text is ever concatenated into the SQL.
     */
    fun page(q: String?, packageName: String?, from: Long?, to: Long?, before: Long, limit: Long): List<ArchiveItem> {
        if (q.isNullOrBlank()) {
            return queries.selectPage(packageName, from, to, before, limit).executeAsList().map { it.toItem() }
        }
        // unicode61 tokenises letters and digits only; a phrase with none (e.g. "-" or "***") has
        // no tokens, and FTS5 rejects an empty phrase. Nothing can match it, so answer that directly.
        if (q.none { it.isLetterOrDigit() }) return emptyList()

        val sql = StringBuilder(
            """
            SELECT n.id, n.device, n.key, n.package_name, n.app_label, n.post_time, n.title, n.text,
                   n.big_text, n.sub_text, n.channel_id, n.category, n.flags, n.importance, n.event,
                   n.removed_reason, n.received_at
            FROM notification_fts
            JOIN notification n ON n.rowid = notification_fts.rowid
            WHERE notification_fts MATCH ?
            """.trimIndent(),
        )
        val binds = mutableListOf<Any>(ftsPhrase(q))
        if (packageName != null) { sql.append(" AND n.package_name = ?"); binds += packageName }
        if (from != null) { sql.append(" AND n.post_time >= ?"); binds += from }
        if (to != null) { sql.append(" AND n.post_time <= ?"); binds += to }
        sql.append(" AND n.post_time < ?"); binds += before
        sql.append(" ORDER BY n.post_time DESC LIMIT ?"); binds += limit

        return archive.driver.executeQuery(null, sql.toString(), { cursor ->
            val out = mutableListOf<ArchiveItem>()
            while (cursor.next().value) out += cursor.toItem()
            QueryResult.Value(out)
        }, binds.size) {
            binds.forEachIndexed { i, v ->
                when (v) {
                    is String -> bindString(i, v)
                    is Long -> bindLong(i, v)
                    else -> error("unsupported bind type ${v::class}")
                }
            }
        }.value
    }

    fun apps(): List<AppSummary> = queries.selectApps().executeAsList().map {
        AppSummary(packageName = it.package_name, appLabel = it.app_label, count = it.count, lastSeen = it.last_seen ?: 0L)
    }

    fun count(): Long = queries.count().executeAsOne()

    private fun Notification.toItem() = ArchiveItem(
        id = id, device = device, key = key, packageName = package_name, appLabel = app_label,
        postTime = post_time, title = title, text = text, bigText = big_text, subText = sub_text,
        channelId = channel_id, category = category, flags = flags.toInt(), importance = importance?.toInt(),
        event = event, removedReason = removed_reason?.toInt(),
    )

    /** POSITIONAL: index N here is column N of the raw SELECT in [page]; change both together. */
    private fun SqlCursor.toItem() = ArchiveItem(
        id = getString(0)!!, device = getString(1)!!, key = getString(2)!!, packageName = getString(3)!!,
        appLabel = getString(4), postTime = getLong(5)!!, title = getString(6), text = getString(7),
        bigText = getString(8), subText = getString(9), channelId = getString(10), category = getString(11),
        flags = getLong(12)!!.toInt(), importance = getLong(13)?.toInt(), event = getString(14)!!,
        removedReason = getLong(15)?.toInt(),
    )

    companion object {
        /**
         * Quotes the whole user query as ONE FTS5 phrase: wrapped in `"`, inner `"` doubled. Inside a
         * phrase FTS5 treats `-`, `:`, `*`, `^`, `OR`, `NEAR` literally, so no user input can reach the
         * MATCH parser as syntax (which would otherwise be a 500 on `q=-foo` or `q=a:b`).
         */
        fun ftsPhrase(q: String): String = "\"" + q.replace("\"", "\"\"") + "\""
    }
}
