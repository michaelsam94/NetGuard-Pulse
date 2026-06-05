package com.michael.netguardplus.system.hotspot.limit

/**
 * Shared rules for hotspot data limits (repository + captive portal).
 * [manualBlock] is the persisted user flag; limit enforcement is derived from usage.
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
    ): Boolean = manualBlock || isLimitReached(rxBytes, txBytes, limitBytes)
}
