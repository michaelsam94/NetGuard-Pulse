package com.michael.netguardplus.data.repository

import com.michael.netguardplus.data.local.db.dao.BlocklistDao
import com.michael.netguardplus.data.local.db.entity.BlockedDomainEntity
import com.michael.netguardplus.data.parental.ParentalBlocklistCatalog
import com.michael.netguardplus.data.parental.ParentalControlStore
import com.michael.netguardplus.domain.model.FamilyDnsProvider
import com.michael.netguardplus.domain.model.ParentalCategory
import com.michael.netguardplus.domain.repository.BlocklistRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

class BlocklistRepositoryImpl(
    private val blocklistDao: BlocklistDao,
    private val parentalControlStore: ParentalControlStore
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
        return combine(
            blocklistDao.observeAllDomains(),
            parentalControlStore.enabledCategories
        ) { dbList, enabledCategories ->
            mergeBlockedDomains(dbList.toSet(), enabledCategories)
        }
    }

    override fun observeEnabledParentalCategories(): Flow<Set<ParentalCategory>> {
        return parentalControlStore.enabledCategories
    }

    override fun observeFamilyDnsProvider(): Flow<FamilyDnsProvider> {
        return parentalControlStore.selectedDnsProvider
    }

    override suspend fun loadBuiltinBlocklist(): Set<String> {
        val currentEntries = blocklistDao.getAllEntries()
        if (currentEntries.isEmpty()) {
            val dbEntities = defaultBlocklist.map { BlockedDomainEntity(it) }
            blocklistDao.insertAll(dbEntities)
        }
        return mergeBlockedDomains(
            blocklistDao.getAllEntries().map { it.domain }.toSet(),
            parentalControlStore.enabledCategories.value
        )
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
        val cleanDomain = domain.trim().lowercase().removeSuffix(".")
        val enabledCategories = parentalControlStore.enabledCategories.value
        val parentalDomains = ParentalBlocklistCatalog.domainsFor(enabledCategories)

        if (matchesDomain(cleanDomain, parentalDomains)) return true

        if (blocklistDao.countDomain(cleanDomain) > 0) return true
        if (defaultBlocklist.contains(cleanDomain)) return true

        var d = cleanDomain
        while (d.contains('.')) {
            d = d.substringAfter('.')
            if (blocklistDao.countDomain(d) > 0 || defaultBlocklist.contains(d)) return true
        }

        return false
    }

    private fun matchesDomain(domain: String, set: Set<String>): Boolean {
        if (set.contains(domain)) return true
        var parent = domain
        while (parent.contains('.')) {
            parent = parent.substringAfter('.')
            if (set.contains(parent)) return true
        }
        return false
    }

    override suspend fun setParentalCategoryEnabled(category: ParentalCategory, enabled: Boolean) {
        parentalControlStore.setCategoryEnabled(category, enabled)
    }

    override suspend fun setFamilyDnsProvider(provider: FamilyDnsProvider) {
        parentalControlStore.setDnsProvider(provider)
    }

    override fun familyDnsServers(): List<String> {
        return parentalControlStore.upstreamDnsServers()
    }

    private fun mergeBlockedDomains(
        dbDomains: Set<String>,
        enabledCategories: Set<ParentalCategory>
    ): Set<String> {
        val set = dbDomains.toMutableSet()
        if (set.isEmpty()) {
            set.addAll(defaultBlocklist)
        }
        set.addAll(ParentalBlocklistCatalog.domainsFor(enabledCategories))
        return set
    }
}
