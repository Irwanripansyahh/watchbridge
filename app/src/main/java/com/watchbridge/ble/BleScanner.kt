package com.watchbridge.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import android.util.Log
import com.watchbridge.ancs.AncsConstants
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * Scans for BLE devices that expose the ANCS service (iPhones).
 */
class BleScanner(context: Context) {

    companion object {
        private const val TAG = "BleScanner"
    }

    private val bluetoothManager =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter = bluetoothManager.adapter
    private val scanner: BluetoothLeScanner?
        get() = bluetoothAdapter?.bluetoothLeScanner

    data class ScannedDevice(
        val name: String?,
        val address: String,
        val rssi: Int
    )

    /**
     * Scan for BLE devices. Emits discovered devices as a Flow.
     * On iPhones, ANCS service UUID may not appear in advertisements,
     * so we scan for all BLE devices and let the user pick.
     */
    @SuppressLint("MissingPermission")
    fun scan(): Flow<ScannedDevice> = callbackFlow {
        val leScanner = scanner
        if (leScanner == null) {
            Log.e(TAG, "BLE scanner not available")
            close()
            return@callbackFlow
        }

        val seen = mutableSetOf<String>()

        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val address = result.device.address
                if (address !in seen) {
                    seen.add(address)
                    val device = ScannedDevice(
                        name = result.device.name,
                        address = address,
                        rssi = result.rssi
                    )
                    Log.d(TAG, "Found device: $device")
                    trySend(device)
                }
            }

            override fun onScanFailed(errorCode: Int) {
                Log.e(TAG, "Scan failed with error code: $errorCode")
                close(Exception("BLE scan failed: $errorCode"))
            }
        }

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        // Scan without filters — iOS doesn't always advertise ANCS UUID
        Log.d(TAG, "Starting BLE scan")
        leScanner.startScan(null, settings, callback)

        awaitClose {
            Log.d(TAG, "Stopping BLE scan")
            leScanner.stopScan(callback)
        }
    }

    /**
     * Scan specifically for devices advertising the ANCS service.
     * Note: iPhones may not advertise ANCS in their scan response,
     * so this may find fewer devices than [scan].
     */
    @SuppressLint("MissingPermission")
    fun scanForAncs(): Flow<ScannedDevice> = callbackFlow {
        val leScanner = scanner
        if (leScanner == null) {
            close()
            return@callbackFlow
        }

        val seen = mutableSetOf<String>()

        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val address = result.device.address
                if (address !in seen) {
                    seen.add(address)
                    trySend(
                        ScannedDevice(
                            name = result.device.name,
                            address = address,
                            rssi = result.rssi
                        )
                    )
                }
            }

            override fun onScanFailed(errorCode: Int) {
                close(Exception("BLE scan failed: $errorCode"))
            }
        }

        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(AncsConstants.ANCS_SERVICE_UUID))
            .build()

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        leScanner.startScan(listOf(filter), settings, callback)

        awaitClose {
            leScanner.stopScan(callback)
        }
    }
}
