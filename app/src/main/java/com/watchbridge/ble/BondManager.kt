package com.watchbridge.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Manages iOS bonding state. iOS bonding is triggered when the watch
 * accesses an encrypted ANCS characteristic (Notification Source CCCD).
 *
 * This class monitors bond state changes and provides the current
 * bonded device for auto-reconnection.
 */
class BondManager(private val context: Context) {

    companion object {
        private const val TAG = "BondManager"
        private const val PREFS_NAME = "watchbridge_bond"
        private const val KEY_BONDED_ADDRESS = "bonded_address"
    }

    enum class BondState {
        NONE,
        BONDING,
        BONDED
    }

    private val _bondState = MutableStateFlow(BondState.NONE)
    val bondState: StateFlow<BondState> = _bondState.asStateFlow()

    private val bluetoothManager =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val bondReceiver = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        override fun onReceive(ctx: Context, intent: Intent) {
            if (intent.action != BluetoothDevice.ACTION_BOND_STATE_CHANGED) return

            val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                ?: return
            val state = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE)
            val prevState = intent.getIntExtra(BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE, BluetoothDevice.BOND_NONE)

            Log.i(TAG, "Bond state changed for ${device.address}: $prevState -> $state")

            when (state) {
                BluetoothDevice.BOND_BONDING -> {
                    _bondState.value = BondState.BONDING
                }
                BluetoothDevice.BOND_BONDED -> {
                    _bondState.value = BondState.BONDED
                    saveBondedDevice(device.address)
                    Log.i(TAG, "Successfully bonded with ${device.address}")
                }
                BluetoothDevice.BOND_NONE -> {
                    _bondState.value = BondState.NONE
                    if (prevState == BluetoothDevice.BOND_BONDING) {
                        Log.w(TAG, "Bonding failed for ${device.address}")
                    } else if (prevState == BluetoothDevice.BOND_BONDED) {
                        Log.w(TAG, "Bond lost for ${device.address}")
                        clearBondedDevice()
                    }
                }
            }
        }
    }

    fun register() {
        val filter = IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
        context.registerReceiver(bondReceiver, filter)

        // Check if we already have a bonded device
        val savedAddress = getSavedBondedAddress()
        if (savedAddress != null && isBonded(savedAddress)) {
            _bondState.value = BondState.BONDED
        }
    }

    fun unregister() {
        try {
            context.unregisterReceiver(bondReceiver)
        } catch (_: IllegalArgumentException) {
            // Not registered
        }
    }

    @SuppressLint("MissingPermission")
    fun isBonded(address: String): Boolean {
        return bluetoothManager.adapter?.bondedDevices?.any { it.address == address } == true
    }

    @SuppressLint("MissingPermission")
    fun getBondedDevice(): BluetoothDevice? {
        val address = getSavedBondedAddress() ?: return null
        return bluetoothManager.adapter?.bondedDevices?.find { it.address == address }
    }

    fun getSavedBondedAddress(): String? {
        return prefs.getString(KEY_BONDED_ADDRESS, null)
    }

    private fun saveBondedDevice(address: String) {
        prefs.edit().putString(KEY_BONDED_ADDRESS, address).apply()
    }

    private fun clearBondedDevice() {
        prefs.edit().remove(KEY_BONDED_ADDRESS).apply()
    }
}
