package com.example.data.local.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "traffic_snapshots",
    indices = [
        Index("uid"),
        Index("timestamp_ms")
    ]
)
data class TrafficSnapshotEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val uid: Int,
    @ColumnInfo(name = "package_name") val packageName: String,
    @ColumnInfo(name = "timestamp_ms") val timestampMs: Long,
    @ColumnInfo(name = "rx_bytes") val rxBytes: Long,
    @ColumnInfo(name = "tx_bytes") val txBytes: Long,
    @ColumnInfo(name = "is_background") val isBackground: Boolean
)
