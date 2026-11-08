package com.hush.app.ui.settings

import com.hush.app.ui.apps.InstalledAppsSource
import com.hush.app.ui.apps.PackageManagerAppsSource
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** Bindings for the Screens B seams (Settings + app picker). Lives here, not in `di/`, per the wave-1 rule. */
@Module
@InstallIn(SingletonComponent::class)
abstract class ScreensBModule {
    @Binds
    abstract fun systemStateProbe(impl: AndroidSystemStateProbe): SystemStateProbe

    @Binds
    abstract fun installedAppsSource(impl: PackageManagerAppsSource): InstalledAppsSource
}
