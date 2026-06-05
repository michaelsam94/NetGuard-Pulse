package com.michael.netguardplus.system.vpn

/**
 * Builds VPN capture routes for phone DNS filtering and optional per-client
 * hotspot enforcement (/32 routes only for explicitly blocked clients).
 *
 * Hotspot gateway addresses are intentionally excluded: routing the gateway into
 * the TUN hijacks dnsmasq and breaks browsing for all tethered clients.
 */
object VpnRoutePlanner {

    val COMMON_DNS_ROUTES = listOf(
        "8.8.8.8", "8.8.4.4", "1.1.1.1", "1.0.0.1",
        "208.67.222.222", "208.67.220.220"
    )

    /** Typical Android hotspot gateway/DNS addresses — must never be routed into the VPN unless enforcing captive portal. */
    val COMMON_HOTSPOT_GATEWAY_DNS = setOf(
        "192.168.43.1", "192.168.44.1", "192.168.45.1",
        "192.168.46.1", "192.168.47.1", "192.168.48.1",
        "192.168.49.1"
    )

    /** IP returned in sinkhole DNS answers — must be routed through the VPN, not a local AP address. */
    const val CAPTIVE_PORTAL_SINKHOLE_IP = "1.1.1.1"

    fun buildCaptureRoutes(
        linkDnsServers: Collection<String>,
        providerServers: Collection<String>,
        blockedHotspotClientIps: Collection<String> = emptyList(),
        excludeDnsServers: Collection<String> = emptyList(),
        includeHotspotGatewayDns: Collection<String> = emptyList(),
        tetherSubnetRoutes: Collection<String> = emptyList()
    ): List<String> {
        val excluded = if (includeHotspotGatewayDns.isNotEmpty()) {
            excludeDnsServers.toSet()
        } else {
            (COMMON_HOTSPOT_GATEWAY_DNS + excludeDnsServers).toSet()
        }
        val routes = linkedSetOf<String>()
        routes.addAll(linkDnsServers.filter { isRoutableIpv4(it) && it !in excluded })
        routes.addAll(providerServers.filter { isRoutableIpv4(it) && it !in excluded })
        routes.addAll(COMMON_DNS_ROUTES.filter { it !in excluded })
        routes.addAll(blockedHotspotClientIps.filter { isRoutableIpv4(it) && it !in excluded })
        routes.addAll(includeHotspotGatewayDns.filter { isRoutableIpv4(it) && it !in excluded })
        routes.addAll(tetherSubnetRoutes.filter { isRoutableIpv4(it) })
        if (blockedHotspotClientIps.isNotEmpty()) {
            routes.add(CAPTIVE_PORTAL_SINKHOLE_IP)
        }
        return routes.toList()
    }

    fun addTetherSubnetRoute(builder: VpnServiceBuilder, gatewayIpv4: String, prefixLength: Int = 24) {
        val network = tetherSubnetNetwork(gatewayIpv4, prefixLength) ?: return
        builder.addRoute(network, prefixLength)
    }

    fun tetherSubnetNetwork(gatewayIpv4: String, prefixLength: Int = 24): String? {
        val parts = gatewayIpv4.split('.')
        if (parts.size != 4) return null
        val maskBits = prefixLength.coerceIn(8, 30)
        val base = parts.map { it.toInt() and 0xFF }.toIntArray()
        val shift = 32 - maskBits
        val networkInt = ((base[0] shl 24) or (base[1] shl 16) or (base[2] shl 8) or base[3]) and (-1 shl shift)
        return "${(networkInt shr 24) and 0xFF}.${(networkInt shr 16) and 0xFF}." +
            "${(networkInt shr 8) and 0xFF}.${networkInt and 0xFF}"
    }

    /** Minimal surface for [android.net.VpnService.Builder] without coupling the planner to Android VPN types. */
    interface VpnServiceBuilder {
        fun addRoute(address: String, prefixLength: Int)
    }

    fun isRoutableIpv4(ip: String): Boolean {
        if (ip.isBlank() || ip == "0.0.0.0") return false
        val parts = ip.split('.')
        if (parts.size != 4) return false
        return parts.all { part ->
            val value = part.toIntOrNull() ?: return false
            value in 0..255
        }
    }
}
