package com.michael.netguardplus.system.alert

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.michael.netguardplus.MainActivity
import com.michael.netguardplus.R
import com.michael.netguardplus.domain.model.AlertNetworkType
import com.michael.netguardplus.domain.model.AlertType
import com.michael.netguardplus.domain.model.DataAlert
import com.michael.netguardplus.domain.repository.AlertRepository
import com.michael.netguardplus.domain.repository.TrafficRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class AlertEngine(
    private val context: Context,
    private val alertRepo: AlertRepository,
    private val trafficRepo: TrafficRepository
) {
    private val scope = CoroutineScope(Dispatchers.IO)
    private val notificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    companion object {
        const val ALERT_CHANNEL_ID = "high_data_alerts_channel"
        private const val ALERT_NOTIF_ID_BASE = 9912
        private const val TAG = "AlertEngine"
    }

    init {
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val soundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            val channel = NotificationChannel(
                ALERT_CHANNEL_ID,
                "Data Usage Alerts",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notifies when Wi-Fi or cellular usage alert thresholds are crossed."
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 500, 200, 500)
                setSound(
                    soundUri,
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                setBypassDnd(true)
                lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    fun checkAllActiveAlerts() {
        scope.launch {
            try {
                val alerts = alertRepo.getActiveAlerts()
                if (alerts.isEmpty()) return@launch

                for (alert in alerts) {
                    if (alert.hasFired) continue
                    val bytesUsed = when {
                        alert.uid == -1 -> getOverallTraffic(alert)
                        else -> getTrafficForApp(alert, alert.uid)
                    }
                    if (bytesUsed >= alert.thresholdBytes) {
                        alertRepo.markAlertFired(alert.id, System.currentTimeMillis())
                        val targetName = alert.packageName ?: "All Transmissions"
                        triggerAlertNotification(alert, targetName, bytesUsed)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error checking active alerts", e)
            }
        }
    }

    private suspend fun getTrafficForApp(alert: DataAlert, uid: Int): Long {
        return when (alert.networkType) {
            AlertNetworkType.MOBILE -> trafficRepo.getMobileTrafficForAppToday(uid)
            AlertNetworkType.WIFI -> trafficRepo.getWifiTrafficForAppToday(uid)
        }
    }

    private suspend fun getOverallTraffic(alert: DataAlert): Long {
        return when (alert.networkType) {
            AlertNetworkType.MOBILE -> trafficRepo.getMobileTrafficToday()
            AlertNetworkType.WIFI -> trafficRepo.getWifiTrafficToday()
        }
    }

    private fun triggerAlertNotification(alert: DataAlert, packageName: String, bytesUsed: Long) {
        val appName = try {
            val appInfo = context.packageManager.getApplicationInfo(packageName, 0)
            context.packageManager.getApplicationLabel(appInfo).toString()
        } catch (_: Exception) {
            if (packageName == "All Transmissions") "All Transmissions" else packageName.substringAfterLast('.')
        }

        val networkLabel = when (alert.networkType) {
            AlertNetworkType.MOBILE -> "Mobile Data"
            AlertNetworkType.WIFI -> "Wi-Fi"
        }
        val limitStr = formatBytes(alert.thresholdBytes)
        val usedStr = formatBytes(bytesUsed)

        val openAppIntent = PendingIntent.getActivity(
            context,
            alert.id.toInt(),
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(context, ALERT_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_defense)
            .setContentTitle("NetGuard Alert: $appName $networkLabel Limit Breached")
            .setContentText("Daily $networkLabel limit is $limitStr, but consumed $usedStr today.")
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    "Daily $networkLabel limit is $limitStr, but consumed $usedStr today."
                )
            )
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(openAppIntent)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)

        when (alert.notificationType) {
            AlertType.VIBRATE -> {
                builder.setVibrate(longArrayOf(0, 500, 200, 500))
                builder.setSilent(true)
            }
            AlertType.SOUND -> {
                builder.setDefaults(NotificationCompat.DEFAULT_SOUND)
            }
            AlertType.BOTH -> {
                builder.setDefaults(NotificationCompat.DEFAULT_ALL)
                builder.setVibrate(longArrayOf(0, 500, 200, 500))
            }
        }

        notificationManager.notify(ALERT_NOTIF_ID_BASE + alert.id.toInt(), builder.build())
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val exp = (Math.log(bytes.toDouble()) / Math.log(1024.0)).toInt()
        val pre = "KMGTPE"[exp - 1] + ""
        return String.format("%.2f %sB", bytes / Math.pow(1024.0, exp.toDouble()), pre)
    }
}
