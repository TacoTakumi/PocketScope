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

        val elements = mutableListOf<Pair<String, Map<String, String>>>()
        parser.parseStream { name, attrs -> elements.add(name to attrs) }

        assertEquals(1, elements.size)
        assertEquals("getProperties", elements[0].first)
        assertEquals("1.7", elements[0].second["version"])
    }

    @Test
    fun `parseStream skips dummy indi root element`() {
        val xml = "<getProperties version=\"1.7\"/>"
        val stream = xml.byteInputStream()
        val parser = IndiProtocolParser(stream)

        val elements = mutableListOf<Pair<String, Map<String, String>>>()
        parser.parseStream { name, attrs -> elements.add(name to attrs) }

        // Should NOT contain the dummy <indi> root
        assertTrue(elements.none { it.first == "indi" })
    }

    @Test
    fun `parseStream handles multiple sequential elements`() {
        val xml = "<getProperties version=\"1.7\"/><newTextVector device=\"CCD\" name=\"CONNECT\"><oneText name=\"CONNECT\">On</oneText></newTextVector>"
        val stream = xml.byteInputStream()
        val parser = IndiProtocolParser(stream)

        val elements = mutableListOf<Pair<String, Map<String, String>>>()
        parser.parseStream { name, attrs -> elements.add(name to attrs) }

        assertTrue(elements.size >= 2)
        assertEquals("getProperties", elements[0].first)
        assertEquals("newTextVector", elements[1].first)
        assertEquals("CCD", elements[1].second["device"])
    }
}
