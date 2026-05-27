package com.example.data.local.db

import androidx.room.Database
import androidx.room.RoomDatabase
import com.example.data.local.db.dao.AlertDao
import com.example.data.local.db.dao.BlocklistDao
import com.example.data.local.db.dao.DnsLogDao
import com.example.data.local.db.dao.TrafficDao
import com.example.data.local.db.entity.DataAlertEntity
import com.example.data.local.db.entity.BlockedDomainEntity
import com.example.data.local.db.entity.DnsLogEntity
import com.example.data.local.db.entity.TrafficSnapshotEntity

@Database(
    entities = [
        TrafficSnapshotEntity::class,
        DnsLogEntity::class,
        DataAlertEntity::class,
        BlockedDomainEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun trafficDao(): TrafficDao
    abstract fun dnsLogDao(): DnsLogDao
    abstract fun alertDao(): AlertDao
    abstract fun blocklistDao(): BlocklistDao
}
