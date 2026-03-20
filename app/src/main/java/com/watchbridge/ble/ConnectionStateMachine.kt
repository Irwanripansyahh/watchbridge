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
 * bonded device. Resets backoff on successful READY state.
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
        private const val MAX_RECONNECT_ATTEMPTS = 20
        private const val QUICK_RECONNECT_THRESHOLD_MS = 5_000L
        private const val BOND_GRACE_PERIOD_MS = 35_000L
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
        targetDevice = device
        reconnectAttempt = 0
        currentBackoffMs = INITIAL_BACKOFF_MS
        _state.value = State.CONNECTING
        connectionManager.connectToDevice(device)
        Log.i(TAG, "Connecting to ${device.name ?: device.address}")
    }

    /**
     * Try to auto-connect to the previously bonded device.
     */
    fun autoConnectToBonded(): Boolean {
        val device = bondManager.getBondedDevice()
        if (device != null) {
            connectTo(device)
            return true
        }
        return false
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
                _state.value = State.CONNECTING
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
                Log.i(TAG, "Connection READY — backoff reset")
            }

            BleConnectionManager.ConnectionState.DISCONNECTED -> {
                val previousState = _state.value
                val wasReady = previousState == State.READY || previousState == State.CONNECTED
                _state.value = State.DISCONNECTED

                // Don't auto-reconnect if disconnect happened during bonding
                // (user rejected pairing or bond timed out)
                val timeSinceConnected = System.currentTimeMillis() - lastConnectedTimestamp
                val wasBonding = timeSinceConnected < BOND_GRACE_PERIOD_MS && previousState == State.CONNECTED

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

    private fun scheduleReconnect(delayMs: Long? = null) {
        if (!autoReconnectEnabled) {
            Log.d(TAG, "Auto-reconnect disabled, not reconnecting")
            _state.value = State.IDLE
            return
        }

        val device = targetDevice ?: bondManager.getBondedDevice()
        if (device == null) {
            Log.w(TAG, "No target device for reconnect")
            _state.value = State.FAILED
            return
        }

        if (reconnectAttempt >= MAX_RECONNECT_ATTEMPTS) {
            Log.w(TAG, "Max reconnect attempts ($MAX_RECONNECT_ATTEMPTS) reached")
            _state.value = State.FAILED
            return
        }

        val actualDelay = delayMs ?: currentBackoffMs

        _state.value = State.WAITING_TO_RECONNECT
        Log.i(TAG, "Scheduling reconnect #${reconnectAttempt + 1} in ${actualDelay}ms")

        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            delay(actualDelay)
            reconnectAttempt++
            currentBackoffMs = min(
                (currentBackoffMs * BACKOFF_MULTIPLIER).toLong(),
                MAX_BACKOFF_MS
            )
            _state.value = State.RECONNECTING
            connectionManager.connectToDevice(device)
        }
    }
}
