package com.michael.netguardplus.data.repository

import android.app.usage.NetworkStats
import android.app.usage.NetworkStatsManager
import android.content.Context
import android.net.NetworkCapabilities
import android.util.Log
import com.michael.netguardplus.data.local.db.dao.UsageHistoryDao
import com.michael.netguardplus.data.local.db.entity.NetworkUsageHistoryEntity
import com.michael.netguardplus.domain.model.UsageHistoryBucket
import com.michael.netguardplus.domain.model.UsageHistoryReport
import com.michael.netguardplus.domain.model.UsageHistorySummary
import com.michael.netguardplus.domain.model.UsageTraffic
import com.michael.netguardplus.domain.repository.UsageHistoryRepository
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class UsageHistoryRepositoryImpl(
    context: Context,
    private val usageHistoryDao: UsageHistoryDao
) : UsageHistoryRepository {

    private val statsManager =
        context.getSystemService(Context.NETWORK_STATS_SERVICE) as? NetworkStatsManager

    override suspend fun loadHistory(startMs: Long, endMs: Long): UsageHistoryReport {
        val safeStart = startMs.coerceAtMost(endMs)
        val safeEnd = endMs.coerceAtLeast(safeStart)

        val mobile = querySystemTransport(NetworkCapabilities.TRANSPORT_CELLULAR, safeStart, safeEnd)
        val wifi = querySystemTransport(NetworkCapabilities.TRANSPORT_WIFI, safeStart, safeEnd)
        val hotspotRecorded = readRecordedCategory(CATEGORY_HOTSPOT, safeStart, safeEnd)

        val summary = UsageHistorySummary(
            mobile = mobile,
            wifi = wifi,
            hotspot = hotspotRecorded
        )

        val buckets = buildBuckets(safeStart, safeEnd)
        return UsageHistoryReport(
            summary = summary,
            buckets = buckets,
            startMs = safeStart,
            endMs = safeEnd
        )
    }

    override suspend fun recordMobileDelta(rxDelta: Long, txDelta: Long, timestampMs: Long) {
        addDelta(CATEGORY_MOBILE, rxDelta, txDelta, timestampMs)
    }

    override suspend fun recordWifiDelta(rxDelta: Long, txDelta: Long, timestampMs: Long) {
        addDelta(CATEGORY_WIFI, rxDelta, txDelta, timestampMs)
    }

    override suspend fun recordHotspotDelta(rxDelta: Long, txDelta: Long, timestampMs: Long) {
        addDelta(CATEGORY_HOTSPOT, rxDelta, txDelta, timestampMs)
    }

    private suspend fun addDelta(category: String, rxDelta: Long, txDelta: Long, timestampMs: Long) {
        if (rxDelta <= 0L && txDelta <= 0L) return

        val bucketStart = truncateToHour(timestampMs)
        val bucketEnd = bucketStart + HOUR_MS
        val existing = usageHistoryDao.getBucket(category, bucketStart)
        val entity = if (existing != null) {
            existing.copy(
                rxBytes = existing.rxBytes + rxDelta.coerceAtLeast(0L),
                txBytes = existing.txBytes + txDelta.coerceAtLeast(0L)
            )
        } else {
            NetworkUsageHistoryEntity(
                category = category,
                bucketStartMs = bucketStart,
                bucketEndMs = bucketEnd,
                rxBytes = rxDelta.coerceAtLeast(0L),
                txBytes = txDelta.coerceAtLeast(0L)
            )
        }
        usageHistoryDao.upsertBucket(entity)
    }

    private suspend fun readRecordedCategory(
        category: String,
        startMs: Long,
        endMs: Long
    ): UsageTraffic {
        val buckets = usageHistoryDao.getBucketsForCategory(category, startMs, endMs)
        return UsageTraffic(
            rxBytes = buckets.sumOf { it.rxBytes },
            txBytes = buckets.sumOf { it.txBytes }
        )
    }

    private fun querySystemTransport(transport: Int, startMs: Long, endMs: Long): UsageTraffic {
        val manager = statsManager ?: return UsageTraffic()
        var rx = 0L
        var tx = 0L
        try {
            manager.querySummary(transport, null, startMs, endMs).use { stats ->
                val bucket = NetworkStats.Bucket()
                while (stats.hasNextBucket()) {
                    stats.getNextBucket(bucket)
                    rx += bucket.rxBytes
                    tx += bucket.txBytes
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "System summary unavailable for transport=$transport: ${e.message}")
        }
        return UsageTraffic(rxBytes = rx, txBytes = tx)
    }

    private suspend fun buildBuckets(startMs: Long, endMs: Long): List<UsageHistoryBucket> {
        val rangeMs = endMs - startMs
        val useHourly = rangeMs <= 2 * DAY_MS

        return if (useHourly) {
            buildHourlyBuckets(startMs, endMs)
        } else {
            buildDailyBuckets(startMs, endMs)
        }
    }

    private suspend fun buildHourlyBuckets(startMs: Long, endMs: Long): List<UsageHistoryBucket> {
        val hourFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
        val buckets = mutableListOf<UsageHistoryBucket>()
        var cursor = truncateToHour(startMs)
        while (cursor < endMs) {
            val bucketEnd = (cursor + HOUR_MS).coerceAtMost(endMs)
            buckets.add(
                UsageHistoryBucket(
                    label = hourFormat.format(Date(cursor)),
                    startMs = cursor,
                    endMs = bucketEnd,
                    mobile = querySystemTransport(NetworkCapabilities.TRANSPORT_CELLULAR, cursor, bucketEnd),
                    wifi = querySystemTransport(NetworkCapabilities.TRANSPORT_WIFI, cursor, bucketEnd),
                    hotspot = readRecordedCategory(CATEGORY_HOTSPOT, cursor, bucketEnd)
                )
            )
            cursor += HOUR_MS
        }
        return buckets.filter {
            it.totalFor(com.michael.netguardplus.domain.model.UsageCategory.ALL).totalBytes > 0L
        }.ifEmpty { buckets }
    }

    private suspend fun buildDailyBuckets(startMs: Long, endMs: Long): List<UsageHistoryBucket> {
        val dayFormat = SimpleDateFormat("MMM d", Locale.getDefault())
        val buckets = mutableListOf<UsageHistoryBucket>()
        val calendar = Calendar.getInstance().apply { timeInMillis = truncateToDay(startMs) }
        val endCalendar = Calendar.getInstance().apply { timeInMillis = endMs }

        while (!calendar.after(endCalendar)) {
            val dayStart = calendar.timeInMillis
            calendar.add(Calendar.DAY_OF_YEAR, 1)
            val dayEnd = calendar.timeInMillis.coerceAtMost(endMs)
            if (dayEnd <= dayStart) break

            buckets.add(
                UsageHistoryBucket(
                    label = dayFormat.format(Date(dayStart)),
                    startMs = dayStart,
                    endMs = dayEnd,
                    mobile = querySystemTransport(NetworkCapabilities.TRANSPORT_CELLULAR, dayStart, dayEnd),
                    wifi = querySystemTransport(NetworkCapabilities.TRANSPORT_WIFI, dayStart, dayEnd),
                    hotspot = readRecordedCategory(CATEGORY_HOTSPOT, dayStart, dayEnd)
                )
            )
        }
        return buckets
    }

    private fun truncateToHour(timestampMs: Long): Long {
        val calendar = Calendar.getInstance().apply { timeInMillis = timestampMs }
        calendar.set(Calendar.MINUTE, 0)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)
        return calendar.timeInMillis
    }

    private fun truncateToDay(timestampMs: Long): Long {
        val calendar = Calendar.getInstance().apply { timeInMillis = timestampMs }
        calendar.set(Calendar.HOUR_OF_DAY, 0)
        calendar.set(Calendar.MINUTE, 0)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)
        return calendar.timeInMillis
    }

    companion object {
        private const val TAG = "UsageHistoryRepo"
        private const val HOUR_MS = 3_600_000L
        private const val DAY_MS = 86_400_000L
        const val CATEGORY_MOBILE = "MOBILE"
        const val CATEGORY_WIFI = "WIFI"
        const val CATEGORY_HOTSPOT = "HOTSPOT"
    }
}
