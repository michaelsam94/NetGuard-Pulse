package com.michael.netguardplus.data.local.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.michael.netguardplus.data.local.db.entity.HotspotClientEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface HotspotDao {

    @Query("SELECT * FROM hotspot_clients ORDER BY last_seen_ms DESC")
    fun observeAllClients(): Flow<List<HotspotClientEntity>>

    @Query("SELECT * FROM hotspot_clients")
    suspend fun getAllClients(): List<HotspotClientEntity>

    @Query("SELECT * FROM hotspot_clients WHERE macAddress = :mac LIMIT 1")
    suspend fun getClient(mac: String): HotspotClientEntity?

    @Query("SELECT * FROM hotspot_clients WHERE ip_address = :ip LIMIT 1")
    suspend fun getClientByIp(ip: String): HotspotClientEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertClient(client: HotspotClientEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertClients(clients: List<HotspotClientEntity>)

    @Query("UPDATE hotspot_clients SET limit_bytes = :limit WHERE macAddress = :mac")
    suspend fun setLimit(mac: String, limit: Long?)

    @Query("UPDATE hotspot_clients SET is_blocked = :blocked WHERE macAddress = :mac")
    suspend fun setBlocked(mac: String, blocked: Boolean)

    @Query("UPDATE hotspot_clients SET rx_bytes = 0, tx_bytes = 0, is_blocked = 0 WHERE macAddress = :mac")
    suspend fun resetUsage(mac: String)

    @Query("DELETE FROM hotspot_clients WHERE last_seen_ms < :threshold")
    suspend fun deleteInactiveClients(threshold: Long)

    @Query("DELETE FROM hotspot_clients")
    suspend fun deleteAllClients()
}
