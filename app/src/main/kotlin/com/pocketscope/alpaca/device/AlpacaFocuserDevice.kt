package com.pocketscope.alpaca.device

import com.pocketscope.BuildConfig
import com.pocketscope.device.FocuserDevice

/**
 * Alpaca Focuser device wrapping PocketScope's FocuserDevice interface.
 *
 * A single focuser instance controls focus across all lenses via
 * the shared FocuserDevice diopter-to-step mapping.
 */
class AlpacaFocuserDevice(
    val focuserDevice: FocuserDevice,
    uniqueId: String
) : AlpacaDevice(
    deviceName = "PocketScope Focuser",
    deviceType = "Focuser",
    deviceNumber = 0,
    uniqueId = uniqueId
) {
    override val description: String = "PocketScope Android Focus Controller"

    override val driverInfo: String = "PocketScope Android Focuser Driver"

    override val driverVersion: String = BuildConfig.VERSION_NAME

    private var isMoving = false
    private var tempComp = false

    override fun handleGet(method: String): DeviceMethodResult {
        return when (method) {
            "absolute" -> DeviceMethodResult.BoolVal(true)
            "ismoving" -> DeviceMethodResult.BoolVal(isMoving)
            "maxincrement" -> DeviceMethodResult.IntVal(focuserDevice.maxSteps)
            "maxstep" -> DeviceMethodResult.IntVal(focuserDevice.maxSteps)
            "position" -> DeviceMethodResult.IntVal(focuserDevice.currentPosition)
            "stepsize" -> DeviceMethodResult.NotImplemented(method) // step size in microns not applicable
            "tempcomp" -> DeviceMethodResult.BoolVal(tempComp)
            "tempcompavailable" -> DeviceMethodResult.BoolVal(false)
            "temperature" -> DeviceMethodResult.NotImplemented(method)
            else -> DeviceMethodResult.Unknown
        }
    }

    override fun handlePut(method: String, params: Map<String, String>): DeviceMethodResult {
        return when (method) {
            "halt" -> {
                isMoving = false
                DeviceMethodResult.Ok
            }
            "move" -> {
                val position = findParam(params, "Position")?.toIntOrNull()
                    ?: return DeviceMethodResult.InvalidValue("Position must be an integer")
                if (position < 0 || position > focuserDevice.maxSteps)
                    return DeviceMethodResult.InvalidValue("Position out of range: $position (0..${focuserDevice.maxSteps})")
                focuserDevice.moveAbsolute(position)
                DeviceMethodResult.Ok
            }
            "tempcomp" -> {
                val v = findParam(params, "TempComp")?.lowercase()
                if (v == "true") DeviceMethodResult.InvalidValue("Temperature compensation not available")
                else { tempComp = false; DeviceMethodResult.Ok }
            }
            else -> DeviceMethodResult.Unknown
        }
    }

    private fun findParam(params: Map<String, String>, name: String): String? =
        params[name]
}
