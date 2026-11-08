package com.hush.app.focus

import android.app.Application
import com.hush.app.AppInitializer
import com.hush.app.di.ApplicationScope
import com.hush.app.settings.SettingsStore
import java.time.Clock
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Re-arms the slot alarm at process start while Focus is on. A force-stop (or a crash with a
 * missed `MY_PACKAGE_REPLACED`) clears our alarms; the next process start, usually the system
 * rebinding the listener, restores it so held rows never sit without a scheduled delivery.
 */
class FocusInitializer @Inject constructor(
    private val settings: SettingsStore,
    private val scheduler: DeliveryScheduler,
    private val clock: Clock,
    @ApplicationScope private val scope: CoroutineScope,
) : AppInitializer {
    override fun init(app: Application) {
        scope.launch {
            if (settings.current().focusEnabled) scheduler.schedule(clock.millis())
        }
    }
}
