package com.michael.netguardplus.system.hotspot

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkAddress
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Looper
import android.util.Log
import com.michael.netguardplus.system.stats.TetheringMonitor
import com.michael.netguardplus.system.stats.isHotspotInterfaceName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Discovers hotspot clients by scanning the phone's local tether subnet (e.g. 10.126.57.x).
 * Uses NetworkInterface fallback when OEMs (Oppo, MIUI) don't expose the AP via ConnectivityManager.
 */
class LocalNetworkClientScanner(context: Context) {

    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val scanning = AtomicBoolean(false)

    private val _clients = MutableStateFlow<List<TetheringMonitor.DiscoveredClient>>(emptyList())
    val clients: StateFlow<List<TetheringMonitor.DiscoveredClient>> = _clients.asStateFlow()

    private val _hotspotActive = MutableStateFlow(false)
    val hotspotActive: StateFlow<Boolean> = _hotspotActive.asStateFlow()

    private val _tetherInterface = MutableStateFlow<String?>(null)
    val tetherInterface: StateFlow<String?> = _tetherInterface.asStateFlow()

    private var registered = false
    private var lastPingSweepMs = 0L
    private var lastSubnetScanMs = 0L

    fun start() {
        if (registered) return
        try {
            connectivityManager.registerNetworkCallback(
                buildNetworkRequest(),
                createNetworkCallback()
            )
            registered = true
        } catch (e: Exception) {
            Log.w(TAG, "Network callback registration failed, using poll-only discovery", e)
        }
        refresh()
    }

    fun refresh() {
        val hasActiveTetherNetwork = hasActiveTetherNetwork()
        if (shouldClearSnapshot(_hotspotActive.value, hasActiveTetherNetwork)) {
            _hotspotActive.value = false
            _tetherInterface.value = null
            _clients.value = emptyList()
            return
        }
        if (!hasActiveTetherNetwork) {
            return
        }
        probeAllNetworks()
        probeFromNetworkInterfaces()
    }

    /** Clears cached clients and throttle timers, then runs a fresh scan immediately. */
    fun forceRefresh() {
        lastPingSweepMs = 0L
        lastSubnetScanMs = 0L
        _clients.value = emptyList()
        refresh()
    }

    private fun buildNetworkRequest(): NetworkRequest {
        val builder = NetworkRequest.Builder()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            builder.addCapability(NetworkCapabilities.NET_CAPABILITY_LOCAL_NETWORK)
        } else {
            builder.addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
        }
        return builder.build()
    }

    private fun createNetworkCallback() = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            probeNetwork(network)
        }

        override fun onLinkPropertiesChanged(
            network: Network,
            linkProperties: android.net.LinkProperties
        ) {
            probeNetwork(network)
        }

        override fun onLost(network: Network) {
            if (!hasActiveTetherNetwork()) {
                _hotspotActive.value = false
                _tetherInterface.value = null
                _clients.value = emptyList()
            }
        }
    }

    private fun probeAllNetworks() {
        connectivityManager.allNetworks.forEach { network ->
            probeNetwork(network)
        }
    }

    /**
     * Oppo/Qualcomm: soft AP runs on wlan1 but is NOT registered as a ConnectivityManager Network.
     */
    private fun probeFromNetworkInterfaces() {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            for (ni in interfaces) {
                if (!ni.isUp || ni.isLoopback) continue
                val iface = ni.name ?: continue
                if (!isHotspotInterfaceName(iface)) continue

                val inet4 = ni.inetAddresses.toList()
                    .filterIsInstance<Inet4Address>()
                    .firstOrNull { !it.isLoopbackAddress && isPrivateIp(it.hostAddress) }
                    ?: continue

                Log.i(TAG, "Found tether interface $iface at ${inet4.hostAddress}")
                _tetherInterface.value = iface
                _hotspotActive.value = true

                val fromNeighbors = scanNeighborsOnInterface(iface, inet4.hostAddress)
                if (fromNeighbors.isNotEmpty()) {
                    _clients.value = fromNeighbors
                    Log.i(TAG, "Neighbor scan on $iface found ${fromNeighbors.size} device(s)")
                    return
                }
                scanSubnet(inet4, 24, iface)
            }
        } catch (e: Exception) {
            Log.w(TAG, "NetworkInterface probe failed", e)
        }
    }

    private fun hasActiveTetherNetwork(): Boolean {
        val fromCm = connectivityManager.allNetworks.any { network ->
            val linkProperties = connectivityManager.getLinkProperties(network) ?: return@any false
            isTetherInterface(linkProperties.interfaceName, linkProperties)
        }
        if (fromCm) return true
        return NetworkInterface.getNetworkInterfaces()?.toList().orEmpty().any { ni ->
            ni.isUp && !ni.isLoopback && isHotspotInterfaceName(ni.name)
        }
    }

    private fun isTetherInterface(iface: String?, linkProperties: android.net.LinkProperties): Boolean {
        return iface != null && isHotspotInterfaceName(iface, linkProperties.linkAddresses)
    }

    private fun probeNetwork(network: Network) {
        val linkProperties = connectivityManager.getLinkProperties(network) ?: return
        val iface = linkProperties.interfaceName ?: return
        if (!isTetherInterface(iface, linkProperties)) return

        _tetherInterface.value = iface

        val localAddress = linkProperties.linkAddresses.firstOrNull { addr ->
            addr.address is Inet4Address && !addr.address.isLoopbackAddress
        } ?: return

        _hotspotActive.value = true

        val fromNeighbors = scanNeighborsOnInterface(iface, localAddress.address.hostAddress)
        if (fromNeighbors.isNotEmpty()) {
            _clients.value = fromNeighbors
            Log.i(TAG, "Neighbor scan on $iface found ${fromNeighbors.size} device(s)")
            return
        }
        scanSubnet(localAddress.address as Inet4Address, localAddress.prefixLength, iface)
    }

    private fun scanNeighborsOnInterface(iface: String, gatewayIp: String?): List<TetheringMonitor.DiscoveredClient> {
        readNeighborsFromShell(iface).takeIf { it.isNotEmpty() }?.let { return it }

        val now = System.currentTimeMillis()
        if (now - lastPingSweepMs < PING_SWEEP_MIN_INTERVAL_MS) {
            return emptyList()
        }
        lastPingSweepMs = now

        // Plain ping works without CAP_NET_RAW; interface-bound ping and ip neigh are blocked on Oppo/MIUI.
        val fromPing = discoverViaPlainPingSweep(iface, gatewayIp)
        if (fromPing.isNotEmpty()) {
            Log.i(TAG, "Plain ping sweep on $iface found ${fromPing.size} device(s)")
        }
        return fromPing
    }

    private fun discoverViaPlainPingSweep(iface: String, gatewayIp: String?): List<TetheringMonitor.DiscoveredClient> {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            scope.launch {
                val found = discoverViaPlainPingSweepBlocking(iface, gatewayIp)
                if (found.isNotEmpty()) {
                    _clients.value = found
                    Log.i(TAG, "Async plain ping sweep found ${found.size} device(s)")
                }
            }
            return emptyList()
        }
        return discoverViaPlainPingSweepBlocking(iface, gatewayIp)
    }

    private fun discoverViaPlainPingSweepBlocking(
        iface: String,
        gatewayIp: String?
    ): List<TetheringMonitor.DiscoveredClient> {
        val hostAddress = gatewayIp ?: return emptyList()
        val parts = hostAddress.split('.')
        if (parts.size != 4) return emptyList()
        val subnetBase = "${parts[0]}.${parts[1]}.${parts[2]}"
        val myHost = parts[3].toIntOrNull() ?: return emptyList()

        // Thread-safe collections shared across all parallel ping coroutines
        val found = java.util.concurrent.CopyOnWriteArrayList<TetheringMonitor.DiscoveredClient>()
        val activeProcs = java.util.concurrent.ConcurrentLinkedQueue<Process>()

        fun killRemaining() = activeProcs.forEach { runCatching { it.destroyForcibly() } }

        return runBlocking(Dispatchers.IO) {
            // Launch all pings in parallel, tracking the Process handle for early kill
            val jobs = (1..254).filter { it != myHost }.map { host ->
                async {
                    val ip = "$subnetBase.$host"
                    val proc = try {
                        ProcessBuilder("ping", "-c", "1", "-W", "1", ip)
                            .redirectErrorStream(true)
                            .start()
                            .also { activeProcs.add(it) }
                    } catch (_: Exception) { return@async }
                    try {
                        if (proc.waitFor() == 0) {
                            found.add(
                                TetheringMonitor.DiscoveredClient(
                                    macAddress = "IP-${ip.replace('.', '-')}",
                                    ipAddress = ip,
                                    deviceName = "Connected Device ($ip)"
                                )
                            )
                        }
                    } finally {
                        activeProcs.remove(proc)
                    }
                }
            }

            // Fast-exit path: connected devices reply in <10ms, so poll at 50ms intervals.
            // As soon as one result appears, kill all remaining ping processes and return.
            // This reduces best-case from ~1500ms to ~50ms.
            var elapsed = 0L
            while (elapsed < FAST_EXIT_SWEEP_MS && found.isEmpty()) {
                delay(SWEEP_POLL_MS)
                elapsed += SWEEP_POLL_MS
            }

            if (found.isNotEmpty()) {
                Log.i(TAG, "Fast-exit ping sweep: ${found.size} device(s) in ~${elapsed}ms")
                killRemaining()          // destroy all remaining OS ping processes
                jobs.awaitAll()          // returns immediately now that procs are killed
                return@runBlocking enrichWithNeighborMacs(iface, found.toList())
            }

            // No quick response — wait for the remainder of the full timeout
            val remaining = PING_SWEEP_TIMEOUT_MS - elapsed
            withTimeoutOrNull(remaining) { jobs.awaitAll() }
                ?: run { killRemaining(); jobs.awaitAll() }

            Log.d(TAG, "Full ping sweep: ${found.size} device(s)")
            enrichWithNeighborMacs(iface, found.toList())
        }
    }

    private fun enrichWithNeighborMacs(
        iface: String,
        clients: List<TetheringMonitor.DiscoveredClient>
    ): List<TetheringMonitor.DiscoveredClient> {
        if (clients.isEmpty()) return clients
        val neighByIp = readNeighborsFromShell(iface).associateBy { it.ipAddress }
        return clients.map { client ->
            neighByIp[client.ipAddress] ?: client
        }
    }

    fun resolveMacByIp(ip: String): String? {
        val iface = _tetherInterface.value
            ?: TetheringMonitor().resolveActiveTetherInterfaces().firstOrNull()
            ?: return null
        return readNeighborsFromShell(iface)
            .firstOrNull { client ->
                client.ipAddress == ip && HotspotClientMerger.isRealMac(client.macAddress)
            }
            ?.macAddress
    }

    private fun readNeighborsFromShell(iface: String): List<TetheringMonitor.DiscoveredClient> {
        // Run all three shell sources in parallel so a slow/blocked source doesn't
        // serialise the others. Worst-case latency drops from 3 × 1200ms to 1200ms.
        // Use an Array for shared output; CountDownLatch provides the happens-before
        // relationship that makes the writes visible after latch.await().
        val latch = java.util.concurrent.CountDownLatch(3)
        val out = Array(3) { "" }  // [0]=neigh, [1]=arp, [2]=leases

        scope.launch { out[0] = execShell("ip neigh show dev $iface"); latch.countDown() }
        scope.launch { out[1] = execShell("cat /proc/net/arp"); latch.countDown() }
        scope.launch {
            out[2] = execShell("cat /data/misc/dhcp/dnsmasq.leases 2>/dev/null")
            latch.countDown()
        }
        // Wait at most SHELL_TIMEOUT_MS + a small buffer for all three to complete
        latch.await(SHELL_TIMEOUT_MS + 250L, java.util.concurrent.TimeUnit.MILLISECONDS)

        val neighOutput = out[0]
        val arpOutput = out[1]
        val leaseOutput = out[2]
        val results = mutableListOf<TetheringMonitor.DiscoveredClient>()

        if (neighOutput.isNotBlank()) {
            Log.d(TAG, "ip neigh on $iface: ${neighOutput.lines().size} lines")
            neighOutput.lineSequence().forEach { line ->
                parseIpNeighLine(line)?.let { results.add(it) }
            }
        }

        if (results.isEmpty() && arpOutput.isNotBlank()) {
            arpOutput.lineSequence().drop(1).forEach { line ->
                parseArpLine(line, iface)?.let { results.add(it) }
            }
        }

        if (leaseOutput.isNotBlank()) {
            leaseOutput.lineSequence().forEach { line ->
                parseDhcpLeaseLine(line)?.let { results.add(it) }
            }
        }

        return results.distinctBy { it.ipAddress }
    }

    private fun parseDhcpLeaseLine(line: String): TetheringMonitor.DiscoveredClient? {
        // "timestamp mac ip hostname client-id"
        val parts = line.trim().split(Regex("\\s+"))
        if (parts.size < 3) return null
        val mac = parts[1].uppercase()
        val ip = parts[2]
        if (!mac.contains(':') || !isPrivateIp(ip)) return null
        val hostname = parts.getOrNull(3)?.takeIf { it.isNotBlank() } ?: "Connected Device ($ip)"
        return TetheringMonitor.DiscoveredClient(
            macAddress = mac,
            ipAddress = ip,
            deviceName = hostname
        )
    }

    private fun execShell(command: String): String {
        return try {
            val proc = Runtime.getRuntime().exec(arrayOf("sh", "-c", command))
            var output = ""
            val reader = Thread {
                try { output = proc.inputStream.bufferedReader().readText() } catch (_: Exception) {}
            }
            reader.isDaemon = true
            reader.start()
            val finished = proc.waitFor(SHELL_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
            if (!finished) {
                proc.destroyForcibly()
                Log.d(TAG, "Shell command timed out: $command")
            }
            reader.join(200)
            output
        } catch (e: Exception) {
            Log.d(TAG, "Shell command failed: $command", e)
            ""
        }
    }

    private fun parseIpNeighLine(line: String): TetheringMonitor.DiscoveredClient? {
        val parts = line.trim().split(Regex("\\s+"))
        if (parts.size < 4) return null
        val ip = parts[0]
        if (!isPrivateIp(ip)) return null

        val macIndex = parts.indexOf("lladdr").takeIf { it >= 0 }?.plus(1) ?: return null
        if (macIndex >= parts.size) return null
        val mac = parts[macIndex].uppercase()
        if (!mac.contains(':') || mac == "00:00:00:00:00:00") return null

        val state = parts.lastOrNull()?.uppercase() ?: ""
        if (state in setOf("FAILED", "INCOMPLETE")) return null

        return TetheringMonitor.DiscoveredClient(
            macAddress = mac,
            ipAddress = ip,
            deviceName = "Connected Device ($ip)"
        )
    }

    private fun parseArpLine(line: String, iface: String): TetheringMonitor.DiscoveredClient? {
        val parts = line.trim().split(Regex("\\s+"))
        if (parts.size < 6) return null
        val ip = parts[0]
        val flags = parts[2].toIntOrNull() ?: return null
        val mac = parts[3].uppercase()
        val device = parts[5]
        if (device != iface) return null
        if (flags and 0x2 == 0) return null
        if (mac == "00:00:00:00:00:00") return null
        if (!isPrivateIp(ip)) return null
        return TetheringMonitor.DiscoveredClient(
            macAddress = mac,
            ipAddress = ip,
            deviceName = "Connected Device ($ip)"
        )
    }

    private fun scanSubnet(localInet: Inet4Address, prefix: Int, iface: String) {
        val now = System.currentTimeMillis()
        if (now - lastSubnetScanMs < SUBNET_SCAN_MIN_INTERVAL_MS) return
        if (!scanning.compareAndSet(false, true)) return
        lastSubnetScanMs = now
        scope.launch {
            try {
                val effectivePrefix = if (prefix in 1..32) prefix else 24
                val hostAddress = localInet.hostAddress ?: return@launch
                val parts = hostAddress.split('.')
                if (parts.size != 4) return@launch

                val subnetBase = "${parts[0]}.${parts[1]}.${parts[2]}"
                val myHost = parts[3].toIntOrNull() ?: return@launch
                val discovered = mutableListOf<TetheringMonitor.DiscoveredClient>()

                val jobs = (1..254).filter { it != myHost }.map { host ->
                    async(Dispatchers.IO) {
                        val ip = "$subnetBase.$host"
                        if (isHostReachableOnInterface(iface, localInet, ip)) {
                            TetheringMonitor.DiscoveredClient(
                                macAddress = "IP-${ip.replace('.', '-')}",
                                ipAddress = ip,
                                deviceName = "Connected Device ($ip)"
                            )
                        } else {
                            null
                        }
                    }
                }
                discovered.addAll(jobs.awaitAll().filterNotNull())

                if (discovered.isNotEmpty()) {
                    _clients.value = discovered
                    Log.i(TAG, "Subnet scan on $iface found ${discovered.size} device(s) on $subnetBase.0/$effectivePrefix")
                } else {
                    Log.d(TAG, "Subnet scan on $iface found no devices on $subnetBase.0/$effectivePrefix")
                }
            } finally {
                scanning.set(false)
            }
        }
    }

    private fun isHostReachableOnInterface(
        iface: String,
        localInet: Inet4Address,
        ip: String
    ): Boolean {
        if (pingHost(ip)) return true
        if (pingViaInterface(iface, ip)) return true
        return tcpProbeViaInterface(localInet, ip)
    }

    private fun pingHost(ip: String): Boolean {
        return try {
            val proc = ProcessBuilder("ping", "-c", "1", "-W", "1", ip)
                .redirectErrorStream(true)
                .start()
            proc.waitFor() == 0
        } catch (e: Exception) {
            false
        }
    }

    private fun pingViaInterface(iface: String, ip: String): Boolean {
        return try {
            val proc = ProcessBuilder("ping", "-c", "1", "-W", "1", "-I", iface, ip)
                .redirectErrorStream(true)
                .start()
            proc.waitFor() == 0
        } catch (e: Exception) {
            false
        }
    }

    private fun tcpProbeViaInterface(localInet: Inet4Address, ip: String): Boolean {
        val ports = intArrayOf(53, 80, 443, 8080, 6200)
        for (port in ports) {
            try {
                Socket().use { socket ->
                    socket.bind(InetSocketAddress(localInet, 0))
                    socket.soTimeout = 250
                    socket.connect(InetSocketAddress(ip, port), 250)
                    return true
                }
            } catch (_: Exception) {
                // try next port
            }
        }
        return false
    }

    private fun isPrivateIp(ip: String?): Boolean {
        if (ip == null) return false
        if (ip.startsWith("10.")) return true
        if (ip.startsWith("192.168.")) return true
        if (ip.startsWith("172.")) {
            val second = ip.substringAfter("172.").substringBefore('.').toIntOrNull() ?: return false
            return second in 16..31
        }
        return false
    }

    companion object {
        private const val TAG = "LocalNetworkScanner"
        private const val SHELL_TIMEOUT_MS = 1_000L
        private const val PING_SWEEP_TIMEOUT_MS = 1_500L
        private const val PING_SWEEP_MIN_INTERVAL_MS = 500L
        private const val SUBNET_SCAN_MIN_INTERVAL_MS = 500L
        /** Poll interval inside the fast-exit ping sweep loop. */
        private const val SWEEP_POLL_MS = 50L
        /** How long to wait for a fast (responding) device before falling back to full timeout. */
        private const val FAST_EXIT_SWEEP_MS = 400L

        internal fun shouldClearSnapshot(
            wasHotspotActive: Boolean,
            hasActiveTetherNetwork: Boolean
        ): Boolean = wasHotspotActive && !hasActiveTetherNetwork
    }
}
