@file:Suppress("DEPRECATION")

package com.pocketscope.indi.server

import com.pocketscope.indi.device.MockDevice
import com.pocketscope.indi.protocol.IndiProtocolParser
import nl.adaptivity.xmlutil.XmlStreaming
import java.io.InputStream
import java.io.OutputStream
import java.io.OutputStreamWriter

/**
 * Manages a single INDI client connection.
 *
 * Reads incoming INDI XML commands via [IndiProtocolParser], responds with
 * property definitions from registered devices, and broadcasts property
 * updates when device state changes (D-07).
 *
 * Operates on raw InputStream/OutputStream for testability — IndiServer
 * bridges from Ktor's channels to these streams.
 */
class ClientSession(
    private val inputStream: InputStream,
    private val outputStream: OutputStream,
    private val devices: List<MockDevice>
) {
    private val writer = OutputStreamWriter(outputStream, Charsets.UTF_8)

    /**
     * Processes incoming INDI commands until the input stream closes.
     *
     * Currently handles:
     * - `getProperties` — responds with defXxxVector for all device properties
     */
    fun handleCommands() {
        val parser = IndiProtocolParser(inputStream)
        parser.parseStream { name, _ ->
            when (name) {
                "getProperties" -> sendPropertyDefinitions()
            }
        }
    }

    /**
     * Sends all property definitions from all registered devices to this client.
     * This is the standard INDI response to a `getProperties` request.
     */
    private fun sendPropertyDefinitions() {
        for (device in devices) {
            for (property in device.properties) {
                val xmlWriter = XmlStreaming.newWriter(writer)
                property.writeXml(xmlWriter)
                xmlWriter.flush()
            }
        }
        writer.flush()
    }
}
