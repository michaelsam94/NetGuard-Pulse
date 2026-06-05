package com.michael.netguardplus.system.hotspot

import java.util.concurrent.ConcurrentHashMap

/**
 * Thread-safe store for per-client captive portal page content, read by [LocalVpnService].
 */
object HotspotCaptivePortalStore {

    data class ClientInfo(
        val deviceName: String,
        val dataUsed: String
    )

    private val clients = ConcurrentHashMap<String, ClientInfo>()

    fun update(clientIp: String, deviceName: String, dataUsed: String) {
        clients[clientIp] = ClientInfo(deviceName, dataUsed)
    }

    fun remove(clientIp: String) {
        clients.remove(clientIp)
    }

    fun clear() {
        clients.clear()
    }

    fun pageHtmlFor(clientIp: String?): String {
        val info = clientIp?.let { clients[it] }
        return HotspotCaptivePortalPage.limitReachedHtml(
            deviceName = info?.deviceName ?: "Unknown device",
            dataUsed = info?.dataUsed ?: "—"
        )
    }

    fun latestPageHtml(): String {
        val info = clients.values.lastOrNull()
        return HotspotCaptivePortalPage.limitReachedHtml(
            deviceName = info?.deviceName ?: "Unknown device",
            dataUsed = info?.dataUsed ?: "—"
        )
    }
}
