package com.watchbridge

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.wear.compose.navigation.SwipeDismissableNavHost
import androidx.wear.compose.navigation.composable
import androidx.wear.compose.navigation.rememberSwipeDismissableNavController
import com.watchbridge.ble.BleScanner
import com.watchbridge.service.WatchBridgeService
import com.watchbridge.ui.screens.HomeScreen
import com.watchbridge.ui.screens.PairingScreen
import com.watchbridge.ui.theme.WatchBridgeTheme

class MainActivity : ComponentActivity() {

    private lateinit var bleScanner: BleScanner
    private var permissionsGranted by mutableStateOf(false)

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        permissionsGranted = results.values.all { it }
        if (permissionsGranted) {
            startBridgeService()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        bleScanner = BleScanner(this)
        checkPermissions()

        setContent {
            WatchBridgeTheme {
                val navController = rememberSwipeDismissableNavController()

                SwipeDismissableNavHost(
                    navController = navController,
                    startDestination = "home"
                ) {
                    composable("home") {
                        HomeScreen(
                            onNavigateToPairing = {
                                navController.navigate("pairing")
                            },
                            onDisconnect = {
                                WatchBridgeService.stateMachine?.disconnect()
                            }
                        )
                    }
                    composable("pairing") {
                        PairingScreen(
                            bleScanner = bleScanner,
                            onDeviceSelected = { device ->
                                ensureServiceRunning()
                                WatchBridgeService.stateMachine?.connectTo(device)
                            },
                            onConnected = {
                                navController.popBackStack()
                            }
                        )
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Auto-reconnect to bonded device if service is running
        if (WatchBridgeService.isRunning) {
            val sm = WatchBridgeService.stateMachine
            if (sm?.state?.value == com.watchbridge.ble.ConnectionStateMachine.State.IDLE) {
                sm.autoConnectToBonded()
            }
        }
    }

    private fun checkPermissions() {
        val required = arrayOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_ADVERTISE
        )

        val missing = required.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missing.isEmpty()) {
            permissionsGranted = true
            startBridgeService()
        } else {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    private fun startBridgeService() {
        if (!WatchBridgeService.isRunning) {
            ensureServiceRunning()
            // Try auto-connect to bonded device
            WatchBridgeService.stateMachine?.autoConnectToBonded()
        }
    }

    private fun ensureServiceRunning() {
        if (!WatchBridgeService.isRunning) {
            val intent = Intent(this, WatchBridgeService::class.java).apply {
                action = WatchBridgeService.ACTION_START
            }
            startForegroundService(intent)
        }
    }
}
