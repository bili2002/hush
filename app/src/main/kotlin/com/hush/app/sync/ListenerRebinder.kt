package com.hush.app.sync

import android.content.ComponentName
import android.content.Context
import android.service.notification.NotificationListenerService

/** Seam for the listener watchdog so [SyncWorker] tests can assert the rebind without a real service. */
fun interface ListenerRebinder {
    fun requestRebind(ctx: Context)
}

/** Asks the OS to (re)bind our listener. A no-op when it is already bound. */
object AndroidListenerRebinder : ListenerRebinder {
    const val COLLECTOR_CLASS = "com.hush.app.listener.NotificationCollectorService"

    override fun requestRebind(ctx: Context) {
        NotificationListenerService.requestRebind(ComponentName(ctx, COLLECTOR_CLASS))
    }
}
