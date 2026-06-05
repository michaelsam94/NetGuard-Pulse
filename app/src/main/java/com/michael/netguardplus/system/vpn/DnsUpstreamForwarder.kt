package com.michael.netguardplus.system.vpn

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.VpnService
import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/**
 * Forwards DNS queries to upstream resolvers on the real network (Wi‑Fi/mobile),
 * bypassing the VPN tunnel.
 */
class DnsUpstreamForwarder(
    private val context: Context,
    private val vpnService: VpnService
) {
    private var routedServers: List<String> = emptyList()

    fun updateRoutedServers(servers: List<String>) {
        routedServers = servers.filter { !isLocalOrVirtualDns(it) }
    }

    fun forwardToFamilyProvider(payload: ByteArray, providerServers: List<String>): ByteArray? {
        val targets = providerServers.filter { !isLocalOrVirtualDns(it) }.distinct()
        if (targets.isEmpty()) return null

        for (target in targets) {
            forwardWithProtect(payload, target)?.let { return it }
        }

        val network = findUnderlyingNetwork()
        if (network != null) {
            for (target in targets) {
                val socket = DatagramSocket()
                try {
                    network.bindSocket(socket)
                    vpnService.protect(socket)
                    forwardOnNetwork(socket, payload, target)?.let { return it }
                } catch (_: Exception) {
                    try {
                        socket.close()
                    } catch (_: Exception) {
                        // ignore
                    }
                }
            }
        }

        return null
    }

    private fun findUnderlyingNetwork(): android.net.Network? {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return null
        return cm.allNetworks.firstOrNull { n ->
            val caps = cm.getNetworkCapabilities(n) ?: return@firstOrNull false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
        } ?: cm.activeNetwork
    }

    fun forward(payload: ByteArray, preferredServer: String? = null): ByteArray? {
        val targets = buildTargetList(preferredServer)
        if (targets.isEmpty()) return null

        for (target in targets) {
            forwardWithProtect(payload, target)?.let { return it }
        }

        val cm = context.getSystemService(ConnectivityManager::class.java)
        val network = findUnderlyingNetwork()

        if (network != null) {
            for (target in targets) {
                val socket = DatagramSocket()
                try {
                    network.bindSocket(socket)
                    vpnService.protect(socket)
                    forwardOnNetwork(socket, payload, target)?.let { return it }
                } catch (_: Exception) {
                    try {
                        socket.close()
                    } catch (_: Exception) {
                        // ignore
                    }
                }
            }
        }

        return null
    }

    private fun buildTargetList(preferredServer: String?): List<String> {
        return buildList {
            if (!preferredServer.isNullOrBlank() && !isLocalOrVirtualDns(preferredServer)) {
                add(preferredServer)
            }
            addAll(routedServers)
            addAll(FALLBACK_DNS)
        }.distinct().filter { !isLocalOrVirtualDns(it) }.ifEmpty { FALLBACK_DNS }
    }

    private fun isLocalOrVirtualDns(ip: String): Boolean {
        if (ip.isBlank() || ip == "0.0.0.0") return true
        if (ip == VPN_TUNNEL_IP || ip == VPN_TUNNEL_IPV6) return true
        if (ip.startsWith("127.")) return true
        if (ip.startsWith("10.255.254.")) return true
        if (ip.startsWith("fd00:6400:6400:")) return true
        return false
    }

    private fun forwardWithProtect(payload: ByteArray, target: String): ByteArray? {
        return try {
            DatagramSocket().use { socket ->
                vpnService.protect(socket)
                forwardOnNetwork(socket, payload, target)
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun forwardOnNetwork(
        socket: DatagramSocket,
        payload: ByteArray,
        upstream: String
    ): ByteArray? {
        return try {
            socket.soTimeout = DNS_TIMEOUT_MS
            socket.send(
                DatagramPacket(
                    payload,
                    payload.size,
                    InetAddress.getByName(upstream),
                    DNS_PORT
                )
            )
            val buffer = ByteArray(4096)
            val response = DatagramPacket(buffer, buffer.size)
            socket.receive(response)
            response.data.copyOfRange(0, response.length)
        } catch (e: Exception) {
            Log.v(TAG, "Forward to $upstream failed: ${e.message}")
            null
        }
    }

    companion object {
        private const val TAG = "DnsUpstreamForwarder"
        private const val DNS_PORT = 53
        private const val VPN_TUNNEL_IP = "10.255.254.1"
        private const val VPN_TUNNEL_IPV6 = "fd00:6400:6400::1"
        private val FALLBACK_DNS = listOf("8.8.8.8", "8.8.4.4", "1.1.1.1", "1.0.0.1")
        private const val DNS_TIMEOUT_MS = 2500
    }
}
