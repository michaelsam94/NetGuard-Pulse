package com.example.system.stats

import android.app.usage.NetworkStats
import android.app.usage.NetworkStatsManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.NetworkCapabilities
import android.util.Log
import com.example.domain.repository.TrafficRepository
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap

class NetworkStatsPoller(
    private val context: Context,
    private val trafficRepo: TrafficRepository
) {
    private val statsManager = context.getSystemService(Context.NETWORK_STATS_SERVICE) as NetworkStatsManager
    private val pm = context.packageManager
    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + job)
    
    private val activeUids = ConcurrentHashMap<Int, String>()
    private var isPolling = false

    fun startPolling(intervalMs: Long = 2000L) {
        if (isPolling) return
        isPolling = true

        scope.launch {
            // First load matching application packages
            loadInstalledUids()

            while (isActive) {
                try {
                    pollOnce()
                } catch (e: Exception) {
                    Log.e("NetworkStatsPoller", "Error polling stats", e)
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
                // Only track apps with internet permissions to optimize query performance
                if (hasInternet) {
                    activeUids[app.uid] = app.packageName
                }
            }
        } catch (e: Exception) {
            Log.e("NetworkStatsPoller", "Error loading installed UIDs", e)
        }
    }

    private suspend fun pollOnce() = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val startTime = now - 10000L // Query the last 10 seconds for speed variations

        for ((uid, packageName) in activeUids) {
            var rxBytes = 0L
            var txBytes = 0L

            // 1. Wifi stats
            try {
                val bucket = statsManager.queryDetailsForUid(
                    NetworkCapabilities.TRANSPORT_WIFI,
                    null,
                    startTime,
                    now,
                    uid
                )
                val retBucket = NetworkStats.Bucket()
                while (bucket.hasNextBucket()) {
                    bucket.getNextBucket(retBucket)
                    rxBytes += retBucket.rxBytes
                    txBytes += retBucket.txBytes
                }
                bucket.close()
            } catch (e: Exception) {
                // Safe skip if stats aren't available
            }

            // 2. Cellular stats
            try {
                val bucket = statsManager.queryDetailsForUid(
                    NetworkCapabilities.TRANSPORT_CELLULAR,
                    null,
                    startTime,
                    now,
                    uid
                )
                val retBucket = NetworkStats.Bucket()
                while (bucket.hasNextBucket()) {
                    bucket.getNextBucket(retBucket)
                    rxBytes += retBucket.rxBytes
                    txBytes += retBucket.txBytes
                }
                bucket.close()
            } catch (e: Exception) {
                // Safe skip
            }

            if (rxBytes > 0 || txBytes > 0) {
                trafficRepo.saveSnapshot(
                    uid = uid,
                    packageName = packageName,
                    rxBytes = rxBytes,
                    txBytes = txBytes,
                    timestampMs = now,
                    isBackground = false // We assume average foreground/active state here
                )
            }
        }
    }

    fun stopPolling() {
        scope.cancel()
        isPolling = false
    }
}
