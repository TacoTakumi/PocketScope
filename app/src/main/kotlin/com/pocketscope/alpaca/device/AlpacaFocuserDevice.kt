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
}
