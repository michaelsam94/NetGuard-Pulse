package com.michael.netguardplus.system.vpn

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import com.michael.netguardplus.system.stats.isHotspotInterfaceName
import java.net.Inet4Address
import java.net.NetworkInterface

object DnsSystemResolver {

    val FALLBACK_UPSTREAM_DNS = listOf("8.8.8.8", "8.8.4.4", "1.1.1.1", "1.0.0.1")

    private val FALLBACK_DNS = FALLBACK_UPSTREAM_DNS

    fun collectLinkDnsServers(context: Context): List<String> {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return emptyList()
        val servers = linkedSetOf<String>()

        for (network in cm.allNetworks) {
            val caps = cm.getNetworkCapabilities(network) ?: continue
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue
            if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) continue

            val link = cm.getLinkProperties(network) ?: continue
            for (dns in link.dnsServers) {
                if (dns is Inet4Address && !dns.isLoopbackAddress) {
                    dns.hostAddress?.let { servers.add(it) }
                }
            }
        }

        Log.i(TAG, "Link DNS for capture routes: ${servers.joinToString()}")
        return servers.toList()
    }

    /** IPv4 addresses assigned to the phone's hotspot/tether interface (local DNS gateway). */
    fun collectHotspotHostIps(): Set<String> {
        val ips = mutableSetOf<String>()
        try {
            NetworkInterface.getNetworkInterfaces()?.toList().orEmpty().forEach { ni ->
                if (!ni.isUp || ni.isLoopback) return@forEach
                val iface = ni.name ?: return@forEach
                if (!isHotspotInterfaceName(iface)) return@forEach
                ni.inetAddresses.toList().forEach { addr ->
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        addr.hostAddress?.let { ips.add(it) }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read hotspot host IPs", e)
        }
        if (ips.isNotEmpty()) {
            Log.i(TAG, "Hotspot host IPs excluded from VPN DNS routes: ${ips.joinToString()}")
        }
        return ips
    }

    /**
     * Upstream resolvers used when forwarding allowed DNS queries.
     */
    fun collectUpstreamDnsServers(context: Context): List<String> {
        val link = collectLinkDnsServers(context)
        val servers = linkedSetOf<String>()
        servers.addAll(link)
        servers.addAll(FALLBACK_DNS)
        Log.i(TAG, "Upstream DNS for forwarding: ${servers.joinToString()}")
        return servers.toList()
    }

    private const val TAG = "DnsSystemResolver"
}
