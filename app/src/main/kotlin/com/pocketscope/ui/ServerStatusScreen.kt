package com.pocketscope.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pocketscope.service.ServerState

@Composable
fun ServerStatusScreen(
    state: ServerState,
    onStartServer: () -> Unit,
    onStopServer: () -> Unit,
    cameraPermissionGranted: Boolean,
    onRequestCameraPermission: () -> Unit,
    isDimmed: Boolean = false,
    onToggleBrightness: () -> Unit = {}
) {
    Scaffold { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Top
        ) {
            Text(
                text = "PocketScope",
                style = MaterialTheme.typography.headlineLarge,
                color = MaterialTheme.colorScheme.primary
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "INDI Server",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(32.dp))

            HorizontalDivider(color = MaterialTheme.colorScheme.outline)

            Spacer(modifier = Modifier.height(24.dp))

            // Status indicator
            Text(
                text = if (state.isRunning) "RUNNING" else "STOPPED",
                style = MaterialTheme.typography.headlineMedium,
                color = if (state.isRunning)
                    MaterialTheme.colorScheme.primary
                else
                    MaterialTheme.colorScheme.onSurfaceVariant,
                fontFamily = FontFamily.Monospace
            )

            Spacer(modifier = Modifier.height(24.dp))

            // IP and Port
            if (state.isRunning) {
                Text(
                    text = state.ipAddress,
                    style = MaterialTheme.typography.displaySmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontFamily = FontFamily.Monospace
                )
                Text(
                    text = "Port ${state.port}",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontFamily = FontFamily.Monospace
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Client count or "Waiting for clients..." (D-07)
                Text(
                    text = if (state.connectedClients == 0) "Waiting for clients..."
                           else "${state.connectedClients} client(s) connected",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )

                // LOW BATTERY warning (D-04)
                if (state.lowBattery) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "LOW BATTERY: ${state.batteryPercent}%",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        fontFamily = FontFamily.Monospace
                    )
                }

                // Night-vision brightness toggle
                Spacer(modifier = Modifier.height(12.dp))
                Button(
                    onClick = onToggleBrightness,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                        contentColor = MaterialTheme.colorScheme.primary
                    )
                ) {
                    Text(
                        text = if (isDimmed) "Restore Brightness" else "Dim for Night Vision",
                        fontFamily = FontFamily.Monospace
                    )
                }

                // Session Metrics (D-10, D-12)
                Spacer(modifier = Modifier.height(16.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                Spacer(modifier = Modifier.height(12.dp))

                // Uptime
                Text(
                    text = "Uptime: ${formatUptime(state.uptimeSeconds)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontFamily = FontFamily.Monospace
                )
                Spacer(modifier = Modifier.height(4.dp))
                // Captures and Errors on one line
                Text(
                    text = "Captures: ${state.captureCount}  Errors: ${state.errorCount}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontFamily = FontFamily.Monospace
                )

                // Event Log (D-06)
                if (state.eventLog.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(12.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = "Events",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(4.dp))

                    val listState = rememberLazyListState()
                    LaunchedEffect(state.eventLog.size) {
                        if (state.eventLog.isNotEmpty()) {
                            listState.animateScrollToItem(state.eventLog.size - 1)
                        }
                    }
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(160.dp)
                    ) {
                        items(state.eventLog) { event ->
                            Text(
                                text = event,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier.padding(vertical = 2.dp)
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            // Action buttons
            if (!cameraPermissionGranted) {
                Button(
                    onClick = onRequestCameraPermission,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Grant Camera Permission")
                }
                Spacer(modifier = Modifier.height(8.dp))
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Button(
                    onClick = onStartServer,
                    enabled = !state.isRunning && cameraPermissionGranted,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Start")
                }
                Button(
                    onClick = onStopServer,
                    enabled = state.isRunning,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Stop")
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

private fun formatUptime(seconds: Long): String {
    val hours = seconds / 3600
    val minutes = (seconds % 3600) / 60
    return if (hours > 0) "${hours}h ${minutes}m" else "${minutes}m"
}
