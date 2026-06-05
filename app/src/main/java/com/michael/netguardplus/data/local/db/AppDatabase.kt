package com.michael.netguardplus.data.local.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.michael.netguardplus.data.local.db.dao.AlertDao
import com.michael.netguardplus.data.local.db.dao.BlocklistDao
import com.michael.netguardplus.data.local.db.dao.DnsLogDao
import com.michael.netguardplus.data.local.db.dao.TrafficDao
import com.michael.netguardplus.data.local.db.dao.HotspotDao
import com.michael.netguardplus.data.local.db.dao.UsageHistoryDao
import com.michael.netguardplus.data.local.db.dao.DeviceUsageDao
import com.michael.netguardplus.data.local.db.dao.DeviceLimitDao
import com.michael.netguardplus.data.local.db.entity.DataAlertEntity
import com.michael.netguardplus.data.local.db.entity.DeviceUsageEntity
import com.michael.netguardplus.data.local.db.entity.DeviceLimitEntity
import com.michael.netguardplus.data.local.db.entity.BlockedDomainEntity
import com.michael.netguardplus.data.local.db.entity.DnsLogEntity
import com.michael.netguardplus.data.local.db.entity.TrafficSnapshotEntity
import com.michael.netguardplus.data.local.db.entity.HotspotClientEntity
import com.michael.netguardplus.data.local.db.entity.NetworkUsageHistoryEntity

@Database(
    entities = [
        TrafficSnapshotEntity::class,
        DnsLogEntity::class,
        DataAlertEntity::class,
        BlockedDomainEntity::class,
        HotspotClientEntity::class,
        NetworkUsageHistoryEntity::class,
        DeviceUsageEntity::class,
        DeviceLimitEntity::class
    ],
    version = 9,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun trafficDao(): TrafficDao
    abstract fun dnsLogDao(): DnsLogDao
    abstract fun alertDao(): AlertDao
    abstract fun blocklistDao(): BlocklistDao
    abstract fun hotspotDao(): HotspotDao
    abstract fun usageHistoryDao(): UsageHistoryDao
    abstract fun deviceUsageDao(): DeviceUsageDao
    abstract fun deviceLimitDao(): DeviceLimitDao

    companion object {
        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE data_alerts ADD COLUMN has_fired INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE data_alerts ADD COLUMN fired_at_ms INTEGER")
            }
        }
    }
}
