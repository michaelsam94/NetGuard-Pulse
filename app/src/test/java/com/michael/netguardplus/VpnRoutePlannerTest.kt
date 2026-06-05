package com.michael.netguardplus

import com.michael.netguardplus.system.vpn.VpnRoutePlanner
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VpnRoutePlannerTest {

    @Test
    fun buildCaptureRoutes_includesBlockedClientHostRoutesOnlyWhenBlocking() {
        val withBlocked = VpnRoutePlanner.buildCaptureRoutes(
            linkDnsServers = listOf("192.168.1.1"),
            providerServers = listOf("1.1.1.1"),
            blockedHotspotClientIps = listOf("192.168.43.10", "0.0.0.0")
        )

        assertTrue(withBlocked.contains("192.168.43.10"))
        assertFalse(withBlocked.contains("0.0.0.0"))
        assertFalse(withBlocked.contains("192.168.43.1"))
    }

    @Test
    fun buildCaptureRoutes_excludesHotspotGatewayDns() {
        val routes = VpnRoutePlanner.buildCaptureRoutes(
            linkDnsServers = listOf("192.168.43.1", "192.168.1.1"),
            providerServers = listOf("1.1.1.1"),
            blockedHotspotClientIps = emptyList()
        )

        assertTrue(routes.contains("192.168.1.1"))
        assertTrue(routes.contains("1.1.1.1"))
        assertFalse(routes.contains("192.168.43.1"))
    }

    @Test
    fun buildCaptureRoutes_includesHotspotGatewayWhenCaptivePortalActive() {
        val routes = VpnRoutePlanner.buildCaptureRoutes(
            linkDnsServers = listOf("192.168.1.1"),
            providerServers = listOf("1.1.1.1"),
            blockedHotspotClientIps = listOf("192.168.43.10"),
            includeHotspotGatewayDns = listOf("192.168.43.1")
        )

        assertTrue(routes.contains("192.168.43.10"))
        assertTrue(routes.contains("192.168.43.1"))
    }

    @Test
    fun isRoutableIpv4_rejectsInvalidAddresses() {
        assertFalse(VpnRoutePlanner.isRoutableIpv4(""))
        assertFalse(VpnRoutePlanner.isRoutableIpv4("0.0.0.0"))
        assertFalse(VpnRoutePlanner.isRoutableIpv4("not-an-ip"))
        assertTrue(VpnRoutePlanner.isRoutableIpv4("10.20.30.40"))
    }
}
