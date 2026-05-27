package com.example.system.alert

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.media.RingtoneManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.domain.model.AlertType
import com.example.domain.model.DataAlert
import com.example.domain.repository.AlertRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch

class AlertEngine(
    private val context: Context,
    private val alertRepo: AlertRepository
) {
    private val scope = CoroutineScope(Dispatchers.IO)
    private val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator

    companion object {
        const val ALERT_CHANNEL_ID = "high_data_alerts_channel"
        const val ALERT_NOTIF_ID = 9912
    }

    init {
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                ALERT_CHANNEL_ID,
                "Bandwidth Limit Alerts",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notifies when local limits on Wi-Fi or cellular usage are crossed."
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    /**
     * Examines traffic fluctuations and triggers alerts if policies are breached.
     */
    fun checkTrafficAlerts(uid: Int, packageName: String, totalBytesUsed: Long) {
        scope.launch {
            try {
                val alerts = alertRepo.getActiveAlerts()
                for (alert in alerts) {
                    val applies = alert.uid == -1 || alert.uid == uid
                    if (applies && totalBytesUsed >= alert.thresholdBytes) {
                        triggerAlertNotification(alert, packageName, totalBytesUsed)
                    }
                }
            } catch (e: Exception) {
                Log.e("AlertEngine", "Error checking alerts", e)
            }
        }
    }

    private fun triggerAlertNotification(alert: DataAlert, packageName: String, bytesUsed: Long) {
        val appName = try {
            val appInfo = context.packageManager.getApplicationInfo(packageName, 0)
            context.packageManager.getApplicationLabel(appInfo).toString()
        } catch (e: Exception) {
            packageName.substringAfterLast('.')
        }

        val limitStr = formatBytes(alert.thresholdBytes)
        val usedStr = formatBytes(bytesUsed)

        val notif = NotificationCompat.Builder(context, ALERT_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle("NetGuard Alert: $appName Limit Breached")
            .setContentText("Policy threshold is $limitStr, but app consumed $usedStr.")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(ALERT_NOTIF_ID + alert.id.toInt(), notif)

        // Notification sound & vibration trigger
        when (alert.notificationType) {
            AlertType.VIBRATE -> triggerVibration()
            AlertType.SOUND -> triggerSound()
            AlertType.BOTH -> {
                triggerVibration()
                triggerSound()
            }
        }
    }

    private fun triggerVibration() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(500, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(500)
        }
    }

    private fun triggerSound() {
        try {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            val ringtone = RingtoneManager.getRingtone(context, uri)
            ringtone.play()
        } catch (e: Exception) {
            // Safe fallback
        }
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val exp = (Math.log(bytes.toDouble()) / Math.log(1024.0)).toInt()
        val pre = "KMGTPE"[exp - 1] + ""
        return String.format("%.2f %sB", bytes / Math.pow(1024.0, exp.toDouble()), pre)
    }
}
