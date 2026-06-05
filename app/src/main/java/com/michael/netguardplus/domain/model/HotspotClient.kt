package com.michael.netguardplus.domain.model

data class HotspotClient(
    val macAddress: String,
    val ipAddress: String,
    val deviceName: String,
    val rxBytes: Long,
    val txBytes: Long,
    val rxSpeed: Long,
    val txSpeed: Long,
    val limitBytes: Long?,
    val isBlocked: Boolean,
    val isConnected: Boolean,
    val lastSeenMs: Long
)
