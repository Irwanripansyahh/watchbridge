package com.watchbridge

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import com.watchbridge.notification.NotificationChannels

class WatchBridgeApp : Application() {

    companion object {
        const val SERVICE_CHANNEL_ID = "watchbridge_service"
    }

    override fun onCreate() {
        super.onCreate()
        createServiceChannel()
        NotificationChannels.createAll(this)
    }

    private fun createServiceChannel() {
        val channel = NotificationChannel(
            SERVICE_CHANNEL_ID,
            getString(R.string.notification_channel_service),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Keeps BLE connection alive"
            setShowBadge(false)
        }

        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(channel)
    }
}
