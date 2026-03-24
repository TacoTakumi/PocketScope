package com.pocketscope.indi.device

import com.pocketscope.indi.properties.NumberProperty
import com.pocketscope.indi.properties.PropertyState
import com.pocketscope.indi.properties.SwitchProperty
import android.graphics.Rect
import android.util.Range
import android.util.Size
import android.util.SizeF
import com.pocketscope.camera.LensInfo
import com.pocketscope.device.FocuserDeviceImpl
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Tests for IndiFocuserDevice -- INDI Focuser mapped to Camera2 manual focus.
 *
 * Covers: device name, property definitions, absolute/relative position handling,
 * focus direction, lens switching reset (D-14), diopter conversion, and alert
 * state when no active lens is present.
 */
class IndiFocuserDeviceTest {

    private lateinit var focuser: IndiFocuserDevice

    /** Helper to create a LensInfo for testing with a given minFocusDistance. */
    private fun testLensInfo(minFocusDistance: Float? = 10.0f) = LensInfo(
        physicalCameraId = "0",
        logicalCameraId = null,
        focalLength = 4.38f,
        pixelArraySize = Size(4032, 3024),
        physicalSensorSize = SizeF(5.64f, 4.23f),
        pixelSizeX = 1.4f,
        pixelSizeY = 1.4f,
        isoRange = Range(50, 6400),
        exposureTimeRange = Range(1000L, 60_000_000_000L),
        minFocusDistance = minFocusDistance,
        maxDigitalZoom = 7.0f,
        activeArraySize = Rect(0, 0, 4032, 3024)
    )

    @Before
    fun setUp() {
        focuser = IndiFocuserDevice(FocuserDeviceImpl())
        // Activate a lens so focuser is operational
        focuser.switchActiveLens(testLensInfo())
    }

    // --- Device identity ---

    @Test
    fun `device name is PocketScope Focuser`() {
        assertEquals("PocketScope Focuser", focuser.deviceName)
    }

    // --- Property definitions ---

    @Test
    fun `properties list contains ABS_FOCUS_POSITION`() {
        val prop = focuser.properties.find { it.name == "ABS_FOCUS_POSITION" }
        assertNotNull("ABS_FOCUS_POSITION missing", prop)
        assertTrue(prop is NumberProperty)
        val np = prop as NumberProperty
        assertEquals(0.0, np.min, 0.001)
        assertEquals(1000.0, np.max, 0.001)
        assertEquals(1.0, np.step, 0.001)
    }

    @Test
    fun `properties list contains REL_FOCUS_POSITION`() {
        val prop = focuser.properties.find { it.name == "REL_FOCUS_POSITION" }
        assertNotNull("REL_FOCUS_POSITION missing", prop)
        assertTrue(prop is NumberProperty)
    }

    @Test
    fun `properties list contains FOCUS_MAX`() {
        val prop = focuser.properties.find { it.name == "FOCUS_MAX" }
        assertNotNull("FOCUS_MAX missing", prop)
        assertTrue(prop is NumberProperty)
        assertEquals(1000.0, (prop as NumberProperty).value, 0.001)
    }

    @Test
    fun `properties list contains FOCUS_MOTION`() {
        val prop = focuser.properties.find { it.name == "FOCUS_MOTION" }
        assertNotNull("FOCUS_MOTION missing", prop)
        assertTrue(prop is SwitchProperty)
        val sp = prop as SwitchProperty
        assertEquals("OneOfMany", sp.rule)
        assertTrue(sp.options.containsKey("FOCUS_INWARD"))
        assertTrue(sp.options.containsKey("FOCUS_OUTWARD"))
    }

    // --- Absolute position ---

    @Test
    fun `handleNewProperty sets absolute focus position`() {
        focuser.handleNewProperty("ABS_FOCUS_POSITION", mapOf("FOCUS_ABSOLUTE_POSITION" to "500"))
        val prop = focuser.properties.find { it.name == "ABS_FOCUS_POSITION" } as NumberProperty
        assertEquals(500.0, prop.value, 0.001)
    }

    // --- Relative position with direction ---

    @Test
    fun `relative position outward adds to current position`() {
        focuser.handleNewProperty("ABS_FOCUS_POSITION", mapOf("FOCUS_ABSOLUTE_POSITION" to "500"))
        // Default direction is FOCUS_OUTWARD
        focuser.handleNewProperty("REL_FOCUS_POSITION", mapOf("FOCUS_RELATIVE_POSITION" to "10"))
        val prop = focuser.properties.find { it.name == "ABS_FOCUS_POSITION" } as NumberProperty
        assertEquals(510.0, prop.value, 0.001)
    }

    @Test
    fun `relative position inward subtracts from current position`() {
        focuser.handleNewProperty("ABS_FOCUS_POSITION", mapOf("FOCUS_ABSOLUTE_POSITION" to "500"))
        // Switch direction to inward
        focuser.handleNewProperty("FOCUS_MOTION", mapOf("FOCUS_INWARD" to "On", "FOCUS_OUTWARD" to "Off"))
        focuser.handleNewProperty("REL_FOCUS_POSITION", mapOf("FOCUS_RELATIVE_POSITION" to "10"))
        val prop = focuser.properties.find { it.name == "ABS_FOCUS_POSITION" } as NumberProperty
        assertEquals(490.0, prop.value, 0.001)
    }

    // --- Clamping ---

    @Test
    fun `relative position does not go below zero`() {
        focuser.handleNewProperty("ABS_FOCUS_POSITION", mapOf("FOCUS_ABSOLUTE_POSITION" to "5"))
        // Switch direction to inward
        focuser.handleNewProperty("FOCUS_MOTION", mapOf("FOCUS_INWARD" to "On", "FOCUS_OUTWARD" to "Off"))
        focuser.handleNewProperty("REL_FOCUS_POSITION", mapOf("FOCUS_RELATIVE_POSITION" to "20"))
        val prop = focuser.properties.find { it.name == "ABS_FOCUS_POSITION" } as NumberProperty
        assertEquals(0.0, prop.value, 0.001)
    }

    @Test
    fun `relative position does not exceed max`() {
        focuser.handleNewProperty("ABS_FOCUS_POSITION", mapOf("FOCUS_ABSOLUTE_POSITION" to "995"))
        focuser.handleNewProperty("REL_FOCUS_POSITION", mapOf("FOCUS_RELATIVE_POSITION" to "20"))
        val prop = focuser.properties.find { it.name == "ABS_FOCUS_POSITION" } as NumberProperty
        assertEquals(1000.0, prop.value, 0.001)
    }

    // --- Lens switching ---

    @Test
    fun `switchActiveLens resets position to zero`() {
        focuser.handleNewProperty("ABS_FOCUS_POSITION", mapOf("FOCUS_ABSOLUTE_POSITION" to "500"))
        focuser.switchActiveLens(testLensInfo(minFocusDistance = 5.0f))
        val prop = focuser.properties.find { it.name == "ABS_FOCUS_POSITION" } as NumberProperty
        assertEquals(0.0, prop.value, 0.001)
    }

    // --- Diopter conversion ---

    @Test
    fun `focuser step 500 with minFocusDistance 10 returns 5 diopters`() {
        focuser.handleNewProperty("ABS_FOCUS_POSITION", mapOf("FOCUS_ABSOLUTE_POSITION" to "500"))
        assertEquals(5.0f, focuser.currentDiopters(), 0.001f)
    }

    // --- Works without active lens ---

    @Test
    fun `handleNewProperty works without active lens`() {
        val noLensFocuser = IndiFocuserDevice(FocuserDeviceImpl()) // no lens activated
        noLensFocuser.handleNewProperty("ABS_FOCUS_POSITION", mapOf("FOCUS_ABSOLUTE_POSITION" to "500"))
        val prop = noLensFocuser.properties.find { it.name == "ABS_FOCUS_POSITION" } as NumberProperty
        assertEquals(500.0, prop.value, 0.001)
        assertEquals(PropertyState.Ok, prop.state)
    }

    // --- State transitions ---

    @Test
    fun `absolute position transitions through Busy to Ok state`() {
        focuser.handleNewProperty("ABS_FOCUS_POSITION", mapOf("FOCUS_ABSOLUTE_POSITION" to "500"))
        val prop = focuser.properties.find { it.name == "ABS_FOCUS_POSITION" } as NumberProperty
        // After move completes, state should be Ok (Busy is transient in synchronous code)
        assertEquals(PropertyState.Ok, prop.state)
        assertEquals(500.0, prop.value, 0.001)
    }

    // --- Float string parsing ---

    @Test
    fun `absolute position parses float-formatted string`() {
        focuser.handleNewProperty("ABS_FOCUS_POSITION", mapOf("FOCUS_ABSOLUTE_POSITION" to "500.0"))
        val prop = focuser.properties.find { it.name == "ABS_FOCUS_POSITION" } as NumberProperty
        assertEquals(500.0, prop.value, 0.001)
        assertEquals(PropertyState.Ok, prop.state)
    }

    @Test
    fun `relative position parses float-formatted string`() {
        focuser.handleNewProperty("ABS_FOCUS_POSITION", mapOf("FOCUS_ABSOLUTE_POSITION" to "100"))
        focuser.handleNewProperty("REL_FOCUS_POSITION", mapOf("FOCUS_RELATIVE_POSITION" to "10.0"))
        val prop = focuser.properties.find { it.name == "ABS_FOCUS_POSITION" } as NumberProperty
        assertEquals(110.0, prop.value, 0.001)
    }
}
