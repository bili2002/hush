package com.hush.server.db

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
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
}
