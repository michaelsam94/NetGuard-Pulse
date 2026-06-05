package com.michael.netguardplus.system.hotspot.limit

import com.michael.netguardplus.data.local.db.dao.DeviceLimitDao
import com.michael.netguardplus.data.local.db.dao.DeviceUsageDao
import com.michael.netguardplus.data.local.db.entity.DeviceUsageEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

class UsageTracker(
    private val usageDao: DeviceUsageDao,
    private val deviceRegistry: DeviceRegistry,
    private val limitDao: DeviceLimitDao
) {
    private val pendingBytes = ConcurrentHashMap<String, Long>()
    private val snapshots = ConcurrentHashMap<String, DeviceUsageEntity>()
    private val flushMutex = Mutex()
    private var flushJob: Job? = null

    fun start(scope: CoroutineScope) {
        flushJob?.cancel()
        flushJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(FLUSH_INTERVAL_MS)
                flushToRoom()
            }
        }
    }

    fun stop() {
        flushJob?.cancel()
        flushJob = null
    }

    fun hasPendingWrites(): Boolean = pendingBytes.isNotEmpty()

    suspend fun ensureTracked(ip: String, nickname: String) {
        if (ip.isBlank()) return
        deviceRegistry.hydrate(ip)
        val resolved = DeviceNicknameMerger.best(nickname, usageDao.getUsage(ip)?.nickname ?: "")
        if (usageDao.getUsage(ip) == null) {
            val entity = DeviceUsageEntity(
                ip = ip,
                nickname = resolved,
                bytesUsed = 0L,
                sessionStartMs = System.currentTimeMillis(),
                lastSeenMs = System.currentTimeMillis()
            )
            usageDao.upsert(entity)
            deviceRegistry.applySyncedEntity(entity)
            snapshots[ip] = entity
        }
    }

    suspend fun recordPacket(sourceIp: String, packetLength: Int) {
        if (sourceIp.isBlank()) return
        deviceRegistry.hydrate(sourceIp)
        if (deviceRegistry.get(sourceIp) == null && usageDao.getUsage(sourceIp) == null) {
            if (limitDao.getLimit(sourceIp) == null) return
            ensureTracked(sourceIp, "")
        }
        val session = deviceRegistry.touch(sourceIp)
        snapshots[sourceIp] = session
        pendingBytes.merge(sourceIp, packetLength.toLong()) { old, inc -> old + inc }
    }

    fun getUsageSnapshot(ip: String): DeviceUsageEntity? {
        val base = snapshots[ip] ?: deviceRegistry.get(ip) ?: return null
        val pending = pendingBytes[ip] ?: 0L
        return base.copy(bytesUsed = base.bytesUsed + pending)
    }

    suspend fun flushToRoom() {
        flushMutex.withLock {
            if (pendingBytes.isEmpty()) return@withLock
            val entries = pendingBytes.entries.toList()
            for ((ip, delta) in entries) {
                if (!pendingBytes.remove(ip, delta)) continue
                deviceRegistry.hydrate(ip)
                val fromDb = usageDao.getUsage(ip)
                val current = fromDb ?: deviceRegistry.get(ip) ?: continue
                val merged = current.copy(
                    nickname = DeviceNicknameMerger.best(current.nickname, fromDb?.nickname ?: current.nickname),
                    bytesUsed = current.bytesUsed + delta,
                    lastSeenMs = System.currentTimeMillis()
                )
                if (fromDb != merged) {
                    usageDao.upsert(merged)
                }
                snapshots[ip] = merged
                deviceRegistry.applySyncedEntity(merged)
            }
        }
    }

    /**
     * Mirrors totals from tether monitoring (absolute counters, not packet deltas).
     * When totals drop (reconnect), session bytes are credited to the daily cap before resetting.
     */
    suspend fun syncHotspotTotals(
        ip: String,
        nickname: String,
        rxBytes: Long,
        txBytes: Long,
        sessionStartMs: Long?
    ) {
        if (ip.isBlank()) return
        deviceRegistry.hydrate(ip)
        val base = usageDao.getUsage(ip) ?: deviceRegistry.get(ip) ?: DeviceUsageEntity(ip = ip)
        val newSessionBytes = (rxBytes + txBytes).coerceAtLeast(0L)
        if (newSessionBytes < base.bytesUsed) {
            creditSessionToDaily(ip, base.bytesUsed)
        }
        val resolvedNickname = DeviceNicknameMerger.best(nickname, base.nickname)
        val entity = base.copy(
            nickname = resolvedNickname,
            bytesUsed = newSessionBytes,
            sessionStartMs = sessionStartMs ?: base.sessionStartMs,
            lastSeenMs = System.currentTimeMillis()
        )
        usageDao.upsert(entity)
        snapshots[ip] = entity
        deviceRegistry.applySyncedEntity(entity)
        pendingBytes.remove(ip)
    }

    suspend fun resetUsage(ip: String) {
        pendingBytes.remove(ip)
        deviceRegistry.resetUsage(ip)
        usageDao.getUsage(ip)?.let { snapshots[ip] = it }
    }

    private suspend fun creditSessionToDaily(ip: String, sessionBytes: Long) {
        if (sessionBytes <= 0L) return
        val limit = limitDao.getLimit(ip) ?: return
        if (limit.dataLimitBytes == Long.MAX_VALUE) return
        val daily = LimitEngine.effectiveDailyBytesStatic(limit)
        val updated = limit.copy(
            dailyBytesUsed = maxOf(daily, sessionBytes),
            dailyResetMs = LimitEngine.startOfTodayMs()
        )
        limitDao.upsert(updated)
    }

    companion object {
        private const val FLUSH_INTERVAL_MS = 5_000L
    }
}
