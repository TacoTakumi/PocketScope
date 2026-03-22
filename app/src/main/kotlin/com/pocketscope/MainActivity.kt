package com.pocketscope

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
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
                    }
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
