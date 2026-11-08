package com.hush.app.focus

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** Focus bindings. The foundation's no-op [DefaultFocusActions] / [PassThroughGate] are superseded here. */
@Module
@InstallIn(SingletonComponent::class)
abstract class FocusModule {
    @Binds
    @Singleton
    abstract fun focusActions(impl: DefaultFocusActionsImpl): FocusActions

    @Binds
    @Singleton
    abstract fun focusGate(impl: HoldGate): FocusGate
}
