package com.michael.netguardplus.domain.model

data class TrafficSummary(
    val totalRxBytes: Long,
    val totalTxBytes: Long,
    val rxPerSec: Long,
    val txPerSec: Long,
    val activeAppCount: Int,
    val blockedRequestsToday: Int,
    val mobileRxBytes: Long = 0L,
    val mobileTxBytes: Long = 0L,
    val mobileRxPerSec: Long = 0L,
    val mobileTxPerSec: Long = 0L,
    val wifiRxBytes: Long = 0L,
    val wifiTxBytes: Long = 0L,
    val wifiRxPerSec: Long = 0L,
    val wifiTxPerSec: Long = 0L
) {
    companion object {
        val EMPTY = TrafficSummary(
            totalRxBytes = 0L,
            totalTxBytes = 0L,
            rxPerSec = 0L,
            txPerSec = 0L,
            activeAppCount = 0,
            blockedRequestsToday = 0
        )
    }
}
