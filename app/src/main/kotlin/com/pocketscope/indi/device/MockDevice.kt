package com.pocketscope.indi.device

import com.pocketscope.indi.properties.IndiProperty
import com.pocketscope.indi.properties.PropertyState
import com.pocketscope.indi.properties.SwitchProperty

/**
 * Simulated INDI camera device for testing the server framework.
 *
 * Exposes a CONNECTION switch property that INDI clients (Ekos/KStars)
 * expect from every device. Global singleton per Context Decision D-04:
 * devices represent physical hardware and share state across all clients.
 */
class MockDevice {

    companion object {
        /** Global singleton — devices are shared across all client sessions (D-04). */
        val instance = MockDevice()
    }

    val deviceName = "Mock Camera"

    val properties = mutableListOf<IndiProperty>()

    private val connectionSwitch = SwitchProperty(
        device = deviceName,
        name = "CONNECTION",
        label = "Connection",
        group = "Main",
        initialState = PropertyState.Idle,
        rule = "OneOfMany",
        options = mutableMapOf("CONNECT" to false, "DISCONNECT" to true)
    )

    init {
        properties.add(connectionSwitch)
    }
}
