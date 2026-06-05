package com.michael.netguardplus.system.stats

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.michael.netguardplus.MainActivity
import com.michael.netguardplus.NetGuardApplication
import com.michael.netguardplus.R

class TrafficMonitorService : Service() {

    private lateinit var poller: NetworkStatsPoller

    companion object {
        private const val TAG = "TrafficMonitorService"
        private const val NOTIF_ID = 8830
        private const val CHANNEL_ID = "traffic_monitor_channel"
        const val ACTION_STOP = "com.michael.netguardplus.action.STOP_TRAFFIC_MONITOR"

        @Volatile
        var userStoppedMonitoring = false

        fun shouldRunForPreference(enabled: Boolean): Boolean = enabled

        fun isForegroundMonitoringEnabled(context: Context): Boolean =
            prefs(context).getBoolean(KEY_FOREGROUND_MONITORING_ENABLED, true)

        fun setForegroundMonitoringEnabled(context: Context, enabled: Boolean) {
            prefs(context).edit()
                .putBoolean(KEY_FOREGROUND_MONITORING_ENABLED, enabled)
                .apply()
        }

        fun start(context: Context) {
            if (!shouldRunForPreference(isForegroundMonitoringEnabled(context))) {
                userStoppedMonitoring = true
                AlertMonitorScheduler.cancelWatchdog(context.applicationContext)
                return
            }
            userStoppedMonitoring = false
            val intent = Intent(context, TrafficMonitorService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
                AlertMonitorScheduler.scheduleWatchdog(context.applicationContext)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start traffic monitor service", e)
            }
        }

        fun stop(context: Context) {
            userStoppedMonitoring = true
            setForegroundMonitoringEnabled(context, false)
            AlertMonitorScheduler.cancelWatchdog(context.applicationContext)
            context.stopService(Intent(context, TrafficMonitorService::class.java))
        }

        private fun prefs(context: Context) =
            context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        private const val PREFS_NAME = "traffic_monitor_preferences"
        private const val KEY_FOREGROUND_MONITORING_ENABLED = "foreground_monitoring_enabled"
    }

    override fun onCreate() {
        super.onCreate()
        poller = (application as NetGuardApplication).container.networkStatsPoller
        createNotificationChannel()

        val stopIntent = PendingIntent.getService(
            this,
            4,
            Intent(this, TrafficMonitorService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val openAppIntent = PendingIntent.getActivity(
            this,
            5,
            Intent(this, MainActivity::class.java).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP
                )
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_defense)
            .setContentTitle("NetGuard Pulse Data Monitor")
            .setContentText("Monitoring data limits while app is closed")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(openAppIntent)
            .addAction(0, "Stop", stopIntent)
            .build()
        startForeground(NOTIF_ID, notification)
        poller.startPolling(3000L)
        AlertMonitorScheduler.scheduleWatchdog(applicationContext)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stop()
            return START_NOT_STICKY
        }
        if (!shouldRunForPreference(isForegroundMonitoringEnabled(this))) {
            stop()
            return START_NOT_STICKY
        }
        userStoppedMonitoring = false
        if (::poller.isInitialized) {
            poller.startPolling(3000L)
        }
        AlertMonitorScheduler.scheduleWatchdog(applicationContext)
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        if (userStoppedMonitoring) return
        Log.d(TAG, "Task removed — restarting foreground monitor")
        start(applicationContext)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        poller.stopPolling()
        if (!userStoppedMonitoring) {
            Log.d(TAG, "Service destroyed unexpectedly — scheduling restart")
            AlertMonitorScheduler.scheduleImmediateRestart(applicationContext)
        }
        super.onDestroy()
    }

    private fun stop() {
        userStoppedMonitoring = true
        setForegroundMonitoringEnabled(this, false)
        AlertMonitorScheduler.cancelWatchdog(applicationContext)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Data Limit Monitor",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps data limit monitoring active while the app is closed."
                setShowBadge(false)
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }
}
