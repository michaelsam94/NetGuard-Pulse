package com.michael.netguardplus.system.hotspot.limit

import java.util.concurrent.atomic.AtomicLong

/**
 * MAC-free session-wide pause gate retained for low-level traffic tests.
 */
object SessionShapeGate {

    private val pauseUntilMs = AtomicLong(0L)

    /** Extends the session-wide pause window by [pauseMs]. */
    fun pauseFor(pauseMs: Long) {
        if (pauseMs <= 0L) return
        val until = System.currentTimeMillis() + pauseMs
        pauseUntilMs.updateAndGet { current -> maxOf(current, until) }
    }

    fun isPaused(): Boolean {
        val until = pauseUntilMs.get()
        if (until <= 0L) return false
        if (System.currentTimeMillis() >= until) {
            pauseUntilMs.compareAndSet(until, 0L)
            return false
        }
        return true
    }

    fun remainingMs(): Long =
        (pauseUntilMs.get() - System.currentTimeMillis()).coerceAtLeast(0L)

    fun clear() {
        pauseUntilMs.set(0L)
    }
}
