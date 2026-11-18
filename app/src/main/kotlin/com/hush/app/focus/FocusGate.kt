package com.hush.app.focus

import com.hush.app.db.NotificationEvent

enum class Decision {
    PASS_THROUGH,

    /** Hidden now, delivered in a digest later. */
    HOLD,

    /** Hidden now and kept out of digests for as long as its rule stays "Muted". */
    MUTE,
}

/** Decides, before insert, whether an incoming event is held or muted. The listener calls this once per event. Bound to [HoldGate]. */
fun interface FocusGate {
    suspend fun decide(event: NotificationEvent): Decision
}
