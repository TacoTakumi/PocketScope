package com.pocketscope.device

import android.os.Handler
import com.pocketscope.camera.CameraSessionContract
import com.pocketscope.camera.LensInfo
import com.pocketscope.camera.RawCaptureSession
import kotlinx.coroutines.sync.Mutex

class CaptureDeviceImpl(
    override val lensInfo: LensInfo,
    private val sessionManager: CameraSessionContract,
    private val rawCaptureSession: RawCaptureSession,
    private val handler: Handler
) : CaptureDevice {

    private val captureMutex = Mutex()

    override val isBusy: Boolean get() = captureMutex.isLocked

    override suspend fun capture(exposureNanos: Long, isoValue: Int): CaptureOutcome {
        if (!captureMutex.tryLock()) return CaptureOutcome.Busy
        return try {
            val cameraDevice = sessionManager.switchToLens(
                lensInfo.physicalCameraId, lensInfo.logicalCameraId
            ) ?: return CaptureOutcome.Error(IllegalStateException("No camera device available"))
            
            val result = rawCaptureSession.capture(cameraDevice, lensInfo, exposureNanos, isoValue)
            CaptureOutcome.Success(result)
        } catch (e: Exception) {
            CaptureOutcome.Error(e)
        } finally {
            captureMutex.unlock()
        }
    }
}
