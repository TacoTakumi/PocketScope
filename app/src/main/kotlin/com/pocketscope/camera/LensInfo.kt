package com.pocketscope.camera

import android.graphics.Rect
import android.util.Range
import android.util.Size
import android.util.SizeF

/**
 * Per-lens metadata extracted from Android Camera2 [CameraCharacteristics].
 *
 * A pure data class with no Camera2 runtime dependency -- it only uses Android
 * SDK value types (Rect, Size, etc.) available at compile time. Created here so
 * both the CCD device (Plan 02-02) and Focuser device (Plan 02-03) can depend
 * on it without sequencing constraints.
 *
 * Pixel sizes are in microns, computed as:
 *   pixelSizeX = (physicalSensorSize.width / pixelArraySize.width) * 1000
 *   pixelSizeY = (physicalSensorSize.height / pixelArraySize.height) * 1000
 *
 * These values map directly to INDI CCD_INFO elements:
 * - CCD_MAX_X -> pixelArraySize.width
 * - CCD_MAX_Y -> pixelArraySize.height
 * - CCD_PIXEL_SIZE -> max(pixelSizeX, pixelSizeY)
 * - CCD_PIXEL_SIZE_X -> pixelSizeX
 * - CCD_PIXEL_SIZE_Y -> pixelSizeY
 * - CCD_BITSPERPIXEL -> 16 (raw sensor)
 */
data class LensInfo(
    val physicalCameraId: String,
    val logicalCameraId: String?,       // non-null if must open via logical camera
    val focalLength: Float,             // mm
    val pixelArraySize: Size,           // width x height
    val physicalSensorSize: SizeF,      // mm
    val pixelSizeX: Float,             // microns = (physicalSize.width / pixelArray.width) * 1000
    val pixelSizeY: Float,             // microns
    val isoRange: Range<Int>?,         // from SENSOR_INFO_SENSITIVITY_RANGE
    val exposureTimeRange: Range<Long>?, // nanoseconds, from SENSOR_INFO_EXPOSURE_TIME_RANGE
    val minFocusDistance: Float?,       // diopters (0.0 = infinity only, null = fixed focus)
    val maxDigitalZoom: Float?,        // from SCALER_AVAILABLE_MAX_DIGITAL_ZOOM
    val activeArraySize: Rect,          // from SENSOR_INFO_ACTIVE_ARRAY_SIZE
    val cfaArrangement: Int = 0,          // Camera2 SENSOR_INFO_COLOR_FILTER_ARRANGEMENT constant (0=RGGB)
    val aperture: Float? = null,          // f-number from LENS_INFO_AVAILABLE_APERTURES (null if unavailable)
    val whiteLevel: Int = 1023            // SENSOR_INFO_WHITE_LEVEL — max raw ADU (e.g. 1023 for 10-bit)
) {
    /** Human-readable lens type based on focal length relative to a reference set. */
    val lensType: String get() = when {
        focalLength < 3.0f -> "Ultrawide"
        focalLength < 8.0f -> "Main"
        else -> "Tele"
    }

    /** Aperture diameter in mm = focalLength / fNumber. Null if aperture unknown. */
    val apertureDiameterMm: Float? get() = aperture?.let { focalLength / it }
}
