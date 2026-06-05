package com.michael.netguardplus.data.local.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "hotspot_clients")
data class HotspotClientEntity(
    @PrimaryKey val macAddress: String,
    @ColumnInfo(name = "ip_address") val ipAddress: String,
    @ColumnInfo(name = "device_name") val deviceName: String,
    @ColumnInfo(name = "rx_bytes") val rxBytes: Long,
    @ColumnInfo(name = "tx_bytes") val txBytes: Long,
    @ColumnInfo(name = "limit_bytes") val limitBytes: Long?,
    @ColumnInfo(name = "is_blocked") val isBlocked: Boolean,
    @ColumnInfo(name = "last_seen_ms") val lastSeenMs: Long
)
