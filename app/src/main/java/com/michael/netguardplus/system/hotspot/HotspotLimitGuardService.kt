package com.michael.netguardplus.system.hotspot

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

/**
 * Minimal foreground service that keeps the app process alive while hotspot
 * client or session alerts are configured.
 *
 * When the process is alive the [HotspotRepositoryImpl] monitoring loop
 * continues to run and will fire the alert-reached notification even if the
 * user has navigated away from the app.
 */
class HotspotLimitGuardService : Service() {

    private var stopRequested = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(NOTIF_ID, buildNotification())
        ensureHotspotMonitoring()
        Log.i(TAG, "Hotspot alert guard service started")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopRequested = true
            Log.i(TAG, "Stop requested — stopping guard service")
            stopSelf()
            return START_NOT_STICKY
        }
        stopRequested = false
        ensureHotspotMonitoring()
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        if (!stopRequested) {
            ensureHotspotMonitoring()
            start(applicationContext)
        }
    }

    override fun onDestroy() {
        if (!stopRequested) {
            start(applicationContext)
        }
        super.onDestroy()
        Log.i(TAG, "Hotspot alert guard service destroyed")
    }

    private fun ensureHotspotMonitoring() {
        try {
            (application as? NetGuardApplication)?.container?.hotspotRepository?.startMonitoring()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start hotspot alert monitor", e)
        }
    }

    // ── notification ────────────────────────────────────────────────────────

    private fun buildNotification() = run {
        val tapIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP
                )
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_defense)
            .setContentTitle("Hotspot alert monitoring")
            .setContentText("You will be notified when a hotspot alert threshold is reached.")
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(tapIntent)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Hotspot alert guard",
                    NotificationManager.IMPORTANCE_MIN
                ).apply {
                    description = "Silent background channel that keeps hotspot alert monitoring active."
                    setShowBadge(false)
                }
            )
        }
    }

    // ── companion ────────────────────────────────────────────────────────────

    companion object {
        private const val TAG = "HotspotAlertGuard"
        private const val NOTIF_ID = 22_000
        const val CHANNEL_ID = "hotspot_limit_guard_channel"
        private const val ACTION_STOP = "com.michael.netguardplus.action.STOP_LIMIT_GUARD"

        fun start(context: Context) {
            val intent = Intent(context, HotspotLimitGuardService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
                Log.i(TAG, "start() called")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start hotspot alert guard service", e)
            }
        }

        fun stop(context: Context) {
            try {
                val intent = Intent(context, HotspotLimitGuardService::class.java).apply {
                    action = ACTION_STOP
                }
                context.startService(intent)
                Log.i(TAG, "stop() called")
            } catch (e: Exception) {
                Log.w(TAG, "Could not stop hotspot alert guard service: ${e.message}")
            }
        }
    }
}
