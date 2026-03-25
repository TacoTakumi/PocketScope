package com.pocketscope.indi.device

import android.util.Log
import com.pocketscope.camera.LensInfo
import com.pocketscope.device.CaptureDevice
import com.pocketscope.device.CaptureOutcome
import com.pocketscope.imaging.BayerPattern
import com.pocketscope.imaging.FitsConverter
import com.pocketscope.indi.properties.BlobProperty
import com.pocketscope.indi.properties.IndiProperty
import com.pocketscope.indi.properties.NumberElement
import com.pocketscope.indi.properties.NumberProperty
import com.pocketscope.indi.properties.NumberVectorProperty
import com.pocketscope.indi.properties.PropertyState
import com.pocketscope.indi.properties.SwitchProperty
import com.pocketscope.indi.properties.TextElement
import com.pocketscope.indi.properties.TextVectorProperty
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * INDI CCD device for a single physical camera lens.
 *
 * Each rear lens (ultrawide, main, telephoto) becomes a separate INDI device
 * with a full CCD property surface: exposure, gain, frame, info, temperature.
 * Sensor metadata (pixel size, resolution, ISO range, exposure range) comes
 * from [LensInfo] which is extracted from Camera2 [CameraCharacteristics] at
 * runtime.
 *
 * Connection management delegates to [CaptureDevice] which ensures
 * only one Camera2 session is active at a time (per D-03).
 *
 * @param captureDevice hardware device representing a specific lens
 * @param scope coroutine scope for async Camera2 operations
 * @param onLensSwitch callback invoked after successful lens connection (per D-14)
 */
class IndiCameraDevice(
    private val captureDevice: CaptureDevice,
    private val scope: CoroutineScope,
    private val onLensSwitch: ((LensInfo) -> Unit)? = null,
    private val blobCallback: (suspend (deviceName: String, fitsBytes: ByteArray) -> Unit)? = null,
    private val onCaptureComplete: ((success: Boolean) -> Unit)? = null,
    private val focusDioptersProvider: (() -> Float)? = null
) : IndiDevice {

    override val deviceName: String = "PocketScope ${captureDevice.lensInfo.lensType}"

    private val _properties = mutableListOf<IndiProperty>()
    override val properties: List<IndiProperty> get() = _properties

    var isConnected: Boolean = true
        private set

    // Exposure range: Camera2 nanoseconds -> INDI seconds (per pitfall 3)
    private val exposureMinSec: Double =
        captureDevice.lensInfo.exposureTimeRange?.lower?.toDouble()?.div(1_000_000_000.0) ?: 0.0001
    private val exposureMaxSec: Double =
        captureDevice.lensInfo.exposureTimeRange?.upper?.toDouble()?.div(1_000_000_000.0) ?: 30.0

    // Gain range: direct ISO mapping (per D-07)
    private val gainMin: Double = captureDevice.lensInfo.isoRange?.lower?.toDouble() ?: 100.0
    private val gainMax: Double = captureDevice.lensInfo.isoRange?.upper?.toDouble() ?: 3200.0

    // Bayer pattern string computed from Camera2 CFA arrangement
    private val bayerPattern: String = BayerPattern.fromCamera2(captureDevice.lensInfo.cfaArrangement)

    // Property references for direct access in handlers
    private val connectionProperty: SwitchProperty
    private val exposureProperty: NumberProperty
    private val gainProperty: NumberProperty
    private val ccdInfoProperty: NumberVectorProperty
    private val ccdFrameProperty: NumberVectorProperty
    private val temperatureProperty: NumberProperty
    private val blobProperty: BlobProperty

    init {
        // CONNECTION switch
        connectionProperty = SwitchProperty(
            device = deviceName,
            name = "CONNECTION",
            label = "Connection",
            group = "Main Control",
            initialState = PropertyState.Ok,
            rule = "OneOfMany",
            options = mutableMapOf("CONNECT" to true, "DISCONNECT" to false),
            perm = "rw"
        )
        _properties.add(connectionProperty)

        // DRIVER_INFO: required by Ekos to identify device type
        _properties.add(TextVectorProperty(
            device = deviceName,
            name = "DRIVER_INFO",
            label = "Driver Info",
            group = "General Info",
            initialState = PropertyState.Idle,
            perm = "ro",
            elements = listOf(
                TextElement("DRIVER_NAME", "Name", deviceName),
                TextElement("DRIVER_EXEC", "Exec", "pocketscope"),
                TextElement("DRIVER_VERSION", "Version", "1.0"),
                TextElement("DRIVER_INTERFACE", "Interface", "2")  // CCD_INTERFACE
            )
        ))

        // CCD_EXPOSURE: in seconds, converted from Camera2 nanoseconds
        exposureProperty = NumberProperty(
            device = deviceName,
            name = "CCD_EXPOSURE",
            label = "Expose",
            group = "Main Control",
            initialState = PropertyState.Idle,
            format = "%g",
            value = 1.0,
            min = exposureMinSec,
            max = exposureMaxSec,
            step = 0.001,
            perm = "rw",
            elementName = "CCD_EXPOSURE_VALUE"
        )
        _properties.add(exposureProperty)

        // CCD_GAIN: direct ISO mapping
        gainProperty = NumberProperty(
            device = deviceName,
            name = "CCD_GAIN",
            label = "Gain",
            group = "Main Control",
            initialState = PropertyState.Idle,
            format = "%.f",
            value = 100.0,
            min = gainMin,
            max = gainMax,
            step = 1.0,
            perm = "rw",
            elementName = "GAIN"
        )
        _properties.add(gainProperty)

        // CCD_INFO: read-only sensor metadata
        ccdInfoProperty = NumberVectorProperty(
            device = deviceName,
            name = "CCD_INFO",
            label = "CCD Information",
            group = "Image Info",
            initialState = PropertyState.Idle,
            perm = "ro",
            elements = mutableListOf(
                NumberElement("CCD_MAX_X", "Max Width", "%.f",
                    captureDevice.lensInfo.pixelArraySize.width.toDouble(), 0.0, 0.0, 0.0),
                NumberElement("CCD_MAX_Y", "Max Height", "%.f",
                    captureDevice.lensInfo.pixelArraySize.height.toDouble(), 0.0, 0.0, 0.0),
                NumberElement("CCD_PIXEL_SIZE", "Pixel Size (um)", "%5.2f",
                    maxOf(captureDevice.lensInfo.pixelSizeX, captureDevice.lensInfo.pixelSizeY).toDouble(), 0.0, 0.0, 0.0),
                NumberElement("CCD_PIXEL_SIZE_X", "Pixel Size X", "%5.2f",
                    captureDevice.lensInfo.pixelSizeX.toDouble(), 0.0, 0.0, 0.0),
                NumberElement("CCD_PIXEL_SIZE_Y", "Pixel Size Y", "%5.2f",
                    captureDevice.lensInfo.pixelSizeY.toDouble(), 0.0, 0.0, 0.0),
                NumberElement("CCD_BITSPERPIXEL", "Bits per Pixel", "%.f",
                    16.0, 0.0, 0.0, 0.0)
            )
        )
        _properties.add(ccdInfoProperty)

        // TELESCOPE_INFO: optics metadata for FOV calculation (KStars auto-reads this)
        val focalLengthMm = captureDevice.lensInfo.focalLength.toDouble()
        val apertureDiameterMm = captureDevice.lensInfo.apertureDiameterMm?.toDouble() ?: 0.0

        _properties.add(NumberVectorProperty(
            device = deviceName,
            name = "TELESCOPE_INFO",
            label = "Telescope Info",
            group = "Options",
            initialState = PropertyState.Idle,
            perm = "ro",
            elements = mutableListOf(
                NumberElement("TELESCOPE_FOCAL_LENGTH", "Focal Length (mm)", "%g",
                    focalLengthMm, 0.0, 10000.0, 0.0),
                NumberElement("TELESCOPE_APERTURE", "Aperture (mm)", "%g",
                    apertureDiameterMm, 0.0, 3000.0, 0.0)
            )
        ))

        // CCD_FRAME: subframe selection
        ccdFrameProperty = NumberVectorProperty(
            device = deviceName,
            name = "CCD_FRAME",
            label = "Frame",
            group = "Image Settings",
            initialState = PropertyState.Idle,
            perm = "rw",
            elements = mutableListOf(
                NumberElement("X", "Left", "%.f",
                    0.0, 0.0, captureDevice.lensInfo.activeArraySize.width().toDouble(), 1.0),
                NumberElement("Y", "Top", "%.f",
                    0.0, 0.0, captureDevice.lensInfo.activeArraySize.height().toDouble(), 1.0),
                NumberElement("WIDTH", "Width", "%.f",
                    captureDevice.lensInfo.activeArraySize.width().toDouble(), 1.0,
                    captureDevice.lensInfo.activeArraySize.width().toDouble(), 1.0),
                NumberElement("HEIGHT", "Height", "%.f",
                    captureDevice.lensInfo.activeArraySize.height().toDouble(), 1.0,
                    captureDevice.lensInfo.activeArraySize.height().toDouble(), 1.0)
            )
        )
        _properties.add(ccdFrameProperty)

        // CCD_TEMPERATURE: read-only, best-effort (per D-08)
        temperatureProperty = NumberProperty(
            device = deviceName,
            name = "CCD_TEMPERATURE",
            label = "Temperature (C)",
            group = "Main Control",
            initialState = PropertyState.Idle,
            format = "%5.2f",
            value = 0.0,
            min = -50.0,
            max = 50.0,
            step = 0.0,
            perm = "ro",
            elementName = "CCD_TEMPERATURE_VALUE"
        )
        _properties.add(temperatureProperty)

        // CCD1: BLOB property for image data
        blobProperty = BlobProperty(
            device = deviceName,
            name = "CCD1",
            label = "Image Data",
            group = "Image Info",
            initialState = PropertyState.Idle,
            perm = "ro"
        )
        _properties.add(blobProperty)
    }

    companion object {
        private const val TAG = "IndiCameraDevice"
    }

    override fun handleNewProperty(propertyName: String, elements: Map<String, String>) {
        Log.d(TAG, "[$deviceName] handleNewProperty: $propertyName elements=$elements")
        when (propertyName) {
            "CONNECTION" -> handleConnection(elements)
            "CCD_EXPOSURE" -> handleExposure(elements)
            "CCD_GAIN" -> handleGain(elements)
            "CCD_FRAME" -> handleFrame(elements)
            else -> { /* unknown property, ignore */ }
        }
    }

    /**
     * Handles CONNECTION switch commands.
     *
     * CONNECTION state tracking and onLensSwitch callback must remain.
     */
    private fun handleConnection(elements: Map<String, String>) {
        val connectValue = elements["CONNECT"]
        if (connectValue == "On") {
            isConnected = true
            connectionProperty.options["CONNECT"] = true
            connectionProperty.options["DISCONNECT"] = false
            connectionProperty.state = PropertyState.Ok
            onLensSwitch?.invoke(captureDevice.lensInfo)
        } else if (elements["DISCONNECT"] == "On") {
            isConnected = false
            connectionProperty.options["CONNECT"] = false
            connectionProperty.options["DISCONNECT"] = true
            connectionProperty.state = PropertyState.Idle
        }
    }

    /**
     * Handles CCD_EXPOSURE value changes and triggers the full capture pipeline.
     *
     * Rejects commands when disconnected (per D-16) or out of range (per D-15).
     * On valid exposure: sets Busy state, captures via CaptureDevice, converts to FITS
     * on Default dispatcher, streams BLOB via callback on IO dispatcher, then
     * sets Ok state. On failure, sets Alert state.
     */
    private fun handleExposure(elements: Map<String, String>) {
        if (!isConnected) {
            exposureProperty.state = PropertyState.Alert
            return
        }
        val valueStr = elements["CCD_EXPOSURE_VALUE"] ?: return
        val seconds = valueStr.toDoubleOrNull() ?: return
        if (seconds < exposureProperty.min || seconds > exposureProperty.max) {
            exposureProperty.state = PropertyState.Alert
            return
        }

        exposureProperty.value = seconds
        exposureProperty.state = PropertyState.Busy

        scope.launch {
            try {
                val exposureNanos = (seconds * 1_000_000_000L).toLong()
                val isoValue = gainProperty.value.toInt()
                val focusDistance = focusDioptersProvider?.invoke() ?: 0.0f

                val outcome = captureDevice.capture(exposureNanos, isoValue, focusDistance)
                when (outcome) {
                    is CaptureOutcome.Success -> {
                        val fitsBytes = withContext(Dispatchers.Default) {
                            FitsConverter.buildFits(
                                rawBytes = outcome.result.rawBytes,
                                width = outcome.result.width,
                                height = outcome.result.height,
                                pixelSizeX = captureDevice.lensInfo.pixelSizeX,
                                pixelSizeY = captureDevice.lensInfo.pixelSizeY,
                                focalLength = captureDevice.lensInfo.focalLength,
                                exposureTimeSec = seconds,
                                isoGain = isoValue,
                                bayerPattern = bayerPattern
                            )
                        }
                        withContext(Dispatchers.IO) {
                            blobCallback?.invoke(deviceName, fitsBytes)
                        }
                        exposureProperty.value = 0.0
                        exposureProperty.state = PropertyState.Ok
                        onCaptureComplete?.invoke(true)
                    }
                    is CaptureOutcome.Busy -> {
                        // Per D-06: INDI reports busy as Alert state
                        exposureProperty.state = PropertyState.Alert
                        onCaptureComplete?.invoke(false)
                    }
                    is CaptureOutcome.Error -> {
                        // Retry once with 500ms delay (INDI-specific retry policy, per D-08)
                        Log.w(TAG, "[$deviceName] Capture failed, retrying", outcome.cause)
                        delay(500)
                        val retryOutcome = captureDevice.capture(exposureNanos, isoValue, focusDistance)
                        when (retryOutcome) {
                            is CaptureOutcome.Success -> {
                                val fitsBytes = withContext(Dispatchers.Default) {
                                    FitsConverter.buildFits(
                                        rawBytes = retryOutcome.result.rawBytes,
                                        width = retryOutcome.result.width,
                                        height = retryOutcome.result.height,
                                        pixelSizeX = captureDevice.lensInfo.pixelSizeX,
                                        pixelSizeY = captureDevice.lensInfo.pixelSizeY,
                                        focalLength = captureDevice.lensInfo.focalLength,
                                        exposureTimeSec = seconds,
                                        isoGain = isoValue,
                                        bayerPattern = bayerPattern
                                    )
                                }
                                withContext(Dispatchers.IO) {
                                    blobCallback?.invoke(deviceName, fitsBytes)
                                }
                                exposureProperty.value = 0.0
                                exposureProperty.state = PropertyState.Ok
                                onCaptureComplete?.invoke(true)
                            }
                            else -> {
                                exposureProperty.state = PropertyState.Alert
                                onCaptureComplete?.invoke(false)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "[$deviceName] Capture pipeline failed", e)
                exposureProperty.state = PropertyState.Alert
                onCaptureComplete?.invoke(false)
            }
        }
    }

    /**
     * Handles CCD_GAIN value changes.
     *
     * Rejects commands when disconnected (per D-16) or out of range (per D-15).
     */
    private fun handleGain(elements: Map<String, String>) {
        if (!isConnected) {
            gainProperty.state = PropertyState.Alert
            return
        }
        val valueStr = elements["GAIN"] ?: return
        val value = valueStr.toDoubleOrNull() ?: return
        if (value < gainProperty.min || value > gainProperty.max) {
            gainProperty.state = PropertyState.Alert
            return
        }
        gainProperty.value = value
        gainProperty.state = PropertyState.Ok
    }

    /**
     * Handles CCD_FRAME element updates.
     *
     * Updates X, Y, WIDTH, HEIGHT elements within bounds.
     */
    private fun handleFrame(elements: Map<String, String>) {
        for ((elemName, valueStr) in elements) {
            val value = valueStr.toDoubleOrNull() ?: continue
            val elem = ccdFrameProperty.getElement(elemName) ?: continue
            if (value < elem.min || value > elem.max) continue
            ccdFrameProperty.setElementValue(elemName, value)
        }
        ccdFrameProperty.state = PropertyState.Ok
    }
}
