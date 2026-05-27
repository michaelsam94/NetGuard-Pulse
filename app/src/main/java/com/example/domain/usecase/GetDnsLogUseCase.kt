package com.example.domain.usecase

import com.example.domain.model.DnsLogEntry
import com.example.domain.repository.DnsRepository
import kotlinx.coroutines.flow.Flow

class GetDnsLogUseCase(
    private val dnsRepo: DnsRepository
) {
    operator fun invoke(limit: Int = 500): Flow<List<DnsLogEntry>> =
        dnsRepo.observeDnsLog(limit = limit)
}
