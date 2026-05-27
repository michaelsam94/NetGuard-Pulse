package com.example.domain.repository

import kotlinx.coroutines.flow.Flow

interface BlocklistRepository {
    fun observeBlockedDomains(): Flow<Set<String>>
    suspend fun loadBuiltinBlocklist(): Set<String>
    suspend fun importBlocklist(content: String): Result<Int> // returns count imported or handled
    suspend fun addDomain(domain: String)
    suspend fun removeDomain(domain: String)
    suspend fun isBlocked(domain: String): Boolean
}
