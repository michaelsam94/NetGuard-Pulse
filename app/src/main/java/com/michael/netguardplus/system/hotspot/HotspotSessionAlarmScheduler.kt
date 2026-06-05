package com.michael.netguardplus.system.hotspot

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.util.Log
import com.michael.netguardplus.domain.model.HotspotSessionConfig

object HotspotSessionAlarmScheduler {

    const val ACTION_SESSION_LIMIT_CHECK =
        "com.michael.netguardplus.action.HOTSPOT_SESSION_LIMIT_CHECK"

    private const val TAG = "HotspotSessionAlarm"
    private const val RC_DATA = 9901
    private const val RC_TIME = 9902
    private const val DATA_CHECK_INTERVAL_MS = 30_000L

    fun schedule(context: Context, config: HotspotSessionConfig, sessionStartMs: Long) {
        if (!config.autoOffEnabled || !config.hasAnyLimit || sessionStartMs == 0L) return
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        if (config.hasDataLimit) {
            val pending = makePending(context, RC_DATA)
            am.setAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + DATA_CHECK_INTERVAL_MS,
                pending
            )
            Log.d(TAG, "Scheduled data-limit check in ${DATA_CHECK_INTERVAL_MS / 1000}s")
        }
        if (config.hasTimeLimit) {
            val triggerAtMs = sessionStartMs + config.timeLimitMs
            val pending = makePending(context, RC_TIME)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMs, pending)
            } else {
                am.setExact(AlarmManager.RTC_WAKEUP, triggerAtMs, pending)
            }
            val inMs = triggerAtMs - System.currentTimeMillis()
            Log.d(TAG, "Scheduled time-limit alarm in ${inMs / 1000}s")
        }
    }

    fun cancel(context: Context) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        listOf(RC_DATA, RC_TIME).forEach { rc ->
            try {
                am.cancel(makePending(context, rc))
            } catch (_: Exception) {}
        }
        Log.d(TAG, "Cancelled session limit alarms")
    }

    private fun makePending(context: Context, requestCode: Int): PendingIntent {
        val intent = Intent(context, HotspotSessionLimitReceiver::class.java).apply {
            action = ACTION_SESSION_LIMIT_CHECK
        }
        return PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
