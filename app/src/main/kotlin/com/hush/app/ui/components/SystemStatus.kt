package com.hush.app.ui.components

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The three OS facts the screens gate on. Read fresh on every call (they change behind our back
 * when the owner flips a system setting), so callers snapshot them on resume.
 */
interface SystemStatus {
    fun listenerEnabled(): Boolean
    fun postNotificationsGranted(): Boolean
    fun ignoringBatteryOptimizations(): Boolean
}

data class SystemSnapshot(
    val listenerEnabled: Boolean,
    val postNotificationsGranted: Boolean,
    val batteryExempt: Boolean,
)

fun SystemStatus.snapshot() = SystemSnapshot(
    listenerEnabled = listenerEnabled(),
    postNotificationsGranted = postNotificationsGranted(),
    batteryExempt = ignoringBatteryOptimizations(),
)

@Singleton
class AndroidSystemStatus @Inject constructor(@ApplicationContext private val context: Context) : SystemStatus {

    override fun listenerEnabled(): Boolean =
        NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)

    /** POST_NOTIFICATIONS exists from API 33; below that posting never needs a runtime grant. */
    override fun postNotificationsGranted(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    override fun ignoringBatteryOptimizations(): Boolean =
        (context.getSystemService(Context.POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(context.packageName)
}

/** Package name → human label. A seam so ViewModel tests never touch [PackageManager]. */
fun interface AppLabelResolver {
    fun label(packageName: String): String
}

@Singleton
class AndroidAppLabelResolver @Inject constructor(@ApplicationContext private val context: Context) : AppLabelResolver {
    private val cache = ConcurrentHashMap<String, String>()

    override fun label(packageName: String): String = cache.getOrPut(packageName) {
        val pm = context.packageManager
        runCatching { pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString() }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: packageName
    }
}
