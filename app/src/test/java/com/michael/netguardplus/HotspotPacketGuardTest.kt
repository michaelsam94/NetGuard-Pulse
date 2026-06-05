package com.michael.netguardplus

import com.michael.netguardplus.system.vpn.HotspotPacketGuard
import org.junit.Assert.assertEquals
import org.junit.Test

class HotspotPacketGuardTest {

    @Test
    fun classify_dropsNonDnsFromBlockedClient() {
        // Minimal IPv4 UDP packet to 8.8.8.8:53 from 192.168.43.10 (not a valid DNS payload).
        val packet = byteArrayOf(
            0x45, 0x00, 0x00, 0x1c, 0x00, 0x00, 0x40, 0x00,
            0x40, 0x11, 0x00, 0x00, 0xc0.toByte(), 0xa8.toByte(), 0x2b, 0x0a,
            0x08, 0x08, 0x08, 0x08, 0x00, 0x35, 0x00, 0x35,
            0x00, 0x08, 0x00, 0x00
        )

        val decision = HotspotPacketGuard.classify(
            packet,
            packet.size,
            blockedClientIps = setOf("192.168.43.10")
        )

        assertEquals(HotspotPacketGuard.Action.DROP, decision.action)
        assertEquals("192.168.43.10", decision.sourceIp)
    }

    @Test
    fun classify_ignoresNonDnsFromOtherSources() {
        val packet = byteArrayOf(
            0x45, 0x00, 0x00, 0x1c, 0x00, 0x00, 0x40, 0x00,
            0x40, 0x06, 0x00, 0x00, 0xc0.toByte(), 0xa8.toByte(), 0x01, 0x05,
            0x08, 0x08, 0x08, 0x08, 0x00, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00
        )

        val decision = HotspotPacketGuard.classify(
            packet,
            packet.size,
            blockedClientIps = setOf("192.168.43.10")
        )

        assertEquals(HotspotPacketGuard.Action.IGNORE, decision.action)
    }
}
