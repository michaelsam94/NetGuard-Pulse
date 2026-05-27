package com.example.system.dns

import com.example.domain.repository.BlocklistRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicReference

class DnsFilterEngine(
    private val blocklistRepo: BlocklistRepository
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
        val set = blockedDomains.get()
        val cleanDomain = domain.trim().lowercase()
        
        // Exact match
        if (set.contains(cleanDomain)) return true

        // Subdomain matching (e.g., ads.example.com where example.com is in blocklist)
        var parent = cleanDomain
        while (parent.contains('.')) {
            parent = parent.substringAfter('.')
            if (set.contains(parent)) return true
        }
        return false
    }
}
