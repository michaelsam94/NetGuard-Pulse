package com.michael.netguardplus.system.vpn

import android.content.Context
import android.net.ConnectivityManager
import android.net.DnsResolver
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.CancellationSignal
import android.util.Log
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.Executors
import kotlin.coroutines.resume

/**
 * Fallback DNS resolution through the OS resolver on the real (non-VPN) network.
 * Used when raw UDP forwarding fails on some devices/Android versions.
 */
class DnsResolverForwarder(private val context: Context) {

    private val executor = Executors.newSingleThreadExecutor()

    suspend fun rawQuery(domain: String, queryType: Int): ByteArray? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null

        val network = findUnderlyingNetwork() ?: return null
        return suspendCancellableCoroutine { cont ->
            val signal = CancellationSignal()
            cont.invokeOnCancellation { signal.cancel() }

            try {
                DnsResolver.getInstance().rawQuery(
                    network,
                    domain,
                    queryType,
                    DnsResolver.CLASS_IN,
                    DnsResolver.FLAG_EMPTY,
                    executor,
                    signal,
                    object : DnsResolver.Callback<ByteArray> {
                        override fun onAnswer(answer: ByteArray, rcode: Int) {
                            if (cont.isActive) {
                                cont.resume(if (rcode == 0 && answer.isNotEmpty()) answer else null)
                            }
                        }

                        override fun onError(error: DnsResolver.DnsException) {
                            Log.d(TAG, "DnsResolver failed for $domain: ${error.message}")
                            if (cont.isActive) cont.resume(null)
                        }
                    }
                )
            } catch (e: Exception) {
                Log.d(TAG, "DnsResolver exception for $domain: ${e.message}")
                if (cont.isActive) cont.resume(null)
            }
        }
    }

    private fun findUnderlyingNetwork(): Network? {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return null
        return cm.allNetworks.firstOrNull { network ->
            val caps = cm.getNetworkCapabilities(network) ?: return@firstOrNull false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
        } ?: cm.activeNetwork
    }

    companion object {
        private const val TAG = "DnsResolverForwarder"
    }
}
