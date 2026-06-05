package com.michael.netguardplus.system.hotspot.limit

import com.michael.netguardplus.data.local.db.dao.DeviceLimitDao
import com.michael.netguardplus.data.local.db.dao.DeviceUsageDao
import com.michael.netguardplus.data.local.db.entity.DeviceLimitEntity
import com.michael.netguardplus.data.local.db.entity.DeviceUsageEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Calendar
import java.util.concurrent.ConcurrentHashMap

class LimitEngine(
    private val usageDao: DeviceUsageDao,
    private val limitDao: DeviceLimitDao,
    private val usageTracker: UsageTracker
) {
    private val cache = ConcurrentHashMap<String, DeviceLimitEntity>()
    private var refreshJob: Job? = null

    fun start(scope: CoroutineScope) {
        refreshJob?.cancel()
        refreshJob = scope.launch(Dispatchers.IO) {
            refreshCache()
            while (isActive) {
                delay(CACHE_REFRESH_MS)
                refreshCache()
            }
        }
    }

    fun stop() {
        refreshJob?.cancel()
        refreshJob = null
    }

    suspend fun refreshCache() {
        val limits = limitDao.getAll()
        cache.clear()
        limits.forEach { cache[it.ip] = it }
    }

    fun setLimit(ip: String, limit: DeviceLimitEntity) {
        cache[ip] = limit
    }

    fun shouldBlock(ip: String, additionalBytes: Int): Boolean {
        val limit = cache[ip] ?: return false
        if (limit.isManuallyBlocked) return true

        val usage = usageTracker.getUsageSnapshot(ip) ?: return false
        val daily = effectiveDailyBytesStatic(limit)
        val sessionBytes = usage.bytesUsed + additionalBytes.coerceAtLeast(0)

        if (limit.dataLimitBytes != Long.MAX_VALUE &&
            daily + sessionBytes >= limit.dataLimitBytes
        ) {
            return true
        }

        val sessionAge = System.currentTimeMillis() - usage.sessionStartMs
        if (limit.timeLimitMs != Long.MAX_VALUE && sessionAge > limit.timeLimitMs) {
            return true
        }
        return false
    }

    fun effectiveDailyBytes(limit: DeviceLimitEntity): Long = effectiveDailyBytesStatic(limit)

    suspend fun creditSessionToDaily(ip: String, sessionBytes: Long) {
        if (sessionBytes <= 0L) return
        val limit = cache[ip] ?: limitDao.getLimit(ip) ?: return
        if (limit.dataLimitBytes == Long.MAX_VALUE) return
        val daily = effectiveDailyBytesStatic(limit)
        val updated = limit.copy(
            dailyBytesUsed = maxOf(daily, sessionBytes),
            dailyResetMs = startOfTodayMs()
        )
        persistLimit(updated)
    }

    suspend fun persistLimit(limit: DeviceLimitEntity) {
        limitDao.upsert(limit)
        cache[limit.ip] = limit
    }

    suspend fun loadUsageFromDb(ip: String): DeviceUsageEntity? = usageDao.getUsage(ip)

    companion object {
        private const val CACHE_REFRESH_MS = 30_000L
        private const val DAY_MS = 86_400_000L

        fun effectiveDailyBytesStatic(limit: DeviceLimitEntity): Long {
            if (limit.dailyResetMs <= 0L) return 0L
            val elapsed = System.currentTimeMillis() - limit.dailyResetMs
            return if (elapsed in 0 until DAY_MS) limit.dailyBytesUsed else 0L
        }

        fun startOfTodayMs(): Long {
            val cal = Calendar.getInstance()
            cal.set(Calendar.HOUR_OF_DAY, 0)
            cal.set(Calendar.MINUTE, 0)
            cal.set(Calendar.SECOND, 0)
            cal.set(Calendar.MILLISECOND, 0)
            return cal.timeInMillis
        }
    }
}
