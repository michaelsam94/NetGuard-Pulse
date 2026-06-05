package com.michael.netguardplus.data.local.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "device_limits")
data class DeviceLimitEntity(
    @PrimaryKey val ip: String,
    /** [Long.MAX_VALUE] = no data cap */
    @ColumnInfo(name = "data_limit_bytes") val dataLimitBytes: Long = Long.MAX_VALUE,
    /** [Long.MAX_VALUE] = no time cap */
    @ColumnInfo(name = "time_limit_ms") val timeLimitMs: Long = Long.MAX_VALUE,
    /** Only the user may set this (block / unblock / reset). */
    @ColumnInfo(name = "is_manually_blocked") val isManuallyBlocked: Boolean = false,
    /** Bytes counted across reconnects until [resetUsage] or daily rollover. */
    @ColumnInfo(name = "daily_bytes_used") val dailyBytesUsed: Long = 0L,
    @ColumnInfo(name = "daily_reset_ms") val dailyResetMs: Long = 0L
)
