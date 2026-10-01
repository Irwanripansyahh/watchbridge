package com.watchbridge.camera

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.watchbridge.ble.BondManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.Executors

/**
 * Camera shutter for the iPhone. The watch acts as a Bluetooth (Classic) HID remote that
 * presses Volume Up, which the iPhone Camera app treats as the shutter — exactly what a
 * Bluetooth selfie stick does.
 *
 * - Classic HID runs alongside the BLE link used for notifications (two devices can only
 *   share one BLE link, but a Classic and a BLE link can coexist).
 * - Only a consumer control (media keys) is declared, not a keyboard, so iOS keeps showing
 *   its on-screen keyboard.
 * - The remote is only registered while the camera screen is open ([start] / [stop]).
 */
@SuppressLint("MissingPermission")
class CameraRemote(private val context: Context) {

    companion object {
        private const val TAG = "CameraRemote"

        private const val REPORT_ID = 1
        private const val VOLUME_UP: Byte = 0x01
        private const val RELEASED: Byte = 0x00
        private const val KEY_PRESS_MS = 60L

        /** One input report: Volume Up and Volume Down bits, padded to a byte. */
        private val REPORT_DESCRIPTOR = byteArrayOf(
            0x05, 0x0C,                    // Usage Page (Consumer)
            0x09, 0x01,                    // Usage (Consumer Control)
            0xA1.toByte(), 0x01,           // Collection (Application)
            0x85.toByte(), REPORT_ID.toByte(), // Report ID
            0x15, 0x00,                    //   Logical Minimum (0)
            0x25, 0x01,                    //   Logical Maximum (1)
            0x75, 0x01,                    //   Report Size (1)
            0x95.toByte(), 0x02,           //   Report Count (2)
            0x09, 0xE9.toByte(),           //   Usage (Volume Increment)
            0x09, 0xEA.toByte(),           //   Usage (Volume Decrement)
            0x81.toByte(), 0x02,           //   Input (Data, Variable, Absolute)
            0x95.toByte(), 0x06,           //   Report Count (6)
            0x81.toByte(), 0x03,           //   Input (Constant) — padding
            0xC0.toByte()                  // End Collection
        )

        private val SDP_SETTINGS = BluetoothHidDeviceAppSdpSettings(
            "WatchBridge Camera Remote",
            "Camera shutter for phone",
            "WatchBridge",
            BluetoothHidDevice.SUBCLASS1_NONE,
            REPORT_DESCRIPTOR
        )
    }

    enum class Status {
        /** This watch's Bluetooth has no HID device support. */
        UNSUPPORTED,
        /** No paired iPhone found. */
        NO_IPHONE,
        CONNECTING,
        CONNECTED,
        DISCONNECTED
    }

    private val adapter: BluetoothAdapter? =
        context.getSystemService(BluetoothManager::class.java).adapter
    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    private val _status = MutableStateFlow(Status.CONNECTING)
    val status: StateFlow<Status> = _status.asStateFlow()

    private var hidDevice: BluetoothHidDevice? = null
    private var iphone: BluetoothDevice? = null
    private var host: BluetoothDevice? = null

    fun start() {
        iphone = findIphone()
        if (iphone == null) {
            _status.value = Status.NO_IPHONE
            return
        }
        _status.value = Status.CONNECTING
        val requested = adapter?.getProfileProxy(context, serviceListener, BluetoothProfile.HID_DEVICE) == true
        if (!requested) {
            Log.w(TAG, "HID device profile not available")
            _status.value = Status.UNSUPPORTED
        }
    }

    fun stop() {
        hidDevice?.let { hid ->
            host?.let { hid.disconnect(it) }
            hid.unregisterApp()
            adapter?.closeProfileProxy(BluetoothProfile.HID_DEVICE, hid)
        }
        hidDevice = null
        host = null
    }

    fun reconnect() {
        val hid = hidDevice ?: return start()
        val target = iphone ?: return
        _status.value = Status.CONNECTING
        hid.connect(target)
    }

    /** Press and release Volume Up: the iPhone Camera app takes a photo (or starts/stops video). */
    fun shutter(): Boolean {
        val hid = hidDevice ?: return false
        val target = host ?: return false
        if (!hid.sendReport(target, REPORT_ID, byteArrayOf(VOLUME_UP))) {
            Log.w(TAG, "Shutter report not sent")
            return false
        }
        mainHandler.postDelayed({ hid.sendReport(target, REPORT_ID, byteArrayOf(RELEASED)) }, KEY_PRESS_MS)
        return true
    }

    private val serviceListener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
            val hid = proxy as BluetoothHidDevice
            hidDevice = hid
            if (!hid.registerApp(SDP_SETTINGS, null, null, executor, callback)) {
                Log.w(TAG, "registerApp failed")
                _status.value = Status.UNSUPPORTED
            }
        }

        override fun onServiceDisconnected(profile: Int) {
            hidDevice = null
            host = null
            _status.value = Status.DISCONNECTED
        }
    }

    private val callback = object : BluetoothHidDevice.Callback() {
        override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, registered: Boolean) {
            Log.i(TAG, "HID app registered=$registered plugged=${pluggedDevice?.address}")
            if (registered) {
                iphone?.let { hidDevice?.connect(it) }
            }
        }

        override fun onConnectionStateChanged(device: BluetoothDevice, state: Int) {
            Log.i(TAG, "HID connection to ${device.address}: $state")
            when (state) {
                BluetoothProfile.STATE_CONNECTED -> {
                    host = device
                    _status.value = Status.CONNECTED
                }
                BluetoothProfile.STATE_CONNECTING -> _status.value = Status.CONNECTING
                BluetoothProfile.STATE_DISCONNECTED -> {
                    if (host == device) host = null
                    _status.value = Status.DISCONNECTED
                }
            }
        }

        override fun onGetReport(device: BluetoothDevice, type: Byte, id: Byte, bufferSize: Int) {
            // The iPhone may ask for the current state: nothing pressed
            hidDevice?.replyReport(device, type, id, byteArrayOf(RELEASED))
        }
    }

    /** The paired iPhone: the one WatchBridge bonded with, else any paired device named iPhone. */
    private fun findIphone(): BluetoothDevice? {
        val bonded = adapter?.bondedDevices.orEmpty()
        val saved = BondManager(context).getSavedBondedAddress()
        return bonded.firstOrNull { it.address == saved }
            ?: bonded.firstOrNull { it.name?.contains("iPhone", ignoreCase = true) == true }
    }
}
