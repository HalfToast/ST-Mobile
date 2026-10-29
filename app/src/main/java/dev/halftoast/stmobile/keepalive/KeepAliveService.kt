package dev.halftoast.stmobile.keepalive

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import dev.halftoast.stmobile.MainActivity
import dev.halftoast.stmobile.R
import dev.halftoast.stmobile.StMobileApp

object KeepAliveCopy {
    const val TITLE = "SillyTavern stays loaded"
    const val TEXT = "Tap to open your chat"
    const val COLOR = 0xFFA11B1B.toInt()
}

class KeepAliveService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val open = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pi = PendingIntent.getActivity(
            this, 0, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification: Notification = NotificationCompat.Builder(this, StMobileApp.KEEPALIVE_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_st)
            .setContentTitle(KeepAliveCopy.TITLE)
            .setContentText(KeepAliveCopy.TEXT)
            .setColor(KeepAliveCopy.COLOR)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(pi)
            .build()
        try {
            val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
        } catch (e: Exception) {
            Log.w(StMobileApp.LOG_TAG, "keepalive: startForeground failed", e)
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    companion object {
        private const val NOTIFICATION_ID = 1

        fun start(context: Context) {
            try {
                ContextCompat.startForegroundService(context, Intent(context, KeepAliveService::class.java))
            } catch (e: Exception) {
                Log.w(StMobileApp.LOG_TAG, "keepalive: start failed", e)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, KeepAliveService::class.java))
        }

        fun requestKeepAlivePermissions(activity: ComponentActivity) {
            val askBattery = {
                val pm = activity.getSystemService(Context.POWER_SERVICE) as PowerManager
                if (!pm.isIgnoringBatteryOptimizations(activity.packageName)) {
                    try {
                        activity.startActivity(
                            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                                .setData(Uri.parse("package:${activity.packageName}")),
                        )
                    } catch (e: Exception) {
                        Log.w(StMobileApp.LOG_TAG, "keepalive: battery request failed", e)
                    }
                }
            }
            if (Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(activity, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                // Register must happen before STARTED; the key is unique per call.
                activity.activityResultRegistry.register(
                    "keepalive-notif-${System.nanoTime()}",
                    ActivityResultContracts.RequestPermission(),
                ) { askBattery() }.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                askBattery()
            }
        }

        fun openBatterySettings(activity: ComponentActivity) {
            activity.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(Uri.parse("package:${activity.packageName}")),
            )
        }
    }
}
