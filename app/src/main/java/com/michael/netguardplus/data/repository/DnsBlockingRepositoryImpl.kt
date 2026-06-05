package com.michael.netguardplus.data.repository

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import com.michael.netguardplus.domain.model.DnsBlockingState
import com.michael.netguardplus.domain.repository.DnsBlockingRepository
import com.michael.netguardplus.system.hotspot.HotspotClientEnforcer
import com.michael.netguardplus.system.hotspot.MacAddressResolver
import com.michael.netguardplus.system.hotspot.limit.DeviceNicknameMerger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Blocks hotspot clients by MAC via [SoftApConfiguration.blockedClientList] when resolvable,
 * otherwise by client IP (captive portal + VPN /32 routes) via [onIpEnforcementChanged].
 */
class DnsBlockingRepositoryImpl(
    private val context: Context,
    private val wifiManager: WifiManager = (context.applicationContext ?: context)
        .getSystemService(Context.WIFI_SERVICE) as WifiManager,
    private val macResolver: MacAddressResolver = MacAddressResolver(context, wifiManager),
    private val resolveMac: (String) -> String? = { macResolver.resolve(it) },
    private val applyMacBlockHook: (String) -> Boolean = { mac ->
        HotspotClientEnforcer.blockViaSoftApBlocklist(wifiManager, mac)
    },
    private val onIpEnforcementChanged: (suspend (blockedIps: Set<String>, isSessionBlocked: Boolean) -> Unit)? = null
) : DnsBlockingRepository {

    private val mutex = Mutex()
    private val blockedByIp = ConcurrentHashMap<String, BlockedClientInfo>()
    private val macsWeBlocked = ConcurrentHashMap.newKeySet<String>()

    private val _blockedClientsFlow = MutableStateFlow<Set<String>>(emptySet())
    override val blockedClients: StateFlow<Set<String>> = _blockedClientsFlow.asStateFlow()

    private val _blockingState = MutableStateFlow<DnsBlockingState>(DnsBlockingState.Idle)
    override val blockingState: StateFlow<DnsBlockingState> = _blockingState.asStateFlow()

    private var _isSessionBlocked = false

    override suspend fun setSessionBlocked(blocked: Boolean) {
        _isSessionBlocked = blocked
        notifyIpEnforcementChanged()
    }

    override fun isSessionBlocked(): Boolean = _isSessionBlocked

    override suspend fun startDnsInterception() {
        mutex.withLock {
            blockedByIp.values.forEach { info ->
                if (info.mac.isNotBlank()) {
                    applyMacBlockHook(info.mac)
                }
            }
            updateBlockingState()
        }
        notifyIpEnforcementChanged()
    }

    override suspend fun stopDnsInterception() {
        mutex.withLock {
            val entries = blockedByIp.entries.toList()
            blockedByIp.clear()
            entries.forEach { (ip, info) ->
                macResolver.invalidate(ip)
                if (info.mac.isNotBlank()) {
                    removeMacBlock(info.mac)
                    macsWeBlocked.remove(info.mac.uppercase(Locale.US))
                }
            }
            publishBlockedIps(emptySet())
            _isSessionBlocked = false
            _blockingState.value = DnsBlockingState.Idle
            Log.i(TAG, "All hotspot blocks cleared")
        }
        notifyIpEnforcementChanged()
    }

    override suspend fun blockClient(clientIp: String, deviceName: String, dataUsed: String) {
        if (updateBlockedEntry(clientIp, deviceName, dataUsed)) {
            return
        }
        val mac = macResolver.resolve(clientIp)
        if (!mac.isNullOrBlank()) {
            proceedWithMacBlock(mac, clientIp, deviceName, dataUsed)
        } else {
            proceedWithIpBlock(clientIp, deviceName, dataUsed)
        }
    }

    /** Updates name/usage label when the client is already blocked (avoids stale "Unknown device / 0 B"). */
    private suspend fun updateBlockedEntry(
        clientIp: String,
        deviceName: String,
        dataUsed: String
    ): Boolean = mutex.withLock {
        val existing = blockedByIp[clientIp] ?: return@withLock false
        val mergedName = DeviceNicknameMerger.best(deviceName, existing.deviceName)
        if (mergedName == existing.deviceName && dataUsed == existing.dataUsed) {
            return@withLock true
        }
        blockedByIp[clientIp] = existing.copy(deviceName = mergedName, dataUsed = dataUsed)
        Log.d(TAG, "Updated block metadata for $mergedName ($clientIp) — $dataUsed")
        true
    }

    private suspend fun proceedWithMacBlock(
        resolvedMac: String,
        clientIp: String,
        deviceName: String,
        dataUsed: String
    ) {
        val blocked = mutex.withLock {
            val existing = blockedByIp[clientIp]
            if (existing != null && existing.mac.isNotBlank()) {
                blockedByIp[clientIp] = existing.copy(
                    deviceName = DeviceNicknameMerger.best(deviceName, existing.deviceName),
                    dataUsed = dataUsed
                )
                return@withLock true
            }
            val applied = applyMacBlockHook(resolvedMac)
            if (!applied) {
                Log.w(TAG, "MAC block API failed for $clientIp mac=$resolvedMac — falling back to IP block")
                return@withLock false
            }
            blockedByIp[clientIp] = BlockedClientInfo(resolvedMac, deviceName, dataUsed)
            macsWeBlocked.add(resolvedMac.uppercase(Locale.US))
            publishBlockedIps(blockedByIp.keys.toSet())
            updateBlockingState()
            Log.i(TAG, "Blocked $deviceName ($clientIp) mac=$resolvedMac after $dataUsed")
            true
        }
        if (blocked) {
            notifyIpEnforcementChanged()
        } else {
            proceedWithIpBlock(clientIp, deviceName, dataUsed)
        }
    }

    private suspend fun proceedWithIpBlock(
        clientIp: String,
        deviceName: String,
        dataUsed: String
    ) {
        mutex.withLock {
            if (blockedByIp.containsKey(clientIp)) return@withLock
            blockedByIp[clientIp] = BlockedClientInfo(IP_ONLY_BLOCK, deviceName, dataUsed)
            publishBlockedIps(blockedByIp.keys.toSet())
            updateBlockingState()
            Log.i(TAG, "IP-only block active for $deviceName ($clientIp) after $dataUsed")
        }
        notifyIpEnforcementChanged()
    }

    override suspend fun unblockClient(clientIp: String) {
        mutex.withLock {
            val info = blockedByIp.remove(clientIp) ?: return@withLock
            macResolver.invalidate(clientIp)
            if (info.mac.isNotBlank()) {
                removeMacBlock(info.mac)
                macsWeBlocked.remove(info.mac.uppercase(Locale.US))
            }
            publishBlockedIps(blockedByIp.keys.toSet())
            updateBlockingState()
            Log.i(TAG, "Unblocked $clientIp mac=${info.mac.ifBlank { "IP-only" }}")
        }
        notifyIpEnforcementChanged()
    }

    override fun isClientBlocked(clientIp: String): Boolean =
        _blockedClientsFlow.value.contains(clientIp)

    override fun getBlockedClients(): Set<String> = _blockedClientsFlow.value

    private suspend fun notifyIpEnforcementChanged() {
        val ips = mutex.withLock { blockedByIp.keys.toSet() }
        onIpEnforcementChanged?.invoke(ips, _isSessionBlocked)
    }

    private fun removeMacBlock(mac: String): Boolean =
        HotspotClientEnforcer.tryRestoreClientAccess(wifiManager, mac)

    private fun publishBlockedIps(ips: Set<String>) {
        _blockedClientsFlow.value = ips.toSet()
    }

    private fun updateBlockingState() {
        _blockingState.value = if (blockedByIp.isEmpty()) {
            DnsBlockingState.Idle
        } else {
            DnsBlockingState.Running
        }
    }

    private data class BlockedClientInfo(
        val mac: String,
        val deviceName: String,
        val dataUsed: String
    )

    companion object {
        private const val TAG = "DnsBlockingRepository"
        /** Empty MAC marks IP-only enforcement (no SoftAp blocklist entry). */
        const val IP_ONLY_BLOCK = ""
    }
}
