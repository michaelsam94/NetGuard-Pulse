package com.example.data.local.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "dns_log",
    indices = [
        Index("timestamp_ms")
    ]
)
data class DnsLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "timestamp_ms") val timestampMs: Long,
    val uid: Int,
    @ColumnInfo(name = "package_name") val packageName: String,
    val domain: String,
    @ColumnInfo(name = "query_type") val queryType: String,
    @ColumnInfo(name = "was_blocked") val wasBlocked: Boolean,
    @ColumnInfo(name = "resolved_ip") val resolvedIp: String?
)
