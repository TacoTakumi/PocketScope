package com.pocketscope.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.camera2.CameraManager
import android.net.ConnectivityManager
import android.os.BatteryManager
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import com.pocketscope.device.DeviceRegistry
import com.pocketscope.alpaca.server.AlpacaServer
import com.pocketscope.indi.server.IndiServer
import com.pocketscope.network.ApprovalManager
import com.pocketscope.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.Inet4Address

class IndiServerService : Service() {

    companion object {
        private const val TAG = "IndiServerService"
        val state = MutableStateFlow(ServerState())
        private const val CHANNEL_ID = "pocketscope_indi"
        private const val NOTIFICATION_ID = 1
        private const val APPROVAL_CHANNEL_ID = "pocketscope_approval"
        private const val APPROVAL_NOTIFICATION_ID = 2
    }

    private var deviceRegistry: DeviceRegistry? = null
    private var server: IndiServer? = null
    private var serverJob: Job? = null
    private var alpacaServer: AlpacaServer? = null
    private var alpacaJob: Job? = null
    private var clientCountJob: Job? = null
    private var cameraThread: HandlerThread? = null
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var settingsRepo: SettingsRepository? = null
    private var approvalManager: ApprovalManager? = null

    private var wakeLock: PowerManager.WakeLock? = null
    private var serverStartElapsedRealtime: Long = 0L

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("PocketScope")
            .setContentText("INDI server running")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setOngoing(true)
            .build()

        startForeground(NOTIFICATION_ID, notification)
        startServer()
        return START_STICKY
    }

    private fun startServer() {
        // Acquire wake lock to keep CPU and network alive during all-night sessions
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "PocketScope::IndiServer"
        ).apply { acquire() }

        val cameraManager = getSystemService(CAMERA_SERVICE) as CameraManager
        val handlerThread = HandlerThread("CameraThread").also { it.start() }
        cameraThread = handlerThread
        val handler = Handler(handlerThread.looper)

        val registry = DeviceRegistry(cameraManager, handler)
        deviceRegistry = registry

        // Initialize network security components
        val settings = SettingsRepository(applicationContext)
        settingsRepo = settings
        val approval = ApprovalManager(settings)
        approvalManager = approval
        ApprovalManager.instance = approval  // Expose to UI layer

        val indiServer = IndiServer(
            registry = registry,
            scope = serviceScope,
            approvalManager = approval,
            onClientEvent = { event -> addEvent(event) },
            onCaptureComplete = { success ->
                state.update { current ->
                    if (success) current.copy(captureCount = current.captureCount + 1)
                    else current.copy(errorCount = current.errorCount + 1)
                }
            }
        )
        server = indiServer

        val alpaca = AlpacaServer(
            registry = registry,
            scope = serviceScope,
            settings = settings,
            onEvent = { event -> addEvent(event) },
            approvalManager = approval
        )
        alpacaServer = alpaca

        val ip = getWifiIpAddress() ?: "unknown"
        state.value = ServerState(
            isRunning = true,
            ipAddress = ip,
            port = IndiServer.DEFAULT_PORT
        )

        serverStartElapsedRealtime = SystemClock.elapsedRealtime()

        // Observe INDI toggle and dynamically start/stop the protocol server
        serviceScope.launch {
            settings.isIndiEnabled.distinctUntilChanged().collectLatest { enabled ->
                state.update { it.copy(isIndiEnabled = enabled) }
                if (enabled) {
                    if (serverJob == null || serverJob?.isActive != true) {
                        serverJob = serviceScope.launch {
                            try {
                                indiServer.start()
                            } catch (e: java.net.BindException) {
                                Log.e(TAG, "Failed to bind INDI server: ${e.message}")
                                addEvent("INDI start failed: port in use")
                            }
                        }
                        addEvent("INDI protocol started")
                    }
                } else {
                    // Per locked decision: abort any in-progress camera exposure
                    // before tearing down the INDI server. CaptureDevice.capture()
                    // is a suspend function guarded by a Mutex -- cancelling
                    // serverJob propagates CancellationException through the
                    // coroutine tree, which interrupts any active capture() call.
                    serverJob?.cancel()
                    serverJob?.join()
                    serverJob = null
                    indiServer.stop()
                    addEvent("INDI protocol stopped (active exposures aborted)")
                }
            }
        }

        // Observe pending approval to update ServerState and show notification
        serviceScope.launch {
            approval.pendingApproval.collect { request ->
                state.update { it.copy(pendingApprovalIp = request?.ip) }
                if (request != null) {
                    showApprovalNotification(request.ip)
                } else {
                    dismissApprovalNotification()
                }
            }
        }

        // Observe Alpaca toggle and dynamically start/stop the Alpaca protocol server
        serviceScope.launch {
            settings.isAlpacaEnabled.distinctUntilChanged().collectLatest { enabled ->
                state.update { it.copy(isAlpacaEnabled = enabled) }
                if (enabled) {
                    if (alpacaJob == null || alpacaJob?.isActive != true) {
                        val handler = kotlinx.coroutines.CoroutineExceptionHandler { _, e ->
                            Log.e(TAG, "Alpaca server error: ${e.message}")
                            addEvent("Alpaca error: ${e.message}")
                        }
                        alpacaJob = serviceScope.launch(handler) {
                            alpaca.start()
                        }
                        addEvent("Alpaca protocol started")
                    }
                } else {
                    alpacaJob?.cancel()
                    alpacaJob?.join()
                    alpacaJob = null
                    alpaca.stop()
                    addEvent("Alpaca protocol stopped")
                }
            }
        }

        // Periodically update connected client count, uptime, battery, and memory
        clientCountJob = serviceScope.launch {
            var iterationCount = 0L
            while (isActive) {
                delay(2000)
                val current = state.value
                if (current.isRunning) {
                    val uptimeSec = (SystemClock.elapsedRealtime() - serverStartElapsedRealtime) / 1000L
                    val batteryPct = readBatteryPercent()
                    state.update {
                        it.copy(
                            connectedClients = indiServer.connectedClientCount,
                            uptimeSeconds = uptimeSec,
                            batteryPercent = batteryPct,
                            lowBattery = batteryPct <= 15
                        )
                    }

                    // Log memory every ~30 seconds (every 15th iteration of 2s loop)
                    iterationCount++
                    if (iterationCount % 15 == 0L) {
                        val rt = Runtime.getRuntime()
                        val usedMb = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024)
                        val maxMb = rt.maxMemory() / (1024 * 1024)
                        Log.d(TAG, "Memory: ${usedMb}MB / ${maxMb}MB")
                    }
                }
            }
        }

        addEvent("Server started")
    }

    override fun onDestroy() {
        addEvent("Server stopped")

        // Release wake lock before cleanup
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null

        clientCountJob?.cancel()
        alpacaJob?.cancel()
        alpacaServer?.stop()
        alpacaServer = null
        serverJob?.cancel()
        server?.stop()
        server = null

        // DeviceRegistry lifecycle owned by service, not protocol server
        deviceRegistry?.closeAll()
        deviceRegistry = null

        // Clean up network security components
        ApprovalManager.instance = null
        approvalManager = null
        settingsRepo = null

        cameraThread?.quitSafely()
        cameraThread = null
        state.value = ServerState()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun readBatteryPercent(): Int {
        val batteryIntent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = batteryIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = batteryIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        return if (level >= 0 && scale > 0) (level * 100) / scale else 100
    }

    private fun addEvent(event: String) {
        val timestamp = java.text.SimpleDateFormat("HH:mm", java.util.Locale.US).format(java.util.Date())
        state.update { current ->
            current.copy(eventLog = (current.eventLog + "$timestamp $event").takeLast(10))
        }
    }

    private fun createNotificationChannel() {
        val notificationManager = getSystemService(NotificationManager::class.java)

        val channel = NotificationChannel(
            CHANNEL_ID,
            "INDI Server",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Shows when the INDI server is running"
        }
        notificationManager.createNotificationChannel(channel)

        val approvalChannel = NotificationChannel(
            APPROVAL_CHANNEL_ID,
            "Connection Approval",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Alerts when a new device tries to connect"
        }
        notificationManager.createNotificationChannel(approvalChannel)
    }

    private fun showApprovalNotification(ip: String) {
        val notification = NotificationCompat.Builder(this, APPROVAL_CHANNEL_ID)
            .setContentTitle("New Connection")
            .setContentText("$ip wants to connect")
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(false)
            .build()
        getSystemService(NotificationManager::class.java)
            .notify(APPROVAL_NOTIFICATION_ID, notification)
    }

    private fun dismissApprovalNotification() {
        getSystemService(NotificationManager::class.java)
            .cancel(APPROVAL_NOTIFICATION_ID)
    }

    private fun getWifiIpAddress(): String? {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return null
        val linkProps = cm.getLinkProperties(network) ?: return null
        return linkProps.linkAddresses
            .map { it.address }
            .filterIsInstance<Inet4Address>()
            .firstOrNull()
            ?.hostAddress
    }
}
