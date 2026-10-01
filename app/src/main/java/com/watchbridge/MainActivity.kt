package com.watchbridge

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.util.Log
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
import com.watchbridge.ble.ConnectionStateMachine
import com.watchbridge.service.WatchBridgeService
import com.watchbridge.settings.SettingsManager
import com.watchbridge.ui.screens.HomeScreen
import com.watchbridge.ui.screens.NowPlayingScreen
import com.watchbridge.ui.screens.OnboardingScreen
import com.watchbridge.ui.screens.PairingScreen
import com.watchbridge.ui.screens.SettingsScreen
import com.watchbridge.ui.theme.WatchBridgeTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private lateinit var bleScanner: BleScanner
    private lateinit var localSettings: SettingsManager
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
        localSettings = SettingsManager(this)

        checkPermissions()

        val startRoute = if (localSettings.isOnboardingComplete) "home" else "onboarding"

        setContent {
            WatchBridgeTheme {
                val navController = rememberSwipeDismissableNavController()

                SwipeDismissableNavHost(
                    navController = navController,
                    startDestination = startRoute
                ) {
                    composable("onboarding") {
                        OnboardingScreen(
                            onComplete = {
                                localSettings.setOnboardingComplete()
                                navController.navigate("home") {
                                    popUpTo("onboarding") { inclusive = true }
                                }
                            }
                        )
                    }
                    composable("home") {
                        HomeScreen(
                            onNavigateToPairing = {
                                navController.navigate("pairing")
                            },
                            onNavigateToSettings = {
                                navController.navigate("settings")
                            },
                            onNavigateToMedia = {
                                navController.navigate("media")
                            },
                            onReconnect = {
                                val sm = WatchBridgeService.stateMachine
                                if (sm == null || !sm.autoConnectToBonded()) {
                                    navController.navigate("pairing")
                                }
                            },
                            onTurnOnBluetooth = { turnOnBluetooth() },
                            onDisconnect = {
                                WatchBridgeService.stateMachine?.disconnect()
                            }
                        )
                    }
                    composable("pairing") {
                        PairingScreen(
                            onStartAdvertising = {
                                ensureServiceRunning()
                                val intent = Intent(
                                    this@MainActivity,
                                    WatchBridgeService::class.java
                                ).apply {
                                    action = WatchBridgeService.ACTION_START_ADVERTISING
                                }
                                startForegroundService(intent)
                            },
                            onDeviceSelected = { device ->
                                connectToDevice(device)
                            },
                            onConnected = {
                                navController.popBackStack()
                            }
                        )
                    }
                    composable("settings") {
                        val settings = WatchBridgeService.settingsManager ?: localSettings
                        SettingsScreen(settings = settings)
                    }
                    composable("media") {
                        NowPlayingScreen(media = WatchBridgeService.mediaManager)
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (WatchBridgeService.isRunning) {
            val sm = WatchBridgeService.stateMachine
            if (sm?.state?.value == ConnectionStateMachine.State.IDLE) {
                sm.autoConnectToBonded()
            }
        }
    }

    private fun checkPermissions() {
        val required = mutableListOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_ADVERTISE,
            Manifest.permission.ACCESS_FINE_LOCATION
        )
        // POST_NOTIFICATIONS required on Android 13+
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            required.add(Manifest.permission.POST_NOTIFICATIONS)
        }

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
        if (!WatchBridgeService.isRunning && localSettings.isOnboardingComplete) {
            // The state machine doesn't exist until the service has started, so let the
            // service connect to the bonded iPhone itself
            Log.i(TAG, "Starting WatchBridgeService and connecting to bonded iPhone")
            val intent = Intent(this, WatchBridgeService::class.java).apply {
                action = WatchBridgeService.ACTION_CONNECT_BONDED
            }
            startForegroundService(intent)
        }
    }

    private fun connectToDevice(device: BluetoothDevice) {
        Log.i(TAG, "connectToDevice: ${device.address}")
        ensureServiceRunning()

        // Service may need a moment to initialize — poll until ready
        CoroutineScope(Dispatchers.Main).launch {
            var attempts = 0
            while (WatchBridgeService.stateMachine == null && attempts < 20) {
                delay(100)
                attempts++
            }
            val sm = WatchBridgeService.stateMachine
            if (sm != null) {
                Log.i(TAG, "Service ready after ${attempts * 100}ms, connecting...")
                sm.connectTo(device)
            } else {
                Log.e(TAG, "Service state machine still null after 2s!")
            }
        }
    }

    /** One-tap "Turn on Bluetooth?" dialog, else the Bluetooth (or main) settings screen. */
    private fun turnOnBluetooth() {
        val actions = listOf(
            BluetoothAdapter.ACTION_REQUEST_ENABLE,
            Settings.ACTION_BLUETOOTH_SETTINGS,
            Settings.ACTION_SETTINGS
        )
        for (action in actions) {
            try {
                startActivity(Intent(action))
                return
            } catch (e: ActivityNotFoundException) {
                Log.w(TAG, "No screen for $action")
            } catch (e: SecurityException) {
                Log.w(TAG, "Not allowed: $action")
            }
        }
    }

    private fun ensureServiceRunning() {
        if (!WatchBridgeService.isRunning) {
            Log.i(TAG, "Starting WatchBridgeService")
            val intent = Intent(this, WatchBridgeService::class.java).apply {
                action = WatchBridgeService.ACTION_START
            }
            startForegroundService(intent)
        }
    }

    companion object {
        private const val TAG = "MainActivity"
    }
}
