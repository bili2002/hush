package com.hush.app.open

import android.app.Application
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ResolveInfo
import com.hush.app.listener.TapTargets
import com.hush.app.listener.appContext
import com.hush.app.testing.event
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class NotificationOpenerTest {

    private val ctx = appContext()
    private val app = shadowOf(ctx as Application)
    private val targets = TapTargets()
    private val opener = AndroidNotificationOpener(ctx, targets)

    private fun target(action: String): PendingIntent =
        PendingIntent.getActivity(ctx, action.hashCode(), Intent(action).setPackage("com.example.alpha"), PendingIntent.FLAG_IMMUTABLE)

    @Suppress("DEPRECATION")
    private fun installLauncher(packageName: String) {
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(packageName)
        val info = ResolveInfo().apply {
            activityInfo = ActivityInfo().apply {
                this.packageName = packageName
                name = "$packageName.MainActivity"
            }
        }
        shadowOf(ctx.packageManager).addResolveInfoForIntent(launcher, info)
    }

    @Test
    fun `a cached tap target is fired and reported as the original`() {
        val row = event("a")
        targets.remember(row, target("com.example.OPEN_CHAT"))

        assertTrue(opener.hasOriginalTarget(row))
        assertEquals(OpenResult.ORIGINAL, opener.open(row))
        assertEquals("com.example.OPEN_CHAT", assertNotNull(app.nextStartedActivity).action)
    }

    @Test
    fun `another row of the same conversation reuses the newest target, a same-titled stranger does not`() {
        val newest = event("new", conversationId = "c1")
        targets.remember(newest, target("com.example.OPEN_CHAT"))

        assertTrue(opener.hasOriginalTarget(event("older", conversationId = "c1")))
        assertFalse(opener.hasOriginalTarget(event("other-app", packageName = "com.example.beta", conversationId = "c1")))
        assertFalse(opener.hasOriginalTarget(event("no-conversation", title = newest.title)))
    }

    @Test
    fun `without a target the app's launcher activity is started`() {
        installLauncher("com.example.alpha")

        assertEquals(OpenResult.APP, opener.open(event("a")))
        assertEquals("com.example.alpha.MainActivity", assertNotNull(app.nextStartedActivity).component?.className)
    }

    @Test
    fun `a cancelled target falls back to the launcher and is forgotten`() {
        installLauncher("com.example.alpha")
        val row = event("a", conversationId = "c1")
        targets.remember(row, target("com.example.OPEN_CHAT").also { it.cancel() })

        assertEquals(OpenResult.APP, opener.open(row))
        assertEquals("com.example.alpha.MainActivity", assertNotNull(app.nextStartedActivity).component?.className)
        assertFalse(opener.hasOriginalTarget(row))
    }

    @Test
    fun `a broadcast target cannot open a screen, so the launcher is used and no chat is promised`() {
        installLauncher("com.example.alpha")
        val row = event("a")
        targets.remember(row, PendingIntent.getBroadcast(ctx, 7, Intent("com.example.TRAMPOLINE"), PendingIntent.FLAG_IMMUTABLE))

        assertFalse(opener.hasOriginalTarget(row))
        assertEquals(OpenResult.APP, opener.open(row))
        assertEquals("com.example.alpha.MainActivity", assertNotNull(app.nextStartedActivity).component?.className)
    }

    @Test
    fun `no target and no launcher is a failure, and nothing is started`() {
        assertEquals(OpenResult.FAILED, opener.open(event("a", packageName = "com.example.gone")))
        assertNull(app.nextStartedActivity)
    }
}
