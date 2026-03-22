package com.pocketscope.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraManager
import android.net.ConnectivityManager
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.pocketscope.indi.server.IndiServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.Inet4Address

class IndiServerService : Service() {

    companion object {
        val state = MutableStateFlow(ServerState())
        private const val CHANNEL_ID = "pocketscope_indi"
        private const val NOTIFICATION_ID = 1
    }

    private var server: IndiServer? = null
    private var serverJob: Job? = null
    private var clientCountJob: Job? = null
    private var cameraThread: HandlerThread? = null
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

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
        val cameraManager = getSystemService(CAMERA_SERVICE) as CameraManager
        val handlerThread = HandlerThread("CameraThread").also { it.start() }
        cameraThread = handlerThread
        val handler = Handler(handlerThread.looper)

        val indiServer = IndiServer(cameraManager, handler)
        server = indiServer

        val ip = getWifiIpAddress() ?: "unknown"
        state.value = ServerState(
            isRunning = true,
            ipAddress = ip,
            port = IndiServer.DEFAULT_PORT
        )

        serverJob = serviceScope.launch {
            indiServer.start()
        }

        // Periodically update connected client count
        clientCountJob = serviceScope.launch {
            while (isActive) {
                delay(2000)
                val current = state.value
                if (current.isRunning) {
                    state.value = current.copy(
                        connectedClients = indiServer.connectedClientCount
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        clientCountJob?.cancel()
        serverJob?.cancel()
        server?.stop()
        server = null
        cameraThread?.quitSafely()
        cameraThread = null
        state.value = ServerState()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "INDI Server",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Shows when the INDI server is running"
        }
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(channel)
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
