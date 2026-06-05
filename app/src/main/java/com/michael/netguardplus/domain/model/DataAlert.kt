package com.michael.netguardplus.domain.model

data class DataAlert(
    val id: Long = 0,
    val uid: Int,                   // -1 = any app
    val packageName: String?,
    val thresholdBytes: Long,       // alert trigger size in bytes
    val windowSeconds: Int,         // observation window in seconds
    val triggerOnBackground: Boolean,
    val notificationType: AlertType,
    val networkType: AlertNetworkType = AlertNetworkType.MOBILE,
    val isEnabled: Boolean,
    val hasFired: Boolean = false,
    val firedAtMs: Long? = null
)

enum class AlertType { VIBRATE, SOUND, BOTH }

enum class AlertNetworkType { MOBILE, WIFI }
