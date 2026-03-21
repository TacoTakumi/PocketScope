package com.pocketscope.indi.server

import com.pocketscope.indi.device.MockDevice
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class ClientSessionTest {

    @Test
    fun `session sends property definitions on getProperties`() {
        val device = MockDevice()
        val clientInput = "<getProperties version=\"1.7\"/>"
        val inputStream = ByteArrayInputStream(clientInput.toByteArray())
        val outputStream = ByteArrayOutputStream()

        val session = ClientSession(inputStream, outputStream, listOf(device))
        session.handleCommands()

        val response = outputStream.toString("UTF-8")
        assertTrue("Should contain defSwitchVector", response.contains("defSwitchVector"))
        assertTrue("Should contain CONNECTION", response.contains("CONNECTION"))
        assertTrue("Should contain Mock Camera", response.contains("Mock Camera"))
    }

    @Test
    fun `session ignores unknown commands`() {
        val device = MockDevice()
        val clientInput = "<enableBLOB device=\"CCD\">Also</enableBLOB>"
        val inputStream = ByteArrayInputStream(clientInput.toByteArray())
        val outputStream = ByteArrayOutputStream()

        val session = ClientSession(inputStream, outputStream, listOf(device))
        session.handleCommands()

        val response = outputStream.toString("UTF-8")
        assertEquals("Should produce no output for unknown commands", "", response)
    }

    @Test
    fun `session handles multiple getProperties requests`() {
        val device = MockDevice()
        val clientInput = "<getProperties version=\"1.7\"/><getProperties version=\"1.7\"/>"
        val inputStream = ByteArrayInputStream(clientInput.toByteArray())
        val outputStream = ByteArrayOutputStream()

        val session = ClientSession(inputStream, outputStream, listOf(device))
        session.handleCommands()

        val response = outputStream.toString("UTF-8")
        // Should contain two defSwitchVector blocks
        val count = "defSwitchVector".toRegex().findAll(response).count()
        assertTrue("Should have at least 2 defSwitchVector elements (open+close per request)", count >= 2)
    }
}
