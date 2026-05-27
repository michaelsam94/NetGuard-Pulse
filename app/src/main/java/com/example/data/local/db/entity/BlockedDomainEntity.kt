package com.example.data.local.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "blocked_domains")
data class BlockedDomainEntity(
    @PrimaryKey val domain: String
)
