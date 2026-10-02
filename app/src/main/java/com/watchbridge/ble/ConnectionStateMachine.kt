package com.watchbridge.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.math.min

/**
 * Manages the full connection lifecycle with auto-reconnect and exponential backoff.
 *
 * States:
 *   IDLE → CONNECTING → CONNECTED → READY
 *                ↑           ↓
 *            WAITING ← DISCONNECTED
 *
 * On disconnect, waits with exponential backoff then auto-reconnects to the
 * bonded device. Resets backoff on successful READY state. Once the quick attempts are
 * used up (iPhone left behind, out of range), it never gives up: it hands over to
 * Android's low-power background connect, which reconnects when the iPhone is back.
 */
class ConnectionStateMachine(
    private val connectionManager: BleConnectionManager,
    private val bondManager: BondManager
) {

    companion object {
        private const val TAG = "ConnStateMachine"
        private const val INITIAL_BACKOFF_MS = 1_000L
        private const val MAX_BACKOFF_MS = 60_000L
        private const val BACKOFF_MULTIPLIER = 2.0
        /** Quick attempts (~5 minutes) before switching to the background connect. */
        private const val MAX_RECONNECT_ATTEMPTS = 8
        private const val QUICK_RECONNECT_THRESHOLD_MS = 5_000L
        private const val BOND_GRACE_PERIOD_MS = 35_000L

        /** Right after Bluetooth turns on, the paired-device list can take a moment to fill. */
        private const val BONDED_LIST_RETRIES = 10
        private const val BONDED_LIST_RETRY_MS = 500L
    }

    enum class State {
        IDLE,
        ADVERTISING,
        CONNECTING,
        CONNECTED,
        READY,
        DISCONNECTED,
        WAITING_TO_RECONNECT,
        RECONNECTING,
        /** iPhone out of range: Android connects in the background as soon as it's back. */
        WAITING_FOR_PHONE,
        /** The watch's Bluetooth is off: nothing to try until it's back on. */
        BLUETOOTH_OFF,
        /** No bonded iPhone to reconnect to; the user has to pair again. */
        FAILED
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _state = MutableStateFlow(State.IDLE)
    val state: StateFlow<State> = _state.asStateFlow()

    private var reconnectJob: Job? = null
    private var reconnectAttempt = 0
    private var currentBackoffMs = INITIAL_BACKOFF_MS
    private var lastConnectedTimestamp = 0L
    private var autoReconnectEnabled = true
    private var targetDevice: BluetoothDevice? = null

    /** Listener for state machine events. */
    var onSessionReset: (() -> Unit)? = null
    var onDisconnected: (() -> Unit)? = null

    /**
     * Start monitoring the connection manager's state.
     */
    fun start() {
        scope.launch {
            connectionManager.connectionState.collect { connState ->
                handleConnectionStateChange(connState)
            }
        }
        Log.i(TAG, "Connection state machine started")
    }

    /**
     * Initiate connection to a device. Stores target for auto-reconnect.
     */
    @SuppressLint("MissingPermission")
    fun connectTo(device: BluetoothDevice) {
        if (_state.value == State.READY || _state.value == State.CONNECTED) {
            Log.d(TAG, "Already connected, ignoring connect request")
            return
        }
        targetDevice = device
        autoReconnectEnabled = true
        if (!bondManager.isBluetoothEnabled()) {
            // Connects once Bluetooth is back on (see onBluetoothStateChanged)
            onBluetoothStateChanged(enabled = false)
            return
        }
        reconnectAttempt = 0
        currentBackoffMs = INITIAL_BACKOFF_MS
        reconnectJob?.cancel()
        _state.value = State.CONNECTING
        connectionManager.connectToDevice(device, onFailed = ::onConnectAttemptFailed)
        Log.i(TAG, "Connecting to ${device.name ?: device.address}")
    }

    /**
     * Try to auto-connect to the previously bonded device.
     */
    /**
     * Reconnect to the phone paired before. Returns false only if no phone was ever paired
     * (then pairing is needed); a paired phone is always reconnected, never paired again.
     */
    fun autoConnectToBonded(): Boolean {
        if (!bondManager.hasPairedPhone()) return false
        autoReconnectEnabled = true
        if (!bondManager.isBluetoothEnabled()) {
            // Paired devices can't even be listed with Bluetooth off; connect once it's back on
            onBluetoothStateChanged(enabled = false)
            return true
        }
        reconnectToPairedPhone()
        return true
    }

    /**
     * Connect straight to the paired phone. If it isn't in the paired-device list yet (just
     * after Bluetooth turned on), wait for it briefly instead of declaring it unpaired.
     */
    private fun reconnectToPairedPhone() {
        val savedAddress = bondManager.getSavedBondedAddress()
        val device = bondManager.getBondedDevice()
            ?: targetDevice?.takeIf { it.address == savedAddress }
        if (device != null) {
            connectTo(device)
            return
        }

        reconnectJob?.cancel()
        _state.value = State.CONNECTING
        reconnectJob = scope.launch {
            repeat(BONDED_LIST_RETRIES) {
                delay(BONDED_LIST_RETRY_MS)
                bondManager.getBondedDevice()?.let {
                    connectTo(it)
                    return@launch
                }
            }
            Log.w(TAG, "Paired phone $savedAddress isn't in the paired-device list")
            _state.value = State.FAILED
        }
    }

    /**
     * Manually disconnect and disable auto-reconnect.
     */
    fun disconnect() {
        autoReconnectEnabled = false
        reconnectJob?.cancel()
        reconnectJob = null
        connectionManager.disconnectDevice()
        _state.value = State.IDLE
        targetDevice = null
        Log.i(TAG, "Manual disconnect")
    }

    /**
     * The watch's Bluetooth was turned off or on. While it's off, reconnecting pauses instead
     * of retrying in a loop; once it's back on, reconnecting starts over right away.
     */
    fun onBluetoothStateChanged(enabled: Boolean) {
        if (!enabled) {
            reconnectJob?.cancel()
            reconnectJob = null
            val previous = _state.value
            if (previous == State.BLUETOOTH_OFF) return
            _state.value = State.BLUETOOTH_OFF
            Log.i(TAG, "Bluetooth off — pausing reconnects")
            // The link went down with Bluetooth: clean up as for any disconnect
            if (previous == State.READY || previous == State.CONNECTED) onDisconnected?.invoke()
            return
        }

        if (_state.value != State.BLUETOOTH_OFF) return
        _state.value = State.IDLE
        if (!bondManager.hasPairedPhone()) return
        // Straight back to the last phone, even after a manual disconnect: turning Bluetooth
        // back on means "connect again"
        Log.i(TAG, "Bluetooth back on — reconnecting to the paired phone")
        autoReconnectEnabled = true
        reconnectToPairedPhone()
    }

    /**
     * Re-enable auto-reconnect (e.g., after user manually disconnected then wants to reconnect).
     */
    fun enableAutoReconnect() {
        autoReconnectEnabled = true
    }

    /**
     * Start BLE advertising for first-time pairing.
     * When iPhone connects, transitions to CONNECTING and initiates GATT client connection.
     */
    fun startAdvertisingForPairing(advertiser: BleAdvertiser) {
        _state.value = State.ADVERTISING
        autoReconnectEnabled = true
        advertiser.onDeviceConnected = { device ->
            targetDevice = device
            reconnectAttempt = 0
            currentBackoffMs = INITIAL_BACKOFF_MS
            _state.value = State.CONNECTING
            connectionManager.connectAfterIncoming(device)
        }
        advertiser.startAdvertising()
        Log.i(TAG, "Started advertising for pairing")
    }

    /**
     * Stop advertising (user cancelled pairing).
     */
    fun stopAdvertising(advertiser: BleAdvertiser) {
        advertiser.stopAdvertising()
        advertiser.onDeviceConnected = null
        if (_state.value == State.ADVERTISING) {
            _state.value = State.IDLE
        }
        Log.i(TAG, "Stopped advertising")
    }

    private fun handleConnectionStateChange(connState: BleConnectionManager.ConnectionState) {
        when (connState) {
            BleConnectionManager.ConnectionState.CONNECTING -> {
                // A background connect stays "waiting" until the iPhone actually shows up
                if (_state.value != State.WAITING_FOR_PHONE) {
                    _state.value = State.CONNECTING
                }
            }

            BleConnectionManager.ConnectionState.CONNECTED,
            BleConnectionManager.ConnectionState.DISCOVERING_SERVICES,
            BleConnectionManager.ConnectionState.SUBSCRIBING -> {
                _state.value = State.CONNECTED
                lastConnectedTimestamp = System.currentTimeMillis()
            }

            BleConnectionManager.ConnectionState.BONDING -> {
                // Don't trigger reconnect during bonding — user may be accepting on iPhone
                _state.value = State.CONNECTED
                lastConnectedTimestamp = System.currentTimeMillis()
                Log.i(TAG, "Bonding in progress — suppressing auto-reconnect")
            }

            BleConnectionManager.ConnectionState.READY -> {
                _state.value = State.READY
                // Reset backoff on successful connection
                reconnectAttempt = 0
                currentBackoffMs = INITIAL_BACKOFF_MS
                autoReconnectEnabled = true
                // The last connected phone is the one to reconnect to from now on
                connectionManager.bluetoothDevice?.let {
                    targetDevice = it
                    bondManager.rememberConnectedPhone(it)
                }
                Log.i(TAG, "Connection READY — backoff reset")
            }

            BleConnectionManager.ConnectionState.DISCONNECTED -> {
                val previousState = _state.value
                // Manual disconnect, or a failed attempt that already scheduled the next one
                if (previousState == State.IDLE ||
                    previousState == State.WAITING_TO_RECONNECT ||
                    previousState == State.WAITING_FOR_PHONE ||
                    previousState == State.BLUETOOTH_OFF
                ) return

                val wasReady = previousState == State.READY || previousState == State.CONNECTED
                // If a failed attempt just scheduled a retry, keep showing that instead
                if (!_state.compareAndSet(previousState, State.DISCONNECTED)) return

                // Don't auto-reconnect if disconnect happened during first-time pairing
                // (user rejected pairing or bond timed out). An already bonded iPhone
                // dropping right after connecting is just flaky range: reconnect.
                val timeSinceConnected = System.currentTimeMillis() - lastConnectedTimestamp
                val wasBonding = timeSinceConnected < BOND_GRACE_PERIOD_MS &&
                    previousState == State.CONNECTED &&
                    !isPairedIphone(targetDevice)

                if (wasBonding) {
                    Log.i(TAG, "Disconnect during bonding phase — not auto-reconnecting")
                    onDisconnected?.invoke()
                } else if (wasReady) {
                    onDisconnected?.invoke()

                    if (timeSinceConnected < QUICK_RECONNECT_THRESHOLD_MS) {
                        Log.i(TAG, "Quick disconnect detected (${timeSinceConnected}ms), reconnecting immediately")
                        scheduleReconnect(delayMs = 500)
                    } else {
                        Log.i(TAG, "Disconnect after ${timeSinceConnected}ms, clearing session")
                        onSessionReset?.invoke()
                        scheduleReconnect()
                    }
                }
            }

            BleConnectionManager.ConnectionState.DISCONNECTING -> {
                // User-initiated, don't auto-reconnect
            }
        }
    }

    /**
     * A connection attempt failed (iPhone out of range, Bluetooth busy, ...). Keep trying as
     * long as there's a bonded iPhone; a first-time pairing that fails is left to the user.
     */
    private fun onConnectAttemptFailed() {
        if (!autoReconnectEnabled) return
        // Failed because Bluetooth was turned off: wait for it instead of retrying
        if (!bondManager.isBluetoothEnabled()) {
            onBluetoothStateChanged(enabled = false)
            return
        }
        val device = targetDevice ?: bondManager.getBondedDevice()
        if (!isPairedIphone(device)) return
        scheduleReconnect()
    }

    /**
     * The saved address also counts: with the watch's Bluetooth turned off the bond state
     * reads as NONE, but we still want to reconnect once it's back on.
     */
    @SuppressLint("MissingPermission")
    private fun isPairedIphone(device: BluetoothDevice?): Boolean =
        device != null && (
            device.bondState == BluetoothDevice.BOND_BONDED ||
                device.address == bondManager.getSavedBondedAddress()
            )

    private fun scheduleReconnect(delayMs: Long? = null) {
        if (!autoReconnectEnabled) {
            Log.d(TAG, "Auto-reconnect disabled, not reconnecting")
            _state.value = State.IDLE
            return
        }
        if (!bondManager.isBluetoothEnabled()) {
            onBluetoothStateChanged(enabled = false)
            return
        }

        val device = targetDevice ?: bondManager.getBondedDevice()
        if (device == null) {
            Log.w(TAG, "No target device for reconnect")
            _state.value = State.FAILED
            return
        }

        reconnectJob?.cancel()

        if (reconnectAttempt >= MAX_RECONNECT_ATTEMPTS) {
            Log.i(TAG, "Quick reconnects used up — waiting for iPhone in the background")
            _state.value = State.WAITING_FOR_PHONE
            // The background connect has no timeout; if it still fails (e.g. Bluetooth was
            // turned off), wait a bit before arming it again.
            val wait = delayMs ?: if (reconnectAttempt == MAX_RECONNECT_ATTEMPTS) 0L else MAX_BACKOFF_MS
            reconnectAttempt = MAX_RECONNECT_ATTEMPTS + 1
            reconnectJob = scope.launch {
                delay(wait)
                connectionManager.connectInBackground(device, onFailed = ::onConnectAttemptFailed)
            }
            return
        }

        val actualDelay = delayMs ?: currentBackoffMs

        _state.value = State.WAITING_TO_RECONNECT
        Log.i(TAG, "Scheduling reconnect #${reconnectAttempt + 1} in ${actualDelay}ms")

        reconnectJob = scope.launch {
            delay(actualDelay)
            reconnectAttempt++
            currentBackoffMs = min(
                (currentBackoffMs * BACKOFF_MULTIPLIER).toLong(),
                MAX_BACKOFF_MS
            )
            _state.value = State.RECONNECTING
            connectionManager.connectToDevice(device, onFailed = ::onConnectAttemptFailed)
        }
    }
}
