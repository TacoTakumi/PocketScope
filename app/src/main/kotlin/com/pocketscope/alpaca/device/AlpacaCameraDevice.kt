package com.pocketscope.alpaca.device

import com.pocketscope.BuildConfig
import com.pocketscope.device.CaptureDevice

/**
 * Alpaca Camera device wrapping PocketScope's CaptureDevice interface.
 *
 * Each physical lens (Ultrawide, Main, Tele) gets its own AlpacaCameraDevice
 * instance, identified by deviceNumber and uniqueId.
 */
class AlpacaCameraDevice(
    val captureDevice: CaptureDevice,
    deviceNumber: Int,
    uniqueId: String
) : AlpacaDevice(
    deviceName = "PocketScope ${captureDevice.lensInfo.lensType} Camera",
    deviceType = "Camera",
    deviceNumber = deviceNumber,
    uniqueId = uniqueId
) {
    override val description: String =
        "PocketScope Android Camera - ${captureDevice.lensInfo.lensType} Lens " +
                "(${captureDevice.lensInfo.focalLength}mm f/${captureDevice.lensInfo.aperture ?: "??"})"

    override val driverInfo: String = "PocketScope Android Camera Driver"

    override val driverVersion: String = BuildConfig.VERSION_NAME
}
