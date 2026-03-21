package com.pocketscope.camera

import android.hardware.camera2.CameraDevice

/**
 * Contract for camera session management.
 *
 * Extracted as an interface so INDI device classes can depend on the contract
 * rather than the concrete [CameraSessionManager], enabling unit testing
 * without Camera2 runtime dependencies.
 */
interface CameraSessionContract {
    /**
     * Switches the active Camera2 session to the specified lens.
     *
     * @param physicalCameraId the physical camera ID for this lens
     * @param logicalCameraId the logical camera ID to open (if non-null)
     * @return the opened [CameraDevice], or null in test contexts
     */
    suspend fun switchToLens(physicalCameraId: String, logicalCameraId: String?): CameraDevice?

    /**
     * Returns the physical camera ID of the currently active lens, or null.
     */
    fun getActiveLensId(): String?

    /**
     * Closes the active camera device and clears all state.
     */
    fun closeAll()
}
