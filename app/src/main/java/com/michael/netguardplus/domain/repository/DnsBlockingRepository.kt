package com.michael.netguardplus.domain.repository

import com.michael.netguardplus.domain.model.DnsBlockingState
import kotlinx.coroutines.flow.StateFlow

interface DnsBlockingRepository {
    val blockedClients: StateFlow<Set<String>>
    val blockingState: StateFlow<DnsBlockingState>

    suspend fun startDnsInterception()
    suspend fun stopDnsInterception()
    suspend fun blockClient(clientIp: String, deviceName: String, dataUsed: String)
    suspend fun unblockClient(clientIp: String)
    fun isClientBlocked(clientIp: String): Boolean
    fun getBlockedClients(): Set<String>
    suspend fun setSessionBlocked(blocked: Boolean)
    fun isSessionBlocked(): Boolean
}
