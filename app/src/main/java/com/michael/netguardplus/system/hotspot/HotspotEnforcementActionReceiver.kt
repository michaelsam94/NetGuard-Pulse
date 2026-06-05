package com.michael.netguardplus.system.hotspot

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.michael.netguardplus.NetGuardApplication
import com.michael.netguardplus.data.hotspot.HotspotSessionStore
import com.michael.netguardplus.data.repository.HotspotRepositoryImpl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class HotspotEnforcementActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        when (intent?.action) {
            ACTION_STOP_SESSION_ENFORCEMENT -> {
                // Cancel alarms and mark dismissed — works even when app process is dead
                HotspotSessionAlarmScheduler.cancel(context)
                HotspotSessionStore(context).setLimitNotified(true)
                nm.cancel(HotspotRepositoryImpl.SESSION_AUTO_OFF_NOTIF_ID)
                nm.cancel(HotspotSessionLimitReceiver.NOTIF_ID)
                // If the process is alive, also reset in-memory enforcement state
                val repo = (context.applicationContext as? NetGuardApplication)
                    ?.container?.hotspotRepository
                if (repo != null) {
                    CoroutineScope(Dispatchers.IO).launch {
                        try {
                            repo.dismissSessionEnforcement()
                        } catch (e: Exception) {
                            Log.w(TAG, "dismissSessionEnforcement failed", e)
                        }
                    }
                }
                Log.i(TAG, "Session enforcement dismissed by user")
            }

            ACTION_UNBLOCK_CLIENT -> {
                val mac = intent.getStringExtra(EXTRA_MAC) ?: return
                val notifId = intent.getIntExtra(EXTRA_NOTIF_ID, -1)
                if (notifId >= 0) nm.cancel(notifId)
                val repo = (context.applicationContext as? NetGuardApplication)
                    ?.container?.hotspotRepository ?: return
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        repo.setClientBlocked(mac, false)
                        Log.i(TAG, "Unblocked client $mac via notification action")
                    } catch (e: Exception) {
                        Log.w(TAG, "Unblock client $mac failed", e)
                    }
                }
            }
        }
    }

    companion object {
        private const val TAG = "HotspotEnforcementAct"
        const val ACTION_STOP_SESSION_ENFORCEMENT =
            "com.michael.netguardplus.action.STOP_SESSION_ENFORCEMENT"
        const val ACTION_UNBLOCK_CLIENT =
            "com.michael.netguardplus.action.UNBLOCK_HOTSPOT_CLIENT"
        const val EXTRA_MAC = "extra_mac"
        const val EXTRA_NOTIF_ID = "extra_notif_id"

        fun stopSessionIntent(context: Context): android.app.PendingIntent {
            val intent = Intent(context, HotspotEnforcementActionReceiver::class.java).apply {
                action = ACTION_STOP_SESSION_ENFORCEMENT
            }
            return android.app.PendingIntent.getBroadcast(
                context,
                RC_STOP_SESSION,
                intent,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
            )
        }

        fun unblockClientIntent(context: Context, mac: String, notifId: Int): android.app.PendingIntent {
            val intent = Intent(context, HotspotEnforcementActionReceiver::class.java).apply {
                action = ACTION_UNBLOCK_CLIENT
                putExtra(EXTRA_MAC, mac)
                putExtra(EXTRA_NOTIF_ID, notifId)
            }
            return android.app.PendingIntent.getBroadcast(
                context,
                RC_UNBLOCK_BASE + mac.hashCode(),
                intent,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
            )
        }

        private const val RC_STOP_SESSION = 9801
        private const val RC_UNBLOCK_BASE = 9810
    }
}
