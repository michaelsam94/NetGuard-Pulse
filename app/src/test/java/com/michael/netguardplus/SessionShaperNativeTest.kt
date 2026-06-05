package com.michael.netguardplus

import com.michael.netguardplus.system.hotspot.limit.SessionShaperNative
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionShaperNativeTest {

    @Test
    fun tokenBucketPausesWhenBurstExceedsRate() {
        val rateBytesPerSec = (500L * 1000L) / 8 // 0.5 Mbps
        val handle = SessionShaperNative.create(rateBytesPerSec)
        try {
            var now = 1_000_000_000L
            val first = SessionShaperNative.onTraffic(handle, rateBytesPerSec / 2, now)
            assertFalse(first.shouldPause)

            now += 10_000_000L
            val burst = SessionShaperNative.onTraffic(handle, rateBytesPerSec * 2, now)
            assertTrue(burst.shouldPause)
            assertTrue(burst.pauseMs in 100L..10_000L)
        } finally {
            SessionShaperNative.destroy(handle)
        }
    }
}
