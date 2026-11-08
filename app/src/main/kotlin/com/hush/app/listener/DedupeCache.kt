package com.hush.app.listener

import com.hush.app.db.NotificationEvent

/**
 * Remembers the last recorded text per notification key so an update that changes nothing
 * visible (same title/text/bigText) is not inserted again.
 *
 * In-memory and process-scoped on purpose: the repository has no per-key lookup, and after a
 * process restart the deterministic event id already turns a replay with the same postTime into
 * an `INSERT OR IGNORE` no-op. Bounded LRU so a chatty device cannot grow it without limit.
 */
class DedupeCache(private val capacity: Int = DEFAULT_CAPACITY) {

    data class Fingerprint(val title: String?, val text: String?, val bigText: String?)

    private val lastSeen = object : LinkedHashMap<String, Fingerprint>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Fingerprint>?): Boolean = size > capacity
    }

    /** True when [event] carries exactly the text last remembered for its key. */
    @Synchronized
    fun isDuplicate(event: NotificationEvent): Boolean = lastSeen[event.key] == event.fingerprint()

    @Synchronized
    fun remember(event: NotificationEvent) {
        lastSeen[event.key] = event.fingerprint()
    }

    @Synchronized
    fun forget(key: String) {
        lastSeen.remove(key)
    }

    @get:Synchronized
    val size: Int get() = lastSeen.size

    private fun NotificationEvent.fingerprint() = Fingerprint(title, text, bigText)

    companion object {
        const val DEFAULT_CAPACITY = 512
    }
}
