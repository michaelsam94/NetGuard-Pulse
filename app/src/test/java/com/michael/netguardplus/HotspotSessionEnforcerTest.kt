package com.michael.netguardplus

import com.michael.netguardplus.domain.model.HotspotSessionConfig
import com.michael.netguardplus.system.hotspot.HotspotSessionEnforcer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HotspotSessionEnforcerTest {

    @Test
    fun `does not notify when disabled`() {
        val config = HotspotSessionConfig(
            autoOffEnabled = false,
            dataLimitEnabled = true,
            dataLimitBytes = 100L
        )
        val decision = HotspotSessionEnforcer.evaluate(
            config = config,
            sessionBytesUsed = 200L,
            sessionStartMs = 1_000L,
            hotspotActive = true,
            nowMs = 2_000L
        )
        assertFalse(decision.shouldNotify)
    }

    @Test
    fun `notifies when data alert reached`() {
        val config = HotspotSessionConfig(
            dataLimitEnabled = true,
            dataLimitBytes = 500L
        )
        val decision = HotspotSessionEnforcer.evaluate(
            config = config,
            sessionBytesUsed = 500L,
            sessionStartMs = 1_000L,
            hotspotActive = true,
            nowMs = 2_000L
        )
        assertTrue(decision.shouldNotify)
        assertEquals(HotspotSessionEnforcer.TriggerReason.DATA, decision.reason)
    }

    @Test
    fun `notifies when timer elapsed`() {
        val config = HotspotSessionConfig(
            timeLimitEnabled = true,
            timeLimitMs = 30 * 60_000L
        )
        val start = 1_000_000L
        val decision = HotspotSessionEnforcer.evaluate(
            config = config,
            sessionBytesUsed = 0L,
            sessionStartMs = start,
            hotspotActive = true,
            nowMs = start + 30 * 60_000L
        )
        assertTrue(decision.shouldNotify)
        assertEquals(HotspotSessionEnforcer.TriggerReason.TIME, decision.reason)
    }

    @Test
    fun `does not notify below thresholds`() {
        val config = HotspotSessionConfig(
            dataLimitEnabled = true,
            dataLimitBytes = 1_000L,
            timeLimitEnabled = true,
            timeLimitMs = 60_000L
        )
        val decision = HotspotSessionEnforcer.evaluate(
            config = config,
            sessionBytesUsed = 500L,
            sessionStartMs = 1_000L,
            hotspotActive = true,
            nowMs = 30_000L
        )
        assertFalse(decision.shouldNotify)
    }
}
