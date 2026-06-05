package com.michael.netguardplus

import com.michael.netguardplus.data.repository.HotspotRepositoryImpl
import org.junit.Assert.assertTrue
import org.junit.Test

class HotspotRepositoryPollingTest {

    @Test
    fun idleHotspotStatePollingIsFastEnoughForSettingsChanges() {
        assertTrue(
            "Hotspot on/off state should refresh within a few seconds while idle.",
            privateLong("HOTSPOT_POLL_IDLE_MS") <= 3_000L
        )
    }

    private fun privateLong(name: String): Long {
        val field = HotspotRepositoryImpl::class.java.getDeclaredField(name)
        field.isAccessible = true
        return field.getLong(null)
    }
}
