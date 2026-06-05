package com.michael.netguardplus.system.hotspot

import com.michael.netguardplus.domain.model.HotspotSessionConfig

object HotspotSessionEnforcer {

    enum class TriggerReason {
        DATA,
        TIME,
        NONE
    }

    data class Decision(
        val shouldNotify: Boolean,
        val reason: TriggerReason
    )

    fun evaluate(
        config: HotspotSessionConfig,
        sessionBytesUsed: Long,
        sessionStartMs: Long,
        hotspotActive: Boolean,
        nowMs: Long = System.currentTimeMillis()
    ): Decision {
        if (!config.autoOffEnabled || !hotspotActive || !config.hasAnyLimit) {
            return Decision(shouldNotify = false, reason = TriggerReason.NONE)
        }

        if (config.hasDataLimit && sessionBytesUsed >= config.dataLimitBytes) {
            return Decision(shouldNotify = true, reason = TriggerReason.DATA)
        }

        if (config.hasTimeLimit && sessionStartMs > 0L) {
            val elapsed = nowMs - sessionStartMs
            if (elapsed >= config.timeLimitMs) {
                return Decision(shouldNotify = true, reason = TriggerReason.TIME)
            }
        }

        return Decision(shouldNotify = false, reason = TriggerReason.NONE)
    }
}
