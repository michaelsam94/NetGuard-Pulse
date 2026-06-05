package com.michael.netguardplus.data.mapper

import com.michael.netguardplus.data.local.db.entity.DataAlertEntity
import com.michael.netguardplus.data.local.db.entity.DnsLogEntity
import com.michael.netguardplus.domain.model.AlertNetworkType
import com.michael.netguardplus.domain.model.AlertType
import com.michael.netguardplus.domain.model.DataAlert
import com.michael.netguardplus.domain.model.DnsLogEntry

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
    networkType = try {
        AlertNetworkType.valueOf(this.networkType)
    } catch (e: Exception) {
        AlertNetworkType.MOBILE
    },
    isEnabled = this.isEnabled,
    hasFired = this.hasFired,
    firedAtMs = this.firedAtMs
)

fun DataAlert.toEntity(): DataAlertEntity = DataAlertEntity(
    id = this.id,
    uid = this.uid,
    packageName = this.packageName,
    thresholdBytes = this.thresholdBytes,
    windowSeconds = this.windowSeconds,
    triggerOnBackground = this.triggerOnBackground,
    notificationType = this.notificationType.name,
    networkType = this.networkType.name,
    isEnabled = this.isEnabled,
    hasFired = this.hasFired,
    firedAtMs = this.firedAtMs
)
