package com.michael.netguardplus.system.vpn.proxy

/**
 * Thread-safe blocking Token Bucket rate limiter to restrict data throughput.
 */
class TokenBucket(private val rateBytesPerSec: Long) {
    private val capacity = (rateBytesPerSec * 2).coerceIn(1024L, 32 * 1024L)
    private var tokens = capacity
    private var lastRefillTime = System.currentTimeMillis()

    /**
     * Blocks the calling thread if not enough tokens are available,
     * effectively throttling traffic.
     */
    @Synchronized
    fun request(bytes: Int) {
        if (rateBytesPerSec <= 0L) return
        refill()
        if (tokens < bytes) {
            val needed = bytes - tokens
            val sleepTimeMs = ((needed * 1000.0) / rateBytesPerSec).toLong().coerceAtLeast(5L)
            tokens = 0
            try {
                Thread.sleep(sleepTimeMs)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        } else {
            tokens -= bytes
        }
    }

    private fun refill() {
        val now = System.currentTimeMillis()
        val elapsed = now - lastRefillTime
        if (elapsed > 0) {
            val generated = (elapsed * rateBytesPerSec) / 1000
            tokens = (tokens + generated).coerceAtMost(capacity)
            lastRefillTime = now
        }
    }
}
