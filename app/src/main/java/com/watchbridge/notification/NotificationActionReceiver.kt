package com.watchbridge.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Receives notification action broadcasts (accept/reject/dismiss)
 * and forwards them to the ANCS action callback.
 */
class NotificationActionReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "NotifActionReceiver"

        /**
         * Set this callback to handle notification actions.
         * Called with (notificationUid, actionId).
         */
        var onAction: ((UInt, Byte) -> Unit)? = null
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != "com.watchbridge.ACTION_PERFORM") return

        val uid = intent.getIntExtra("notification_uid", -1)
        val actionId = intent.getByteExtra("action_id", -1)

        if (uid == -1) return

        Log.d(TAG, "Action received: uid=$uid actionId=$actionId")
        onAction?.invoke(uid.toUInt(), actionId)
    }
}
