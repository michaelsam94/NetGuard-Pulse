package com.michael.netguardplus.data.local.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "data_alerts")
data class DataAlertEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val uid: Int,
    @ColumnInfo(name = "package_name") val packageName: String?,
    @ColumnInfo(name = "threshold_bytes") val thresholdBytes: Long,
    @ColumnInfo(name = "window_seconds") val windowSeconds: Int,
    @ColumnInfo(name = "trigger_on_background") val triggerOnBackground: Boolean,
    @ColumnInfo(name = "notification_type") val notificationType: String, // String representation of AlertType
    @ColumnInfo(name = "network_type") val networkType: String = "MOBILE",
    @ColumnInfo(name = "is_enabled") val isEnabled: Boolean,
    @ColumnInfo(name = "has_fired", defaultValue = "0") val hasFired: Boolean = false,
    @ColumnInfo(name = "fired_at_ms") val firedAtMs: Long? = null
)
