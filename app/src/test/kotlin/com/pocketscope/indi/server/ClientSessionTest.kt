package com.pocketscope.indi.server

import com.pocketscope.indi.device.MockDevice
import com.pocketscope.indi.properties.PropertyState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream

class ClientSessionTest {

    @Test
    fun `session sends property definitions on getProperties`() = runBlocking {
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
    fun `session ignores unknown commands`() = runBlocking {
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
    fun `session handles multiple getProperties requests`() = runBlocking {
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

    @Test
    fun `session broadcasts property updates to output stream`() = runBlocking {
        val device = MockDevice()
        val clientInput = PipedInputStream()
        val clientInputWriter = PipedOutputStream(clientInput)
        val outputStream = ByteArrayOutputStream()

        val session = ClientSession(clientInput, outputStream, listOf(device))

        // Launch session in background
        val job = launch {
            session.handleCommands()
        }

        // Give session time to start collecting
        delay(200)

        // Trigger a property update by changing state
        device.properties[0].state = PropertyState.Ok

        // Give broadcast time to write
        delay(200)

        // Close input to stop the session
        clientInputWriter.close()

        // Wait for session to finish
        job.join()

        val response = outputStream.toString("UTF-8")
        assertTrue("Should contain setSwitchVector", response.contains("setSwitchVector"))
        assertTrue("Should contain CONNECTION", response.contains("CONNECTION"))
    }
}
