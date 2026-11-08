package com.hush.server.db

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import java.io.File
import java.util.Properties
import org.slf4j.LoggerFactory

/** An open archive: the raw [driver] (for FTS, which SQLDelight cannot express) and the typed [db]. */
class Archive(val driver: SqlDriver, val db: ArchiveDatabase) : AutoCloseable {
    override fun close() = driver.close()
}

/**
 * Opens the archive SQLite database and makes sure its schema exists (issue #8).
 *
 * One [JdbcSqliteDriver] per DB. `:memory:` (or SQLDelight's [JdbcSqliteDriver.IN_MEMORY]) gives a
 * single in-memory connection for tests. A file path gets WAL mode (one writer, concurrent
 * readers, so a search during a push never blocks) and a busy timeout: under Ktor each request
 * thread gets its own connection to the file, and without the timeout two simultaneous writers
 * would fail with SQLITE_BUSY instead of waiting a moment.
 *
 * Schema creation is guarded by SQLite's `user_version`: a fresh file (version 0) gets the
 * SQLDelight schema, then the FTS5 index and triggers via raw SQL, then the version stamp. Every
 * statement is `IF NOT EXISTS`, and the stamp comes LAST, so a bootstrap that dies halfway is
 * retried on the next start instead of wedging the box with "table already exists".
 *
 * FTS5 is created on the driver rather than in `Archive.sq` because SQLDelight's dialect does not
 * parse `CREATE VIRTUAL TABLE`. The index is an EXTERNAL-CONTENT table keyed on
 * `notification.rowid`; the three triggers keep it in sync. `notification` has a TEXT primary key,
 * so its rowids are implicit, and SQLite's `VACUUM` may renumber implicit rowids — which would
 * silently point the index at the wrong rows. Rule: NEVER `VACUUM` this file without immediately
 * running `INSERT INTO notification_fts(notification_fts) VALUES('rebuild');` afterwards. Nothing in
 * the server vacuums; `auto_vacuum` is left at its default (off).
 */
object ArchiveDatabaseFactory {

    /** Current schema version; bump together with a migration path when the schema changes. */
    const val SCHEMA_VERSION = 1L

    /** Milliseconds a connection waits on a locked file before giving up (file DBs only). */
    const val BUSY_TIMEOUT_MS = 5_000

    private val log = LoggerFactory.getLogger(ArchiveDatabaseFactory::class.java)

    fun open(path: String): Archive {
        val inMemory = path == JdbcSqliteDriver.IN_MEMORY || path == ":memory:"
        val driver = if (inMemory) {
            JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        } else {
            File(path).absoluteFile.parentFile?.mkdirs()
            val props = Properties().apply { setProperty("busy_timeout", BUSY_TIMEOUT_MS.toString()) }
            JdbcSqliteDriver("jdbc:sqlite:$path", props).also {
                // Persistent in the file: later connections see WAL without re-running this.
                it.execute(null, "PRAGMA journal_mode=WAL;", 0)
            }
        }
        ensureSchema(driver)
        return Archive(driver, ArchiveDatabase(driver))
    }

    private fun ensureSchema(driver: SqlDriver) {
        when (val current = userVersion(driver)) {
            0L -> {
                ArchiveDatabase.Schema.create(driver)
                createFts(driver)
                driver.execute(null, "PRAGMA user_version = $SCHEMA_VERSION;", 0)
            }
            SCHEMA_VERSION -> Unit
            else -> log.warn(
                "archive DB is at schema version {} but this build targets {} — a rolled-back deploy? " +
                    "Leaving the file alone.",
                current, SCHEMA_VERSION,
            )
        }
    }

    private fun createFts(driver: SqlDriver) {
        val statements = listOf(
            """CREATE VIRTUAL TABLE IF NOT EXISTS notification_fts
               USING fts5(title, text, big_text, content='notification', content_rowid='rowid');""",
            """CREATE TRIGGER IF NOT EXISTS notification_ai AFTER INSERT ON notification BEGIN
                 INSERT INTO notification_fts(rowid, title, text, big_text)
                 VALUES (new.rowid, new.title, new.text, new.big_text);
               END;""",
            """CREATE TRIGGER IF NOT EXISTS notification_ad AFTER DELETE ON notification BEGIN
                 INSERT INTO notification_fts(notification_fts, rowid, title, text, big_text)
                 VALUES ('delete', old.rowid, old.title, old.text, old.big_text);
               END;""",
            """CREATE TRIGGER IF NOT EXISTS notification_au AFTER UPDATE ON notification BEGIN
                 INSERT INTO notification_fts(notification_fts, rowid, title, text, big_text)
                 VALUES ('delete', old.rowid, old.title, old.text, old.big_text);
                 INSERT INTO notification_fts(rowid, title, text, big_text)
                 VALUES (new.rowid, new.title, new.text, new.big_text);
               END;""",
        )
        for (sql in statements) driver.execute(null, sql.trimIndent(), 0)
    }

    /** Reads SQLite's `user_version` (0 on a brand-new DB). */
    fun userVersion(driver: SqlDriver): Long = scalarLong(driver, "PRAGMA user_version;")

    /** The current `journal_mode`: "wal" for files opened here; `:memory:` keeps SQLite's default. */
    fun journalMode(driver: SqlDriver): String =
        driver.executeQuery(null, "PRAGMA journal_mode;", { c ->
            QueryResult.Value(if (c.next().value) c.getString(0) ?: "" else "")
        }, 0).value

    internal fun scalarLong(driver: SqlDriver, sql: String): Long =
        driver.executeQuery(null, sql, { c ->
            QueryResult.Value(if (c.next().value) c.getLong(0) ?: 0L else 0L)
        }, 0).value
}
