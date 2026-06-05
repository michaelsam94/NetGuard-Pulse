package com.michael.netguardplus.domain.usecase

import com.michael.netguardplus.domain.model.DnsLogEntry
import com.michael.netguardplus.domain.repository.BlocklistRepository
import com.michael.netguardplus.domain.repository.DnsRepository

class CheckDnsBlockUseCase(
    private val blocklistRepo: BlocklistRepository,
    private val dnsRepo: DnsRepository
) {
    suspend operator fun invoke(domain: String, uid: Int, pkg: String): Boolean {
        val blocked = blocklistRepo.isBlocked(domain)
        dnsRepo.insertDnsEntry(
            DnsLogEntry(
                timestampMs = System.currentTimeMillis(),
                uid = uid,
                packageName = pkg,
                domain = domain,
                queryType = "A",
                wasBlocked = blocked,
                resolvedIp = if (blocked) "Blocked" else "Allowed"
            )
        )
        return blocked
    }
}
