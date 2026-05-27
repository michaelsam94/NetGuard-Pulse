package com.example.domain.repository

import com.example.domain.model.AppTrafficInfo
import com.example.domain.model.TrafficSummary
import kotlinx.coroutines.flow.Flow

interface TrafficRepository {
    fun observeAllAppTraffic(): Flow<List<AppTrafficInfo>>
    fun observeTrafficSummary(): Flow<TrafficSummary>
    suspend fun getAppTrafficHistory(uid: Int, fromMs: Long): List<AppTrafficInfo>
    suspend fun saveSnapshot(uid: Int, packageName: String, rxBytes: Long, txBytes: Long, timestampMs: Long, isBackground: Boolean)
    suspend fun clearHistory(olderThanMs: Long)
}
