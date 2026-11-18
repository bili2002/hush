package com.hush.app.focus

import android.app.Application
import android.util.Log
import com.hush.app.AppInitializer
import com.hush.app.di.ApplicationScope
import com.hush.app.settings.SettingsStore
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * One delivery run at process start while Focus is on or a profile schedule exists. A force-stop
 * (or a crash with a missed `MY_PACKAGE_REPLACED`) clears our alarms; the next process start,
 * usually the system rebinding the listener, restores them. A run rather than a bare re-arm: the
 * alarm that was lost may have been a schedule start or end that is already overdue, and only a
 * run applies it (and then arms the alarm for what comes next).
 */
class FocusInitializer @Inject constructor(
    private val settings: SettingsStore,
    private val deliverer: Deliverer,
    @ApplicationScope private val scope: CoroutineScope,
) : AppInitializer {
    override fun init(app: Application) {
        scope.launch {
            try {
                val current = settings.current()
                if (current.focusEnabled || current.hasSchedule) deliverer.deliverDue()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Process start must not die on this; the periodic safety net runs the same thing later.
                Log.e("HushFocus", "start-up delivery run failed: ${e.javaClass.simpleName}")
            }
        }
    }
}
