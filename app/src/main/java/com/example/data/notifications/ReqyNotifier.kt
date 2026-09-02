package com.example.data.notifications

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.example.MainActivity

/**
 * Reqy Assistant's own real Android notification channel -- backs the Settings screen's
 * `settings-notifications-row` toggle (see `docs/designs/ai-chat/reqy-assistant-light.html`
 * Screen 2: "must call the real permission/channel API, not just flip UI"). Mirrors the channel
 * creation pattern already used by `RequestrVpnService.startForegroundNotification` so it
 * follows an existing, reviewed convention rather than inventing a new one.
 *
 * Gated by [com.example.data.prefs.ChatSettingsStore.areNotificationsEnabled] (caller-side) and,
 * on API 33+, by the already-declared `POST_NOTIFICATIONS` runtime permission -- this object
 * never requests that permission itself, it only no-ops when it is not granted.
 */
object ReqyNotifier {

    private const val CHANNEL_ID = "reqy_assistant_replies"
    private const val NOTIFICATION_ID = 9421

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Reqy Assistant replies",
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply { description = "Notifies you when Reqy Assistant finishes a reply." }
        )
    }

    private fun hasPostPermission(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ActivityCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
    }

    /** Shows (or silently skips, if permission isn't granted) a "Reqy replied" notification. */
    fun notifyAssistantReply(context: Context, replyText: String) {
        if (!hasPostPermission(context)) return
        ensureChannel(context)

        val openApp = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val preview = replyText.trim().let { if (it.length > 120) it.take(120) + "\u2026" else it }
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle("Reqy Assistant")
            .setContentText(preview.ifBlank { "New reply" })
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(openApp)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
    }
}
