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
}
