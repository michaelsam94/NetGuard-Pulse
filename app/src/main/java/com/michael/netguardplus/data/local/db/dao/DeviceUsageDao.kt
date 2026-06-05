package com.michael.netguardplus.data.local.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.michael.netguardplus.data.local.db.entity.DeviceUsageEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface DeviceUsageDao {

    @Query("SELECT * FROM device_usage ORDER BY last_seen_ms DESC")
    fun observeAll(): Flow<List<DeviceUsageEntity>>

    @Query("SELECT * FROM device_usage")
    suspend fun getAll(): List<DeviceUsageEntity>

    @Query("SELECT * FROM device_usage WHERE ip = :ip LIMIT 1")
    suspend fun getUsage(ip: String): DeviceUsageEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(usage: DeviceUsageEntity)

    @Update
    suspend fun update(usage: DeviceUsageEntity)

    @Query("DELETE FROM device_usage WHERE ip = :ip")
    suspend fun delete(ip: String)

    @Query("UPDATE device_usage SET bytes_used = 0, session_start_ms = :sessionStartMs, last_seen_ms = :lastSeenMs WHERE ip = :ip")
    suspend fun resetSession(ip: String, sessionStartMs: Long, lastSeenMs: Long)
}
