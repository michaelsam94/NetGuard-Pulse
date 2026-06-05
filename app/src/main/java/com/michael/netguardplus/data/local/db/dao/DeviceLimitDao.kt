package com.michael.netguardplus.data.local.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.michael.netguardplus.data.local.db.entity.DeviceLimitEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface DeviceLimitDao {

    @Query("SELECT * FROM device_limits")
    suspend fun getAll(): List<DeviceLimitEntity>

    @Query("SELECT * FROM device_limits")
    fun observeAll(): Flow<List<DeviceLimitEntity>>

    @Query("SELECT * FROM device_limits WHERE ip = :ip LIMIT 1")
    suspend fun getLimit(ip: String): DeviceLimitEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(limit: DeviceLimitEntity)

    @Query("DELETE FROM device_limits WHERE ip = :ip")
    suspend fun delete(ip: String)
}
