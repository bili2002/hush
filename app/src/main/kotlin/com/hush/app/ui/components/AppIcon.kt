package com.hush.app.ui.components

import android.content.Context
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Process-wide icon cache keyed by package; the PackageManager lookup is the slow part. */
private val iconCache = LruCache<String, ImageBitmap>(96)

private const val ICON_PX = 128

private fun loadAppIcon(context: Context, packageName: String): ImageBitmap? = runCatching {
    context.packageManager.getApplicationIcon(packageName).toBitmap(ICON_PX, ICON_PX).asImageBitmap()
}.getOrNull()

/** The launcher icon of [packageName], or a bell when the app is gone / unknown. */
@Composable
fun AppIcon(packageName: String, modifier: Modifier = Modifier, size: Dp = 40.dp) {
    val context = LocalContext.current
    val inspection = LocalInspectionMode.current
    val bitmap by produceState<ImageBitmap?>(initialValue = iconCache.get(packageName), packageName) {
        if (value == null && !inspection) {
            val loaded = withContext(Dispatchers.IO) { loadAppIcon(context, packageName) }
            if (loaded != null) iconCache.put(packageName, loaded)
            value = loaded
        }
    }
    val sized = modifier.size(size)
    val icon = bitmap
    if (icon != null) {
        Image(bitmap = icon, contentDescription = null, modifier = sized)
    } else {
        Icon(
            Icons.Filled.Notifications,
            contentDescription = null,
            modifier = sized,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
