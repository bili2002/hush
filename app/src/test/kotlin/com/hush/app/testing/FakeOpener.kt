package com.hush.app.testing

import com.hush.app.db.NotificationEvent
import com.hush.app.open.NotificationOpener
import com.hush.app.open.OpenResult

/** Records what was opened; answers [result]. */
class FakeOpener(var result: OpenResult = OpenResult.ORIGINAL) : NotificationOpener {
    val opened = mutableListOf<String>()

    override fun open(event: NotificationEvent): OpenResult {
        opened += event.id
        return result
    }
}
