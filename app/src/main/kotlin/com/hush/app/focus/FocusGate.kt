package com.hush.app.focus

import com.hush.app.db.NotificationEvent

enum class Decision { PASS_THROUGH, HOLD }

/** Decides, before insert, whether an incoming event is held. The listener calls this once per event. Bound to [HoldGate]. */
fun interface FocusGate {
    suspend fun decide(event: NotificationEvent): Decision
}
