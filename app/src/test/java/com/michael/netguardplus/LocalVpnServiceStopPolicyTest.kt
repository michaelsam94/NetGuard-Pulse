package com.michael.netguardplus

import com.michael.netguardplus.domain.model.FamilyDnsProvider
import com.michael.netguardplus.system.vpn.LocalVpnService
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalVpnServiceStopPolicyTest {

    @Test
    fun stopHotspotEnforcement_keepsActiveFamilyDnsVpnAlive() {
        assertFalse(
            LocalVpnService.shouldShutdownForHotspotEnforcementStop(
                hotspotEnforcementOnly = false,
                activeProvider = FamilyDnsProvider.CLOUDFLARE_FAMILY,
                hasHotspotEnforcement = false
            )
        )
    }

    @Test
    fun stopHotspotEnforcement_shutsDownWhenServiceOnlyExistsForHotspotEnforcement() {
        assertTrue(
            LocalVpnService.shouldShutdownForHotspotEnforcementStop(
                hotspotEnforcementOnly = true,
                activeProvider = null,
                hasHotspotEnforcement = true
            )
        )
    }

    @Test
    fun stopHotspotEnforcement_shutsDownIdleServiceWithoutFamilyDns() {
        assertTrue(
            LocalVpnService.shouldShutdownForHotspotEnforcementStop(
                hotspotEnforcementOnly = false,
                activeProvider = null,
                hasHotspotEnforcement = false
            )
        )
    }
}
