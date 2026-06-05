package com.michael.netguardplus.system.vpn

/**
 * Classifies packets on the VPN TUN interface for hotspot client enforcement.
 * Blocked clients get DNS sinkholed to the gateway and HTTP redirected to the captive portal page.
 */
object HotspotPacketGuard {

    enum class Action {
        /** Non-DNS/non-portal HTTP traffic from a blocked hotspot client — do not forward. */
        DROP,
        /** DNS query — handled by [LocalVpnService.resolveDnsQuery]. */
        HANDLE_DNS,
        /** HTTP to the hotspot gateway — inject captive portal page. */
        HANDLE_CAPTIVE_HTTP,
        /** Not relevant to hotspot enforcement (phone DNS or unparseable). */
        IGNORE
    }

    data class Decision(
        val action: Action,
        val sourceIp: String? = null,
        val dnsQuery: DnsPacketHandler.ParsedDnsQuery? = null
    )

    fun classify(
        buffer: ByteArray,
        length: Int,
        blockedClientIps: Set<String>,
        gatewayIps: Set<String> = emptySet(),
        isSessionBlocked: Boolean = false
    ): Decision {
        val sourceIp = extractIpv4Source(buffer, length)
        val destIp = extractIpv4Dest(buffer, length)
        val dnsQuery = DnsPacketHandler.parseDnsQuery(buffer, length)

        val isBlocked = (sourceIp != null && blockedClientIps.contains(sourceIp)) ||
                (isSessionBlocked && sourceIp != null && sourceIp != "127.0.0.1" && sourceIp != "10.255.254.1" && !gatewayIps.contains(sourceIp))

        if (sourceIp != null && isBlocked) {
            if (dnsQuery != null) {
                return Decision(Action.HANDLE_DNS, sourceIp, dnsQuery)
            }
            val tcp = CaptivePortalHttpInjector.parseTcp(buffer, length)
            if (tcp != null &&
                CaptivePortalHttpInjector.isHttpPort(tcp.destPort) &&
                (gatewayIps.isEmpty() || tcp.destIp in gatewayIps || tcp.destIp == VpnRoutePlanner.CAPTIVE_PORTAL_SINKHOLE_IP)
            ) {
                return Decision(Action.HANDLE_CAPTIVE_HTTP, sourceIp)
            }
            return Decision(Action.DROP, sourceIp)
        }

        return if (dnsQuery != null) {
            Decision(Action.HANDLE_DNS, sourceIp, dnsQuery)
        } else {
            Decision(Action.IGNORE, sourceIp)
        }
    }

    fun extractIpv4Source(buffer: ByteArray, length: Int): String? {
        if (length < 20) return null
        val versionAndIhl = buffer[0].toInt() and 0xFF
        if (versionAndIhl shr 4 != 4) return null
        val headerLength = (versionAndIhl and 0x0F) * 4
        if (headerLength < 20 || length < headerLength) return null
        return readIpv4(buffer, 12)
    }

    fun extractIpv4Dest(buffer: ByteArray, length: Int): String? {
        if (length < 20) return null
        val versionAndIhl = buffer[0].toInt() and 0xFF
        if (versionAndIhl shr 4 != 4) return null
        val headerLength = (versionAndIhl and 0x0F) * 4
        if (headerLength < 20 || length < headerLength) return null
        return readIpv4(buffer, 16)
    }

    private fun readIpv4(buffer: ByteArray, offset: Int): String =
        "${buffer[offset].toInt() and 0xFF}.${buffer[offset + 1].toInt() and 0xFF}." +
            "${buffer[offset + 2].toInt() and 0xFF}.${buffer[offset + 3].toInt() and 0xFF}"
}
