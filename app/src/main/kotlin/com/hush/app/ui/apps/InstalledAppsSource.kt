package com.hush.app.ui.apps

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class InstalledApp(val packageName: String, val label: String)

/** Launchable apps on the device. Faked in tests; the real one scans the package manager. */
interface InstalledAppsSource {
    suspend fun launchable(): List<InstalledApp>
}

class PackageManagerAppsSource @Inject constructor(
    @ApplicationContext private val ctx: Context,
) : InstalledAppsSource {

    /** Runs on IO: the scan + label loading takes a noticeable fraction of a second on a full phone. */
    override suspend fun launchable(): List<InstalledApp> = withContext(Dispatchers.IO) {
        val pm = ctx.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolved = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(intent, 0)
        }
        resolved
            .asSequence()
            .map { it.activityInfo.packageName to it }
            // Hush itself is never held or excluded (DECISIONS), so it has no business in either list.
            .filter { (pkg, _) -> pkg != ctx.packageName }
            .distinctBy { (pkg, _) -> pkg }
            .map { (pkg, ri) ->
                val label = runCatching { ri.loadLabel(pm)?.toString() }.getOrNull()
                InstalledApp(pkg, label?.takeIf { it.isNotBlank() } ?: pkg)
            }
            .toList()
    }
}
