package com.hush.app.db

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.hush.app.testing.event
import com.hush.app.testing.fixedClock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest

/** The phone already carries a v1 database; every schema change must upgrade it in place. */
class MigrationTest {

    /** The v1 schema exactly as it shipped (wave 1), before `migrations/1.sqm`. */
    private val v1 = listOf(
        """
        CREATE TABLE notification_event (
            id TEXT NOT NULL PRIMARY KEY,
            key TEXT NOT NULL,
            package_name TEXT NOT NULL,
            app_label TEXT,
            post_time INTEGER NOT NULL,
            title TEXT,
            text TEXT,
            big_text TEXT,
            sub_text TEXT,
            channel_id TEXT,
            category TEXT,
            flags INTEGER NOT NULL,
            importance INTEGER,
            event TEXT NOT NULL,
            removed_reason INTEGER,
            held INTEGER NOT NULL DEFAULT 0,
            delivered_at INTEGER,
            synced_at INTEGER,
            created_at INTEGER NOT NULL
        )
        """.trimIndent(),
        "CREATE INDEX notification_event_post_time ON notification_event(post_time)",
        "CREATE INDEX notification_event_package_post_time ON notification_event(package_name, post_time)",
        "CREATE INDEX notification_event_unsynced ON notification_event(post_time) WHERE synced_at IS NULL",
        "CREATE INDEX notification_event_held_undelivered ON notification_event(post_time) WHERE held = 1 AND delivered_at IS NULL",
        """
        INSERT INTO notification_event (id, key, package_name, app_label, post_time, title, text, flags, event, held, created_at)
        VALUES ('old', '0|com.example.alpha|1|null|10000', 'com.example.alpha', 'Alpha', 100, 'Sender A', 'synthetic', 0, 'POSTED', 1, 100)
        """.trimIndent(),
    )

    @Test
    fun `v1 database upgrades in place - old rows survive and new rows carry a conversation`() = runTest {
        Class.forName("org.sqlite.JDBC")
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        v1.forEach { driver.execute(null, it, 0) }

        HushDatabase.Schema.migrate(driver, 1, HushDatabase.Schema.version)

        val repo = NotificationRepository(HushDatabase(driver), fixedClock())
        repo.insert(event("new", postTime = 200, title = "Sender B", conversationId = "c1", conversationTitle = "Group one"))

        val rows = repo.selectHistory("", null, null, 10).first()
        assertEquals(listOf("new", "old"), rows.map { it.id })
        assertEquals("c1", rows[0].conversationId)
        assertNull(rows[1].conversationId)
        assertEquals("Sender A", rows[1].chatKey, "a pre-migration row still groups by its title")
        assertEquals(true, rows[1].held, "held state is untouched by the upgrade")
        assertEquals(listOf("old"), repo.selectHeldUndelivered().map { it.id })
    }

    @Test
    fun `schema version is 2`() {
        assertEquals(2L, HushDatabase.Schema.version)
    }
}
