package com.michael.netguardplus.data.local.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.michael.netguardplus.data.local.db.entity.NetworkUsageHistoryEntity

data class UsageCategoryTotal(
    val category: String,
    val rx_total: Long,
    val tx_total: Long
)

@Dao
interface UsageHistoryDao {

    @Query("SELECT * FROM network_usage_history WHERE category = :category AND bucket_start_ms = :bucketStartMs LIMIT 1")
    suspend fun getBucket(category: String, bucketStartMs: Long): NetworkUsageHistoryEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertBucket(entity: NetworkUsageHistoryEntity)

    @Query("""
        SELECT category, SUM(rx_bytes) as rx_total, SUM(tx_bytes) as tx_total
        FROM network_usage_history
        WHERE bucket_start_ms >= :startMs AND bucket_end_ms <= :endMs
        GROUP BY category
    """)
    suspend fun sumByCategory(startMs: Long, endMs: Long): List<UsageCategoryTotal>

    @Query("""
        SELECT * FROM network_usage_history
        WHERE category = :category
          AND bucket_start_ms >= :startMs
          AND bucket_end_ms <= :endMs
        ORDER BY bucket_start_ms ASC
    """)
    suspend fun getBucketsForCategory(category: String, startMs: Long, endMs: Long): List<NetworkUsageHistoryEntity>

    @Query("""
        SELECT * FROM network_usage_history
        WHERE bucket_start_ms >= :startMs AND bucket_end_ms <= :endMs
        ORDER BY bucket_start_ms ASC
    """)
    suspend fun getBucketsInRange(startMs: Long, endMs: Long): List<NetworkUsageHistoryEntity>

    @Query("DELETE FROM network_usage_history WHERE bucket_end_ms < :beforeMs")
    suspend fun purgeOlderThan(beforeMs: Long)
}
