package com.michael.netguardplus.domain.repository

import com.michael.netguardplus.domain.model.HotspotClient
import com.michael.netguardplus.domain.model.HotspotSessionConfig
import com.michael.netguardplus.domain.model.HotspotSessionStatus
import kotlinx.coroutines.flow.Flow

interface HotspotRepository {
    fun observeAllClients(): Flow<List<HotspotClient>>
    fun observeHotspotEnabled(): Flow<Boolean>
    fun observeSessionConfig(): Flow<HotspotSessionConfig>
    fun observeSessionStatus(): Flow<HotspotSessionStatus>
    suspend fun updateSessionConfig(config: HotspotSessionConfig)
    suspend fun setHotspotEnabled(enabled: Boolean): Result<Unit>
    suspend fun setClientLimit(mac: String, limitBytes: Long?)
    suspend fun setClientBlocked(mac: String, blocked: Boolean)
    suspend fun resetClientUsage(mac: String)
    fun startMonitoring()
    fun stopMonitoring()
    suspend fun refreshNow()
    suspend fun dismissSessionEnforcement()
    fun openHotspotSettings()
    /** Sum of live rx+tx speeds (bytes/sec) for connected hotspot clients. */
    fun aggregateClientSpeedBytesPerSec(): Long
}
