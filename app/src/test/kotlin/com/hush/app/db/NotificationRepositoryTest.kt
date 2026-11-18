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
        assertEquals(0L, repo.heldUndelivered().first().size.toLong())
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
        assertEquals(2L, repo.heldUndelivered().first().size.toLong())

        repo.markDelivered(listOf("h1"), deliveredAt = 1_000)
        assertEquals(listOf("h2"), repo.selectHeldUndelivered().map { it.id })
        assertEquals(1L, repo.heldUndelivered().first().size.toLong())
        assertEquals(listOf("h1"), repo.selectHeldBatch(1_000).map { it.id })
        assertTrue(repo.selectHeldBatch(2_000).isEmpty())

        repo.markDelivered(emptyList(), deliveredAt = 3_000) // no-op
        assertEquals(1L, repo.heldUndelivered().first().size.toLong())
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
    fun `selectHistory matches title text big_text or conversation title and filters by package`() = runTest {
        repo.insert(event("t", packageName = "com.example.alpha", title = "needle here", text = "x"))
        repo.insert(event("x", packageName = "com.example.beta", title = "y", text = "a needle"))
        repo.insert(event("b", packageName = "com.example.beta", title = "y", text = "z", bigText = "NEEDLE big"))
        repo.insert(event("c", packageName = "com.example.beta", title = "y", text = "z", conversationId = "c1", conversationTitle = "Needle group"))
        repo.insert(event("n", packageName = "com.example.beta", title = "nothing", text = "else"))

        suspend fun ids(q: String, pkg: String?) = repo.selectHistory(q, pkg, null, 10).first().map { it.id }.toSet()

        assertEquals(setOf("t", "x", "b", "c"), ids("needle", null))
        assertEquals(setOf("x", "b", "c"), ids("needle", "com.example.beta"))
        assertTrue(ids("needle", "com.example.gamma").isEmpty())
        assertEquals(4L, repo.countHistory("needle", null, null).first())
    }

    @Test
    fun `selectHistory treats LIKE wildcards and backslash literally`() = runTest {
        repo.insert(event("pct", title = "100% done", text = "x"))
        repo.insert(event("under", title = "snake_case", text = "x"))
        repo.insert(event("slash", title = "C:\\path", text = "x"))
        repo.insert(event("plain", title = "100 done snakeXcase", text = "x"))

        suspend fun ids(q: String) = repo.selectHistory(q, null, null, 10).first().map { it.id }

        assertEquals(listOf("pct"), ids("100%"))
        assertEquals(listOf("under"), ids("e_c"))
        assertEquals(listOf("slash"), ids("\\p"))
        assertEquals(listOf("pct"), ids("%"))
    }

    @Test
    fun `selectHistory lists POSTED rows only, newest first, and the count ignores the limit`() = runTest {
        repo.insert(event("p1", postTime = 10))
        repo.insert(event("r1", postTime = 20, event = EventType.REMOVED))
        repo.insert(event("p2", postTime = 30))
        repo.insert(event("p3", postTime = 40))

        assertEquals(listOf("p3", "p2"), repo.selectHistory("", null, null, 2).first().map { it.id })
        assertEquals(3L, repo.countHistory("", null, null).first())
    }

    @Test
    fun `chat filter matches the conversation id, and the title for rows without one`() = runTest {
        val pkg = "com.example.alpha"
        repo.insert(event("new1", packageName = pkg, title = "Sender A", conversationId = "c1", conversationTitle = "Group one", postTime = 40))
        repo.insert(event("new2", packageName = pkg, title = "Sender B", conversationId = "c1", conversationTitle = "Group one", postTime = 30))
        repo.insert(event("legacy", packageName = pkg, title = "Sender A", postTime = 20))
        repo.insert(event("other", packageName = "com.example.beta", title = "Sender A", postTime = 10))

        assertEquals(listOf("new1", "new2"), repo.selectHistory("", pkg, "c1", 10).first().map { it.id })
        assertEquals(listOf("legacy"), repo.selectHistory("", pkg, "Sender A", 10).first().map { it.id })
        assertEquals(2L, repo.countHistory("", pkg, "c1").first())
    }

    @Test
    fun `selectChats groups one app by chat key with the newest name, most recent first`() = runTest {
        val pkg = "com.example.alpha"
        repo.insert(event("a", packageName = pkg, title = "Sender A", conversationId = "c1", conversationTitle = "Old name", postTime = 10))
        repo.insert(event("b", packageName = pkg, title = "Sender B", conversationId = "c1", conversationTitle = "New name", postTime = 50))
        repo.insert(event("l1", packageName = pkg, title = "Legacy sender", postTime = 20))
        repo.insert(event("l2", packageName = pkg, title = "Legacy sender", postTime = 30))
        repo.insert(event("gone", packageName = pkg, title = "Legacy sender", postTime = 60, event = EventType.REMOVED))
        repo.insert(event("untitled", packageName = pkg, title = null, postTime = 70))
        repo.insert(event("elsewhere", packageName = "com.example.beta", title = "Sender A", postTime = 80))

        assertEquals(
            listOf(
                ChatSummary("c1", "New name", 2, 50),
                ChatSummary("Legacy sender", "Legacy sender", 2, 30),
            ),
            repo.selectChats(pkg).first(),
        )
    }

    @Test
    fun `conversation fields round trip and chatKey falls back to the title`() = runTest {
        repo.insert(event("c", title = "Sender A", conversationId = "c1", conversationTitle = "Group one"))
        repo.insert(event("p", title = "Plain title", postTime = 1))

        val rows = repo.selectHistory("", null, null, 10).first().associateBy { it.id }

        assertEquals("c1", rows.getValue("c").conversationId)
        assertEquals("Group one", rows.getValue("c").conversationTitle)
        assertEquals("c1", rows.getValue("c").chatKey)
        assertEquals(null, rows.getValue("p").conversationId)
        assertEquals("Plain title", rows.getValue("p").chatKey)
    }

    @Test
    fun `selectApps aggregates POSTED rows per package`() = runTest {
        repo.insert(event("a1", packageName = "com.example.alpha", appLabel = "Alpha", postTime = 10))
        repo.insert(event("a2", packageName = "com.example.alpha", appLabel = null, postTime = 30))
        repo.insert(event("b1", packageName = "com.example.beta", appLabel = "Beta", postTime = 20))
        // A removal is not something History lists, so it must not inflate the app's count or recency.
        repo.insert(event("b2", packageName = "com.example.beta", appLabel = "Beta", postTime = 99, event = EventType.REMOVED))

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
