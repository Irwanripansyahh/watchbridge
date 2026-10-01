package com.watchbridge.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import com.watchbridge.ble.BondManager
import com.watchbridge.service.WatchBridgeService
import com.watchbridge.settings.SettingsManager
import com.watchbridge.ui.screens.NowPlayingScreen
import com.watchbridge.ui.theme.WatchBridgeTheme
import kotlinx.coroutines.delay

/**
 * The "Music Control" launcher icon: a second entry in the app list that opens straight
 * into the media controls. Same app and process as WatchBridge, just its own task.
 */
class MediaActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startBridgeIfNeeded()

        setContent {
            WatchBridgeTheme {
                // The bridge may still be starting; pick up its media state once it exists
                var media by remember { mutableStateOf(WatchBridgeService.mediaManager) }
                LaunchedEffect(Unit) {
                    while (media == null) {
                        delay(500)
                        media = WatchBridgeService.mediaManager
                    }
                }
                NowPlayingScreen(media = media)
            }
        }
    }

    /** Opened straight from the launcher, maybe before WatchBridge itself ever ran. */
    private fun startBridgeIfNeeded() {
        if (WatchBridgeService.isRunning) return
        if (!SettingsManager(this).isOnboardingComplete) return
        if (BondManager(this).getSavedBondedAddress() == null) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
            != PackageManager.PERMISSION_GRANTED
        ) return

        startForegroundService(
            Intent(this, WatchBridgeService::class.java)
                .setAction(WatchBridgeService.ACTION_CONNECT_BONDED)
        )
    }
}
