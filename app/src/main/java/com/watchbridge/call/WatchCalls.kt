package com.watchbridge.call

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.telecom.TelecomManager
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.watchbridge.settings.SettingsManager

/**
 * Talking on the watch itself. When the watch is connected to the phone as its Bluetooth
 * hands-free device (like a headset), the watch's own phone app rings for incoming calls and
 * carries the call audio. Only the system can create that link, so WatchBridge cooperates
 * with it: it answers through the watch's call system (Telecom), which puts the audio on the
 * watch, and steps aside when the watch's own call screen is already ringing.
 *
 * Without that link — or with "Answer calls on watch" off — calls are answered on the phone
 * as before (ANCS).
 */
object WatchCalls {

    private const val TAG = "WatchCalls"

    val PERMISSIONS = arrayOf(
        Manifest.permission.READ_PHONE_STATE,
        Manifest.permission.ANSWER_PHONE_CALLS
    )

    fun hasPermissions(context: Context): Boolean = PERMISSIONS.all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

    fun isEnabled(context: Context): Boolean =
        SettingsManager(context).isCallsOnWatchEnabled && hasPermissions(context)

    /** The watch's own phone app is ringing: a hands-free call coming from the phone. */
    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION") // The call state across every call the watch's phone app handles
    fun isRinging(context: Context): Boolean =
        isEnabled(context) && runCatching {
            context.getSystemService(TelephonyManager::class.java).callState ==
                TelephonyManager.CALL_STATE_RINGING
        }.getOrDefault(false)

    /** A call is going on in the watch's own phone app (so its in-call screen is showing). */
    @SuppressLint("MissingPermission")
    fun isInCall(context: Context): Boolean =
        isEnabled(context) && runCatching { telecom(context).isInCall }.getOrDefault(false)

    /**
     * Answer the ringing call on the watch, with the audio on the watch. Returns false when
     * there's no such call, so the caller answers on the phone instead.
     */
    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION") // Still the way for a non-dialer app to answer
    fun answer(context: Context): Boolean {
        if (!isRinging(context)) return false
        return runCatching {
            telecom(context).acceptRingingCall()
            Log.i(TAG, "Answered on the watch")
            true
        }.getOrElse {
            Log.w(TAG, "Couldn't answer on the watch", it)
            false
        }
    }

    private fun telecom(context: Context): TelecomManager =
        context.getSystemService(TelecomManager::class.java)
}
