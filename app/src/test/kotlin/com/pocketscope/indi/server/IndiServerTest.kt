package com.pocketscope.indi.server

import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.Socket

class IndiServerTest {

    private var server: IndiServer? = null

    @After
    fun tearDown() {
        server?.stop()
    }

    @Test
    fun `IndiServer initializes without crashing`() {
        server = IndiServer()
        assertNotNull(server)
    }

    @Test
    fun `can connect to port 7624 locally and disconnect`() = runBlocking {
        server = IndiServer()
        val serverJob = launch { server!!.start() }

        // Give the server a moment to bind
        delay(500)

        // Try to connect as a client
        val client = Socket("127.0.0.1", 7624)
        assertTrue("Client should be connected", client.isConnected)
        client.close()

        serverJob.cancel()
    }
}
