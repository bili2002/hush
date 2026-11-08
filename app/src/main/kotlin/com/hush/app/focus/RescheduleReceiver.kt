package com.hush.app.focus

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.hush.app.di.ApplicationScope
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Alarms are cleared on reboot and on app update, and an RTC alarm drifts when the clock or zone
 * changes. On each of those events: deliver anything overdue and re-arm the slot alarm (both
 * inside [Deliverer.deliverDue]). This is the "never lose" path for held rows.
 */
@AndroidEntryPoint
class RescheduleReceiver : BroadcastReceiver() {

    @Inject lateinit var deliverer: Deliverer

    @Inject @ApplicationScope lateinit var scope: CoroutineScope

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in HANDLED_ACTIONS) return
        val pending = goAsync()
        scope.launch {
            try {
                deliverer.deliverDue()
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        val HANDLED_ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIMEZONE_CHANGED,
            Intent.ACTION_TIME_CHANGED,
        )
    }
}
