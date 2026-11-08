package com.hush.app.testing

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.hush.app.db.EventType
import com.hush.app.db.HushDatabase
import com.hush.app.db.NotificationEvent
import com.hush.app.db.NotificationRepository
import java.time.Clock

/** Fresh in-memory database with the schema applied. */
fun testDatabase(): HushDatabase {
    // Registers the driver in the caller's classloader (Robolectric sandbox vs plain JVM).
    Class.forName("org.sqlite.JDBC")
    val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
    HushDatabase.Schema.create(driver)
    return HushDatabase(driver)
}

fun testRepository(clock: Clock = fixedClock(), db: HushDatabase = testDatabase()): NotificationRepository =
    NotificationRepository(db, clock)

/** Synthetic event fixture. Every field has a harmless default; override what the test cares about. */
fun event(
    id: String,
    packageName: String = "com.example.alpha",
    postTime: Long = 1_000L,
    held: Boolean = false,
    appLabel: String? = "Alpha",
    title: String? = "title $id",
    text: String? = "text $id",
    bigText: String? = null,
    event: EventType = EventType.POSTED,
): NotificationEvent = NotificationEvent(
    id = id,
    key = "0|$packageName|$id|null|10000",
    packageName = packageName,
    appLabel = appLabel,
    postTime = postTime,
    title = title,
    text = text,
    bigText = bigText,
    subText = null,
    channelId = "default",
    category = null,
    flags = 0,
    importance = 3,
    event = event,
    removedReason = null,
    held = held,
)
