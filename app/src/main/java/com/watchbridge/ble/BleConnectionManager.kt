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
import com.watchbridge.ams.AmsConstants
import com.watchbridge.ancs.AncsConstants
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import no.nordicsemi.android.ble.BleManager
import java.util.UUID

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
 * 7. Subscribe to Apple Media Service (optional, for media controls)
 */
class BleConnectionManager(
    context: Context
) : BleManager(context) {

    companion object {
        private const val TAG = "BleConnectionManager"
        private const val DESIRED_MTU = 256
        private const val BOND_TIMEOUT_MS = 30_000L

        // Standard Battery Service, which iOS offers to bonded accessories
        private val BATTERY_SERVICE_UUID = UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb")
        private val BATTERY_LEVEL_UUID = UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb")

        // Standard Generic Access service: the name the phone gives itself
        private val GENERIC_ACCESS_UUID = UUID.fromString("00001800-0000-1000-8000-00805f9b34fb")
        private val DEVICE_NAME_UUID = UUID.fromString("00002a00-0000-1000-8000-00805f9b34fb")
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

    // Apple Media Service (optional — media controls only)
    private var amsRemoteCommandChar: BluetoothGattCharacteristic? = null
    private var amsEntityUpdateChar: BluetoothGattCharacteristic? = null
    private var amsEntityAttributeChar: BluetoothGattCharacteristic? = null

    /** The iPhone's battery level in percent, or null when unknown (not connected). */
    private val _phoneBattery = MutableStateFlow<Int?>(null)
    val phoneBattery: StateFlow<Int?> = _phoneBattery.asStateFlow()

    /** The name the phone reports for itself over GATT, or null if it didn't say. */
    private val _phoneDeviceName = MutableStateFlow<String?>(null)
    val phoneDeviceName: StateFlow<String?> = _phoneDeviceName.asStateFlow()

    private var onAmsReady: (() -> Unit)? = null
    private var onAmsRemoteCommands: ((ByteArray) -> Unit)? = null
    private var onAmsEntityUpdate: ((ByteArray) -> Unit)? = null

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

        // Queued after ANCS, so notifications work even if these fail
        setupAms()
        setupBattery()
        readDeviceName()
    }

    /** The phone's own name, a fallback for when the watch's Bluetooth has no name for it. */
    private fun readDeviceName() {
        val name = currentGatt?.getService(GENERIC_ACCESS_UUID)?.getCharacteristic(DEVICE_NAME_UUID)
            ?: return
        readCharacteristic(name)
            .with { _, data ->
                _phoneDeviceName.value = data.value?.toString(Charsets.UTF_8)?.trim()?.ifEmpty { null }
            }
            .fail { _, status -> Log.w(TAG, "Phone device name read failed: $status") }
            .enqueue()
    }

    /** Read the iPhone's battery level and follow its changes. Optional, like AMS. */
    private fun setupBattery() {
        val level = currentGatt?.getService(BATTERY_SERVICE_UUID)?.getCharacteristic(BATTERY_LEVEL_UUID)
        if (level == null) {
            Log.i(TAG, "iPhone battery level not available")
            return
        }

        val onLevel = { bytes: ByteArray? ->
            bytes?.firstOrNull()?.let { _phoneBattery.value = it.toInt() and 0xFF }
        }
        setNotificationCallback(level).with { _, data -> onLevel(data.value) }
        readCharacteristic(level)
            .with { _, data -> onLevel(data.value) }
            .fail { _, status -> Log.w(TAG, "iPhone battery read failed: $status") }
            .enqueue()
        if (level.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) {
            enableNotifications(level)
                .fail { _, status -> Log.w(TAG, "iPhone battery notifications failed: $status") }
                .enqueue()
        }
    }

    /**
     * Subscribe to Apple Media Service: now-playing info and remote control for whatever
     * app is playing on the iPhone. Optional — ANCS works without it.
     */
    private fun setupAms() {
        val service = currentGatt?.getService(AmsConstants.AMS_SERVICE_UUID)
        if (service == null) {
            Log.i(TAG, "AMS not available — media controls disabled")
            return
        }

        val remoteCommand = service.getCharacteristic(AmsConstants.REMOTE_COMMAND_UUID)
        val entityUpdate = service.getCharacteristic(AmsConstants.ENTITY_UPDATE_UUID)
        if (remoteCommand == null || entityUpdate == null) {
            Log.w(TAG, "AMS service found but missing characteristics")
            return
        }
        amsRemoteCommandChar = remoteCommand
        amsEntityUpdateChar = entityUpdate
        amsEntityAttributeChar = service.getCharacteristic(AmsConstants.ENTITY_ATTRIBUTE_UUID)

        setNotificationCallback(remoteCommand).with { _, data ->
            data.value?.let { bytes -> onAmsRemoteCommands?.invoke(bytes) }
        }
        setNotificationCallback(entityUpdate).with { _, data ->
            data.value?.let { bytes -> onAmsEntityUpdate?.invoke(bytes) }
        }

        enableNotifications(remoteCommand)
            .fail { _, status -> Log.w(TAG, "✗ Failed to enable AMS Remote Command: $status") }
            .enqueue()
        enableNotifications(entityUpdate)
            .fail { _, status -> Log.w(TAG, "✗ Failed to enable AMS Entity Update: $status") }
            .enqueue()

        // Tell iOS which attributes to send us: one write per entity
        writeCharacteristic(
            entityUpdate,
            byteArrayOf(
                AmsConstants.ENTITY_PLAYER,
                AmsConstants.PLAYER_ATTR_NAME,
                AmsConstants.PLAYER_ATTR_PLAYBACK_INFO,
                AmsConstants.PLAYER_ATTR_VOLUME
            ),
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        )
            .fail { _, status -> Log.w(TAG, "✗ AMS player subscription failed: $status") }
            .enqueue()
        writeCharacteristic(
            entityUpdate,
            byteArrayOf(
                AmsConstants.ENTITY_TRACK,
                AmsConstants.TRACK_ATTR_ARTIST,
                AmsConstants.TRACK_ATTR_TITLE,
                AmsConstants.TRACK_ATTR_DURATION
            ),
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        )
            .done {
                Log.i(TAG, "✓ Subscribed to Apple Media Service")
                onAmsReady?.invoke()
            }
            .fail { _, status -> Log.w(TAG, "✗ AMS track subscription failed: $status") }
            .enqueue()
    }

    override fun onServicesInvalidated() {
        notificationSourceChar = null
        controlPointChar = null
        dataSourceChar = null
        amsRemoteCommandChar = null
        amsEntityUpdateChar = null
        amsEntityAttributeChar = null
        _phoneBattery.value = null
        _phoneDeviceName.value = null
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

    fun setAmsCallbacks(
        onReady: () -> Unit,
        onRemoteCommands: (ByteArray) -> Unit,
        onEntityUpdate: (ByteArray) -> Unit
    ) {
        onAmsReady = onReady
        onAmsRemoteCommands = onRemoteCommands
        onAmsEntityUpdate = onEntityUpdate
    }

    /** Send an AMS RemoteCommandID (play, pause, next track, volume up, ...). */
    fun writeAmsRemoteCommand(commandId: Byte) {
        val char = amsRemoteCommandChar ?: run {
            Log.w(TAG, "AMS Remote Command not available")
            return
        }
        writeCharacteristic(char, byteArrayOf(commandId), BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
            .fail { _, status -> Log.w(TAG, "AMS command $commandId failed: $status") }
            .enqueue()
    }

    /** Fetch the full value of a truncated AMS attribute: write which one, then read it. */
    fun readAmsAttribute(entityId: Byte, attributeId: Byte, onValue: (String) -> Unit) {
        val char = amsEntityAttributeChar ?: return
        writeCharacteristic(
            char, byteArrayOf(entityId, attributeId), BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        )
            .fail { _, status -> Log.w(TAG, "AMS attribute select failed: $status") }
            .enqueue()
        readCharacteristic(char)
            .with { _, data -> data.value?.let { onValue(String(it, Charsets.UTF_8)) } }
            .fail { _, status -> Log.w(TAG, "AMS attribute read failed: $status") }
            .enqueue()
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

    /** Bumped on every new connection attempt, so failures of superseded attempts are ignored. */
    private var connectGeneration = 0

    /**
     * Direct connection attempt: fast, but gives up if the iPhone isn't reachable right now.
     * [onFailed] is called if this attempt fails (and wasn't superseded by a newer one).
     */
    fun connectToDevice(device: BluetoothDevice, onFailed: () -> Unit = {}) {
        enqueueConnect(device, background = false, onFailed = onFailed)
    }

    /**
     * Background connection for a bonded iPhone that is out of range. After one quick direct
     * try, Nordic hands over to Android's autoConnect: a low-power wait with no timeout that
     * connects as soon as the iPhone is back in range.
     */
    fun connectInBackground(device: BluetoothDevice, onFailed: () -> Unit = {}) {
        enqueueConnect(device, background = true, onFailed = onFailed)
    }

    private fun enqueueConnect(device: BluetoothDevice, background: Boolean, onFailed: () -> Unit) {
        // Run on the main thread so cancelQueue() and the new request stay in order.
        mainHandler.post {
            // A pending background attempt blocks Nordic's queue (it never times out),
            // so drop it first — otherwise this request would never run.
            cancelQueue()

            val generation = ++connectGeneration
            _connectionState.value = ConnectionState.CONNECTING
            Log.i(TAG, "Attempting ${if (background) "background" else "direct"} connection to ${device.address}...")

            val request = connect(device)
            if (background) {
                request.useAutoConnect(true)
            } else {
                request
                    .retry(2, 500)       // 2 retries with 500ms delay
                    .timeout(15000)      // 15s timeout — bonding can take time
                    .useAutoConnect(false)
            }
            request
                .done {
                    Log.i(TAG, "Connected to ${device.address}")
                }
                .fail { _, status ->
                    if (generation != connectGeneration) return@fail
                    Log.e(TAG, "Connection failed to ${device.address} with status: $status")
                    _connectionState.value = ConnectionState.DISCONNECTED
                    onFailed()
                }
                .enqueue()
        }
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
        mainHandler.post {
            // disconnect() alone can't stop a pending background attempt (it waits in the queue)
            cancelQueue()
            disconnect().enqueue()
        }
    }
}
