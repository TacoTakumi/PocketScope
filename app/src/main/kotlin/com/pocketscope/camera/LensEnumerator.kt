package com.pocketscope.camera

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.graphics.Rect
import android.util.Size
import android.util.SizeF

/**
 * Enumerates all rear-facing physical camera lenses from Camera2.
 *
 * Strategy per RESEARCH.md pitfall 2:
 * 1. Find rear-facing logical cameras in cameraIdList
 * 2. For logical multi-cameras, enumerate physicalCameraIds
 * 3. Check if each physical ID also appears in cameraIdList (can open standalone)
 * 4. If not in cameraIdList, record logicalCameraId for routing via OutputConfiguration
 *
 * Returns LensInfo list sorted by focal length (ultrawide -> main -> tele).
 */
object LensEnumerator {

    /**
     * Enumerates all rear-facing physical camera lenses.
     *
     * @param cameraManager the system CameraManager service
     * @return sorted list of LensInfo (by focal length ascending)
     */
    fun enumerateLenses(cameraManager: CameraManager): List<LensInfo> {
        val lenses = mutableListOf<LensInfo>()
        val standaloneIds = cameraManager.cameraIdList.toSet()

        for (cameraId in cameraManager.cameraIdList) {
            val chars = cameraManager.getCameraCharacteristics(cameraId)
            val facing = chars.get(CameraCharacteristics.LENS_FACING)
            if (facing != CameraCharacteristics.LENS_FACING_BACK) continue

            val caps = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
                ?: intArrayOf()
            if (CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA in caps) {
                // Logical multi-camera: enumerate physical sub-cameras
                for (physicalId in chars.physicalCameraIds) {
                    val physChars = cameraManager.getCameraCharacteristics(physicalId)
                    val logicalId = if (physicalId in standaloneIds) null else cameraId
                    lenses.add(buildLensInfo(physicalId, logicalId, physChars))
                }
            } else {
                // Single camera, not part of a logical group
                lenses.add(buildLensInfo(cameraId, null, chars))
            }
        }

        return lenses.sortedBy { it.focalLength }
    }

    private fun buildLensInfo(
        cameraId: String,
        logicalCameraId: String?,
        chars: CameraCharacteristics
    ): LensInfo {
        val focalLengths = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
            ?: floatArrayOf(0f)
        val pixelArray = chars.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)
            ?: Size(0, 0)
        val physicalSize = chars.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
            ?: SizeF(0f, 0f)
        val activeArray = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
            ?: Rect()

        val cfa = chars.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT) ?: 0
        val apertures = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES)

        return LensInfo(
            physicalCameraId = cameraId,
            logicalCameraId = logicalCameraId,
            focalLength = focalLengths[0],
            pixelArraySize = pixelArray,
            physicalSensorSize = physicalSize,
            pixelSizeX = if (pixelArray.width > 0)
                (physicalSize.width / pixelArray.width) * 1000f else 0f,
            pixelSizeY = if (pixelArray.height > 0)
                (physicalSize.height / pixelArray.height) * 1000f else 0f,
            isoRange = chars.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE),
            exposureTimeRange = chars.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE),
            minFocusDistance = chars.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE),
            maxDigitalZoom = chars.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM),
            activeArraySize = activeArray,
            cfaArrangement = cfa,
            aperture = apertures?.firstOrNull()
        )
    }
}
