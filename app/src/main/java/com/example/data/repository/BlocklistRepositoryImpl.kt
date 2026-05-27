package com.example.data.repository

import com.example.data.local.db.dao.BlocklistDao
import com.example.data.local.db.entity.BlockedDomainEntity
import com.example.domain.repository.BlocklistRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class BlocklistRepositoryImpl(
    private val blocklistDao: BlocklistDao
) : BlocklistRepository {

    private val defaultBlocklist = setOf(
        "doubleclick.net",
        "googleads.g.doubleclick.net",
        "pubads.g.doubleclick.net",
        "analytics.google.com",
        "app-measurement.com",
        "scorecardresearch.com",
        "adnxs.com",
        "adsrvr.org",
        "amazon-adsystem.com",
        "taboola.com",
        "outbrain.com",
        "rubiconproject.com",
        "openx.net",
        "flurry.com",
        "optimizely.com",
        "mixpanel.com",
        "hotjar.com",
        "facebook.net",
        "connect.facebook.net",
        "ads.youtube.com",
        "ads.tiktok.com",
        "creative-serving.com"
    )

    override fun observeBlockedDomains(): Flow<Set<String>> {
        return blocklistDao.observeAllDomains().map { dbList ->
            val set = dbList.toMutableSet()
            if (set.isEmpty()) {
                // If DB is empty, default set is active
                set.addAll(defaultBlocklist)
            }
            set
        }
    }

    override suspend fun loadBuiltinBlocklist(): Set<String> {
        val currentEntries = blocklistDao.getAllEntries()
        if (currentEntries.isEmpty()) {
            val dbEntities = defaultBlocklist.map { BlockedDomainEntity(it) }
            blocklistDao.insertAll(dbEntities)
        }
        return blocklistDao.getAllEntries().map { it.domain }.toSet()
    }

    override suspend fun importBlocklist(content: String): Result<Int> {
        return try {
            val domains = content.lines()
                .map { it.trim().lowercase() }
                .filter { it.isNotEmpty() && !it.startsWith("#") }
            
            if (domains.isEmpty()) {
                return Result.failure(Exception("No valid domains found in import content"))
            }

            val dbEntities = domains.map { BlockedDomainEntity(it) }
            blocklistDao.insertAll(dbEntities)
            Result.success(domains.size)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun addDomain(domain: String) {
        val cleanDomain = domain.trim().lowercase()
        if (cleanDomain.isNotEmpty()) {
            blocklistDao.insert(BlockedDomainEntity(cleanDomain))
        }
    }

    override suspend fun removeDomain(domain: String) {
        blocklistDao.delete(domain.trim().lowercase())
    }

    override suspend fun isBlocked(domain: String): Boolean {
        val cleanDomain = domain.trim().lowercase()
        // Check exact match in custom list
        if (blocklistDao.countDomain(cleanDomain) > 0) return true
        if (defaultBlocklist.contains(cleanDomain)) return true

        // Check parent subdomains (e.g. ads.doubleclick.net -> doubleclick.net)
        var d = cleanDomain
        while (d.contains('.')) {
            d = d.substringAfter('.')
            if (blocklistDao.countDomain(d) > 0 || defaultBlocklist.contains(d)) return true
        }

        return false
    }
}
