package com.hush.app.di

import android.content.Context
import android.util.Log
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStoreFile
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import com.hush.app.AppInitializer
import com.hush.app.db.HushDatabase
import com.hush.app.settings.DataStoreSettingsStore
import com.hush.app.settings.SettingsStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.Multibinds
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import java.time.Clock
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.json.Json

/** Only the foundation issue edits this file. Feature bindings live in `focus/FocusModule` and `sync/SyncModule`. */
@Module
@InstallIn(SingletonComponent::class)
abstract class AppModule {

    @Multibinds
    abstract fun initializers(): Set<AppInitializer>

    companion object {
        @Provides
        @Singleton
        fun database(@ApplicationContext ctx: Context): HushDatabase =
            HushDatabase(AndroidSqliteDriver(HushDatabase.Schema, ctx, "hush.db"))

        // The DataStore is private to the store: nothing else on the graph should read raw preferences.
        // A corrupt file falls back to defaults rather than crashing every reader.
        @Provides
        @Singleton
        fun settingsStore(@ApplicationContext ctx: Context, @ApplicationScope scope: CoroutineScope): SettingsStore =
            DataStoreSettingsStore(
                PreferenceDataStoreFactory.create(
                    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
                    scope = scope,
                ) { ctx.preferencesDataStoreFile("settings") },
            )

        // No Logging plugin: bodies carry notification content and the bearer token.
        @Provides
        @Singleton
        fun httpClient(): HttpClient = HttpClient(CIO) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }

        @Provides
        @Singleton
        fun clock(): Clock = Clock.systemUTC()

        @Provides
        @Singleton
        @ApplicationScope
        fun applicationScope(): CoroutineScope = CoroutineScope(
            SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, t ->
                Log.e(TAG, "Uncaught exception in application scope", t)
            },
        )

        private const val TAG = "HushApp"
    }
}
