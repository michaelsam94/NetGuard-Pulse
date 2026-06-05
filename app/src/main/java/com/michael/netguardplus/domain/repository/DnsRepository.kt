package com.michael.netguardplus.domain.repository

import com.michael.netguardplus.domain.model.DnsLogEntry
import kotlinx.coroutines.flow.Flow

interface DnsRepository {
    fun observeDnsLog(limit: Int): Flow<List<DnsLogEntry>>
    suspend fun insertDnsEntry(entry: DnsLogEntry)
    suspend fun getBlockedCount(sinceMs: Long): Int
    suspend fun clearLog(olderThanMs: Long)
}
