package com.michael.netguardplus

import com.michael.netguardplus.data.local.db.entity.DataAlertEntity
import com.michael.netguardplus.data.mapper.toDomain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DataAlertMapperTest {

    @Test
    fun dataAlertEntityToDomain_preservesFiredState() {
        val firedAtMs = 1_799_999_000_000L

        val alert = DataAlertEntity(
            id = 42L,
            uid = -1,
            packageName = "All Transmissions",
            thresholdBytes = 50L * 1024L * 1024L,
            windowSeconds = 3600,
            triggerOnBackground = false,
            notificationType = "BOTH",
            networkType = "MOBILE",
            isEnabled = true,
            hasFired = true,
            firedAtMs = firedAtMs
        ).toDomain()

        assertTrue(alert.hasFired)
        assertEquals(firedAtMs, alert.firedAtMs)
    }
}
