package com.michael.netguardplus.data.local.db.dao

import androidx.room.*
import com.michael.netguardplus.data.local.db.entity.DataAlertEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface AlertDao {
    @Query("SELECT * FROM data_alerts")
    fun observeAll(): Flow<List<DataAlertEntity>>

    @Query("SELECT * FROM data_alerts WHERE is_enabled = 1")
    suspend fun getActiveAlerts(): List<DataAlertEntity>

    @Query("UPDATE data_alerts SET has_fired = 1, fired_at_ms = :firedAtMs WHERE id = :id")
    suspend fun markFired(id: Long, firedAtMs: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdate(entity: DataAlertEntity): Long

    @Query("DELETE FROM data_alerts WHERE id = :id")
    suspend fun deleteById(id: Long)
}
