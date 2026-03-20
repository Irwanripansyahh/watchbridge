package com.watchbridge.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.watchbridge.ancs.AncsConstants
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import no.nordicsemi.android.ble.BleManager

/**
 * Manages the BLE GATT client connection to an iPhone using Nordic BLE Library.
 *
 * CORRECTED Connection flow:
 * 1. Connect to iPhone as GATT client
 * 2. Accept any services (ANCS is HIDDEN until bonded!)
 * 3. Request bond → triggers iOS pairing dialog
 * 4. After bonding, re-discover services to find ANCS
 * 5. Subscribe to Notification Source & Data Source
 * 6. Connection READY
 */
class BleConnectionManager(
    context: Context
) : BleManager(context) {

    companion object {
        private const val TAG = "BleConnectionManager"
        private const val DESIRED_MTU = 256
        private const val BOND_TIMEOUT_MS = 30_000L
    }

    enum class ConnectionState {
        DISCONNECTED,
        CONNECTING,
        CONNECTED,
        DISCOVERING_SERVICES,
        BONDING,
        SUBSCRIBING,
        READY,
        DISCONNECTING
    }

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private var notificationSourceChar: BluetoothGattCharacteristic? = null
    private var controlPointChar: BluetoothGattCharacteristic? = null
    private var dataSourceChar: BluetoothGattCharacteristic? = null

    // Keep a reference to the BluetoothGatt for manual service re-discovery
    private var currentGatt: BluetoothGatt? = null

    private var onNotificationSource: ((ByteArray) -> Unit)? = null
    private var onDataSource: ((ByteArray) -> Unit)? = null

    override fun log(priority: Int, message: String) {
        Log.println(priority, TAG, message)
    }

    override fun getMinLogPriority(): Int = Log.DEBUG

    /**
     * CRITICAL FIX: Always return true here.
     *
     * iOS does NOT expose ANCS in GATT service discovery until the device is bonded.
     * We must accept the connection first, bond, and THEN discover ANCS.
     * Nordic BLE calls this before initialize(), so returning false would disconnect.
     */
    override fun isRequiredServiceSupported(gatt: BluetoothGatt): Boolean {
        currentGatt = gatt
        _connectionState.value = ConnectionState.DISCOVERING_SERVICES

        // Try to find ANCS now — it will likely fail on first connection (pre-bond)
        val found = tryDiscoverAncs(gatt)
        if (found) {
            Log.i(TAG, "ANCS found immediately (device may already be bonded)")
        } else {
            Log.i(TAG, "ANCS not found yet — will discover after bonding")
        }

        // Always return true — we'll handle ANCS discovery after bonding
        return true
    }

    private var bondReceiver: BroadcastReceiver? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var bondTimeoutRunnable: Runnable? = null

    @SuppressLint("MissingPermission")
    override fun initialize() {
        _connectionState.value = ConnectionState.CONNECTED
        Log.i(TAG, "=== INITIALIZE START ===")

        // Step 1: Request larger MTU
        requestMtu(DESIRED_MTU)
            .with { _, mtu -> Log.i(TAG, "MTU negotiated: $mtu") }
            .fail { _, status -> Log.w(TAG, "MTU request failed: $status") }
            .enqueue()

        // Step 2: Check bond state
        val device = bluetoothDevice
        when (device?.bondState) {
            BluetoothDevice.BOND_BONDED -> {
                Log.i(TAG, "Already bonded — discovering ANCS directly")
                afterBondEstablished()
                return
            }
            BluetoothDevice.BOND_BONDING -> {
                // iPhone may have already started bonding (e.g., from incoming connection)
                Log.i(TAG, "Already bonding — waiting for bond to complete...")
                _connectionState.value = ConnectionState.BONDING
                registerBondReceiver(device)
                // Don't call createBond() — bonding is already in progress
            }
            else -> {
                // Step 3: Initiate bonding via Android system API
                // iOS shows the pairing dialog when it receives the SMP pairing request
                _connectionState.value = ConnectionState.BONDING
                Log.i(TAG, "Initiating createBond() — iPhone should show pairing dialog...")

                registerBondReceiver(device)

                val bondStarted = device?.createBond() ?: false
                Log.i(TAG, "createBond() initiated: $bondStarted")

                if (!bondStarted) {
                    Log.w(TAG, "createBond() returned false — device may already be bonding")
                }
            }
        }

        // Timeout if user doesn't accept pairing within 30s
        bondTimeoutRunnable = Runnable {
            Log.e(TAG, "Bond timeout — user did not accept pairing in ${BOND_TIMEOUT_MS}ms")
            unregisterBondReceiver()
            _connectionState.value = ConnectionState.DISCONNECTED
        }
        mainHandler.postDelayed(bondTimeoutRunnable!!, BOND_TIMEOUT_MS)
    }

    @SuppressLint("MissingPermission")
    private fun registerBondReceiver(device: BluetoothDevice?) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                if (intent.action != BluetoothDevice.ACTION_BOND_STATE_CHANGED) return

                val bondDevice = intent.getParcelableExtra<BluetoothDevice>(
                    BluetoothDevice.EXTRA_DEVICE
                )
                if (bondDevice?.address != device?.address) return

                val bondState = intent.getIntExtra(
                    BluetoothDevice.EXTRA_BOND_STATE,
                    BluetoothDevice.BOND_NONE
                )

                when (bondState) {
                    BluetoothDevice.BOND_BONDING -> {
                        Log.i(TAG, "Bonding in progress — user should see dialog on iPhone")
                    }
                    BluetoothDevice.BOND_BONDED -> {
                        Log.i(TAG, "=== BONDED SUCCESSFULLY === Re-discovering services...")
                        cancelBondTimeout()
                        unregisterBondReceiver()
                        afterBondEstablished()
                    }
                    BluetoothDevice.BOND_NONE -> {
                        Log.e(TAG, "Bond removed/failed — user may have rejected pairing")
                        cancelBondTimeout()
                        unregisterBondReceiver()
                        _connectionState.value = ConnectionState.DISCONNECTED
                    }
                }
            }
        }
        bondReceiver = receiver
        context.registerReceiver(receiver, IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED))
    }

    private fun unregisterBondReceiver() {
        bondReceiver?.let {
            try {
                context.unregisterReceiver(it)
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "Bond receiver already unregistered")
            }
        }
        bondReceiver = null
    }

    private fun cancelBondTimeout() {
        bondTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        bondTimeoutRunnable = null
    }

    /**
     * After bonding, re-discover GATT services.
     * iOS now exposes ANCS since we are bonded/encrypted.
     */
    private fun afterBondEstablished() {
        val gatt = currentGatt
        if (gatt == null) {
            Log.e(TAG, "No GATT reference — cannot re-discover services")
            _connectionState.value = ConnectionState.DISCONNECTED
            return
        }

        _connectionState.value = ConnectionState.DISCOVERING_SERVICES
        Log.i(TAG, "Re-discovering services after bonding...")

        // Force service re-discovery — iOS will now expose ANCS
        @SuppressLint("MissingPermission")
        val started = gatt.discoverServices()
        if (!started) {
            Log.e(TAG, "discoverServices() returned false")
        }

        // Wait for service discovery, then find ANCS and subscribe
        waitForServiceDiscoveryAndSubscribe(gatt, attempt = 1)
    }

    /**
     * Wait for service re-discovery, then look for ANCS. Retries once.
     */
    private fun waitForServiceDiscoveryAndSubscribe(gatt: BluetoothGatt, attempt: Int) {
        beginAtomicRequestQueue()
            .add(sleep(2000))
            .done {
                Log.i(TAG, "Post-bond delay complete (attempt $attempt), looking for ANCS...")
                logDiscoveredServices(gatt)
                val found = tryDiscoverAncs(gatt)
                if (found) {
                    setupAndSubscribe()
                } else if (attempt < 2) {
                    Log.w(TAG, "ANCS not found, retrying service discovery...")
                    @SuppressLint("MissingPermission")
                    val retried = gatt.discoverServices()
                    Log.i(TAG, "Retry discoverServices: $retried")
                    waitForServiceDiscoveryAndSubscribe(gatt, attempt + 1)
                } else {
                    Log.e(TAG, "ANCS not found after bonding + retries")
                    _connectionState.value = ConnectionState.DISCONNECTED
                }
            }
            .enqueue()
    }

    private fun logDiscoveredServices(gatt: BluetoothGatt) {
        val services = gatt.services
        Log.i(TAG, "Discovered ${services?.size ?: 0} services:")
        services?.forEach { svc ->
            Log.i(TAG, "  Service: ${svc.uuid} (${svc.characteristics.size} chars)")
        }
    }

    /**
     * Try to find ANCS service and characteristics in the current GATT.
     */
    private fun tryDiscoverAncs(gatt: BluetoothGatt): Boolean {
        val ancsService: BluetoothGattService? = gatt.getService(AncsConstants.ANCS_SERVICE_UUID)
        if (ancsService == null) {
            Log.d(TAG, "ANCS service UUID not found in ${gatt.services?.size ?: 0} services")
            return false
        }

        notificationSourceChar = ancsService.getCharacteristic(AncsConstants.NOTIFICATION_SOURCE_UUID)
        controlPointChar = ancsService.getCharacteristic(AncsConstants.CONTROL_POINT_UUID)
        dataSourceChar = ancsService.getCharacteristic(AncsConstants.DATA_SOURCE_UUID)

        val allFound = notificationSourceChar != null &&
            controlPointChar != null &&
            dataSourceChar != null

        if (allFound) {
            Log.i(TAG, "✓ ANCS service and all characteristics discovered!")
        } else {
            Log.w(TAG, "ANCS service found but missing characteristics: " +
                "NS=${notificationSourceChar != null} " +
                "CP=${controlPointChar != null} " +
                "DS=${dataSourceChar != null}")
        }

        return allFound
    }

    /**
     * Set up notification callbacks and subscribe to ANCS characteristics.
     */
    private fun setupAndSubscribe() {
        _connectionState.value = ConnectionState.SUBSCRIBING
        Log.i(TAG, "=== SETTING UP ANCS SUBSCRIPTIONS ===")

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

        // Subscribe to Data Source FIRST (Apple recommends this order)
        dataSourceChar?.let { char ->
            enableNotifications(char)
                .done { Log.i(TAG, "✓ Subscribed to Data Source") }
                .fail { _, status ->
                    Log.e(TAG, "✗ Failed to enable Data Source: $status")
                }
                .enqueue()
        }

        // Then subscribe to Notification Source
        notificationSourceChar?.let { char ->
            enableNotifications(char)
                .done {
                    Log.i(TAG, "✓ Subscribed to Notification Source — READY!")
                    _connectionState.value = ConnectionState.READY
                }
                .fail { _, status ->
                    Log.e(TAG, "✗ Failed to enable Notification Source: $status")
                }
                .enqueue()
        }
    }

    override fun onServicesInvalidated() {
        notificationSourceChar = null
        controlPointChar = null
        dataSourceChar = null
        currentGatt = null
        cancelBondTimeout()
        unregisterBondReceiver()
        _connectionState.value = ConnectionState.DISCONNECTED
    }

    fun setAncsCallbacks(
        onNotificationSource: (ByteArray) -> Unit,
        onDataSource: (ByteArray) -> Unit
    ) {
        this.onNotificationSource = onNotificationSource
        this.onDataSource = onDataSource
    }

    fun writeControlPoint(data: ByteArray) {
        val char = controlPointChar ?: run {
            Log.e(TAG, "Control Point characteristic not available")
            return
        }
        writeCharacteristic(char, data, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
            .fail { _, status -> Log.e(TAG, "Control Point write failed: $status") }
            .enqueue()
    }

    fun connectToDevice(device: BluetoothDevice) {
        _connectionState.value = ConnectionState.CONNECTING
        Log.i(TAG, "Attempting connection to ${device.address}...")
        connect(device)
            .retry(2, 500)       // 2 retries with 500ms delay
            .timeout(15000)      // 15s timeout — bonding can take time
            .useAutoConnect(false)
            .done {
                Log.i(TAG, "Connected to ${device.address}")
            }
            .fail { _, status ->
                Log.e(TAG, "Connection failed to ${device.address} with status: $status")
                _connectionState.value = ConnectionState.DISCONNECTED
            }
            .enqueue()
    }

    /**
     * Connect to a device that has already connected to our GATT server (incoming connection).
     * Uses autoConnect since the device is already in range at the link layer.
     * Longer timeout and more retries to accommodate bonding during this phase.
     */
    fun connectAfterIncoming(device: BluetoothDevice) {
        _connectionState.value = ConnectionState.CONNECTING
        Log.i(TAG, "Connecting after incoming connection from ${device.address}...")
        connect(device)
            .retry(3, 1000)      // 3 retries with 1s delay
            .timeout(30000)      // 30s timeout — bonding happens during this phase
            .useAutoConnect(true) // Device is already in range/connected at link layer
            .done {
                Log.i(TAG, "Connected after incoming from ${device.address}")
            }
            .fail { _, status ->
                Log.e(TAG, "Connection failed after incoming from ${device.address} with status: $status")
                _connectionState.value = ConnectionState.DISCONNECTED
            }
            .enqueue()
    }

    fun disconnectDevice() {
        _connectionState.value = ConnectionState.DISCONNECTING
        disconnect().enqueue()
    }
}
