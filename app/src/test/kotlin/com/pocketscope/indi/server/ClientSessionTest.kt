package com.pocketscope.indi.server

import com.pocketscope.indi.device.IndiDevice
import com.pocketscope.indi.properties.IndiProperty
import com.pocketscope.indi.properties.PropertyState
import com.pocketscope.indi.properties.SwitchProperty
import com.pocketscope.indi.properties.NumberProperty
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream

/**
 * Minimal IndiDevice implementation for test isolation.
 * Tracks handleNewProperty calls for assertion.
 */
class TestDevice(
    override val deviceName: String,
    vararg props: IndiProperty
) : IndiDevice {
    override val properties: List<IndiProperty> = props.toList()

    val receivedCommands = mutableListOf<Pair<String, Map<String, String>>>()

    override fun handleNewProperty(propertyName: String, elements: Map<String, String>) {
        receivedCommands.add(propertyName to elements)
    }
}

class ClientSessionTest {

    private fun makeSwitch(device: String, name: String) = SwitchProperty(
        device = device,
        name = name,
        label = name,
        group = "Main",
        initialState = PropertyState.Idle,
        rule = "OneOfMany",
        options = mutableMapOf("CONNECT" to false, "DISCONNECT" to true),
        perm = "rw"
    )

    private fun makeNumber(device: String, name: String) = NumberProperty(
        device = device,
        name = name,
        label = name,
        group = "Main Control",
        initialState = PropertyState.Idle,
        format = "%g",
        value = 1.0,
        min = 0.0,
        max = 100.0,
        step = 1.0,
        perm = "rw"
    )

    @Test
    fun `session sends property definitions on getProperties`() = runBlocking {
        val device = TestDevice("Mock Camera", makeSwitch("Mock Camera", "CONNECTION"))
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
        val device = TestDevice("CCD", makeSwitch("CCD", "CONNECTION"))
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
        val device = TestDevice("Mock Camera", makeSwitch("Mock Camera", "CONNECTION"))
        val clientInput = "<getProperties version=\"1.7\"/><getProperties version=\"1.7\"/>"
        val inputStream = ByteArrayInputStream(clientInput.toByteArray())
        val outputStream = ByteArrayOutputStream()

        val session = ClientSession(inputStream, outputStream, listOf(device))
        session.handleCommands()

        val response = outputStream.toString("UTF-8")
        val count = "defSwitchVector".toRegex().findAll(response).count()
        assertTrue("Should have at least 2 defSwitchVector elements (open+close per request)", count >= 2)
    }

    @Test
    fun `session broadcasts property updates to output stream`() = runBlocking {
        val switchProp = makeSwitch("Mock Camera", "CONNECTION")
        val device = TestDevice("Mock Camera", switchProp)
        val clientInput = PipedInputStream()
        val clientInputWriter = PipedOutputStream(clientInput)
        val outputStream = ByteArrayOutputStream()

        val session = ClientSession(clientInput, outputStream, listOf(device))

        val job = launch {
            session.handleCommands()
        }

        delay(200)

        // Trigger a property update by changing state
        switchProp.state = PropertyState.Ok

        delay(200)

        clientInputWriter.close()
        job.join()

        val response = outputStream.toString("UTF-8")
        assertTrue("Should contain setSwitchVector", response.contains("setSwitchVector"))
        assertTrue("Should contain CONNECTION", response.contains("CONNECTION"))
    }

    // --- New dispatch tests ---

    @Test
    fun `getProperties response includes properties from all devices`() = runBlocking {
        val ccd1 = TestDevice("PocketScope Ultrawide",
            makeSwitch("PocketScope Ultrawide", "CONNECTION"),
            makeNumber("PocketScope Ultrawide", "CCD_EXPOSURE"))
        val ccd2 = TestDevice("PocketScope Main",
            makeSwitch("PocketScope Main", "CONNECTION"),
            makeNumber("PocketScope Main", "CCD_EXPOSURE"))
        val ccd3 = TestDevice("PocketScope Tele",
            makeSwitch("PocketScope Tele", "CONNECTION"),
            makeNumber("PocketScope Tele", "CCD_EXPOSURE"))
        val focuser = TestDevice("PocketScope Focuser",
            makeSwitch("PocketScope Focuser", "CONNECTION"),
            makeNumber("PocketScope Focuser", "ABS_FOCUS_POSITION"))

        val clientInput = "<getProperties version=\"1.7\"/>"
        val inputStream = ByteArrayInputStream(clientInput.toByteArray())
        val outputStream = ByteArrayOutputStream()

        val session = ClientSession(inputStream, outputStream, listOf(ccd1, ccd2, ccd3, focuser))
        session.handleCommands()

        val response = outputStream.toString("UTF-8")
        assertTrue("Should contain Ultrawide", response.contains("PocketScope Ultrawide"))
        assertTrue("Should contain Main", response.contains("PocketScope Main"))
        assertTrue("Should contain Tele", response.contains("PocketScope Tele"))
        assertTrue("Should contain Focuser", response.contains("PocketScope Focuser"))
        assertTrue("Should contain CCD_EXPOSURE", response.contains("CCD_EXPOSURE"))
        assertTrue("Should contain ABS_FOCUS_POSITION", response.contains("ABS_FOCUS_POSITION"))
    }

    @Test
    fun `newNumberVector dispatches to correct device by name`() = runBlocking {
        val ccdMain = TestDevice("PocketScope Main",
            makeNumber("PocketScope Main", "CCD_EXPOSURE"))
        val ccdTele = TestDevice("PocketScope Tele",
            makeNumber("PocketScope Tele", "CCD_EXPOSURE"))

        val clientInput = """
            <newNumberVector device="PocketScope Main" name="CCD_EXPOSURE">
                <oneNumber name="CCD_EXPOSURE_VALUE">5.0</oneNumber>
            </newNumberVector>
        """.trimIndent()
        val inputStream = ByteArrayInputStream(clientInput.toByteArray())
        val outputStream = ByteArrayOutputStream()

        val session = ClientSession(inputStream, outputStream, listOf(ccdMain, ccdTele))
        session.handleCommands()

        assertEquals("Main device should receive command", 1, ccdMain.receivedCommands.size)
        assertEquals("CCD_EXPOSURE", ccdMain.receivedCommands[0].first)
        assertEquals("5.0", ccdMain.receivedCommands[0].second["CCD_EXPOSURE_VALUE"])
        assertEquals("Tele device should NOT receive command", 0, ccdTele.receivedCommands.size)
    }

    @Test
    fun `newSwitchVector dispatches to focuser device`() = runBlocking {
        val focuser = TestDevice("PocketScope Focuser",
            makeSwitch("PocketScope Focuser", "FOCUS_MOTION"))

        val clientInput = """
            <newSwitchVector device="PocketScope Focuser" name="FOCUS_MOTION">
                <oneSwitch name="FOCUS_INWARD">On</oneSwitch>
            </newSwitchVector>
        """.trimIndent()
        val inputStream = ByteArrayInputStream(clientInput.toByteArray())
        val outputStream = ByteArrayOutputStream()

        val session = ClientSession(inputStream, outputStream, listOf(focuser))
        session.handleCommands()

        assertEquals("Focuser should receive command", 1, focuser.receivedCommands.size)
        assertEquals("FOCUS_MOTION", focuser.receivedCommands[0].first)
        assertEquals("On", focuser.receivedCommands[0].second["FOCUS_INWARD"])
    }

    @Test
    fun `newNumberVector with unknown device is ignored`() = runBlocking {
        val ccd = TestDevice("PocketScope Main",
            makeNumber("PocketScope Main", "CCD_EXPOSURE"))

        val clientInput = """
            <newNumberVector device="Unknown Device" name="CCD_EXPOSURE">
                <oneNumber name="CCD_EXPOSURE_VALUE">5.0</oneNumber>
            </newNumberVector>
        """.trimIndent()
        val inputStream = ByteArrayInputStream(clientInput.toByteArray())
        val outputStream = ByteArrayOutputStream()

        val session = ClientSession(inputStream, outputStream, listOf(ccd))
        // Should not crash
        session.handleCommands()

        assertEquals("Known device should NOT receive command", 0, ccd.receivedCommands.size)
    }

    @Test
    fun `newTextVector dispatches to correct device`() = runBlocking {
        val device = TestDevice("PocketScope Main",
            makeSwitch("PocketScope Main", "CONNECTION"))

        val clientInput = """
            <newTextVector device="PocketScope Main" name="SOME_TEXT">
                <oneText name="TEXT_VALUE">hello</oneText>
            </newTextVector>
        """.trimIndent()
        val inputStream = ByteArrayInputStream(clientInput.toByteArray())
        val outputStream = ByteArrayOutputStream()

        val session = ClientSession(inputStream, outputStream, listOf(device))
        session.handleCommands()

        assertEquals("Device should receive text command", 1, device.receivedCommands.size)
        assertEquals("SOME_TEXT", device.receivedCommands[0].first)
    }
}
