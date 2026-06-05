package com.michael.netguardplus.system.hotspot

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.util.Log
import com.michael.netguardplus.system.stats.isHotspotInterfaceName
import com.michael.netguardplus.system.vpn.DnsSystemResolver
import java.net.Inet4Address

/**
 * Resolves the active hotspot AP network (ap0) for binding local sockets to the tether interface.
 */
object HotspotApNetwork {

    data class ApInfo(
        val network: Network?,
        val interfaceName: String,
        val gatewayIpv4: String,
        val subnetPrefixLength: Int
    )

    fun resolve(context: Context): ApInfo? {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        if (cm != null) {
            for (network in cm.allNetworks) {
                val caps = cm.getNetworkCapabilities(network) ?: continue
                if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) continue
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue

                val link: LinkProperties = cm.getLinkProperties(network) ?: continue
                val iface = link.interfaceName ?: continue
                if (!isHotspotInterfaceName(iface)) continue

                val gateway = link.linkAddresses
                    .mapNotNull { it.address }
                    .filterIsInstance<Inet4Address>()
                    .firstOrNull { !it.isLoopbackAddress }
                    ?.hostAddress
                    ?: link.routes
                        .mapNotNull { route ->
                            route.gateway?.takeIf { it is Inet4Address && !it.isLoopbackAddress } as? Inet4Address
                        }
                        .firstOrNull()
                        ?.hostAddress

                if (gateway == null) continue

                val prefix = link.linkAddresses
                    .firstOrNull { it.address is Inet4Address && !it.address.isLoopbackAddress }
                    ?.prefixLength
                    ?: 24

                Log.i(TAG, "Hotspot AP network on $iface gateway=$gateway/$prefix")
                return ApInfo(
                    network = network,
                    interfaceName = iface,
                    gatewayIpv4 = gateway,
                    subnetPrefixLength = prefix
                )
            }
        }

        // Fallback: Query network interfaces directly to find hotspot AP properties
        try {
            val interfaces = java.net.NetworkInterface.getNetworkInterfaces()?.toList()
            if (interfaces != null) {
                for (ni in interfaces) {
                    if (!ni.isUp || ni.isLoopback) continue
                    val iface = ni.name ?: continue
                    if (!isHotspotInterfaceName(iface)) continue

                    for (ia in ni.interfaceAddresses) {
                        val addr = ia.address
                        if (addr is Inet4Address && !addr.isLoopbackAddress) {
                            val gateway = addr.hostAddress ?: continue
                            val prefix = ia.networkPrefixLength.toInt()
                            Log.i(TAG, "Resolved fallback hotspot AP network on $iface gateway=$gateway/$prefix")
                            return ApInfo(
                                network = null,
                                interfaceName = iface,
                                gatewayIpv4 = gateway,
                                subnetPrefixLength = prefix
                            )
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to resolve fallback hotspot AP info", e)
        }

        val hostIps = DnsSystemResolver.collectHotspotHostIps()
        if (hostIps.isNotEmpty()) {
            val gateway = hostIps.first()
            Log.i(TAG, "Resolved hotspot AP from active host IP fallback gateway=$gateway/24")
            return ApInfo(
                network = null,
                interfaceName = "ap0",
                gatewayIpv4 = gateway,
                subnetPrefixLength = 24
            )
        }
        return null
    }

    fun tetherSubnetRoute(gatewayIpv4: String, prefixLength: Int = 24): String? {
        val parts = gatewayIpv4.split('.')
        if (parts.size != 4) return null
        val maskBits = prefixLength.coerceIn(8, 30)
        val base = parts.map { it.toInt() and 0xFF }.toIntArray()
        val shift = 32 - maskBits
        val networkInt = ((base[0] shl 24) or (base[1] shl 16) or (base[2] shl 8) or base[3]) and (-1 shl shift)
        return "${(networkInt shr 24) and 0xFF}.${(networkInt shr 16) and 0xFF}." +
            "${(networkInt shr 8) and 0xFF}.${networkInt and 0xFF}"
    }

    private const val TAG = "HotspotApNetwork"
}
