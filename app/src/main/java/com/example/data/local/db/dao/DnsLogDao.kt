package com.example.data.local.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.data.local.db.entity.DnsLogEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface DnsLogDao {
    @Query("SELECT * FROM dns_log ORDER BY timestamp_ms DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<DnsLogEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entry: DnsLogEntity)

    @Query("SELECT COUNT(*) FROM dns_log WHERE was_blocked = 1 AND timestamp_ms > :since")
    suspend fun countBlockedSince(since: Long): Int

    @Query("DELETE FROM dns_log WHERE timestamp_ms < :beforeMs")
    suspend fun purgeOlderThan(beforeMs: Long)
}
