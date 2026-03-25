package com.pocketscope.alpaca.server

import android.util.Log
import com.pocketscope.BuildConfig
import com.pocketscope.alpaca.device.AlpacaCameraDevice
import com.pocketscope.alpaca.device.AlpacaDevice
import com.pocketscope.alpaca.device.AlpacaFocuserDevice
import com.pocketscope.alpaca.model.AlpacaErrors
import com.pocketscope.alpaca.model.BoolResponse
import com.pocketscope.alpaca.model.ConfiguredDevice
import com.pocketscope.alpaca.model.ConfiguredDevicesResponse
import com.pocketscope.alpaca.model.IntArrayResponse
import com.pocketscope.alpaca.model.IntResponse
import com.pocketscope.alpaca.model.MethodResponse
import com.pocketscope.alpaca.model.ServerDescription
import com.pocketscope.alpaca.model.ServerDescriptionResponse
import com.pocketscope.alpaca.model.StringArrayResponse
import com.pocketscope.alpaca.model.StringResponse
import com.pocketscope.device.DeviceRegistry
import com.pocketscope.network.ApprovalManager
import com.pocketscope.network.NetworkFilter
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.origin
import io.ktor.server.request.receiveParameters
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.put
import io.ktor.server.routing.routing
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * ASCOM Alpaca HTTP server with UDP discovery.
 *
 * Runs a Ktor CIO embedded HTTP server on [port] (default 11111) serving
 * the Alpaca management API and common device endpoints. A UDP listener
 * on port 32227 responds to "alpacadiscovery1" broadcasts.
 *
 * All HTTP requests pass through a security interceptor that checks
 * [NetworkFilter] for private-IP enforcement and [ApprovalManager]
 * for user-approved connections.
 *
 * Mirrors the [com.pocketscope.indi.server.IndiServer] lifecycle pattern:
 * created by [com.pocketscope.service.IndiServerService], started/stopped
 * via the isAlpacaEnabled flow.
 */
class AlpacaServer(
    private val registry: DeviceRegistry,
    private val scope: CoroutineScope,
    private val port: Int = DEFAULT_PORT,
    private val host: String = "0.0.0.0",
    private val onEvent: ((String) -> Unit)? = null,
    private val approvalManager: ApprovalManager? = null
) {
    companion object {
        private const val TAG = "AlpacaServer"
        const val DEFAULT_PORT = 11111
        const val DISCOVERY_PORT = 32227
    }

    private val alpacaDevices: List<AlpacaDevice> = buildList {
        registry.captureDevices.forEachIndexed { index, captureDevice ->
            add(AlpacaCameraDevice(captureDevice, index, UUID.randomUUID().toString()))
        }
        add(AlpacaFocuserDevice(registry.focuserDevice, UUID.randomUUID().toString()))
    }

    private val serverTransactionCounter = AtomicInteger(1)
    private fun nextServerTransactionId(): Int = serverTransactionCounter.getAndIncrement()

    private var engine: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? = null
    private var discoveryJob: Job? = null

    /**
     * Case-insensitive parameter extraction from both query parameters (GET)
     * and form body parameters (PUT/POST), per Alpaca spec requirement that
     * parameter names are case-insensitive.
     */
    private suspend fun io.ktor.server.application.ApplicationCall.alpacaParam(name: String): String? {
        // Check query parameters first (case-insensitive)
        val queryMatch = request.queryParameters.entries()
            .firstOrNull { it.key.equals(name, ignoreCase = true) }
            ?.value?.firstOrNull()
        if (queryMatch != null) return queryMatch

        // For PUT/POST, also check form body parameters
        if (request.local.method == HttpMethod.Put || request.local.method == HttpMethod.Post) {
            val formParams = receiveParameters()
            val formMatch = formParams.entries()
                .firstOrNull { it.key.equals(name, ignoreCase = true) }
                ?.value?.firstOrNull()
            if (formMatch != null) return formMatch
        }

        return null
    }

    private fun findDevice(deviceType: String, deviceNumber: Int): AlpacaDevice? =
        alpacaDevices.firstOrNull {
            it.deviceType.equals(deviceType, ignoreCase = true) && it.deviceNumber == deviceNumber
        }

    /**
     * Starts the Alpaca HTTP server and UDP discovery listener.
     *
     * The HTTP server runs on [host]:[port] with ContentNegotiation for JSON
     * serialization and a global security interceptor. The UDP discovery
     * listener runs on port 32227 in a separate coroutine.
     */
    fun start() {
        val versionName = BuildConfig.VERSION_NAME
        engine = embeddedServer(CIO, port = port, host = host) {
            install(ContentNegotiation) {
                json(Json { encodeDefaults = true })
            }

            // Global security interceptor (D-02)
            intercept(ApplicationCallPipeline.Plugins) {
                val remoteIp = call.request.origin.remoteHost
                if (!NetworkFilter.isPrivateIp(remoteIp)) {
                    call.respond(HttpStatusCode.Forbidden, "Access denied")
                    finish()
                    return@intercept
                }
                if (approvalManager != null) {
                    val approved = approvalManager.awaitApproval(remoteIp)
                    if (!approved) {
                        call.respond(HttpStatusCode.Forbidden, "Connection not approved")
                        finish()
                        return@intercept
                    }
                }
            }

            routing {
                // --- Management API (ALP-02) ---

                get("/management/apiversions") {
                    val clientTxId = call.alpacaParam("ClientTransactionID")?.toIntOrNull() ?: 0
                    call.respond(IntArrayResponse(
                        value = listOf(1),
                        clientTransactionID = clientTxId,
                        serverTransactionID = nextServerTransactionId()
                    ))
                }

                get("/management/v1/description") {
                    val clientTxId = call.alpacaParam("ClientTransactionID")?.toIntOrNull() ?: 0
                    call.respond(ServerDescriptionResponse(
                        value = ServerDescription(
                            serverName = "PocketScope",
                            manufacturer = "PocketScope",
                            manufacturerVersion = versionName,
                            location = "Android Device"
                        ),
                        clientTransactionID = clientTxId,
                        serverTransactionID = nextServerTransactionId()
                    ))
                }

                get("/management/v1/configureddevices") {
                    val clientTxId = call.alpacaParam("ClientTransactionID")?.toIntOrNull() ?: 0
                    call.respond(ConfiguredDevicesResponse(
                        value = alpacaDevices.map {
                            ConfiguredDevice(
                                deviceName = it.deviceName,
                                deviceType = it.deviceType,
                                deviceNumber = it.deviceNumber,
                                uniqueID = it.uniqueId
                            )
                        },
                        clientTransactionID = clientTxId,
                        serverTransactionID = nextServerTransactionId()
                    ))
                }

                // --- Common Device Endpoints (ALP-05) ---

                get("/api/v1/{device_type}/{device_number}/{method}") {
                    val deviceType = call.parameters["device_type"] ?: ""
                    val deviceNumber = call.parameters["device_number"]?.toIntOrNull() ?: -1
                    val method = call.parameters["method"]?.lowercase() ?: ""
                    val clientTxId = call.alpacaParam("ClientTransactionID")?.toIntOrNull() ?: 0
                    val serverTxId = nextServerTransactionId()

                    val device = findDevice(deviceType, deviceNumber)
                    if (device == null) {
                        call.respond(HttpStatusCode.BadRequest, MethodResponse(
                            clientTransactionID = clientTxId,
                            serverTransactionID = serverTxId,
                            errorNumber = AlpacaErrors.INVALID_VALUE,
                            errorMessage = "Unknown device: $deviceType/$deviceNumber"
                        ))
                        return@get
                    }

                    when (method) {
                        "connected" -> call.respond(BoolResponse(
                            value = device.isConnected,
                            clientTransactionID = clientTxId,
                            serverTransactionID = serverTxId
                        ))
                        "name" -> call.respond(StringResponse(
                            value = device.deviceName,
                            clientTransactionID = clientTxId,
                            serverTransactionID = serverTxId
                        ))
                        "description" -> call.respond(StringResponse(
                            value = device.description,
                            clientTransactionID = clientTxId,
                            serverTransactionID = serverTxId
                        ))
                        "driverinfo" -> call.respond(StringResponse(
                            value = device.driverInfo,
                            clientTransactionID = clientTxId,
                            serverTransactionID = serverTxId
                        ))
                        "driverversion" -> call.respond(StringResponse(
                            value = device.driverVersion,
                            clientTransactionID = clientTxId,
                            serverTransactionID = serverTxId
                        ))
                        "interfaceversion" -> call.respond(IntResponse(
                            value = device.interfaceVersion,
                            clientTransactionID = clientTxId,
                            serverTransactionID = serverTxId
                        ))
                        "supportedactions" -> call.respond(StringArrayResponse(
                            value = device.supportedActions(),
                            clientTransactionID = clientTxId,
                            serverTransactionID = serverTxId
                        ))
                        "connecting" -> call.respond(BoolResponse(
                            value = false,
                            clientTransactionID = clientTxId,
                            serverTransactionID = serverTxId
                        ))
                        else -> call.respond(MethodResponse(
                            clientTransactionID = clientTxId,
                            serverTransactionID = serverTxId,
                            errorNumber = AlpacaErrors.NOT_IMPLEMENTED,
                            errorMessage = "Method not implemented: $method"
                        ))
                    }
                }

                put("/api/v1/{device_type}/{device_number}/{method}") {
                    val deviceType = call.parameters["device_type"] ?: ""
                    val deviceNumber = call.parameters["device_number"]?.toIntOrNull() ?: -1
                    val method = call.parameters["method"]?.lowercase() ?: ""
                    val clientTxId = call.alpacaParam("ClientTransactionID")?.toIntOrNull() ?: 0
                    val serverTxId = nextServerTransactionId()

                    val device = findDevice(deviceType, deviceNumber)
                    if (device == null) {
                        call.respond(HttpStatusCode.BadRequest, MethodResponse(
                            clientTransactionID = clientTxId,
                            serverTransactionID = serverTxId,
                            errorNumber = AlpacaErrors.INVALID_VALUE,
                            errorMessage = "Unknown device: $deviceType/$deviceNumber"
                        ))
                        return@put
                    }

                    when (method) {
                        "connected" -> {
                            val connectedParam = call.alpacaParam("Connected")
                            val connectedValue = connectedParam?.lowercase() == "true"
                            device.isConnected = connectedValue
                            call.respond(MethodResponse(
                                clientTransactionID = clientTxId,
                                serverTransactionID = serverTxId
                            ))
                        }
                        "connect" -> {
                            device.isConnected = true
                            call.respond(MethodResponse(
                                clientTransactionID = clientTxId,
                                serverTransactionID = serverTxId
                            ))
                        }
                        "disconnect" -> {
                            device.isConnected = false
                            call.respond(MethodResponse(
                                clientTransactionID = clientTxId,
                                serverTransactionID = serverTxId
                            ))
                        }
                        else -> call.respond(MethodResponse(
                            clientTransactionID = clientTxId,
                            serverTransactionID = serverTxId,
                            errorNumber = AlpacaErrors.NOT_IMPLEMENTED,
                            errorMessage = "Method not implemented: $method"
                        ))
                    }
                }
            }
        }

        engine?.start(wait = false)
        Log.i(TAG, "Alpaca HTTP server started on $host:$port with ${alpacaDevices.size} devices")
        onEvent?.invoke("Alpaca HTTP server listening on port $port")

        // Start UDP discovery listener
        discoveryJob = scope.launch(Dispatchers.IO) {
            runDiscoveryListener()
        }
    }

    /**
     * Stops the HTTP server and UDP discovery listener.
     */
    fun stop() {
        discoveryJob?.cancel()
        discoveryJob = null
        engine?.stop(500, 1000)
        engine = null
        Log.i(TAG, "Alpaca server stopped")
    }

    /**
     * UDP discovery listener on port 32227.
     *
     * Listens for "alpacadiscovery1" broadcast packets and responds with
     * the Alpaca HTTP port in JSON format. Only responds to private-network
     * IPs per D-05.
     */
    private suspend fun runDiscoveryListener() {
        val socket = DatagramSocket(null).apply {
            reuseAddress = true
            bind(InetSocketAddress("0.0.0.0", DISCOVERY_PORT))
        }
        try {
            val buffer = ByteArray(64)
            while (kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]?.isActive != false) {
                val packet = DatagramPacket(buffer, buffer.size)
                socket.receive(packet)  // blocking, OK on IO dispatcher
                val message = String(packet.data, 0, packet.length).trim()
                val senderIp = packet.address.hostAddress ?: continue
                if (message == "alpacadiscovery1" && NetworkFilter.isPrivateIp(senderIp)) {
                    val response = """{"AlpacaPort":$port}""".toByteArray()
                    val responsePacket = DatagramPacket(response, response.size, packet.address, packet.port)
                    socket.send(responsePacket)
                    onEvent?.invoke("Discovery response sent to $senderIp")
                }
            }
        } finally {
            socket.close()
        }
    }
}
