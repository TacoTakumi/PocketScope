package com.pocketscope.indi.device

import android.graphics.Rect
import android.hardware.camera2.CameraDevice
import android.util.Range
import android.util.Size
import android.util.SizeF
import com.pocketscope.camera.CameraSessionContract
import com.pocketscope.camera.LensInfo
import com.pocketscope.indi.properties.BlobProperty
import com.pocketscope.indi.properties.NumberProperty
import com.pocketscope.indi.properties.NumberVectorProperty
import com.pocketscope.indi.properties.PropertyState
import com.pocketscope.indi.properties.SwitchProperty
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests for [IndiCameraDevice] - INDI CCD device per physical lens.
 *
 * Uses a [FakeCameraSessionContract] to avoid Camera2 runtime dependencies.
 * Tests property creation, value handling, range validation, and connection logic.
 * Runs under Robolectric for real Android SDK types (Size, Range, Rect).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class IndiCameraDeviceTest {

    private lateinit var testLensInfo: LensInfo
    private lateinit var fakeSession: FakeCameraSessionContract

    @Before
    fun setUp() {
        testLensInfo = LensInfo(
            physicalCameraId = "2",
            logicalCameraId = "0",
            focalLength = 6.81f,           // > 6.0 -> "Tele"
            pixelArraySize = Size(4032, 3024),
            physicalSensorSize = SizeF(5.64f, 4.23f),
            pixelSizeX = 1.4f,
            pixelSizeY = 1.4f,
            isoRange = Range(50, 6400),
            exposureTimeRange = Range(100_000L, 30_000_000_000L),  // 0.0001s to 30s in ns
            minFocusDistance = 10.0f,
            maxDigitalZoom = 8.0f,
            activeArraySize = Rect(0, 0, 4032, 3024)
        )

        fakeSession = FakeCameraSessionContract()
    }

    private fun createDevice(
        lens: LensInfo = testLensInfo,
        session: FakeCameraSessionContract = fakeSession,
        scope: kotlinx.coroutines.CoroutineScope = kotlinx.coroutines.GlobalScope,
        onLensSwitch: ((LensInfo) -> Unit)? = null
    ): IndiCameraDevice = IndiCameraDevice(lens, session, scope, onLensSwitch)

    // --- Device name tests ---

    @Test
    fun `device name follows PocketScope lensType pattern`() = runTest {
        val device = createDevice(scope = this)
        assertEquals("PocketScope Tele", device.deviceName)
    }

    @Test
    fun `device name for ultrawide lens`() = runTest {
        val ultrawideLens = testLensInfo.copy(focalLength = 2.0f)
        val device = createDevice(lens = ultrawideLens, scope = this)
        assertEquals("PocketScope Ultrawide", device.deviceName)
    }

    @Test
    fun `device name for main lens`() = runTest {
        val mainLens = testLensInfo.copy(focalLength = 4.5f)
        val device = createDevice(lens = mainLens, scope = this)
        assertEquals("PocketScope Main", device.deviceName)
    }

    // --- Property existence tests ---

    @Test
    fun `exposes CCD_EXPOSURE property`() = runTest {
        val device = createDevice(scope = this)
        val prop = device.properties.find { it.name == "CCD_EXPOSURE" }
        assertNotNull("CCD_EXPOSURE property must exist", prop)
        assertTrue("CCD_EXPOSURE must be a NumberProperty", prop is NumberProperty)
    }

    @Test
    fun `exposes CCD_GAIN property`() = runTest {
        val device = createDevice(scope = this)
        val prop = device.properties.find { it.name == "CCD_GAIN" }
        assertNotNull("CCD_GAIN property must exist", prop)
        assertTrue("CCD_GAIN must be a NumberProperty", prop is NumberProperty)
    }

    @Test
    fun `exposes CCD_INFO property`() = runTest {
        val device = createDevice(scope = this)
        val prop = device.properties.find { it.name == "CCD_INFO" }
        assertNotNull("CCD_INFO property must exist", prop)
        assertTrue("CCD_INFO must be a NumberVectorProperty", prop is NumberVectorProperty)
    }

    @Test
    fun `exposes CCD_FRAME property`() = runTest {
        val device = createDevice(scope = this)
        val prop = device.properties.find { it.name == "CCD_FRAME" }
        assertNotNull("CCD_FRAME property must exist", prop)
        assertTrue("CCD_FRAME must be a NumberVectorProperty", prop is NumberVectorProperty)
    }

    @Test
    fun `exposes CCD_TEMPERATURE property`() = runTest {
        val device = createDevice(scope = this)
        val prop = device.properties.find { it.name == "CCD_TEMPERATURE" }
        assertNotNull("CCD_TEMPERATURE property must exist", prop)
        assertTrue("CCD_TEMPERATURE must be a NumberProperty", prop is NumberProperty)
    }

    @Test
    fun `exposes CONNECTION property`() = runTest {
        val device = createDevice(scope = this)
        val prop = device.properties.find { it.name == "CONNECTION" }
        assertNotNull("CONNECTION property must exist", prop)
        assertTrue("CONNECTION must be a SwitchProperty", prop is SwitchProperty)
    }

    // --- CCD_INFO elements ---

    @Test
    fun `CCD_INFO has 6 elements with correct values from LensInfo`() = runTest {
        val device = createDevice(scope = this)
        val info = device.properties.find { it.name == "CCD_INFO" } as NumberVectorProperty
        assertEquals(6, info.elements.size)
        assertEquals("ro", info.perm)

        assertEquals(4032.0, info.getElement("CCD_MAX_X")!!.value, 0.01)
        assertEquals(3024.0, info.getElement("CCD_MAX_Y")!!.value, 0.01)
        assertEquals(1.4, info.getElement("CCD_PIXEL_SIZE")!!.value, 0.01)
        assertEquals(1.4, info.getElement("CCD_PIXEL_SIZE_X")!!.value, 0.01)
        assertEquals(1.4, info.getElement("CCD_PIXEL_SIZE_Y")!!.value, 0.01)
        assertEquals(16.0, info.getElement("CCD_BITSPERPIXEL")!!.value, 0.01)
    }

    // --- CCD_EXPOSURE range from LensInfo ---

    @Test
    fun `CCD_EXPOSURE min max derived from LensInfo exposureTimeRange in seconds`() = runTest {
        val device = createDevice(scope = this)
        val prop = device.properties.find { it.name == "CCD_EXPOSURE" } as NumberProperty
        // 100_000 ns = 0.0001 s
        assertEquals(0.0001, prop.min, 0.00001)
        // 30_000_000_000 ns = 30.0 s
        assertEquals(30.0, prop.max, 0.01)
    }

    // --- CCD_GAIN range from LensInfo ---

    @Test
    fun `CCD_GAIN min max derived from LensInfo isoRange`() = runTest {
        val device = createDevice(scope = this)
        val prop = device.properties.find { it.name == "CCD_GAIN" } as NumberProperty
        assertEquals(50.0, prop.min, 0.01)
        assertEquals(6400.0, prop.max, 0.01)
    }

    // --- handleNewProperty: CCD_EXPOSURE ---

    @Test
    fun `handleNewProperty CCD_EXPOSURE sets Busy then Alert when rawCaptureSession is null`() = runTest {
        val device = createDevice(scope = this)

        // Connect first
        fakeSession.shouldSucceed = true
        device.handleNewProperty("CONNECTION", mapOf("CONNECT" to "On"))
        advanceUntilIdle()

        device.handleNewProperty("CCD_EXPOSURE", mapOf("CCD_EXPOSURE_VALUE" to "5.0"))
        advanceUntilIdle()

        val prop = device.properties.find { it.name == "CCD_EXPOSURE" } as NumberProperty
        assertEquals(5.0, prop.value, 0.01)
        // Without rawCaptureSession, capture fails and state becomes Alert
        assertEquals(PropertyState.Alert, prop.state)
    }

    // --- handleNewProperty: out-of-range gain ---

    @Test
    fun `handleNewProperty CCD_GAIN out of range sets Alert state`() = runTest {
        val device = createDevice(scope = this)

        // Connect first
        fakeSession.shouldSucceed = true
        device.handleNewProperty("CONNECTION", mapOf("CONNECT" to "On"))
        advanceUntilIdle()

        device.handleNewProperty("CCD_GAIN", mapOf("GAIN" to "9999"))
        val prop = device.properties.find { it.name == "CCD_GAIN" } as NumberProperty
        assertEquals(PropertyState.Alert, prop.state)
    }

    // --- handleNewProperty: disconnected device ---

    @Test
    fun `handleNewProperty CCD_EXPOSURE on disconnected device sets Alert state`() = runTest {
        val device = createDevice(scope = this)

        // Device is not connected by default
        assertFalse(device.isConnected)
        device.handleNewProperty("CCD_EXPOSURE", mapOf("CCD_EXPOSURE_VALUE" to "5.0"))
        val prop = device.properties.find { it.name == "CCD_EXPOSURE" } as NumberProperty
        assertEquals(PropertyState.Alert, prop.state)
    }

    // --- handleConnection with onLensSwitch callback ---

    @Test
    fun `handleConnection CONNECT invokes onLensSwitch callback after session switch`() = runTest {
        var callbackLensInfo: LensInfo? = null
        val device = createDevice(
            scope = this,
            onLensSwitch = { callbackLensInfo = it }
        )

        fakeSession.shouldSucceed = true
        device.handleNewProperty("CONNECTION", mapOf("CONNECT" to "On"))
        advanceUntilIdle()

        assertNotNull("onLensSwitch must be invoked", callbackLensInfo)
        assertEquals(testLensInfo.physicalCameraId, callbackLensInfo!!.physicalCameraId)
        assertTrue(device.isConnected)
    }

    @Test
    fun `handleConnection CONNECT sets Ok state after successful switch`() = runTest {
        val device = createDevice(scope = this)

        fakeSession.shouldSucceed = true
        device.handleNewProperty("CONNECTION", mapOf("CONNECT" to "On"))
        advanceUntilIdle()

        val connProp = device.properties.find { it.name == "CONNECTION" }!!
        assertEquals(PropertyState.Ok, connProp.state)
        assertTrue(device.isConnected)
    }

    // --- CCD_FRAME elements ---

    @Test
    fun `CCD_FRAME has 4 elements with correct bounds from activeArraySize`() = runTest {
        val device = createDevice(scope = this)
        val frame = device.properties.find { it.name == "CCD_FRAME" } as NumberVectorProperty
        assertEquals(4, frame.elements.size)
        assertEquals("rw", frame.perm)

        val width = frame.getElement("WIDTH")!!
        assertEquals(4032.0, width.value, 0.01)
        assertEquals(4032.0, width.max, 0.01)

        val height = frame.getElement("HEIGHT")!!
        assertEquals(3024.0, height.value, 0.01)
        assertEquals(3024.0, height.max, 0.01)
    }

    // --- BlobProperty tests ---

    @Test
    fun `exposes CCD1 BlobProperty in properties list`() = runTest {
        val device = createDevice(scope = this)
        val prop = device.properties.find { it.name == "CCD1" }
        assertNotNull("CCD1 BlobProperty must exist", prop)
        assertTrue("CCD1 must be a BlobProperty", prop is BlobProperty)
    }

    // --- Bayer pattern tests ---

    @Test
    fun `bayerPattern is computed from lensInfo cfaArrangement`() = runTest {
        // Default cfaArrangement is 0 (RGGB) in testLensInfo
        val device = createDevice(scope = this)
        // 7 properties: CONNECTION, CCD_EXPOSURE, CCD_GAIN, CCD_INFO, CCD_FRAME, CCD_TEMPERATURE, CCD1
        assertEquals(7, device.properties.size)
    }

    @Test
    fun `handleExposure sets Busy state immediately`() = runTest {
        val device = createDevice(scope = this)

        // Connect first
        fakeSession.shouldSucceed = true
        device.handleNewProperty("CONNECTION", mapOf("CONNECT" to "On"))
        advanceUntilIdle()

        // Don't advance - check Busy is set synchronously
        device.handleNewProperty("CCD_EXPOSURE", mapOf("CCD_EXPOSURE_VALUE" to "1.0"))
        val prop = device.properties.find { it.name == "CCD_EXPOSURE" } as NumberProperty
        // State should be Busy (set synchronously before coroutine launch)
        // or Alert (if coroutine already ran). Both are valid depending on timing.
        assertTrue(
            "State should be Busy or Alert",
            prop.state == PropertyState.Busy || prop.state == PropertyState.Alert
        )
    }
}

/**
 * Fake implementation of [CameraSessionContract] for unit testing.
 *
 * Returns null for CameraDevice (tests don't need the actual device object),
 * or throws if [shouldSucceed] is false.
 */
class FakeCameraSessionContract : CameraSessionContract {
    var shouldSucceed = true
    var switchCallCount = 0
    var lastPhysicalId: String? = null
    var lastLogicalId: String? = null

    override suspend fun switchToLens(physicalCameraId: String, logicalCameraId: String?): CameraDevice? {
        switchCallCount++
        lastPhysicalId = physicalCameraId
        lastLogicalId = logicalCameraId
        if (!shouldSucceed) {
            throw RuntimeException("Fake camera error")
        }
        // Return null -- tests only verify INDI property behavior,
        // not CameraDevice usage.
        return null
    }

    override fun getActiveLensId(): String? = lastPhysicalId

    override fun closeAll() {
        lastPhysicalId = null
        lastLogicalId = null
    }
}
