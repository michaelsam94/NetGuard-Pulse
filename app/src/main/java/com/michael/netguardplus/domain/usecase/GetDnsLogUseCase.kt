package com.michael.netguardplus.domain.usecase

import com.michael.netguardplus.domain.model.DnsLogEntry
import com.michael.netguardplus.domain.repository.DnsRepository
import kotlinx.coroutines.flow.Flow

class GetDnsLogUseCase(
    private val dnsRepo: DnsRepository
) {
    operator fun invoke(limit: Int = 500): Flow<List<DnsLogEntry>> =
        dnsRepo.observeDnsLog(limit = limit)
}
