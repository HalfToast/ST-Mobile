package dev.halftoast.stmobile

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager

class StMobileApp : Application() {
    companion object {
        const val LOG_TAG = "STMobile"
        const val KEEPALIVE_CHANNEL_ID = "keepalive"
    }

    override fun onCreate() {
        super.onCreate()
        val channel = NotificationChannel(
            KEEPALIVE_CHANNEL_ID, "Keep alive", NotificationManager.IMPORTANCE_LOW,
        ).apply { description = "Keeps ST Mobile loaded in the background" }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }
}
