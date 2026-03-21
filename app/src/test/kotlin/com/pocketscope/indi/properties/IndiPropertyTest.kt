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

    // --- writeSetXml tests ---

    private fun writeSetPropertyToXml(property: IndiProperty): String {
        val sw = StringWriter()
        val writer = KtXmlWriter(sw)
        property.writeSetXml(writer)
        writer.flush()
        writer.close()
        return sw.toString()
    }

    @Test
    fun `TextProperty writeSetXml produces setTextVector with oneText`() {
        val prop = TextProperty(
            device = "CCD Simulator",
            name = "DRIVER_INFO",
            label = "Driver Info",
            group = "General",
            initialState = PropertyState.Ok,
            value = "PocketScope CCD"
        )

        val xml = writeSetPropertyToXml(prop)

        assertTrue("Should contain setTextVector", xml.contains("setTextVector"))
        assertFalse("Should NOT contain defTextVector", xml.contains("defTextVector"))
        assertTrue("Should contain oneText", xml.contains("oneText"))
        assertFalse("Should NOT contain defText", xml.contains("defText"))
        assertTrue("Should contain device attribute", xml.contains("device=\"CCD Simulator\""))
        assertTrue("Should contain name attribute", xml.contains("name=\"DRIVER_INFO\""))
        assertTrue("Should contain state attribute", xml.contains("state=\"Ok\""))
        assertTrue("Should contain the text value", xml.contains("PocketScope CCD"))
        assertWellFormedXml(xml)
    }

    @Test
    fun `NumberProperty writeSetXml produces setNumberVector with oneNumber`() {
        val prop = NumberProperty(
            device = "CCD Simulator",
            name = "CCD_EXPOSURE",
            label = "Exposure",
            group = "Main Control",
            initialState = PropertyState.Busy,
            format = "%6.2f",
            value = 1.5,
            min = 0.001,
            max = 3600.0,
            step = 0.1
        )

        val xml = writeSetPropertyToXml(prop)

        assertTrue("Should contain setNumberVector", xml.contains("setNumberVector"))
        assertFalse("Should NOT contain defNumberVector", xml.contains("defNumberVector"))
        assertTrue("Should contain oneNumber", xml.contains("oneNumber"))
        assertFalse("Should NOT contain defNumber", xml.contains("defNumber"))
        assertTrue("Should contain device attribute", xml.contains("device=\"CCD Simulator\""))
        assertTrue("Should contain name attribute", xml.contains("name=\"CCD_EXPOSURE\""))
        assertTrue("Should contain state attribute", xml.contains("state=\"Busy\""))
        assertTrue("Should contain formatted value", xml.contains("1.50"))
        assertWellFormedXml(xml)
    }

    @Test
    fun `SwitchProperty writeSetXml produces setSwitchVector with oneSwitch`() {
        val prop = SwitchProperty(
            device = "CCD Simulator",
            name = "CONNECTION",
            label = "Connection",
            group = "Main Control",
            initialState = PropertyState.Ok,
            rule = "OneOfMany",
            options = mutableMapOf("CONNECT" to true, "DISCONNECT" to false)
        )

        val xml = writeSetPropertyToXml(prop)

        assertTrue("Should contain setSwitchVector", xml.contains("setSwitchVector"))
        assertFalse("Should NOT contain defSwitchVector", xml.contains("defSwitchVector"))
        assertTrue("Should contain oneSwitch", xml.contains("oneSwitch"))
        assertFalse("Should NOT contain defSwitch", xml.contains("defSwitch"))
        assertTrue("Should contain device attribute", xml.contains("device=\"CCD Simulator\""))
        assertTrue("Should contain name attribute", xml.contains("name=\"CONNECTION\""))
        assertTrue("Should contain state attribute", xml.contains("state=\"Ok\""))
        assertTrue("Should contain On", xml.contains("On"))
        assertTrue("Should contain Off", xml.contains("Off"))
        assertWellFormedXml(xml)
    }

    // --- Phase 2 Plan 01: NumberVectorProperty, perm field, IndiDevice ---

    @Test
    fun `NumberVectorProperty with 3 elements serializes defNumberVector with 3 defNumber children`() {
        val elements = mutableListOf(
            NumberElement("CCD_MAX_X", "Max X", "%6.0f", 4032.0, 0.0, 10000.0, 0.0),
            NumberElement("CCD_MAX_Y", "Max Y", "%6.0f", 3024.0, 0.0, 10000.0, 0.0),
            NumberElement("CCD_PIXEL_SIZE", "Pixel Size", "%6.2f", 1.22, 0.0, 100.0, 0.0)
        )
        val prop = NumberVectorProperty(
            device = "CCD Simulator",
            name = "CCD_INFO",
            label = "CCD Info",
            group = "Image Info",
            initialState = PropertyState.Idle,
            perm = "ro",
            elements = elements
        )

        val xml = writePropertyToXml(prop)

        assertTrue("Should contain defNumberVector", xml.contains("defNumberVector"))
        // Count defNumber start tags (not defNumberVector) — account for namespace prefix (n1:defNumber)
        val defNumberCount = Regex("""<\w*:?defNumber\s""").findAll(xml).count()
        assertEquals("Should have 3 defNumber elements", 3, defNumberCount)
        assertTrue("Should contain CCD_MAX_X", xml.contains("CCD_MAX_X"))
        assertTrue("Should contain CCD_MAX_Y", xml.contains("CCD_MAX_Y"))
        assertTrue("Should contain CCD_PIXEL_SIZE", xml.contains("CCD_PIXEL_SIZE"))
        assertWellFormedXml(xml)
    }

    @Test
    fun `NumberVectorProperty writeSetXml produces setNumberVector with 3 oneNumber children`() {
        val elements = mutableListOf(
            NumberElement("CCD_MAX_X", "Max X", "%6.0f", 4032.0, 0.0, 10000.0, 0.0),
            NumberElement("CCD_MAX_Y", "Max Y", "%6.0f", 3024.0, 0.0, 10000.0, 0.0),
            NumberElement("CCD_PIXEL_SIZE", "Pixel Size", "%6.2f", 1.22, 0.0, 100.0, 0.0)
        )
        val prop = NumberVectorProperty(
            device = "CCD Simulator",
            name = "CCD_INFO",
            label = "CCD Info",
            group = "Image Info",
            initialState = PropertyState.Ok,
            perm = "ro",
            elements = elements
        )

        val sw = StringWriter()
        val writer = KtXmlWriter(sw)
        prop.writeSetXml(writer)
        writer.flush()
        writer.close()
        val xml = sw.toString()

        assertTrue("Should contain setNumberVector", xml.contains("setNumberVector"))
        assertFalse("Should NOT contain defNumberVector", xml.contains("defNumberVector"))
        assertTrue("Should contain oneNumber", xml.contains("oneNumber"))
        val oneNumberCount = Regex("""<\w*:?oneNumber\s""").findAll(xml).count()
        assertEquals("Should have 3 oneNumber elements", 3, oneNumberCount)
        assertWellFormedXml(xml)
    }

    @Test
    fun `NumberVectorProperty getElement returns correct NumberElement`() {
        val elements = mutableListOf(
            NumberElement("CCD_MAX_X", "Max X", "%6.0f", 4032.0, 0.0, 10000.0, 0.0),
            NumberElement("CCD_MAX_Y", "Max Y", "%6.0f", 3024.0, 0.0, 10000.0, 0.0)
        )
        val prop = NumberVectorProperty(
            device = "CCD Simulator",
            name = "CCD_INFO",
            label = "CCD Info",
            group = "Image Info",
            initialState = PropertyState.Idle,
            perm = "ro",
            elements = elements
        )

        val elem = prop.getElement("CCD_MAX_X")
        assertNotNull(elem)
        assertEquals(4032.0, elem!!.value, 0.0001)
    }

    @Test
    fun `NumberVectorProperty setElementValue updates value and emits update`() = runTest {
        val elements = mutableListOf(
            NumberElement("CCD_MAX_X", "Max X", "%6.0f", 4032.0, 0.0, 10000.0, 0.0)
        )
        val prop = NumberVectorProperty(
            device = "CCD Simulator",
            name = "CCD_INFO",
            label = "CCD Info",
            group = "Image Info",
            initialState = PropertyState.Idle,
            perm = "rw",
            elements = elements
        )

        var received: IndiProperty? = null
        val job = launch {
            received = prop.updates.first()
        }

        prop.setElementValue("CCD_MAX_X", 8064.0)

        job.join()
        assertNotNull(received)
        assertEquals(8064.0, prop.getElement("CCD_MAX_X")!!.value, 0.0001)
    }

    @Test
    fun `NumberVectorProperty with perm ro serializes perm attribute in defNumberVector`() {
        val elements = mutableListOf(
            NumberElement("CCD_MAX_X", "Max X", "%6.0f", 4032.0, 0.0, 10000.0, 0.0)
        )
        val prop = NumberVectorProperty(
            device = "CCD Simulator",
            name = "CCD_INFO",
            label = "CCD Info",
            group = "Image Info",
            initialState = PropertyState.Idle,
            perm = "ro",
            elements = elements
        )

        val xml = writePropertyToXml(prop)
        assertTrue("Should contain perm=\"ro\"", xml.contains("perm=\"ro\""))
    }

    @Test
    fun `NumberProperty gains perm field and serializes it`() {
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
            step = 0.1,
            perm = "rw"
        )

        val xml = writePropertyToXml(prop)
        assertTrue("Should contain perm=\"rw\"", xml.contains("perm=\"rw\""))
    }

    @Test
    fun `MockDevice implements IndiDevice interface`() {
        val device: com.pocketscope.indi.device.IndiDevice = com.pocketscope.indi.device.MockDevice.instance
        assertEquals("Mock Camera", device.deviceName)
        assertTrue(device.properties.isNotEmpty())
    }
}
