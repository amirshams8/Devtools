package com.jarvismini.devtools

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager

class DevToolsApp : Application() {

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "AutoBuild Service",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "AutoBuild CI loop status"
            setShowBadge(false)
        }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(channel)
    }

    companion object {
        const val CHANNEL_ID = "autobuild_channel"
    }
}
