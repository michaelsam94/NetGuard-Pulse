package com.michael.netguardplus.system.hotspot.limit

import android.util.Log

/**
 * Native token-bucket helper retained for low-level traffic tests.
 */
object SessionShaperNative {

    private const val TAG = "SessionShaperNative"

    data class ShaperResult(
        val shouldPause: Boolean,
        val pauseMs: Long,
        val debtBytes: Long
    )

    private var nativeLoaded = false
    private val kotlinBackupHandles = java.util.concurrent.ConcurrentHashMap<Long, Long>()

    init {
        nativeLoaded = try {
            System.loadLibrary("bandwidth_native")
            true
        } catch (e: UnsatisfiedLinkError) {
            Log.w(TAG, "Native bandwidth shaper unavailable, using Kotlin fallback", e)
            false
        }
    }

    fun create(rateBytesPerSec: Long): Long {
        val rate = rateBytesPerSec.coerceAtLeast(1L)
        val kotlinHandle = KotlinSessionShaper.create(rate)
        if (nativeLoaded) {
            val nativeHandle = nativeCreate(rate)
            if (nativeHandle > 0L) {
                kotlinBackupHandles[nativeHandle] = kotlinHandle
                return nativeHandle
            }
        }
        return kotlinHandle
    }

    fun destroy(handle: Long) {
        if (handle == 0L) return
        kotlinBackupHandles.remove(handle)?.let { KotlinSessionShaper.destroy(it) }
        if (nativeLoaded && handle > 0) {
            nativeDestroy(handle)
        } else {
            KotlinSessionShaper.destroy(handle)
        }
    }

    fun setRate(handle: Long, rateBytesPerSec: Long) {
        if (handle == 0L) return
        val rate = rateBytesPerSec.coerceAtLeast(1L)
        kotlinBackupHandles[handle]?.let { KotlinSessionShaper.setRate(it, rate) }
        if (nativeLoaded && handle > 0) {
            nativeSetRate(handle, rate)
        } else {
            KotlinSessionShaper.setRate(handle, rate)
        }
    }

    fun onTraffic(handle: Long, bytes: Long, nowNs: Long): ShaperResult {
        if (handle == 0L) return ShaperResult(false, 0L, 0L)
        if (nativeLoaded && handle > 0) {
            val native = nativeOnTraffic(handle, bytes, nowNs)
            if (native != null) return native
            Log.w(TAG, "Native onTraffic returned null — using Kotlin fallback")
        }
        val kotlinHandle = kotlinBackupHandles[handle] ?: handle
        return KotlinSessionShaper.onTraffic(kotlinHandle, bytes, nowNs)
    }

    fun isNativeLoaded(): Boolean = nativeLoaded

    private external fun nativeCreate(rateBytesPerSec: Long): Long
    private external fun nativeDestroy(handle: Long)
    private external fun nativeSetRate(handle: Long, rateBytesPerSec: Long)
    private external fun nativeOnTraffic(handle: Long, bytes: Long, nowNs: Long): ShaperResult?

    private object KotlinSessionShaper {
        private val shapers = java.util.concurrent.ConcurrentHashMap<Long, FallbackShaper>()
        private val nextId = java.util.concurrent.atomic.AtomicLong(-1L)

        fun create(rateBytesPerSec: Long): Long {
            val id = nextId.decrementAndGet()
            shapers[id] = FallbackShaper(rateBytesPerSec)
            return id
        }

        fun destroy(handle: Long) {
            shapers.remove(handle)
        }

        fun setRate(handle: Long, rateBytesPerSec: Long) {
            shapers[handle]?.setRate(rateBytesPerSec)
        }

        fun onTraffic(handle: Long, bytes: Long, nowNs: Long): ShaperResult {
            return shapers[handle]?.onTraffic(bytes, nowNs) ?: ShaperResult(false, 0L, 0L)
        }

        private class FallbackShaper(initialRate: Long) {
            private var rateBytesPerSec = initialRate.coerceAtLeast(1L)
            private var capacity = (rateBytesPerSec / 2).coerceIn(512L, 64 * 1024L).toDouble()
            private var tokens = capacity
            private var lastNs = 0L

            fun setRate(rate: Long) {
                rateBytesPerSec = rate.coerceAtLeast(1L)
                capacity = (rateBytesPerSec / 2).coerceIn(512L, 64 * 1024L).toDouble()
                tokens = tokens.coerceAtMost(capacity)
            }

            fun onTraffic(bytes: Long, nowNs: Long): ShaperResult {
                refill(nowNs)
                if (bytes <= 0L) return ShaperResult(false, 0L, 0L)
                tokens -= bytes.toDouble()
                if (tokens >= 0.0) return ShaperResult(false, 0L, 0L)
                val debt = kotlin.math.ceil(-tokens).toLong()
                tokens = 0.0
                val pauseMs = (debt * 1000L / rateBytesPerSec).coerceIn(100L, 10_000L)
                return ShaperResult(true, pauseMs, debt)
            }

            private fun refill(nowNs: Long) {
                if (lastNs <= 0L) {
                    lastNs = nowNs
                    return
                }
                val elapsedSec = (nowNs - lastNs) / 1_000_000_000.0
                if (elapsedSec > 0.0) {
                    tokens = (tokens + rateBytesPerSec * elapsedSec).coerceAtMost(capacity)
                    lastNs = nowNs
                }
            }
        }
    }
}
