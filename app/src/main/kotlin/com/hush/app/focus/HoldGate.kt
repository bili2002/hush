package com.hush.app.focus

import com.hush.app.db.NotificationEvent
import com.hush.app.settings.SettingsStore
import javax.inject.Inject

/** [FocusGate] backed by the current [Settings][com.hush.app.settings.Settings] and [FocusPolicy]. */
class HoldGate @Inject constructor(private val settings: SettingsStore) : FocusGate {
    override suspend fun decide(event: NotificationEvent): Decision = FocusPolicy.decide(event, settings.current())
}
