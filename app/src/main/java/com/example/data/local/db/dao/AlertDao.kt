package com.example.data.local.db.dao

import androidx.room.*
import com.example.data.local.db.entity.DataAlertEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface AlertDao {
    @Query("SELECT * FROM data_alerts")
    fun observeAll(): Flow<List<DataAlertEntity>>

    @Query("SELECT * FROM data_alerts WHERE is_enabled = 1")
    suspend fun getActiveAlerts(): List<DataAlertEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdate(entity: DataAlertEntity): Long

    @Query("DELETE FROM data_alerts WHERE id = :id")
    suspend fun deleteById(id: Long)
}
