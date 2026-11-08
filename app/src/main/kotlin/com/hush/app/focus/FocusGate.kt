package com.hush.app.focus

import com.hush.app.db.NotificationEvent

enum class Decision { PASS_THROUGH, HOLD }

/** Decides, before insert, whether an incoming event is held. The listener calls this once per event. */
fun interface FocusGate {
    suspend fun decide(event: NotificationEvent): Decision
}

/** Foundation default: nothing is ever held. The Focus issue swaps the binding in [FocusModule]. */
object PassThroughGate : FocusGate {
    override suspend fun decide(event: NotificationEvent): Decision = Decision.PASS_THROUGH
}
