package com.example.interception

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.data.interception.InterceptionStateRepository
import com.example.data.interception.WatcherStatus

class CaptureForegroundService : Service() {

    companion object {
        const val CHANNEL_ID = "requestr_capture"
        const val NOTIFICATION_ID = 1001
        private const val TAG = "CaptureFg"
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val state = InterceptionStateRepository.state.value
        val status = when (state.watcherStatus) {
            WatcherStatus.HEALTHY -> "● Capturing"
            WatcherStatus.DEGRADED -> "◐ Degraded"
            WatcherStatus.ERROR -> "○ Silent"
            WatcherStatus.IDLE -> "● Active"
        }

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("requestr Interceptr")
            .setContentText("$status · ${state.methodLabel} · ${state.packetCount} pkts")
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(
                PendingIntent.getActivity(this, 0,
                    Intent(this, MainActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        putExtra("open_tab", "intercept")
                    },
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            )
            .build()

        startForeground(NOTIFICATION_ID, notification)
        Log.i(TAG, "Foreground service started")
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        Log.i(TAG, "Foreground service stopped")
        super.onDestroy()
    }

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID, "Capture Status",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Shows active capture status"
            setShowBadge(false)
        }
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .createNotificationChannel(channel)
    }
}
