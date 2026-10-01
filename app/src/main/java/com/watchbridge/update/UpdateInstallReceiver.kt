package com.watchbridge.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Receives the system installer's result for an update session. */
class UpdateInstallReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != AppUpdater.ACTION_INSTALL_STATUS) return
        AppUpdater.onInstallStatus(context, intent)
    }
}
