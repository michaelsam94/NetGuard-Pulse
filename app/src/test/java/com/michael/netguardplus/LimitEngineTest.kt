package com.michael.netguardplus

import com.michael.netguardplus.data.local.db.dao.DeviceLimitDao
import com.michael.netguardplus.data.local.db.dao.DeviceUsageDao
import com.michael.netguardplus.data.local.db.entity.DeviceLimitEntity
import com.michael.netguardplus.data.local.db.entity.DeviceUsageEntity
import com.michael.netguardplus.system.hotspot.limit.DeviceRegistry
import com.michael.netguardplus.system.hotspot.limit.LimitEngine
import com.michael.netguardplus.system.hotspot.limit.UsageTracker
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class LimitEngineTest {

    private lateinit var usageDao: DeviceUsageDao
    private lateinit var limitDao: DeviceLimitDao
    private lateinit var usageTracker: UsageTracker
    private lateinit var engine: LimitEngine

    @Before
    fun setUp() {
        usageDao = mock()
        limitDao = mock()
        val registry = DeviceRegistry(usageDao, limitDao)
        usageTracker = UsageTracker(usageDao, registry, limitDao)
        engine = LimitEngine(usageDao, limitDao, usageTracker)
    }

    @Test
    fun blocksDeviceWhenDataLimitExceeded() = runBlocking {
        whenever(usageDao.getUsage("10.0.0.2")).thenReturn(
            DeviceUsageEntity(ip = "10.0.0.2", bytesUsed = 500_000_000L)
        )
        usageTracker.recordPacket("10.0.0.2", 0)
        engine.setLimit(
            "10.0.0.2",
            DeviceLimitEntity(ip = "10.0.0.2", dataLimitBytes = 500_000_000L)
        )
        assertTrue(engine.shouldBlock("10.0.0.2", 1024))
    }

    @Test
    fun allowsDeviceWhenUnderDataLimit() = runBlocking {
        whenever(usageDao.getUsage("10.0.0.3")).thenReturn(
            DeviceUsageEntity(ip = "10.0.0.3", bytesUsed = 100_000L)
        )
        usageTracker.recordPacket("10.0.0.3", 0)
        engine.setLimit(
            "10.0.0.3",
            DeviceLimitEntity(ip = "10.0.0.3", dataLimitBytes = 500_000_000L)
        )
        assertFalse(engine.shouldBlock("10.0.0.3", 1024))
    }

    @Test
    fun blocksDeviceWhenTimeLimitExceeded() = runBlocking {
        val oldStart = System.currentTimeMillis() - (3 * 60 * 1000L)
        whenever(usageDao.getUsage("10.0.0.4")).thenReturn(
            DeviceUsageEntity(
                ip = "10.0.0.4",
                bytesUsed = 0L,
                sessionStartMs = oldStart,
                lastSeenMs = System.currentTimeMillis()
            )
        )
        usageTracker.recordPacket("10.0.0.4", 0)
        engine.setLimit(
            "10.0.0.4",
            DeviceLimitEntity(ip = "10.0.0.4", timeLimitMs = 2 * 60 * 1000L)
        )
        assertTrue(engine.shouldBlock("10.0.0.4", 64))
    }

    @Test
    fun blocksAtZeroSessionBytesWhenDailyCapAlreadyExceeded() = runBlocking {
        val cap = 7_300_000L
        whenever(usageDao.getUsage("10.0.0.5")).thenReturn(
            DeviceUsageEntity(ip = "10.0.0.5", bytesUsed = 0L)
        )
        usageTracker.recordPacket("10.0.0.5", 0)
        engine.setLimit(
            "10.0.0.5",
            DeviceLimitEntity(
                ip = "10.0.0.5",
                dataLimitBytes = cap,
                dailyBytesUsed = cap,
                dailyResetMs = LimitEngine.startOfTodayMs()
            )
        )
        assertTrue(engine.shouldBlock("10.0.0.5", 0))
    }

    @Test
    fun manualBlockIgnoresZeroUsage() = runBlocking {
        whenever(usageDao.getUsage("10.0.0.6")).thenReturn(
            DeviceUsageEntity(ip = "10.0.0.6", bytesUsed = 0L)
        )
        usageTracker.recordPacket("10.0.0.6", 0)
        engine.setLimit(
            "10.0.0.6",
            DeviceLimitEntity(ip = "10.0.0.6", isManuallyBlocked = true)
        )
        assertTrue(engine.shouldBlock("10.0.0.6", 0))
    }
}
