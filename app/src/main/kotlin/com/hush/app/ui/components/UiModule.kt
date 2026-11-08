package com.hush.app.ui.components

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** UI-side seams (OS status, app labels). Lives here, not in `di/`, because only the screens use them. */
@Module
@InstallIn(SingletonComponent::class)
abstract class UiModule {
    @Binds
    abstract fun systemStatus(impl: AndroidSystemStatus): SystemStatus

    @Binds
    abstract fun appLabels(impl: AndroidAppLabelResolver): AppLabelResolver
}
