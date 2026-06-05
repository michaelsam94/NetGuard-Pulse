package com.michael.netguardplus.system.stats

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Restarts background alert monitoring after boot, app update, alarm watchdog,
 * or when the OS kills the monitor process.
 */
class AlertMonitorReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            ACTION_QUICKBOOT_POWERON,
            AlertMonitorScheduler.ACTION_RESTART_MONITOR -> {
                if (TrafficMonitorService.userStoppedMonitoring ||
                    !TrafficMonitorService.isForegroundMonitoringEnabled(context)
                ) {
                    AlertMonitorScheduler.cancelWatchdog(context.applicationContext)
                    return
                }
                try {
                    TrafficMonitorService.start(context.applicationContext)
                    AlertMonitorScheduler.scheduleWatchdog(context.applicationContext)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to restart alert monitor (${intent.action})", e)
                }
            }
        }
    }

    companion object {
        private const val TAG = "AlertMonitorReceiver"
        private const val ACTION_QUICKBOOT_POWERON = "android.intent.action.QUICKBOOT_POWERON"
    }
}
