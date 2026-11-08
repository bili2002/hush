package com.hush.app.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
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

        @Provides
        @Singleton
        fun preferences(@ApplicationContext ctx: Context, @ApplicationScope scope: CoroutineScope): DataStore<Preferences> =
            PreferenceDataStoreFactory.create(scope = scope) { ctx.preferencesDataStoreFile("settings") }

        @Provides
        @Singleton
        fun settingsStore(prefs: DataStore<Preferences>): SettingsStore = DataStoreSettingsStore(prefs)

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
        fun applicationScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
