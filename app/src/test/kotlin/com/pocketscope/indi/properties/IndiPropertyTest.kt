package com.pocketscope.indi.properties

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/**
 * Tests for INDI Property domain models and Flow-based update emission.
 */
class IndiPropertyTest {

    // --- Task 1: Property Domain Models & Event Flow ---

    @Test
    fun `TextProperty can be instantiated with correct values`() {
        val prop = TextProperty(
            device = "CCD Simulator",
            name = "DRIVER_INFO",
            label = "Driver Info",
            group = "General",
            initialState = PropertyState.Idle,
            value = "PocketScope CCD"
        )
        assertEquals("CCD Simulator", prop.device)
        assertEquals("DRIVER_INFO", prop.name)
        assertEquals("Driver Info", prop.label)
        assertEquals("General", prop.group)
        assertEquals(PropertyState.Idle, prop.state)
        assertEquals("PocketScope CCD", prop.value)
    }

    @Test
    fun `NumberProperty can be instantiated with correct values`() {
        val prop = NumberProperty(
            device = "CCD Simulator",
            name = "CCD_EXPOSURE",
            label = "Exposure",
            group = "Main Control",
            initialState = PropertyState.Idle,
            format = "%6.2f",
            value = 1.0,
            min = 0.001,
            max = 3600.0,
            step = 0.1
        )
        assertEquals("CCD_EXPOSURE", prop.name)
        assertEquals(1.0, prop.value, 0.0001)
        assertEquals(0.001, prop.min, 0.0001)
        assertEquals(3600.0, prop.max, 0.0001)
        assertEquals(0.1, prop.step, 0.0001)
        assertEquals("%6.2f", prop.format)
    }

    @Test
    fun `SwitchProperty can be instantiated with correct values`() {
        val prop = SwitchProperty(
            device = "CCD Simulator",
            name = "CONNECTION",
            label = "Connection",
            group = "Main Control",
            initialState = PropertyState.Idle,
            rule = "OneOfMany",
            options = mutableMapOf("CONNECT" to true, "DISCONNECT" to false)
        )
        assertEquals("CONNECTION", prop.name)
        assertEquals("OneOfMany", prop.rule)
        assertTrue(prop.options["CONNECT"]!!)
        assertFalse(prop.options["DISCONNECT"]!!)
    }

    @Test
    fun `TextProperty value change emits update on Flow`() = runTest {
        val prop = TextProperty(
            device = "CCD Simulator",
            name = "DRIVER_INFO",
            label = "Driver Info",
            group = "General",
            initialState = PropertyState.Idle,
            value = "initial"
        )

        var received: IndiProperty? = null
        val job = launch {
            received = prop.updates.first()
        }

        prop.value = "updated"

        job.join()
        assertNotNull(received)
        assertEquals("updated", (received as TextProperty).value)
    }

    @Test
    fun `NumberProperty value change emits update on Flow`() = runTest {
        val prop = NumberProperty(
            device = "CCD Simulator",
            name = "CCD_EXPOSURE",
            label = "Exposure",
            group = "Main Control",
            initialState = PropertyState.Idle,
            format = "%6.2f",
            value = 1.0,
            min = 0.001,
            max = 3600.0,
            step = 0.1
        )

        var received: IndiProperty? = null
        val job = launch {
            received = prop.updates.first()
        }

        prop.value = 5.0

        job.join()
        assertNotNull(received)
        assertEquals(5.0, (received as NumberProperty).value, 0.0001)
    }

    @Test
    fun `State change emits update on Flow`() = runTest {
        val prop = TextProperty(
            device = "CCD Simulator",
            name = "DRIVER_INFO",
            label = "Driver Info",
            group = "General",
            initialState = PropertyState.Idle,
            value = "test"
        )

        var received: IndiProperty? = null
        val job = launch {
            received = prop.updates.first()
        }

        prop.state = PropertyState.Busy

        job.join()
        assertNotNull(received)
        assertEquals(PropertyState.Busy, received!!.state)
    }
}
