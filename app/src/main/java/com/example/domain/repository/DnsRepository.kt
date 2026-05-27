package com.example.domain.repository

import com.example.domain.model.DnsLogEntry
import kotlinx.coroutines.flow.Flow

interface DnsRepository {
    fun observeDnsLog(limit: Int): Flow<List<DnsLogEntry>>
    suspend fun insertDnsEntry(entry: DnsLogEntry)
    suspend fun getBlockedCount(sinceMs: Long): Int
    suspend fun clearLog(olderThanMs: Long)
}
