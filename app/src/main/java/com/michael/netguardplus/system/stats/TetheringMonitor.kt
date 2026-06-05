package com.michael.netguardplus.system.stats

import android.util.Log
import java.io.File
import java.net.NetworkInterface
import java.util.Locale

/**
 * Reads tether interface traffic and supplements client discovery when TetheringManager is unavailable.
 */
class TetheringMonitor {

    data class DiscoveredClient(
        val macAddress: String,
        val ipAddress: String,
        val deviceName: String
    )

    data class TrafficDelta(val rxBytes: Long, val txBytes: Long)

    private val tetherInterfacePattern = Regex("^(ap\\d+|softap\\d+|ap_br_ap\\d+|rndis\\d+)$")

    private var lastIfaceRx = 0L
    private var lastIfaceTx = 0L
    private var lastIfaceReadMs = 0L

    fun scanConnectedClients(): List<DiscoveredClient> {
        if (procAccessBlocked) return emptyList()
        val fromArp = scanArpOnTetherInterfaces()
        if (fromArp.isNotEmpty()) return fromArp
        return scanIpNeighOnTetherInterfaces()
    }

    /** Looks up a tether client's hardware MAC from ARP / neighbor tables. */
    fun resolveMacByIp(ip: String): String? {
        if (!isPrivateIp(ip)) return null
        return scanConnectedClients()
            .firstOrNull { client -> client.ipAddress == ip && isRealHardwareMac(client.macAddress) }
            ?.macAddress
    }

    private fun isRealHardwareMac(mac: String): Boolean {
        if (mac.isBlank() || mac.startsWith("IP-")) return false
        val parts = mac.split(':')
        if (parts.size != 6) return false
        return parts.all { part ->
            part.length == 2 && part.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }
        }
    }

    fun resolveActiveTetherInterfaces(): List<String> {
        val fromNi = enumerateHotspotNetworkInterfaces()
        if (fromNi.isNotEmpty()) return fromNi

        if (procAccessBlocked) return emptyList()

        val fromSys = try {
            File("/sys/class/net").list()?.filter { name ->
                name.matches(tetherInterfacePattern) &&
                    File("/sys/class/net/$name/operstate").exists()
            }.orEmpty()
        } catch (e: Exception) {
            noteAccessFailure("Could not list tether interfaces from sysfs", e)
            emptyList()
        }
        if (fromSys.isNotEmpty()) return fromSys
        return readTetherInterfacesFromProcNetDev()
    }

    private fun enumerateHotspotNetworkInterfaces(): List<String> {
        return try {
            NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
                .filter { ni ->
                    if (!ni.isUp || ni.isLoopback) return@filter false
                    val name = ni.name ?: return@filter false
                    isHotspotInterfaceName(name) || name.matches(tetherInterfacePattern)
                }
                .mapNotNull { it.name }
        } catch (e: Exception) {
            noteAccessFailure("Could not enumerate hotspot interfaces", e)
            emptyList()
        }
    }

    fun readTetherInterfaceDelta(): TrafficDelta {
        val now = System.currentTimeMillis()
        var totalIfaceRx = 0L
        var totalIfaceTx = 0L

        for (iface in resolveActiveTetherInterfaces()) {
            val stats = readInterfaceBytes(iface)
            totalIfaceRx += stats.first
            totalIfaceTx += stats.second
        }

        if (lastIfaceReadMs == 0L) {
            lastIfaceRx = totalIfaceRx
            lastIfaceTx = totalIfaceTx
            lastIfaceReadMs = now
            return TrafficDelta(0L, 0L)
        }

        val deltaIfaceRx = (totalIfaceRx - lastIfaceRx).coerceAtLeast(0L)
        val deltaIfaceTx = (totalIfaceTx - lastIfaceTx).coerceAtLeast(0L)
        lastIfaceRx = totalIfaceRx
        lastIfaceTx = totalIfaceTx
        lastIfaceReadMs = now

        // ap0 rx = data from tethered devices (client upload); ap0 tx = data to clients (client download)
        val clientDownload = deltaIfaceTx
        val clientUpload = deltaIfaceRx
        if (clientDownload > 0 || clientUpload > 0) {
            Log.d(TAG, "Tether delta: download=$clientDownload upload=$clientUpload")
        }
        return TrafficDelta(rxBytes = clientDownload, txBytes = clientUpload)
    }

    fun attributeTrafficToClients(
        clients: List<DiscoveredClient>,
        delta: TrafficDelta
    ): Map<String, TrafficDelta> {
        if (clients.isEmpty() || (delta.rxBytes == 0L && delta.txBytes == 0L)) {
            return emptyMap()
        }

        if (clients.size == 1) {
            val client = clients.first()
            return mapOf(client.ipAddress to delta)
        }

        val clientIps = clients.map { it.ipAddress }.toSet()
        val connectionCounts = countTcpConnectionsByIp(clientIps)
        val totalConnections = connectionCounts.values.sum().coerceAtLeast(clients.size)

        return clients.associate { client ->
            val weight = connectionCounts[client.ipAddress]?.coerceAtLeast(1) ?: 1
            val share = weight.toDouble() / totalConnections
            client.ipAddress to TrafficDelta(
                rxBytes = (delta.rxBytes * share).toLong(),
                txBytes = (delta.txBytes * share).toLong()
            )
        }
    }

    fun resetInterfaceBaseline() {
        lastIfaceRx = 0L
        lastIfaceTx = 0L
        lastIfaceReadMs = 0L
    }

    private fun scanArpOnTetherInterfaces(): List<DiscoveredClient> {
        val tetherIfaces = resolveActiveTetherInterfaces().toSet()
        if (tetherIfaces.isEmpty()) return emptyList()

        return try {
            File("/proc/net/arp").readLines()
                .drop(1)
                .mapNotNull { line -> parseNeighborLine(line, tetherIfaces) }
                .distinctBy { it.macAddress }
        } catch (e: Exception) {
            noteAccessFailure("ARP scan unavailable", e)
            emptyList()
        }
    }

    private fun scanIpNeighOnTetherInterfaces(): List<DiscoveredClient> {
        val results = mutableListOf<DiscoveredClient>()
        for (iface in resolveActiveTetherInterfaces()) {
            try {
                val process = ProcessBuilder("ip", "neigh", "show", "dev", iface)
                    .redirectErrorStream(true)
                    .start()
                val output = process.inputStream.bufferedReader().readText()
                process.waitFor()
                output.lineSequence().forEach { line ->
                    parseIpNeighLine(line)?.let { results.add(it) }
                }
            } catch (e: Exception) {
                noteAccessFailure("ip neigh unavailable for $iface", e)
            }
        }
        return results.distinctBy { it.macAddress }
    }

    private fun parseNeighborLine(line: String, tetherIfaces: Set<String>): DiscoveredClient? {
        val parts = line.trim().split(Regex("\\s+"))
        if (parts.size < 6) return null
        val ip = parts[0]
        val flags = parts[2].toIntOrNull() ?: return null
        val mac = parts[3].uppercase(Locale.US)
        val device = parts[5]
        if (device !in tetherIfaces) return null
        if (flags and 0x2 == 0) return null
        if (mac == "00:00:00:00:00:00") return null
        if (!isPrivateIp(ip)) return null
        return DiscoveredClient(mac, ip, resolveDeviceName(mac))
    }

    private fun parseIpNeighLine(line: String): DiscoveredClient? {
        val parts = line.trim().split(Regex("\\s+"))
        if (parts.size < 4) return null
        val ip = parts[0]
        if (!isPrivateIp(ip)) return null

        val macIndex = parts.indexOf("lladdr").takeIf { it >= 0 }?.plus(1) ?: return null
        if (macIndex >= parts.size) return null
        val mac = parts[macIndex].uppercase(Locale.US)
        if (!mac.contains(':') || mac == "00:00:00:00:00:00") return null

        val state = parts.lastOrNull()?.uppercase() ?: ""
        if (state in setOf("FAILED", "INCOMPLETE")) return null

        return DiscoveredClient(mac, ip, resolveDeviceName(mac))
    }

    private fun isPrivateIp(ip: String): Boolean {
        if (ip.startsWith("10.")) return true
        if (ip.startsWith("192.168.")) return true
        if (ip.startsWith("172.")) {
            val second = ip.substringAfter("172.").substringBefore('.').toIntOrNull() ?: return false
            return second in 16..31
        }
        return false
    }

    private fun readInterfaceBytes(iface: String): Pair<Long, Long> {
        readInterfaceBytesFromProcNetDev(iface)?.let { return it }

        return try {
            val rx = File("/sys/class/net/$iface/statistics/rx_bytes").readText().trim().toLongOrNull() ?: 0L
            val tx = File("/sys/class/net/$iface/statistics/tx_bytes").readText().trim().toLongOrNull() ?: 0L
            rx to tx
        } catch (e: Exception) {
            0L to 0L
        }
    }

    private fun readInterfaceBytesFromProcNetDev(iface: String): Pair<Long, Long>? {
        return try {
            for (line in File("/proc/net/dev").readLines()) {
                val colonIdx = line.indexOf(':')
                if (colonIdx < 0) continue
                val name = line.substring(0, colonIdx).trim()
                if (name != iface) continue
                val stats = line.substring(colonIdx + 1).trim().split(Regex("\\s+"))
                if (stats.size < 9) return null
                val rx = stats[0].toLongOrNull() ?: 0L
                val tx = stats[8].toLongOrNull() ?: 0L
                return rx to tx
            }
            null
        } catch (e: Exception) {
            noteAccessFailure("Could not read /proc/net/dev for $iface", e)
            null
        }
    }

    private fun readTetherInterfacesFromProcNetDev(): List<String> {
        return try {
            File("/proc/net/dev").readLines()
                .mapNotNull { line ->
                    val colonIdx = line.indexOf(':')
                    if (colonIdx < 0) return@mapNotNull null
                    line.substring(0, colonIdx).trim()
                }
                .filter { it.matches(tetherInterfacePattern) }
        } catch (e: Exception) {
            noteAccessFailure("Could not list tether interfaces from /proc/net/dev", e)
            emptyList()
        }
    }

    private fun countTcpConnectionsByIp(clientIps: Set<String>): Map<String, Int> {
        val counts = mutableMapOf<String, Int>()
        clientIps.forEach { counts[it] = 0 }

        listOf("/proc/net/tcp", "/proc/net/tcp6").forEach { path ->
            try {
                File(path).readLines().drop(1).forEach { line ->
                    val parts = line.trim().split(Regex("\\s+"))
                    if (parts.size < 3) return@forEach
                    val localIp = decodeProcNetIp(parts[1].substringBefore(':'))
                    val remoteIp = decodeProcNetIp(parts[2].substringBefore(':'))
                    if (localIp in clientIps) counts[localIp] = counts.getOrDefault(localIp, 0) + 1
                    if (remoteIp in clientIps) counts[remoteIp] = counts.getOrDefault(remoteIp, 0) + 1
                }
            } catch (e: Exception) {
                noteAccessFailure("Could not read $path", e)
            }
        }
        return counts
    }

    private fun decodeProcNetIp(hexIp: String): String {
        if (hexIp.length != 8) return hexIp
        return try {
            val b1 = hexIp.substring(6, 8).toInt(16)
            val b2 = hexIp.substring(4, 6).toInt(16)
            val b3 = hexIp.substring(2, 4).toInt(16)
            val b4 = hexIp.substring(0, 2).toInt(16)
            "$b1.$b2.$b3.$b4"
        } catch (e: Exception) {
            hexIp
        }
    }

    private fun resolveDeviceName(mac: String): String {
        val prefix = mac.replace(":", "").take(6).uppercase(Locale.US)
        return OUI_NAMES[prefix] ?: "Device ${mac.takeLast(8).replace(":", "")}"
    }

    companion object {
        private const val TAG = "TetheringMonitor"

        @Volatile
        private var procAccessBlocked = false

        private fun noteAccessFailure(message: String, e: Exception) {
            if (isProcAccessDenied(e)) {
                if (!procAccessBlocked) {
                    procAccessBlocked = true
                    Log.i(
                        TAG,
                        "$message — /proc and sysfs access blocked on this device; " +
                            "using NetworkInterface and LocalNetworkClientScanner instead"
                    )
                }
            } else {
                Log.d(TAG, "$message: ${e.message}")
            }
        }

        private fun isProcAccessDenied(e: Exception): Boolean {
            val messages = sequenceOf(e.message, e.cause?.message).filterNotNull()
            return messages.any { it.contains("EACCES", ignoreCase = true) || it.contains("Permission denied", ignoreCase = true) }
        }

        private val OUI_NAMES = mapOf(
            "3CD0F8" to "Apple Device",
            "D4A33D" to "Apple Device",
            "A4B197" to "Samsung Device",
            "001A11" to "Google Device",
            "F4F5D8" to "Google Device",
            "001B44" to "Apple Device",
            "002608" to "Apple Device"
        )
    }
}
