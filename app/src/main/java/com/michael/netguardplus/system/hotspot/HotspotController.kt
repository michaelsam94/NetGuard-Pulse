package com.michael.netguardplus.system.hotspot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.net.ConnectivityManager
import android.net.TetheringManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Base64
import android.util.Log
import com.michael.netguardplus.system.stats.TetheringMonitor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.lang.reflect.Proxy
import java.util.Locale
import java.util.concurrent.Executor

class HotspotController(
    private val context: Context,
    private val executor: Executor
) {
    private val appContext = context.applicationContext
    private val wifiManager = appContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val connectivityManager = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val tetheringManager: TetheringManager? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            appContext.getSystemService(TetheringManager::class.java)
        } else {
            null
        }

    private val _isHotspotEnabled = MutableStateFlow(false)
    val isHotspotEnabled: StateFlow<Boolean> = _isHotspotEnabled.asStateFlow()

    private val _connectedClients = MutableStateFlow<List<TetheringMonitor.DiscoveredClient>>(emptyList())
    val connectedClients: StateFlow<List<TetheringMonitor.DiscoveredClient>> = _connectedClients.asStateFlow()

    private var softApCallbackRegistered = false
    private var tetheringEventCallbackRegistered = false
    private var receiversRegistered = false
    private var lastConnectedClientSignalMs = 0L

    private val tetherReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_WIFI_AP_STATE_CHANGED,
                ACTION_TETHER_STATE_CHANGED,
                ACTION_EASY_TETHER_CONNECT,
                ACTION_EASY_TETHER_DISCONNECT -> {
                    parseEasyTetherIntent(intent)?.let { client ->
                        setConnectedClients(listOf(client))
                        _isHotspotEnabled.value = true
                    }
                    refreshHotspotState()
                    refreshConnectedClients()
                }
            }
        }
    }

    private val miuiSettingsObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            refreshConnectedClients()
        }
    }

    fun startObserving() {
        registerReceivers()
        registerMiuiSettingsObserver()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            registerSoftApCallback()
            registerTetheringEventCallback()
        }
        refreshHotspotState()
        refreshConnectedClients()
    }

    fun refreshHotspotState() {
        _isHotspotEnabled.value = readHotspotEnabled()
    }

    fun refreshConnectedClients() {
        val clients = readConnectedClients()
        setConnectedClients(clients)
    }

    private fun setConnectedClients(clients: List<TetheringMonitor.DiscoveredClient>) {
        _connectedClients.value = clients
        if (clients.isNotEmpty()) {
            lastConnectedClientSignalMs = System.currentTimeMillis()
        } else {
            lastConnectedClientSignalMs = 0L
        }
    }

    private fun hasFreshConnectedClientSignal(nowMs: Long = System.currentTimeMillis()): Boolean {
        return _connectedClients.value.isNotEmpty() &&
            lastConnectedClientSignalMs > 0L &&
            nowMs - lastConnectedClientSignalMs <= FRESH_CLIENT_SIGNAL_MS
    }

    fun setHotspotEnabled(
        enabled: Boolean,
        onStarted: () -> Unit = {},
        onFailed: (String) -> Unit = {}
    ) {
        if (enabled) {
            startHotspot(onStarted, onFailed)
        } else {
            stopHotspot(onStarted, onFailed)
        }
    }

    fun disconnectBlockedClient(mac: String, ip: String? = null): Boolean {
        val macArg = mac.takeIf { HotspotClientMerger.isRealMac(it) }
        return HotspotClientEnforcer.tryDisconnectClient(appContext, wifiManager, macArg, ip)
    }

    fun restoreClientNetworkAccess(mac: String, ip: String? = null): Boolean {
        val macArg = mac.takeIf { HotspotClientMerger.isRealMac(it) }
        val restored = HotspotClientEnforcer.tryRestoreClientAccess(wifiManager, macArg)
        if (restored) {
            Log.i(TAG, "Restored hotspot access for mac=$macArg ip=$ip")
        }
        return restored
    }

    fun restartHotspotForEnforcement(onComplete: () -> Unit = {}) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            Log.w(TAG, "Hotspot restart for enforcement requires Android 11+")
            onComplete()
            return
        }
        val tm = tetheringManager
        if (tm == null) {
            Log.w(TAG, "TetheringManager unavailable for enforcement")
            onComplete()
            return
        }
        stopHotspotAsync(tm) { stopped ->
            if (!stopped) {
                Log.w(TAG, "Could not stop hotspot for in-app enforcement")
                onComplete()
                return@stopHotspotAsync
            }
            _isHotspotEnabled.value = false
            setConnectedClients(emptyList())
            Handler(Looper.getMainLooper()).postDelayed({
                startHotspotAsync(tm, onComplete)
            }, 2_500L)
        }
    }

    private fun stopHotspotAsync(tm: TetheringManager, onResult: (Boolean) -> Unit) {
        val request = buildPublicTetheringRequest()
        try {
            tm.stopTethering(
                request,
                executor,
                object : TetheringManager.StopTetheringCallback {
                    override fun onStopTetheringSucceeded() {
                        Log.i(TAG, "stopTethering(TetheringRequest) succeeded")
                        onResult(true)
                    }

                    override fun onStopTetheringFailed(error: Int) {
                        Log.w(
                            TAG,
                            "stopTethering failed (error=$error) — third-party apps cannot reset hotspot on this device without system permission"
                        )
                        onResult(false)
                    }
                }
            )
        } catch (e: Exception) {
            Log.w(TAG, "stopTethering call failed: ${e.message}")
            onResult(false)
        }
    }

    private fun startHotspotAsync(
        tm: TetheringManager,
        onComplete: () -> Unit,
        onFailed: ((String) -> Unit)? = null
    ) {
        val request = buildPublicTetheringRequest()
        try {
            tm.startTethering(
                request,
                executor,
                object : TetheringManager.StartTetheringCallback {
                    override fun onTetheringStarted() {
                        _isHotspotEnabled.value = true
                        Log.i(TAG, "startTethering(TetheringRequest) succeeded")
                        onComplete()
                    }

                    override fun onTetheringFailed(error: Int) {
                        Log.w(TAG, "startTethering failed (error=$error), trying legacy")
                        if (!invokeStartHotspotLegacy(onComplete)) {
                            onFailed?.invoke("Could not start hotspot automatically (error $error)")
                                ?: onComplete()
                        }
                    }
                }
            )
        } catch (e: Exception) {
            Log.w(TAG, "startTethering call failed", e)
            if (!invokeStartHotspotLegacy(onComplete)) {
                onFailed?.invoke("Could not start hotspot automatically")
                    ?: onComplete()
            }
        }
    }

    private fun buildPublicTetheringRequest(): TetheringManager.TetheringRequest {
        return TetheringManager.TetheringRequest.Builder(TetheringManager.TETHERING_WIFI).build()
    }

    private fun tryStopSoftAp(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
        return try {
            val method = wifiManager.javaClass.getMethod("stopSoftAp")
            val result = method.invoke(wifiManager) as? Boolean ?: false
            if (result) Log.i(TAG, "stopSoftAp() succeeded")
            result
        } catch (e: Exception) {
            Log.d(TAG, "stopSoftAp unavailable", e)
            false
        }
    }

    fun openConnectedClientsSettings(): Boolean {
        val intents = listOf(
            Intent().setClassName(
                "com.oplus.wirelesssettings",
                "com.oplus.wirelesssettings.wifi.tether.WifiTetherConnectManagerActivity"
            ),
            Intent().setClassName(
                "com.android.settings",
                "com.android.settings.wifi.tether.WifiTetherConnectManagerActivity"
            ),
            Intent().setClassName(
                "com.android.settings",
                "com.android.settings.wifi.tether.WifiDeviceManagementActivity"
            ),
            Intent("android.settings.TETHER_SETTINGS"),
            Intent().setClassName(
                "com.android.settings",
                "com.android.settings.wifi.tether.WifiTetherSettings"
            )
        )
        for (intent in intents) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                if (intent.resolveActivity(appContext.packageManager) != null) {
                    appContext.startActivity(intent)
                    return true
                }
            } catch (e: Exception) {
                Log.w(TAG, "Could not open hotspot client settings via ${intent.component ?: intent.action}", e)
            }
        }
        return openHotspotSettings()
    }

    fun openHotspotSettings(): Boolean {
        val intents = listOf(
            Intent("android.settings.TETHER_SETTINGS"),
            Intent(Settings.ACTION_WIRELESS_SETTINGS),
            Intent(Settings.ACTION_WIFI_SETTINGS),
            Intent().setClassName("com.android.settings", "com.android.settings.wifi.tether.WifiTetherSettings")
        )
        for (intent in intents) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                if (intent.resolveActivity(appContext.packageManager) != null) {
                    appContext.startActivity(intent)
                    return true
                }
            } catch (e: Exception) {
                Log.w(TAG, "Could not open hotspot settings via ${intent.action ?: intent.component}", e)
            }
        }
        return false
    }

    private fun registerReceivers() {
        if (receiversRegistered) return
        val filter = IntentFilter().apply {
            addAction(ACTION_WIFI_AP_STATE_CHANGED)
            addAction(ACTION_TETHER_STATE_CHANGED)
            addAction(ACTION_EASY_TETHER_CONNECT)
            addAction(ACTION_EASY_TETHER_DISCONNECT)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            appContext.registerReceiver(tetherReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            appContext.registerReceiver(tetherReceiver, filter)
        }
        receiversRegistered = true
    }

    private fun registerMiuiSettingsObserver() {
        try {
            appContext.contentResolver.registerContentObserver(
                Settings.Global.CONTENT_URI,
                true,
                miuiSettingsObserver
            )
        } catch (e: Exception) {
            Log.d(TAG, "Could not observe MIUI settings", e)
        }
    }

    private fun registerSoftApCallback() {
        if (softApCallbackRegistered || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        try {
            val callbackClass = Class.forName("android.net.wifi.WifiManager\$SoftApCallback")
            val callback = Proxy.newProxyInstance(
                callbackClass.classLoader,
                arrayOf(callbackClass)
            ) { _, method, args ->
                when (method.name) {
                    "onConnectedClientsChanged" -> {
                        val clientsArg = when (args?.size ?: 0) {
                            1 -> args?.get(0)
                            2 -> args?.get(1)
                            else -> null
                        }
                        val parsed = parseSoftApClients(clientsArg)
                        setConnectedClients(parsed)
                        if (parsed.isNotEmpty()) {
                            _isHotspotEnabled.value = true
                        }
                        Log.i(TAG, "SoftApCallback reported ${parsed.size} client(s)")
                    }
                    "onStateChanged" -> {
                        val newState = args?.getOrNull(1) as? Int ?: return@newProxyInstance null
                        _isHotspotEnabled.value = newState == WIFI_AP_STATE_ENABLED
                        if (newState != WIFI_AP_STATE_ENABLED) {
                            setConnectedClients(emptyList())
                        }
                    }
                }
                null
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                wifiManager.javaClass.getMethod(
                    "registerSoftApCallback",
                    Executor::class.java,
                    callbackClass
                ).invoke(wifiManager, executor, callback)
            } else {
                wifiManager.javaClass.getMethod(
                    "registerSoftApCallback",
                    callbackClass,
                    Handler::class.java
                ).invoke(wifiManager, callback, Handler(Looper.getMainLooper()))
            }
            softApCallbackRegistered = true
            Log.i(TAG, "SoftApCallback registered")
        } catch (e: Exception) {
            Log.d(TAG, "SoftApCallback registration failed", e)
        }
    }

    /** API 30+ hidden callback — supplies tether client MAC + IP when SoftAp APIs are denied. */
    private fun registerTetheringEventCallback() {
        if (tetheringEventCallbackRegistered || Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val tm = tetheringManager ?: return
        try {
            val callbackClass = Class.forName("android.net.TetheringManager\$TetheringEventCallback")
            val callback = Proxy.newProxyInstance(
                callbackClass.classLoader,
                arrayOf(callbackClass)
            ) { proxy, method, args ->
                when (method.name) {
                    "hashCode" -> return@newProxyInstance System.identityHashCode(proxy)
                    "equals" -> return@newProxyInstance (proxy === args?.getOrNull(0))
                    "onClientsChanged" -> {
                        @Suppress("UNCHECKED_CAST")
                        val raw = args?.getOrNull(0) as? Collection<Any> ?: emptyList()
                        val parsed = raw.mapNotNull { parseTetheredClient(it) }
                        setConnectedClients(parsed)
                        if (parsed.isNotEmpty()) {
                            _isHotspotEnabled.value = true
                        }
                        Log.i(TAG, "TetheringEventCallback reported ${parsed.size} client(s)")
                    }
                }
                null
            }
            tm.javaClass.getMethod(
                "registerTetheringEventCallback",
                Executor::class.java,
                callbackClass
            ).invoke(tm, executor, callback)
            tetheringEventCallbackRegistered = true
            Log.i(TAG, "TetheringEventCallback registered")
        } catch (e: Exception) {
            Log.w(TAG, "TetheringEventCallback registration failed", e)
        }
    }

    private fun parseSoftApClients(clientsArg: Any?): List<TetheringMonitor.DiscoveredClient> {
        if (clientsArg !is List<*>) return emptyList()
        return clientsArg.mapNotNull { item ->
            when (item) {
                null -> null
                is String -> parseMacStringClient(item)
                else -> parseWifiClient(item) ?: parseTetheredClient(item) ?: parseMacAddressObject(item)
            }
        }
    }

    private fun parseMacAddressObject(value: Any): TetheringMonitor.DiscoveredClient? {
        return try {
            val mac = value.javaClass.getMethod("toString").invoke(value)?.toString()
                ?.uppercase(Locale.US)
                ?.takeIf { it.contains(':') }
                ?: return null
            TetheringMonitor.DiscoveredClient(
                macAddress = mac,
                ipAddress = "0.0.0.0",
                deviceName = "Device ${mac.takeLast(8).replace(":", "")}"
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun readConnectedClients(): List<TetheringMonitor.DiscoveredClient> {
        readTetheringManagerTetheredClients().takeIf { it.isNotEmpty() }?.let { return it }
        readViaWifiManagerReflection().takeIf { it.isNotEmpty() }?.let { return it }
        readViaTetheringManagerReflection().takeIf { it.isNotEmpty() }?.let { return it }
        readViaTetheringBinder().takeIf { it.isNotEmpty() }?.let { return it }
        readMiuiEasyTetherSettings().takeIf { it.isNotEmpty() }?.let { return it }
        // Do NOT fall back to cached _connectedClients — that would preserve stale clients after disconnect.
        // Client discovery is handled by SoftApCallback + LocalNetworkClientScanner in the repository.
        return emptyList()
    }

    private fun readViaWifiManagerReflection(): List<TetheringMonitor.DiscoveredClient> {
        for (method in wifiManager.javaClass.methods) {
            val name = method.name.lowercase(Locale.US)
            if (method.parameterTypes.isNotEmpty()) continue
            if (!name.contains("client") && !name.contains("station") && !name.contains("connectedsta")) {
                continue
            }
            try {
                val result = method.invoke(wifiManager) ?: continue
                when (result) {
                    is List<*> -> {
                        val parsed = result.mapNotNull { item ->
                            when (item) {
                                is String -> parseMacStringClient(item)
                                else -> item?.let { parseWifiClient(it) ?: parseTetheredClient(it) }
                            }
                        }
                        if (parsed.isNotEmpty()) {
                            Log.i(TAG, "Found ${parsed.size} client(s) via WifiManager.${method.name}")
                            return parsed
                        }
                    }
                }
            } catch (e: Exception) {
                Log.d(TAG, "WifiManager.${method.name} failed", e)
            }
        }
        return emptyList()
    }

    private fun parseMacStringClient(value: String): TetheringMonitor.DiscoveredClient? {
        if (!value.contains(':')) return null
        val mac = value.uppercase(Locale.US)
        return TetheringMonitor.DiscoveredClient(
            macAddress = mac,
            ipAddress = "0.0.0.0",
            deviceName = "Device ${mac.takeLast(8).replace(":", "")}"
        )
    }

    private fun readViaTetheringManagerReflection(): List<TetheringMonitor.DiscoveredClient> {
        val tm = tetheringManager ?: return emptyList()
        for (method in tm.javaClass.methods) {
            if (!method.name.contains("client", ignoreCase = true)) continue
            if (method.parameterTypes.isNotEmpty()) continue
            try {
                @Suppress("UNCHECKED_CAST")
                val result = method.invoke(tm) as? List<*> ?: continue
                val parsed = result.mapNotNull { item -> parseTetheredClient(item as Any) }
                if (parsed.isNotEmpty()) {
                    Log.i(TAG, "Found ${parsed.size} client(s) via TetheringManager.${method.name}")
                    return parsed
                }
            } catch (e: Exception) {
                Log.d(TAG, "TetheringManager.${method.name} failed", e)
            }
        }
        return emptyList()
    }

    private fun readViaTetheringBinder(): List<TetheringMonitor.DiscoveredClient> {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Hidden ITetheringConnector is denied for targetSdk 36+ apps.
            return emptyList()
        }
        return try {
            val serviceManager = Class.forName("android.os.ServiceManager")
            val binder = serviceManager.getMethod("getService", String::class.java)
                .invoke(null, "tethering") as android.os.IBinder
            val stubClass = Class.forName("android.net.ITetheringConnector\$Stub")
            val connector = stubClass.getMethod("asInterface", android.os.IBinder::class.java)
                .invoke(null, binder) ?: return emptyList()

            val methods = connector.javaClass.methods.filter { it.name.contains("TetheredClient", ignoreCase = true) }
            for (method in methods) {
                try {
                    val result = when (method.parameterTypes.size) {
                        0 -> method.invoke(connector)
                        2 -> method.invoke(connector, appContext.packageName, null)
                        else -> continue
                    } as? List<*> ?: continue
                    @Suppress("UNCHECKED_CAST")
                    val parsed = result.mapNotNull { item -> parseTetheredClient(item as Any) }
                    if (parsed.isNotEmpty()) {
                        Log.i(TAG, "Found ${parsed.size} client(s) via ITetheringConnector.${method.name}")
                        return parsed
                    }
                } catch (e: Exception) {
                    Log.d(TAG, "Binder method ${method.name} failed", e)
                }
            }
            emptyList()
        } catch (e: Exception) {
            Log.d(TAG, "ITetheringConnector unavailable", e)
            emptyList()
        }
    }

    private fun parseEasyTetherIntent(intent: Intent?): TetheringMonitor.DiscoveredClient? {
        if (intent == null) return null
        val mac = intent.getStringExtra("bssid")
            ?: intent.getStringExtra("mac")
            ?: intent.getStringExtra("macAddress")
        val name = intent.getStringExtra("deviceName")
            ?: intent.getStringExtra("name")
            ?: intent.getStringExtra("hostname")
        val ip = intent.getStringExtra("ip")
            ?: intent.getStringExtra("ipAddress")
            ?: "0.0.0.0"
        if (mac.isNullOrBlank()) return null
        return TetheringMonitor.DiscoveredClient(
            macAddress = mac.uppercase(Locale.US),
            ipAddress = ip,
            deviceName = name?.takeIf { it.isNotBlank() } ?: "Connected Device"
        )
    }

    private fun readMiuiEasyTetherSettings(): List<TetheringMonitor.DiscoveredClient> {
        return try {
            val resolver = appContext.contentResolver
            val macRaw = Settings.Global.getString(resolver, "easy_tether_bssid_record_connected")
            val name = Settings.Global.getString(resolver, "easy_tether_device_name_record_connected")
            if (macRaw.isNullOrBlank()) return emptyList()

            val mac = decodeMiuiMac(macRaw) ?: macRaw.uppercase(Locale.US)
            TetheringMonitor.DiscoveredClient(
                macAddress = mac,
                ipAddress = "0.0.0.0",
                deviceName = name?.takeIf { it.isNotBlank() } ?: "Connected Device"
            ).let { listOf(it) }
        } catch (e: Exception) {
            Log.d(TAG, "MIUI easy_tether settings unavailable", e)
            emptyList()
        }
    }

    private fun decodeMiuiMac(raw: String): String? {
        return try {
            if (raw.contains(':')) return raw.uppercase(Locale.US)
            val decoded = String(Base64.decode(raw, Base64.DEFAULT)).trim()
            if (decoded.contains(':')) return decoded.uppercase(Locale.US)
            null
        } catch (_: Exception) {
            null
        }
    }

    private fun parseTetheredClient(client: Any): TetheringMonitor.DiscoveredClient? {
        return try {
            val macObj = client.javaClass.methods.firstOrNull { it.name == "getMacAddress" }?.invoke(client)
                ?: return null
            val mac = when (macObj) {
                is String -> macObj.uppercase(Locale.US)
                else -> macObj.javaClass.getMethod("toString").invoke(macObj)?.toString()?.uppercase(Locale.US)
            }?.takeIf { HotspotClientMerger.isRealMac(it) } ?: return null

            val ip = extractTetheredClientIp(client) ?: "0.0.0.0"
            val hostname = try {
                client.javaClass.getMethod("getHostname").invoke(client) as? String
            } catch (_: Exception) {
                null
            }
            TetheringMonitor.DiscoveredClient(
                macAddress = mac,
                ipAddress = ip,
                deviceName = hostname?.takeIf { it.isNotBlank() }
                    ?: "Device ${mac.takeLast(8).replace(":", "")}"
            )
        } catch (e: Exception) {
            Log.d(TAG, "parseTetheredClient failed: ${e.message}")
            null
        }
    }

    private fun extractTetheredClientIp(client: Any): String? {
        val addrs = client.javaClass.methods.firstOrNull { it.name == "getAddresses" }
            ?.invoke(client) as? List<*> ?: return null
        for (addr in addrs) {
            if (addr == null) continue
            extractHostAddress(addr)?.let { ip ->
                if (HotspotClientMerger.hasValidIp(ip)) return ip
            }
        }
        return null
    }

    private fun extractHostAddress(addrInfo: Any): String? {
        val linkOrInet = addrInfo.javaClass.methods.firstOrNull { it.name == "getAddress" }
            ?.invoke(addrInfo) ?: return null
        val inet = if (linkOrInet.javaClass.name.contains("LinkAddress")) {
            linkOrInet.javaClass.getMethod("getAddress").invoke(linkOrInet)
        } else {
            linkOrInet
        } ?: return null
        return inet.javaClass.getMethod("getHostAddress").invoke(inet) as? String
    }

    private fun readTetheringManagerTetheredClients(): List<TetheringMonitor.DiscoveredClient> {
        val tm = tetheringManager ?: return emptyList()
        for (name in listOf("getTetheredClients", "getConnectedClients", "getTetheredClientList")) {
            try {
                val method = tm.javaClass.methods.firstOrNull { m ->
                    m.name == name && m.parameterTypes.isEmpty()
                } ?: continue
                @Suppress("UNCHECKED_CAST")
                val result = method.invoke(tm) as? List<Any> ?: continue
                val parsed = result.mapNotNull { parseTetheredClient(it) }
                if (parsed.isNotEmpty()) {
                    Log.i(TAG, "Found ${parsed.size} client(s) via TetheringManager.$name")
                    return parsed
                }
            } catch (e: Exception) {
                Log.d(TAG, "TetheringManager.$name failed: ${e.message}")
            }
        }
        return emptyList()
    }

    private fun parseWifiClient(client: Any): TetheringMonitor.DiscoveredClient? {
        return try {
            val macObj = client.javaClass.methods.firstOrNull { it.name == "getMacAddress" }?.invoke(client)
            val mac = macObj?.toString()?.uppercase(Locale.US) ?: return null
            val ip = client.javaClass.methods.firstOrNull { it.name == "getInetAddress" }?.invoke(client)
                ?.let { inet -> inet.javaClass.getMethod("getHostAddress").invoke(inet) as? String }
                ?: "0.0.0.0"
            TetheringMonitor.DiscoveredClient(
                macAddress = mac,
                ipAddress = ip,
                deviceName = "Device ${mac.takeLast(8).replace(":", "")}"
            )
        } catch (e: Exception) {
            null
        }
    }

    private fun startHotspot(onStarted: () -> Unit, onFailed: (String) -> Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val tm = tetheringManager
            if (tm != null) {
                startHotspotAsync(tm, onStarted, onFailed)
                return
            }
        }
        if (openHotspotSettings()) {
            onFailed("Opened hotspot settings — turn it on there")
        } else {
            onFailed("Could not open hotspot settings automatically")
        }
    }

    private fun stopHotspot(onStarted: () -> Unit, onFailed: (String) -> Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val tm = tetheringManager
            if (tm != null) {
                stopHotspotAsync(tm) { stopped ->
                    _isHotspotEnabled.value = false
                    setConnectedClients(emptyList())
                    if (stopped) {
                        onStarted()
                    } else {
                        openHotspotSettings()
                        onStarted()
                    }
                }
                return
            }
        }
        _isHotspotEnabled.value = false
        setConnectedClients(emptyList())
        openHotspotSettings()
        onStarted()
    }

    private fun invokeStopHotspotLegacy(): Boolean {
        return try {
            @Suppress("DEPRECATION")
            val method = wifiManager.javaClass.getMethod(
                "setWifiApEnabled",
                android.net.wifi.WifiConfiguration::class.java,
                Boolean::class.javaPrimitiveType
            )
            val result = method.invoke(wifiManager, null, false) as? Boolean ?: false
            if (result) Log.i(TAG, "setWifiApEnabled(false) succeeded")
            result
        } catch (e: Exception) {
            Log.d(TAG, "Legacy hotspot stop unavailable", e)
            false
        }
    }

    private fun invokeStartHotspotLegacy(onStarted: () -> Unit): Boolean {
        return try {
            @Suppress("DEPRECATION")
            val method = wifiManager.javaClass.getMethod(
                "setWifiApEnabled",
                android.net.wifi.WifiConfiguration::class.java,
                Boolean::class.javaPrimitiveType
            )
            val result = method.invoke(wifiManager, null, true) as? Boolean ?: false
            if (result) {
                _isHotspotEnabled.value = true
                Log.i(TAG, "setWifiApEnabled(true) succeeded")
                onStarted()
            }
            result
        } catch (e: Exception) {
            Log.d(TAG, "Legacy hotspot start unavailable", e)
            false
        }
    }

    private fun readHotspotEnabled(): Boolean {
        try {
            @Suppress("DEPRECATION")
            val active = connectivityManager.activeNetworkInfo
            if (active != null && active.type == ConnectivityManager.TYPE_MOBILE) {
                // Hotspot can be active while mobile is upstream
            }
        } catch (_: Exception) {
        }

        try {
            val tethered = connectivityManager.javaClass.getMethod("getTetheredIfaces").invoke(connectivityManager)
            if (tethered is Array<*> && tethered.isNotEmpty()) {
                return shouldReportHotspotEnabled(
                    hasFreshCachedClients = hasFreshConnectedClientSignal(),
                    hasTetheredIfaces = true,
                    wifiApState = WIFI_AP_STATE_DISABLED
                )
            }
        } catch (_: Exception) {
        }

        return try {
            @Suppress("DEPRECATION")
            val state = wifiManager.javaClass.getMethod("getWifiApState").invoke(wifiManager) as? Int ?: return false
            shouldReportHotspotEnabled(
                hasFreshCachedClients = hasFreshConnectedClientSignal(),
                hasTetheredIfaces = false,
                wifiApState = state
            )
        } catch (_: Exception) {
            shouldReportHotspotEnabled(
                hasFreshCachedClients = hasFreshConnectedClientSignal(),
                hasTetheredIfaces = false,
                wifiApState = null
            )
        }
    }

    companion object {
        private const val TAG = "HotspotController"
        private const val WIFI_AP_STATE_DISABLED = 11
        private const val WIFI_AP_STATE_ENABLING = 12
        private const val WIFI_AP_STATE_ENABLED = 13
        private const val FRESH_CLIENT_SIGNAL_MS = 10_000L
        private const val ACTION_WIFI_AP_STATE_CHANGED = "android.net.wifi.WIFI_AP_STATE_CHANGED"
        private const val ACTION_TETHER_STATE_CHANGED = "android.net.conn.TETHER_STATE_CHANGED"
        private const val ACTION_EASY_TETHER_CONNECT = "easy_tether_land_connect"
        private const val ACTION_EASY_TETHER_DISCONNECT = "easy_tether_land_disconnect_celluar"

        internal fun shouldReportHotspotEnabled(
            hasFreshCachedClients: Boolean,
            hasTetheredIfaces: Boolean,
            wifiApState: Int?
        ): Boolean {
            if (hasTetheredIfaces) return true
            if (wifiApState == WIFI_AP_STATE_DISABLED) return false
            return wifiApState == WIFI_AP_STATE_ENABLED ||
                wifiApState == WIFI_AP_STATE_ENABLING ||
                (wifiApState == null && hasFreshCachedClients)
        }
    }
}
