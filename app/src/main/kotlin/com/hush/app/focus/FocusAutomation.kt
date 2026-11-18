package com.hush.app.focus

import android.app.NotificationManager
import android.content.Context
import com.hush.app.R
import com.hush.app.settings.Settings
import com.hush.app.settings.SettingsStore
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/** The phone's Do Not Disturb switch. A seam: tests never touch the real one. */
interface DndController {
    /** True when Do Not Disturb is on, whoever turned it on. */
    fun isOn(): Boolean

    /** Turns Do Not Disturb on or off. Returns the reason it could not, or null when done. */
    fun set(on: Boolean): String?
}

@Singleton
class AndroidDndController @Inject constructor(@ApplicationContext private val ctx: Context) : DndController {
    private val nm get() = ctx.getSystemService(NotificationManager::class.java)

    override fun isOn(): Boolean = nm.currentInterruptionFilter.let {
        it != NotificationManager.INTERRUPTION_FILTER_ALL && it != NotificationManager.INTERRUPTION_FILTER_UNKNOWN
    }

    /**
     * "Priority only": whatever the owner allowed through Do Not Disturb (starred contacts, alarms)
     * still gets through. An app with notification access normally has this right already; where
     * it does not, the reason says where to grant it.
     */
    override fun set(on: Boolean): String? = try {
        if (!nm.isNotificationPolicyAccessGranted) {
            ctx.getString(R.string.focus_dnd_no_access)
        } else {
            nm.setInterruptionFilter(if (on) NotificationManager.INTERRUPTION_FILTER_PRIORITY else NotificationManager.INTERRUPTION_FILTER_ALL)
            null
        }
    } catch (e: SecurityException) {
        e.message ?: ctx.getString(R.string.focus_dnd_no_access)
    }
}

/**
 * Brings the stored state in line with the schedule and the phone in line with the stored state.
 * Called at the start of every delivery run ([Deliverer.deliverDue]), which is what the alarm,
 * boot, the periodic safety net and every UI action go through.
 */
@Singleton
class FocusAutomation @Inject constructor(
    private val settings: SettingsStore,
    private val dnd: DndController,
) {
    /** Applies the schedule as of [now] and returns the settings that are then in force. */
    suspend fun apply(now: Long, zone: ZoneId = ZoneId.systemDefault()): Settings {
        if (settings.current().let { it.reconciled(now, zone) != it }) settings.update { it.reconciled(now, zone) }
        syncDnd()
        return settings.current()
    }

    /**
     * Do Not Disturb follows "Focus is on with a profile that asks for it". Hush only ever turns off
     * what it turned on: if the owner already had it on, Hush leaves it alone both ways, and if the
     * owner turns it off while Hush's is running, Hush does not turn it back on.
     */
    private suspend fun syncDnd() {
        val s = settings.current()
        val wanted = s.focusEnabled && s.activeProfile.dnd
        val mine = s.profiles.dndByHush
        val error = when {
            !wanted -> if (mine) dnd.set(false).also { failed -> if (failed == null) mark(byHush = false) } else null
            mine || dnd.isOn() -> null
            else -> dnd.set(true).also { failed -> if (failed == null) mark(byHush = true) }
        }
        // A failure is retried on every run and stays on screen until it works or is no longer wanted.
        if (error != s.profiles.dndError) settings.update { it.copy(profiles = it.profiles.copy(dndError = error)) }
    }

    private suspend fun mark(byHush: Boolean) = settings.update { it.copy(profiles = it.profiles.copy(dndByHush = byHush)) }
}
