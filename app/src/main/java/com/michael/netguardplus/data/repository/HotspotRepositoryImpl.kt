package com.michael.netguardplus.data.repository

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.app.PendingIntent
import android.os.Build
import android.net.NetworkCapabilities
import android.util.Log
import androidx.core.app.NotificationCompat
import com.michael.netguardplus.MainActivity
import com.michael.netguardplus.R
import com.michael.netguardplus.data.hotspot.HotspotSessionStore
import com.michael.netguardplus.data.local.db.dao.HotspotDao
import com.michael.netguardplus.data.local.db.entity.HotspotClientEntity
import com.michael.netguardplus.data.parental.ParentalControlStore
import com.michael.netguardplus.domain.model.HotspotClient
import com.michael.netguardplus.domain.model.HotspotSessionConfig
import com.michael.netguardplus.domain.model.HotspotSessionStatus
import com.michael.netguardplus.domain.repository.HotspotRepository
import com.michael.netguardplus.domain.repository.DnsBlockingRepository
import com.michael.netguardplus.domain.repository.UsageHistoryRepository
import com.michael.netguardplus.system.hotspot.HotspotCaptivePortalService
import com.michael.netguardplus.system.hotspot.HotspotSessionEnforcer
import com.michael.netguardplus.system.hotspot.limit.HotspotLimitPolicy
import com.michael.netguardplus.system.hotspot.HotspotClientMerger
import com.michael.netguardplus.system.hotspot.HotspotController
import com.michael.netguardplus.system.hotspot.HotspotEnforcementActionReceiver
import com.michael.netguardplus.system.hotspot.HotspotLimitGuardService
import com.michael.netguardplus.system.hotspot.HotspotSessionAlarmScheduler
import com.michael.netguardplus.system.hotspot.LocalNetworkClientScanner
import com.michael.netguardplus.system.stats.HotspotTrafficTracker
import com.michael.netguardplus.system.stats.TetheringMonitor
import com.michael.netguardplus.system.hotspot.MacAddressResolver
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.resume
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

class HotspotRepositoryImpl(
    private val context: Context,
    private val hotspotDao: HotspotDao,
    private val usageHistoryRepository: UsageHistoryRepository,
    private val parentalControlStore: ParentalControlStore,
    private val dnsBlockingRepository: DnsBlockingRepository
) : HotspotRepository {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val monitorMutex = Mutex()
    private var job: Job? = null
    private val tetheringMonitor = TetheringMonitor()
    private val hotspotTrafficTracker = HotspotTrafficTracker(context)
    private val localNetworkScanner = LocalNetworkClientScanner(context)
    private val macResolver = MacAddressResolver(
        context = context,
        shellMacResolver = { ip -> localNetworkScanner.resolveMacByIp(ip) }
    )
    private val hotspotController = HotspotController(context, Executors.newSingleThreadExecutor())
    private val sessionStore = HotspotSessionStore(context)
    private val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private val _sessionStatus = MutableStateFlow(HotspotSessionStatus())

    private val lastSnapshotMap = ConcurrentHashMap<String, LastSpeedData>()
    private val currentSpeeds = ConcurrentHashMap<String, SpeedData>()
    private var hotspotSessionStartMs = 0L
    private var lastHotspotSessionRx = 0L
    private var lastHotspotSessionTx = 0L
    private var hotspotHistoryBaselineCaptured = false
    private val enforcementWarningSent = ConcurrentHashMap.newKeySet<String>()
    /** IP → timestamp; prevents repeated blockClient / log spam in the monitor loop. */
    private val enforcedClients = ConcurrentHashMap<String, Long>()
    private val knownClientIps = ConcurrentHashMap.newKeySet<String>()
    private var lastEnforcementNotifyMs = 0L
    private var lastUplinkTransport: Int? = null
    private var sessionAlertTriggered = false
    private var lastMonitorStepMs = 0L
    private val activeClientMacs = MutableStateFlow<Set<String>>(emptySet())

    private data class LastSpeedData(val rx: Long, val tx: Long, val timestampMs: Long)
    private data class SpeedData(val rxSpeed: Long, val txSpeed: Long)

    companion object {
        const val HOTSPOT_CHANNEL_ID = "hotspot_limit_alerts_channel"
        const val NOTIF_BASE_ID = 20000
        const val SESSION_AUTO_OFF_NOTIF_ID = 21000
        private const val TAG = "HotspotRepository"
        private const val STALE_CLIENT_MS = 2 * 60 * 1000L
        private const val ACTIVE_SESSION_STALE_CLIENT_MS = 30_000L
        private const val ENFORCEMENT_NOTIFY_DEBOUNCE_MS = 20_000L
        private const val HOTSPOT_POLL_ACTIVE_MS = 1_000L
        private const val HOTSPOT_POLL_IDLE_MS = 2_000L
        private const val SESSION_AGGREGATE_MAC = "HOTSPOT-SESSION-AGGREGATE"
        // Grace window: keep a device visible for 10 s after its last seen timestamp
        // so a single missed poll cycle doesn't make it flicker out of the UI.
        private const val CONNECTED_GRACE_MS = 10_000L
        /** Number of rapid scans fired when SoftApCallback detects a new client. */
        private const val BURST_SCAN_COUNT = 5
        /** Gap between burst scans (ms). 5 × 200 ms = 1 s total burst window. */
        private const val BURST_SCAN_INTERVAL_MS = 200L

        internal fun shouldDisplayClient(
            hotspotEnabled: Boolean,
            isConnected: Boolean,
            isBlocked: Boolean,
            sessionStartMs: Long,
            lastSeenMs: Long,
            nowMs: Long = System.currentTimeMillis()
        ): Boolean {
            if (!hotspotEnabled) return false
            val recentlySeen = lastSeenMs > 0L && (nowMs - lastSeenMs) < CONNECTED_GRACE_MS
            if (isConnected || isBlocked || recentlySeen) {
                if (!isConnected && recentlySeen && !isBlocked) {
                    Log.d(TAG, "Client lastSeen=${nowMs - lastSeenMs}ms ago — holding visible during grace window")
                }
                return true
            }
            return false
        }

        internal fun shouldIncludeClientEntity(macAddress: String): Boolean {
            return macAddress != SESSION_AGGREGATE_MAC
        }
    }

    init {
        createNotificationChannel()
        localNetworkScanner.start()
        hotspotController.startObserving()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                HOTSPOT_CHANNEL_ID,
                "Hotspot Limit Warnings",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notifies when connected hotspot devices exceed their data usage limit."
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    override fun observeAllClients(): Flow<List<HotspotClient>> {
        return combine(
            hotspotDao.observeAllClients(),
            observeHotspotEnabled(),
            observeSessionStatus(),
            activeClientMacs
        ) { entities, enabled, sessionStatus, activeMacs ->
            if (!enabled) {
                emptyList()
            } else {
                entities.mapNotNull { entity ->
                    if (!shouldIncludeClientEntity(entity.macAddress)) {
                        return@mapNotNull null
                    }
                    val isBlocked = HotspotLimitPolicy.isEffectivelyBlocked(
                        manualBlock = entity.isBlocked,
                        rxBytes = entity.rxBytes,
                        txBytes = entity.txBytes,
                        limitBytes = entity.limitBytes
                    )
                    val isConnected = entity.macAddress in activeMacs
                    if (shouldDisplayClient(enabled, isConnected, isBlocked, sessionStatus.sessionStartMs, entity.lastSeenMs)) {
                        val speed = currentSpeeds[entity.macAddress] ?: SpeedData(0L, 0L)
                        HotspotClient(
                            macAddress = entity.macAddress,
                            ipAddress = entity.ipAddress,
                            deviceName = entity.deviceName,
                            rxBytes = entity.rxBytes,
                            txBytes = entity.txBytes,
                            rxSpeed = speed.rxSpeed,
                            txSpeed = speed.txSpeed,
                            limitBytes = entity.limitBytes,
                            isBlocked = isBlocked,
                            isConnected = isConnected,
                            lastSeenMs = entity.lastSeenMs
                        )
                    } else {
                        null
                    }
                }
            }
        }
    }

    override fun observeHotspotEnabled(): Flow<Boolean> = combine(
        hotspotController.isHotspotEnabled,
        localNetworkScanner.hotspotActive
    ) { controller, local -> controller || local }

    override fun observeSessionConfig(): Flow<HotspotSessionConfig> = sessionStore.config

    override fun observeSessionStatus(): Flow<HotspotSessionStatus> = _sessionStatus.asStateFlow()

    override suspend fun updateSessionConfig(config: HotspotSessionConfig) {
        sessionStore.updateConfig(config.copy(speedLimitKbps = 0L))
        val status = _sessionStatus.value
        val decision = HotspotSessionEnforcer.evaluate(
            config = config,
            sessionBytesUsed = status.sessionBytesUsed,
            sessionStartMs = hotspotSessionStartMs,
            hotspotActive = status.isHotspotActive,
            nowMs = System.currentTimeMillis()
        )
        if (decision.shouldNotify && !sessionStore.isLimitNotified()) {
            sessionAlertTriggered = true
            sessionStore.setLimitNotified(true)
            HotspotSessionAlarmScheduler.cancel(context)
            notifySessionAlert(decision.reason, status.sessionBytesUsed)
        } else if (!decision.shouldNotify) {
            sessionAlertTriggered = false
            sessionStore.setLimitNotified(false)
            dnsBlockingRepository.setSessionBlocked(false)
            if (hotspotSessionStartMs > 0L && status.isHotspotActive) {
                HotspotSessionAlarmScheduler.cancel(context)
                HotspotSessionAlarmScheduler.schedule(context, config, hotspotSessionStartMs)
            }
        }
        refreshHotspotGuardService()
    }

    override suspend fun setHotspotEnabled(enabled: Boolean): Result<Unit> = suspendCancellableCoroutine { cont ->
        hotspotController.setHotspotEnabled(
            enabled = enabled,
            onStarted = {
                if (cont.isActive) cont.resume(Result.success(Unit)) {}
            },
            onFailed = { message ->
                if (cont.isActive) cont.resume(Result.failure(IllegalStateException(message))) {}
            }
        )
    }

    override suspend fun setClientLimit(mac: String, limitBytes: Long?) {
        hotspotDao.setLimit(mac, limitBytes)
        // Keep the process alive so the monitoring loop can fire limit-reached
        // notifications even when the user has navigated away from the app.
        refreshHotspotGuardService()
    }

    private suspend fun refreshHotspotGuardService() {
        val anyClientLimitActive = hotspotDao.getAllClients().any { it.limitBytes != null }
        val anySessionAlertActive = sessionStore.config.value.autoOffEnabled && sessionStore.config.value.hasAnyLimit
        if (anyClientLimitActive || anySessionAlertActive) {
            HotspotLimitGuardService.start(context)
        } else {
            HotspotLimitGuardService.stop(context)
        }
    }

    override suspend fun setClientBlocked(mac: String, blocked: Boolean) {
        if (blocked) {
            refreshNow()
            ensureClientIpResolved(mac)
        } else {
            enforcementWarningSent.remove(mac)
        }
        hotspotDao.setBlocked(mac, blocked)
        if (blocked) {
            currentSpeeds[mac] = SpeedData(0L, 0L)
            hotspotDao.getClient(mac)?.let { client ->
                enforceDnsCaptiveBlock(
                    client,
                    "Device blocked in-app — Wi‑Fi access restricted."
                )
            }
            syncMacBlockingState(
                hotspotController.isHotspotEnabled.value || localNetworkScanner.hotspotActive.value
            )
        } else {
            hotspotDao.getClient(mac)?.let { restoreClientNetwork(it) }
        }
    }

    override suspend fun resetClientUsage(mac: String) {
        val client = hotspotDao.getClient(mac)
        hotspotDao.resetUsage(mac)
        lastSnapshotMap.remove(mac)
        currentSpeeds[mac] = SpeedData(0L, 0L)
        enforcementWarningSent.remove(mac)
        client?.let { restoreClientNetwork(it) }
    }

    override fun startMonitoring() {
        if (job != null) return
        job = scope.launch {
            launch {
                observeHotspotEnabled()
                    .distinctUntilChanged()
                    .collect { _ ->
                        refreshNow()
                    }
            }
            launch {
                var prevClients = hotspotController.connectedClients.value
                hotspotController.connectedClients
                    .collect { clients ->
                        if (clients != prevClients && clients.isNotEmpty()) {
                            prevClients = clients
                            // Burst-scan: do 5 rapid scans spaced 200 ms apart so we
                            // catch the ARP/DHCP entry as soon as the OS populates it
                            // (typically 100-300 ms after the device connects).
                            scope.launch {
                                repeat(BURST_SCAN_COUNT) {
                                    refreshNow()
                                    delay(BURST_SCAN_INTERVAL_MS)
                                }
                            }
                        } else {
                            prevClients = clients
                        }
                    }
            }
            while (isActive) {
                try {
                    monitorRealHotspotStep()
                } catch (e: Exception) {
                    Log.e("HotspotRepository", "Error in hotspot monitoring loop", e)
                }
                val intervalMs = if (
                    hotspotController.isHotspotEnabled.value ||
                    localNetworkScanner.hotspotActive.value
                ) {
                    HOTSPOT_POLL_ACTIVE_MS
                } else {
                    HOTSPOT_POLL_IDLE_MS
                }
                delay(intervalMs)
            }
        }
    }

    override fun stopMonitoring() {
        job?.cancel()
        job = null
    }

    override suspend fun refreshNow() {
        monitorRealHotspotStep(force = true)
    }

    override suspend fun dismissSessionEnforcement() {
        sessionAlertTriggered = false
        dnsBlockingRepository.setSessionBlocked(false)
        // Prevent background alarm from re-notifying in this session
        sessionStore.setLimitNotified(true)
        HotspotSessionAlarmScheduler.cancel(context)
    }

    override fun openHotspotSettings() {
        hotspotController.openHotspotSettings()
    }

    override fun aggregateClientSpeedBytesPerSec(): Long =
        currentSpeeds.values.sumOf { it.rxSpeed + it.txSpeed }

    private suspend fun monitorRealHotspotStep(force: Boolean = false) = monitorMutex.withLock {
        monitorRealHotspotStepInternal(force)
    }

    private suspend fun monitorRealHotspotStepInternal(force: Boolean = false) {
        hotspotController.refreshHotspotState()
        hotspotController.refreshConnectedClients()
        if (force) localNetworkScanner.forceRefresh() else localNetworkScanner.refresh()

        val now = System.currentTimeMillis()
        val discovered = HotspotClientMerger.merge(
            hotspotController.connectedClients.value,
            localNetworkScanner.clients.value
        )

        val hotspotActive = hotspotController.isHotspotEnabled.value || localNetworkScanner.hotspotActive.value
        if (!hotspotActive) {
            knownClientIps.clear()
            sessionAlertTriggered = false
            dnsBlockingRepository.setSessionBlocked(false)
            lastMonitorStepMs = 0L
            if (hotspotSessionStartMs > 0L) {
                hotspotTrafficTracker.clearSessionBaseline()
                HotspotSessionAlarmScheduler.cancel(context)
                sessionStore.clearSessionProgress()
            }
            hotspotSessionStartMs = 0L
            lastHotspotSessionRx = 0L
            lastHotspotSessionTx = 0L
            hotspotHistoryBaselineCaptured = false
            lastUplinkTransport = null
            tetheringMonitor.resetInterfaceBaseline()
        } else if (hotspotSessionStartMs == 0L) {
            hotspotSessionStartMs = now
            hotspotTrafficTracker.clearPollBaseline()
            hotspotTrafficTracker.resetMobileCellularBaseline()
            tetheringMonitor.resetInterfaceBaseline()
            lastUplinkTransport = hotspotTrafficTracker.detectUplinkTransport()
            sessionStore.clearSessionProgress()
            sessionStore.saveSessionProgress(hotspotSessionStartMs, 0L)
            HotspotSessionAlarmScheduler.schedule(context, sessionStore.config.value, hotspotSessionStartMs)
        }

        val tetherIface = localNetworkScanner.tetherInterface.value
            ?: tetheringMonitor.resolveActiveTetherInterfaces().firstOrNull()

        val currentUplink = hotspotTrafficTracker.detectUplinkTransport()
        if (hotspotActive && lastUplinkTransport != null && currentUplink != null &&
            currentUplink != lastUplinkTransport
        ) {
            Log.i(
                TAG,
                "Uplink changed ($lastUplinkTransport → $currentUplink) — resetting hotspot traffic baselines"
            )
            hotspotTrafficTracker.clearSessionBaseline()
            hotspotTrafficTracker.clearPollBaseline()
            hotspotTrafficTracker.resetMobileCellularBaseline()
            tetheringMonitor.resetInterfaceBaseline()
            lastHotspotSessionRx = 0L
            lastHotspotSessionTx = 0L
            tetherIface?.let { hotspotTrafficTracker.ensureSessionBaseline(it) }
        }
        if (hotspotActive && currentUplink != null) {
            lastUplinkTransport = currentUplink
        }

        if (hotspotActive && hotspotSessionStartMs > 0L) {
            tetherIface?.let { hotspotTrafficTracker.ensureSessionBaseline(it) }
        }

        val ifaceDelta = tetheringMonitor.readTetherInterfaceDelta()
        val trackerDelta = hotspotTrafficTracker.readPollDelta(tetherIface, hotspotSessionStartMs)
        val uplinkCellular = currentUplink == NetworkCapabilities.TRANSPORT_CELLULAR
        val mobileDelta = if (uplinkCellular && hotspotActive) {
            hotspotTrafficTracker.readMobileCellularPollDelta()
        } else {
            TetheringMonitor.TrafficDelta(0L, 0L)
        }
        val pollDelta = when {
            uplinkCellular && hotspotActive -> {
                val mobileTotal = mobileDelta.rxBytes + mobileDelta.txBytes
                val tetherTotal = trackerDelta.rxBytes + trackerDelta.txBytes +
                    ifaceDelta.rxBytes + ifaceDelta.txBytes
                if (mobileTotal >= tetherTotal) mobileDelta else trackerDelta
            }
            ifaceDelta.rxBytes > 0L || ifaceDelta.txBytes > 0L -> ifaceDelta
            else -> trackerDelta
        }

        val sessionTotals = if (hotspotSessionStartMs > 0L && hotspotActive) {
            hotspotTrafficTracker.readHotspotTotalsSince(hotspotSessionStartMs, tetherIface)
        } else {
            TetheringMonitor.TrafficDelta(0L, 0L)
        }
        lastHotspotSessionRx = sessionTotals.rxBytes
        lastHotspotSessionTx = sessionTotals.txBytes

        val sessionBytesUsed = sessionTotals.rxBytes + sessionTotals.txBytes
        if (hotspotActive && hotspotSessionStartMs > 0L) {
            sessionStore.saveSessionProgress(hotspotSessionStartMs, sessionBytesUsed)
        }
        val elapsedMs = if (hotspotSessionStartMs > 0L && hotspotActive) {
            now - hotspotSessionStartMs
        } else {
            0L
        }
        val stepMs = if (lastMonitorStepMs > 0L) {
            (now - lastMonitorStepMs).coerceIn(100L, 30_000L)
        } else {
            HOTSPOT_POLL_ACTIVE_MS
        }
        lastMonitorStepMs = now
        val sessionSpeedBytesPerSec = if (hotspotActive) {
            ((pollDelta.rxBytes + pollDelta.txBytes) * 1000L / stepMs).coerceAtLeast(0L)
        } else {
            0L
        }
        _sessionStatus.value = HotspotSessionStatus(
            isHotspotActive = hotspotActive,
            sessionStartMs = hotspotSessionStartMs,
            sessionBytesUsed = sessionBytesUsed,
            elapsedMs = elapsedMs,
            sessionSpeedBytesPerSec = sessionSpeedBytesPerSec
        )

        if (hotspotActive && !sessionAlertTriggered) {
            maybeNotifySessionAlert(sessionBytesUsed, now)
        }

        if (hotspotActive && hotspotHistoryBaselineCaptured) {
            if (pollDelta.rxBytes > 0L || pollDelta.txBytes > 0L) {
                usageHistoryRepository.recordHotspotDelta(pollDelta.rxBytes, pollDelta.txBytes, now)
            }
        } else if (hotspotActive) {
            hotspotHistoryBaselineCaptured = true
        }

        val attributed = tetheringMonitor.attributeTrafficToClients(discovered, pollDelta)
        val knownClients = hotspotDao.getAllClients().associateBy { it.macAddress }
        val activeMacs = if (hotspotActive) {
            discovered.map { it.macAddress }.toMutableSet()
        } else {
            mutableSetOf()
        }
        val prevActiveMacs = activeClientMacs.value
        val dropped = prevActiveMacs - activeMacs
        val gained = activeMacs - prevActiveMacs
        if (dropped.isNotEmpty()) Log.d(TAG, "Poll: devices dropped from scan this cycle: $dropped")
        if (gained.isNotEmpty()) Log.d(TAG, "Poll: devices (re)appeared in scan: $gained")
        activeClientMacs.value = activeMacs

        if (hotspotActive) {
            for (client in discovered) {
                if (HotspotClientMerger.hasValidIp(client.ipAddress) &&
                    knownClientIps.add(client.ipAddress)
                ) {
                    onHotspotClientDetected(client.ipAddress)
                }
            }
        }

        for (client in discovered) {
            val existing = knownClients[client.macAddress]
            val traffic = attributed[client.ipAddress] ?: TetheringMonitor.TrafficDelta(0L, 0L)
            val limitBytes = existing?.limitBytes
            val manualBlock = existing?.isBlocked == true

            val newRx = (existing?.rxBytes ?: 0L) + traffic.rxBytes
            val newTx = (existing?.txBytes ?: 0L) + traffic.txBytes
            val totalUsed = newRx + newTx

            val limitReached = HotspotLimitPolicy.isLimitReached(newRx, newTx, limitBytes)
            val effectivelyBlocked = HotspotLimitPolicy.isEffectivelyBlocked(
                manualBlock = manualBlock,
                rxBytes = newRx,
                txBytes = newTx,
                limitBytes = limitBytes
            )

            updateSpeed(client.macAddress, newRx, newTx, now)

            if (
                limitReached &&
                !manualBlock &&
                enforcementWarningSent.add(client.macAddress)
            ) {
                val blockedEntity = HotspotClientEntity(
                    macAddress = client.macAddress,
                    ipAddress = client.ipAddress,
                    deviceName = client.deviceName,
                    rxBytes = newRx,
                    txBytes = newTx,
                    limitBytes = limitBytes,
                    isBlocked = false,
                    lastSeenMs = now
                )
                enforceDnsCaptiveBlock(
                    blockedEntity,
                    "Data limit reached (${formatBytes(totalUsed)} / ${formatBytes(limitBytes!!)}) — browsing redirected to limit page."
                )
            }

            hotspotDao.upsertClient(
                HotspotClientEntity(
                    macAddress = client.macAddress,
                    ipAddress = client.ipAddress,
                    deviceName = client.deviceName,
                    rxBytes = newRx,
                    txBytes = newTx,
                    limitBytes = limitBytes,
                    isBlocked = manualBlock,
                    lastSeenMs = now
                )
            )

        }

        if (discovered.isEmpty() && hotspotActive &&
            (pollDelta.rxBytes > 0L || pollDelta.txBytes > 0L)
        ) {
            activeMacs.add(SESSION_AGGREGATE_MAC)
            val aggregateExisting = knownClients[SESSION_AGGREGATE_MAC]
            val aggregateRx = (aggregateExisting?.rxBytes ?: 0L) + pollDelta.rxBytes
            val aggregateTx = (aggregateExisting?.txBytes ?: 0L) + pollDelta.txBytes
            updateSpeed(SESSION_AGGREGATE_MAC, aggregateRx, aggregateTx, now)
            hotspotDao.upsertClient(
                HotspotClientEntity(
                    macAddress = SESSION_AGGREGATE_MAC,
                    ipAddress = "0.0.0.0",
                    deviceName = "Hotspot usage (session)",
                    rxBytes = aggregateRx,
                    txBytes = aggregateTx,
                    limitBytes = null,
                    isBlocked = false,
                    lastSeenMs = now
                )
            )
        }

        val staleThreshold = now - STALE_CLIENT_MS
        val activeStaleThreshold = now - ACTIVE_SESSION_STALE_CLIENT_MS
        for ((mac, entity) in knownClients) {
            if (mac !in activeMacs) {
                currentSpeeds.remove(mac)
                lastSnapshotMap.remove(mac)
                if (hotspotActive && !entity.isBlocked && entity.lastSeenMs < activeStaleThreshold) {
                    hotspotDao.deleteInactiveClients(activeStaleThreshold)
                } else if (!hotspotActive && entity.lastSeenMs < staleThreshold) {
                    hotspotDao.deleteInactiveClients(staleThreshold)
                }
            }
        }

        if (discovered.isEmpty() && knownClients.isNotEmpty() && !hotspotActive) {
            hotspotDao.deleteInactiveClients(staleThreshold)
        }

        syncMacBlockingState(hotspotActive)
    }

    private fun maybeNotifySessionAlert(sessionBytesUsed: Long, nowMs: Long) {
        val config = sessionStore.config.value
        val decision = HotspotSessionEnforcer.evaluate(
            config = config,
            sessionBytesUsed = sessionBytesUsed,
            sessionStartMs = hotspotSessionStartMs,
            hotspotActive = true,
            nowMs = nowMs
        )
        if (!decision.shouldNotify) return

        sessionAlertTriggered = true
        Log.i(TAG, "Session alert triggered (${decision.reason})")
        HotspotSessionAlarmScheduler.cancel(context)
        sessionStore.setLimitNotified(true)
        notifySessionAlert(decision.reason, sessionBytesUsed)
    }

    private fun notifySessionAlert(
        reason: HotspotSessionEnforcer.TriggerReason,
        sessionBytesUsed: Long
    ) {
        val config = sessionStore.config.value
        val title = "Hotspot session alert reached"
        val detail = when (reason) {
            HotspotSessionEnforcer.TriggerReason.DATA ->
                "Data alert reached (${formatBytes(sessionBytesUsed)} / ${formatBytes(config.dataLimitBytes)})."
            HotspotSessionEnforcer.TriggerReason.TIME ->
                "Time alert reached (${formatDurationMs(config.timeLimitMs)})."
            HotspotSessionEnforcer.TriggerReason.NONE -> return
        }
        val message = "$detail Hotspot and data remain on; this is notification-only."

        val appIntent = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            SESSION_AUTO_OFF_NOTIF_ID,
            appIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notif = NotificationCompat.Builder(context, HOTSPOT_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_defense)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        notificationManager.notify(SESSION_AUTO_OFF_NOTIF_ID, notif)
    }

    private fun formatDurationMs(ms: Long): String {
        val totalSec = (ms / 1000).coerceAtLeast(0)
        val hours = totalSec / 3600
        val minutes = (totalSec % 3600) / 60
        val seconds = totalSec % 60
        return when {
            hours > 0 -> "${hours}h ${minutes}m"
            minutes > 0 -> "${minutes}m ${seconds}s"
            else -> "${seconds}s"
        }
    }

    private suspend fun syncMacBlockingState(hotspotActive: Boolean) {
        if (!hotspotActive) {
            if (dnsBlockingRepository.getBlockedClients().isNotEmpty()) {
                dnsBlockingRepository.stopDnsInterception()
            }
            enforcedClients.clear()
            return
        }
        val blocked = hotspotDao.getAllClients().filter { client ->
            HotspotLimitPolicy.isEffectivelyBlocked(
                manualBlock = client.isBlocked,
                rxBytes = client.rxBytes,
                txBytes = client.txBytes,
                limitBytes = client.limitBytes
            ) && HotspotClientMerger.hasValidIp(client.ipAddress)
        }
        val activeBlockedIps = blocked.map { it.ipAddress }.toSet()
        enforcedClients.keys.retainAll { it in activeBlockedIps }

        if (blocked.isEmpty()) {
            if (dnsBlockingRepository.getBlockedClients().isEmpty()) {
                HotspotCaptivePortalService.requestStop(context)
            }
            return
        }

        HotspotCaptivePortalService.requestSync(context)

        for (client in blocked) {
            if (enforcedClients.containsKey(client.ipAddress)) continue
            dnsBlockingRepository.blockClient(
                clientIp = client.ipAddress,
                deviceName = client.deviceName,
                dataUsed = formatBytes(client.rxBytes + client.txBytes)
            )
            enforcedClients[client.ipAddress] = System.currentTimeMillis()
        }
    }

    private fun updateSpeed(mac: String, rx: Long, tx: Long, now: Long) {
        val prev = lastSnapshotMap[mac]
        if (prev != null) {
            val timeDeltaSec = ((now - prev.timestampMs).coerceAtLeast(100L)) / 1000.0
            val rxSpeed = ((rx - prev.rx).coerceAtLeast(0L) / timeDeltaSec).toLong()
            val txSpeed = ((tx - prev.tx).coerceAtLeast(0L) / timeDeltaSec).toLong()
            currentSpeeds[mac] = SpeedData(rxSpeed, txSpeed)
        } else {
            currentSpeeds[mac] = SpeedData(0L, 0L)
        }
        lastSnapshotMap[mac] = LastSpeedData(rx, tx, now)
    }

    private fun onHotspotClientDetected(@Suppress("UNUSED_PARAMETER") clientIp: String) {
        // MAC resolution requires system permissions; IP-only enforcement handles limits.
    }

    private fun notifyIpOnlyLimitEnforcement(client: HotspotClientEntity, dataUsed: String) {
        val message =
            "Limit reached for ${client.deviceName} (${client.ipAddress}). " +
                "Blocking by IP — browsing is restricted until you reset usage or unblock."
        notifyInAppBlockEnforced(client, message)
    }

    private suspend fun ensureClientIpResolved(mac: String) {
        repeat(3) {
            val client = hotspotDao.getClient(mac)
            if (client != null && HotspotClientMerger.hasValidIp(client.ipAddress)) return
            refreshNow()
            delay(350)
        }
    }

    private suspend fun restoreClientNetwork(client: HotspotClientEntity) {
        Log.i(TAG, "Restoring normal network for ${client.deviceName} (${client.ipAddress})")
        enforcedClients.remove(client.ipAddress)
        dnsBlockingRepository.unblockClient(client.ipAddress)
        val mac = macResolver.resolve(client.ipAddress)
            ?: client.macAddress.takeIf { HotspotClientMerger.isRealMac(it) }
        hotspotController.restoreClientNetworkAccess(mac.orEmpty(), client.ipAddress)
        syncMacBlockingState(
            hotspotController.isHotspotEnabled.value || localNetworkScanner.hotspotActive.value
        )
    }

    private suspend fun enforceDnsCaptiveBlock(client: HotspotClientEntity, message: String) {
        val now = System.currentTimeMillis()
        if (now - lastEnforcementNotifyMs < ENFORCEMENT_NOTIFY_DEBOUNCE_MS) {
            return
        }
        lastEnforcementNotifyMs = now
        Log.i(TAG, "Data limit reached for ${client.deviceName} (${client.ipAddress})")
        notifyInAppBlockEnforced(client, message)
    }

    private fun notifyInAppBlockEnforced(client: HotspotClientEntity, message: String) {
        val notifId = NOTIF_BASE_ID + client.macAddress.hashCode()
        val appIntent = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            client.macAddress.hashCode(),
            appIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val unblockIntent = HotspotEnforcementActionReceiver.unblockClientIntent(
            context, client.macAddress, notifId
        )

        val notif = NotificationCompat.Builder(context, HOTSPOT_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_defense)
            .setContentTitle("Blocked: ${client.deviceName}")
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .addAction(0, "Unblock", unblockIntent)
            .build()

        notificationManager.notify(notifId, notif)
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val exp = (Math.log(bytes.toDouble()) / Math.log(1024.0)).toInt()
        val pre = "KMGTPE"[exp - 1] + ""
        return String.format("%.1f %sB", bytes / Math.pow(1024.0, exp.toDouble()), pre)
    }
}
