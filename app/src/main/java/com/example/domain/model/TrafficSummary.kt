package com.example.domain.model

data class TrafficSummary(
    val totalRxBytes: Long,
    val totalTxBytes: Long,
    val rxPerSec: Long,
    val txPerSec: Long,
    val activeAppCount: Int,
    val blockedRequestsToday: Int
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
