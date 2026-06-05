package com.michael.netguardplus

import com.michael.netguardplus.system.hotspot.HotspotController
import com.michael.netguardplus.system.hotspot.LocalNetworkClientScanner
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HotspotStaleStateTest {

    @Test
    fun staleConnectedClientsDoNotKeepHotspotEnabled() {
        assertFalse(
            HotspotController.shouldReportHotspotEnabled(
                hasFreshCachedClients = false,
                hasTetheredIfaces = false,
                wifiApState = 11
            )
        )
    }

    @Test
    fun freshConnectedClientCallbackCanReportHotspotWhenSystemStateIsUnknown() {
        assertTrue(
            HotspotController.shouldReportHotspotEnabled(
                hasFreshCachedClients = true,
                hasTetheredIfaces = false,
                wifiApState = null
            )
        )
    }

    @Test
    fun disabledWifiApStateOverridesFreshConnectedClientCache() {
        assertFalse(
            HotspotController.shouldReportHotspotEnabled(
                hasFreshCachedClients = true,
                hasTetheredIfaces = false,
                wifiApState = 11
            )
        )
    }

    @Test
    fun enabledWifiApStateReportsHotspotEnabled() {
        assertTrue(
            HotspotController.shouldReportHotspotEnabled(
                hasFreshCachedClients = false,
                hasTetheredIfaces = false,
                wifiApState = 13
            )
        )
    }

    @Test
    fun activeScannerSnapshotShouldClearWhenNoTetherNetworkRemains() {
        assertTrue(
            LocalNetworkClientScanner.shouldClearSnapshot(
                wasHotspotActive = true,
                hasActiveTetherNetwork = false
            )
        )
    }
}
