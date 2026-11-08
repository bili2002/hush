package com.hush.app.focus

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.hush.app.di.ApplicationScope
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Slot alarm target. Glue only: hands off to [Deliverer] under `goAsync`. */
@AndroidEntryPoint
class DeliveryReceiver : BroadcastReceiver() {

    @Inject lateinit var deliverer: Deliverer

    @Inject @ApplicationScope lateinit var scope: CoroutineScope

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_DELIVER) return
        val pending = goAsync()
        scope.launch {
            try {
                deliverer.deliverDue()
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_DELIVER = "com.hush.app.focus.DELIVER"
    }
}
