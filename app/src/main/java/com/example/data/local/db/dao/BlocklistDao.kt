package com.example.data.local.db.dao

import androidx.room.*
import com.example.data.local.db.entity.BlockedDomainEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface BlocklistDao {
    @Query("SELECT  domain FROM blocked_domains")
    fun observeAllDomains(): Flow<List<String>>

    @Query("SELECT * FROM blocked_domains")
    suspend fun getAllEntries(): List<BlockedDomainEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: BlockedDomainEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(entities: List<BlockedDomainEntity>)

    @Query("DELETE FROM blocked_domains WHERE domain = :domain")
    suspend fun delete(domain: String)

    @Query("SELECT COUNT(*) FROM blocked_domains WHERE domain = :domain")
    suspend fun countDomain(domain: String): Int
}
