package com.hush.app.sync

/** Triggers a sync as soon as the network allows. Bound to [WorkSyncScheduler] in [SyncModule]. */
interface SyncScheduler {
    fun syncNow()
}
