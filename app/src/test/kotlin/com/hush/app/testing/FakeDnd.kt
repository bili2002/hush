package com.hush.app.testing

import com.hush.app.focus.DndController

/** A Do Not Disturb switch in memory. [failWith] makes every [set] fail with that reason. */
class FakeDnd(var on: Boolean = false, var failWith: String? = null) : DndController {
    val calls = mutableListOf<Boolean>()

    override fun isOn(): Boolean = on

    override fun set(on: Boolean): String? {
        failWith?.let { return it }
        calls += on
        this.on = on
        return null
    }
}
