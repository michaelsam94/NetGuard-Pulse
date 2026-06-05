package com.michael.netguardplus.system.hotspot

import android.util.Log
import com.michael.netguardplus.system.vpn.DnsPacketHandler
import com.michael.netguardplus.system.vpn.DnsSystemResolver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketException
import java.util.concurrent.atomic.AtomicReference

/**
 * UDP DNS server bound to the hotspot gateway. Blocked clients receive A records pointing
 * at the gateway (captive portal); other clients are forwarded upstream.
 */
class HotspotDnsServer(
    private val scope: CoroutineScope,
    private val bindIp: String,
    private val portalIp: String,
    private val blockedClientIps: () -> Set<String>,
    private val sessionPaused: () -> Boolean = { false }
) {
    private val job = AtomicReference<Job?>(null)
    @Volatile
    var isRunning = false
        private set

    fun start() {
        if (job.get()?.isActive == true) return
        job.set(scope.launch(Dispatchers.IO) { runLoop() })
    }

    fun stop() {
        job.getAndSet(null)?.cancel()
        isRunning = false
    }

    private suspend fun runLoop() {
        val socket = openSocket(bindIp) ?: return
        isRunning = true
        Log.i(TAG, "Hotspot DNS server listening on $bindIp:53 (portal=$portalIp)")
        val buffer = ByteArray(4096)
        try {
            while (scope.isActive) {
                val packet = DatagramPacket(buffer, buffer.size)
                try {
                    socket.receive(packet)
                } catch (e: SocketException) {
                    if (!scope.isActive) break
                    Log.d(TAG, "DNS receive ended: ${e.message}")
                    break
                }
                val clientIp = packet.address.hostAddress ?: continue
                val queryPayload = packet.data.copyOfRange(packet.offset, packet.offset + packet.length)
                val responsePayload = resolveQuery(clientIp, queryPayload) ?: continue
                val response = DatagramPacket(
                    responsePayload,
                    responsePayload.size,
                    packet.address,
                    packet.port
                )
                socket.send(response)
            }
        } finally {
            isRunning = false
            runCatching { socket.close() }
            Log.i(TAG, "Hotspot DNS server stopped")
        }
    }

    private fun openSocket(bindIp: String): DatagramSocket? {
        return try {
            DatagramSocket(null).apply {
                reuseAddress = true
                bind(InetSocketAddress(bindIp, DNS_PORT))
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not bind DNS on $bindIp:$DNS_PORT (${e.message}) — trying any interface")
            try {
                DatagramSocket(null).apply {
                    reuseAddress = true
                    bind(InetSocketAddress(DNS_PORT))
                }
            } catch (e2: Exception) {
                Log.e(TAG, "Hotspot DNS server failed to bind port $DNS_PORT", e2)
                null
            }
        }
    }

    private fun resolveQuery(clientIp: String, queryPayload: ByteArray): ByteArray? {
        if (sessionPaused()) {
            Log.d(TAG, "Session bandwidth pause — dropping DNS for $clientIp")
            return null
        }
        if (blockedClientIps().contains(clientIp)) {
            Log.d(TAG, "Captive portal DNS for blocked client $clientIp")
            return DnsPacketHandler.buildRawCaptivePortalResponse(queryPayload, portalIp)
        }
        val upstream = forwardDnsQuery(queryPayload)
        if (upstream != null) return upstream
        return DnsPacketHandler.buildRawCaptivePortalResponse(queryPayload, portalIp)
    }

    private fun forwardDnsQuery(queryPayload: ByteArray): ByteArray? {
        val servers = DnsSystemResolver.FALLBACK_UPSTREAM_DNS
        for (server in servers) {
            try {
                DatagramSocket().use { socket ->
                    socket.soTimeout = 4_000
                    val request = DatagramPacket(
                        queryPayload,
                        queryPayload.size,
                        InetAddress.getByName(server),
                        DNS_PORT
                    )
                    socket.send(request)
                    val buffer = ByteArray(4096)
                    val response = DatagramPacket(buffer, buffer.size)
                    socket.receive(response)
                    return buffer.copyOfRange(response.offset, response.offset + response.length)
                }
            } catch (e: Exception) {
                Log.v(TAG, "Upstream DNS $server failed: ${e.message}")
            }
        }
        return null
    }

    companion object {
        private const val TAG = "HotspotDnsServer"
        private const val DNS_PORT = 53
    }
}
