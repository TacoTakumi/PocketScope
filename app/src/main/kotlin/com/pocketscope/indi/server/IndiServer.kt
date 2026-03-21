package com.pocketscope.indi.server

import com.pocketscope.indi.device.MockDevice
import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.ServerSocket
import io.ktor.network.sockets.Socket
import io.ktor.network.sockets.aSocket
import io.ktor.network.sockets.openReadChannel
import io.ktor.network.sockets.openWriteChannel
import io.ktor.utils.io.jvm.javaio.toInputStream
import io.ktor.utils.io.jvm.javaio.toOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.StandardSocketOptions
import java.nio.channels.SocketChannel

/**
 * INDI protocol TCP server.
 *
 * Listens on the standard INDI port (7624) for client connections from
 * astronomy software such as KStars/Ekos. Each connected client is handled
 * in its own coroutine.
 *
 * Socket configuration:
 * - reuseAddress=true on the server socket for quick restarts
 * - keepAlive=true on accepted client connections for WiFi resilience
 */
class IndiServer(
    private val port: Int = DEFAULT_PORT,
    private val host: String = "0.0.0.0"
) {
    companion object {
        /** Standard INDI protocol port. */
        const val DEFAULT_PORT = 7624
    }

    private var selectorManager: SelectorManager? = null
    private var serverSocket: ServerSocket? = null
    private var acceptJob: Job? = null

    /**
     * Starts the INDI TCP server, binding to [host]:[port] and accepting
     * client connections in a loop.
     *
     * This is a suspending function that runs until cancelled.
     */
    suspend fun start() = coroutineScope {
        selectorManager = SelectorManager(Dispatchers.IO)

        serverSocket = aSocket(selectorManager!!)
            .tcp()
            .bind(host, port) {
                reuseAddress = true
            }

        acceptJob = launch {
            while (isActive) {
                val clientSocket = serverSocket!!.accept()
                launch {
                    handleClient(clientSocket)
                }
            }
        }
    }

    /**
     * Handles a single connected INDI client.
     *
     * Sets TCP keepAlive on the accepted connection for WiFi resilience,
     * then delegates to [ClientSession] which uses [IndiProtocolParser]
     * to process INDI XML commands and respond with property definitions
     * from the global [MockDevice] singleton (D-04).
     */
    private suspend fun handleClient(socket: Socket) {
        try {
            enableKeepAlive(socket)

            val readChannel = socket.openReadChannel()
            val writeChannel = socket.openWriteChannel(autoFlush = true)
            val inputStream = readChannel.toInputStream()
            val outputStream = writeChannel.toOutputStream()

            val session = ClientSession(
                inputStream = inputStream,
                outputStream = outputStream,
                devices = listOf(MockDevice.instance)
            )
            session.handleCommands()
        } catch (_: Exception) {
            // Client disconnected or connection error
        } finally {
            try {
                socket.close()
            } catch (_: Exception) {
                // Ignore close errors
            }
        }
    }

    /**
     * Enables TCP keepAlive on the underlying NIO socket channel.
     * This detects dropped WiFi connections promptly.
     */
    private fun enableKeepAlive(socket: Socket) {
        try {
            // Access the underlying Java NIO channel to set keepAlive
            val field = socket.javaClass.getDeclaredField("channel")
            field.isAccessible = true
            val channel = field.get(socket) as? SocketChannel
            channel?.setOption(StandardSocketOptions.SO_KEEPALIVE, true)
        } catch (_: Exception) {
            // Best-effort: keepAlive is desirable but not critical
        }
    }

    /**
     * Stops the server and releases all resources.
     */
    fun stop() {
        acceptJob?.cancel()
        acceptJob = null
        try {
            serverSocket?.close()
        } catch (_: Exception) {
            // Ignore close errors
        }
        serverSocket = null
        selectorManager?.close()
        selectorManager = null
    }
}
