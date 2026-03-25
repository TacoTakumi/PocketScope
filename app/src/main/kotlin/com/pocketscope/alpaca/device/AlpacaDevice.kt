package com.pocketscope.alpaca.device

/**
 * Abstract base class for ASCOM Alpaca device implementations.
 *
 * Provides common endpoint logic shared by all device types:
 * connected, name, description, driverinfo, driverversion,
 * interfaceversion, supportedactions.
 */
abstract class AlpacaDevice(
    val deviceName: String,
    val deviceType: String,
    val deviceNumber: Int,
    val uniqueId: String
) {
    /**
     * Whether the device is connected. Defaults to true since phone hardware
     * is always physically available -- there is no separate connect step.
     */
    var isConnected: Boolean = true
        protected set

    /** Human-readable description of the device. */
    abstract val description: String

    /** Information about the driver (name/purpose). */
    abstract val driverInfo: String

    /** Driver version string. */
    abstract val driverVersion: String

    /** ASCOM interface version supported (V4 compliance). */
    open val interfaceVersion: Int = 4

    /** List of custom action names supported by this device. */
    fun supportedActions(): List<String> = emptyList()
}
