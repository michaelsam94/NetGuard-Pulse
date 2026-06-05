package com.michael.netguardplus.domain.repository

import com.michael.netguardplus.domain.model.AppTrafficInfo
import com.michael.netguardplus.domain.model.TrafficSummary
import kotlinx.coroutines.flow.Flow

interface TrafficRepository {
    fun observeAllAppTraffic(): Flow<List<AppTrafficInfo>>
    fun observeTrafficSummary(): Flow<TrafficSummary>
    suspend fun getAppTrafficHistory(uid: Int, fromMs: Long): List<AppTrafficInfo>
    suspend fun saveSnapshot(
        uid: Int,
        packageName: String,
        wifiRx: Long,
        wifiTx: Long,
        mobileRx: Long,
        mobileTx: Long,
        timestampMs: Long,
        isBackground: Boolean
    )
    suspend fun getMobileTrafficToday(): Long
    suspend fun getMobileTrafficForAppToday(uid: Int): Long
    suspend fun getWifiTrafficToday(): Long
    suspend fun getWifiTrafficForAppToday(uid: Int): Long
    suspend fun updateDeviceNetworkStats(
        mobileRxBytes: Long,
        mobileTxBytes: Long,
        wifiRxBytes: Long,
        wifiTxBytes: Long,
        timestampMs: Long,
        mobileRxPerSec: Long = 0L,
        mobileTxPerSec: Long = 0L,
        wifiRxPerSec: Long = 0L,
        wifiTxPerSec: Long = 0L
    )
    suspend fun updateAppSpeeds(
        speeds: Map<Int, Pair<Long, Long>>,
        timestampMs: Long
    )
    suspend fun clearHistory(olderThanMs: Long)
    suspend fun notifyAppTrafficPollComplete(timestampMs: Long)
}

