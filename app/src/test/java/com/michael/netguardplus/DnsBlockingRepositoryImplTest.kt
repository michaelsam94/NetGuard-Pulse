package com.michael.netguardplus

import android.content.Context
import android.net.wifi.WifiManager
import com.michael.netguardplus.data.repository.DnsBlockingRepositoryImpl
import com.michael.netguardplus.domain.model.DnsBlockingState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.junit.MockitoJUnitRunner

@RunWith(MockitoJUnitRunner::class)
class DnsBlockingRepositoryImplTest {

    @Mock
    private lateinit var context: Context

    @Mock
    private lateinit var wifiManager: WifiManager

    private lateinit var repository: DnsBlockingRepositoryImpl

    private val testMac = "AA:BB:CC:DD:EE:FF"

    @Before
    fun setUp() {
        repository = DnsBlockingRepositoryImpl(
            context = context,
            wifiManager = wifiManager,
            resolveMac = { ip ->
                if (ip == "192.168.43.10") testMac else null
            },
            applyMacBlockHook = { true }
        )
    }

    @Test
    fun testBlockedClientAppearsInGetBlockedClients() = runBlocking {
        repository.blockClient("192.168.43.10", "Test Phone", "1.0 GB")
        assertTrue(repository.getBlockedClients().contains("192.168.43.10"))
    }

    @Test
    fun testUnblockClientRemovesFromBlockedSet() = runBlocking {
        repository.blockClient("192.168.43.10", "Test Phone", "1.0 GB")
        repository.unblockClient("192.168.43.10")
        assertFalse(repository.getBlockedClients().contains("192.168.43.10"))
    }

    @Test
    fun testIsClientBlockedReturnsTrueAfterBlock() = runBlocking {
        repository.blockClient("192.168.43.10", "Test Phone", "1.0 GB")
        assertTrue(repository.isClientBlocked("192.168.43.10"))
    }

    @Test
    fun testStopInterceptionClearsAllBlockedClients() = runBlocking {
        repository.blockClient("192.168.43.10", "Test Phone", "1.0 GB")
        repository.stopDnsInterception()
        assertTrue(repository.getBlockedClients().isEmpty())
        assertEquals(DnsBlockingState.Idle, repository.blockingState.value)
    }

    @Test
    fun testBlockWithoutMacUsesIpOnlyEnforcement() = runBlocking {
        var enforcedIps: Set<String>? = null
        val ipRepo = DnsBlockingRepositoryImpl(
            context = context,
            wifiManager = wifiManager,
            resolveMac = { null },
            applyMacBlockHook = { true },
            onIpEnforcementChanged = { ips, _ -> enforcedIps = ips }
        )
        ipRepo.blockClient("10.0.0.99", "Unknown", "0 B")
        assertTrue(ipRepo.getBlockedClients().contains("10.0.0.99"))
        assertEquals(setOf("10.0.0.99"), enforcedIps)
    }

    @Test
    fun testMacBlockApiFailureFallsBackToIpBlock() = runBlocking {
        var enforcedIps: Set<String>? = null
        val ipRepo = DnsBlockingRepositoryImpl(
            context = context,
            wifiManager = wifiManager,
            resolveMac = { ip -> if (ip == "192.168.43.10") testMac else null },
            applyMacBlockHook = { false },
            onIpEnforcementChanged = { ips, _ -> enforcedIps = ips }
        )
        ipRepo.blockClient("192.168.43.10", "Phone", "1 GB")
        assertTrue(ipRepo.getBlockedClients().contains("192.168.43.10"))
        assertEquals(setOf("192.168.43.10"), enforcedIps)
    }

    @Test
    fun testSessionBlockedInvokesCallback() = runBlocking {
        var enforcedIps: Set<String>? = null
        var sessionBlocked = false
        val ipRepo = DnsBlockingRepositoryImpl(
            context = context,
            wifiManager = wifiManager,
            resolveMac = { null },
            applyMacBlockHook = { true },
            onIpEnforcementChanged = { ips, sb ->
                enforcedIps = ips
                sessionBlocked = sb
            }
        )
        ipRepo.setSessionBlocked(true)
        assertTrue(ipRepo.isSessionBlocked())
        assertTrue(sessionBlocked)
    }
}
