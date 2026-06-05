package com.michael.netguardplus.data.repository

import com.michael.netguardplus.data.local.db.dao.DnsLogDao
import com.michael.netguardplus.data.mapper.toDomain
import com.michael.netguardplus.data.mapper.toEntity
import com.michael.netguardplus.domain.model.DnsLogEntry
import com.michael.netguardplus.domain.repository.DnsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class DnsRepositoryImpl(
    private val dnsLogDao: DnsLogDao
) : DnsRepository {

    override fun observeDnsLog(limit: Int): Flow<List<DnsLogEntry>> {
        return dnsLogDao.observeRecent(limit).map { list ->
            list.map { it.toDomain() }
        }
    }

    override suspend fun insertDnsEntry(entry: DnsLogEntry) {
        dnsLogDao.insert(entry.toEntity())
    }

    override suspend fun getBlockedCount(sinceMs: Long): Int {
        return dnsLogDao.countBlockedSince(sinceMs)
    }

    override suspend fun clearLog(olderThanMs: Long) {
        dnsLogDao.purgeOlderThan(olderThanMs)
    }
}
