package com.pocketscope.camera

import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.os.Handler
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Enforces that only one Camera2 session is active at a time.
 *
 * When switching lenses, the previous [CameraDevice] is closed before
 * opening the new one. All access is serialized via a [Mutex] to prevent
 * race conditions from concurrent INDI client commands.
 *
 * Per D-03: connecting lens B auto-disconnects lens A.
 * Per D-07/pitfall 1: close old before opening new.
 */
class CameraSessionManager(
    private val cameraManager: CameraManager,
    private val handler: Handler
) : CameraSessionContract {
    private val mutex = Mutex()
    private var activeDevice: CameraDevice? = null
    private var activeLensId: String? = null

    /**
     * Switches the active Camera2 session to the specified lens.
     *
     * If the requested lens is already active, returns the existing [CameraDevice].
     * Otherwise, closes the current device (if any) and opens the new one.
     *
     * @param physicalCameraId the physical camera ID for this lens
     * @param logicalCameraId the logical camera ID to open (if non-null, used instead of physical)
     * @return the opened [CameraDevice]
     * @throws CameraAccessException if the camera cannot be opened
     */
    @Suppress("MissingPermission") // Permission checked at app level before reaching here
    override suspend fun switchToLens(physicalCameraId: String, logicalCameraId: String?): CameraDevice =
        mutex.withLock {
            // If already on the requested lens, return the existing device
            if (activeLensId == physicalCameraId && activeDevice != null) {
                return@withLock activeDevice!!
            }

            // Close old device synchronously under mutex.
            // Camera2's openCamera internally waits for same-physical-camera release.
            // The mutex serializes all access so no concurrent switchToLens can race.
            activeDevice?.close()
            activeDevice = null
            activeLensId = null

            // Open new camera -- use logical ID if available, else physical ID
            val targetId = logicalCameraId ?: physicalCameraId
            val device = cameraManager.openCameraCoroutine(targetId, handler)

            activeDevice = device
            activeLensId = physicalCameraId
            device
        }

    /**
     * Returns the physical camera ID of the currently active lens, or null if none.
     */
    override fun getActiveLensId(): String? = activeLensId

    /**
     * Closes the active camera device and clears all state.
     */
    override fun closeAll() {
        activeDevice?.close()
        activeDevice = null
        activeLensId = null
    }

    /**
     * Opens a camera by ID using a coroutine-friendly suspending wrapper
     * around [CameraManager.openCamera].
     *
     * Resumes the coroutine when the camera is opened, or throws on error/disconnect.
     */
    @Suppress("MissingPermission")
    private suspend fun CameraManager.openCameraCoroutine(
        cameraId: String,
        handler: Handler
    ): CameraDevice = suspendCancellableCoroutine { cont ->
        openCamera(cameraId, object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
                if (cont.isActive) {
                    cont.resume(camera)
                }
            }

            override fun onDisconnected(camera: CameraDevice) {
                camera.close()
                if (cont.isActive) {
                    cont.cancel()
                }
            }

            override fun onError(camera: CameraDevice, error: Int) {
                camera.close()
                if (cont.isActive) {
                    cont.resumeWithException(
                        CameraAccessException(error, "Camera open failed with error code $error")
                    )
                }
            }
        }, handler)

        cont.invokeOnCancellation {
            // Cleanup if the coroutine is cancelled externally
        }
    }
}
