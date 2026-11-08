package com.hush.app.listener

import com.hush.app.testing.event
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DedupeCacheTest {

    @Test
    fun `duplicate only when title, text and bigText all match the remembered row for the key`() {
        val cache = DedupeCache()
        val e = event("a", title = "t", text = "x", bigText = "big")
        assertFalse(cache.isDuplicate(e))

        cache.remember(e)
        assertTrue(cache.isDuplicate(e))
        assertTrue(cache.isDuplicate(e.copy(id = "other-id", postTime = 999, importance = 4)))
        assertFalse(cache.isDuplicate(e.copy(title = "changed")))
        assertFalse(cache.isDuplicate(e.copy(text = "changed")))
        assertFalse(cache.isDuplicate(e.copy(bigText = null)))
        assertFalse(cache.isDuplicate(e.copy(key = "another-key")))
    }

    @Test
    fun `forget clears the key`() {
        val cache = DedupeCache()
        val e = event("a")
        cache.remember(e)
        cache.forget(e.key)
        assertFalse(cache.isDuplicate(e))
    }

    @Test
    fun `evicts least recently used beyond capacity`() {
        val cache = DedupeCache(capacity = 2)
        val a = event("a")
        val b = event("b")
        val c = event("c")
        cache.remember(a)
        cache.remember(b)
        cache.isDuplicate(a) // touch a so b is the eldest
        cache.remember(c)
        assertEquals(2, cache.size)
        assertTrue(cache.isDuplicate(a))
        assertFalse(cache.isDuplicate(b))
        assertTrue(cache.isDuplicate(c))
    }
}
