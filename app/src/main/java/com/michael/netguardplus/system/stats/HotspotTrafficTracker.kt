package com.michael.netguardplus.system.stats

import android.app.usage.NetworkStats
import android.app.usage.NetworkStatsManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkAddress
import android.net.NetworkCapabilities
import android.net.TrafficStats
import android.util.Log
import java.io.File
import java.net.Inet4Address
import java.util.Calendar

private val HOTSPOT_IFACE = Regex("^(ap\\d+|softap\\d+|ap_br_ap\\d+)$")
private val QUALCOMM_WLAN_AP = Regex("^wlan[1-9]\\d*$")

/**
 * Measures tethered hotspot traffic when /proc, sysfs, and per-interface netstats are unavailable.
 * Uses TrafficStats + NetworkStatsManager for the network-stack UID (1073) on the uplink interface.
 */
class HotspotTrafficTracker(context: Context) {

    private val statsManager =
        context.getSystemService(Context.NETWORK_STATS_SERVICE) as? NetworkStatsManager
    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private var sessionBaselineRx = 0L
    private var sessionBaselineTx = 0L
    private var sessionBaselineCaptured = false

    private var lastPollRx = 0L
    private var lastPollTx = 0L
    private var lastPollIface: String? = null
    private var lastPollSource: String? = null

    private var lastMobileRx = -1L
    private var lastMobileTx = -1L
    private var mobileSessionBaselineRx = 0L
    private var mobileSessionBaselineTx = 0L
    private var mobileSessionBaselineCaptured = false

    fun detectUplinkTransport(): Int? {
        val network = connectivityManager.activeNetwork ?: return null
        val caps = connectivityManager.getNetworkCapabilities(network) ?: return null
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ->
                NetworkCapabilities.TRANSPORT_CELLULAR
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ->
                NetworkCapabilities.TRANSPORT_WIFI
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ->
                NetworkCapabilities.TRANSPORT_ETHERNET
            else -> null
        }
    }

    fun clearPollBaseline() {
        lastPollRx = 0L
        lastPollTx = 0L
        lastPollIface = null
        lastPollSource = null
        lastNetstatsWindowEndMs = 0L
        lastMobileRx = -1L
        lastMobileTx = -1L
    }

    fun resetMobileCellularBaseline() {
        val rx = TrafficStats.getMobileRxBytes().normalizeTrafficStat()
        val tx = TrafficStats.getMobileTxBytes().normalizeTrafficStat()
        mobileSessionBaselineRx = rx.coerceAtLeast(0L)
        mobileSessionBaselineTx = tx.coerceAtLeast(0L)
        mobileSessionBaselineCaptured = true
        lastMobileRx = mobileSessionBaselineRx
        lastMobileTx = mobileSessionBaselineTx
        Log.i(
            TAG,
            "Mobile cellular baseline rx=$mobileSessionBaselineRx tx=$mobileSessionBaselineTx"
        )
    }

    fun clearMobileCellularBaseline() {
        mobileSessionBaselineCaptured = false
        mobileSessionBaselineRx = 0L
        mobileSessionBaselineTx = 0L
        lastMobileRx = -1L
        lastMobileTx = -1L
    }

    /**
     * Live mobile-data poll delta — same counters as the dashboard mobile stats.
     * On cellular hotspot, tethered client traffic flows through the mobile interface.
     */
    fun readMobileCellularPollDelta(): TetheringMonitor.TrafficDelta {
        val rx = TrafficStats.getMobileRxBytes().normalizeTrafficStat()
        val tx = TrafficStats.getMobileTxBytes().normalizeTrafficStat()
        if (rx < 0L && tx < 0L) return emptyDelta()

        val safeRx = if (rx >= 0L) rx else lastMobileRx.coerceAtLeast(0L)
        val safeTx = if (tx >= 0L) tx else lastMobileTx.coerceAtLeast(0L)

        if (lastMobileRx < 0L) {
            lastMobileRx = safeRx
            lastMobileTx = safeTx
            return emptyDelta()
        }

        val delta = TetheringMonitor.TrafficDelta(
            rxBytes = (safeRx - lastMobileRx).coerceAtLeast(0L),
            txBytes = (safeTx - lastMobileTx).coerceAtLeast(0L)
        )
        lastMobileRx = safeRx
        lastMobileTx = safeTx
        if (delta.rxBytes > 0L || delta.txBytes > 0L) {
            Log.d(TAG, "Mobile cellular poll delta rx=${delta.rxBytes} tx=${delta.txBytes}")
        }
        return delta
    }

    /** Session usage on cellular hotspot via mobile interface counters. */
    fun readMobileCellularSessionTotals(): TetheringMonitor.TrafficDelta {
        if (!mobileSessionBaselineCaptured) {
            resetMobileCellularBaseline()
        }
        val rx = TrafficStats.getMobileRxBytes().normalizeTrafficStat()
        val tx = TrafficStats.getMobileTxBytes().normalizeTrafficStat()
        val curRx = if (rx >= 0L) rx else mobileSessionBaselineRx
        val curTx = if (tx >= 0L) tx else mobileSessionBaselineTx
        return TetheringMonitor.TrafficDelta(
            rxBytes = (curRx - mobileSessionBaselineRx).coerceAtLeast(0L),
            txBytes = (curTx - mobileSessionBaselineTx).coerceAtLeast(0L)
        )
    }

    private fun Long.normalizeTrafficStat(): Long = if (this >= 0L) this else -1L

    /**
     * Per-poll delta from the best available counter. On mobile-data uplink, prefers network-stack
     * UID counters (same family as live mobile stats) over ap0 interface counters that often stay 0.
     */
    fun readPollDelta(
        preferredIface: String? = null,
        sessionStartMs: Long = 0L
    ): TetheringMonitor.TrafficDelta {
        val uplinkCellular = detectUplinkTransport() == NetworkCapabilities.TRANSPORT_CELLULAR

        if (uplinkCellular) {
            readTrafficStatsForTetherUids(allowZero = true)?.let { snap ->
                return pollDeltaFromSnapshot(
                    snap.rxBytes,
                    snap.txBytes,
                    SOURCE_TETHER_UID,
                    preferredIface
                )
            }
            readCellularUidWindowDelta(sessionStartMs)?.let { return it }
        }

        readInterfaceBytesRealtime(preferredIface, allowZero = !uplinkCellular)?.let { snap ->
            return pollDeltaFromSnapshot(
                snap.rxBytes,
                snap.txBytes,
                SOURCE_IFACE,
                preferredIface
            )
        }

        val fallback = readRawTotalsSnapshot(preferredIface, uplinkCellular)
        return pollDeltaFromSnapshot(
            fallback.rxBytes,
            fallback.txBytes,
            fallback.source,
            preferredIface
        )
    }

    private var lastNetstatsWindowEndMs = 0L

    /** Sliding-window delta for tether UID on cellular (matches NetworkStatsManager polling). */
    private fun readCellularUidWindowDelta(sessionStartMs: Long): TetheringMonitor.TrafficDelta? {
        val endMs = System.currentTimeMillis()
        val startMs = when {
            lastNetstatsWindowEndMs > sessionStartMs -> lastNetstatsWindowEndMs
            sessionStartMs > 0L -> sessionStartMs
            else -> endMs - 5_000L
        }
        if (endMs - startMs < 50L) return null

        val delta = queryTetheringUidNetstats(startMs, endMs, cellularOnly = true) ?: return null
        lastNetstatsWindowEndMs = endMs
        if (delta.rxBytes > 0L || delta.txBytes > 0L) {
            Log.d(TAG, "Cellular UID window delta rx=${delta.rxBytes} tx=${delta.txBytes}")
        }
        return delta
    }

    private fun pollDeltaFromSnapshot(
        rxBytes: Long,
        txBytes: Long,
        source: String,
        preferredIface: String?
    ): TetheringMonitor.TrafficDelta {
        val ifaceChanged = preferredIface != null && preferredIface != lastPollIface
        val sourceChanged = lastPollSource != null && source != lastPollSource

        if (lastPollSource == null || ifaceChanged || sourceChanged) {
            lastPollRx = rxBytes
            lastPollTx = txBytes
            lastPollIface = preferredIface
            lastPollSource = source
            if (ifaceChanged || sourceChanged) {
                Log.i(
                    TAG,
                    "Poll baseline reset (iface=$preferredIface source=$source rx=$rxBytes tx=$txBytes)"
                )
            }
            return emptyDelta()
        }

        val delta = TetheringMonitor.TrafficDelta(
            rxBytes = (rxBytes - lastPollRx).coerceAtLeast(0L),
            txBytes = (txBytes - lastPollTx).coerceAtLeast(0L)
        )
        lastPollRx = rxBytes
        lastPollTx = txBytes
        if (delta.rxBytes > 0L || delta.txBytes > 0L) {
            Log.d(TAG, "Poll delta rx=${delta.rxBytes} tx=${delta.txBytes} via $source")
        }
        return delta
    }

    fun resetSessionBaseline(preferredIface: String?) {
        val snap = readRawTotalsSnapshot(preferredIface)
        sessionBaselineRx = snap.rxBytes
        sessionBaselineTx = snap.txBytes
        sessionBaselineCaptured = true
        Log.i(TAG, "Hotspot session baseline on $preferredIface rx=$sessionBaselineRx tx=$sessionBaselineTx")
    }

    fun clearSessionBaseline() {
        sessionBaselineCaptured = false
        sessionBaselineRx = 0L
        sessionBaselineTx = 0L
        clearPollBaseline()
        clearMobileCellularBaseline()
    }

    fun ensureSessionBaseline(preferredIface: String) {
        if (!sessionBaselineCaptured) {
            resetSessionBaseline(preferredIface)
        }
    }

    fun readHotspotTotalsSince(
        sessionStartMs: Long,
        preferredIface: String? = null
    ): TetheringMonitor.TrafficDelta {
        if (detectUplinkTransport() == NetworkCapabilities.TRANSPORT_CELLULAR) {
            val mobile = readMobileCellularSessionTotals()
            Log.d(TAG, "Session totals (mobile cellular) rx=${mobile.rxBytes} tx=${mobile.txBytes}")
            return mobile
        }

        if (!sessionBaselineCaptured) {
            resetSessionBaseline(preferredIface)
        }

        val current = readRawTotalsSnapshot(preferredIface)
        val sessionRx = (current.rxBytes - sessionBaselineRx).coerceAtLeast(0L)
        val sessionTx = (current.txBytes - sessionBaselineTx).coerceAtLeast(0L)
        Log.d(TAG, "Session totals rx=$sessionRx tx=$sessionTx (raw rx=${current.rxBytes} tx=${current.txBytes})")
        return TetheringMonitor.TrafficDelta(rxBytes = sessionRx, txBytes = sessionTx)
    }

    private data class RawTotalsSnapshot(
        val rxBytes: Long,
        val txBytes: Long,
        val source: String
    )

    private fun readRawTotalsSnapshot(
        preferredIface: String? = null,
        uplinkCellular: Boolean = detectUplinkTransport() == NetworkCapabilities.TRANSPORT_CELLULAR
    ): RawTotalsSnapshot {
        if (uplinkCellular) {
            readTrafficStatsForTetherUids(allowZero = true)?.let { stats ->
                Log.d(TAG, "Raw totals from tether UID TrafficStats rx=${stats.rxBytes} tx=${stats.txBytes}")
                return RawTotalsSnapshot(stats.rxBytes, stats.txBytes, SOURCE_TETHER_UID)
            }
        }

        readInterfaceBytesRealtime(preferredIface, allowZero = !uplinkCellular)?.let { stats ->
            Log.d(TAG, "Raw totals from interface counters rx=${stats.rxBytes} tx=${stats.txBytes}")
            return RawTotalsSnapshot(stats.rxBytes, stats.txBytes, SOURCE_IFACE)
        }

        val endMs = System.currentTimeMillis()
        val startMs = startOfTodayMs()

        if (uplinkCellular) {
            queryTetheringUidNetstats(startMs, endMs, cellularOnly = true)?.let { stats ->
                if (stats.hasTraffic()) {
                    Log.d(TAG, "Raw totals from cellular tether UID rx=${stats.rxBytes} tx=${stats.txBytes}")
                    return RawTotalsSnapshot(stats.rxBytes, stats.txBytes, SOURCE_CELLULAR_UID)
                }
            }
            queryCellularByInterface(startMs, endMs, preferredIface)?.let { stats ->
                if (stats.hasTraffic()) {
                    Log.d(TAG, "Raw totals from cellular iface $preferredIface rx=${stats.rxBytes} tx=${stats.txBytes}")
                    return RawTotalsSnapshot(stats.rxBytes, stats.txBytes, SOURCE_CELLULAR_IFACE)
                }
            }
        }

        readTrafficStatsForTetherUids(allowZero = false)?.let { stats ->
            Log.d(TAG, "Raw totals from TrafficStats rx=${stats.rxBytes} tx=${stats.txBytes}")
            return RawTotalsSnapshot(stats.rxBytes, stats.txBytes, SOURCE_TETHER_UID)
        }

        queryTetheringUidNetstats(startMs, endMs, cellularOnly = false)?.let { stats ->
            if (stats.hasTraffic()) {
                Log.d(TAG, "Raw totals from tether UID netstats rx=${stats.rxBytes} tx=${stats.txBytes}")
                return RawTotalsSnapshot(stats.rxBytes, stats.txBytes, SOURCE_TETHER_UID)
            }
        }

        queryTaggedTetherNetstats(startMs, endMs)?.let { stats ->
            if (stats.hasTraffic()) {
                Log.d(TAG, "Raw totals from tagged netstats rx=${stats.rxBytes} tx=${stats.txBytes}")
                return RawTotalsSnapshot(stats.rxBytes, stats.txBytes, SOURCE_TAGGED)
            }
        }

        val iface = preferredIface ?: detectTetherInterfaceName()
        val byInterface = queryWifiByInterface(startMs, endMs, iface)
        if (byInterface.hasTraffic()) {
            Log.d(TAG, "Raw totals from interface $iface rx=${byInterface.rxBytes} tx=${byInterface.txBytes}")
            return RawTotalsSnapshot(byInterface.rxBytes, byInterface.txBytes, SOURCE_NETSTATS_IFACE)
        }

        Log.d(TAG, "Raw totals unavailable — returning zero")
        return RawTotalsSnapshot(0L, 0L, SOURCE_NONE)
    }

    /**
     * Real-time counters from sysfs or INetworkManagementService (updates every poll).
     */
    private fun readInterfaceBytesRealtime(
        preferredIface: String?,
        allowZero: Boolean = true
    ): TetheringMonitor.TrafficDelta? {
        val iface = preferredIface ?: detectTetherInterfaceName() ?: return null

        readInterfaceBytesFromSysfs(iface)?.let { (rx, tx) ->
            return toClientDeltaIfValid(rx, tx, allowZero)
        }

        readInterfaceBytesViaTrafficStats(iface, allowZero)?.let { return it }

        readInterfaceBytesViaNetworkManagement(iface, allowZero)?.let { return it }

        return readInterfaceBytesViaNetstatsService(iface, allowZero)
    }

    private fun toClientDeltaIfValid(
        ifaceRx: Long,
        ifaceTx: Long,
        allowZero: Boolean
    ): TetheringMonitor.TrafficDelta? {
        if (ifaceRx < 0L && ifaceTx < 0L) return null
        val rx = ifaceRx.coerceAtLeast(0L)
        val tx = ifaceTx.coerceAtLeast(0L)
        if (!allowZero && rx == 0L && tx == 0L) return null
        return toClientDelta(rx, tx)
    }

    private fun readInterfaceBytesViaTrafficStats(
        iface: String,
        allowZero: Boolean = true
    ): TetheringMonitor.TrafficDelta? {
        return try {
            val rx = TrafficStats::class.java.getMethod("getRxBytes", String::class.java)
                .invoke(null, iface) as Long
            val tx = TrafficStats::class.java.getMethod("getTxBytes", String::class.java)
                .invoke(null, iface) as Long
            toClientDeltaIfValid(rx, tx, allowZero)
        } catch (e: Exception) {
            Log.d(TAG, "TrafficStats iface methods unavailable for $iface", e)
            null
        }
    }

    private fun readInterfaceBytesViaNetstatsService(
        iface: String,
        allowZero: Boolean = true
    ): TetheringMonitor.TrafficDelta? {
        return try {
            val serviceManager = Class.forName("android.os.ServiceManager")
            val binder = serviceManager.getMethod("getService", String::class.java)
                .invoke(null, "netstats") as? android.os.IBinder ?: return null
            val stubClass = Class.forName("android.net.INetworkStatsService\$Stub")
            val connector = stubClass.getMethod("asInterface", android.os.IBinder::class.java)
                .invoke(null, binder) ?: return null

            for (method in connector.javaClass.methods) {
                val name = method.name.lowercase()
                if (!name.contains("iface") && !name.contains("interface")) continue
                if (method.parameterTypes.size != 1 || method.parameterTypes[0] != String::class.java) continue
                try {
                    val value = method.invoke(connector, iface) as? Long ?: continue
                    if (value >= 0L) {
                        Log.i(TAG, "NetstatsService.${method.name}($iface)=$value")
                    }
                } catch (_: Exception) {
                    // try next
                }
            }

            val rxMethod = connector.javaClass.methods.firstOrNull {
                it.name == "getIfaceRxBytes" && it.parameterTypes.contentEquals(arrayOf(String::class.java))
            }
            val txMethod = connector.javaClass.methods.firstOrNull {
                it.name == "getIfaceTxBytes" && it.parameterTypes.contentEquals(arrayOf(String::class.java))
            }
            if (rxMethod != null && txMethod != null) {
                val rx = rxMethod.invoke(connector, iface) as Long
                val tx = txMethod.invoke(connector, iface) as Long
                toClientDeltaIfValid(rx, tx, allowZero)?.let { return it }
            }
            null
        } catch (e: Exception) {
            Log.d(TAG, "INetworkStatsService unavailable for $iface", e)
            null
        }
    }

    private fun readInterfaceBytesFromSysfs(iface: String): Pair<Long, Long>? {
        return try {
            val rx = File("/sys/class/net/$iface/statistics/rx_bytes").readText().trim().toLongOrNull()
            val tx = File("/sys/class/net/$iface/statistics/tx_bytes").readText().trim().toLongOrNull()
            if (rx != null && tx != null) rx to tx else null
        } catch (_: Exception) {
            null
        }
    }

    private fun readInterfaceBytesViaNetworkManagement(
        iface: String,
        allowZero: Boolean = true
    ): TetheringMonitor.TrafficDelta? {
        return try {
            val serviceManager = Class.forName("android.os.ServiceManager")
            val binder = serviceManager.getMethod("getService", String::class.java)
                .invoke(null, "network_management") as? android.os.IBinder ?: return null
            val stubClass = Class.forName("android.os.INetworkManagementService\$Stub")
            val connector = stubClass.getMethod("asInterface", android.os.IBinder::class.java)
                .invoke(null, binder) ?: return null

            val rx = connector.javaClass.getMethod("getInterfaceRxBytes", String::class.java)
                .invoke(connector, iface) as Long
            val tx = connector.javaClass.getMethod("getInterfaceTxBytes", String::class.java)
                .invoke(connector, iface) as Long

            toClientDeltaIfValid(rx, tx, allowZero)
        } catch (e: Exception) {
            Log.d(TAG, "NetworkManagement stats unavailable for $iface", e)
            null
        }
    }

    private fun readTrafficStatsForTetherUids(allowZero: Boolean = false): TetheringMonitor.TrafficDelta? {
        var rx = 0L
        var tx = 0L
        var any = false

        for (uid in TETHERING_UIDS) {
            val uidRx = TrafficStats.getUidRxBytes(uid)
            val uidTx = TrafficStats.getUidTxBytes(uid)
            if (uidRx != UNSUPPORTED && uidRx >= 0L) {
                rx += uidRx
                any = true
            }
            if (uidTx != UNSUPPORTED && uidTx >= 0L) {
                tx += uidTx
                any = true
            }
        }

        return if (any) {
            val result = TetheringMonitor.TrafficDelta(rxBytes = tx, txBytes = rx)
            if (allowZero || result.hasTraffic()) result else null
        } else {
            null
        }
    }

    private fun queryTetheringUidNetstats(
        startMs: Long,
        endMs: Long,
        cellularOnly: Boolean = false
    ): TetheringMonitor.TrafficDelta? {
        val manager = statsManager ?: return null

        var rx = 0L
        var tx = 0L
        var buckets = 0

        val transports = if (cellularOnly) {
            intArrayOf(NetworkCapabilities.TRANSPORT_CELLULAR)
        } else {
            UPLINK_TRANSPORTS
        }

        for (uid in TETHERING_UIDS) {
            for (transport in transports) {
                try {
                    manager.queryDetailsForUid(transport, null, startMs, endMs, uid).use { stats ->
                        val bucket = NetworkStats.Bucket()
                        while (stats.hasNextBucket()) {
                            stats.getNextBucket(bucket)
                            rx += bucket.rxBytes
                            tx += bucket.txBytes
                            buckets++
                        }
                    }
                } catch (e: Exception) {
                    Log.d(TAG, "UID $uid transport $transport query skipped: ${e.message}")
                }
            }
        }

        return if (buckets > 0 && (rx > 0L || tx > 0L)) {
            TetheringMonitor.TrafficDelta(rxBytes = tx, txBytes = rx)
        } else {
            null
        }
    }

    private fun queryTaggedTetherNetstats(startMs: Long, endMs: Long): TetheringMonitor.TrafficDelta? {
        val manager = statsManager ?: return null
        var rx = 0L
        var tx = 0L
        var buckets = 0

        for (tag in TETHERING_TAGS) {
            for (transport in UPLINK_TRANSPORTS) {
                try {
                    val method = manager.javaClass.getMethod(
                        "queryDetailsForUidTag",
                        Int::class.javaPrimitiveType,
                        String::class.java,
                        Long::class.javaPrimitiveType,
                        Long::class.javaPrimitiveType,
                        Int::class.javaPrimitiveType,
                        Int::class.javaPrimitiveType
                    )
                    method.invoke(manager, transport, null, startMs, endMs, NETWORK_STACK_UID, tag)
                        .let { it as? NetworkStats }
                        ?.use { stats ->
                            val bucket = NetworkStats.Bucket()
                            while (stats.hasNextBucket()) {
                                stats.getNextBucket(bucket)
                                rx += bucket.rxBytes
                                tx += bucket.txBytes
                                buckets++
                            }
                        }
                } catch (e: Exception) {
                    Log.d(TAG, "Tagged query tag=$tag transport=$transport skipped: ${e.message}")
                }
            }
        }

        return if (buckets > 0 && (rx > 0L || tx > 0L)) {
            TetheringMonitor.TrafficDelta(rxBytes = tx, txBytes = rx)
        } else {
            null
        }
    }

    private fun detectTetherInterfaceName(): String? {
        for (network in connectivityManager.allNetworks) {
            val linkProperties = connectivityManager.getLinkProperties(network) ?: continue
            val iface = linkProperties.interfaceName ?: continue
            if (isHotspotInterfaceName(iface, linkProperties.linkAddresses)) {
                return iface
            }
        }
        return null
    }

    private fun queryWifiByInterface(
        startMs: Long,
        endMs: Long,
        preferredIface: String?
    ): TetheringMonitor.TrafficDelta {
        val manager = statsManager ?: return emptyDelta()

        var ifaceRx = 0L
        var ifaceTx = 0L
        var bucketCount = 0
        var nullIfaceRx = 0L
        var nullIfaceTx = 0L

        try {
            manager.queryDetails(
                NetworkCapabilities.TRANSPORT_WIFI,
                null,
                startMs,
                endMs
            ).use { stats ->
                val bucket = NetworkStats.Bucket()
                while (stats.hasNextBucket()) {
                    stats.getNextBucket(bucket)
                    val iface = bucket.readInterfaceName()
                    if (iface != null && isHotspotInterface(iface, preferredIface)) {
                        ifaceRx += bucket.rxBytes
                        ifaceTx += bucket.txBytes
                        bucketCount++
                    } else if (iface == null) {
                        nullIfaceRx += bucket.rxBytes
                        nullIfaceTx += bucket.txBytes
                    }
                }
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "Usage access required for hotspot Wi-Fi stats", e)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to query hotspot Wi-Fi stats", e)
        }

        if (ifaceRx > 0L || ifaceTx > 0L) {
            return toClientDelta(ifaceRx, ifaceTx)
        }

        if (preferredIface != null && (nullIfaceRx > 0L || nullIfaceTx > 0L)) {
            return toClientDelta(nullIfaceRx, nullIfaceTx)
        }

        return emptyDelta()
    }

    private fun queryCellularByInterface(
        startMs: Long,
        endMs: Long,
        preferredIface: String?
    ): TetheringMonitor.TrafficDelta? {
        val manager = statsManager ?: return null
        val iface = preferredIface ?: detectTetherInterfaceName() ?: return null

        var ifaceRx = 0L
        var ifaceTx = 0L
        var bucketCount = 0

        try {
            manager.queryDetails(
                NetworkCapabilities.TRANSPORT_CELLULAR,
                null,
                startMs,
                endMs
            ).use { stats ->
                val bucket = NetworkStats.Bucket()
                while (stats.hasNextBucket()) {
                    stats.getNextBucket(bucket)
                    val bucketIface = bucket.readInterfaceName()
                    if (bucketIface == iface || (bucketIface == null && preferredIface != null)) {
                        ifaceRx += bucket.rxBytes
                        ifaceTx += bucket.txBytes
                        bucketCount++
                    }
                }
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "Usage access required for cellular iface stats", e)
        } catch (e: Exception) {
            Log.w(TAG, "Failed cellular iface query for $iface", e)
        }

        return if (bucketCount > 0 && (ifaceRx > 0L || ifaceTx > 0L)) {
            toClientDelta(ifaceRx, ifaceTx)
        } else {
            null
        }
    }

    private fun isHotspotInterface(iface: String, preferredIface: String?): Boolean {
        if (preferredIface != null) return iface == preferredIface
        return iface.matches(HOTSPOT_IFACE) || iface.matches(QUALCOMM_WLAN_AP)
    }

    private fun toClientDelta(ifaceRx: Long, ifaceTx: Long): TetheringMonitor.TrafficDelta {
        return TetheringMonitor.TrafficDelta(rxBytes = ifaceTx, txBytes = ifaceRx)
    }

    private fun TetheringMonitor.TrafficDelta.hasTraffic(): Boolean {
        return rxBytes > 0L || txBytes > 0L
    }

    private fun emptyDelta() = TetheringMonitor.TrafficDelta(0L, 0L)

    private fun startOfTodayMs(): Long {
        return Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }

    companion object {
        private const val TAG = "HotspotTrafficTracker"
        private const val NETWORK_STACK_UID = 1073
        private const val UNSUPPORTED = -1L
        private const val SOURCE_IFACE = "iface"
        private const val SOURCE_TETHER_UID = "tether_uid"
        private const val SOURCE_CELLULAR_UID = "cellular_tether_uid"
        private const val SOURCE_CELLULAR_IFACE = "cellular_iface"
        private const val SOURCE_MOBILE_CELLULAR = "mobile_cellular"
        private const val SOURCE_TAGGED = "tagged"
        private const val SOURCE_NETSTATS_IFACE = "netstats_iface"
        private const val SOURCE_NONE = "none"
        private val TETHERING_UIDS = intArrayOf(NETWORK_STACK_UID, 1021, 1000)
        private val UPLINK_TRANSPORTS = intArrayOf(
            NetworkCapabilities.TRANSPORT_WIFI,
            NetworkCapabilities.TRANSPORT_CELLULAR
        )
        // Tags seen on Oppo/Qualcomm for network-stack forwarded traffic.
        private val TETHERING_TAGS = intArrayOf(
            0x0,
            0xfffffe01.toInt(),
            0xffffff81.toInt(),
            0xffffff82.toInt()
        )
    }
}

fun isHotspotInterfaceName(iface: String, linkAddresses: List<LinkAddress> = emptyList()): Boolean {
    if (iface.matches(HOTSPOT_IFACE)) return true
    if (iface.matches(QUALCOMM_WLAN_AP)) {
        if (linkAddresses.isEmpty()) return true
        return linkAddresses.any { addr ->
            val ip = (addr.address as? Inet4Address)?.hostAddress ?: return@any false
            ip.startsWith("192.168.") || ip.startsWith("10.") || ip.startsWith("172.")
        }
    }
    return false
}

private fun NetworkStats.Bucket.readInterfaceName(): String? = runCatching {
    javaClass.getMethod("getInterface").invoke(this) as String
}.getOrNull() ?: runCatching {
    javaClass.getMethod("getIface").invoke(this) as String
}.getOrNull()
