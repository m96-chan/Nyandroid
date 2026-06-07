package dev.nyandroid.terminal.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import dev.nyandroid.terminal.R

/**
 * Handles terminal notifications triggered by OSC 99 (kitty) and OSC 777.
 */
object TerminalNotification {

    private const val CHANNEL_ID = "nyandroid_terminal"
    private const val CHANNEL_NAME = "Terminal Notifications"
    private var nextNotifId = 1000

    private var channelCreated = false

    private fun ensureChannel(context: Context) {
        if (channelCreated) return
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_DEFAULT,
        )
        nm.createNotificationChannel(channel)
        channelCreated = true
    }

    /**
     * Shows a notification with the given title and body.
     */
    fun show(context: Context, title: String, body: String) {
        ensureChannel(context)
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(title)
            .setContentText(body)
            .setAutoCancel(true)
            .build()
        nm.notify(nextNotifId++, notification)
    }

    /**
     * Parses OSC 99 payload (kitty notification).
     * Format: key=value;key=value;... body
     * Supported keys: t (title), p (urgency)
     */
    fun parseOsc99(payload: String): Pair<String, String>? {
        // Simple parsing: everything after last semicolon-separated param is the body.
        val parts = payload.split(';')
        var title = "Terminal"
        val body = parts.lastOrNull() ?: return null
        for (part in parts.dropLast(1)) {
            val kv = part.split('=', limit = 2)
            if (kv.size == 2 && kv[0] == "t") title = kv[1]
        }
        return title to body
    }

    /**
     * Parses OSC 777 payload (rxvt-unicode notification).
     * Format: notify;title;body
     */
    fun parseOsc777(payload: String): Pair<String, String>? {
        val parts = payload.split(';', limit = 3)
        if (parts.size < 3 || parts[0] != "notify") return null
        return parts[1] to parts[2]
    }
}
