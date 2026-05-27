package com.example.data.mapper

import com.example.data.local.db.entity.DataAlertEntity
import com.example.data.local.db.entity.DnsLogEntity
import com.example.domain.model.AlertType
import com.example.domain.model.DataAlert
import com.example.domain.model.DnsLogEntry

fun DnsLogEntity.toDomain(): DnsLogEntry = DnsLogEntry(
    id = this.id,
    timestampMs = this.timestampMs,
    uid = this.uid,
    packageName = this.packageName,
    domain = this.domain,
    queryType = this.queryType,
    wasBlocked = this.wasBlocked,
    resolvedIp = this.resolvedIp
)

fun DnsLogEntry.toEntity(): DnsLogEntity = DnsLogEntity(
    id = this.id,
    timestampMs = this.timestampMs,
    uid = this.uid,
    packageName = this.packageName,
    domain = this.domain,
    queryType = this.queryType,
    wasBlocked = this.wasBlocked,
    resolvedIp = this.resolvedIp
)

fun DataAlertEntity.toDomain(): DataAlert = DataAlert(
    id = this.id,
    uid = this.uid,
    packageName = this.packageName,
    thresholdBytes = this.thresholdBytes,
    windowSeconds = this.windowSeconds,
    triggerOnBackground = this.triggerOnBackground,
    notificationType = try {
        AlertType.valueOf(this.notificationType)
    } catch (e: Exception) {
        AlertType.BOTH
    },
    isEnabled = this.isEnabled
)

fun DataAlert.toEntity(): DataAlertEntity = DataAlertEntity(
    id = this.id,
    uid = this.uid,
    packageName = this.packageName,
    thresholdBytes = this.thresholdBytes,
    windowSeconds = this.windowSeconds,
    triggerOnBackground = this.triggerOnBackground,
    notificationType = this.notificationType.name,
    isEnabled = this.isEnabled
)
