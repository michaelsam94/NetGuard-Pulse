package com.michael.netguardplus.data.local.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "device_usage")
data class DeviceUsageEntity(
    @PrimaryKey val ip: String,
    val nickname: String = "Unknown device",
    @ColumnInfo(name = "bytes_used") val bytesUsed: Long = 0L,
    @ColumnInfo(name = "session_start_ms") val sessionStartMs: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "last_seen_ms") val lastSeenMs: Long = System.currentTimeMillis()
)
