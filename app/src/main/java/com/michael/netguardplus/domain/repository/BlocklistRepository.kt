package com.michael.netguardplus.domain.repository

import com.michael.netguardplus.domain.model.FamilyDnsProvider
import com.michael.netguardplus.domain.model.ParentalCategory
import kotlinx.coroutines.flow.Flow

interface BlocklistRepository {
    fun observeBlockedDomains(): Flow<Set<String>>
    fun observeEnabledParentalCategories(): Flow<Set<ParentalCategory>>
    fun observeFamilyDnsProvider(): Flow<FamilyDnsProvider>
    suspend fun loadBuiltinBlocklist(): Set<String>
    suspend fun importBlocklist(content: String): Result<Int> // returns count imported or handled
    suspend fun addDomain(domain: String)
    suspend fun removeDomain(domain: String)
    suspend fun isBlocked(domain: String): Boolean
    suspend fun setParentalCategoryEnabled(category: ParentalCategory, enabled: Boolean)
    suspend fun setFamilyDnsProvider(provider: FamilyDnsProvider)
    fun familyDnsServers(): List<String>
}
