package com.michael.netguardplus

import com.michael.netguardplus.system.hotspot.HotspotClientMerger
import com.michael.netguardplus.system.stats.TetheringMonitor
import org.junit.Assert.assertEquals
import org.junit.Test

class HotspotClientMergerTest {

    @Test
    fun merge_prefersScannedIpOverControllerPlaceholder() {
        val controller = listOf(
            TetheringMonitor.DiscoveredClient("AA:BB:CC:DD:EE:FF", "0.0.0.0", "Connected Device")
        )
        val scanned = listOf(
            TetheringMonitor.DiscoveredClient("AA:BB:CC:DD:EE:FF", "192.168.43.22", "Phone")
        )

        val merged = HotspotClientMerger.merge(controller, scanned)

        assertEquals(1, merged.size)
        assertEquals("192.168.43.22", merged.first().ipAddress)
    }

    @Test
    fun merge_linksSingleUnknownMacWithSingleOrphanIp() {
        val controller = listOf(
            TetheringMonitor.DiscoveredClient("11:22:33:44:55:66", "0.0.0.0", "Connected Device")
        )
        val scanned = listOf(
            TetheringMonitor.DiscoveredClient("99:88:77:66:55:44", "10.126.57.8", "Laptop")
        )

        val merged = HotspotClientMerger.merge(controller, scanned)

        assertEquals(1, merged.size)
        assertEquals("10.126.57.8", merged.first().ipAddress)
    }
}
