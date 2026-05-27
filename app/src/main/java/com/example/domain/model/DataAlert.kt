package com.example.domain.model

data class DataAlert(
    val id: Long = 0,
    val uid: Int,                   // -1 = any app
    val packageName: String?,
    val thresholdBytes: Long,       // alert trigger size in bytes
    val windowSeconds: Int,         // observation window in seconds
    val triggerOnBackground: Boolean,
    val notificationType: AlertType,
    val isEnabled: Boolean
)

enum class AlertType { VIBRATE, SOUND, BOTH }
