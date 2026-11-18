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
 * An older file is upgraded in place, one version at a time ([migrate]); each step is written so
 * that running it again after a crash is harmless, and the stamp again comes last.
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
    const val SCHEMA_VERSION = 2L

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
            in 1L until SCHEMA_VERSION -> {
                migrate(driver, from = current)
                driver.execute(null, "PRAGMA user_version = $SCHEMA_VERSION;", 0)
                log.info("archive DB migrated from schema version {} to {}", current, SCHEMA_VERSION)
            }
            else -> log.warn(
                "archive DB is at schema version {} but this build targets {} — a rolled-back deploy? " +
                    "Leaving the file alone.",
                current, SCHEMA_VERSION,
            )
        }
    }

    /**
     * Upgrades an existing file from schema version [from] to [SCHEMA_VERSION]. Rows are never
     * rewritten. The FTS index covers title/text/big_text only and is keyed on rowid, neither of
     * which a new nullable column touches, so it needs no rebuild.
     */
    private fun migrate(driver: SqlDriver, from: Long) {
        if (from < 2L) {
            // v1 → v2: conversation identity. ADD COLUMN has no IF NOT EXISTS, hence the check: a
            // start that died between the two statements must not fail on "duplicate column name".
            for (column in listOf("conversation_id", "conversation_title")) {
                if (!hasColumn(driver, "notification", column)) {
                    driver.execute(null, "ALTER TABLE notification ADD COLUMN $column TEXT;", 0)
                }
            }
        }
    }

    private fun hasColumn(driver: SqlDriver, table: String, column: String): Boolean =
        driver.executeQuery(null, "PRAGMA table_info($table);", { c ->
            var found = false
            // table_info columns: cid, name, type, notnull, dflt_value, pk
            while (c.next().value) if (c.getString(1) == column) found = true
            QueryResult.Value(found)
        }, 0).value

    internal fun createFts(driver: SqlDriver) {
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
