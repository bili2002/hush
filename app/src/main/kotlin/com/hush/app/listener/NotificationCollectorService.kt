package com.hush.app.listener

import android.content.ComponentName
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * The one OS-facing piece. Thin glue: every callback is a single launch into the application scope
 * (never a service scope: `onDestroy` must not cancel an in-flight insert) that delegates to
 * [NotificationIngest]. Fields are injected by Hilt in `onCreate`; nothing runs in the constructor.
 *
 * The FQCN `com.hush.app.listener.NotificationCollectorService` is referenced by name elsewhere
 * (the sync issue's `requestRebind`); do not rename.
 */
@AndroidEntryPoint
class NotificationCollectorService : NotificationListenerService() {

    @Inject lateinit var ingest: NotificationIngest

    override fun onListenerConnected() {
        Log.i(TAG, "listener connected")
        // Read while still on the binder thread: the binding can go away before the coroutine runs.
        val active: List<StatusBarNotification> = try {
            activeNotifications?.toList().orEmpty()
        } catch (e: RuntimeException) {
            Log.w(TAG, "getActiveNotifications failed: ${e.javaClass.simpleName}")
            emptyList()
        }
        ingest.launch {
            ingest.onConnected()
            ingest.onBackfill(active)
        }
    }

    override fun onListenerDisconnected() {
        Log.i(TAG, "listener disconnected; requesting rebind")
        ingest.launch { ingest.onDisconnected() }
        requestRebind(ComponentName(this, NotificationCollectorService::class.java))
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?, rankingMap: RankingMap?) {
        sbn ?: return
        val importance = importanceOf(sbn.key, rankingMap)
        ingest.launch { ingest.onPosted(sbn, importance) { key -> cancelNotification(key) } }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?, rankingMap: RankingMap?, reason: Int) {
        sbn ?: return
        ingest.launch { ingest.onRemoved(sbn, reason) }
    }

    /** Null when the ranking map is absent, empty, or does not know the key. Never throws. */
    private fun importanceOf(key: String, rankingMap: RankingMap?): Int? {
        rankingMap ?: return null
        return try {
            val ranking = Ranking()
            if (rankingMap.getRanking(key, ranking)) ranking.importance else null
        } catch (e: RuntimeException) {
            null
        }
    }

    private companion object {
        const val TAG = "HushListener"
    }
}
