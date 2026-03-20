package com.watchbridge.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.os.ParcelUuid
import android.util.Log
import com.watchbridge.ancs.AncsConstants
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/**
 * Advertises the watch as a BLE peripheral with ANCS service solicitation.
 *
 * This is required for first-time pairing: iPhone discovers the watch in
 * Settings → Bluetooth and initiates pairing. After bonding, reconnections
 * use normal outbound GATT client connections (no advertising needed).
 *
 * NOTE: Wear OS silently suppresses BLE advertising when the watch has an
 * active companion phone connection. The watch must be disconnected from
 * its companion Android phone for advertising to actually transmit packets.
 */
@SuppressLint("MissingPermission")
class BleAdvertiser(private val context: Context) {

    companion object {
        private const val TAG = "BleAdvertiser"
        private const val DEVICE_NAME = "WatchBridge"

        // Standard Bluetooth GATT service/characteristic UUIDs
        private val GAP_SERVICE_UUID = UUID.fromString("00001800-0000-1000-8000-00805f9b34fb")
        private val DEVICE_NAME_CHAR_UUID = UUID.fromString("00002A00-0000-1000-8000-00805f9b34fb")
        private val APPEARANCE_CHAR_UUID = UUID.fromString("00002A01-0000-1000-8000-00805f9b34fb")
    }

    enum class State { IDLE, ADVERTISING, FAILED }

    private val _state = MutableStateFlow(State.IDLE)
    val state: StateFlow<State> = _state.asStateFlow()

    var onDeviceConnected: ((BluetoothDevice) -> Unit)? = null

    private val bluetoothManager =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter = bluetoothManager.adapter

    private var advertiser: BluetoothLeAdvertiser? = null
    private var gattServer: BluetoothGattServer? = null
    private var savedOriginalName: String? = null

    private val gattServerCallback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            if (newState == BluetoothGatt.STATE_CONNECTED) {
                Log.i(TAG, "iPhone connected to GATT server: ${device.address}")
                stopAdvertising()
                onDeviceConnected?.invoke(device)
            } else if (newState == BluetoothGatt.STATE_DISCONNECTED) {
                Log.i(TAG, "Device disconnected from GATT server: ${device.address}")
            }
        }

        override fun onCharacteristicReadRequest(
            device: BluetoothDevice,
            requestId: Int,
            offset: Int,
            characteristic: BluetoothGattCharacteristic
        ) {
            Log.d(TAG, "Read request for ${characteristic.uuid}")
            val value = characteristic.value ?: byteArrayOf()
            val responseData = if (offset < value.size) {
                value.copyOfRange(offset, value.size)
            } else {
                byteArrayOf()
            }
            gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, responseData)
        }
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            Log.i(TAG, "Advertising started successfully")
            _state.value = State.ADVERTISING
        }

        override fun onStartFailure(errorCode: Int) {
            val reason = when (errorCode) {
                ADVERTISE_FAILED_DATA_TOO_LARGE -> "DATA_TOO_LARGE"
                ADVERTISE_FAILED_TOO_MANY_ADVERTISERS -> "TOO_MANY_ADVERTISERS"
                ADVERTISE_FAILED_ALREADY_STARTED -> "ALREADY_STARTED"
                ADVERTISE_FAILED_INTERNAL_ERROR -> "INTERNAL_ERROR"
                ADVERTISE_FAILED_FEATURE_UNSUPPORTED -> "FEATURE_UNSUPPORTED"
                else -> "UNKNOWN($errorCode)"
            }
            Log.e(TAG, "Advertising failed: $reason")
            _state.value = State.FAILED
        }
    }

    fun startAdvertising() {
        if (_state.value == State.ADVERTISING) {
            Log.w(TAG, "Already advertising")
            return
        }

        if (!bluetoothAdapter.isMultipleAdvertisementSupported) {
            Log.e(TAG, "BLE advertising not supported on this device")
            _state.value = State.FAILED
            return
        }

        // Set a short name to fit within BLE advertisement size limits
        savedOriginalName = bluetoothAdapter.name
        try {
            bluetoothAdapter.name = DEVICE_NAME
            Log.i(TAG, "Set BLE name to '$DEVICE_NAME' (was '${savedOriginalName}')")
        } catch (e: Exception) {
            Log.w(TAG, "Could not set BLE name: ${e.message}")
        }

        // Open GATT server with Generic Access service (required by BLE spec)
        gattServer = bluetoothManager.openGattServer(context, gattServerCallback)
        if (gattServer == null) {
            Log.e(TAG, "Failed to open GATT server")
            restoreDeviceName()
            _state.value = State.FAILED
            return
        }
        setupGattServices()

        advertiser = bluetoothAdapter.bluetoothLeAdvertiser
        if (advertiser == null) {
            Log.e(TAG, "BluetoothLeAdvertiser not available")
            restoreDeviceName()
            _state.value = State.FAILED
            return
        }

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setConnectable(true)
            .setTimeout(0)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .build()

        // Primary data: ANCS service solicitation UUID (AD type 0x15)
        // This tells iPhone "I want to consume your ANCS service"
        // iPhone Settings → Bluetooth shows devices with service solicitation UUIDs
        val advertiseData = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .setIncludeTxPowerLevel(false)
            .addServiceSolicitationUuid(ParcelUuid(AncsConstants.ANCS_SERVICE_UUID))
            .build()

        // Scan response: device name
        val scanResponse = AdvertiseData.Builder()
            .setIncludeDeviceName(true)
            .setIncludeTxPowerLevel(false)
            .build()

        Log.i(TAG, "Starting BLE advertising with ANCS solicitation UUID=${AncsConstants.ANCS_SERVICE_UUID}...")
        Log.i(TAG, "NOTE: If watch is connected to a companion Android phone, advertising may be suppressed by Wear OS")
        advertiser?.startAdvertising(settings, advertiseData, scanResponse, advertiseCallback)
    }

    /**
     * Add Generic Access (0x1800) service with Device Name and Appearance.
     * Required by BLE spec — iPhone expects a compliant GATT server.
     */
    private fun setupGattServices() {
        val gapService = BluetoothGattService(
            GAP_SERVICE_UUID,
            BluetoothGattService.SERVICE_TYPE_PRIMARY
        )

        // Device Name characteristic (0x2A00)
        val deviceNameChar = BluetoothGattCharacteristic(
            DEVICE_NAME_CHAR_UUID,
            BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_READ
        )
        deviceNameChar.setValue(DEVICE_NAME)
        gapService.addCharacteristic(deviceNameChar)

        // Appearance characteristic (0x2A01) — 0x03C0 = "Watch" category
        val appearanceChar = BluetoothGattCharacteristic(
            APPEARANCE_CHAR_UUID,
            BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_READ
        )
        appearanceChar.setValue(byteArrayOf(0xC0.toByte(), 0x03))
        gapService.addCharacteristic(appearanceChar)

        val added = gattServer?.addService(gapService)
        Log.i(TAG, "Added Generic Access service: $added")
    }

    private fun restoreDeviceName() {
        savedOriginalName?.let { name ->
            try {
                bluetoothAdapter.name = name
                Log.i(TAG, "Restored BLE name to '$name'")
            } catch (e: Exception) {
                Log.w(TAG, "Could not restore BLE name: ${e.message}")
            }
            savedOriginalName = null
        }
    }

    fun stopAdvertising() {
        advertiser?.stopAdvertising(advertiseCallback)
        advertiser = null
        restoreDeviceName()
        if (_state.value == State.ADVERTISING) {
            _state.value = State.IDLE
        }
        Log.i(TAG, "Advertising stopped")
    }

    fun close() {
        stopAdvertising()
        gattServer?.close()
        gattServer = null
        onDeviceConnected = null
        Log.i(TAG, "BleAdvertiser closed")
    }
}
