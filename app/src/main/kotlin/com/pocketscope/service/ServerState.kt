package com.pocketscope.service

data class ServerState(
    val isRunning: Boolean = false,
    val ipAddress: String = "",
    val port: Int = 7624,
    val connectedClients: Int = 0
)
