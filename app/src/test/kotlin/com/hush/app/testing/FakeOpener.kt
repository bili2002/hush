package com.hush.app.testing

import com.hush.app.db.NotificationEvent
import com.hush.app.open.NotificationOpener
import com.hush.app.open.OpenResult

/** Records what was opened; answers [result]. [originals] are the ids that still have a live tap target. */
class FakeOpener(var result: OpenResult = OpenResult.ORIGINAL, val originals: MutableSet<String> = mutableSetOf()) : NotificationOpener {
    val opened = mutableListOf<String>()

    override fun hasOriginalTarget(event: NotificationEvent): Boolean = event.id in originals

    override fun open(event: NotificationEvent): OpenResult {
        opened += event.id
        return result
    }
}
