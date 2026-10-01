package com.watchbridge.service

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.watchbridge.ble.BondManager
import com.watchbridge.settings.SettingsManager

/**
 * Restarts the bridge after the watch reboots or the app is updated, so notifications
 * keep flowing without the user having to open WatchBridge first.
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) return

        if (!SettingsManager(context).isOnboardingComplete) return
        if (BondManager(context).getSavedBondedAddress() == null) {
            Log.i(TAG, "No paired iPhone yet, not starting the bridge")
            return
        }
        // The connectedDevice foreground service type requires this permission at start time
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "BLUETOOTH_CONNECT not granted, not starting the bridge")
            return
        }

        Log.i(TAG, "${intent.action}: starting bridge")
        context.startForegroundService(
            Intent(context, WatchBridgeService::class.java)
                .setAction(WatchBridgeService.ACTION_CONNECT_BONDED)
        )
    }
}
