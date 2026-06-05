package com.michael.netguardplus.data.local.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.michael.netguardplus.data.local.db.entity.TrafficSnapshotEntity
import kotlinx.coroutines.flow.Flow

data class AggregatedTrafficTuple(
    val uid: Int,
    val package_name: String,
    val rx_total: Long,
    val tx_total: Long,
    val last_active: Long
)

@Dao
interface TrafficDao {
    @Query("""
        SELECT uid, package_name, 
               SUM(rx_bytes) as rx_total, SUM(tx_bytes) as tx_total,
               MAX(timestamp_ms) as last_active
        FROM traffic_snapshots
        WHERE timestamp_ms > :sinceMs
        GROUP BY uid, package_name
        ORDER BY (rx_total + tx_total) DESC
    """)
    fun observeAggregatedTraffic(sinceMs: Long): Flow<List<AggregatedTrafficTuple>>

    @Query("SELECT * FROM traffic_snapshots WHERE uid = :uid AND timestamp_ms > :sinceMs")
    suspend fun getTrafficForAppSince(uid: Int, sinceMs: Long): List<TrafficSnapshotEntity>

    @Query("SELECT * FROM traffic_snapshots WHERE timestamp_ms > :sinceMs")
    suspend fun getTrafficSince(sinceMs: Long): List<TrafficSnapshotEntity>

    @Query("""
        SELECT COALESCE(SUM(rx_bytes + tx_bytes), 0)
        FROM traffic_snapshots
        WHERE timestamp_ms > :sinceMs AND is_mobile = 1
    """)
    suspend fun getMobileTrafficSince(sinceMs: Long): Long

    @Query("""
        SELECT COALESCE(SUM(rx_bytes + tx_bytes), 0)
        FROM traffic_snapshots
        WHERE uid = :uid AND timestamp_ms > :sinceMs AND is_mobile = 1
    """)
    suspend fun getMobileTrafficForAppSince(uid: Int, sinceMs: Long): Long

    @Query("""
        SELECT COALESCE(SUM(rx_bytes + tx_bytes), 0)
        FROM traffic_snapshots
        WHERE timestamp_ms > :sinceMs AND is_mobile = 0
    """)
    suspend fun getWifiTrafficSince(sinceMs: Long): Long

    @Query("""
        SELECT COALESCE(SUM(rx_bytes + tx_bytes), 0)
        FROM traffic_snapshots
        WHERE uid = :uid AND timestamp_ms > :sinceMs AND is_mobile = 0
    """)
    suspend fun getWifiTrafficForAppSince(uid: Int, sinceMs: Long): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSnapshot(entity: TrafficSnapshotEntity)

    @Query("DELETE FROM traffic_snapshots WHERE timestamp_ms < :beforeMs")
    suspend fun purgeOlderThan(beforeMs: Long)
}
