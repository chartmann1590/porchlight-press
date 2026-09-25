package com.charleshartman.porchlightpress.work

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.charleshartman.porchlightpress.MainActivity
import com.charleshartman.porchlightpress.data.weather.WeatherAlert

/**
 * Severe-weather notifications (Phase 7). Channel "Severe weather", high
 * importance. No ads, no tracking — just the alert. When the app language
 * is not English, the notification carries the translated event name
 * alongside the original (e.g. "Advertencia de tornado · Tornado Warning").
 */
object AlertNotifications {
    const val CHANNEL_SEVERE = "severe_weather"

    /**
     * Per-alert request/notification code. A raw [String.hashCode] is a 32-bit
     * signed int and can collide (birthday paradox), which would let one
     * alert's PendingIntent overwrite another's. Masking off the sign bit
     * keeps the full 31-bit range and XORing with a per-app salt further
     * separates alert codes from any hash computed elsewhere in the app.
     */
    private const val SALT = 0x50726F63 // "Proc" — arbitrary, fixed per build
    fun requestCode(alertId: String): Int =
        (alertId.hashCode() and 0x7FFFFFFF) xor SALT

    fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_SEVERE) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_SEVERE,
                "Severe weather",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "Warnings for severe weather near your home location."
            },
        )
    }

    fun canPost(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < 33) return true
        return ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
    }

    /**
     * Post one Warning-level alert. [translatedEvent]/[translatedHeadline]
     * are the ML Kit translations when appLanguage != en (null = English).
     */
    fun showWarning(
        context: Context,
        alert: WeatherAlert,
        translatedEvent: String? = null,
        translatedHeadline: String? = null,
    ) {
        if (!canPost(context)) return
        ensureChannels(context)
        val title = if (translatedEvent != null && translatedEvent != alert.event) {
            "$translatedEvent · ${alert.event}"
        } else {
            alert.event
        }
        val text = translatedHeadline ?: alert.headline.ifBlank { alert.event }
        val openApp = Intent(context, MainActivity::class.java).apply {
            putExtra(MainActivity.EXTRA_OPEN_WEATHER, true)
            putExtra(MainActivity.EXTRA_ALERT_ID, alert.id)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pending = PendingIntent.getActivity(
            context,
            requestCode(alert.id),
            openApp,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_SEVERE)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setContentIntent(pending)
            .build()
        runCatching {
            NotificationManagerCompat.from(context).notify(requestCode(alert.id), notification)
        }
    }
}
