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

    private val lens get() = captureDevice.lensInfo
    private val sensorWidth get() = lens.pixelArraySize.width
    private val sensorHeight get() = lens.pixelArraySize.height

    // Subframe state (defaults to full sensor)
    var numX: Int = sensorWidth
    var numY: Int = sensorHeight
    var startX: Int = 0
    var startY: Int = 0

    // Gain (ISO) state
    var gain: Int = lens.isoRange?.lower ?: 100

    // Readout mode (only RAW supported)
    var readoutMode: Int = 0

    override fun handleGet(method: String): DeviceMethodResult {
        return when (method) {
            // --- Sensor geometry ---
            "cameraxsize" -> DeviceMethodResult.IntVal(sensorWidth)
            "cameraysize" -> DeviceMethodResult.IntVal(sensorHeight)
            "numx" -> DeviceMethodResult.IntVal(numX)
            "numy" -> DeviceMethodResult.IntVal(numY)
            "startx" -> DeviceMethodResult.IntVal(startX)
            "starty" -> DeviceMethodResult.IntVal(startY)
            "binx" -> DeviceMethodResult.IntVal(1)
            "biny" -> DeviceMethodResult.IntVal(1)
            "maxbinx" -> DeviceMethodResult.IntVal(1)
            "maxbiny" -> DeviceMethodResult.IntVal(1)
            "pixelsizex" -> DeviceMethodResult.DoubleVal(lens.pixelSizeX.toDouble())
            "pixelsizey" -> DeviceMethodResult.DoubleVal(lens.pixelSizeY.toDouble())

            // --- Sensor type / Bayer ---
            "sensortype" -> DeviceMethodResult.IntVal(1) // 1 = Color
            "sensorname" -> DeviceMethodResult.StringVal("${lens.lensType} Sensor")
            "bayeroffsetx" -> DeviceMethodResult.IntVal(0)
            "bayeroffsety" -> DeviceMethodResult.IntVal(0)

            // --- Gain / ISO ---
            "gain" -> DeviceMethodResult.IntVal(gain)
            "gainmin" -> DeviceMethodResult.IntVal(lens.isoRange?.lower ?: 100)
            "gainmax" -> DeviceMethodResult.IntVal(lens.isoRange?.upper ?: 3200)
            "gains" -> DeviceMethodResult.NotImplemented(method)
            "gainmode" -> DeviceMethodResult.IntVal(1) // 1 = GainMinMax mode

            // --- Exposure ---
            "exposuremin" -> DeviceMethodResult.DoubleVal(
                (lens.exposureTimeRange?.lower?.toDouble() ?: 1_000_000.0) / 1_000_000_000.0
            )
            "exposuremax" -> DeviceMethodResult.DoubleVal(
                (lens.exposureTimeRange?.upper?.toDouble() ?: 30_000_000_000.0) / 1_000_000_000.0
            )
            "lastexposureduration" -> DeviceMethodResult.NotImplemented(method)
            "lastexposurestarttime" -> DeviceMethodResult.NotImplemented(method)

            // --- State ---
            "camerastate" -> DeviceMethodResult.IntVal(if (captureDevice.isBusy) 2 else 0)
            "imageready" -> DeviceMethodResult.BoolVal(false)
            "ispulseguiding" -> DeviceMethodResult.BoolVal(false)
            "percentcompleted" -> DeviceMethodResult.IntVal(0)

            // --- Capabilities ---
            "canabortexposure" -> DeviceMethodResult.BoolVal(false)
            "canstopexposure" -> DeviceMethodResult.BoolVal(false)
            "cangetcoolerpower" -> DeviceMethodResult.BoolVal(false)
            "cansetccdtemperature" -> DeviceMethodResult.BoolVal(false)
            "canfastreadout" -> DeviceMethodResult.BoolVal(false)
            "canasymmetricbin" -> DeviceMethodResult.BoolVal(false)
            "canpulseguide" -> DeviceMethodResult.BoolVal(false)
            "hasshutter" -> DeviceMethodResult.BoolVal(false)

            // --- Cooler (not available) ---
            "cooleron" -> DeviceMethodResult.BoolVal(false)
            "coolerpower" -> DeviceMethodResult.NotImplemented(method)
            "ccdtemperature" -> DeviceMethodResult.NotImplemented(method)
            "setccdtemperature" -> DeviceMethodResult.NotImplemented(method)
            "heatsinktemperature" -> DeviceMethodResult.NotImplemented(method)

            // --- Readout ---
            "readoutmode" -> DeviceMethodResult.IntVal(readoutMode)
            "readoutmodes" -> DeviceMethodResult.StringListVal(listOf("Raw"))
            "fastreadout" -> DeviceMethodResult.NotImplemented(method)

            // --- Image data ---
            "imagearray", "imagearrayvariant" ->
                DeviceMethodResult.InvalidOperation("No image available — call StartExposure first")
            "maxadu" -> DeviceMethodResult.IntVal(65535) // 16-bit
            "electronsperadu" -> DeviceMethodResult.NotImplemented(method)
            "fullwellcapacity" -> DeviceMethodResult.NotImplemented(method)

            // --- Offset ---
            "offset" -> DeviceMethodResult.NotImplemented(method)
            "offsetmin" -> DeviceMethodResult.NotImplemented(method)
            "offsetmax" -> DeviceMethodResult.NotImplemented(method)
            "offsets" -> DeviceMethodResult.NotImplemented(method)
            "offsetmode" -> DeviceMethodResult.NotImplemented(method)

            // --- Exposure resolution ---
            "exposureresolution" -> DeviceMethodResult.DoubleVal(0.001) // 1ms resolution

            // --- Sub-exposure ---
            "subexposureduration" -> DeviceMethodResult.NotImplemented(method)

            // --- Device state (V4) ---
            "devicestate" -> DeviceMethodResult.StringListVal(emptyList())

            else -> DeviceMethodResult.Unknown
        }
    }

    override fun handlePut(method: String, params: Map<String, String>): DeviceMethodResult {
        return when (method) {
            "binx" -> {
                val v = findParam(params, "BinX")?.toIntOrNull()
                if (v == null || v != 1) DeviceMethodResult.InvalidValue("BinX must be 1")
                else DeviceMethodResult.Ok
            }
            "biny" -> {
                val v = findParam(params, "BinY")?.toIntOrNull()
                if (v == null || v != 1) DeviceMethodResult.InvalidValue("BinY must be 1")
                else DeviceMethodResult.Ok
            }
            "numx" -> {
                val v = findParam(params, "NumX")?.toIntOrNull()
                    ?: return DeviceMethodResult.InvalidValue("NumX must be an integer")
                if (v < 1 || v > sensorWidth) return DeviceMethodResult.InvalidValue("NumX out of range: $v")
                numX = v
                DeviceMethodResult.Ok
            }
            "numy" -> {
                val v = findParam(params, "NumY")?.toIntOrNull()
                    ?: return DeviceMethodResult.InvalidValue("NumY must be an integer")
                if (v < 1 || v > sensorHeight) return DeviceMethodResult.InvalidValue("NumY out of range: $v")
                numY = v
                DeviceMethodResult.Ok
            }
            "startx" -> {
                val v = findParam(params, "StartX")?.toIntOrNull()
                    ?: return DeviceMethodResult.InvalidValue("StartX must be an integer")
                if (v < 0 || v >= sensorWidth) return DeviceMethodResult.InvalidValue("StartX out of range: $v")
                startX = v
                DeviceMethodResult.Ok
            }
            "starty" -> {
                val v = findParam(params, "StartY")?.toIntOrNull()
                    ?: return DeviceMethodResult.InvalidValue("StartY must be an integer")
                if (v < 0 || v >= sensorHeight) return DeviceMethodResult.InvalidValue("StartY out of range: $v")
                startY = v
                DeviceMethodResult.Ok
            }
            "gain" -> {
                val v = findParam(params, "Gain")?.toIntOrNull()
                    ?: return DeviceMethodResult.InvalidValue("Gain must be an integer")
                val min = lens.isoRange?.lower ?: 100
                val max = lens.isoRange?.upper ?: 3200
                if (v < min || v > max) return DeviceMethodResult.InvalidValue("Gain out of range: $v ($min..$max)")
                gain = v
                DeviceMethodResult.Ok
            }
            "readoutmode" -> {
                val v = findParam(params, "ReadoutMode")?.toIntOrNull()
                    ?: return DeviceMethodResult.InvalidValue("ReadoutMode must be an integer")
                if (v != 0) return DeviceMethodResult.InvalidValue("Only readout mode 0 (Raw) is supported")
                readoutMode = v
                DeviceMethodResult.Ok
            }
            "cooleron" -> DeviceMethodResult.NotImplemented(method)
            "setccdtemperature" -> DeviceMethodResult.NotImplemented(method)
            "fastreadout" -> DeviceMethodResult.NotImplemented(method)
            "offset" -> DeviceMethodResult.NotImplemented(method)
            "subexposureduration" -> DeviceMethodResult.NotImplemented(method)
            "startexposure" -> DeviceMethodResult.NotImplemented(method) // TODO: wire to CaptureDevice
            "stopexposure" -> DeviceMethodResult.NotImplemented(method)
            "abortexposure" -> DeviceMethodResult.NotImplemented(method)
            "pulseguide" -> DeviceMethodResult.NotImplemented(method)
            else -> DeviceMethodResult.Unknown
        }
    }

    private fun findParam(params: Map<String, String>, name: String): String? =
        params[name]
}
