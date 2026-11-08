package com.hush.app.db

import com.hush.app.testing.event
import com.hush.app.testing.testRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest

class NotificationRepositoryTest {

    private val repo = testRepository()

    @Test
    fun `insert then selectRecent returns newest first and respects before`() = runTest {
        repo.insert(event("a", postTime = 100))
        repo.insert(event("b", postTime = 300))
        repo.insert(event("c", postTime = 200))

        assertEquals(listOf("b", "c", "a"), repo.selectRecent(limit = 10, before = Long.MAX_VALUE).first().map { it.id })
        assertEquals(listOf("c", "a"), repo.selectRecent(limit = 10, before = 300).first().map { it.id })
        assertEquals(listOf("b"), repo.selectRecent(limit = 1, before = Long.MAX_VALUE).first().map { it.id })
    }

    @Test
    fun `insert is OR IGNORE on a duplicate id`() = runTest {
        repo.insert(event("dup", title = "first"))
        repo.insert(event("dup", title = "second"))

        val rows = repo.selectRecent(limit = 10, before = Long.MAX_VALUE).first()
        assertEquals(1, rows.size)
        assertEquals("first", rows.single().title)
    }

    @Test
    fun `round-trips every field including event type and stamps`() = runTest {
        val e = event("r", event = EventType.REMOVED, held = true, bigText = "big")
        repo.insert(e)
        assertEquals(e, repo.selectRecent(limit = 1, before = Long.MAX_VALUE).first().single())

        repo.markDelivered(listOf("r"), deliveredAt = 111)
        repo.markSynced(listOf("r"), syncedAt = 222)
        assertEquals(
            e.copy(deliveredAt = 111, syncedAt = 222),
            repo.selectRecent(limit = 1, before = Long.MAX_VALUE).first().single(),
        )
    }

    @Test
    fun `insert ignores caller-supplied stamps`() = runTest {
        repo.insert(event("s").copy(deliveredAt = 5, syncedAt = 6))
        val row = repo.selectRecent(limit = 1, before = Long.MAX_VALUE).first().single()
        assertEquals(null, row.deliveredAt)
        assertEquals(null, row.syncedAt)
    }

    @Test
    fun `markSynced and markDelivered handle more ids than SQLite's 999 variable cap`() = runTest {
        val ids = (1..1001).map { "id-$it" }
        ids.forEachIndexed { i, id -> repo.insert(event(id, postTime = i.toLong(), held = true)) }

        repo.markSynced(ids, syncedAt = 10)
        repo.markDelivered(ids, deliveredAt = 20)

        assertEquals(0L, repo.countUnsynced().first())
        assertEquals(0L, repo.countHeld().first())
        assertEquals(1001, repo.selectHeldBatch(20).size)
    }

    @Test
    fun `unsynced then markSynced`() = runTest {
        repo.insert(event("a", postTime = 1))
        repo.insert(event("b", postTime = 2))
        repo.insert(event("c", postTime = 3))

        assertEquals(3L, repo.countUnsynced().first())
        assertEquals(listOf("a", "b"), repo.selectUnsynced(limit = 2).map { it.id })

        repo.markSynced(listOf("a", "b"), syncedAt = 500)
        assertEquals(listOf("c"), repo.selectUnsynced(limit = 10).map { it.id })
        assertEquals(1L, repo.countUnsynced().first())

        repo.markSynced(emptyList(), syncedAt = 600) // no-op, must not throw
        assertEquals(1L, repo.countUnsynced().first())
    }

    @Test
    fun `held then markDelivered then selectHeldBatch`() = runTest {
        repo.insert(event("h1", held = true, postTime = 1))
        repo.insert(event("h2", held = true, postTime = 2))
        repo.insert(event("p", held = false, postTime = 3))

        assertEquals(listOf("h1", "h2"), repo.selectHeldUndelivered().map { it.id })
        assertEquals(2L, repo.countHeld().first())

        repo.markDelivered(listOf("h1"), deliveredAt = 1_000)
        assertEquals(listOf("h2"), repo.selectHeldUndelivered().map { it.id })
        assertEquals(1L, repo.countHeld().first())
        assertEquals(listOf("h1"), repo.selectHeldBatch(1_000).map { it.id })
        assertTrue(repo.selectHeldBatch(2_000).isEmpty())

        repo.markDelivered(emptyList(), deliveredAt = 3_000) // no-op
        assertEquals(1L, repo.countHeld().first())
    }

    @Test
    fun `retention deletes only synced rows that are not held-undelivered`() = runTest {
        repo.insert(event("synced-plain", postTime = 10))
        repo.insert(event("synced-delivered", postTime = 10, held = true))
        repo.insert(event("synced-held-undelivered", postTime = 10, held = true))
        repo.insert(event("unsynced-plain", postTime = 10))
        repo.insert(event("synced-recent", postTime = 1_000))
        repo.markSynced(listOf("synced-plain", "synced-delivered", "synced-held-undelivered", "synced-recent"), syncedAt = 20)
        repo.markDelivered(listOf("synced-delivered"), deliveredAt = 30)

        val deleted = repo.deleteSyncedOlderThan(ts = 500)

        assertEquals(2, deleted)
        assertEquals(
            setOf("synced-held-undelivered", "unsynced-plain", "synced-recent"),
            repo.selectRecent(limit = 10, before = Long.MAX_VALUE).first().map { it.id }.toSet(),
        )
    }

    @Test
    fun `search matches title text or big_text and filters by package`() = runTest {
        repo.insert(event("t", packageName = "com.example.alpha", title = "needle here", text = "x"))
        repo.insert(event("x", packageName = "com.example.beta", title = "y", text = "a needle"))
        repo.insert(event("b", packageName = "com.example.beta", title = "y", text = "z", bigText = "NEEDLE big"))
        repo.insert(event("n", packageName = "com.example.beta", title = "nothing", text = "else"))

        assertEquals(setOf("t", "x", "b"), repo.search("needle", null, 10).first().map { it.id }.toSet())
        assertEquals(setOf("x", "b"), repo.search("needle", "com.example.beta", 10).first().map { it.id }.toSet())
        assertTrue(repo.search("needle", "com.example.gamma", 10).first().isEmpty())
    }

    @Test
    fun `search treats LIKE wildcards and backslash literally`() = runTest {
        repo.insert(event("pct", title = "100% done", text = "x"))
        repo.insert(event("under", title = "snake_case", text = "x"))
        repo.insert(event("slash", title = "C:\\path", text = "x"))
        repo.insert(event("plain", title = "100 done snakeXcase", text = "x"))

        assertEquals(listOf("pct"), repo.search("100%", null, 10).first().map { it.id })
        assertEquals(listOf("under"), repo.search("e_c", null, 10).first().map { it.id })
        assertEquals(listOf("slash"), repo.search("\\p", null, 10).first().map { it.id })
        assertTrue(repo.search("%", null, 10).first().map { it.id } == listOf("pct"))
    }

    @Test
    fun `selectApps aggregates per package`() = runTest {
        repo.insert(event("a1", packageName = "com.example.alpha", appLabel = "Alpha", postTime = 10))
        repo.insert(event("a2", packageName = "com.example.alpha", appLabel = null, postTime = 30))
        repo.insert(event("b1", packageName = "com.example.beta", appLabel = "Beta", postTime = 20))

        val apps = repo.selectApps().first()

        assertEquals(
            listOf(
                AppSummary("com.example.alpha", "Alpha", 2, 30),
                AppSummary("com.example.beta", "Beta", 1, 20),
            ),
            apps,
        )
    }
}
