package com.michael.netguardplus.system.hotspot

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.michael.netguardplus.MainActivity
import com.michael.netguardplus.NetGuardApplication
import com.michael.netguardplus.R
import com.michael.netguardplus.data.local.db.entity.HotspotClientEntity
import com.michael.netguardplus.system.vpn.DnsSystemResolver
import com.michael.netguardplus.system.vpn.LocalVpnService
import com.michael.netguardplus.system.hotspot.limit.HotspotLimitPolicy
import com.michael.netguardplus.system.vpn.VpnRoutePlanner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * Runs a local DNS + HTTP captive portal on the hotspot gateway for manually blocked clients.
 * Works without root: clients use the gateway as DNS (via DHCP); blocked clients are redirected
 * to the local blocked-device page hosted on the phone.
 */
class HotspotCaptivePortalService : Service() {

    private val serviceJob = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + serviceJob)

    private var dnsServer: HotspotDnsServer? = null
    private var httpServer: HotspotCaptiveHttpServer? = null
    private val blockedClientIps = ConcurrentHashMap.newKeySet<String>()
    private val blockedClientLabels = ConcurrentHashMap<String, String>()
    private var gatewayIp: String = DEFAULT_GATEWAY_IP

    private var observeJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        observeJob = scope.launch {
            val dao = (application as NetGuardApplication).container.database.hotspotDao()
            dao.observeAllClients().collectLatest { clients ->
                applyBlockedClients(clients.filter { isEnforceableBlock(it) })
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                shutdown()
                return START_NOT_STICKY
            }
            ACTION_SYNC, null -> {
                startForeground(NOTIF_ID, buildNotification())
                return START_STICKY
            }
            else -> return START_NOT_STICKY
        }
    }

    private fun isEnforceableBlock(client: HotspotClientEntity): Boolean {
        if (!VpnRoutePlanner.isRoutableIpv4(client.ipAddress)) return false
        return HotspotLimitPolicy.isEffectivelyBlocked(
            manualBlock = client.isBlocked,
            rxBytes = client.rxBytes,
            txBytes = client.txBytes,
            limitBytes = client.limitBytes
        )
    }

    private fun applyBlockedClients(blocked: List<HotspotClientEntity>) {
        blockedClientIps.clear()
        blockedClientLabels.clear()
        for (client in blocked) {
            blockedClientIps.add(client.ipAddress)
            blockedClientLabels[client.ipAddress] = client.deviceName
        }

        if (blockedClientIps.isEmpty()) {
            shutdownServers()
            stopForegroundAndSelf()
            return
        }

        gatewayIp = resolveGatewayIp()
        Log.i(TAG, "Captive portal active for ${blockedClientIps.size} client(s) via $gatewayIp")
        startServers()
        requestVpnGatewayCapture()
    }

    private fun resolveGatewayIp(): String {
        return DnsSystemResolver.collectHotspotHostIps().firstOrNull() ?: DEFAULT_GATEWAY_IP
    }

    private fun startServers() {
        dnsServer?.stop()
        httpServer?.stop()

        val dns = HotspotDnsServer(
            scope = scope,
            bindIp = gatewayIp,
            portalIp = gatewayIp,
            blockedClientIps = { blockedClientIps.toSet() }
        )
        val http = HotspotCaptiveHttpServer(
            scope = scope,
            bindIp = gatewayIp,
            pageHtml = {
                val label = blockedClientLabels.values.firstOrNull()
                HotspotCaptivePortalPage.html(label)
            }
        )
        dns.start()
        http.start()
        dnsServer = dns
        httpServer = http
    }

    private fun shutdownServers() {
        dnsServer?.stop()
        httpServer?.stop()
        dnsServer = null
        httpServer = null
    }

    private fun requestVpnGatewayCapture() {
        if (!LocalVpnService.isRunning) return
        LocalVpnService.requestRouteRebuild(this)
    }

    private fun shutdown() {
        shutdownServers()
        blockedClientIps.clear()
        blockedClientLabels.clear()
        stopForegroundAndSelf()
    }

    private fun stopForegroundAndSelf() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
    }

    override fun onDestroy() {
        observeJob?.cancel()
        shutdownServers()
        scope.cancel()
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Hotspot limit enforcement",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            )
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopIntent = PendingIntent.getService(
            this,
            3,
            Intent(this, HotspotCaptivePortalService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val count = blockedClientIps.size.coerceAtLeast(1)
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            Notification.Builder(this)
        }
        return builder
            .setContentTitle("Hotspot limit enforcement")
            .setContentText("$count device(s) redirected to limit page")
            .setSmallIcon(R.drawable.ic_notification_defense)
            .setContentIntent(pendingIntent)
            .addAction(
                Notification.Action.Builder(
                    null,
                    "Stop",
                    stopIntent
                ).build()
            )
            .build()
    }

    companion object {
        private const val TAG = "HotspotCaptivePortal"
        private const val ACTION_SYNC = "com.michael.netguardplus.action.SYNC_HOTSPOT_CAPTIVE"
        private const val ACTION_STOP = "com.michael.netguardplus.action.STOP_HOTSPOT_CAPTIVE"
        private const val NOTIF_ID = 8830
        private const val CHANNEL_ID = "hotspot_captive_channel"
        private const val DEFAULT_GATEWAY_IP = "192.168.43.1"

        fun requestSync(context: Context) {
            val intent = Intent(context, HotspotCaptivePortalService::class.java).apply {
                action = ACTION_SYNC
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun requestStop(context: Context) {
            val intent = Intent(context, HotspotCaptivePortalService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }
}
