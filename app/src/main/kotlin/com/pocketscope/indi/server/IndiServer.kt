package com.pocketscope.indi.server

import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.ServerSocket
import io.ktor.network.sockets.Socket
import io.ktor.network.sockets.aSocket
import io.ktor.network.sockets.openReadChannel
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
     * then reads from the socket until the client disconnects.
     * Will be expanded in subsequent plans to parse INDI XML commands
     * and route them to device drivers.
     */
    private suspend fun handleClient(socket: Socket) {
        try {
            // Enable keepAlive on accepted connections for WiFi resilience
            enableKeepAlive(socket)

            val readChannel = socket.openReadChannel()
            while (!readChannel.isClosedForRead) {
                // Wait for data from the client; returns false when channel closes
                if (!readChannel.awaitContent(1)) break
                // Future: parse INDI XML commands from readChannel
            }
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
