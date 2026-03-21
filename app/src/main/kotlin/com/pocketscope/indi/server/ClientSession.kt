@file:Suppress("DEPRECATION")

package com.pocketscope.indi.server

import com.pocketscope.indi.device.MockDevice
import com.pocketscope.indi.protocol.IndiProtocolParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import nl.adaptivity.xmlutil.XmlStreaming
import java.io.InputStream
import java.io.OutputStream
import java.io.OutputStreamWriter

/**
 * Manages a single INDI client connection.
 *
 * Reads incoming INDI XML commands via [IndiProtocolParser], responds with
 * property definitions from registered devices, and broadcasts property
 * updates when device state changes via SharedFlow collection.
 *
 * Uses coroutineScope to run the command parser and property broadcast
 * collector concurrently. The output stream is protected by synchronized
 * blocks to prevent interleaving of XML from concurrent writes.
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
     * Processes incoming INDI commands and broadcasts property updates
     * until the input stream closes.
     *
     * Launches two concurrent coroutines within a coroutineScope:
     * 1. A broadcast collector that merges all device property SharedFlows
     *    and writes setXxxVector XML updates to the output stream
     * 2. The command parser that processes incoming INDI XML commands
     *
     * When the parser returns (client disconnected), the entire scope
     * is cancelled, stopping the broadcast collector.
     */
    suspend fun handleCommands() = coroutineScope {
        // Merge all property update flows from all devices
        val updateFlows = devices.flatMap { device ->
            device.properties.map { it.updates }
        }

        // Launch broadcast collector for property updates
        val broadcastJob: Job = launch(Dispatchers.IO) {
            updateFlows.merge().collect { property ->
                synchronized(writer) {
                    val xmlWriter = XmlStreaming.newWriter(writer)
                    property.writeSetXml(xmlWriter)
                    xmlWriter.flush()
                    writer.flush()
                }
            }
        }

        // Run parser on IO dispatcher (blocks until stream closes)
        launch(Dispatchers.IO) {
            val parser = IndiProtocolParser(inputStream)
            parser.parseStream { name, attributes, elements ->
                when (name) {
                    "getProperties" -> {
                        synchronized(writer) {
                            sendPropertyDefinitions()
                        }
                    }
                }
            }
            // Parser returned = input stream closed, cancel broadcast
            broadcastJob.cancel()
        }
    }

    /**
     * Sends all property definitions from all registered devices to this client.
     * This is the standard INDI response to a `getProperties` request.
     *
     * Must be called within synchronized(writer) from the caller.
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
