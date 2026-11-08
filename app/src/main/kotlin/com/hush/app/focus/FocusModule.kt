package com.hush.app.focus

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** The Focus issue replaces these two bindings here and nowhere else. */
@Module
@InstallIn(SingletonComponent::class)
abstract class FocusModule {
    @Binds
    @Singleton
    abstract fun focusActions(impl: DefaultFocusActions): FocusActions

    companion object {
        @Provides
        @Singleton
        fun focusGate(): FocusGate = PassThroughGate
    }
}
