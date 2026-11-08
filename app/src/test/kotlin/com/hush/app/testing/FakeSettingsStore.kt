package com.hush.app.testing

import com.hush.app.settings.Settings
import com.hush.app.settings.SettingsStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/** In-memory [SettingsStore]; [state] is exposed so tests can seed and assert directly. */
class FakeSettingsStore(initial: Settings = Settings()) : SettingsStore {
    val state = MutableStateFlow(initial)

    override val settings: Flow<Settings> get() = state

    override suspend fun current(): Settings = state.value

    override suspend fun update(block: (Settings) -> Settings) = state.update(block)
}
