package com.michael.netguardplus.data.repository

import android.content.Context
import android.content.pm.PackageManager
import com.michael.netguardplus.data.local.db.dao.TrafficDao
import com.michael.netguardplus.data.local.db.entity.TrafficSnapshotEntity
import com.michael.netguardplus.data.local.db.dao.DnsLogDao
import com.michael.netguardplus.domain.model.AppTrafficInfo
import com.michael.netguardplus.domain.model.TrafficSummary
import com.michael.netguardplus.domain.repository.TrafficRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import java.util.Calendar
import java.util.concurrent.ConcurrentHashMap

class TrafficRepositoryImpl(
    private val context: Context,
    private val trafficDao: TrafficDao,
    private val dnsLogDao: DnsLogDao
) : TrafficRepository {

    private val pm: PackageManager = context.packageManager
    private val lastSnapshotMap = ConcurrentHashMap<Int, LastSnapshotData>()
    private val currentSpeeds = ConcurrentHashMap<Int, SpeedData>()
    private val uidPackageNames = ConcurrentHashMap<Int, String>()

    private data class LastSnapshotData(
        val wifiRx: Long,
        val wifiTx: Long,
        val mobileRx: Long,
        val mobileTx: Long,
        val timestampMs: Long
    )

    private data class SpeedData(
        val rxSpeed: Long,
        val txSpeed: Long
    )

    private data class DeviceSnapshot(
        val mobileRx: Long,
        val mobileTx: Long,
        val wifiRx: Long,
        val wifiTx: Long,
        val timestampMs: Long
    )

    private var lastDeviceSnapshot: DeviceSnapshot? = null
    @Volatile private var mobileRxSpeed = 0L
    @Volatile private var mobileTxSpeed = 0L
    @Volatile private var wifiRxSpeed = 0L
    @Volatile private var wifiTxSpeed = 0L
    private var cachedDeviceStats = DeviceSnapshot(0L, 0L, 0L, 0L, 0L)
    private val deviceStatsTick = MutableStateFlow(0L)
    private val appTrafficTick = MutableStateFlow(0L)

    override fun observeAllAppTraffic(): Flow<List<AppTrafficInfo>> {
        val sinceMs = startOfTodayMs()
        return combine(
            trafficDao.observeAggregatedTraffic(sinceMs),
            appTrafficTick
        ) { dbList, _ ->
            val dbByUid = dbList.associateBy { it.uid }
            val allUids = (dbByUid.keys + lastSnapshotMap.keys + currentSpeeds.keys).toSet()

            allUids.mapNotNull { uid ->
                val tuple = dbByUid[uid]
                val snapshot = lastSnapshotMap[uid]
                var packageName = tuple?.package_name ?: uidPackageNames[uid]
                if (packageName == null) {
                    packageName = try {
                        pm.getPackagesForUid(uid)?.firstOrNull()
                    } catch (_: Exception) {
                        null
                    }
                    if (packageName != null) {
                        uidPackageNames[uid] = packageName
                    }
                }
                if (packageName == null) return@mapNotNull null
                val speed = currentSpeeds[uid] ?: SpeedData(0L, 0L)
                val rxTotal = snapshot?.let { it.wifiRx + it.mobileRx } ?: tuple?.rx_total ?: 0L
                val txTotal = snapshot?.let { it.wifiTx + it.mobileTx } ?: tuple?.tx_total ?: 0L
                val liveSpeed = speed.rxSpeed + speed.txSpeed

                if (rxTotal == 0L && txTotal == 0L && liveSpeed == 0L) {
                    return@mapNotNull null
                }

                AppTrafficInfo(
                    uid = uid,
                    packageName = packageName,
                    appLabel = getAppLabel(packageName, uid),
                    rxBytesTotal = rxTotal,
                    txBytesTotal = txTotal,
                    rxBytesPerSec = speed.rxSpeed,
                    txBytesPerSec = speed.txSpeed,
                    isBackground = false,
                    lastActiveMs = snapshot?.timestampMs ?: tuple?.last_active ?: 0L,
                    blockedDomains = 0,
                    sessionStartMs = sinceMs
                )
            }
        }
    }

    override fun observeTrafficSummary(): Flow<TrafficSummary> {
        return combine(observeAllAppTraffic(), deviceStatsTick) { list, _ ->
            val deviceTotalRx = cachedDeviceStats.mobileRx + cachedDeviceStats.wifiRx
            val deviceTotalTx = cachedDeviceStats.mobileTx + cachedDeviceStats.wifiTx
            val deviceLiveRx = mobileRxSpeed + wifiRxSpeed
            val deviceLiveTx = mobileTxSpeed + wifiTxSpeed
            val blockedToday = dnsLogDao.countBlockedSince(startOfTodayMs())

            TrafficSummary(
                totalRxBytes = deviceTotalRx,
                totalTxBytes = deviceTotalTx,
                rxPerSec = deviceLiveRx,
                txPerSec = deviceLiveTx,
                activeAppCount = list.count { (it.rxBytesPerSec + it.txBytesPerSec) > 0 },
                blockedRequestsToday = blockedToday,
                mobileRxBytes = cachedDeviceStats.mobileRx,
                mobileTxBytes = cachedDeviceStats.mobileTx,
                mobileRxPerSec = mobileRxSpeed,
                mobileTxPerSec = mobileTxSpeed,
                wifiRxBytes = cachedDeviceStats.wifiRx,
                wifiTxBytes = cachedDeviceStats.wifiTx,
                wifiRxPerSec = wifiRxSpeed,
                wifiTxPerSec = wifiTxSpeed
            )
        }
    }

    override suspend fun getAppTrafficHistory(uid: Int, fromMs: Long): List<AppTrafficInfo> {
        val snapshots = trafficDao.getTrafficForAppSince(uid, fromMs)
        if (snapshots.isEmpty()) return emptyList()
        val pkg = snapshots.first().packageName
        val label = getAppLabel(pkg, uid)
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
        wifiRx: Long,
        wifiTx: Long,
        mobileRx: Long,
        mobileTx: Long,
        timestampMs: Long,
        isBackground: Boolean
    ) {
        uidPackageNames[uid] = packageName
        val prev = lastSnapshotMap[uid]
        lastSnapshotMap[uid] = LastSnapshotData(wifiRx, wifiTx, mobileRx, mobileTx, timestampMs)

        // Save Wifi delta to DB
        val wifiRxDelta = if (prev != null) (wifiRx - prev.wifiRx).coerceAtLeast(0) else wifiRx
        val wifiTxDelta = if (prev != null) (wifiTx - prev.wifiTx).coerceAtLeast(0) else wifiTx
        if (wifiRxDelta > 0 || wifiTxDelta > 0) {
            trafficDao.insertSnapshot(
                TrafficSnapshotEntity(
                    uid = uid,
                    packageName = packageName,
                    timestampMs = timestampMs,
                    rxBytes = wifiRxDelta,
                    txBytes = wifiTxDelta,
                    isBackground = isBackground,
                    isMobile = false
                )
            )
        }

        // Save Mobile delta to DB
        val mobileRxDelta = if (prev != null) (mobileRx - prev.mobileRx).coerceAtLeast(0) else mobileRx
        val mobileTxDelta = if (prev != null) (mobileTx - prev.mobileTx).coerceAtLeast(0) else mobileTx
        if (mobileRxDelta > 0 || mobileTxDelta > 0) {
            trafficDao.insertSnapshot(
                TrafficSnapshotEntity(
                    uid = uid,
                    packageName = packageName,
                    timestampMs = timestampMs,
                    rxBytes = mobileRxDelta,
                    txBytes = mobileTxDelta,
                    isBackground = isBackground,
                    isMobile = true
                )
            )
        }
    }

    override suspend fun getMobileTrafficToday(): Long {
        val manager = context.getSystemService(Context.NETWORK_STATS_SERVICE) as? android.app.usage.NetworkStatsManager
            ?: return trafficDao.getMobileTrafficSince(getStartOfDayMs())
        
        var rxBytes = 0L
        var txBytes = 0L
        try {
            manager.querySummary(
                android.net.NetworkCapabilities.TRANSPORT_CELLULAR,
                null,
                getStartOfDayMs(),
                System.currentTimeMillis()
            ).use { stats ->
                val bucket = android.app.usage.NetworkStats.Bucket()
                while (stats.hasNextBucket()) {
                    stats.getNextBucket(bucket)
                    rxBytes += bucket.rxBytes
                    txBytes += bucket.txBytes
                }
            }
            return rxBytes + txBytes
        } catch (e: Exception) {
            return trafficDao.getMobileTrafficSince(getStartOfDayMs())
        }
    }

    override suspend fun getMobileTrafficForAppToday(uid: Int): Long {
        val manager = context.getSystemService(Context.NETWORK_STATS_SERVICE) as? android.app.usage.NetworkStatsManager
            ?: return trafficDao.getMobileTrafficForAppSince(uid, getStartOfDayMs())
            
        var rxBytes = 0L
        var txBytes = 0L
        try {
            manager.queryDetailsForUid(
                android.net.NetworkCapabilities.TRANSPORT_CELLULAR,
                null,
                getStartOfDayMs(),
                System.currentTimeMillis(),
                uid
            ).use { stats ->
                val bucket = android.app.usage.NetworkStats.Bucket()
                while (stats.hasNextBucket()) {
                    stats.getNextBucket(bucket)
                    rxBytes += bucket.rxBytes
                    txBytes += bucket.txBytes
                }
            }
            return rxBytes + txBytes
        } catch (e: Exception) {
            return trafficDao.getMobileTrafficForAppSince(uid, getStartOfDayMs())
        }
    }

    override suspend fun getWifiTrafficToday(): Long {
        val manager = context.getSystemService(Context.NETWORK_STATS_SERVICE) as? android.app.usage.NetworkStatsManager
            ?: return trafficDao.getWifiTrafficSince(getStartOfDayMs())

        var rxBytes = 0L
        var txBytes = 0L
        try {
            manager.querySummary(
                android.net.NetworkCapabilities.TRANSPORT_WIFI,
                null,
                getStartOfDayMs(),
                System.currentTimeMillis()
            ).use { stats ->
                val bucket = android.app.usage.NetworkStats.Bucket()
                while (stats.hasNextBucket()) {
                    stats.getNextBucket(bucket)
                    rxBytes += bucket.rxBytes
                    txBytes += bucket.txBytes
                }
            }
            return rxBytes + txBytes
        } catch (e: Exception) {
            return trafficDao.getWifiTrafficSince(getStartOfDayMs())
        }
    }

    override suspend fun getWifiTrafficForAppToday(uid: Int): Long {
        val manager = context.getSystemService(Context.NETWORK_STATS_SERVICE) as? android.app.usage.NetworkStatsManager
            ?: return trafficDao.getWifiTrafficForAppSince(uid, getStartOfDayMs())

        var rxBytes = 0L
        var txBytes = 0L
        try {
            manager.queryDetailsForUid(
                android.net.NetworkCapabilities.TRANSPORT_WIFI,
                null,
                getStartOfDayMs(),
                System.currentTimeMillis(),
                uid
            ).use { stats ->
                val bucket = android.app.usage.NetworkStats.Bucket()
                while (stats.hasNextBucket()) {
                    stats.getNextBucket(bucket)
                    rxBytes += bucket.rxBytes
                    txBytes += bucket.txBytes
                }
            }
            return rxBytes + txBytes
        } catch (e: Exception) {
            return trafficDao.getWifiTrafficForAppSince(uid, getStartOfDayMs())
        }
    }

    private fun getStartOfDayMs(): Long {
        val calendar = java.util.Calendar.getInstance()
        calendar.set(java.util.Calendar.HOUR_OF_DAY, 0)
        calendar.set(java.util.Calendar.MINUTE, 0)
        calendar.set(java.util.Calendar.SECOND, 0)
        calendar.set(java.util.Calendar.MILLISECOND, 0)
        return calendar.timeInMillis
    }

    override suspend fun notifyAppTrafficPollComplete(timestampMs: Long) {
        appTrafficTick.value = timestampMs
    }

    override suspend fun updateAppSpeeds(speeds: Map<Int, Pair<Long, Long>>, timestampMs: Long) {
        for ((uid, speed) in speeds) {
            currentSpeeds[uid] = SpeedData(speed.first, speed.second)
        }
        appTrafficTick.value = timestampMs
    }

    override suspend fun updateDeviceNetworkStats(
        mobileRxBytes: Long,
        mobileTxBytes: Long,
        wifiRxBytes: Long,
        wifiTxBytes: Long,
        timestampMs: Long,
        mobileRxPerSec: Long,
        mobileTxPerSec: Long,
        wifiRxPerSec: Long,
        wifiTxPerSec: Long
    ) {
        val prev = lastDeviceSnapshot
        mobileRxSpeed = mobileRxPerSec
        mobileTxSpeed = mobileTxPerSec
        wifiRxSpeed = wifiRxPerSec
        wifiTxSpeed = wifiTxPerSec

        if (prev != null &&
            mobileRxPerSec == 0L && mobileTxPerSec == 0L &&
            wifiRxPerSec == 0L && wifiTxPerSec == 0L
        ) {
            val timeDeltaSec = ((timestampMs - prev.timestampMs).coerceAtLeast(100L)) / 1000.0
            mobileRxSpeed = ((mobileRxBytes - prev.mobileRx).coerceAtLeast(0L) / timeDeltaSec).toLong()
            mobileTxSpeed = ((mobileTxBytes - prev.mobileTx).coerceAtLeast(0L) / timeDeltaSec).toLong()
            wifiRxSpeed = ((wifiRxBytes - prev.wifiRx).coerceAtLeast(0L) / timeDeltaSec).toLong()
            wifiTxSpeed = ((wifiTxBytes - prev.wifiTx).coerceAtLeast(0L) / timeDeltaSec).toLong()
        }
        lastDeviceSnapshot = DeviceSnapshot(
            mobileRx = mobileRxBytes,
            mobileTx = mobileTxBytes,
            wifiRx = wifiRxBytes,
            wifiTx = wifiTxBytes,
            timestampMs = timestampMs
        )
        cachedDeviceStats = lastDeviceSnapshot!!
        deviceStatsTick.value = timestampMs
    }

    override suspend fun clearHistory(olderThanMs: Long) {
        trafficDao.purgeOlderThan(olderThanMs)
    }

    private fun getAppLabel(packageName: String, uid: Int): String {
        SYSTEM_UID_LABELS[uid]?.let { return it }
        return try {
            val appInfo = pm.getApplicationInfo(packageName, 0)
            pm.getApplicationLabel(appInfo).toString()
        } catch (_: Exception) {
            packageName.substringAfterLast('.')
        }
    }

    private fun startOfTodayMs(): Long {
        return Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }

    companion object {
        private val SYSTEM_UID_LABELS = mapOf(
            1000 to "Android System",
            1073 to "Network Stack",
            1021 to "Bluetooth",
            1010 to "Wi-Fi Service",
            1001 to "Telephony"
        )
    }
}
