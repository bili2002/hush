package com.hush.app

import android.app.Application
import android.util.Log
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

/** Runs once at process start, after Hilt is ready. Contribute with `@IntoSet` in your own module. */
fun interface AppInitializer {
    fun init(app: Application)
}

@HiltAndroidApp
class HushApp : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory

    @Inject lateinit var initializers: Set<@JvmSuppressWildcards AppInitializer>

    override fun onCreate() {
        super.onCreate()
        // An initializer failure is logged, never fatal: this process also hosts the listener.
        initializers.forEach { init ->
            runCatching { init.init(this) }.onFailure { Log.e("HushApp", "AppInitializer ${init.javaClass.name} failed", it) }
        }
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()
}
