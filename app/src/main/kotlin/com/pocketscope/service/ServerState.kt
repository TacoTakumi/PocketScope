package com.pocketscope.service

data class ServerState(
    val isRunning: Boolean = false,
    val ipAddress: String = "",
    val port: Int = 7624,
    val connectedClients: Int = 0,
    // Phase 4: Session metrics
    val uptimeSeconds: Long = 0L,
    val captureCount: Int = 0,
    val errorCount: Int = 0,
    // Phase 4: Event log -- last 10 entries
    val eventLog: List<String> = emptyList(),
    // Phase 4: Battery warning
    val lowBattery: Boolean = false,
    val batteryPercent: Int = 100
)
