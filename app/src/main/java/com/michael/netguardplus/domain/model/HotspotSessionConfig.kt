package com.michael.netguardplus.domain.model

data class HotspotSessionConfig(
    val autoOffEnabled: Boolean = true,
    val dataLimitEnabled: Boolean = false,
    val dataLimitBytes: Long = Long.MAX_VALUE,
    val timeLimitEnabled: Boolean = false,
    val timeLimitMs: Long = Long.MAX_VALUE,
    val speedLimitKbps: Long = 0L
) {
    val hasDataLimit: Boolean
        get() = dataLimitEnabled && dataLimitBytes != Long.MAX_VALUE && dataLimitBytes > 0L

    val hasTimeLimit: Boolean
        get() = timeLimitEnabled && timeLimitMs != Long.MAX_VALUE && timeLimitMs > 0L

    val hasAnyLimit: Boolean
        get() = hasDataLimit || hasTimeLimit
}

data class HotspotSessionStatus(
    val isHotspotActive: Boolean = false,
    val sessionStartMs: Long = 0L,
    val sessionBytesUsed: Long = 0L,
    val elapsedMs: Long = 0L,
    /** Combined live throughput for the whole hotspot session (all clients), bytes/sec. */
    val sessionSpeedBytesPerSec: Long = 0L
)
