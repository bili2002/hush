package com.hush.app.sync

interface SyncScheduler {
    fun syncNow()
}

/** Foundation default: no sync. The Sync issue replaces the binding in [SyncModule]. */
object NoopSyncScheduler : SyncScheduler {
    override fun syncNow() = Unit
}
