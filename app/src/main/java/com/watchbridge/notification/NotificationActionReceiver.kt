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

        const val ACTION_PERFORM = "com.watchbridge.ACTION_PERFORM"

        /** IntArray of ANCS UIDs, e.g. every message of a conversation that was swiped away. */
        const val EXTRA_NOTIFICATION_UIDS = "notification_uids"

        /**
         * Set this callback to handle notification actions.
         * Called with (notificationUid, actionId).
         */
        var onAction: ((UInt, Byte) -> Unit)? = null
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_PERFORM) return

        val actionId = intent.getByteExtra("action_id", -1)
        val uids = intent.getIntArrayExtra(EXTRA_NOTIFICATION_UIDS)
            ?: intArrayOf(intent.getIntExtra("notification_uid", -1))

        for (uid in uids) {
            if (uid == -1) continue
            Log.d(TAG, "Action received: uid=$uid actionId=$actionId")
            onAction?.invoke(uid.toUInt(), actionId)
        }
    }
}
