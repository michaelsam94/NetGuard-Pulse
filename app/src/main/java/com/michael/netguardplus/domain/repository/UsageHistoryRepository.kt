package com.michael.netguardplus.domain.repository

import com.michael.netguardplus.domain.model.UsageHistoryReport

interface UsageHistoryRepository {
    suspend fun loadHistory(startMs: Long, endMs: Long): UsageHistoryReport
    suspend fun recordMobileDelta(rxDelta: Long, txDelta: Long, timestampMs: Long)
    suspend fun recordWifiDelta(rxDelta: Long, txDelta: Long, timestampMs: Long)
    suspend fun recordHotspotDelta(rxDelta: Long, txDelta: Long, timestampMs: Long)
}
