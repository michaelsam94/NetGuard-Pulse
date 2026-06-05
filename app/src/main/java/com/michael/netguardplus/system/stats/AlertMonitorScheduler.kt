package com.michael.netguardplus.system.stats

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log

object AlertMonitorScheduler {
    private const val TAG = "AlertMonitorScheduler"
    private const val REQUEST_CODE = 8841
    const val ACTION_RESTART_MONITOR = "com.michael.netguardplus.action.RESTART_ALERT_MONITOR"
    private const val WATCHDOG_INTERVAL_MS = 15 * 60 * 1000L

    fun scheduleWatchdog(context: Context) {
        scheduleRestart(context, WATCHDOG_INTERVAL_MS)
    }

    fun scheduleImmediateRestart(context: Context) {
        scheduleRestart(context, 3_000L)
    }

    private fun scheduleRestart(context: Context, delayMs: Long) {
        try {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val intent = Intent(context, AlertMonitorReceiver::class.java).apply {
                action = ACTION_RESTART_MONITOR
            }
            val pending = PendingIntent.getBroadcast(
                context,
                REQUEST_CODE,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            alarmManager.setAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + delayMs,
                pending
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to schedule alert monitor watchdog", e)
        }
    }

    fun cancelWatchdog(context: Context) {
        try {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val intent = Intent(context, AlertMonitorReceiver::class.java).apply {
                action = ACTION_RESTART_MONITOR
            }
            val pending = PendingIntent.getBroadcast(
                context,
                REQUEST_CODE,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            alarmManager.cancel(pending)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to cancel alert monitor watchdog", e)
        }
    }
}
