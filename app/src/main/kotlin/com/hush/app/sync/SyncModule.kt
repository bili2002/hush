package com.hush.app.sync

import com.hush.app.AppInitializer
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import dagger.multibindings.Multibinds
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class SyncModule {
    @Binds
    @Singleton
    abstract fun syncScheduler(impl: WorkSyncScheduler): SyncScheduler

    @Binds
    @Singleton
    abstract fun hushApi(impl: KtorHushApi): HushApi

    @Binds
    @IntoSet
    abstract fun syncInitializer(impl: SyncInitializer): AppInitializer

    /** Empty by default; the Focus issue contributes its deliverer with `@Binds @IntoSet` in `FocusModule`. */
    @Multibinds
    abstract fun deliverDueHooks(): Set<DeliverDueHook>

    companion object {
        @Provides
        @Singleton
        fun listenerRebinder(): ListenerRebinder = AndroidListenerRebinder
    }
}
