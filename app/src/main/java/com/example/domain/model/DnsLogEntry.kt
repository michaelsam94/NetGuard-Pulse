package com.example.domain.model

data class DnsLogEntry(
    val id: Long = 0,
    val timestampMs: Long,
    val uid: Int,
    val packageName: String,
    val domain: String,
    val queryType: String,          // e.g. A, AAAA, etc.
    val wasBlocked: Boolean,
    val resolvedIp: String?
)
