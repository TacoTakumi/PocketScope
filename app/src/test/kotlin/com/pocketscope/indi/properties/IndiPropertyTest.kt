package com.pocketscope.indi.properties

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import nl.adaptivity.xmlutil.core.KtXmlWriter
import org.junit.Assert.*
import org.junit.Test
import java.io.StringWriter
import javax.xml.parsers.DocumentBuilderFactory
import org.xml.sax.InputSource
import java.io.StringReader

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

    // --- Task 2: XML Serialization & Formatting ---

    private fun writePropertyToXml(property: IndiProperty): String {
        val sw = StringWriter()
        val writer = KtXmlWriter(sw)
        property.writeXml(writer)
        writer.flush()
        writer.close()
        return sw.toString()
    }

    private fun assertWellFormedXml(xml: String) {
        try {
            val factory = DocumentBuilderFactory.newInstance()
            val builder = factory.newDocumentBuilder()
            builder.parse(InputSource(StringReader(xml)))
        } catch (e: Exception) {
            fail("XML is not well-formed: ${e.message}\nXML was: $xml")
        }
    }

    @Test
    fun `NumberProperty generates valid INDI XML`() {
        val prop = NumberProperty(
            device = "CCD Simulator",
            name = "CCD_EXPOSURE",
            label = "Exposure",
            group = "Main Control",
            initialState = PropertyState.Idle,
            format = "%6.2f",
            value = 1.5,
            min = 0.001,
            max = 3600.0,
            step = 0.1
        )

        val xml = writePropertyToXml(prop)

        // Should contain the defNumberVector wrapper
        assertTrue("Should contain defNumberVector", xml.contains("defNumberVector"))
        assertTrue("Should contain device attribute", xml.contains("device=\"CCD Simulator\""))
        assertTrue("Should contain name attribute", xml.contains("name=\"CCD_EXPOSURE\""))
        assertTrue("Should contain defNumber element", xml.contains("defNumber"))
    }

    @Test
    fun `NumberProperty formats doubles strictly per INDI format`() {
        val prop = NumberProperty(
            device = "CCD Simulator",
            name = "CCD_EXPOSURE",
            label = "Exposure",
            group = "Main Control",
            initialState = PropertyState.Idle,
            format = "%6.2f",
            value = 1.5,
            min = 0.001,
            max = 3600.0,
            step = 0.1
        )

        val xml = writePropertyToXml(prop)

        // With format "%6.2f", 1.5 should be formatted as "  1.50" (6 chars, 2 decimals)
        assertTrue("Should contain strictly formatted number", xml.contains("1.50"))
        // Should NOT contain "1.5" without trailing zero (exact format matters)
        assertFalse("Should not have loosely formatted 1.5 without padding",
            xml.contains(">1.5<"))
    }

    @Test
    fun `TextProperty generates valid INDI XML`() {
        val prop = TextProperty(
            device = "CCD Simulator",
            name = "DRIVER_INFO",
            label = "Driver Info",
            group = "General",
            initialState = PropertyState.Ok,
            value = "PocketScope CCD"
        )

        val xml = writePropertyToXml(prop)

        assertTrue("Should contain defTextVector", xml.contains("defTextVector"))
        assertTrue("Should contain device attribute", xml.contains("device=\"CCD Simulator\""))
        assertTrue("Should contain the text value", xml.contains("PocketScope CCD"))
    }

    @Test
    fun `SwitchProperty generates valid INDI XML`() {
        val prop = SwitchProperty(
            device = "CCD Simulator",
            name = "CONNECTION",
            label = "Connection",
            group = "Main Control",
            initialState = PropertyState.Idle,
            rule = "OneOfMany",
            options = mutableMapOf("CONNECT" to true, "DISCONNECT" to false)
        )

        val xml = writePropertyToXml(prop)

        assertTrue("Should contain defSwitchVector", xml.contains("defSwitchVector"))
        assertTrue("Should contain rule attribute", xml.contains("rule=\"OneOfMany\""))
        assertTrue("Should contain On state", xml.contains("On"))
        assertTrue("Should contain Off state", xml.contains("Off"))
    }

    @Test
    fun `generated XML is well-formed`() {
        val prop = NumberProperty(
            device = "CCD Simulator",
            name = "CCD_EXPOSURE",
            label = "Exposure",
            group = "Main Control",
            initialState = PropertyState.Idle,
            format = "%6.2f",
            value = 1.5,
            min = 0.001,
            max = 3600.0,
            step = 0.1
        )

        val xml = writePropertyToXml(prop)
        assertWellFormedXml(xml)
    }

    @Test
    fun `TextProperty XML is well-formed`() {
        val prop = TextProperty(
            device = "CCD Simulator",
            name = "DRIVER_INFO",
            label = "Driver Info",
            group = "General",
            initialState = PropertyState.Ok,
            value = "PocketScope CCD"
        )

        val xml = writePropertyToXml(prop)
        assertWellFormedXml(xml)
    }

    @Test
    fun `SwitchProperty XML is well-formed`() {
        val prop = SwitchProperty(
            device = "CCD Simulator",
            name = "CONNECTION",
            label = "Connection",
            group = "Main Control",
            initialState = PropertyState.Idle,
            rule = "OneOfMany",
            options = mutableMapOf("CONNECT" to true, "DISCONNECT" to false)
        )

        val xml = writePropertyToXml(prop)
        assertWellFormedXml(xml)
    }
}
