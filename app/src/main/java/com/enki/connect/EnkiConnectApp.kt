package com.enki.connect

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager

class EnkiConnectApp : Application() {

    companion object {
        const val CHANNEL_TRACKING = "enki_tracking"
        const val CHANNEL_UPLOADS = "enki_uploads"
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        val manager = getSystemService(NotificationManager::class.java)

        val tracking = NotificationChannel(
            CHANNEL_TRACKING,
            "Location Tracking",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Shows when GPS tracking is active"
            setShowBadge(false)
        }

        val uploads = NotificationChannel(
            CHANNEL_UPLOADS,
            "Data Uploads",
            NotificationManager.IMPORTANCE_MIN
        ).apply {
            description = "Background data sync to Enki server"
            setShowBadge(false)
        }

        manager.createNotificationChannel(tracking)
        manager.createNotificationChannel(uploads)
    }
}
