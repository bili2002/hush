package com.hush.app.focus

import com.hush.app.db.NotificationEvent
import com.hush.app.settings.SettingsStore
import java.time.Clock
import java.time.ZoneId
import javax.inject.Inject

/**
 * [FocusGate] backed by the current [Settings][com.hush.app.settings.Settings] and [FocusPolicy].
 * The settings are read as the profile schedule makes them right now ([reconciled]), not as the
 * last alarm left them: the alarm can be minutes late in Doze, and a notification arriving just
 * after a scheduled start must already be held.
 */
class HoldGate @Inject constructor(private val settings: SettingsStore, private val clock: Clock) : FocusGate {
    override suspend fun decide(event: NotificationEvent): Decision =
        FocusPolicy.decide(event, settings.current().reconciled(clock.millis(), ZoneId.systemDefault()))
}
