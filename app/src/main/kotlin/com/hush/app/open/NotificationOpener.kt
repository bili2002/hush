package com.hush.app.open

import android.app.ActivityOptions
import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.hush.app.db.NotificationEvent
import com.hush.app.listener.TapTargets
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton

enum class OpenResult {
    /** The notification's own tap target was fired: the app opens where the notification pointed (the chat). */
    ORIGINAL,

    /** No live target any more; the app's launcher activity was started instead. */
    APP,

    /** Neither worked: the app is uninstalled, disabled, or has no launcher activity. */
    FAILED,
}

/** Opens what a recorded notification pointed at. A seam so ViewModel tests never start activities. */
interface NotificationOpener {
    /** True while the original tap target of [event] (or of its conversation) is still cached. */
    fun hasOriginalTarget(event: NotificationEvent): Boolean

    fun open(event: NotificationEvent): OpenResult
}

@Singleton
class AndroidNotificationOpener @Inject constructor(
    @ApplicationContext private val ctx: Context,
    private val tapTargets: TapTargets,
) : NotificationOpener {

    override fun hasOriginalTarget(event: NotificationEvent): Boolean = tapTargets.find(event)?.let(::opensScreen) == true

    override fun open(event: NotificationEvent): OpenResult {
        val target = tapTargets.find(event)
        if (target != null && opensScreen(target)) {
            if (send(target)) return OpenResult.ORIGINAL
            // One-shot or revoked by its app: it will never work again.
            tapTargets.forget(event, target)
        }
        return if (launch(event.packageName)) OpenResult.APP else OpenResult.FAILED
    }

    /**
     * A broadcast or service target would be "sent" successfully and then show nothing: only the
     * system's own notification tap lets such a trampoline start an activity. Those go to the
     * launcher instead. Before Android 12 the kind cannot be asked, so the target is tried.
     */
    private fun opensScreen(target: PendingIntent): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || target.isActivity

    /**
     * Fires the other app's [PendingIntent]. Hush is the visible app when this runs (a History row
     * was tapped), but from Android 14 a sender has to opt in before its "may start activities"
     * privilege is lent to the intent; without the opt-in the send succeeds and nothing opens.
     */
    private fun send(target: PendingIntent): Boolean = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val options = ActivityOptions.makeBasic()
                .setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
            target.send(ctx, 0, null, null, null, null, options.toBundle())
        } else {
            target.send()
        }
        true
    } catch (e: PendingIntent.CanceledException) {
        false
    } catch (e: RuntimeException) {
        // Another app's token misbehaving must not take the screen (and the listener in this process) down.
        Log.w(TAG, "tap target failed: ${e.javaClass.simpleName}")
        false
    }

    private fun launch(packageName: String): Boolean {
        val intent = try {
            ctx.packageManager.getLaunchIntentForPackage(packageName)
        } catch (e: RuntimeException) {
            Log.w(TAG, "launcher lookup failed for $packageName: ${e.javaClass.simpleName}")
            null
        } ?: return false
        return try {
            ctx.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        } catch (e: ActivityNotFoundException) {
            false
        } catch (e: SecurityException) {
            false
        }
    }

    private companion object {
        const val TAG = "HushOpener"
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class OpenModule {
    @Binds
    abstract fun opener(impl: AndroidNotificationOpener): NotificationOpener
}
