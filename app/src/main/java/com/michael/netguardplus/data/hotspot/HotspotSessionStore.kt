package com.michael.netguardplus.data.hotspot

import android.content.Context
import android.content.SharedPreferences
import com.michael.netguardplus.domain.model.HotspotSessionConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class HotspotSessionStore(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _config = MutableStateFlow(readConfig())
    val config: StateFlow<HotspotSessionConfig> = _config.asStateFlow()

    fun updateConfig(config: HotspotSessionConfig) {
        val sanitized = config.copy(speedLimitKbps = 0L)
        prefs.edit()
            .putBoolean(KEY_AUTO_OFF, sanitized.autoOffEnabled)
            .putBoolean(KEY_DATA_ENABLED, sanitized.dataLimitEnabled)
            .putLong(KEY_DATA_BYTES, sanitized.dataLimitBytes)
            .putBoolean(KEY_TIME_ENABLED, sanitized.timeLimitEnabled)
            .putLong(KEY_TIME_MS, sanitized.timeLimitMs)
            .putLong(KEY_SPEED_LIMIT, 0L)
            .apply()
        _config.value = sanitized
    }

    private fun readConfig(): HotspotSessionConfig {
        return HotspotSessionConfig(
            autoOffEnabled = prefs.getBoolean(KEY_AUTO_OFF, true),
            dataLimitEnabled = prefs.getBoolean(KEY_DATA_ENABLED, false),
            dataLimitBytes = prefs.getLong(KEY_DATA_BYTES, Long.MAX_VALUE),
            timeLimitEnabled = prefs.getBoolean(KEY_TIME_ENABLED, false),
            timeLimitMs = prefs.getLong(KEY_TIME_MS, Long.MAX_VALUE),
            speedLimitKbps = 0L
        )
    }

    fun saveSessionProgress(startMs: Long, bytesUsed: Long) {
        prefs.edit()
            .putLong(KEY_SESSION_START_MS, startMs)
            .putLong(KEY_SESSION_BYTES_USED, bytesUsed)
            .apply()
    }

    fun clearSessionProgress() {
        prefs.edit()
            .remove(KEY_SESSION_START_MS)
            .remove(KEY_SESSION_BYTES_USED)
            .remove(KEY_LIMIT_NOTIFIED)
            .apply()
    }

    fun readSessionProgress(): SessionProgress =
        SessionProgress(
            startMs = prefs.getLong(KEY_SESSION_START_MS, 0L),
            bytesUsed = prefs.getLong(KEY_SESSION_BYTES_USED, 0L)
        )

    fun isLimitNotified(): Boolean = prefs.getBoolean(KEY_LIMIT_NOTIFIED, false)

    fun setLimitNotified(notified: Boolean) {
        prefs.edit().putBoolean(KEY_LIMIT_NOTIFIED, notified).apply()
    }

    data class SessionProgress(val startMs: Long, val bytesUsed: Long)

    companion object {
        private const val PREFS_NAME = "hotspot_session_config"
        private const val KEY_AUTO_OFF = "auto_off_enabled"
        private const val KEY_DATA_ENABLED = "data_limit_enabled"
        private const val KEY_DATA_BYTES = "data_limit_bytes"
        private const val KEY_TIME_ENABLED = "time_limit_enabled"
        private const val KEY_TIME_MS = "time_limit_ms"
        private const val KEY_SPEED_LIMIT = "speed_limit_kbps"
        private const val KEY_SESSION_START_MS = "session_start_ms"
        private const val KEY_SESSION_BYTES_USED = "session_bytes_used"
        private const val KEY_LIMIT_NOTIFIED = "limit_notified"
    }
}
