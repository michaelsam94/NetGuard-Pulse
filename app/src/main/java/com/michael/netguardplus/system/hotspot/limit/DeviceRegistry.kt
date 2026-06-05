package com.michael.netguardplus.system.hotspot.limit

import com.michael.netguardplus.data.local.db.dao.DeviceLimitDao
import com.michael.netguardplus.data.local.db.dao.DeviceUsageDao
import com.michael.netguardplus.data.local.db.entity.DeviceUsageEntity
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/**
 * Maps source IPs to nicknames and stable usage sessions (in-memory, flushed with [UsageTracker]).
 */
class DeviceRegistry(
    private val usageDao: DeviceUsageDao,
    private val limitDao: DeviceLimitDao
) {
    private val sessions = ConcurrentHashMap<String, DeviceUsageEntity>()
    private val mutex = Mutex()

    suspend fun hydrate(ip: String) {
        if (sessions.containsKey(ip)) return
        usageDao.getUsage(ip)?.let { sessions[ip] = it }
    }

    suspend fun touch(ip: String, nowMs: Long = System.currentTimeMillis()): DeviceUsageEntity {
        val existing = sessions[ip]
        val updated = when {
            existing == null -> {
                val fromDb = usageDao.getUsage(ip)
                fromDb?.copy(lastSeenMs = nowMs) ?: DeviceUsageEntity(
                    ip = ip,
                    nickname = UNKNOWN,
                    bytesUsed = 0L,
                    sessionStartMs = nowMs,
                    lastSeenMs = nowMs
                )
            }
            nowMs - existing.lastSeenMs > STALE_SESSION_MS -> {
                creditSessionToDaily(ip, existing.bytesUsed)
                existing.copy(
                    bytesUsed = 0L,
                    sessionStartMs = nowMs,
                    lastSeenMs = nowMs
                )
            }
            else -> existing.copy(lastSeenMs = nowMs)
        }
        sessions[ip] = updated
        return updated
    }

    /** Keeps registry and Room in sync after tether stats push (avoids stale "Unknown device" overwrites). */
    fun applySyncedEntity(entity: DeviceUsageEntity) {
        sessions[entity.ip] = entity
    }

    fun get(ip: String): DeviceUsageEntity? = sessions[ip]

    fun allSessions(): Collection<DeviceUsageEntity> = sessions.values

    suspend fun setNickname(ip: String, nickname: String) {
        mutex.withLock {
            val existing = sessions[ip] ?: usageDao.getUsage(ip) ?: DeviceUsageEntity(ip = ip)
            val updated = existing.copy(
                nickname = DeviceNicknameMerger.best(nickname, existing.nickname)
            )
            sessions[ip] = updated
            usageDao.upsert(updated)
        }
    }

    suspend fun resetUsage(ip: String, nowMs: Long = System.currentTimeMillis()) {
        mutex.withLock {
            val existing = sessions[ip] ?: usageDao.getUsage(ip) ?: DeviceUsageEntity(ip = ip)
            val updated = existing.copy(
                bytesUsed = 0L,
                sessionStartMs = nowMs,
                lastSeenMs = nowMs
            )
            sessions[ip] = updated
            usageDao.upsert(updated)
        }
    }

    private suspend fun creditSessionToDaily(ip: String, sessionBytes: Long) {
        if (sessionBytes <= 0L) return
        val limit = limitDao.getLimit(ip) ?: return
        if (limit.dataLimitBytes == Long.MAX_VALUE) return
        val daily = LimitEngine.effectiveDailyBytesStatic(limit)
        limitDao.upsert(
            limit.copy(
                dailyBytesUsed = maxOf(daily, sessionBytes),
                dailyResetMs = LimitEngine.startOfTodayMs()
            )
        )
    }

    companion object {
        const val STALE_SESSION_MS = 10 * 60 * 1000L
        private const val UNKNOWN = "Unknown device"
    }
}
