package com.hush.server.db

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.hush.server.NotificationDto
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The DB layer on its own: FTS5 presence, bootstrap guard, journal mode. Synthetic rows only. */
class ArchiveDatabaseFactoryTest {

    private fun synthetic(id: String, title: String?, text: String?) = NotificationDto(
        id = id, key = "0|pkg.example.alpha|$id", packageName = "pkg.example.alpha", appLabel = "Alpha",
        postTime = 1_000L + id.hashCode().mod(1000), title = title, text = text, bigText = null, subText = null,
        channelId = "general", category = null, flags = 0, importance = 3, event = "posted", removedReason = null,
    )

    private fun ftsRowids(driver: SqlDriver, match: String): List<Long> =
        driver.executeQuery(null, "SELECT rowid FROM notification_fts WHERE notification_fts MATCH ?;", { c ->
            val out = mutableListOf<Long>()
            while (c.next().value) out += c.getLong(0)!!
            QueryResult.Value(out)
        }, 1) { bindString(0, match) }.value

    @Test
    fun `FTS5 smoke - the bundled SQLite has fts5 and the triggers index inserted rows`() {
        ArchiveDatabaseFactory.open(":memory:").use { archive ->
            val repo = ArchiveRepository(archive)
            repo.insertAll("test-device", listOf(
                synthetic("a1", "Synthetic title one", "the quick brown fox"),
                synthetic("a2", "Synthetic title two", "lazy dogs sleep"),
            ), receivedAt = 5L)
            assertEquals(1, ftsRowids(archive.driver, "\"brown fox\"").size)
            assertEquals(2, ftsRowids(archive.driver, "synthetic").size)
            assertEquals(0, ftsRowids(archive.driver, "unicorn").size)
        }
    }

    @Test
    fun `fresh DB is stamped at the current schema version`() {
        ArchiveDatabaseFactory.open(":memory:").use { archive ->
            assertEquals(ArchiveDatabaseFactory.SCHEMA_VERSION, ArchiveDatabaseFactory.userVersion(archive.driver))
            // WAL is a file-only mode; an in-memory DB stays on its default journal (no WAL pragma was issued).
            assertTrue(ArchiveDatabaseFactory.journalMode(archive.driver) != "wal")
        }
    }

    @Test
    fun `a file DB opens in WAL mode and survives being reopened`() {
        val dir = Files.createTempDirectory("hush-archive-test")
        val path = dir.resolve("nested/archive.db").toString() // parent dir must be created by the factory
        try {
            ArchiveDatabaseFactory.open(path).use { archive ->
                assertEquals("wal", ArchiveDatabaseFactory.journalMode(archive.driver))
                assertEquals(
                    ArchiveDatabaseFactory.BUSY_TIMEOUT_MS.toLong(),
                    ArchiveDatabaseFactory.scalarLong(archive.driver, "PRAGMA busy_timeout;"),
                    "the busy_timeout connection property must reach SQLite",
                )
                val repo = ArchiveRepository(archive)
                // On a FILE each thread owns a connection: `SELECT changes()` must run on the
                // transaction's connection or `accepted` would always be 0 in production.
                assertEquals(PushCounts(accepted = 1, duplicates = 0), repo.insertAll("d", listOf(synthetic("f1", "persisted", null)), 1L))
                assertEquals(PushCounts(accepted = 0, duplicates = 1), repo.insertAll("d", listOf(synthetic("f1", "persisted", null)), 2L))
            }
            // Second open must NOT re-run CREATE (would throw "already exists") and must see the row.
            ArchiveDatabaseFactory.open(path).use { archive ->
                assertEquals(ArchiveDatabaseFactory.SCHEMA_VERSION, ArchiveDatabaseFactory.userVersion(archive.driver))
                assertEquals(1L, ArchiveRepository(archive).count())
                assertEquals(1, ftsRowids(archive.driver, "persisted").size)
            }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `ignored duplicate insert does not double-index the row`() {
        ArchiveDatabaseFactory.open(":memory:").use { archive ->
            val repo = ArchiveRepository(archive)
            repo.insertAll("d", listOf(synthetic("x1", "once", "only")), 1L)
            val second = repo.insertAll("d", listOf(synthetic("x1", "changed", "body")), 2L)
            assertEquals(PushCounts(accepted = 0, duplicates = 1), second)
            assertEquals(1, ftsRowids(archive.driver, "once").size)
            assertTrue(ftsRowids(archive.driver, "changed").isEmpty())
        }
    }

    /** A file exactly as the v1 server (issue #8) left it: v1 table, indexes, FTS, one row, version stamp 1. */
    private fun createV1File(path: String, alsoHalfMigrated: Boolean = false) {
        val driver = JdbcSqliteDriver("jdbc:sqlite:$path")
        try {
            listOf(
                """
                CREATE TABLE notification (
                    id TEXT NOT NULL PRIMARY KEY, device TEXT NOT NULL, key TEXT NOT NULL, package_name TEXT NOT NULL,
                    app_label TEXT, post_time INTEGER NOT NULL, title TEXT, text TEXT, big_text TEXT, sub_text TEXT,
                    channel_id TEXT, category TEXT, flags INTEGER NOT NULL, importance INTEGER, event TEXT NOT NULL,
                    removed_reason INTEGER, received_at INTEGER NOT NULL
                )
                """.trimIndent(),
                "CREATE INDEX notification_post_time ON notification(post_time)",
                "CREATE INDEX notification_package_post_time ON notification(package_name, post_time)",
            ).forEach { driver.execute(null, it, 0) }
            ArchiveDatabaseFactory.createFts(driver)
            driver.execute(
                null,
                """
                INSERT INTO notification (id, device, key, package_name, app_label, post_time, title, text, flags, event, received_at)
                VALUES ('old1', 'test-device', '0|pkg.example.alpha|old1', 'pkg.example.alpha', 'Alpha', 1000, 'Legacy title', 'legacy body', 0, 'posted', 1)
                """.trimIndent(),
                0,
            )
            if (alsoHalfMigrated) driver.execute(null, "ALTER TABLE notification ADD COLUMN conversation_id TEXT", 0)
            driver.execute(null, "PRAGMA user_version = 1", 0)
        } finally {
            driver.close()
        }
    }

    private fun columns(driver: SqlDriver): List<String> =
        driver.executeQuery(null, "PRAGMA table_info(notification);", { c ->
            val out = mutableListOf<String>()
            while (c.next().value) out += "${c.getString(1)} ${c.getString(2)}"
            QueryResult.Value(out)
        }, 0).value

    @Test
    fun `an old bootstrap that died before its version stamp is completed, not stamped as it is`() {
        val dir = Files.createTempDirectory("hush-archive-unstamped")
        val path = dir.resolve("archive.db").toString()
        try {
            createV1File(path)
            JdbcSqliteDriver("jdbc:sqlite:$path").use { it.execute(null, "PRAGMA user_version = 0", 0) }

            ArchiveDatabaseFactory.open(path).use { archive ->
                assertEquals(2L, ArchiveDatabaseFactory.userVersion(archive.driver))
                val repo = ArchiveRepository(archive)
                repo.insertAll("d", listOf(synthetic("n1", "t", "b").copy(conversationId = "chat-7")), 1L)
                assertEquals("chat-7", repo.page(null, null, null, null, Long.MAX_VALUE, 10).single { it.id == "n1" }.conversationId)
            }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    private fun migratedFileKeepsRowsAndTakesConversations(alsoHalfMigrated: Boolean) {
        val dir = Files.createTempDirectory("hush-archive-migrate")
        val path = dir.resolve("archive.db").toString()
        try {
            createV1File(path, alsoHalfMigrated)

            ArchiveDatabaseFactory.open(path).use { archive ->
                assertEquals(2L, ArchiveDatabaseFactory.userVersion(archive.driver))
                val repo = ArchiveRepository(archive)
                val old = repo.page(null, null, null, null, Long.MAX_VALUE, 10).single()
                assertEquals("old1", old.id)
                assertEquals(null, old.conversationId)
                assertEquals(1, ftsRowids(archive.driver, "legacy").size, "the FTS index still points at the old row")

                repo.insertAll(
                    "test-device",
                    listOf(synthetic("new1", "Synthetic sender", "fresh body").copy(conversationId = "chat-7", conversationTitle = "Synthetic chat")),
                    receivedAt = 2L,
                )
                // Both read paths: the FTS join lists its columns by position, the plain page maps SELECT *.
                val viaSearch = repo.page("fresh", null, null, null, Long.MAX_VALUE, 10).single()
                val viaPage = repo.page(null, null, null, null, Long.MAX_VALUE, 10).single { it.id == "new1" }
                for (fresh in listOf(viaSearch, viaPage)) {
                    assertEquals("chat-7", fresh.conversationId)
                    assertEquals("Synthetic chat", fresh.conversationTitle)
                    assertEquals("Synthetic sender", fresh.title)
                }
                ArchiveDatabaseFactory.open(":memory:").use { freshFile ->
                    assertEquals(columns(freshFile.driver), columns(archive.driver), "same columns in the same order as a fresh file")
                }
            }
            // A second start finds version 2 and must not try to add the columns again.
            ArchiveDatabaseFactory.open(path).use { archive ->
                assertEquals(2L, ArchiveRepository(archive).count())
            }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `a v1 file is upgraded in place - rows and FTS survive, new rows carry a conversation`() =
        migratedFileKeepsRowsAndTakesConversations(alsoHalfMigrated = false)

    @Test
    fun `an upgrade that died after the first column is finished on the next start`() =
        migratedFileKeepsRowsAndTakesConversations(alsoHalfMigrated = true)
}
