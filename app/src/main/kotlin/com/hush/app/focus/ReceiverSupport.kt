package com.hush.app.focus

import android.content.BroadcastReceiver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Keeps the receiver alive (`goAsync`) for one [Deliverer.deliverDue] run, then finishes it. */
internal fun BroadcastReceiver.deliverDueAsync(scope: CoroutineScope, deliverer: Deliverer) {
    val pending = goAsync()
    scope.launch {
        try {
            deliverer.deliverDue()
        } finally {
            pending.finish()
        }
    }
}
