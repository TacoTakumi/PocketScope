@file:Suppress("DEPRECATION")

package com.pocketscope.indi.server

import android.util.Base64
import android.util.Base64OutputStream
import android.util.Log
import com.pocketscope.indi.device.IndiDevice
import com.pocketscope.indi.properties.IndiXmlWriter
import com.pocketscope.indi.protocol.IndiProtocolParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import java.io.InputStream
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

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
    private val devices: List<IndiDevice>
) {
    companion object {
        private const val TAG = "ClientSession"
    }
    private val writer = OutputStreamWriter(outputStream, Charsets.UTF_8)

    // Track BLOB enable state per device. Values: "Never" (default), "Also", "Only"
    private val blobEnabled = mutableMapOf<String, String>()

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
                    val xmlWriter = IndiXmlWriter(writer)
                    property.writeSetXml(xmlWriter)
                    xmlWriter.flush()
                }
            }
        }

        // Run parser on IO dispatcher (blocks until stream closes)
        launch(Dispatchers.IO) {
            val parser = IndiProtocolParser(inputStream)
            parser.parseStream { name, attributes, elements ->
                Log.d(TAG, "Received command: $name attrs=$attributes")
                when (name) {
                    "getProperties" -> {
                        synchronized(writer) {
                            Log.d(TAG, "Sending property definitions for ${devices.size} devices")
                            sendPropertyDefinitions()
                            Log.d(TAG, "Property definitions sent")
                        }
                    }
                    "newNumberVector", "newSwitchVector", "newTextVector" -> {
                        val deviceName = attributes["device"] ?: return@parseStream
                        val propertyName = attributes["name"] ?: return@parseStream
                        val targetDevice = devices.find { it.deviceName == deviceName }
                            ?: return@parseStream
                        targetDevice.handleNewProperty(propertyName, elements)
                    }
                    "enableBLOB" -> {
                        val deviceName = attributes["device"] ?: return@parseStream
                        val mode = elements["__text__"] ?: "Also"
                        blobEnabled[deviceName] = mode
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
        val xmlWriter = IndiXmlWriter(writer)
        for (device in devices) {
            for (property in device.properties) {
                property.writeXml(xmlWriter)
            }
        }
        xmlWriter.flush()
    }

    /**
     * Streams a FITS file as a Base64-encoded INDI BLOB to this client.
     *
     * Only sends if enableBLOB state for this device is "Also" or "Only".
     * Uses [Base64OutputStream] to stream Base64 encoding directly to TCP
     * without holding the full encoded string in memory (IMG-05).
     *
     * The XML is written directly as text (not via XmlWriter) because the
     * Base64 payload must be streamed between the oneBLOB tags without
     * XML escaping.
     */
    fun streamBlob(deviceName: String, fitsBytes: ByteArray) {
        val mode = blobEnabled[deviceName] ?: "Never"
        if (mode == "Never") return

        val timestamp = DateTimeFormatter.ISO_INSTANT.format(
            Instant.now().atOffset(ZoneOffset.UTC)
        )

        synchronized(writer) {
            writer.write("""<setBLOBVector device="$deviceName" name="CCD1" state="Ok" timestamp="$timestamp">""")
            writer.write("""<oneBLOB name="CCD1" size="${fitsBytes.size}" format=".fits">""")
            writer.flush()

            // Stream Base64 directly to TCP -- never hold full encoded string (IMG-05)
            val base64Stream = Base64OutputStream(outputStream, Base64.NO_WRAP)
            fitsBytes.inputStream().copyTo(base64Stream, bufferSize = 4096)
            base64Stream.close()  // Flushes final padding bytes

            writer.write("</oneBLOB></setBLOBVector>")
            writer.flush()
        }
    }
}
