package com.pocketscope.alpaca.device

/**
 * Result of a device-specific method call, used by [AlpacaDevice.handleGet]
 * and [AlpacaDevice.handlePut] to return typed values to the server layer.
 */
sealed class DeviceMethodResult {
    data class IntVal(val value: Int) : DeviceMethodResult()
    data class BoolVal(val value: Boolean) : DeviceMethodResult()
    data class DoubleVal(val value: Double) : DeviceMethodResult()
    data class StringVal(val value: String) : DeviceMethodResult()
    data class StringListVal(val value: List<String>) : DeviceMethodResult()
    data class IntListVal(val value: List<Int>) : DeviceMethodResult()
    /** Method is valid for this device type but not implemented. */
    data class NotImplemented(val method: String) : DeviceMethodResult()
    /** Method is recognized and handled (PUT success with no return value). */
    object Ok : DeviceMethodResult()
    /** Method is recognized but the parameter value is invalid. */
    data class InvalidValue(val message: String) : DeviceMethodResult()
    /** Method is valid but cannot be performed in the current state. */
    data class InvalidOperation(val message: String) : DeviceMethodResult()
    /** Image array data ready for binary (ImageBytes) or JSON transfer. */
    data class ImageData(
        val rawBytes: ByteArray,
        val width: Int,
        val height: Int
    ) : DeviceMethodResult()
    /** Method name is not recognized for this device type. */
    object Unknown : DeviceMethodResult()
}

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

    /** Handle a device-specific GET method. Returns [DeviceMethodResult.Unknown] for unrecognized methods. */
    open fun handleGet(method: String): DeviceMethodResult = DeviceMethodResult.Unknown

    /** Handle a device-specific PUT method. Returns [DeviceMethodResult.Unknown] for unrecognized methods. */
    open fun handlePut(method: String, params: Map<String, String>): DeviceMethodResult = DeviceMethodResult.Unknown
}
