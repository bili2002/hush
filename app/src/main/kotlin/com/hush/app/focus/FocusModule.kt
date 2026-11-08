package com.hush.app.focus

import com.hush.app.AppInitializer
import com.hush.app.sync.DeliverDueHook
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class FocusModule {
    @Binds
    @Singleton
    abstract fun focusActions(impl: DefaultFocusActionsImpl): FocusActions

    @Binds
    @Singleton
    abstract fun focusGate(impl: HoldGate): FocusGate

    @Binds
    @IntoSet
    abstract fun focusInitializer(impl: FocusInitializer): AppInitializer

    companion object {
        /** Sync's periodic safety net: the non-forced, due-rule path, never "deliver now". */
        @Provides
        @IntoSet
        fun deliverDueHook(deliverer: Deliverer): DeliverDueHook = DeliverDueHook { deliverer.deliverDue() }
    }
}
