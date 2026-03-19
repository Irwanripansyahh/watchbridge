package com.watchbridge.ble

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.content.Context
import android.util.Log
import com.watchbridge.ancs.AncsConstants
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import no.nordicsemi.android.ble.BleManager

/**
 * Manages the BLE GATT client connection to an iPhone using Nordic BLE Library.
 * Handles connection lifecycle, ANCS service discovery, characteristic subscriptions,
 * reconnection, and MTU negotiation.
 *
 * All Nordic BLE operations (enableNotifications, setNotificationCallback,
 * writeCharacteristic) are protected and must be called from within a BleManager subclass.
 */
class BleConnectionManager(
    context: Context
) : BleManager(context) {

    companion object {
        private const val TAG = "BleConnectionManager"
        private const val DESIRED_MTU = 256
    }

    enum class ConnectionState {
        DISCONNECTED,
        CONNECTING,
        CONNECTED,
        DISCOVERING_SERVICES,
        READY,
        DISCONNECTING
    }

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    // ANCS characteristics — discovered during service discovery
    private var notificationSourceChar: BluetoothGattCharacteristic? = null
    private var controlPointChar: BluetoothGattCharacteristic? = null
    private var dataSourceChar: BluetoothGattCharacteristic? = null

    // Callbacks set by the caller
    private var onNotificationSource: ((ByteArray) -> Unit)? = null
    private var onDataSource: ((ByteArray) -> Unit)? = null

    override fun log(priority: Int, message: String) {
        Log.println(priority, TAG, message)
    }

    override fun getMinLogPriority(): Int = Log.DEBUG

    override fun isRequiredServiceSupported(gatt: BluetoothGatt): Boolean {
        _connectionState.value = ConnectionState.DISCOVERING_SERVICES

        val ancsService = gatt.getService(AncsConstants.ANCS_SERVICE_UUID)
        if (ancsService == null) {
            Log.w(TAG, "ANCS service not found")
            return false
        }

        notificationSourceChar = ancsService.getCharacteristic(AncsConstants.NOTIFICATION_SOURCE_UUID)
        controlPointChar = ancsService.getCharacteristic(AncsConstants.CONTROL_POINT_UUID)
        dataSourceChar = ancsService.getCharacteristic(AncsConstants.DATA_SOURCE_UUID)

        val allFound = notificationSourceChar != null &&
            controlPointChar != null &&
            dataSourceChar != null

        if (allFound) {
            Log.i(TAG, "ANCS service discovered successfully")
        } else {
            Log.w(TAG, "Missing ANCS characteristics: " +
                "NS=${notificationSourceChar != null} " +
                "CP=${controlPointChar != null} " +
                "DS=${dataSourceChar != null}")
        }

        return allFound
    }

    override fun initialize() {
        _connectionState.value = ConnectionState.CONNECTED

        // Request larger MTU for attribute data
        requestMtu(DESIRED_MTU)
            .with { _, mtu ->
                Log.i(TAG, "MTU negotiated: $mtu")
            }
            .fail { _, status ->
                Log.w(TAG, "MTU request failed with status: $status")
            }
            .enqueue()

        // Set up notification callbacks
        notificationSourceChar?.let { char ->
            setNotificationCallback(char).with { _, data ->
                data.value?.let { bytes -> onNotificationSource?.invoke(bytes) }
            }
        }
        dataSourceChar?.let { char ->
            setNotificationCallback(char).with { _, data ->
                data.value?.let { bytes -> onDataSource?.invoke(bytes) }
            }
        }

        // Subscribe to Notification Source — this triggers iOS bonding!
        notificationSourceChar?.let { char ->
            enableNotifications(char)
                .done {
                    Log.i(TAG, "Subscribed to Notification Source")
                    _connectionState.value = ConnectionState.READY
                }
                .fail { _, status ->
                    Log.e(TAG, "Failed to enable Notification Source: $status")
                }
                .enqueue()
        }

        // Subscribe to Data Source for attribute responses
        dataSourceChar?.let { char ->
            enableNotifications(char)
                .done { Log.i(TAG, "Subscribed to Data Source") }
                .fail { _, status ->
                    Log.e(TAG, "Failed to enable Data Source: $status")
                }
                .enqueue()
        }
    }

    override fun onServicesInvalidated() {
        notificationSourceChar = null
        controlPointChar = null
        dataSourceChar = null
        _connectionState.value = ConnectionState.DISCONNECTED
    }

    /**
     * Set callbacks for ANCS notification data.
     * Must be called before connecting.
     */
    fun setAncsCallbacks(
        onNotificationSource: (ByteArray) -> Unit,
        onDataSource: (ByteArray) -> Unit
    ) {
        this.onNotificationSource = onNotificationSource
        this.onDataSource = onDataSource
    }

    /**
     * Write a command to the ANCS Control Point.
     */
    fun writeControlPoint(data: ByteArray) {
        val char = controlPointChar
        if (char == null) {
            Log.e(TAG, "Control Point characteristic not available")
            return
        }

        writeCharacteristic(
            char,
            data,
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        )
            .fail { _, status ->
                Log.e(TAG, "Control Point write failed: $status")
            }
            .enqueue()
    }

    /**
     * Connect to a BLE device (iPhone).
     */
    fun connectToDevice(device: BluetoothDevice) {
        _connectionState.value = ConnectionState.CONNECTING
        connect(device)
            .retry(3, 200)
            .useAutoConnect(true)
            .done {
                Log.i(TAG, "Connected to ${device.address}")
            }
            .fail { _, status ->
                Log.e(TAG, "Connection failed with status: $status")
                _connectionState.value = ConnectionState.DISCONNECTED
            }
            .enqueue()
    }

    fun disconnectDevice() {
        _connectionState.value = ConnectionState.DISCONNECTING
        disconnect().enqueue()
    }
}
