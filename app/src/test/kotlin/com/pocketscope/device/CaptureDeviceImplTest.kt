package com.pocketscope.device

import android.graphics.Rect
import android.hardware.camera2.CameraDevice
import android.os.Handler
import android.os.Looper
import android.util.Range
import android.util.Size
import android.util.SizeF
import com.pocketscope.camera.CameraSessionContract
import com.pocketscope.camera.CaptureResult
import com.pocketscope.camera.LensInfo
import com.pocketscope.camera.RawCaptureSession
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class CaptureDeviceImplTest {

    private lateinit var lensInfo: LensInfo
    private lateinit var fakeSessionContract: FakeCameraSessionContract
    private lateinit var fakeRawCaptureSession: FakeRawCaptureSession
    private lateinit var handler: Handler
    private lateinit var captureDevice: CaptureDeviceImpl

    @Before
    fun setup() {
        lensInfo = LensInfo(
            physicalCameraId = "0",
            logicalCameraId = null,
            focalLength = 4.0f,
            pixelArraySize = Size(4000, 3000),
            physicalSensorSize = SizeF(5.0f, 4.0f),
            pixelSizeX = 1.0f,
            pixelSizeY = 1.0f,
            isoRange = Range(100, 800),
            exposureTimeRange = Range(1000L, 1000000000L),
            minFocusDistance = 10.0f,
            maxDigitalZoom = 1.0f,
            activeArraySize = Rect(0, 0, 4000, 3000)
        )

        handler = Handler(Looper.getMainLooper())
        fakeSessionContract = FakeCameraSessionContract()
        fakeRawCaptureSession = FakeRawCaptureSession(handler)
        
        captureDevice = CaptureDeviceImpl(
            lensInfo = lensInfo,
            sessionManager = fakeSessionContract,
            rawCaptureSession = fakeRawCaptureSession,
            handler = handler
        )
    }

    @Test
    fun `capture returns Success with CaptureResult when session and capture succeed`() = runTest {
        val outcome = captureDevice.capture(1000L, 100)
        
        assertTrue("Expected Success outcome", outcome is CaptureOutcome.Success)
        val success = outcome as CaptureOutcome.Success
        assertEquals(fakeRawCaptureSession.fixedResult, success.result)
        
        assertEquals(lensInfo.physicalCameraId, fakeSessionContract.lastPhysicalId)
        assertFalse(captureDevice.isBusy)
    }

    @Test
    fun `capture returns Busy when mutex is already held`() = runTest {
        // Make the capture process take time
        fakeRawCaptureSession.delayMs = 1000L
        
        // Launch a coroutine that will hold the mutex
        val job = launch {
            captureDevice.capture(1000L, 100)
        }
        
        // Ensure the first coroutine started and grabbed the lock
        advanceTimeBy(100)
        
        assertTrue("Device should be busy", captureDevice.isBusy)
        
        // Call capture from another coroutine immediately
        val outcome = captureDevice.capture(1000L, 100)
        assertTrue("Expected Busy outcome", outcome is CaptureOutcome.Busy)
        
        // Advance time to let the first capture finish
        advanceTimeBy(1000)
        job.join()
        
        assertFalse("Device should not be busy after finish", captureDevice.isBusy)
    }

    @Test
    fun `capture returns Error when sessionManager returns null`() = runTest {
        fakeSessionContract.returnNull = true
        
        val outcome = captureDevice.capture(1000L, 100)
        assertTrue("Expected Error outcome", outcome is CaptureOutcome.Error)
        assertFalse(captureDevice.isBusy)
    }

    @Test
    fun `capture passes focusDistance through to RawCaptureSession`() = runTest {
        val outcome = captureDevice.capture(1000L, 100, 5.0f)

        assertTrue("Expected Success outcome", outcome is CaptureOutcome.Success)
        assertEquals(5.0f, fakeRawCaptureSession.lastFocusDistance)
    }

    @Test
    fun `capture defaults focusDistance to zero`() = runTest {
        val outcome = captureDevice.capture(1000L, 100)

        assertTrue("Expected Success outcome", outcome is CaptureOutcome.Success)
        assertEquals(0.0f, fakeRawCaptureSession.lastFocusDistance)
    }

    @Test
    fun `capture returns Error when rawCaptureSession throws`() = runTest {
        fakeRawCaptureSession.shouldThrow = true
        
        val outcome = captureDevice.capture(1000L, 100)
        assertTrue("Expected Error outcome", outcome is CaptureOutcome.Error)
        assertFalse(captureDevice.isBusy)
    }

    class FakeCameraSessionContract : CameraSessionContract {
        var returnNull = false
        var lastPhysicalId: String? = null
        
        override suspend fun switchToLens(physicalCameraId: String, logicalCameraId: String?): CameraDevice? {
            lastPhysicalId = physicalCameraId
            if (returnNull) return null
            return mock(CameraDevice::class.java)
        }

        override fun getActiveLensId(): String? = lastPhysicalId
        override fun closeAll() {}
    }

    class FakeRawCaptureSession(handler: Handler) : RawCaptureSession(handler) {
        val fixedResult = CaptureResult(ByteArray(0), 100, 100)
        var shouldThrow = false
        var delayMs = 0L
        var lastFocusDistance: Float = 0.0f

        override suspend fun capture(
            cameraDevice: CameraDevice,
            lensInfo: LensInfo,
            exposureNanos: Long,
            isoValue: Int,
            focusDistance: Float
        ): CaptureResult {
            lastFocusDistance = focusDistance
            if (delayMs > 0) {
                delay(delayMs)
            }
            if (shouldThrow) {
                throw RuntimeException("Simulated capture error")
            }
            return fixedResult
        }
    }
}
