package com.pocketscope.indi.protocol

import org.junit.Assert.*
import org.junit.Test

class IndiProtocolParserTest {

    @Test
    fun `parser initializes on InputStream without throwing`() {
        val xml = "<getProperties version=\"1.7\"/>"
        val stream = xml.byteInputStream()
        val parser = IndiProtocolParser(stream)
        assertNotNull(parser)
    }

    @Test
    fun `parseStream extracts getProperties tag and attributes`() {
        val xml = "<getProperties version=\"1.7\"/>"
        val stream = xml.byteInputStream()
        val parser = IndiProtocolParser(stream)

        val results = mutableListOf<Triple<String, Map<String, String>, Map<String, String>>>()
        parser.parseStream { name, attrs, elements -> results.add(Triple(name, attrs, elements)) }

        assertEquals(1, results.size)
        assertEquals("getProperties", results[0].first)
        assertEquals("1.7", results[0].second["version"])
    }

    @Test
    fun `parseStream skips dummy indi root element`() {
        val xml = "<getProperties version=\"1.7\"/>"
        val stream = xml.byteInputStream()
        val parser = IndiProtocolParser(stream)

        val results = mutableListOf<Triple<String, Map<String, String>, Map<String, String>>>()
        parser.parseStream { name, attrs, elements -> results.add(Triple(name, attrs, elements)) }

        assertTrue(results.none { it.first == "indi" })
    }

    @Test
    fun `parseStream handles multiple sequential elements`() {
        val xml = "<getProperties version=\"1.7\"/><newTextVector device=\"CCD\" name=\"CONNECT\"><oneText name=\"CONNECT\">On</oneText></newTextVector>"
        val stream = xml.byteInputStream()
        val parser = IndiProtocolParser(stream)

        val results = mutableListOf<Triple<String, Map<String, String>, Map<String, String>>>()
        parser.parseStream { name, attrs, elements -> results.add(Triple(name, attrs, elements)) }

        assertTrue(results.size >= 2)
        assertEquals("getProperties", results[0].first)
        assertEquals("newTextVector", results[1].first)
        assertEquals("CCD", results[1].second["device"])
    }

    // --- Phase 2 Plan 01: Child element parsing (D-18) ---

    @Test
    fun `getProperties produces empty elements map`() {
        val xml = "<getProperties version=\"1.7\"/>"
        val stream = xml.byteInputStream()
        val parser = IndiProtocolParser(stream)

        val results = mutableListOf<Triple<String, Map<String, String>, Map<String, String>>>()
        parser.parseStream { name, attrs, elements -> results.add(Triple(name, attrs, elements)) }

        assertEquals(1, results.size)
        assertTrue("elements should be empty for getProperties", results[0].third.isEmpty())
    }

    @Test
    fun `newNumberVector with oneNumber child extracts element`() {
        val xml = """<newNumberVector device="cam" name="CCD_EXPOSURE"><oneNumber name="CCD_EXPOSURE_VALUE">5.0</oneNumber></newNumberVector>"""
        val stream = xml.byteInputStream()
        val parser = IndiProtocolParser(stream)

        val results = mutableListOf<Triple<String, Map<String, String>, Map<String, String>>>()
        parser.parseStream { name, attrs, elements -> results.add(Triple(name, attrs, elements)) }

        assertEquals(1, results.size)
        assertEquals("newNumberVector", results[0].first)
        assertEquals("cam", results[0].second["device"])
        assertEquals("CCD_EXPOSURE", results[0].second["name"])
        assertEquals("5.0", results[0].third["CCD_EXPOSURE_VALUE"])
    }

    @Test
    fun `newSwitchVector with multiple oneSwitch children extracts all elements`() {
        val xml = """<newSwitchVector device="cam" name="CONNECTION"><oneSwitch name="CONNECT">On</oneSwitch><oneSwitch name="DISCONNECT">Off</oneSwitch></newSwitchVector>"""
        val stream = xml.byteInputStream()
        val parser = IndiProtocolParser(stream)

        val results = mutableListOf<Triple<String, Map<String, String>, Map<String, String>>>()
        parser.parseStream { name, attrs, elements -> results.add(Triple(name, attrs, elements)) }

        assertEquals(1, results.size)
        assertEquals("newSwitchVector", results[0].first)
        val childElements = results[0].third
        assertEquals(2, childElements.size)
        assertEquals("On", childElements["CONNECT"])
        assertEquals("Off", childElements["DISCONNECT"])
    }

    @Test
    fun `newNumberVector with 2 oneNumber children collects all`() {
        val xml = """<newNumberVector device="cam" name="CCD_FRAME"><oneNumber name="X">0</oneNumber><oneNumber name="WIDTH">4032</oneNumber></newNumberVector>"""
        val stream = xml.byteInputStream()
        val parser = IndiProtocolParser(stream)

        val results = mutableListOf<Triple<String, Map<String, String>, Map<String, String>>>()
        parser.parseStream { name, attrs, elements -> results.add(Triple(name, attrs, elements)) }

        assertEquals(1, results.size)
        val childElements = results[0].third
        assertEquals(2, childElements.size)
        assertEquals("0", childElements["X"])
        assertEquals("4032", childElements["WIDTH"])
    }
}
