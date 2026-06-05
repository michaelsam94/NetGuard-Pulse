package com.michael.netguardplus.system.dns

import com.michael.netguardplus.data.parental.ParentalBlocklistCatalog
import com.michael.netguardplus.data.parental.ParentalControlStore
import com.michael.netguardplus.domain.repository.BlocklistRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicReference

class DnsFilterEngine(
    private val blocklistRepo: BlocklistRepository,
    private val parentalControlStore: ParentalControlStore
) {
    private val blockedDomains = AtomicReference<Set<String>>(emptySet())
    private val scope = CoroutineScope(Dispatchers.IO)

    init {
        scope.launch {
            blocklistRepo.observeBlockedDomains().collectLatest { domains ->
                blockedDomains.set(domains)
            }
        }
    }

    /**
     * Lock-free match check on domain strings. Fast, run from packets thread.
     */
    fun isBlocked(domain: String): Boolean {
        val cleanDomain = domain.trim().lowercase().removeSuffix(".")
        val set = blockedDomains.get()

        if (matchesDomainSet(cleanDomain, set)) return true

        // Immediate parental category check (no wait for flow propagation).
        val parentalDomains = ParentalBlocklistCatalog.domainsFor(parentalControlStore.enabledCategories.value)
        return matchesDomainSet(cleanDomain, parentalDomains)
    }

    private fun matchesDomainSet(domain: String, set: Set<String>): Boolean {
        if (set.contains(domain)) return true
        var parent = domain
        while (parent.contains('.')) {
            parent = parent.substringAfter('.')
            if (set.contains(parent)) return true
        }
        return false
    }
}
