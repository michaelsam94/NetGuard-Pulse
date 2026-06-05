package com.michael.netguardplus.system.hotspot

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.michael.netguardplus.MainActivity
import com.michael.netguardplus.R
import com.michael.netguardplus.data.hotspot.HotspotSessionStore
import com.michael.netguardplus.domain.model.HotspotSessionConfig

/**
 * Fired by AlarmManager when a hotspot session data or time limit is reached.
 * Works even when the app is closed — no root required.
 */
class HotspotSessionLimitReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != HotspotSessionAlarmScheduler.ACTION_SESSION_LIMIT_CHECK) return

        val store = HotspotSessionStore(context)
        val config = store.config.value
        val progress = store.readSessionProgress()

        if (progress.startMs == 0L) return
        if (store.isLimitNotified()) return

        val decision = HotspotSessionEnforcer.evaluate(
            config = config,
            sessionBytesUsed = progress.bytesUsed,
            sessionStartMs = progress.startMs,
            hotspotActive = true,
            nowMs = System.currentTimeMillis()
        )

        if (decision.shouldNotify) {
            store.setLimitNotified(true)
            HotspotSessionAlarmScheduler.cancel(context)
            sendNotification(context, config, decision.reason, progress.bytesUsed)
            Log.i(TAG, "Session limit reached (${decision.reason}) — notification sent")
        } else {
            // Limit not yet hit — reschedule the next data-limit check
            if (config.hasDataLimit) {
                HotspotSessionAlarmScheduler.schedule(context, config, progress.startMs)
            }
        }
    }

    private fun sendNotification(
        context: Context,
        config: HotspotSessionConfig,
        reason: HotspotSessionEnforcer.TriggerReason,
        bytesUsed: Long
    ) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        ensureChannel(context, nm)

        val detail = when (reason) {
            HotspotSessionEnforcer.TriggerReason.DATA ->
                "Data alert reached (${formatBytes(bytesUsed)} / ${formatBytes(config.dataLimitBytes)}). Hotspot and data remain on."
            HotspotSessionEnforcer.TriggerReason.TIME ->
                "Time alert reached (${formatDuration(config.timeLimitMs)}). Hotspot and data remain on."
            HotspotSessionEnforcer.TriggerReason.NONE -> return
        }

        val tapIntent = PendingIntent.getActivity(
            context,
            NOTIF_ID,
            Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notif = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_defense)
            .setContentTitle("Hotspot session alert reached")
            .setContentText(detail)
            .setStyle(NotificationCompat.BigTextStyle().bigText(detail))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setContentIntent(tapIntent)
            .build()

        nm.notify(NOTIF_ID, notif)
    }

    private fun ensureChannel(context: Context, nm: NotificationManager) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_ID,
                "Hotspot Session Alerts",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Alerts when hotspot session data or time thresholds are reached."
            }
            nm.createNotificationChannel(ch)
        }
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes <= 0L || bytes == Long.MAX_VALUE) return "—"
        val exp = (Math.log(bytes.toDouble()) / Math.log(1024.0)).toInt().coerceIn(1, 6)
        val pre = "KMGTPE"[exp - 1]
        return String.format("%.1f %sB", bytes / Math.pow(1024.0, exp.toDouble()), pre)
    }

    private fun formatDuration(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        val h = s / 3600
        val m = (s % 3600) / 60
        return when {
            h > 0 -> "${h}h ${m}m"
            m > 0 -> "${m}m"
            else -> "${s}s"
        }
    }

    companion object {
        private const val TAG = "HotspotSessionLimit"
        private const val CHANNEL_ID = "hotspot_session_limit_channel"
        const val NOTIF_ID = 22000
    }
}
