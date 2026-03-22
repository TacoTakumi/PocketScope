package com.pocketscope.camera

import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.util.Log
import kotlinx.coroutines.suspendCancellableCoroutine
import java.nio.ByteBuffer
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Manages Camera2 RAW_SENSOR capture with fully manual controls.
 *
 * All auto-processing is disabled (CONTROL_MODE, NOISE_REDUCTION, HOT_PIXEL,
 * LENS_SHADING) to preserve raw Bayer data for astronomy workflows.
 *
 * The [capture] method creates a temporary ImageReader and CameraCaptureSession,
 * triggers a single still capture, extracts raw bytes handling row stride padding,
 * and cleans up all resources.
 *
 * For multi-camera devices, uses [OutputConfiguration.setPhysicalCameraId] to
 * route the capture to the correct physical sensor when the device was opened
 * through a logical camera.
 *
 * @param handler Camera2 callback handler (must be on a background thread)
 */
class RawCaptureSession(private val handler: Handler) {

    companion object {
        private const val TAG = "RawCaptureSession"

        /**
         * Extracts raw pixel bytes from a ByteBuffer, handling row stride padding.
         *
         * Exposed as internal for unit testing the extraction logic independently
         * of Camera2 hardware APIs.
         *
         * @param buffer source buffer containing pixel data (possibly with row padding)
         * @param rowStride bytes per row in the buffer (may be > width * pixelStride due to padding)
         * @param pixelStride bytes per pixel (typically 2 for 16-bit RAW)
         * @param width image width in pixels
         * @param height image height in pixels
         * @return raw pixel bytes with padding stripped
         */
        internal fun extractRawPixels(
            buffer: ByteBuffer,
            rowStride: Int,
            pixelStride: Int,
            width: Int,
            height: Int
        ): ByteArray {
            val rowBytes = width * pixelStride

            return if (rowStride == rowBytes) {
                // Contiguous: no padding, read all at once
                ByteArray(buffer.remaining()).also { buffer.get(it) }
            } else {
                // Padded: read row by row, skipping padding bytes
                val output = ByteArray(rowBytes * height)
                for (row in 0 until height) {
                    buffer.position(row * rowStride)
                    buffer.get(output, row * rowBytes, rowBytes)
                }
                output
            }
        }
    }

    /**
     * Captures a single RAW_SENSOR frame with manual exposure and ISO.
     *
     * @param cameraDevice an opened CameraDevice from CameraSessionManager
     * @param lensInfo metadata for the active lens (provides dimensions)
     * @param exposureNanos exposure time in nanoseconds
     * @param isoValue sensor sensitivity (ISO)
     * @return CaptureResult containing raw 16-bit pixel bytes
     * @throws RuntimeException if capture session configuration or capture fails
     */
    suspend fun capture(
        cameraDevice: CameraDevice,
        lensInfo: LensInfo,
        exposureNanos: Long,
        isoValue: Int
    ): CaptureResult {
        val imageReader = ImageReader.newInstance(
            lensInfo.pixelArraySize.width,
            lensInfo.pixelArraySize.height,
            ImageFormat.RAW_SENSOR,
            2  // maxImages: prevents OOM per IMG-05
        )
        Log.d(TAG, "ImageReader: ${lensInfo.pixelArraySize.width}x${lensInfo.pixelArraySize.height} RAW_SENSOR, physical=${lensInfo.physicalCameraId} logical=${lensInfo.logicalCameraId}")

        try {
            // Create capture session with physical camera routing for multi-camera
            val captureSession = suspendCancellableCoroutine<CameraCaptureSession> { cont ->
                val outputConfig = OutputConfiguration(imageReader.surface)
                if (lensInfo.logicalCameraId != null) {
                    outputConfig.setPhysicalCameraId(lensInfo.physicalCameraId)
                    Log.d(TAG, "Routing output to physical camera ${lensInfo.physicalCameraId}")
                }

                val sessionConfig = SessionConfiguration(
                    SessionConfiguration.SESSION_REGULAR,
                    listOf(outputConfig),
                    Executor { command -> handler.post(command) },
                    object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(session: CameraCaptureSession) {
                            if (cont.isActive) cont.resume(session)
                        }

                        override fun onConfigureFailed(session: CameraCaptureSession) {
                            if (cont.isActive) cont.resumeWithException(
                                RuntimeException("Capture session configuration failed")
                            )
                        }
                    }
                )

                cameraDevice.createCaptureSession(sessionConfig)
            }

            try {
                // Build capture request with all auto-processing disabled
                val captureRequest = cameraDevice.createCaptureRequest(
                    CameraDevice.TEMPLATE_STILL_CAPTURE
                ).apply {
                    addTarget(imageReader.surface)
                    set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_OFF)
                    set(CaptureRequest.SENSOR_EXPOSURE_TIME, exposureNanos)
                    set(CaptureRequest.SENSOR_SENSITIVITY, isoValue)
                    set(CaptureRequest.NOISE_REDUCTION_MODE, CameraMetadata.NOISE_REDUCTION_MODE_OFF)
                    set(CaptureRequest.HOT_PIXEL_MODE, CameraMetadata.HOT_PIXEL_MODE_OFF)
                    set(
                        CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE,
                        CameraMetadata.STATISTICS_LENS_SHADING_MAP_MODE_OFF
                    )
                }.build()

                // Capture and extract raw bytes
                return suspendCancellableCoroutine { cont ->
                    imageReader.setOnImageAvailableListener({ reader ->
                        val image = reader.acquireLatestImage()
                        if (image != null) {
                            try {
                                val result = extractRawBytes(image)
                                if (cont.isActive) cont.resume(result)
                            } catch (e: Exception) {
                                if (cont.isActive) cont.resumeWithException(e)
                            }
                        }
                    }, handler)

                    captureSession.capture(
                        captureRequest,
                        object : CameraCaptureSession.CaptureCallback() {
                            override fun onCaptureFailed(
                                session: CameraCaptureSession,
                                request: CaptureRequest,
                                failure: CaptureFailure
                            ) {
                                if (cont.isActive) cont.resumeWithException(
                                    RuntimeException("Capture failed with reason: ${failure.reason}")
                                )
                            }
                        },
                        handler
                    )
                }
            } finally {
                captureSession.close()
            }
        } finally {
            imageReader.close()
        }
    }

    /**
     * Extracts raw 16-bit pixel bytes from a Camera2 Image, handling row stride padding.
     *
     * The Image is closed immediately after byte extraction to prevent
     * ImageReader queue exhaustion (IMG-05 / Pitfall 2).
     */
    private fun extractRawBytes(image: Image): CaptureResult {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride  // Should be 2 for 16-bit
        val width = image.width
        val height = image.height

        val result = extractRawPixels(buffer, rowStride, pixelStride, width, height)
        image.close()  // CRITICAL: close immediately per IMG-05 / Pitfall 2
        return CaptureResult(result, width, height)
    }
}
