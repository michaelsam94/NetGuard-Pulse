package com.michael.netguardplus

import com.michael.netguardplus.system.stats.TrafficMonitorService
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrafficMonitorServicePolicyTest {

    @Test
    fun shouldRunOnLaunch_whenPreferenceEnabled() {
        assertTrue(TrafficMonitorService.shouldRunForPreference(true))
    }

    @Test
    fun shouldNotRunOnLaunch_whenPreferenceDisabled() {
        assertFalse(TrafficMonitorService.shouldRunForPreference(false))
    }
}
