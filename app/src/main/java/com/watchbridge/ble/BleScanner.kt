package com.watchbridge.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.util.Log
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * Scans for BLE devices. iPhones don't advertise ANCS in scan responses,
 * so we scan for ALL devices and show ones with names.
 *
 * iPhones are identified by Apple's company ID (0x004C) in manufacturer data.
 */
class BleScanner(context: Context) {

    companion object {
        private const val TAG = "BleScanner"
        /** Apple's Bluetooth SIG company identifier. */
        private const val APPLE_COMPANY_ID = 0x004C
    }

    private val appContext = context.applicationContext
    private val bluetoothManager =
        appContext.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter = bluetoothManager.adapter

    data class ScannedDevice(
        val name: String?,
        val address: String,
        val rssi: Int,
        val isAppleDevice: Boolean = false
    )

    @SuppressLint("MissingPermission")
    fun scan(): Flow<ScannedDevice> = callbackFlow {
        Log.i(TAG, "=== SCAN START ===")
        Log.i(TAG, "Bluetooth enabled: ${bluetoothAdapter?.isEnabled}")

        val leScanner = bluetoothAdapter?.bluetoothLeScanner
        if (leScanner == null) {
            Log.e(TAG, "BLE scanner is NULL — Bluetooth may be off")
            close()
            return@callbackFlow
        }

        val seen = mutableSetOf<String>()
        var totalFound = 0

        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                totalFound++
                val address = result.device.address
                // Try scan record name first, fall back to device.name
                val rawName = result.scanRecord?.deviceName ?: result.device.name

                // Check if this is an Apple device by manufacturer data
                val isApple = result.scanRecord?.getManufacturerSpecificData(APPLE_COMPANY_ID) != null

                // Try cached name from Bluetooth adapter for Apple devices
                val cachedName = if (isApple && rawName == null) {
                    try {
                        bluetoothAdapter?.getRemoteDevice(address)?.name
                    } catch (_: Exception) { null }
                } else null

                val displayName = when {
                    rawName != null -> rawName
                    cachedName != null -> cachedName
                    isApple -> "Apple Device"
                    else -> null
                }

                if (address !in seen) {
                    seen.add(address)
                    Log.i(TAG, "Device #${seen.size}: name=$displayName addr=$address rssi=${result.rssi} apple=$isApple")
                    trySend(ScannedDevice(name = displayName, address = address, rssi = result.rssi, isAppleDevice = isApple))
                } else if (displayName != null) {
                    // Update name if we got one on a subsequent scan
                    trySend(ScannedDevice(name = displayName, address = address, rssi = result.rssi, isAppleDevice = isApple))
                }
            }

            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                Log.i(TAG, "Batch scan results: ${results.size}")
                results.forEach { onScanResult(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, it) }
            }

            override fun onScanFailed(errorCode: Int) {
                val reason = when (errorCode) {
                    SCAN_FAILED_ALREADY_STARTED -> "ALREADY_STARTED"
                    SCAN_FAILED_APPLICATION_REGISTRATION_FAILED -> "APP_REGISTRATION_FAILED"
                    SCAN_FAILED_FEATURE_UNSUPPORTED -> "FEATURE_UNSUPPORTED"
                    SCAN_FAILED_INTERNAL_ERROR -> "INTERNAL_ERROR"
                    else -> "UNKNOWN($errorCode)"
                }
                Log.e(TAG, "!!! SCAN FAILED: $reason (code=$errorCode)")
                close(Exception("BLE scan failed: $reason"))
            }
        }

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        try {
            leScanner.startScan(emptyList(), settings, callback)
            Log.i(TAG, "startScan() called successfully")
        } catch (e: Exception) {
            Log.e(TAG, "startScan() threw exception", e)
            close(e)
            return@callbackFlow
        }

        awaitClose {
            Log.i(TAG, "=== SCAN STOP === (found $totalFound results, ${seen.size} unique)")
            try {
                leScanner.stopScan(callback)
            } catch (e: Exception) {
                Log.w(TAG, "stopScan error: ${e.message}")
            }
        }
    }
}
