package com.hush.app.ui.settings

import android.content.Context
import android.os.PowerManager
import androidx.core.app.NotificationManagerCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/** The two OS facts the Settings screen re-checks on resume. Faked in tests. */
interface SystemStateProbe {
    fun isListenerEnabled(): Boolean
    fun isBatteryExempt(): Boolean
}

class AndroidSystemStateProbe @Inject constructor(
    @ApplicationContext private val ctx: Context,
) : SystemStateProbe {

    // Package-level check on purpose: no dependency on the listener service class (owned by the listener issue).
    override fun isListenerEnabled(): Boolean =
        NotificationManagerCompat.getEnabledListenerPackages(ctx).contains(ctx.packageName)

    override fun isBatteryExempt(): Boolean =
        (ctx.getSystemService(Context.POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(ctx.packageName)
}
