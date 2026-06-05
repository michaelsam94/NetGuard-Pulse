package com.michael.netguardplus

import com.michael.netguardplus.system.hotspot.LocalNetworkClientScanner
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalNetworkClientScannerTest {

    @Test
    fun fallbackScansAreThrottledForFastClientDiscovery() {
        assertTrue(
            "Ping sweep fallback should retry within a few seconds so newly connected clients appear quickly.",
            privateLong("PING_SWEEP_MIN_INTERVAL_MS") <= 3_000L
        )
        assertTrue(
            "Subnet fallback should retry within a few seconds so newly connected clients appear quickly.",
            privateLong("SUBNET_SCAN_MIN_INTERVAL_MS") <= 3_000L
        )
    }

    private fun privateLong(name: String): Long {
        val field = LocalNetworkClientScanner::class.java.getDeclaredField(name)
        field.isAccessible = true
        return field.getLong(null)
    }
}
