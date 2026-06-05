package com.michael.netguardplus.system.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.system.OsConstants
import android.util.Log
import com.michael.netguardplus.MainActivity
import com.michael.netguardplus.NetGuardApplication
import com.michael.netguardplus.R
import com.michael.netguardplus.data.parental.ParentalBlocklistCatalog
import com.michael.netguardplus.domain.model.FamilyDnsProvider
import com.michael.netguardplus.domain.model.ParentalCategory
import com.michael.netguardplus.system.dns.DnsFilterEngine
import com.michael.netguardplus.system.hotspot.HotspotApNetwork
import com.michael.netguardplus.system.hotspot.HotspotCaptivePortalStore
import com.michael.netguardplus.system.hotspot.limit.SessionShapeGate
import com.michael.netguardplus.system.hotspot.limit.HotspotLimiterEnforcementBridge
import com.michael.netguardplus.system.vpn.proxy.ProxyEngine
import kotlinx.coroutines.*
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InterruptedIOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Captures DNS queries on the VPN tunnel and forwards them to the selected family DNS provider.
 * Routes blocked hotspot client IPs (/32) into the tunnel so all their traffic can be dropped.
 */
class LocalVpnService : VpnService() {

    private val serviceJob = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + serviceJob)
    private val shuttingDown = AtomicBoolean(false)
    private val sessionCounter = AtomicInteger(0)
    private val writeLock = Any()
    private val inFlightQueries = AtomicInteger(0)

    private var vpnInterface: ParcelFileDescriptor? = null
    private var keepAliveJob: Job? = null
    private lateinit var dnsForwarder: DnsUpstreamForwarder
    private lateinit var dnsFilterEngine: DnsFilterEngine
    private var providerServers: List<String> = emptyList()
    private var localBlocklist: Set<String> = emptySet()
    private var rebuildJob: Job? = null
    private val pendingRouteRebuild = AtomicBoolean(false)
    private var hotspotEnforcementOnly = false

    private val blockedClientIps = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private var isSessionBlocked = false
    private var speedLimitKbps = 0L
    private var proxyEngine: ProxyEngine? = null
    private var appliedHotspotClientRouteIps: Set<String> = emptySet()
    private var lastRouteRebuildMs = 0L

    private val tetherReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val action = intent?.action ?: return
            if (action == "android.net.wifi.WIFI_AP_STATE_CHANGED" ||
                action == "android.net.conn.TETHER_STATE_CHANGED"
            ) {
                scope.launch {
                    delay(2000)
                    if (isRunning && (activeProvider != null || hotspotEnforcementOnly) &&
                        (blockedClientIps.isNotEmpty() || speedLimitKbps > 0L)
                    ) {
                        Log.i(
                            TAG,
                            "Hotspot state change — rebuilding routes (blocked=$blockedClientIps speedLimit=${speedLimitKbps}Kbps)"
                        )
                        scheduleVpnRouteRebuild()
                    }
                }
            }
        }
    }

    companion object {
        const val ACTION_STOP = "com.michael.netguardplus.action.STOP_VPN"
        const val ACTION_APPLY = "com.michael.netguardplus.action.APPLY_DNS"
        const val ACTION_HOTSPOT_ENFORCE = "com.michael.netguardplus.action.HOTSPOT_ENFORCE"
        const val ACTION_STOP_HOTSPOT_ENFORCE = "com.michael.netguardplus.action.STOP_HOTSPOT_ENFORCE"
        const val ACTION_REBUILD_ROUTES = "com.michael.netguardplus.action.REBUILD_VPN_ROUTES"
        const val EXTRA_PROVIDER = "extra_provider"
        const val EXTRA_BLOCKED_IPS = "extra_blocked_ips"
        const val EXTRA_SESSION_BLOCKED = "extra_session_blocked"
        const val EXTRA_SPEED_LIMIT_KBPS = "extra_speed_limit_kbps"
        const val NOTIF_ID = 8829
        const val CHANNEL_ID = "dns_channel"
        private const val VPN_TUNNEL_IP = "10.255.254.1"
        private const val MAX_IN_FLIGHT = 64
        private const val TAG = "LocalVpnService"
        private const val ROUTE_REBUILD_DEBOUNCE_MS = 350L
        private const val MIN_ROUTE_REBUILD_INTERVAL_MS = 5_000L
        private val HOTSPOT_UPSTREAM_DNS = listOf("8.8.8.8", "8.8.4.4")

        @Volatile
        var isRunning = false
            private set

        @Volatile
        var activeProvider: FamilyDnsProvider? = null
            private set

        /** Routes blocked tether client IPs through the VPN for drop / captive portal when MAC block is unavailable. */
        fun applyHotspotEnforcement(
            context: Context,
            blockedHotspotClientIps: Collection<String>,
            sessionBlocked: Boolean = false
        ) {
            Log.i(TAG, "Hotspot VPN enforcement disabled; DNS VPN remains available")
            return
            val app = context.applicationContext as? NetGuardApplication
            val configFlow = app?.container?.hotspotRepository?.observeSessionConfig()
            val routableBlocked = blockedHotspotClientIps.filter { VpnRoutePlanner.isRoutableIpv4(it) }
            val speedLimit = (configFlow as? kotlinx.coroutines.flow.StateFlow<com.michael.netguardplus.domain.model.HotspotSessionConfig>)?.value?.speedLimitKbps ?: 0L
            if (routableBlocked.isEmpty() && !sessionBlocked && speedLimit <= 0L) {
                requestStopHotspotEnforcement(context)
                return
            }
            val intent = Intent(context, LocalVpnService::class.java).apply {
                action = ACTION_HOTSPOT_ENFORCE
                putStringArrayListExtra(EXTRA_BLOCKED_IPS, ArrayList(routableBlocked))
                putExtra(EXTRA_SESSION_BLOCKED, sessionBlocked)
                putExtra(EXTRA_SPEED_LIMIT_KBPS, speedLimit)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        /** Session bandwidth VPN routes are disabled in this build. */
        fun applySessionBandwidthVpn(context: Context, speedLimitKbps: Long) {
            Log.i(TAG, "Session bandwidth VPN disabled; ignoring speedLimitKbps=$speedLimitKbps")
        }

        fun requestStopSessionBandwidthVpn(context: Context) {
            Log.i(TAG, "Session bandwidth VPN disabled; no route to stop")
        }

        fun requestStopHotspotEnforcement(context: Context) {
            Log.i(TAG, "Hotspot VPN enforcement disabled; no route to stop")
            return
            val intent = Intent(context, LocalVpnService::class.java).apply {
                action = ACTION_STOP_HOTSPOT_ENFORCE
            }
            context.startService(intent)
        }

        fun applyDns(
            context: Context,
            provider: FamilyDnsProvider,
            blockedHotspotClientIps: Collection<String> = emptyList()
        ) {
            if (provider == FamilyDnsProvider.SYSTEM_DEFAULT) {
                requestStop(context)
                return
            }
            val routableBlocked = blockedHotspotClientIps.filter { VpnRoutePlanner.isRoutableIpv4(it) }
            val intent = Intent(context, LocalVpnService::class.java).apply {
                action = ACTION_APPLY
                putExtra(EXTRA_PROVIDER, provider.name)
                if (routableBlocked.isNotEmpty()) {
                    putStringArrayListExtra(EXTRA_BLOCKED_IPS, ArrayList(routableBlocked))
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun requestStop(context: Context) {
            isRunning = false
            activeProvider = null
            val intent = Intent(context, LocalVpnService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }

        fun requestRouteRebuild(context: Context) {
            Log.i(TAG, "Hotspot route rebuild skipped; hotspot VPN enforcement disabled")
            return
            if (!isRunning) return
            val intent = Intent(context, LocalVpnService::class.java).apply {
                action = ACTION_REBUILD_ROUTES
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        internal fun shouldShutdownForHotspotEnforcementStop(
            hotspotEnforcementOnly: Boolean,
            activeProvider: FamilyDnsProvider?,
            hasHotspotEnforcement: Boolean
        ): Boolean {
            if (hotspotEnforcementOnly) return true
            if (activeProvider != null) return false
            return !hasHotspotEnforcement
        }
    }

    override fun onCreate() {
        super.onCreate()
        dnsForwarder = DnsUpstreamForwarder(this, this)
        dnsFilterEngine = (application as NetGuardApplication).container.dnsFilterEngine

        val filter = android.content.IntentFilter().apply {
            addAction("android.net.wifi.WIFI_AP_STATE_CHANGED")
            addAction("android.net.conn.TETHER_STATE_CHANGED")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(tetherReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(tetherReceiver, filter)
        }

        scope.launch {
            val hotspotRepo = (application as NetGuardApplication).container.hotspotRepository
            hotspotRepo.observeSessionConfig().collect { config ->
                if (this@LocalVpnService.speedLimitKbps != config.speedLimitKbps) {
                    Log.i(TAG, "Hotspot speed limit changed: ${config.speedLimitKbps} Kbps (was: ${this@LocalVpnService.speedLimitKbps})")
                    this@LocalVpnService.speedLimitKbps = config.speedLimitKbps
                    if (isRunning) {
                        scheduleVpnRouteRebuild(force = true)
                    }
                }
            }
        }

        scope.launch {
            val dao = (application as NetGuardApplication).container.database.hotspotDao()
            dao.observeAllClients().collect { clients ->
                if (!isRunning || speedLimitKbps <= 0L) return@collect
                val ips = clients.mapNotNull { entity ->
                    entity.ipAddress.takeIf { VpnRoutePlanner.isRoutableIpv4(it) }
                }.toSet()
                if (ips == appliedHotspotClientRouteIps) return@collect
                Log.i(
                    TAG,
                    "Hotspot client route set changed (${appliedHotspotClientRouteIps.size} -> ${ips.size}) — scheduling route rebuild"
                )
                scheduleVpnRouteRebuild(force = false)
            }
        }
    }

    private fun refreshSpeedLimitFromConfig() {
        val app = application as? NetGuardApplication ?: return
        val flow = app.container.hotspotRepository.observeSessionConfig()
        val config = (flow as? kotlinx.coroutines.flow.StateFlow<com.michael.netguardplus.domain.model.HotspotSessionConfig>)?.value
        speedLimitKbps = config?.speedLimitKbps ?: 0L
    }

    private suspend fun loadHotspotClientRouteIps(): Set<String> {
        val dao = (application as NetGuardApplication).container.database.hotspotDao()
        return dao.getAllClients()
            .mapNotNull { entity ->
                entity.ipAddress.takeIf { VpnRoutePlanner.isRoutableIpv4(it) }
            }
            .toSet()
    }

    private fun applySpeedLimitFromIntent(intent: Intent?) {
        if (intent?.hasExtra(EXTRA_SPEED_LIMIT_KBPS) == true) {
            speedLimitKbps = intent.getLongExtra(EXTRA_SPEED_LIMIT_KBPS, speedLimitKbps)
        } else {
            refreshSpeedLimitFromConfig()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action != ACTION_STOP &&
            intent?.action != ACTION_STOP_HOTSPOT_ENFORCE &&
            intent?.action != ACTION_HOTSPOT_ENFORCE
        ) {
            applySpeedLimitFromIntent(intent)
        }

        if (intent?.action == ACTION_STOP) {
            shutdown()
            return START_NOT_STICKY
        }

        if (intent?.action == ACTION_STOP_HOTSPOT_ENFORCE) {
            val hadHotspotEnforcement = blockedClientIps.isNotEmpty() || isSessionBlocked || speedLimitKbps > 0L
            speedLimitKbps = 0L
            if (
                shouldShutdownForHotspotEnforcementStop(
                    hotspotEnforcementOnly = hotspotEnforcementOnly,
                    activeProvider = activeProvider,
                    hasHotspotEnforcement = hadHotspotEnforcement
                )
            ) {
                shutdown()
            } else {
                if (hadHotspotEnforcement) {
                    blockedClientIps.clear()
                    isSessionBlocked = false
                    scheduleVpnRouteRebuild(force = activeProvider != null)
                }
            }
            return START_NOT_STICKY
        }

        if (intent?.action == ACTION_HOTSPOT_ENFORCE) {
            val sessionBlocked = intent.getBooleanExtra(EXTRA_SESSION_BLOCKED, false)
            val blockedFromIntent = intent.getStringArrayListExtra(EXTRA_BLOCKED_IPS)
                ?.filter { VpnRoutePlanner.isRoutableIpv4(it) }
                .orEmpty()
            val blockedSet = blockedFromIntent.toSet()
            val incomingSpeedLimit = if (intent.hasExtra(EXTRA_SPEED_LIMIT_KBPS)) {
                intent.getLongExtra(EXTRA_SPEED_LIMIT_KBPS, speedLimitKbps)
            } else {
                speedLimitKbps
            }
            val unchanged = blockedSet == blockedClientIps.toSet() &&
                sessionBlocked == isSessionBlocked &&
                incomingSpeedLimit == speedLimitKbps

            if (keepAliveJob?.isActive == true && hotspotEnforcementOnly && unchanged) {
                return START_STICKY
            }

            if (isRunning && hotspotEnforcementOnly && unchanged) {
                return START_STICKY
            }

            speedLimitKbps = incomingSpeedLimit
            blockedClientIps.clear()
            blockedClientIps.addAll(blockedFromIntent)
            isSessionBlocked = sessionBlocked

            if (isRunning && activeProvider != null) {
                createNotificationChannel()
                startForeground(NOTIF_ID, buildNotification(activeProvider!!))
                if (!unchanged) {
                    Log.i(TAG, "Family DNS VPN running — rebuilding routes for hotspot enforcement: $blockedFromIntent (sessionBlocked=$sessionBlocked)")
                    scheduleVpnRouteRebuild(force = true)
                }
                return START_STICKY
            }

            shuttingDown.set(false)
            hotspotEnforcementOnly = true
            activeProvider = null
            providerServers = HOTSPOT_UPSTREAM_DNS
            localBlocklist = emptySet()
            createNotificationChannel()
            startForeground(NOTIF_ID, buildHotspotEnforcementNotification())

            val sessionId = sessionCounter.incrementAndGet()
            keepAliveJob?.cancel()
            closeVpnInterface()
            keepAliveJob = scope.launch {
                try {
                    runDnsProxy(null, sessionId)
                } catch (_: CancellationException) {
                    Log.d(TAG, "Hotspot enforcement session $sessionId cancelled")
                } catch (e: Exception) {
                    if (sessionId == sessionCounter.get() && !shuttingDown.get()) {
                        Log.e(TAG, "Hotspot enforcement VPN stopped unexpectedly", e)
                        shutdown()
                    }
                }
            }
            return START_STICKY
        }

        if (intent?.action == ACTION_REBUILD_ROUTES) {
            if (isRunning && (activeProvider != null || hotspotEnforcementOnly)) {
                createNotificationChannel()
                val notification = activeProvider?.let { buildNotification(it) }
                    ?: buildHotspotEnforcementNotification()
                startForeground(NOTIF_ID, notification)
                scheduleVpnRouteRebuild()
            }
            return START_STICKY
        }

        val providerName = intent?.getStringExtra(EXTRA_PROVIDER)
        val provider = providerName?.let { name ->
            runCatching { FamilyDnsProvider.valueOf(name) }.getOrNull()
        }

        if (provider == null || provider == FamilyDnsProvider.SYSTEM_DEFAULT) {
            if (hotspotEnforcementOnly && (blockedClientIps.isNotEmpty() || isSessionBlocked)) {
                return START_STICKY
            }
            shutdown()
            return START_NOT_STICKY
        }

        hotspotEnforcementOnly = false

        val sessionBlocked = intent?.getBooleanExtra(EXTRA_SESSION_BLOCKED, false) ?: false
        val blockedFromIntent = intent?.getStringArrayListExtra(EXTRA_BLOCKED_IPS)
            ?.filter { VpnRoutePlanner.isRoutableIpv4(it) }
            .orEmpty()
        val unchanged = blockedFromIntent.toSet() == blockedClientIps.toSet() && sessionBlocked == isSessionBlocked

        if (blockedFromIntent.isNotEmpty() || sessionBlocked) {
            blockedClientIps.clear()
            blockedClientIps.addAll(blockedFromIntent)
            isSessionBlocked = sessionBlocked
        }

        if (isRunning && activeProvider == provider && vpnInterface != null) {
            createNotificationChannel()
            startForeground(NOTIF_ID, buildNotification(provider))
            if (!unchanged) {
                Log.i(TAG, "VPN already running — rebuilding routes for blocked clients: $blockedFromIntent (sessionBlocked=$sessionBlocked)")
                scheduleVpnRouteRebuild()
            } else {
                Log.d(TAG, "Already running ${provider.label}, skipping restart")
            }
            return START_STICKY
        }

        shuttingDown.set(false)
        providerServers = provider.serverIps
        localBlocklist = buildLocalBlocklist(provider)
        createNotificationChannel()
        startForeground(NOTIF_ID, buildNotification(provider))

        val sessionId = sessionCounter.incrementAndGet()
        keepAliveJob?.cancel()
        closeVpnInterface()
        keepAliveJob = scope.launch {
            try {
                runDnsProxy(provider, sessionId)
            } catch (_: CancellationException) {
                Log.d(TAG, "DNS session $sessionId cancelled (provider switch)")
            } catch (e: Exception) {
                if (sessionId == sessionCounter.get() && !shuttingDown.get()) {
                    Log.e(TAG, "DNS proxy stopped unexpectedly", e)
                    shutdown()
                }
            }
        }

        return START_STICKY
    }

    override fun onRevoke() {
        shutdown()
        super.onRevoke()
    }

    private suspend fun runDnsProxy(provider: FamilyDnsProvider?, sessionId: Int) {
        refreshSpeedLimitFromConfig()
        val hotspotClientIps = if (speedLimitKbps > 0L) loadHotspotClientRouteIps() else emptySet()
        vpnInterface = buildDnsProxyInterface(provider, hotspotClientIps) ?: run {
            Log.e(TAG, "Could not establish VPN for ${provider?.label ?: "hotspot enforcement"}")
            if (sessionId == sessionCounter.get() && !shuttingDown.get()) {
                shutdown()
            }
            return
        }
        if (speedLimitKbps > 0L) {
            appliedHotspotClientRouteIps = hotspotClientIps
        }

        if (sessionId != sessionCounter.get()) return

        isRunning = true
        if (provider != null) {
            activeProvider = provider
            Log.i(
                TAG,
                "DNS proxy active: ${provider.label} → forwarding captured queries to ${provider.serverIps.joinToString()}"
            )
        } else {
            Log.i(
                TAG,
                "Hotspot enforcement VPN active for blocked clients: $blockedClientIps"
            )
        }
        pendingRouteRebuild.set(false)

        if (speedLimitKbps > 0L) {
            val apInfo = HotspotApNetwork.resolve(this)
            val clientSubnet = apInfo?.let { VpnRoutePlanner.tetherSubnetNetwork(it.gatewayIpv4, it.subnetPrefixLength) }
            Log.i(
                TAG,
                "Starting ProxyEngine for user-space NAT and bandwidth limit: $speedLimitKbps Kbps. " +
                    "Subnet: $clientSubnet/${apInfo?.subnetPrefixLength ?: 24} clients=${hotspotClientIps.size}"
            )
            val engine = ProxyEngine(
                vpnService = this,
                tunFd = vpnInterface!!.fileDescriptor,
                speedLimitKbps = speedLimitKbps,
                clientSubnet = clientSubnet,
                subnetPrefixLength = apInfo?.subnetPrefixLength ?: 24,
                gatewayIpv4 = apInfo?.gatewayIpv4,
                knownClientIps = hotspotClientIps
            ) { dnsQuery ->
                resolveDnsQuery(dnsQuery, provider)
            }
            proxyEngine = engine
            engine.start()

            try {
                while (sessionId == sessionCounter.get() && !shuttingDown.get()) {
                    delay(100)
                }
            } finally {
                engine.stop()
                if (proxyEngine === engine) {
                    proxyEngine = null
                }
            }
            return
        }

        val gatewayIps = DnsSystemResolver.collectHotspotHostIps()
        val inputStream = FileInputStream(vpnInterface!!.fileDescriptor)
        val outputStream = FileOutputStream(vpnInterface!!.fileDescriptor)
        val buffer = ByteArray(32767)

        while (sessionId == sessionCounter.get() && !shuttingDown.get()) {
            try {
                val length = inputStream.read(buffer)
                if (length <= 0) {
                    if (shuttingDown.get() || sessionId != sessionCounter.get()) break
                    delay(5)
                    continue
                }

                val packetCopy = buffer.copyOf(length)
                val sourceIp = HotspotPacketGuard.extractIpv4Source(packetCopy, length)
                if (SessionShapeGate.isPaused() && isLikelyHotspotClientIp(sourceIp)) {
                    continue
                }
                val effectiveBlocked = blockedClientIps.toMutableSet()
                if (sourceIp != null &&
                    HotspotLimiterEnforcementBridge.shouldBlockPacket(sourceIp, length)
                ) {
                    effectiveBlocked.add(sourceIp)
                    scope.launch {
                        HotspotLimiterEnforcementBridge.recordBlockedPacket(sourceIp, length)
                    }
                }
                val decision = HotspotPacketGuard.classify(
                    packetCopy,
                    length,
                    effectiveBlocked,
                    gatewayIps,
                    isSessionBlocked
                )
                when (decision.action) {
                    HotspotPacketGuard.Action.DROP -> {
                        val desc = DnsPacketHandler.describeUnparsedPacket(packetCopy, length)
                        Log.d(
                            TAG,
                            "Dropped hotspot traffic from blocked client ${decision.sourceIp} ($length bytes, $desc)"
                        )
                        val reset = Ipv4TcpReset.buildResetIfTcp(packetCopy, length)
                        if (reset != null) {
                            writeToTunnel(sessionId, outputStream, reset)
                        }
                        continue
                    }
                    HotspotPacketGuard.Action.HANDLE_CAPTIVE_HTTP -> {
                        val html = HotspotCaptivePortalStore.pageHtmlFor(decision.sourceIp)
                        val httpResponse = CaptivePortalHttpInjector.buildResponseIfNeeded(
                            packetCopy,
                            length,
                            html
                        )
                        if (httpResponse != null) {
                            Log.i(TAG, "Serving captive portal HTTP to blocked client ${decision.sourceIp}")
                            writeToTunnel(sessionId, outputStream, httpResponse)
                        }
                        continue
                    }
                    HotspotPacketGuard.Action.IGNORE -> {
                        val desc = DnsPacketHandler.describeUnparsedPacket(packetCopy, length)
                        if (!desc.contains("nextHeader=58")) {
                            Log.v(TAG, "Ignored non-DNS packet ($length bytes, $desc)")
                        }
                        continue
                    }
                    HotspotPacketGuard.Action.HANDLE_DNS -> {
                        val query = decision.dnsQuery ?: continue
                        while (inFlightQueries.get() >= MAX_IN_FLIGHT) {
                            delay(5)
                            if (shuttingDown.get() || sessionId != sessionCounter.get()) return
                        }
                        inFlightQueries.incrementAndGet()
                        scope.launch {
                            try {
                                if (sessionId != sessionCounter.get()) return@launch
                                val response = resolveDnsQuery(query, provider)
                                writeToTunnel(sessionId, outputStream, response, query.domain)
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                if (sessionId == sessionCounter.get()) {
                                    Log.w(TAG, "DNS response failed for ${query.domain}: ${e.message}")
                                }
                            } finally {
                                inFlightQueries.decrementAndGet()
                            }
                        }
                    }
                }
            } catch (e: InterruptedIOException) {
                if (sessionId != sessionCounter.get()) break
                delay(5)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (sessionId != sessionCounter.get()) break
                Log.w(TAG, "VPN read error (continuing)", e)
                delay(50)
            }
        }
    }

    private fun writeToTunnel(
        sessionId: Int,
        outputStream: FileOutputStream,
        payload: ByteArray,
        label: String? = null
    ) {
        if (sessionId != sessionCounter.get() || shuttingDown.get()) return
        synchronized(writeLock) {
            if (sessionId != sessionCounter.get() || shuttingDown.get()) return
            try {
                outputStream.write(payload)
            } catch (e: java.io.IOException) {
                if (sessionId != sessionCounter.get()) return
                Log.w(TAG, "VPN tunnel write failed${label?.let { " for $it" }.orEmpty()}: ${e.message}")
            }
        }
    }

    private suspend fun awaitInFlightDrain(timeoutMs: Long = 1_500L) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (inFlightQueries.get() > 0 && System.currentTimeMillis() < deadline) {
            delay(10)
        }
    }

    private fun resolveDnsQuery(
        query: DnsPacketHandler.ParsedDnsQuery,
        provider: FamilyDnsProvider?
    ): ByteArray {
        val clientIp = query.sourceIp
        val portalIp = resolveCaptivePortalIp()
        if (SessionShapeGate.isPaused() && isLikelyHotspotClientIp(clientIp)) {
            Log.v(TAG, "Session bandwidth pause — dropping DNS for hotspot client $clientIp")
            return DnsPacketHandler.buildServFailResponse(query)
        }
        val isBlocked = blockedClientIps.contains(clientIp) ||
                (isSessionBlocked && clientIp != null && clientIp != "127.0.0.1" && !DnsSystemResolver.collectHotspotHostIps().contains(clientIp)) ||
                HotspotLimiterEnforcementBridge.shouldBlockPacket(clientIp, 0)
        if (isBlocked) {
            Log.i(
                TAG,
                "Captive portal DNS to $portalIp for blocked hotspot client: $clientIp (${query.domain})"
            )
            return DnsPacketHandler.buildHotspotLimitSinkholeResponse(query, portalIp)
        }

        if (provider == null || hotspotEnforcementOnly) {
            val upstream = DnsPacketHandler.forwardDnsQuery(query) { payload ->
                dnsForwarder.forwardToFamilyProvider(payload, providerServers)
            }
            if (upstream != null) {
                return DnsPacketHandler.buildResponsePacket(query, upstream)
            }
            return DnsPacketHandler.buildServFailResponse(query)
        }

        val domain = query.domain
        if (domain != null && isBlockedDomain(domain)) {
            Log.d(TAG, "Blocked locally: $domain")
            return DnsPacketHandler.buildBlockedDnsResponse(query)
        }

        val upstream = DnsPacketHandler.forwardDnsQuery(query) { payload ->
            dnsForwarder.forwardToFamilyProvider(payload, providerServers)
        }

        if (upstream != null) {
            Log.d(TAG, "Resolved via family DNS: ${query.domain}")
            return DnsPacketHandler.buildResponsePacket(query, upstream)
        }

        Log.w(TAG, "Family DNS forward failed for ${query.domain ?: "unknown"}")
        return DnsPacketHandler.buildServFailResponse(query)
    }

    private fun buildLocalBlocklist(provider: FamilyDnsProvider): Set<String> {
        if (provider == FamilyDnsProvider.SYSTEM_DEFAULT) return emptySet()
        return ParentalBlocklistCatalog.domainsFor(
            setOf(
                ParentalCategory.ADULT,
                ParentalCategory.GAMBLING,
                ParentalCategory.DRUGS
            )
        )
    }

    private fun isBlockedDomain(domain: String): Boolean {
        if (dnsFilterEngine.isBlocked(domain)) return true
        val clean = domain.trim().lowercase().removeSuffix(".")
        if (matchesBlocklist(clean, localBlocklist)) return true
        return false
    }

    private fun matchesBlocklist(domain: String, blocklist: Set<String>): Boolean {
        if (blocklist.contains(domain)) return true
        var parent = domain
        while (parent.contains('.')) {
            parent = parent.substringAfter('.')
            if (blocklist.contains(parent)) return true
        }
        return false
    }

    private fun scheduleVpnRouteRebuild(force: Boolean = false) {
        if (shuttingDown.get()) return
        if (activeProvider == null && !hotspotEnforcementOnly) {
            pendingRouteRebuild.set(true)
            return
        }
        if (!isRunning && !hotspotEnforcementOnly) {
            pendingRouteRebuild.set(true)
            return
        }
        rebuildJob?.cancel()
        rebuildJob = scope.launch {
            if (!force) {
                val elapsed = System.currentTimeMillis() - lastRouteRebuildMs
                if (elapsed in 0 until MIN_ROUTE_REBUILD_INTERVAL_MS) {
                    delay(MIN_ROUTE_REBUILD_INTERVAL_MS - elapsed)
                }
            }
            delay(ROUTE_REBUILD_DEBOUNCE_MS)
            if (!isRunning || (activeProvider == null && !hotspotEnforcementOnly) || shuttingDown.get()) return@launch
            val provider = activeProvider
            Log.i(TAG, "Rebuilding VPN routes for blocked hotspot clients: $blockedClientIps")
            lastRouteRebuildMs = System.currentTimeMillis()
            val sessionId = sessionCounter.incrementAndGet()
            keepAliveJob?.cancel()
            awaitInFlightDrain()
            closeVpnInterface()
            keepAliveJob = scope.launch {
                try {
                    runDnsProxy(provider, sessionId)
                } catch (_: CancellationException) {
                    Log.d(TAG, "DNS session $sessionId cancelled (route rebuild)")
                } catch (e: Exception) {
                    if (sessionId == sessionCounter.get() && !shuttingDown.get()) {
                        Log.e(TAG, "DNS proxy stopped after route rebuild", e)
                        shutdown()
                    }
                }
            }
        }
    }

    private fun resolveCaptivePortalIp(): String {
        if (blockedClientIps.isNotEmpty() || isSessionBlocked) {
            return VpnRoutePlanner.CAPTIVE_PORTAL_SINKHOLE_IP
        }
        return DnsSystemResolver.collectHotspotHostIps().firstOrNull()
            ?: VpnRoutePlanner.COMMON_HOTSPOT_GATEWAY_DNS.first()
    }

    private suspend fun buildDnsProxyInterface(
        provider: FamilyDnsProvider?,
        hotspotClientIps: Set<String> = emptySet()
    ): ParcelFileDescriptor? {
        return try {
            refreshSpeedLimitFromConfig()
            val servers = provider?.serverIps?.takeIf { it.isNotEmpty() } ?: HOTSPOT_UPSTREAM_DNS

            val hotspotHostIps = DnsSystemResolver.collectHotspotHostIps()
            val apInfo = HotspotApNetwork.resolve(this)
            val tetherSubnet = apInfo?.let {
                VpnRoutePlanner.tetherSubnetNetwork(it.gatewayIpv4, it.subnetPrefixLength)
            }
            val enforcing = blockedClientIps.isNotEmpty() || isSessionBlocked || speedLimitKbps > 0L

            val captureRoutes = VpnRoutePlanner.buildCaptureRoutes(
                linkDnsServers = DnsSystemResolver.collectLinkDnsServers(this),
                providerServers = servers,
                blockedHotspotClientIps = blockedClientIps,
                excludeDnsServers = hotspotHostIps,
                includeHotspotGatewayDns = emptyList(),
                tetherSubnetRoutes = emptyList()
            )

            val builder = Builder()
                .setSession(
                    if (provider != null) "NetGuard Pulse DNS" else "NetGuard Pulse Hotspot Limit"
                )
                .addAddress(VPN_TUNNEL_IP, 32)
                .setMtu(1500)
                .setBlocking(true)

            servers.forEach { builder.addDnsServer(it) }
            if (speedLimitKbps > 0L) {
                builder.addRoute("0.0.0.0", 0)
                if (tetherSubnet != null && apInfo != null) {
                    builder.addRoute(tetherSubnet, apInfo.subnetPrefixLength)
                }
                val gateway = apInfo?.gatewayIpv4
                hotspotClientIps.forEach { clientIp ->
                    if (gateway == null || clientIp != gateway) {
                        builder.addRoute(clientIp, 32)
                    }
                }
                Log.i(
                    TAG,
                    "Bandwidth limit VPN routes: default + tether=$tetherSubnet/${apInfo?.subnetPrefixLength ?: 24} " +
                        "clientRoutes=${hotspotClientIps.size} speed=${speedLimitKbps}Kbps"
                )
            } else {
                captureRoutes.forEach { builder.addRoute(it, 32) }
                if (enforcing && tetherSubnet != null && apInfo != null) {
                    builder.addRoute(tetherSubnet, apInfo.subnetPrefixLength)
                }
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                builder.addDisallowedApplication(packageName)
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                builder.allowFamily(OsConstants.AF_INET)
            }

            val cm = getSystemService(ConnectivityManager::class.java)
            val underlying = cm?.activeNetwork?.let { arrayOf(it) }
                ?: cm?.allNetworks?.filter { network ->
                    val caps = cm.getNetworkCapabilities(network) ?: return@filter false
                    caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                        !caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN)
                }?.toTypedArray()

            if (!underlying.isNullOrEmpty() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
                builder.setUnderlyingNetworks(underlying)
            }

            Log.i(
                TAG,
                "Establishing VPN routes=${captureRoutes.joinToString()} upstream=${servers.joinToString()} blocked=$blockedClientIps"
            )
            builder.establish()
        } catch (e: Exception) {
            Log.e(TAG, "Could not establish VPN interface", e)
            null
        }
    }

    private fun buildHotspotEnforcementNotification(): Notification {
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
            2,
            Intent(this, LocalVpnService::class.java).apply { action = ACTION_STOP },
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

    private fun shutdown() {
        if (!shuttingDown.compareAndSet(false, true)) return

        Log.i(TAG, "Shutting down VPN")
        sessionCounter.incrementAndGet()
        isRunning = false
        activeProvider = null
        hotspotEnforcementOnly = false
        isSessionBlocked = false
        providerServers = emptyList()
        localBlocklist = emptySet()
        appliedHotspotClientRouteIps = emptySet()
        rebuildJob?.cancel()
        keepAliveJob?.cancel()
        proxyEngine?.stop()
        proxyEngine = null
        closeVpnInterface()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
    }

    private fun closeVpnInterface() {
        try {
            vpnInterface?.close()
        } catch (e: Exception) {
            Log.d(TAG, "Error closing VPN interface", e)
        } finally {
            vpnInterface = null
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "NetGuard Pulse DNS",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(provider: FamilyDnsProvider): Notification {
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
            2,
            Intent(this, LocalVpnService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            Notification.Builder(this)
        }

        return builder
            .setContentTitle("DNS: ${provider.label}")
            .setContentText("Filtering via ${provider.serverIps.joinToString(" · ")}")
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

    override fun onDestroy() {
        isRunning = false
        activeProvider = null
        shuttingDown.set(true)
        sessionCounter.incrementAndGet()
        rebuildJob?.cancel()
        keepAliveJob?.cancel()
        proxyEngine?.stop()
        proxyEngine = null
        serviceJob.cancel()
        closeVpnInterface()
        try {
            unregisterReceiver(tetherReceiver)
        } catch (_: Exception) {}
        super.onDestroy()
    }

    private fun isLikelyHotspotClientIp(ip: String?): Boolean {
        if (ip.isNullOrBlank()) return false
        if (ip == "127.0.0.1" || ip == "10.255.254.1") return false
        if (DnsSystemResolver.collectHotspotHostIps().contains(ip)) return false
        val parts = ip.split('.')
        if (parts.size != 4) return false
        val a = parts[0].toIntOrNull() ?: return false
        val b = parts[1].toIntOrNull() ?: return false
        return when (a) {
            10 -> true
            172 -> b in 16..31
            192 -> parts[2].toIntOrNull() == 168
            else -> false
        }
    }
}
