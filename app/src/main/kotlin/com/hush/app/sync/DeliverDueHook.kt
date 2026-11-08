package com.hush.app.sync

/**
 * Safety-net seam for Focus delivery: the periodic sync calls every bound hook after pushing, so a
 * digest whose alarm was missed (Doze, reboot) still goes out within ~15 minutes.
 *
 * Bound as a Hilt multibinding set ([SyncModule] declares the empty set). The Focus issue contributes
 * its deliverer with `@Binds @IntoSet abstract fun deliverDueHook(impl: ...): DeliverDueHook` in
 * `focus/FocusModule`; nothing in `sync/` needs to change for that.
 */
fun interface DeliverDueHook {
    suspend fun deliverDue()
}
