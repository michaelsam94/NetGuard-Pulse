package com.michael.netguardplus.data.local.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index

@Entity(
    tableName = "network_usage_history",
    primaryKeys = ["category", "bucket_start_ms"],
    indices = [Index("bucket_start_ms"), Index("bucket_end_ms")]
)
data class NetworkUsageHistoryEntity(
    val category: String,
    @ColumnInfo(name = "bucket_start_ms") val bucketStartMs: Long,
    @ColumnInfo(name = "bucket_end_ms") val bucketEndMs: Long,
    @ColumnInfo(name = "rx_bytes") val rxBytes: Long,
    @ColumnInfo(name = "tx_bytes") val txBytes: Long
)
