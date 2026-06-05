package com.michael.netguardplus.system.hotspot.limit

/**
 * Lets [com.michael.netguardplus.system.vpn.LocalVpnService] drop packets for over-limit
 * hotspot clients even when repository block state is momentarily out of sync.
 */
object HotspotLimiterEnforcementBridge {

    @Volatile
    private var limitEngine: LimitEngine? = null

    @Volatile
    private var usageTracker: UsageTracker? = null

    fun attach(limitEngine: LimitEngine, usageTracker: UsageTracker) {
        this.limitEngine = limitEngine
        this.usageTracker = usageTracker
    }

    fun detach() {
        limitEngine = null
        usageTracker = null
    }

    fun shouldBlockPacket(sourceIp: String, packetLength: Int): Boolean {
        if (sourceIp.isBlank()) return false
        return limitEngine?.shouldBlock(sourceIp, packetLength) == true
    }

    suspend fun recordBlockedPacket(sourceIp: String, packetLength: Int) {
        usageTracker?.recordPacket(sourceIp, packetLength)
    }
}
