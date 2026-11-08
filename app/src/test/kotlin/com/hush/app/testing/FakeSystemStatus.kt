package com.hush.app.testing

import com.hush.app.ui.components.SystemStatus

/** Mutable OS facts; flip a field, then call the ViewModel's `refreshSystemState()`. */
class FakeSystemStatus(
    var listener: Boolean = true,
    var postPermission: Boolean = true,
    var batteryExempt: Boolean = true,
) : SystemStatus {
    override fun listenerEnabled() = listener
    override fun postNotificationsGranted() = postPermission
    override fun ignoringBatteryOptimizations() = batteryExempt
}
