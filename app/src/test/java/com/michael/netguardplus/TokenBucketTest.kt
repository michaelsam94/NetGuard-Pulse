package com.michael.netguardplus.system.vpn.proxy

import org.junit.Assert.assertTrue
import org.junit.Test

class TokenBucketTest {

    @Test
    fun testMbpsToBytesPerSecondConversion() {
        // 1000 Kbps (UI "1.0 Mbps") → 125_000 bytes/sec
        val speedLimitKbps = 1000L
        val rateBytesPerSec = (speedLimitKbps * 1000L) / 8
        assertTrue(rateBytesPerSec == 125_000L)
    }

    @Test
    fun testTokenBucketRateLimiting() {
        val rate = 1000L // 1000 bytes per second
        val bucket = TokenBucket(rate)
        
        val capacity = (rate * 2).coerceIn(1024L, 32 * 1024L).toInt()
        bucket.request(capacity)
        
        val startBlock = System.currentTimeMillis()
        // Now requesting 500 bytes should block for ~500ms
        bucket.request(500)
        val elapsed = System.currentTimeMillis() - startBlock
        
        assertTrue("Should block for at least 300ms, elapsed: $elapsed ms", elapsed >= 300)
    }
}
