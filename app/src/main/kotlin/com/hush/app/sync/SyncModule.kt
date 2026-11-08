package com.hush.app.sync

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** The Sync issue replaces this binding here and adds its `@IntoSet AppInitializer` here. */
@Module
@InstallIn(SingletonComponent::class)
object SyncModule {
    @Provides
    @Singleton
    fun syncScheduler(): SyncScheduler = NoopSyncScheduler
}
