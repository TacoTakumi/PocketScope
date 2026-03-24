package com.pocketscope.device

import android.graphics.Rect
import android.util.Range
import android.util.Size
import android.util.SizeF
import com.pocketscope.camera.LensInfo
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class FocuserDeviceImplTest {

    private lateinit var focuser: FocuserDeviceImpl

    @Before
    fun setup() {
        focuser = FocuserDeviceImpl()
    }

    @Test
    fun `moveAbsolute sets position and returns it`() {
        val pos = focuser.moveAbsolute(500)
        assertEquals(500, pos)
        assertEquals(500, focuser.currentPosition)
    }

    @Test
    fun `moveAbsolute clamps to maxSteps`() {
        val pos = focuser.moveAbsolute(1500)
        assertEquals(1000, pos)
        assertEquals(1000, focuser.currentPosition)
    }

    @Test
    fun `moveAbsolute clamps to zero`() {
        val pos = focuser.moveAbsolute(-10)
        assertEquals(0, pos)
        assertEquals(0, focuser.currentPosition)
    }

    @Test
    fun `moveRelative outward adds steps`() {
        focuser.moveAbsolute(500)
        val pos = focuser.moveRelative(10, outward = true)
        assertEquals(510, pos)
        assertEquals(510, focuser.currentPosition)
    }

    @Test
    fun `moveRelative inward subtracts steps`() {
        focuser.moveAbsolute(500)
        val pos = focuser.moveRelative(10, outward = false)
        assertEquals(490, pos)
        assertEquals(490, focuser.currentPosition)
    }

    @Test
    fun `moveRelative inward clamps to zero`() {
        focuser.moveAbsolute(5)
        val pos = focuser.moveRelative(20, outward = false)
        assertEquals(0, pos)
        assertEquals(0, focuser.currentPosition)
    }

    @Test
    fun `switchActiveLens resets position`() {
        focuser.moveAbsolute(500)
        val lensInfo = createDummyLensInfo()
        focuser.switchActiveLens(lensInfo)
        assertEquals(0, focuser.currentPosition)
        assertEquals(lensInfo, focuser.activeLensInfo)
    }

    @Test
    fun `currentDiopters returns correct value based on minFocusDistance`() {
        val lensInfo = createDummyLensInfo(minFocusDistance = 10.0f)
        focuser.switchActiveLens(lensInfo)
        focuser.moveAbsolute(500)
        assertEquals(5.0f, focuser.currentDiopters(), 0.001f)
    }

    @Test
    fun `currentDiopters returns 0 when no active lens`() {
        focuser.moveAbsolute(500)
        assertEquals(0.0f, focuser.currentDiopters(), 0.001f)
    }

    @Test
    fun `maxSteps defaults to 1000`() {
        assertEquals(1000, focuser.maxSteps)
    }

    private fun createDummyLensInfo(minFocusDistance: Float = 10.0f): LensInfo {
        return LensInfo(
            physicalCameraId = "0",
            logicalCameraId = null,
            focalLength = 4.0f,
            pixelArraySize = Size(4000, 3000),
            physicalSensorSize = SizeF(5.0f, 4.0f),
            pixelSizeX = 1.0f,
            pixelSizeY = 1.0f,
            isoRange = Range(100, 800),
            exposureTimeRange = Range(1000L, 1000000000L),
            minFocusDistance = minFocusDistance,
            maxDigitalZoom = 1.0f,
            activeArraySize = Rect(0, 0, 4000, 3000)
        )
    }
}
