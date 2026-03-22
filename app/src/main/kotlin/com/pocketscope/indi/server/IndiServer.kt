package com.pocketscope.indi.server

import android.hardware.camera2.CameraManager
import android.os.Handler
import com.pocketscope.camera.CameraSessionManager
import com.pocketscope.camera.LensEnumerator
import com.pocketscope.camera.RawCaptureSession
import com.pocketscope.indi.device.IndiCameraDevice
import com.pocketscope.indi.device.IndiDevice
import com.pocketscope.indi.device.IndiFocuserDevice
import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.ServerSocket
import io.ktor.network.sockets.Socket
import io.ktor.network.sockets.aSocket
import io.ktor.network.sockets.openReadChannel
import io.ktor.network.sockets.openWriteChannel
import io.ktor.utils.io.jvm.javaio.toInputStream
import io.ktor.utils.io.jvm.javaio.toOutputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.StandardSocketOptions
import java.nio.channels.SocketChannel
import java.util.concurrent.CopyOnWriteArrayList

/**
 * INDI protocol TCP server.
 *
 * Listens on the standard INDI port (7624) for client connections from
 * astronomy software such as KStars/Ekos. Each connected client is handled
 * in its own coroutine.
 *
 * At construction, enumerates all rear-facing camera lenses via [LensEnumerator],
 * creates one [IndiCameraDevice] per lens plus a single [IndiFocuserDevice].
 * Each camera device's onLensSwitch callback is wired to
 * [IndiFocuserDevice.switchActiveLens] so that connecting a CCD device resets
 * the focuser position (per D-14).
 *
 * Socket configuration:
 * - reuseAddress=true on the server socket for quick restarts
 * - keepAlive=true on accepted client connections for WiFi resilience
 */
class IndiServer(
    private val cameraManager: CameraManager,
    private val handler: Handler,
    private val port: Int = DEFAULT_PORT,
    private val host: String = "0.0.0.0"
) {
    companion object {
        /** Standard INDI protocol port. */
        const val DEFAULT_PORT = 7624
    }

    private val serverScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val sessionManager = CameraSessionManager(cameraManager, handler)
    private val rawCaptureSession = RawCaptureSession(handler)
    private val lenses = LensEnumerator.enumerateLenses(cameraManager)
    private val focuserDevice = IndiFocuserDevice()

    /** Active client sessions for BLOB broadcasting. Thread-safe for concurrent iteration. */
    private val activeSessions = CopyOnWriteArrayList<ClientSession>()

    /** Number of currently connected INDI clients. */
    val connectedClientCount: Int
        get() = activeSessions.size

    /** Broadcasts FITS data to all active sessions that have enabled BLOBs. */
    private val blobCallback: suspend (String, ByteArray) -> Unit = { deviceName, fitsBytes ->
        for (session in activeSessions) {
            try {
                session.streamBlob(deviceName, fitsBytes)
            } catch (_: Exception) {
                // Session may have disconnected; streamBlob failure
                // is non-fatal for other sessions
            }
        }
    }

    private val cameraDevices = lenses.map { lensInfo ->
        IndiCameraDevice(
            lensInfo = lensInfo,
            sessionManager = sessionManager,
            scope = serverScope,
            onLensSwitch = { switchedLensInfo ->
                focuserDevice.switchActiveLens(switchedLensInfo)
            },
            rawCaptureSession = rawCaptureSession,
            blobCallback = blobCallback
        )
    }
    private val allDevices: List<IndiDevice> = cameraDevices + focuserDevice

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
     * from all registered devices.
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
                devices = allDevices
            )
            activeSessions.add(session)
            try {
                session.handleCommands()
            } finally {
                activeSessions.remove(session)
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
        activeSessions.clear()
        sessionManager.closeAll()
    }
}
