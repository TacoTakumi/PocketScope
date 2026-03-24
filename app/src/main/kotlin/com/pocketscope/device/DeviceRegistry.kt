package com.pocketscope.device

import android.hardware.camera2.CameraManager
import android.os.Handler
import com.pocketscope.camera.CameraSessionContract
import com.pocketscope.camera.CameraSessionManager
import com.pocketscope.camera.LensEnumerator
import com.pocketscope.camera.RawCaptureSession

class DeviceRegistry {
    val captureDevices: List<CaptureDevice>
    val focuserDevice: FocuserDevice
    private val sessionManager: CameraSessionContract

    constructor(cameraManager: CameraManager, handler: Handler) {
        this.sessionManager = CameraSessionManager(cameraManager, handler)
        val rawCaptureSession = RawCaptureSession(handler)
        
        val lenses = LensEnumerator.enumerateLenses(cameraManager)
        this.captureDevices = lenses.map { lensInfo ->
            CaptureDeviceImpl(lensInfo, this.sessionManager, rawCaptureSession, handler)
        }
        this.focuserDevice = FocuserDeviceImpl()
    }

    internal constructor(
        captureDevices: List<CaptureDevice>,
        focuserDevice: FocuserDevice,
        sessionManager: CameraSessionContract
    ) {
        this.captureDevices = captureDevices
        this.focuserDevice = focuserDevice
        this.sessionManager = sessionManager
    }

    fun closeAll() {
        sessionManager.closeAll()
    }
}
