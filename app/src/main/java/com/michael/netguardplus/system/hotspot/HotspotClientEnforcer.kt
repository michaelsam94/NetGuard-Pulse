package com.michael.netguardplus.system.hotspot

import android.content.Context
import android.net.MacAddress
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import java.util.Locale

/**
 * Blocks or restores hotspot clients via Soft AP APIs (public on API 33+, reflection below).
 */
object HotspotClientEnforcer {

    private const val TAG = "HotspotClientEnforcer"
    private val shaperPausedMacs = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    fun tryDisconnectClient(
        context: Context,
        wifiManager: WifiManager,
        mac: String?,
        ip: String? = null
    ): Boolean {
        val realMac = mac?.takeIf { HotspotClientMerger.isRealMac(it) }?.let { normalizeMac(it) }
            ?: run {
                Log.w(TAG, "Cannot disconnect — no MAC for ip=$ip")
                return false
            }
        val success = blockViaSoftApBlocklist(wifiManager, realMac) ||
            blockViaReflection(wifiManager, realMac, disconnect = true)
        if (success) {
            Log.i(TAG, "Disconnect/block issued for mac=$realMac ip=$ip")
        } else {
            Log.w(TAG, "No disconnect path succeeded for mac=$realMac ip=$ip")
        }
        return success
    }

    fun blockViaSoftApBlocklist(wifiManager: WifiManager, mac: String): Boolean {
        val realMac = normalizeMac(mac)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (blockViaSoftApConfig(wifiManager, realMac)) return true
        }
        return trySoftApBlocklistReflection(wifiManager, realMac)
    }

    fun blockViaReflection(
        wifiManager: WifiManager,
        mac: String,
        disconnect: Boolean
    ): Boolean {
        val realMac = normalizeMac(mac)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (disconnect && blockViaConnectedStationReflection(wifiManager, realMac)) {
                return true
            }
            if (tryAllowOrDisconnectClient(wifiManager, realMac, disconnect)) return true
        }
        return if (disconnect) {
            tryRuntimeWifiDisconnect(wifiManager, realMac)
        } else {
            tryRemoveFromSoftApBlocklist(wifiManager, realMac)
        }
    }

    /**
     * Android 11–12: disconnect using the live [WifiClient] from [getSoftApConnectedStations].
     */
    @RequiresApi(Build.VERSION_CODES.R)
    private fun blockViaConnectedStationReflection(
        wifiManager: WifiManager,
        mac: String
    ): Boolean {
        return try {
            val stationsMethod = wifiManager.javaClass.methods.firstOrNull { m ->
                m.name == "getSoftApConnectedStations" && m.parameterTypes.isEmpty()
            } ?: return false
            @Suppress("UNCHECKED_CAST")
            val stations = stationsMethod.invoke(wifiManager) as? List<Any> ?: return false
            val target = stations.firstOrNull { client ->
                clientMac(client).equals(mac, ignoreCase = true)
            } ?: run {
                Log.w(TAG, "WifiClient not found for mac=$mac")
                return false
            }
            val method = wifiManager.javaClass.getDeclaredMethod(
                "allowOrDisconnectClient",
                target.javaClass,
                Int::class.javaPrimitiveType
            )
            method.isAccessible = true
            val result = method.invoke(wifiManager, target, 0) as? Boolean ?: true
            Log.i(TAG, "Reflection disconnect issued for mac=$mac result=$result")
            result
        } catch (e: Exception) {
            Log.e(TAG, "Reflection block failed: ${e.message}")
            false
        }
    }

    private fun clientMac(client: Any): String {
        return try {
            val macMethod = client.javaClass.methods.firstOrNull { m ->
                m.name.equals("getMacAddress", ignoreCase = true)
            }
            macMethod?.invoke(client)?.toString().orEmpty()
        } catch (_: Exception) {
            ""
        }
    }

    private fun tryAllowOrDisconnectClient(
        wifiManager: WifiManager,
        mac: String,
        disconnect: Boolean
    ): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
        return try {
            val wifiClientClass = Class.forName("android.net.wifi.WifiClient")
            val macAddress = MacAddress.fromString(mac)
            val client = wifiClientClass.getConstructor(MacAddress::class.java).newInstance(macAddress)
            val method = wifiManager.javaClass.getDeclaredMethod(
                "allowOrDisconnectClient",
                wifiClientClass,
                Int::class.javaPrimitiveType
            )
            method.isAccessible = true
            val flag = if (disconnect) 0 else 1
            val result = method.invoke(wifiManager, client, flag) as? Boolean ?: true
            Log.i(TAG, "allowOrDisconnectClient($mac, disconnect=$disconnect) -> $result")
            result
        } catch (e: Exception) {
            Log.d(TAG, "allowOrDisconnectClient unavailable: ${e.message}")
            false
        }
    }

    private fun tryRuntimeWifiDisconnect(wifiManager: WifiManager, mac: String): Boolean {
        val macAddress = MacAddress.fromString(mac)
        val targetMethods = setOf(
            "blockClient",
            "disconnectSoftApClient",
            "disconnectSoftApClients",
            "forceDisconnectClient",
            "disableClient",
            "removeClient"
        )
        for (method in wifiManager.javaClass.methods) {
            if (method.name !in targetMethods) continue
            try {
                val result = when (method.parameterTypes.size) {
                    1 -> when (method.parameterTypes[0].name) {
                        MacAddress::class.java.name -> method.invoke(wifiManager, macAddress)
                        "java.util.List" -> method.invoke(wifiManager, listOf(macAddress))
                        "java.lang.String" -> method.invoke(wifiManager, mac)
                        else -> null
                    }
                    else -> null
                }
                Log.i(TAG, "WifiManager.${method.name}($mac) -> $result")
                if (result == true || (result == null && method.returnType == Void.TYPE)) {
                    return true
                }
            } catch (e: Exception) {
                Log.d(TAG, "WifiManager.${method.name} failed: ${e.message}")
            }
        }
        return false
    }

    fun tryRestoreClientAccess(
        wifiManager: WifiManager,
        mac: String?
    ): Boolean {
        val realMac = mac?.takeIf { HotspotClientMerger.isRealMac(it) }?.let { normalizeMac(it) }
            ?: return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (unblockViaSoftApConfig(wifiManager, realMac)) return true
        }
        return tryRemoveFromSoftApBlocklist(wifiManager, realMac) ||
            resumeViaConnectedStationReflection(wifiManager, realMac)
    }

    /** Briefly pauses a hotspot client at the WiFi layer for session bandwidth shaping. */
    fun pauseClientTraffic(wifiManager: WifiManager, mac: String): Boolean {
        val realMac = normalizeMac(mac)
        val paused = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            blockViaConnectedStationReflection(wifiManager, realMac) ||
                tryAllowOrDisconnectClient(wifiManager, realMac, disconnect = true)
        } else {
            false
        } || blockViaSoftApBlocklist(wifiManager, realMac)
        if (paused) {
            shaperPausedMacs.add(realMac)
            Log.i(TAG, "Paused client for bandwidth shaping: $realMac")
        } else {
            Log.w(TAG, "Failed to pause client for bandwidth shaping: $realMac")
        }
        return paused
    }

    /** Restores a client paused by [pauseClientTraffic]. */
    fun resumeClientTraffic(wifiManager: WifiManager, mac: String): Boolean {
        val realMac = normalizeMac(mac)
        val resumed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            resumeViaConnectedStationReflection(wifiManager, realMac) ||
                tryAllowOrDisconnectClient(wifiManager, realMac, disconnect = false)
        } else {
            false
        } || tryRemoveFromSoftApBlocklist(wifiManager, realMac)
        if (resumed || shaperPausedMacs.contains(realMac)) {
            shaperPausedMacs.remove(realMac)
            Log.d(TAG, "Resumed client after bandwidth shaping: $realMac")
        }
        return resumed
    }

    /** Clears any WiFi-layer pauses left over from session bandwidth shaping. */
    fun clearShaperBlocklistEntries(wifiManager: WifiManager) {
        val macs = shaperPausedMacs.toList()
        shaperPausedMacs.clear()
        for (mac in macs) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                resumeViaConnectedStationReflection(wifiManager, mac)
                tryAllowOrDisconnectClient(wifiManager, mac, disconnect = false)
            }
            tryRemoveFromSoftApBlocklist(wifiManager, mac)
            Log.i(TAG, "Cleared bandwidth shaper pause for $mac")
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun resumeViaConnectedStationReflection(
        wifiManager: WifiManager,
        mac: String
    ): Boolean {
        return try {
            val stationsMethod = wifiManager.javaClass.methods.firstOrNull { m ->
                m.name == "getSoftApConnectedStations" && m.parameterTypes.isEmpty()
            } ?: return false
            @Suppress("UNCHECKED_CAST")
            val stations = stationsMethod.invoke(wifiManager) as? List<Any> ?: return false
            val target = stations.firstOrNull { client ->
                clientMac(client).equals(mac, ignoreCase = true)
            } ?: return false
            val method = wifiManager.javaClass.getDeclaredMethod(
                "allowOrDisconnectClient",
                target.javaClass,
                Int::class.javaPrimitiveType
            )
            method.isAccessible = true
            val result = method.invoke(wifiManager, target, 1) as? Boolean ?: true
            Log.d(TAG, "Reflection resume issued for mac=$mac result=$result")
            result
        } catch (e: Exception) {
            Log.d(TAG, "Reflection resume failed: ${e.message}")
            false
        }
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun blockViaSoftApConfig(wifiManager: WifiManager, mac: String): Boolean {
        val ok = trySoftApBlocklistReflection(wifiManager, mac)
        if (ok) Log.i(TAG, "setSoftApConfiguration result=$ok for mac=$mac")
        return ok
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun unblockViaSoftApConfig(wifiManager: WifiManager, mac: String): Boolean =
        tryRemoveFromSoftApBlocklist(wifiManager, mac)

    private fun tryRemoveFromSoftApBlocklist(wifiManager: WifiManager, mac: String): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
        return try {
            val macAddress = MacAddress.fromString(mac)
            val getConfig = wifiManager.javaClass.getMethod("getSoftApConfiguration")
            val config = getConfig.invoke(wifiManager) ?: return false
            val blockedMethod = config.javaClass.methods.firstOrNull { it.name == "getBlockedClientList" }
                ?: return false
            @Suppress("UNCHECKED_CAST")
            val existing = (blockedMethod.invoke(config) as? List<MacAddress>)?.toMutableList()
                ?: return false
            if (!existing.remove(macAddress)) {
                Log.d(TAG, "$mac not on SoftAp blocklist")
                return false
            }
            val builderClass = Class.forName("android.net.wifi.SoftApConfiguration\$Builder")
            val builder = builderClass.getConstructor(config.javaClass).newInstance(config)
            builderClass.getMethod("setBlockedClientList", List::class.java).invoke(builder, existing)
            val updated = builderClass.getMethod("build").invoke(builder)
            val setConfig = wifiManager.javaClass.getMethod("setSoftApConfiguration", config.javaClass)
            val ok = setConfig.invoke(wifiManager, updated) as? Boolean ?: false
            if (ok) Log.i(TAG, "Removed $mac from SoftAp blocklist")
            ok
        } catch (e: Exception) {
            Log.d(TAG, "SoftAp blocklist remove unavailable: ${e.message}")
            false
        }
    }

    private fun trySoftApBlocklistReflection(wifiManager: WifiManager, mac: String): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
        return try {
            val macAddress = MacAddress.fromString(mac)
            val getConfig = wifiManager.javaClass.getMethod("getSoftApConfiguration")
            val config = getConfig.invoke(wifiManager) ?: return false
            val blockedMethod = config.javaClass.methods.firstOrNull { it.name == "getBlockedClientList" }
                ?: return false
            @Suppress("UNCHECKED_CAST")
            val existing = (blockedMethod.invoke(config) as? List<MacAddress>)?.toMutableList()
                ?: mutableListOf()
            if (existing.contains(macAddress)) {
                Log.d(TAG, "$mac already on SoftAp blocklist")
                return true
            }
            existing.add(macAddress)
            val builderClass = Class.forName("android.net.wifi.SoftApConfiguration\$Builder")
            val builder = builderClass.getConstructor(config.javaClass).newInstance(config)
            builderClass.getMethod("setBlockedClientList", List::class.java).invoke(builder, existing)
            val updated = builderClass.getMethod("build").invoke(builder)
            val setConfig = wifiManager.javaClass.getMethod("setSoftApConfiguration", config.javaClass)
            val ok = setConfig.invoke(wifiManager, updated) as? Boolean ?: false
            if (ok) Log.i(TAG, "Added $mac to SoftAp blocklist via setSoftApConfiguration")
            ok
        } catch (e: Exception) {
            Log.d(TAG, "SoftAp blocklist unavailable: ${e.message}")
            false
        }
    }

    private fun normalizeMac(mac: String): String = mac.uppercase(Locale.US)
}
