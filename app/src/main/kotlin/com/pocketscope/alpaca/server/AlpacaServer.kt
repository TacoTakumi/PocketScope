package com.pocketscope.alpaca.server

import android.util.Log
import com.pocketscope.BuildConfig
import com.pocketscope.alpaca.device.AlpacaCameraDevice
import com.pocketscope.alpaca.device.AlpacaDevice
import com.pocketscope.alpaca.device.AlpacaFocuserDevice
import com.pocketscope.alpaca.device.DeviceMethodResult
import com.pocketscope.alpaca.model.AlpacaErrors
import com.pocketscope.alpaca.model.BoolResponse
import com.pocketscope.alpaca.model.ConfiguredDevice
import com.pocketscope.alpaca.model.ConfiguredDevicesResponse
import com.pocketscope.alpaca.model.DoubleResponse
import com.pocketscope.alpaca.model.ImageArrayResponse
import com.pocketscope.alpaca.model.IntArrayResponse
import com.pocketscope.alpaca.model.IntResponse
import com.pocketscope.alpaca.model.MethodResponse
import com.pocketscope.alpaca.model.ServerDescription
import com.pocketscope.alpaca.model.ServerDescriptionResponse
import com.pocketscope.alpaca.model.StringArrayResponse
import com.pocketscope.alpaca.model.StringResponse
import com.pocketscope.device.DeviceRegistry
import com.pocketscope.network.ApprovalManager
import com.pocketscope.settings.SettingsRepository
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
import io.ktor.server.response.respondBytes
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
    private val settings: SettingsRepository,
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

    private lateinit var alpacaDevices: List<AlpacaDevice>

    private suspend fun initDevices() {
        alpacaDevices = buildList {
            registry.captureDevices.forEachIndexed { index, captureDevice ->
                add(AlpacaCameraDevice(
                    captureDevice = captureDevice,
                    deviceNumber = index,
                    uniqueId = settings.getDeviceUuid("camera", index),
                    scope = scope,
                    focusDioptersProvider = { registry.focuserDevice.currentDiopters() }
                ))
            }
            add(AlpacaFocuserDevice(
                registry.focuserDevice,
                settings.getDeviceUuid("focuser", 0)
            ))
        }
    }

    private val serverTransactionCounter = AtomicInteger(1)
    private fun nextServerTransactionId(): Int = serverTransactionCounter.getAndIncrement()

    private var engine: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? = null
    private var discoveryJob: Job? = null

    /**
     * Case-insensitive parameter lookup from a pre-parsed parameter map.
     * The map must be built once per request via [buildParamMap] to avoid
     * consuming the request body more than once.
     */
    private fun paramFrom(params: Map<String, String>, name: String): String? =
        params[name.lowercase()]

    /** Parse ClientTransactionID as an unsigned 32-bit value, clamping negatives to 0. */
    private fun parseClientTxId(params: Map<String, String>): Int {
        val raw = paramFrom(params, "ClientTransactionID")?.toLongOrNull() ?: return 0
        if (raw < 0 || raw > UInt.MAX_VALUE.toLong()) return 0
        return raw.toInt()
    }

    /**
     * Builds a merged, case-preserving parameter map from query parameters
     * and (for PUT/POST) form body parameters. Must be called once per
     * request — subsequent lookups use [paramFrom].
     */
    private suspend fun io.ktor.server.application.ApplicationCall.buildParamMap(): Map<String, String> {
        val map = mutableMapOf<String, String>()
        // Query parameters
        request.queryParameters.entries().forEach { (key, values) ->
            values.firstOrNull()?.let { map[key.lowercase()] = it }
        }
        // Form body parameters (PUT/POST only)
        if (request.local.method == HttpMethod.Put || request.local.method == HttpMethod.Post) {
            receiveParameters().entries().forEach { (key, values) ->
                values.firstOrNull()?.let { map.putIfAbsent(key.lowercase(), it) }
            }
        }
        return map
    }

    private fun findDevice(deviceType: String, deviceNumber: Int): AlpacaDevice? =
        alpacaDevices.firstOrNull {
            it.deviceType.equals(deviceType, ignoreCase = true) && it.deviceNumber == deviceNumber
        }

    /**
     * Maps a [DeviceMethodResult] to the appropriate HTTP response.
     * - Known results (IntVal, BoolVal, etc.) → HTTP 200 with typed JSON
     * - NotImplemented → HTTP 200 with NOT_IMPLEMENTED error in body
     * - InvalidValue → HTTP 400
     * - Unknown → HTTP 400 (method not recognized by this device type)
     */
    private suspend fun respondDeviceResult(
        call: io.ktor.server.application.ApplicationCall,
        result: DeviceMethodResult,
        method: String,
        clientTxId: Int,
        serverTxId: Int
    ) {
        when (result) {
            is DeviceMethodResult.IntVal -> call.respond(IntResponse(
                value = result.value, clientTransactionID = clientTxId, serverTransactionID = serverTxId
            ))
            is DeviceMethodResult.BoolVal -> call.respond(BoolResponse(
                value = result.value, clientTransactionID = clientTxId, serverTransactionID = serverTxId
            ))
            is DeviceMethodResult.DoubleVal -> call.respond(DoubleResponse(
                value = result.value, clientTransactionID = clientTxId, serverTransactionID = serverTxId
            ))
            is DeviceMethodResult.StringVal -> call.respond(StringResponse(
                value = result.value, clientTransactionID = clientTxId, serverTransactionID = serverTxId
            ))
            is DeviceMethodResult.StringListVal -> call.respond(StringArrayResponse(
                value = result.value, clientTransactionID = clientTxId, serverTransactionID = serverTxId
            ))
            is DeviceMethodResult.IntListVal -> call.respond(IntArrayResponse(
                value = result.value, clientTransactionID = clientTxId, serverTransactionID = serverTxId
            ))
            is DeviceMethodResult.Ok -> call.respond(MethodResponse(
                clientTransactionID = clientTxId, serverTransactionID = serverTxId
            ))
            is DeviceMethodResult.NotImplemented -> call.respond(MethodResponse(
                clientTransactionID = clientTxId, serverTransactionID = serverTxId,
                errorNumber = AlpacaErrors.NOT_IMPLEMENTED,
                errorMessage = "Method not implemented: ${result.method}"
            ))
            is DeviceMethodResult.InvalidValue -> call.respond(HttpStatusCode.BadRequest, MethodResponse(
                clientTransactionID = clientTxId, serverTransactionID = serverTxId,
                errorNumber = AlpacaErrors.INVALID_VALUE,
                errorMessage = result.message
            ))
            is DeviceMethodResult.InvalidOperation -> call.respond(MethodResponse(
                clientTransactionID = clientTxId, serverTransactionID = serverTxId,
                errorNumber = AlpacaErrors.INVALID_OPERATION,
                errorMessage = result.message
            ))
            is DeviceMethodResult.ImageData -> {
                val accept = call.request.headers["Accept"] ?: ""
                val wantJson = accept.contains("application/json", ignoreCase = true) &&
                    !accept.contains("application/imagebytes", ignoreCase = true)
                if (wantJson) {
                    Log.i(TAG, "Serving ImageArray JSON: ${result.width}x${result.height}")
                    respondImageJson(call, result, clientTxId, serverTxId)
                } else {
                    Log.i(TAG, "Serving ImageBytes: ${result.width}x${result.height}, ${result.rawBytes.size} bytes")
                    respondImageBytes(call, result, clientTxId, serverTxId)
                }
            }
            is DeviceMethodResult.Unknown -> call.respond(HttpStatusCode.BadRequest, MethodResponse(
                clientTransactionID = clientTxId, serverTransactionID = serverTxId,
                errorNumber = AlpacaErrors.INVALID_VALUE,
                errorMessage = "Unknown method for this device: $method"
            ))
        }
    }

    /**
     * Responds with ASCOM Alpaca ImageBytes binary format.
     *
     * ASCOM convention: array is indexed [x, y] where x=column, y=row.
     * Dimension1 = NumX (width), Dimension2 = NumY (height).
     * Binary pixel order is column-major: all rows for column 0, then column 1, etc.
     * Camera2 delivers row-major data, so we transpose during the write loop.
     *
     * See Doc/ascom-imagebytes-format.md for full format reference.
     */
    private suspend fun respondImageBytes(
        call: io.ktor.server.application.ApplicationCall,
        imageData: DeviceMethodResult.ImageData,
        clientTxId: Int,
        serverTxId: Int
    ) {
        val headerSize = 44
        val width = imageData.width
        val height = imageData.height
        val pixelCount = width * height
        val buffer = java.nio.ByteBuffer.allocate(headerSize + pixelCount * 4)
            .order(java.nio.ByteOrder.LITTLE_ENDIAN)

        // Metadata header (11 x Int32 LE)
        buffer.putInt(1)                    // MetadataVersion
        buffer.putInt(0)                    // ErrorNumber
        buffer.putInt(clientTxId)           // ClientTransactionID
        buffer.putInt(serverTxId)           // ServerTransactionID
        buffer.putInt(headerSize)           // DataStart
        buffer.putInt(2)                    // ImageElementType: 2 = Int32
        buffer.putInt(2)                    // TransmissionElementType: 2 = Int32
        buffer.putInt(2)                    // Rank: 2 = 2D
        buffer.putInt(width)                // Dimension1 = NumX (columns)
        buffer.putInt(height)               // Dimension2 = NumY (rows)
        buffer.putInt(0)                    // Dimension3 = 0

        // Convert 16-bit LE raw pixels to 32-bit LE ints, column-major order.
        // Camera2 source is row-major, so we index as (y * width + x) to transpose.
        val raw = imageData.rawBytes
        var minVal = Int.MAX_VALUE
        var maxVal = Int.MIN_VALUE
        var sumVal = 0L
        for (x in 0 until width) {
            for (y in 0 until height) {
                val srcIdx = (y * width + x) * 2
                val lo = raw[srcIdx].toInt() and 0xFF
                val hi = raw[srcIdx + 1].toInt() and 0xFF
                val pixel = lo or (hi shl 8)
                if (pixel < minVal) minVal = pixel
                if (pixel > maxVal) maxVal = pixel
                sumVal += pixel
                buffer.putInt(pixel)
            }
        }
        val avgVal = if (pixelCount > 0) sumVal / pixelCount else 0
        Log.i(TAG, "ImageBytes pixel stats: min=$minVal max=$maxVal avg=$avgVal count=$pixelCount rawBytes=${raw.size}")

        call.respondBytes(
            bytes = buffer.array(),
            contentType = io.ktor.http.ContentType("application", "imagebytes")
        )
    }

    /**
     * Responds with ASCOM Alpaca ImageArray JSON format.
     *
     * Column-major: outer array has NumX (width) elements, each inner array
     * has NumY (height) elements. See Doc/ascom-imagebytes-format.md.
     */
    private suspend fun respondImageJson(
        call: io.ktor.server.application.ApplicationCall,
        imageData: DeviceMethodResult.ImageData,
        clientTxId: Int,
        serverTxId: Int
    ) {
        val width = imageData.width
        val height = imageData.height
        val raw = imageData.rawBytes

        val columns = ArrayList<List<Int>>(width)
        for (x in 0 until width) {
            val col = ArrayList<Int>(height)
            for (y in 0 until height) {
                val srcIdx = (y * width + x) * 2
                val lo = raw[srcIdx].toInt() and 0xFF
                val hi = raw[srcIdx + 1].toInt() and 0xFF
                col.add(lo or (hi shl 8))
            }
            columns.add(col)
        }

        call.respond(ImageArrayResponse(
            value = columns,
            clientTransactionID = clientTxId,
            serverTransactionID = serverTxId
        ))
    }

    /**
     * Starts the Alpaca HTTP server and UDP discovery listener.
     *
     * The HTTP server runs on [host]:[port] with ContentNegotiation for JSON
     * serialization and a global security interceptor. The UDP discovery
     * listener runs on port 32227 in a separate coroutine.
     */
    suspend fun start() {
        initDevices()
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
                    val params = call.buildParamMap()
                    val clientTxId = parseClientTxId(params)
                    call.respond(IntArrayResponse(
                        value = listOf(1),
                        clientTransactionID = clientTxId,
                        serverTransactionID = nextServerTransactionId()
                    ))
                }

                get("/management/v1/description") {
                    val params = call.buildParamMap()
                    val clientTxId = parseClientTxId(params)
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
                    val params = call.buildParamMap()
                    val clientTxId = parseClientTxId(params)
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
                    val method = call.parameters["method"] ?: ""
                    val params = call.buildParamMap()
                    val clientTxId = parseClientTxId(params)
                    val serverTxId = nextServerTransactionId()

                    if (method == "imageready" || method == "imagearray" || method == "imagearrayvariant" || method == "camerastate") {
                        Log.d(TAG, "GET $deviceType/$deviceNumber/$method")
                    }

                    // Alpaca spec requires lowercase device type in URLs
                    if (deviceType != deviceType.lowercase()) {
                        call.respond(HttpStatusCode.BadRequest, MethodResponse(
                            clientTransactionID = clientTxId,
                            serverTransactionID = serverTxId,
                            errorNumber = AlpacaErrors.INVALID_VALUE,
                            errorMessage = "Invalid device type (must be lowercase): $deviceType"
                        ))
                        return@get
                    }

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

                    // Alpaca spec requires lowercase method names in URLs
                    if (method != method.lowercase()) {
                        call.respond(HttpStatusCode.BadRequest, MethodResponse(
                            clientTransactionID = clientTxId,
                            serverTransactionID = serverTxId,
                            errorNumber = AlpacaErrors.INVALID_VALUE,
                            errorMessage = "Invalid method name (must be lowercase): $method"
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
                        else -> {
                            if (!device.isConnected) {
                                call.respond(MethodResponse(
                                    clientTransactionID = clientTxId,
                                    serverTransactionID = serverTxId,
                                    errorNumber = AlpacaErrors.NOT_CONNECTED,
                                    errorMessage = "Device is not connected"
                                ))
                                return@get
                            }
                            respondDeviceResult(
                                call, device.handleGet(method), method, clientTxId, serverTxId
                            )
                        }
                    }
                }

                put("/api/v1/{device_type}/{device_number}/{method}") {
                    val deviceType = call.parameters["device_type"] ?: ""
                    val deviceNumber = call.parameters["device_number"]?.toIntOrNull() ?: -1
                    val method = call.parameters["method"] ?: ""
                    val params = call.buildParamMap()
                    val clientTxId = parseClientTxId(params)
                    val serverTxId = nextServerTransactionId()

                    // Alpaca spec requires lowercase device type in URLs
                    if (deviceType != deviceType.lowercase()) {
                        call.respond(HttpStatusCode.BadRequest, MethodResponse(
                            clientTransactionID = clientTxId,
                            serverTransactionID = serverTxId,
                            errorNumber = AlpacaErrors.INVALID_VALUE,
                            errorMessage = "Invalid device type (must be lowercase): $deviceType"
                        ))
                        return@put
                    }

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

                    // Alpaca spec requires lowercase method names in URLs
                    if (method != method.lowercase()) {
                        call.respond(HttpStatusCode.BadRequest, MethodResponse(
                            clientTransactionID = clientTxId,
                            serverTransactionID = serverTxId,
                            errorNumber = AlpacaErrors.INVALID_VALUE,
                            errorMessage = "Invalid method name (must be lowercase): $method"
                        ))
                        return@put
                    }

                    when (method) {
                        "connected" -> {
                            val raw = params["connected"]?.lowercase()
                            if (raw != "true" && raw != "false") {
                                call.respond(HttpStatusCode.BadRequest, MethodResponse(
                                    clientTransactionID = clientTxId,
                                    serverTransactionID = serverTxId,
                                    errorNumber = AlpacaErrors.INVALID_VALUE,
                                    errorMessage = "Connected parameter must be 'true' or 'false'"
                                ))
                            } else {
                                device.isConnected = raw == "true"
                                call.respond(MethodResponse(
                                    clientTransactionID = clientTxId,
                                    serverTransactionID = serverTxId
                                ))
                            }
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
                        else -> {
                            if (!device.isConnected) {
                                call.respond(MethodResponse(
                                    clientTransactionID = clientTxId,
                                    serverTransactionID = serverTxId,
                                    errorNumber = AlpacaErrors.NOT_CONNECTED,
                                    errorMessage = "Device is not connected"
                                ))
                                return@put
                            }
                            respondDeviceResult(
                                call, device.handlePut(method, params), method, clientTxId, serverTxId
                            )
                        }
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
                    if (approvalManager != null) {
                        // Silently drop duplicate discovery while a dialog is already showing
                        if (approvalManager.pendingApproval.value != null) continue
                        val approved = approvalManager.awaitApproval(senderIp)
                        if (!approved) {
                            onEvent?.invoke("Discovery from $senderIp denied")
                            continue
                        }
                    }
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
