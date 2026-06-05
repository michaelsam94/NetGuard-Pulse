package com.michael.netguardplus.domain.repository

import com.michael.netguardplus.domain.model.DataAlert
import kotlinx.coroutines.flow.Flow

interface AlertRepository {
    fun observeAlerts(): Flow<List<DataAlert>>
    suspend fun upsertAlert(alert: DataAlert): Long
    suspend fun deleteAlert(id: Long)
    suspend fun getActiveAlerts(): List<DataAlert>
    suspend fun markAlertFired(id: Long, firedAtMs: Long)
}
