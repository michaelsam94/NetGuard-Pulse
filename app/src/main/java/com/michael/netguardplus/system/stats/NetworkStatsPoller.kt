package com.michael.netguardplus.system.stats

import android.app.usage.NetworkStats
import android.app.usage.NetworkStatsManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.NetworkCapabilities
import android.net.TrafficStats
import android.util.Log
import com.michael.netguardplus.domain.repository.TrafficRepository
import com.michael.netguardplus.domain.repository.UsageHistoryRepository
import com.michael.netguardplus.system.alert.AlertEngine
import kotlinx.coroutines.*
import java.net.NetworkInterface
import java.util.Calendar
import java.util.concurrent.ConcurrentHashMap

class NetworkStatsPoller(
    private val context: Context,
    private val trafficRepo: TrafficRepository,
    private val usageHistoryRepo: UsageHistoryRepository,
    private val alertEngine: AlertEngine
) {
    private val statsManager = context.getSystemService(Context.NETWORK_STATS_SERVICE) as? NetworkStatsManager
    private val pm = context.packageManager
    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + job)

    private val activeUids = ConcurrentHashMap<Int, String>()
    private var isPolling = false
    private val trackingStartMs = startOfTodayMs()
    private var lastLiveSample: LiveCounterSample? = null
    private val wifiIface = detectWifiStationInterface()
    private var lastDeviceTotals: DeviceTotals? = null
    private var pollGeneration = 0
    private val lastTrafficStatsUidBytes = ConcurrentHashMap<Int, Pair<Long, Long>>()
    private var lastPollTimeMs = 0L

    fun startPolling(intervalMs: Long = 5000L) {
        if (isPolling) return
        isPolling = true

        scope.launch {
            loadInstalledUids()

            while (isActive) {
                try {
                    pollOnce()
                } catch (e: Exception) {
                    Log.e(TAG, "Error polling stats", e)
                }
                delay(intervalMs)
            }
        }
    }

    private fun loadInstalledUids() {
        try {
            val apps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
            for (app in apps) {
                val hasInternet = try {
                    pm.getPackageInfo(app.packageName, PackageManager.GET_PERMISSIONS)
                        .requestedPermissions?.contains(android.Manifest.permission.INTERNET) == true
                } catch (e: Exception) {
                    false
                }
                if (hasInternet) {
                    activeUids[app.uid] = app.packageName
                }
            }
            // System services that use the network but aren't regular installed apps
            activeUids[1000] = "android"
            activeUids[1073] = "com.android.networkstack"
            activeUids[1021] = "com.android.bluetooth"
            activeUids[1010] = "com.android.wifi"
        } catch (e: Exception) {
            Log.e(TAG, "Error loading installed UIDs", e)
        }
    }

    private suspend fun pollOnce() = withContext(Dispatchers.IO) {
        val manager = statsManager ?: return@withContext
        val now = System.currentTimeMillis()
        pollGeneration++
        val pollPerAppDetails = pollGeneration % 5 == 0

        // Calculate real-time per-app speeds on every poll using TrafficStats
        val appSpeeds = mutableMapOf<Int, Pair<Long, Long>>()
        val timeDeltaSec = if (lastPollTimeMs > 0L) {
            ((now - lastPollTimeMs).coerceAtLeast(100L)) / 1000.0
        } else {
            0.0
        }

        for ((uid, _) in activeUids) {
            val rxBytes = TrafficStats.getUidRxBytes(uid)
            val txBytes = TrafficStats.getUidTxBytes(uid)
            val rx = if (rxBytes >= 0L) rxBytes else 0L
            val tx = if (txBytes >= 0L) txBytes else 0L

            val prev = lastTrafficStatsUidBytes[uid]
            lastTrafficStatsUidBytes[uid] = Pair(rx, tx)

            if (prev != null && timeDeltaSec > 0.0) {
                val rxSpeed = ((rx - prev.first).coerceAtLeast(0L) / timeDeltaSec).toLong()
                val txSpeed = ((tx - prev.second).coerceAtLeast(0L) / timeDeltaSec).toLong()
                appSpeeds[uid] = Pair(rxSpeed, txSpeed)
            } else {
                appSpeeds[uid] = Pair(0L, 0L)
            }
        }
        lastPollTimeMs = now
        trafficRepo.updateAppSpeeds(appSpeeds, now)

        var deviceMobileRx = 0L
        var deviceMobileTx = 0L
        var deviceWifiRx = 0L
        var deviceWifiTx = 0L

        if (pollPerAppDetails) {
            for ((uid, packageName) in activeUids) {
                val cellular = queryUidBytes(manager, uid, NetworkCapabilities.TRANSPORT_CELLULAR, now)
                val wifi = queryUidBytes(manager, uid, NetworkCapabilities.TRANSPORT_WIFI, now)

                deviceMobileRx += cellular.first
                deviceMobileTx += cellular.second
                deviceWifiRx += wifi.first
                deviceWifiTx += wifi.second

                trafficRepo.saveSnapshot(
                    uid = uid,
                    packageName = packageName,
                    wifiRx = wifi.first,
                    wifiTx = wifi.second,
                    mobileRx = cellular.first,
                    mobileTx = cellular.second,
                    timestampMs = now,
                    isBackground = false
                )

            }
            trafficRepo.notifyAppTrafficPollComplete(now)
        }

        val summaryMobile = queryTransportSummary(manager, NetworkCapabilities.TRANSPORT_CELLULAR, now)
        val summaryWifi = queryTransportSummary(manager, NetworkCapabilities.TRANSPORT_WIFI, now)

        if (summaryMobile.first > 0L || summaryMobile.second > 0L) {
            deviceMobileRx = summaryMobile.first
            deviceMobileTx = summaryMobile.second
        }
        if (summaryWifi.first > 0L || summaryWifi.second > 0L) {
            deviceWifiRx = summaryWifi.first
            deviceWifiTx = summaryWifi.second
        }

        val liveSpeeds = computeLiveSpeeds(now)

        recordHistoryDeltas(
            mobileRx = deviceMobileRx,
            mobileTx = deviceMobileTx,
            wifiRx = deviceWifiRx,
            wifiTx = deviceWifiTx,
            now = now
        )

        trafficRepo.updateDeviceNetworkStats(
            mobileRxBytes = deviceMobileRx,
            mobileTxBytes = deviceMobileTx,
            wifiRxBytes = deviceWifiRx,
            wifiTxBytes = deviceWifiTx,
            timestampMs = now,
            mobileRxPerSec = liveSpeeds.mobileRxPerSec,
            mobileTxPerSec = liveSpeeds.mobileTxPerSec,
            wifiRxPerSec = liveSpeeds.wifiRxPerSec,
            wifiTxPerSec = liveSpeeds.wifiTxPerSec
        )
        alertEngine.checkAllActiveAlerts()
    }

    private suspend fun recordHistoryDeltas(
        mobileRx: Long,
        mobileTx: Long,
        wifiRx: Long,
        wifiTx: Long,
        now: Long
    ) {
        val previous = lastDeviceTotals
        lastDeviceTotals = DeviceTotals(mobileRx, mobileTx, wifiRx, wifiTx)
        if (previous == null) return

        val mobileRxDelta = (mobileRx - previous.mobileRx).coerceAtLeast(0L)
        val mobileTxDelta = (mobileTx - previous.mobileTx).coerceAtLeast(0L)
        val wifiRxDelta = (wifiRx - previous.wifiRx).coerceAtLeast(0L)
        val wifiTxDelta = (wifiTx - previous.wifiTx).coerceAtLeast(0L)

        usageHistoryRepo.recordMobileDelta(mobileRxDelta, mobileTxDelta, now)
        usageHistoryRepo.recordWifiDelta(wifiRxDelta, wifiTxDelta, now)
    }

    private fun computeLiveSpeeds(now: Long): LiveSpeeds {
        val current = readLiveCounterSample(now)
        val previous = lastLiveSample
        lastLiveSample = current

        if (previous == null) {
            return LiveSpeeds()
        }

        val timeDeltaSec = ((now - previous.timestampMs).coerceAtLeast(100L)) / 1000.0
        return LiveSpeeds(
            mobileRxPerSec = speedDelta(current.mobileRx, previous.mobileRx, timeDeltaSec),
            mobileTxPerSec = speedDelta(current.mobileTx, previous.mobileTx, timeDeltaSec),
            wifiRxPerSec = speedDelta(current.wifiRx, previous.wifiRx, timeDeltaSec),
            wifiTxPerSec = speedDelta(current.wifiTx, previous.wifiTx, timeDeltaSec)
        )
    }

    private fun speedDelta(current: Long, previous: Long, timeDeltaSec: Double): Long {
        if (current < 0L || previous < 0L) return 0L
        return ((current - previous).coerceAtLeast(0L) / timeDeltaSec).toLong()
    }

    private fun readLiveCounterSample(now: Long): LiveCounterSample {
        val mobileRx = TrafficStats.getMobileRxBytes().normalizeTrafficStat()
        val mobileTx = TrafficStats.getMobileTxBytes().normalizeTrafficStat()
        val wifiRx = readIfaceRxBytes(wifiIface).normalizeTrafficStat()
        val wifiTx = readIfaceTxBytes(wifiIface).normalizeTrafficStat()
        return LiveCounterSample(mobileRx, mobileTx, wifiRx, wifiTx, now)
    }

    private fun Long.normalizeTrafficStat(): Long {
        return if (this >= 0L) this else -1L
    }

    private fun readIfaceRxBytes(iface: String): Long {
        return readIfaceBytes(iface, "getRxBytes")
    }

    private fun readIfaceTxBytes(iface: String): Long {
        return readIfaceBytes(iface, "getTxBytes")
    }

    private fun readIfaceBytes(iface: String, methodName: String): Long {
        return try {
            TrafficStats::class.java.getMethod(methodName, String::class.java)
                .invoke(null, iface) as Long
        } catch (_: Exception) {
            -1L
        }
    }

    private fun queryTransportSummary(
        manager: NetworkStatsManager,
        transport: Int,
        endMs: Long
    ): Pair<Long, Long> {
        var rxBytes = 0L
        var txBytes = 0L
        try {
            manager.querySummary(transport, null, trackingStartMs, endMs).use { stats ->
                val bucket = NetworkStats.Bucket()
                while (stats.hasNextBucket()) {
                    stats.getNextBucket(bucket)
                    rxBytes += bucket.rxBytes
                    txBytes += bucket.txBytes
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "Summary query failed for transport=$transport: ${e.message}")
        }
        return rxBytes to txBytes
    }

    private fun queryUidBytes(
        manager: NetworkStatsManager,
        uid: Int,
        transport: Int,
        endMs: Long
    ): Pair<Long, Long> {
        var rxBytes = 0L
        var txBytes = 0L
        try {
            manager.queryDetailsForUid(
                transport,
                null,
                trackingStartMs,
                endMs,
                uid
            ).use { bucketResult ->
                val retBucket = NetworkStats.Bucket()
                while (bucketResult.hasNextBucket()) {
                    bucketResult.getNextBucket(retBucket)
                    rxBytes += retBucket.rxBytes
                    txBytes += retBucket.txBytes
                }
            }
        } catch (_: Exception) {
            // Skip UIDs without stats access
        }
        return rxBytes to txBytes
    }

    private fun detectWifiStationInterface(): String {
        return try {
            NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
                .firstOrNull { ni ->
                    ni.isUp && !ni.isLoopback && ni.name?.startsWith("wlan") == true &&
                        !isLikelyHotspotInterface(ni.name)
                }
                ?.name
                ?: "wlan0"
        } catch (_: Exception) {
            "wlan0"
        }
    }

    private fun isLikelyHotspotInterface(name: String?): Boolean {
        if (name == null) return false
        if (name.matches(Regex("^wlan[1-9]\\d*$"))) return true
        return name.matches(Regex("^(ap\\d+|softap\\d+)$"))
    }

    fun stopPolling() {
        scope.cancel()
        isPolling = false
        lastLiveSample = null
    }

    suspend fun pollNow() {
        try {
            pollOnce()
        } catch (e: Exception) {
            Log.e(TAG, "Error during manual poll", e)
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

    private data class LiveCounterSample(
        val mobileRx: Long,
        val mobileTx: Long,
        val wifiRx: Long,
        val wifiTx: Long,
        val timestampMs: Long
    )

    private data class LiveSpeeds(
        val mobileRxPerSec: Long = 0L,
        val mobileTxPerSec: Long = 0L,
        val wifiRxPerSec: Long = 0L,
        val wifiTxPerSec: Long = 0L
    )

    private data class DeviceTotals(
        val mobileRx: Long,
        val mobileTx: Long,
        val wifiRx: Long,
        val wifiTx: Long
    )

    companion object {
        private const val TAG = "NetworkStatsPoller"
    }
}
