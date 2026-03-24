package com.pocketscope.ui

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.pocketscope.service.ServerState

/**
 * Top-level navigation host for PocketScope.
 *
 * Routes between the main server status screen and the settings screen.
 * Also overlays the [ApprovalDialog] when a pending connection approval exists.
 *
 * @param navController Navigation controller for managing screen transitions.
 * @param serverState Current server state including protocol toggles and pending approvals.
 * @param whitelistedIps Current set of always-allowed IP addresses.
 * @param onStartServer Callback to start the INDI server service.
 * @param onStopServer Callback to stop the INDI server service.
 * @param cameraPermissionGranted Whether camera permission has been granted.
 * @param onRequestCameraPermission Callback to request camera permission.
 * @param isDimmed Whether the screen is in dimmed night-vision mode.
 * @param onToggleBrightness Callback to toggle brightness dimming.
 * @param onToggleIndi Callback to enable/disable INDI protocol.
 * @param onToggleAlpaca Callback to enable/disable Alpaca protocol.
 * @param onApprovalResponse Callback to respond to a pending connection approval.
 * @param onRemoveWhitelistedIp Callback to remove an IP from the whitelist.
 */
@Composable
fun PocketScopeNavHost(
    navController: NavHostController,
    serverState: ServerState,
    whitelistedIps: Set<String>,
    onStartServer: () -> Unit,
    onStopServer: () -> Unit,
    cameraPermissionGranted: Boolean,
    onRequestCameraPermission: () -> Unit,
    isDimmed: Boolean,
    onToggleBrightness: () -> Unit,
    onToggleIndi: (Boolean) -> Unit,
    onToggleAlpaca: (Boolean) -> Unit,
    onApprovalResponse: (ip: String, approved: Boolean, alwaysAllow: Boolean) -> Unit,
    onRemoveWhitelistedIp: (String) -> Unit
) {
    NavHost(navController = navController, startDestination = "main") {
        composable("main") {
            ServerStatusScreen(
                state = serverState,
                onStartServer = onStartServer,
                onStopServer = onStopServer,
                cameraPermissionGranted = cameraPermissionGranted,
                onRequestCameraPermission = onRequestCameraPermission,
                isDimmed = isDimmed,
                onToggleBrightness = onToggleBrightness,
                isIndiEnabled = serverState.isIndiEnabled,
                isAlpacaEnabled = serverState.isAlpacaEnabled,
                onToggleIndi = onToggleIndi,
                onToggleAlpaca = onToggleAlpaca,
                onNavigateToSettings = { navController.navigate("settings") }
            )
        }
        composable("settings") {
            SettingsScreen(
                whitelistedIps = whitelistedIps,
                onRemoveIp = onRemoveWhitelistedIp,
                onNavigateBack = { navController.popBackStack() }
            )
        }
    }

    // Overlay approval dialog when a connection is pending
    serverState.pendingApprovalIp?.let { ip ->
        ApprovalDialog(
            ip = ip,
            onAllowOnce = { onApprovalResponse(ip, true, false) },
            onAlwaysAllow = { onApprovalResponse(ip, true, true) },
            onDeny = { onApprovalResponse(ip, false, false) }
        )
    }
}
