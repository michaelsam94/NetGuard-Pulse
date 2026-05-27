package com.example.data.repository

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import com.example.data.local.db.dao.TrafficDao
import com.example.data.local.db.entity.TrafficSnapshotEntity
import com.example.data.local.db.dao.DnsLogDao
import com.example.domain.model.AppTrafficInfo
import com.example.domain.model.TrafficSummary
import com.example.domain.repository.TrafficRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.io.File
import java.util.concurrent.ConcurrentHashMap

class TrafficRepositoryImpl(
    private val context: Context,
    private val trafficDao: TrafficDao,
    private val dnsLogDao: DnsLogDao
) : TrafficRepository {

    private val pm: PackageManager = context.packageManager
    private val lastSnapshotMap = ConcurrentHashMap<Int, LastSnapshotData>()
    private val currentSpeeds = ConcurrentHashMap<Int, SpeedData>()

    private data class LastSnapshotData(
        val rx: Long,
        val tx: Long,
        val timestampMs: Long
    )

    private data class SpeedData(
        val rxSpeed: Long,
        val txSpeed: Long
    )

    override fun observeAllAppTraffic(): Flow<List<AppTrafficInfo>> {
        // Observe traffic snapshots from the last 24 hours (86400000 ms)
        val sinceMs = System.currentTimeMillis() - 86400000L
        return trafficDao.observeAggregatedTraffic(sinceMs).map { list ->
            list.map { tuple ->
                val appLabel = getAppLabel(tuple.package_name)
                val iconUri = "android.resource://${tuple.package_name}/" // Representing for Compose icon loader
                val speed = currentSpeeds[tuple.uid] ?: SpeedData(0L, 0L)

                AppTrafficInfo(
                    uid = tuple.uid,
                    packageName = tuple.package_name,
                    appLabel = appLabel,
                    rxBytesTotal = tuple.rx_total,
                    txBytesTotal = tuple.tx_total,
                    rxBytesPerSec = speed.rxSpeed,
                    txBytesPerSec = speed.txSpeed,
                    isBackground = false, // Background status calculated during polling
                    lastActiveMs = tuple.last_active,
                    blockedDomains = 0, // Blocked count mapped separately
                    sessionStartMs = sinceMs
                )
            }
        }
    }

    override fun observeTrafficSummary(): Flow<TrafficSummary> {
        return observeAllAppTraffic().map { list ->
            val totalRx = list.sumOf { it.rxBytesTotal }
            val totalTx = list.sumOf { it.txBytesTotal }
            val liveRx = list.sumOf { it.rxBytesPerSec }
            val liveTx = list.sumOf { it.txBytesPerSec }
            val blockedToday = dnsLogDao.countBlockedSince(System.currentTimeMillis() - 86400000L)

            TrafficSummary(
                totalRxBytes = totalRx,
                totalTxBytes = totalTx,
                rxPerSec = liveRx,
                txPerSec = liveTx,
                activeAppCount = list.count { (it.rxBytesPerSec + it.txBytesPerSec) > 0 },
                blockedRequestsToday = blockedToday
            )
        }
    }

    override suspend fun getAppTrafficHistory(uid: Int, fromMs: Long): List<AppTrafficInfo> {
        val snapshots = trafficDao.getTrafficForAppSince(uid, fromMs)
        if (snapshots.isEmpty()) return emptyList()
        val pkg = snapshots.first().packageName
        val label = getAppLabel(pkg)
        val rxTotal = snapshots.sumOf { it.rxBytes }
        val txTotal = snapshots.sumOf { it.txBytes }

        return listOf(
            AppTrafficInfo(
                uid = uid,
                packageName = pkg,
                appLabel = label,
                rxBytesTotal = rxTotal,
                txBytesTotal = txTotal,
                rxBytesPerSec = 0,
                txBytesPerSec = 0,
                isBackground = false,
                lastActiveMs = snapshots.maxOf { it.timestampMs },
                blockedDomains = 0,
                sessionStartMs = fromMs
            )
        )
    }

    override suspend fun saveSnapshot(
        uid: Int,
        packageName: String,
        rxBytes: Long,
        txBytes: Long,
        timestampMs: Long,
        isBackground: Boolean
    ) {
        val prev = lastSnapshotMap[uid]
        if (prev != null) {
            val timeDeltaSec = ((timestampMs - prev.timestampMs).coerceAtLeast(100L)) / 1000.0
            val rxSpeed = ((rxBytes - prev.rx).coerceAtLeast(0L) / timeDeltaSec).toLong()
            val txSpeed = ((txBytes - prev.tx).coerceAtLeast(0L) / timeDeltaSec).toLong()
            currentSpeeds[uid] = SpeedData(rxSpeed, txSpeed)
        } else {
            currentSpeeds[uid] = SpeedData(0L, 0L)
        }

        lastSnapshotMap[uid] = LastSnapshotData(rxBytes, txBytes, timestampMs)

        // Only save changes to database if there's actual traffic movement
        val rxDelta = if (prev != null) (rxBytes - prev.rx).coerceAtLeast(0) else rxBytes
        val txDelta = if (prev != null) (txBytes - prev.tx).coerceAtLeast(0) else txBytes

        if (rxDelta > 0 || txDelta > 0) {
            trafficDao.insertSnapshot(
                TrafficSnapshotEntity(
                    uid = uid,
                    packageName = packageName,
                    timestampMs = timestampMs,
                    rxBytes = rxDelta,
                    txBytes = txDelta,
                    isBackground = isBackground
                )
            )
        }
    }

    override suspend fun clearHistory(olderThanMs: Long) {
        trafficDao.purgeOlderThan(olderThanMs)
    }

    private fun getAppLabel(packageName: String): String {
        return try {
            val appInfo = pm.getApplicationInfo(packageName, 0)
            pm.getApplicationLabel(appInfo).toString()
        } catch (e: Exception) {
            packageName.substringAfterLast('.')
        }
    }
}
