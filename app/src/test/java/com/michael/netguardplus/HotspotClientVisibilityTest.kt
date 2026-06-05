package com.michael.netguardplus

import com.michael.netguardplus.data.repository.HotspotRepositoryImpl
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HotspotClientVisibilityTest {

    @Test
    fun controllerClientWithoutResolvedIpIsVisibleImmediately() {
        assertTrue(
            HotspotRepositoryImpl.shouldIncludeClientEntity(
                macAddress = "AA:BB:CC:DD:EE:FF"
            )
        )
    }

    @Test
    fun disconnectedUnblockedClientIsHidden() {
        assertFalse(
            HotspotRepositoryImpl.shouldDisplayClient(
                hotspotEnabled = true,
                isConnected = false,
                isBlocked = false,
                sessionStartMs = 1_000L,
                lastSeenMs = 2_000L
            )
        )
    }

    @Test
    fun oldUnblockedClientStaysHiddenUntilSeenInCurrentSession() {
        assertFalse(
            HotspotRepositoryImpl.shouldDisplayClient(
                hotspotEnabled = true,
                isConnected = false,
                isBlocked = false,
                sessionStartMs = 2_000L,
                lastSeenMs = 1_000L
            )
        )
    }

    @Test
    fun recentlySeenClientRemainsVisibleDuringGraceWindow() {
        val now = System.currentTimeMillis()
        assertTrue(
            HotspotRepositoryImpl.shouldDisplayClient(
                hotspotEnabled = true,
                isConnected = false,
                isBlocked = false,
                sessionStartMs = now - 60_000L,
                lastSeenMs = now - 3_000L,
                nowMs = now
            )
        )
    }

    @Test
    fun clientHiddenAfterGraceWindowExpires() {
        val now = System.currentTimeMillis()
        assertFalse(
            HotspotRepositoryImpl.shouldDisplayClient(
                hotspotEnabled = true,
                isConnected = false,
                isBlocked = false,
                sessionStartMs = now - 60_000L,
                lastSeenMs = now - 15_000L,
                nowMs = now
            )
        )
    }
}
