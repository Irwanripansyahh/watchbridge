package com.watchbridge.tile

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.watchbridge.MainActivity
import com.watchbridge.ble.BondManager
import com.watchbridge.service.WatchBridgeService

/**
 * Invisible activity behind the tile's "Reconnect" chip. Tiles can only launch activities,
 * and an activity (unlike a tile) is allowed to start the foreground service.
 */
class ReconnectActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val paired = BondManager(this).getSavedBondedAddress() != null
        val canConnect = ContextCompat.checkSelfPermission(
            this, Manifest.permission.BLUETOOTH_CONNECT
        ) == PackageManager.PERMISSION_GRANTED

        if (paired && canConnect) {
            startForegroundService(
                Intent(this, WatchBridgeService::class.java)
                    .setAction(WatchBridgeService.ACTION_CONNECT_BONDED)
            )
            Toast.makeText(this, "Reconnecting to phone…", Toast.LENGTH_SHORT).show()
        } else {
            // Not paired yet, or permissions missing: the app walks the user through it
            startActivity(Intent(this, MainActivity::class.java))
        }
        finish()
    }
}
