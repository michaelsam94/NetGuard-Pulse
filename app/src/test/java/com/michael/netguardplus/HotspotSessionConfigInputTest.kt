package com.michael.netguardplus

import com.michael.netguardplus.presentation.dashboard.buildHotspotSessionConfigFromInputs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HotspotSessionConfigInputTest {

    @Test
    fun `clamps huge values instead of overflowing`() {
        val config = buildHotspotSessionConfigFromInputs(
            autoOffEnabled = true,
            dataLimitEnabled = true,
            dataMbInput = Long.MAX_VALUE.toString(),
            timeLimitEnabled = true,
            timeMinutesInput = Long.MAX_VALUE.toString()
        )

        assertTrue(config.dataLimitBytes > 0L)
        assertTrue(config.timeLimitMs > 0L)
        assertEquals(0L, config.speedLimitKbps)
    }

    @Test
    fun `session config keeps bandwidth limiter disabled`() {
        val config = buildHotspotSessionConfigFromInputs(
            autoOffEnabled = true,
            dataLimitEnabled = false,
            dataMbInput = "500",
            timeLimitEnabled = false,
            timeMinutesInput = "60"
        )

        assertEquals(Long.MAX_VALUE, config.dataLimitBytes)
        assertEquals(Long.MAX_VALUE, config.timeLimitMs)
        assertEquals(0L, config.speedLimitKbps)
    }
}
