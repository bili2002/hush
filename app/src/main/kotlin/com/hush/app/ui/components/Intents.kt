package com.hush.app.ui.components

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.provider.Settings

/** The hosting [Activity] behind a Compose [Context], or null outside one (previews, tests). */
fun Context.findActivity(): Activity? {
    var ctx: Context = this
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}

/**
 * Starts [intent] and reports whether it launched. Some OEM builds ship without a given settings
 * page ([ActivityNotFoundException]) or deny it ([SecurityException]); callers show a line saying
 * so instead of silently doing nothing.
 */
fun Context.startActivitySafely(intent: Intent): Boolean = try {
    startActivity(intent)
    true
} catch (e: ActivityNotFoundException) {
    false
} catch (e: SecurityException) {
    false
}

/** The app's own notification settings page (minSdk 26, so the O+ action is always available). */
fun appNotificationSettingsIntent(context: Context): Intent =
    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
