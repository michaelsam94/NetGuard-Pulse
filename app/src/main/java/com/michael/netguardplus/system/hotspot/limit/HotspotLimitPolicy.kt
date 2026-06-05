package com.michael.netguardplus.system.hotspot.limit

/**
 * Shared rules for hotspot client blocking.
 * Usage thresholds are notification-only alerts; only a manual block restricts access.
 */
object HotspotLimitPolicy {

    fun isLimitReached(rxBytes: Long, txBytes: Long, limitBytes: Long?): Boolean {
        if (limitBytes == null || limitBytes <= 0L) return false
        return (rxBytes + txBytes) >= limitBytes
    }

    fun isEffectivelyBlocked(
        manualBlock: Boolean,
        rxBytes: Long,
        txBytes: Long,
        limitBytes: Long?
    ): Boolean = manualBlock
}
