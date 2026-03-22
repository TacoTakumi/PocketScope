package com.pocketscope

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.Window
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pocketscope.service.IndiServerService
import com.pocketscope.ui.ServerStatusScreen
import com.pocketscope.ui.theme.PocketScopeTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Keep screen on for astronomy sessions (UI-03)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setContent {
            PocketScopeTheme {
                val serverState by IndiServerService.state
                    .collectAsStateWithLifecycle()

                // Night-vision brightness toggle (D-13)
                var isDimmed by remember { mutableStateOf(false) }
                // Reset dim state when server stops
                LaunchedEffect(serverState.isRunning) {
                    if (!serverState.isRunning) isDimmed = false
                }
                BrightnessEffect(window = window, dimmed = isDimmed)

                var cameraPermissionGranted by remember {
                    mutableStateOf(
                        ContextCompat.checkSelfPermission(
                            this@MainActivity,
                            Manifest.permission.CAMERA
                        ) == PackageManager.PERMISSION_GRANTED
                    )
                }

                val permissionLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.RequestPermission()
                ) { granted ->
                    cameraPermissionGranted = granted
                }

                ServerStatusScreen(
                    state = serverState,
                    onStartServer = { startIndiService() },
                    onStopServer = { stopIndiService() },
                    cameraPermissionGranted = cameraPermissionGranted,
                    onRequestCameraPermission = {
                        permissionLauncher.launch(Manifest.permission.CAMERA)
                    },
                    isDimmed = isDimmed,
                    onToggleBrightness = { isDimmed = !isDimmed }
                )
            }
        }
    }

    private fun startIndiService() {
        val intent = Intent(this, IndiServerService::class.java)
        startForegroundService(intent)
    }

    private fun stopIndiService() {
        val intent = Intent(this, IndiServerService::class.java)
        stopService(intent)
    }
}

@Composable
private fun BrightnessEffect(window: Window, dimmed: Boolean) {
    // Remember the brightness before we override it, so we can restore it
    val originalBrightness = remember {
        mutableStateOf(window.attributes.screenBrightness)
    }
    LaunchedEffect(dimmed) {
        val lp = window.attributes
        if (dimmed) {
            originalBrightness.value = lp.screenBrightness
            // 0.01f = minimum brightness. NOT 0.0f which some devices treat as "use system default"
            lp.screenBrightness = 0.01f
        } else {
            lp.screenBrightness = originalBrightness.value
        }
        window.attributes = lp
    }
}
