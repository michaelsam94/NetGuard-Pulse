package com.example.domain.model

data class AppTrafficInfo(
    val uid: Int,
    val packageName: String,
    val appLabel: String,
    val rxBytesTotal: Long,
    val txBytesTotal: Long,
    val rxBytesPerSec: Long,        // current speed download
    val txBytesPerSec: Long,        // current speed upload
    val isBackground: Boolean,
    val lastActiveMs: Long,
    val blockedDomains: Int,
    val sessionStartMs: Long
)
