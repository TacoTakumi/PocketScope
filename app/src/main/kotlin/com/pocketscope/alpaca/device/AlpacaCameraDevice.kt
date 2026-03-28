package com.pocketscope.alpaca.device

import android.util.Log
import com.pocketscope.BuildConfig
import com.pocketscope.camera.CaptureResult
import com.pocketscope.device.CaptureDevice
import com.pocketscope.device.CaptureOutcome
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Alpaca Camera device wrapping PocketScope's CaptureDevice interface.
 *
 * Each physical lens (Ultrawide, Main, Tele) gets its own AlpacaCameraDevice
 * instance, identified by deviceNumber and uniqueId.
 */
class AlpacaCameraDevice(
    val captureDevice: CaptureDevice,
    deviceNumber: Int,
    uniqueId: String,
    private val scope: CoroutineScope,
    private val focusDioptersProvider: (() -> Float)? = null
) : AlpacaDevice(
    deviceName = "PocketScope ${captureDevice.lensInfo.lensType} Camera",
    deviceType = "Camera",
    deviceNumber = deviceNumber,
    uniqueId = uniqueId
) {
    companion object {
        private const val TAG = "AlpacaCameraDevice"
        // ASCOM CameraState enum
        private const val CAMERA_IDLE = 0
        private const val CAMERA_WAITING = 1
        private const val CAMERA_EXPOSING = 2
        private const val CAMERA_ERROR = 4
        // ISO 8601 format required by ASCOM
        private val ISO_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")
    }

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

    // Capture state
    private var captureJob: Job? = null
    private var lastCaptureResult: CaptureResult? = null
    private var imageReady: Boolean = false
    private var lastExposureDuration: Double = 0.0
    private var lastExposureStartTime: String = ""
    private var cameraState: Int = CAMERA_IDLE

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
            // ASCOM SensorType: 0=Mono, 1=Color(debayered), 2=RGGB, 3=CMYG, 4=CMYG2, 5=LRGB
            "sensortype" -> DeviceMethodResult.IntVal(2) // 2 = RGGB (raw Bayer)
            "sensorname" -> DeviceMethodResult.StringVal("${lens.lensType} Sensor")
            // Bayer offsets derived from Camera2 CFA: 0=RGGB(0,0), 1=GRBG(1,0), 2=GBRG(0,1), 3=BGGR(1,1)
            "bayeroffsetx" -> DeviceMethodResult.IntVal(if (lens.cfaArrangement == 1 || lens.cfaArrangement == 3) 1 else 0)
            "bayeroffsety" -> DeviceMethodResult.IntVal(if (lens.cfaArrangement == 2 || lens.cfaArrangement == 3) 1 else 0)

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
            "lastexposureduration" -> {
                if (lastExposureDuration > 0.0) DeviceMethodResult.DoubleVal(lastExposureDuration)
                else DeviceMethodResult.InvalidOperation("No prior exposure")
            }
            "lastexposurestarttime" -> {
                if (lastExposureStartTime.isNotEmpty()) DeviceMethodResult.StringVal(lastExposureStartTime)
                else DeviceMethodResult.InvalidOperation("No prior exposure")
            }

            // --- State ---
            "camerastate" -> DeviceMethodResult.IntVal(cameraState)
            "imageready" -> DeviceMethodResult.BoolVal(imageReady)
            "ispulseguiding" -> DeviceMethodResult.BoolVal(false)
            "percentcompleted" -> DeviceMethodResult.IntVal(
                if (cameraState == CAMERA_EXPOSING) 50 else if (imageReady) 100 else 0
            )

            // --- Capabilities ---
            "canabortexposure" -> DeviceMethodResult.BoolVal(true)
            "canstopexposure" -> DeviceMethodResult.BoolVal(true)
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
            "imagearray", "imagearrayvariant" -> {
                val result = lastCaptureResult
                if (!imageReady || result == null) {
                    DeviceMethodResult.InvalidOperation("No image available — call StartExposure first")
                } else {
                    DeviceMethodResult.ImageData(
                        rawBytes = result.rawBytes,
                        width = result.width,
                        height = result.height
                    )
                }
            }
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
            "startexposure" -> handleStartExposure(params)
            "stopexposure" -> handleAbortExposure()
            "abortexposure" -> handleAbortExposure()
            "pulseguide" -> DeviceMethodResult.NotImplemented(method)
            else -> DeviceMethodResult.Unknown
        }
    }

    private fun handleStartExposure(params: Map<String, String>): DeviceMethodResult {
        val duration = findParam(params, "Duration")?.toDoubleOrNull()
            ?: return DeviceMethodResult.InvalidValue("Duration must be a number (seconds)")

        val exposureMin = (lens.exposureTimeRange?.lower?.toDouble() ?: 1_000_000.0) / 1_000_000_000.0
        val exposureMax = (lens.exposureTimeRange?.upper?.toDouble() ?: 30_000_000_000.0) / 1_000_000_000.0
        if (duration < exposureMin || duration > exposureMax) {
            return DeviceMethodResult.InvalidValue(
                "Duration out of range: $duration (${exposureMin}..${exposureMax})"
            )
        }

        if (cameraState == CAMERA_EXPOSING) {
            return DeviceMethodResult.InvalidOperation("Exposure already in progress")
        }

        // Clear previous image and start capture
        imageReady = false
        lastCaptureResult = null
        cameraState = CAMERA_EXPOSING
        lastExposureDuration = duration
        lastExposureStartTime = Instant.now().atZone(ZoneOffset.UTC).format(ISO_FORMATTER)

        val exposureNanos = (duration * 1_000_000_000.0).toLong()
        val isoValue = gain
        val focusDistance = focusDioptersProvider?.invoke() ?: 0.0f

        captureJob = scope.launch(Dispatchers.IO) {
            Log.i(TAG, "[${deviceName}] Starting exposure: ${duration}s ISO=$isoValue focus=$focusDistance")
            val outcome = captureDevice.capture(exposureNanos, isoValue, focusDistance)
            when (outcome) {
                is CaptureOutcome.Success -> {
                    Log.i(TAG, "[${deviceName}] Capture complete: ${outcome.result.width}x${outcome.result.height}")
                    lastCaptureResult = outcome.result
                    imageReady = true
                    cameraState = CAMERA_IDLE
                }
                is CaptureOutcome.Busy -> {
                    Log.w(TAG, "[${deviceName}] Capture busy, retrying after 500ms")
                    kotlinx.coroutines.delay(500)
                    val retry = captureDevice.capture(exposureNanos, isoValue, focusDistance)
                    if (retry is CaptureOutcome.Success) {
                        lastCaptureResult = retry.result
                        imageReady = true
                        cameraState = CAMERA_IDLE
                    } else {
                        Log.e(TAG, "[${deviceName}] Capture retry failed: $retry")
                        cameraState = CAMERA_ERROR
                    }
                }
                is CaptureOutcome.Error -> {
                    Log.e(TAG, "[${deviceName}] Capture failed", outcome.cause)
                    cameraState = CAMERA_ERROR
                }
            }
        }

        return DeviceMethodResult.Ok
    }

    private fun handleAbortExposure(): DeviceMethodResult {
        captureJob?.cancel()
        captureJob = null
        cameraState = CAMERA_IDLE
        return DeviceMethodResult.Ok
    }

    private fun findParam(params: Map<String, String>, name: String): String? =
        params[name]
}
