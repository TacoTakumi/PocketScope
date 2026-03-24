package com.pocketscope.indi.server

import android.util.Log
import com.pocketscope.device.DeviceRegistry
import com.pocketscope.indi.device.IndiCameraDevice
import com.pocketscope.network.ApprovalManager
import com.pocketscope.network.NetworkFilter
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
 * At construction, it uses the provided [DeviceRegistry] to set up
 * INDI devices for each capture device plus a single focuser.
 * Each camera device's onLensSwitch callback is wired to
 * [IndiFocuserDevice.switchActiveLens] so that connecting a CCD device resets
 * the focuser position (per D-14).
 *
 * Socket configuration:
 * - reuseAddress=true on the server socket for quick restarts
 * - keepAlive=true on accepted client connections for WiFi resilience
 */
class IndiServer(
    private val registry: DeviceRegistry,
    private val scope: CoroutineScope,
    private val port: Int = DEFAULT_PORT,
    private val host: String = "0.0.0.0",
    private val onClientEvent: ((String) -> Unit)? = null,
    private val onCaptureComplete: ((success: Boolean) -> Unit)? = null,
    private val approvalManager: ApprovalManager? = null
) {
    companion object {
        private const val TAG = "IndiServer"
        /** Standard INDI protocol port. */
        const val DEFAULT_PORT = 7624
    }

    private val focuserDevice = IndiFocuserDevice(registry.focuserDevice)

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

    private val cameraDevices = registry.captureDevices.map { captureDevice ->
        IndiCameraDevice(
            captureDevice = captureDevice,
            scope = scope,
            onLensSwitch = { switchedLensInfo ->
                focuserDevice.switchActiveLens(switchedLensInfo)
            },
            blobCallback = blobCallback,
            onCaptureComplete = onCaptureComplete
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

        // Retry bind in case the previous socket is still in TIME_WAIT after a rapid toggle
        var attempts = 0
        while (true) {
            try {
                serverSocket = aSocket(selectorManager!!)
                    .tcp()
                    .bind(host, port) {
                        reuseAddress = true
                    }
                break
            } catch (e: java.net.BindException) {
                if (++attempts >= 5) throw e
                Log.w(TAG, "Port $port still in use, retrying in ${attempts * 200}ms (attempt $attempts/5)")
                kotlinx.coroutines.delay(attempts * 200L)
            }
        }

        Log.i(TAG, "INDI server listening on $host:$port with ${allDevices.size} devices (${registry.captureDevices.size} lenses)")

        acceptJob = launch {
            while (isActive) {
                val clientSocket = serverSocket!!.accept()
                Log.i(TAG, "Client connected: ${clientSocket.remoteAddress}")
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
        val rawIp = socket.remoteAddress.toString()
        val cleanIp = NetworkFilter.extractIp(rawIp)

        // Step 1: Reject non-private IPs immediately (NET-01)
        if (!NetworkFilter.isPrivateIp(cleanIp)) {
            Log.w(TAG, "Rejected non-private IP: $cleanIp")
            onClientEvent?.invoke("Rejected non-private IP: $cleanIp")
            try { socket.close() } catch (_: Exception) {}
            return
        }

        // Step 2: Await user approval if ApprovalManager is provided (NET-02, NET-03)
        if (approvalManager != null) {
            val approved = approvalManager.awaitApproval(cleanIp)
            if (!approved) {
                Log.i(TAG, "Connection denied by user: $cleanIp")
                onClientEvent?.invoke("Connection denied: $cleanIp")
                try { socket.close() } catch (_: Exception) {}
                return
            }
        }

        // Step 3: Existing connection handling
        val clientIp = rawIp
        try {
            enableKeepAlive(socket)
            onClientEvent?.invoke("Client connected ($clientIp)")

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
                Log.i(TAG, "Client session ended: ${socket.remoteAddress}")
                activeSessions.remove(session)
                onClientEvent?.invoke("Client disconnected ($clientIp)")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Client error: ${e.message}", e)
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
        // NOTE: Do NOT call registry.closeAll() here.
        // DeviceRegistry lifecycle is managed by IndiServerService,
        // not by individual protocol servers.
    }
}
