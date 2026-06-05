package com.michael.netguardplus.system.hotspot

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import com.michael.netguardplus.system.stats.TetheringMonitor
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Resolves tether client IP → hardware MAC using [WifiManager.getSoftApConnectedStations] only.
 *
 * When [WifiManager.getSoftApConnectedStations] cannot map IP→MAC, falls back to tether ARP /
 * `ip neigh` scans via [TetheringMonitor].
 */
class MacAddressResolver(
    context: Context,
    private val wifiManager: WifiManager = (context.applicationContext ?: context)
        .getSystemService(Context.WIFI_SERVICE) as WifiManager,
    private val shellMacResolver: ((String) -> String?)? = null
) {
    private val ipToMacCache = ConcurrentHashMap<String, String>()
    private val tetheringMonitor = TetheringMonitor()

    fun resolve(ip: String): String? {
        if (!HotspotClientMerger.hasValidIp(ip)) return null
        ipToMacCache[ip]?.let { return it }
        val mac = getMacForIp(ip)
        if (mac != null) {
            ipToMacCache[ip] = mac
        }
        return mac
    }

    fun invalidate(ip: String) {
        ipToMacCache.remove(ip)
    }

    fun clearCache() {
        ipToMacCache.clear()
    }

    /** Call when a new hotspot client IP is first observed to populate the cache early. */
    fun onClientConnected(clientIp: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            refreshCache(clientIp)
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    fun refreshCache(triggerIp: String? = null) {
        try {
            val stations = loadSoftApStations()
            Log.d(TAG, "refreshCache: ${stations.size} stations connected")
            stations.forEach { client ->
                val mac = extractMacFromStation(client)
                Log.d(TAG, "  Station MAC: $mac")
            }
            if (stations.size == 1 && triggerIp != null) {
                val mac = extractMacFromStation(stations[0]) ?: return
                ipToMacCache[triggerIp] = mac
                Log.i(TAG, "Cached at refresh: $triggerIp → $mac")
            }
        } catch (e: Exception) {
            Log.w(TAG, "refreshCache failed: ${e.message}")
        }
    }

    fun getMacForIp(targetIp: String): String? {
        if (!HotspotClientMerger.hasValidIp(targetIp)) return null

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                for (station in loadSoftApStations()) {
                    val ip = extractIpFromStation(station)
                    val mac = extractMacFromStation(station)
                    if (ip == targetIp && mac != null) {
                        ipToMacCache[targetIp] = mac
                        return mac
                    }
                }
            } catch (e: Exception) {
                Log.d(TAG, "Station lookup failed for $targetIp: ${e.message}")
            }
        }

        tetheringMonitor.resolveMacByIp(targetIp)?.let { mac ->
            ipToMacCache[targetIp] = mac
            return mac
        }

        shellMacResolver?.invoke(targetIp)?.let { mac ->
            ipToMacCache[targetIp] = mac
            return mac
        }

        return null
    }

    private fun extractIpFromStation(item: Any): String? {
        return try {
            val ipMethod = item.javaClass.methods.firstOrNull { m ->
                m.name == "getInetAddress" || m.name == "getIpAddress"
            } ?: return null
            val ipObj = ipMethod.invoke(item) ?: return null
            when (ipObj) {
                is String -> ipObj.takeIf { HotspotClientMerger.hasValidIp(it) }
                else -> ipObj.javaClass.getMethod("getHostAddress").invoke(ipObj) as? String
            }
        } catch (_: Exception) {
            null
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun loadSoftApStations(): List<Any> {
        return try {
            val method = wifiManager.javaClass.methods.firstOrNull { m ->
                m.name == "getSoftApConnectedStations" && m.parameterTypes.isEmpty()
            } ?: return emptyList()
            @Suppress("UNCHECKED_CAST")
            method.invoke(wifiManager) as? List<Any> ?: emptyList()
        } catch (e: Exception) {
            Log.w(TAG, "getSoftApConnectedStations failed: ${e.message}")
            emptyList()
        }
    }

    private fun extractMacFromStation(item: Any): String? {
        return try {
            when (item) {
                is String -> item.takeIf { it.contains(':') }?.uppercase(Locale.US)
                else -> {
                    val macField = item.javaClass.methods.firstOrNull { m ->
                        m.name.equals("getMacAddress", ignoreCase = true) ||
                            m.name.equals("getMac", ignoreCase = true)
                    }
                    val macObj = macField?.invoke(item) ?: return null
                    normalizeMac(macObj.toString())
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun normalizeMac(raw: String): String? {
        val mac = raw.uppercase(Locale.US)
        return mac.takeIf { HotspotClientMerger.isRealMac(it) }
    }

    companion object {
        private const val TAG = "MacAddressResolver"
    }
}
