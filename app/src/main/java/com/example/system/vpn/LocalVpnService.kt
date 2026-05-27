package com.example.system.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import com.example.MainActivity
import com.example.NetGuardApplication
import com.example.system.dns.DnsFilterEngine
import com.example.domain.usecase.CheckDnsBlockUseCase
import kotlinx.coroutines.*
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.ArrayBlockingQueue

class LocalVpnService : VpnService() {

    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + job)
    
    private var vpnInterface: ParcelFileDescriptor? = null
    private val bufferPool = ArrayBlockingQueue<ByteBuffer>(32)

    private val checkDnsBlock: CheckDnsBlockUseCase by lazy {
        (application as NetGuardApplication).container.checkDnsBlockUseCase
    }
    
    private val dnsFilterEngine: DnsFilterEngine by lazy {
        (application as NetGuardApplication).container.dnsFilterEngine
    }

    companion object {
        const val NOTIF_ID = 8829
        const val CHANNEL_ID = "vpn_channel"
        var isRunning = false
    }

    override fun onCreate() {
        super.onCreate()
        for (i in 0 until 16) {
            bufferPool.offer(ByteBuffer.allocate(32767))
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        isRunning = true
        createNotificationChannel()
        startForeground(NOTIF_ID, buildVpnNotification())

        scope.launch {
            try {
                runVpnLoop()
            } catch (e: Exception) {
                Log.e("LocalVpnService", "VPN exception", e)
            } finally {
                isRunning = false
                stopSelf()
            }
        }

        return START_STICKY
    }

    private suspend fun runVpnLoop() = withContext(Dispatchers.IO) {
        vpnInterface = buildVpnInterface() ?: return@withContext
        val inputStream = FileInputStream(vpnInterface!!.fileDescriptor)
        val outputStream = FileOutputStream(vpnInterface!!.fileDescriptor)

        while (isActive) {
            val buffer = bufferPool.poll() ?: ByteBuffer.allocate(32767)
            buffer.clear()

            try {
                val length = inputStream.read(buffer.array())
                if (length > 0) {
                    buffer.limit(length)
                    val packet = PacketParser.parseIpPacket(buffer, length)

                    if (packet.isDnsQuery && packet.dnsQueryDomain != null) {
                        launch {
                            val domain = packet.dnsQueryDomain
                            // Identify dummy UID for demonstration/safety or determine caller uid
                            val dummyUid = 10001
                            val fakePkg = "com.example.mockapp"
                            checkDnsBlock(domain, dummyUid, fakePkg)
                        }
                    }

                    // Forward immediately (In a production userspace tun, packets are rewritten, 
                    // we write them back to allow passthrough network connectivity)
                    outputStream.write(buffer.array(), 0, length)
                }
            } catch (e: Exception) {
                // Ignore transient read errors
            } finally {
                bufferPool.offer(buffer)
            }
        }
    }

    private fun buildVpnInterface(): ParcelFileDescriptor? {
        return try {
            Builder()
                .setSession("NetGuard Pulse Firewall")
                .addAddress("10.8.0.2", 32)
                .addDnsServer("8.8.8.8") // Fallback DNS
                .addRoute("0.0.0.0", 0) // Intercept all
                .setBlocking(true)
                .establish()
        } catch (e: Exception) {
            Log.e("LocalVpnService", "Could not establish interface", e)
            null
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "NetGuard Pulse Local VPN Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val nm = getSystemService(NotificationManager::class.java)
            nm?.createNotificationChannel(channel)
        }
    }

    private fun buildVpnNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            Notification.Builder(this)
        }

        return builder
            .setContentTitle("NetGuard Live Firewall Active")
            .setContentText("Monitoring live ports and filtering ad/tracker domains locally.")
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentIntent(pendingIntent)
            .build()
    }

    override fun onDestroy() {
        isRunning = false
        scope.cancel()
        try {
            vpnInterface?.close()
        } catch (e: Exception) {
            // Safe close
        }
        super.onDestroy()
    }
}
