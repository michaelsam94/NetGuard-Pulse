package com.michael.netguardplus.system.hotspot

import com.michael.netguardplus.system.stats.TetheringMonitor
import com.michael.netguardplus.system.vpn.VpnRoutePlanner

object HotspotClientMerger {

    fun merge(
        controllerClients: List<TetheringMonitor.DiscoveredClient>,
        scannedClients: List<TetheringMonitor.DiscoveredClient>
    ): List<TetheringMonitor.DiscoveredClient> {
        val byMac = linkedMapOf<String, TetheringMonitor.DiscoveredClient>()

        fun upsert(client: TetheringMonitor.DiscoveredClient) {
            val mac = client.macAddress.trim()
            if (mac.isBlank()) return
            val existing = byMac[mac]
            byMac[mac] = when {
                existing == null -> client
                hasValidIp(client.ipAddress) && !hasValidIp(existing.ipAddress) ->
                    client.copy(deviceName = pickName(existing.deviceName, client.deviceName))
                hasValidIp(client.ipAddress) ->
                    existing.copy(
                        ipAddress = client.ipAddress,
                        deviceName = pickName(existing.deviceName, client.deviceName)
                    )
                else -> existing.copy(deviceName = pickName(existing.deviceName, client.deviceName))
            }
        }

        controllerClients.forEach(::upsert)
        scannedClients.forEach(::upsert)

        val merged = byMac.values.toMutableList()
        val missingIp = merged.filter { !hasValidIp(it.ipAddress) }
        val orphanScanned = scannedClients.filter { scanned ->
            hasValidIp(scanned.ipAddress) && controllerClients.none { it.macAddress == scanned.macAddress }
        }

        if (missingIp.size == 1 && orphanScanned.size == 1) {
            val targetMac = missingIp.first().macAddress
            val idx = merged.indexOfFirst { it.macAddress == targetMac }
            if (idx >= 0) {
                val orphan = orphanScanned.first()
                merged[idx] = merged[idx].copy(
                    ipAddress = orphan.ipAddress,
                    deviceName = pickName(merged[idx].deviceName, orphan.deviceName)
                )
            }
        }

        return merged
            .groupBy { client ->
                if (hasValidIp(client.ipAddress)) client.ipAddress else client.macAddress
            }
            .values
            .map { group -> group.maxBy { macPriority(it.macAddress) } }
    }

    fun hasValidIp(ip: String): Boolean = VpnRoutePlanner.isRoutableIpv4(ip)

    fun isRealMac(mac: String): Boolean {
        if (mac.isBlank() || mac.startsWith("IP-")) return false
        val parts = mac.split(':')
        if (parts.size != 6) return false
        return parts.all { part ->
            part.length == 2 && part.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }
        }
    }

    private fun macPriority(mac: String): Int = when {
        isRealMac(mac) -> 2
        mac.startsWith("IP-") -> 1
        else -> 0
    }

    private fun pickName(existing: String, incoming: String): String {
        return when {
            incoming.isNotBlank() && incoming != "Connected Device" -> incoming
            existing.isNotBlank() -> existing
            else -> incoming
        }
    }
}
