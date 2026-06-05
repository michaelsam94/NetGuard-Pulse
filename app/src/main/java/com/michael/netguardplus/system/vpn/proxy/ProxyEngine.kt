package com.michael.netguardplus.system.vpn.proxy

import android.net.VpnService
import android.util.Log
import com.michael.netguardplus.system.hotspot.limit.SessionShapeGate
import com.michael.netguardplus.system.vpn.DnsPacketHandler
import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.DatagramChannel
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import java.nio.channels.SocketChannel
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Handles the User-Space TCP/UDP NAT Proxy and rate-limiting loop over the TUN interface.
 */
class ProxyEngine(
    private val vpnService: VpnService,
    tunFd: FileDescriptor,
    private val speedLimitKbps: Long,
    private val clientSubnet: String?,
    private val subnetPrefixLength: Int,
    private val gatewayIpv4: String?,
    private val knownClientIps: Set<String> = emptySet(),
    private val resolveDns: (DnsPacketHandler.ParsedDnsQuery) -> ByteArray
) {
    private val isRunning = AtomicBoolean(true)
    private val selector = Selector.open()
    private val tokenBucket = TokenBucket((speedLimitKbps * 1000L) / 8)

    private val tunInputStream = FileInputStream(tunFd)
    private val tunOutputStream = FileOutputStream(tunFd)
    private val tunWriteLock = Any()

    private val tcpSessions = ConcurrentHashMap<String, TcpSession>()
    private val udpSessions = ConcurrentHashMap<String, UdpSession>()

    private var selectorThread: Thread? = null
    private var readerThread: Thread? = null

    companion object {
        private const val TAG = "ProxyEngine"
        private const val BUFFER_SIZE = 32767
    }

    class TcpSession(
        val clientIp: String,
        val clientPort: Int,
        val destIp: String,
        val destPort: Int,
        val clientSeqStart: Long,
        val socketChannel: SocketChannel
    ) {
        var clientSeq = clientSeqStart + 1
        var hostSeq = 1000L
        var isConnected = false
        var isClosing = false
        val pendingData = java.util.concurrent.CopyOnWriteArrayList<ByteArray>()
    }

    private fun shouldThrottle(clientIp: String): Boolean {
        if (gatewayIpv4 != null && clientIp == gatewayIpv4) return false
        if (clientIp == "10.255.254.1") return false
        if (knownClientIps.isNotEmpty() && clientIp in knownClientIps) return true
        if (isLikelyHotspotClientIp(clientIp)) return true
        if (clientSubnet == null) return false
        return isClientIpInSubnet(clientIp)
    }

    private fun waitWhileSessionPaused() {
        while (SessionShapeGate.isPaused() && isRunning.get()) {
            try {
                Thread.sleep(5)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            }
        }
    }

    private fun throttleBytes(clientIp: String, bytes: Int) {
        if (!shouldThrottle(clientIp)) return
        waitWhileSessionPaused()
        tokenBucket.request(bytes)
    }

    private fun isLikelyHotspotClientIp(ip: String): Boolean {
        val parts = ip.split('.')
        if (parts.size != 4) return false
        val a = parts[0].toIntOrNull() ?: return false
        val b = parts[1].toIntOrNull() ?: return false
        return when (a) {
            10 -> true
            172 -> b in 16..31
            192 -> parts[2].toIntOrNull() == 168
            else -> false
        }
    }

    private fun isClientIpInSubnet(ip: String): Boolean {
        if (clientSubnet == null) return false
        val ipParts = ip.split('.')
        val subnetParts = clientSubnet.split('.')
        if (ipParts.size != 4 || subnetParts.size != 4) return false
        
        val ipInt = ipToLong(ip)
        val subnetInt = ipToLong(clientSubnet)
        val mask = (-1L shl (32 - subnetPrefixLength)) and 0xFFFFFFFFL
        return (ipInt and mask) == (subnetInt and mask)
    }

    private fun ipToLong(ip: String): Long {
        val parts = ip.split('.')
        var result = 0L
        for (part in parts) {
            result = (result shl 8) or (part.toLong() and 0xFF)
        }
        return result
    }

    class UdpSession(
        val clientIp: String,
        val clientPort: Int,
        val destIp: String,
        val destPort: Int,
        val datagramChannel: DatagramChannel
    )

    fun start() {
        selectorThread = Thread({ runSelector() }, "ProxyEngine-Selector").apply { start() }
        readerThread = Thread({ runReader() }, "ProxyEngine-Reader").apply { start() }
        Log.i(TAG, "ProxyEngine started with speed limit $speedLimitKbps Kbps, clients=${knownClientIps.size}, subnet=$clientSubnet")
    }

    fun stop() {
        if (!isRunning.compareAndSet(true, false)) return
        Log.i(TAG, "Stopping ProxyEngine")

        try {
            selector.wakeup()
        } catch (_: Exception) {}

        tcpSessions.values.forEach { session ->
            try {
                session.socketChannel.close()
            } catch (_: Exception) {}
        }
        tcpSessions.clear()

        udpSessions.values.forEach { session ->
            try {
                session.datagramChannel.close()
            } catch (_: Exception) {}
        }
        udpSessions.clear()

        try {
            selector.close()
        } catch (_: Exception) {}
    }

    private fun runReader() {
        val buffer = ByteArray(BUFFER_SIZE)
        val byteBuffer = ByteBuffer.wrap(buffer)

        while (isRunning.get()) {
            try {
                val length = tunInputStream.read(buffer)
                if (length <= 0) {
                    Thread.sleep(5)
                    continue
                }

                byteBuffer.clear()
                byteBuffer.limit(length)

                val packet = ProxyPacket(byteBuffer)
                if (packet.isTcp) {
                    handleTcpPacket(packet)
                } else if (packet.isUdp) {
                    handleUdpPacket(packet)
                }
            } catch (e: Exception) {
                if (isRunning.get()) {
                    Log.e(TAG, "Error reading from TUN interface", e)
                }
                break
            }
        }
    }

    private fun handleTcpPacket(packet: ProxyPacket) {
        val sessionKey = "${packet.sourceIp}:${packet.sourcePort}->${packet.destIp}:${packet.destPort}"

        if (packet.isTcpSyn) {
            try {
                val socketChannel = SocketChannel.open()
                socketChannel.configureBlocking(false)
                vpnService.protect(socketChannel.socket())

                val session = TcpSession(
                    clientIp = packet.sourceIp,
                    clientPort = packet.sourcePort,
                    destIp = packet.destIp,
                    destPort = packet.destPort,
                    clientSeqStart = packet.tcpSeq,
                    socketChannel = socketChannel
                )

                tcpSessions[sessionKey] = session
                socketChannel.connect(InetSocketAddress(packet.destIp, packet.destPort))

                selector.wakeup()
                socketChannel.register(selector, SelectionKey.OP_CONNECT, session)

                // Complete client-side SYN handshake immediately
                val synAckPacket = ProxyPacket.buildTcpPacket(
                    srcIp = packet.destIp,
                    destIp = packet.sourceIp,
                    srcPort = packet.destPort,
                    destPort = packet.sourcePort,
                    seq = session.hostSeq,
                    ack = session.clientSeq,
                    flags = 0x12 // SYN-ACK
                )

                writeToTun(synAckPacket)
                session.hostSeq++
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initiate TCP proxy connection to ${packet.destIp}:${packet.destPort}", e)
                sendTcpRst(packet)
            }
            return
        }

        val session = tcpSessions[sessionKey] ?: return

        if (packet.isTcpRst || packet.isTcpFin) {
            session.isClosing = true
            try {
                session.socketChannel.close()
            } catch (_: Exception) {}
            tcpSessions.remove(sessionKey)

            val finAck = ProxyPacket.buildTcpPacket(
                srcIp = packet.destIp,
                destIp = packet.sourceIp,
                srcPort = packet.destPort,
                destPort = packet.sourcePort,
                seq = session.hostSeq,
                ack = packet.tcpSeq + 1,
                flags = 0x11 // FIN-ACK
            )
            writeToTun(finAck)
            return
        }

        if (packet.isTcpAck && packet.tcpPayloadLength > 0) {
            val payload = ByteArray(packet.tcpPayloadLength)
            packet.buffer.position(packet.tcpPayloadOffset)
            packet.buffer.get(payload)

            if (!session.isConnected) {
                session.pendingData.add(payload)
            } else {
                try {
                    throttleBytes(session.clientIp, payload.size)
                    session.socketChannel.write(ByteBuffer.wrap(payload))
                    session.clientSeq += payload.size

                    val ackPacket = ProxyPacket.buildTcpPacket(
                        srcIp = packet.destIp,
                        destIp = packet.sourceIp,
                        srcPort = packet.destPort,
                        destPort = packet.sourcePort,
                        seq = session.hostSeq,
                        ack = session.clientSeq,
                        flags = 0x10 // ACK
                    )
                    writeToTun(ackPacket)
                } catch (e: Exception) {
                    Log.e(TAG, "Error writing to TCP SocketChannel", e)
                    sendTcpRst(packet)
                    tcpSessions.remove(sessionKey)
                }
            }
        }
    }

    private fun handleUdpPacket(packet: ProxyPacket) {
        if (packet.destPort == 53) {
            val packetBytes = ByteArray(packet.totalLength)
            packet.buffer.position(0)
            packet.buffer.get(packetBytes)
            val dnsQuery = DnsPacketHandler.parseDnsQuery(packetBytes, packetBytes.size)
            if (dnsQuery != null) {
                Thread {
                    try {
                        val response = resolveDns(dnsQuery)
                        writeToTun(response)
                    } catch (e: Exception) {
                        Log.e(TAG, "DNS resolution failed in ProxyEngine", e)
                    }
                }.start()
                return
            }
        }

        val sessionKey = "${packet.sourceIp}:${packet.sourcePort}->${packet.destIp}:${packet.destPort}"
        var session = udpSessions[sessionKey]

        if (session == null) {
            try {
                val datagramChannel = DatagramChannel.open()
                datagramChannel.configureBlocking(false)
                vpnService.protect(datagramChannel.socket())

                datagramChannel.connect(InetSocketAddress(packet.destIp, packet.destPort))

                session = UdpSession(
                    clientIp = packet.sourceIp,
                    clientPort = packet.sourcePort,
                    destIp = packet.destIp,
                    destPort = packet.destPort,
                    datagramChannel = datagramChannel
                )

                udpSessions[sessionKey] = session
                selector.wakeup()
                datagramChannel.register(selector, SelectionKey.OP_READ, session)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to open UDP DatagramChannel", e)
                return
            }
        }

        if (packet.udpPayloadLength > 0) {
            val payload = ByteArray(packet.udpPayloadLength)
            packet.buffer.position(packet.udpPayloadOffset)
            packet.buffer.get(payload)

            throttleBytes(packet.sourceIp, payload.size)

            try {
                session.datagramChannel.write(ByteBuffer.wrap(payload))
            } catch (e: Exception) {
                Log.e(TAG, "Error writing to UDP DatagramChannel", e)
                try {
                    session.datagramChannel.close()
                } catch (_: Exception) {}
                udpSessions.remove(sessionKey)
            }
        }
    }

    private fun sendTcpRst(packet: ProxyPacket) {
        val rstPacket = ProxyPacket.buildTcpPacket(
            srcIp = packet.destIp,
            destIp = packet.sourceIp,
            srcPort = packet.destPort,
            destPort = packet.sourcePort,
            seq = packet.tcpAck,
            ack = packet.tcpSeq + packet.tcpPayloadLength,
            flags = 0x04 // RST
        )
        writeToTun(rstPacket)
    }

    private fun writeToTun(packet: ByteArray) {
        synchronized(tunWriteLock) {
            try {
                tunOutputStream.write(packet)
            } catch (e: IOException) {
                Log.e(TAG, "Error writing packet to TUN", e)
            }
        }
    }

    private fun runSelector() {
        val buffer = ByteBuffer.allocate(BUFFER_SIZE)

        while (isRunning.get()) {
            try {
                val readyChannels = selector.select(1000)
                if (readyChannels == 0) continue

                val keys = selector.selectedKeys()
                val iterator = keys.iterator()

                while (iterator.hasNext()) {
                    val key = iterator.next()
                    iterator.remove()

                    if (!key.isValid) continue

                    if (key.isConnectable) {
                        handleConnect(key)
                    } else if (key.isReadable) {
                        handleRead(key, buffer)
                    }
                }
            } catch (e: Exception) {
                if (isRunning.get()) {
                    Log.e(TAG, "Error in Selector loop", e)
                }
                break
            }
        }
    }

    private fun handleConnect(key: SelectionKey) {
        val session = key.attachment() as? TcpSession ?: return
        val channel = key.channel() as SocketChannel
        try {
            if (channel.finishConnect()) {
                session.isConnected = true
                key.interestOps(SelectionKey.OP_READ)

                // Flush pending data
                val iterator = session.pendingData.iterator()
                while (iterator.hasNext()) {
                    val data = iterator.next()
                    throttleBytes(session.clientIp, data.size)
                    channel.write(ByteBuffer.wrap(data))
                    session.clientSeq += data.size

                    val ackPacket = ProxyPacket.buildTcpPacket(
                        srcIp = session.destIp,
                        destIp = session.clientIp,
                        srcPort = session.destPort,
                        destPort = session.clientPort,
                        seq = session.hostSeq,
                        ack = session.clientSeq,
                        flags = 0x10 // ACK
                    )
                    writeToTun(ackPacket)
                }
                session.pendingData.clear()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to finish TCP connect to ${session.destIp}:${session.destPort}", e)
            key.cancel()
            try {
                channel.close()
            } catch (_: Exception) {}

            val rstPacket = ProxyPacket.buildTcpPacket(
                srcIp = session.destIp,
                destIp = session.clientIp,
                srcPort = session.destPort,
                destPort = session.clientPort,
                seq = session.hostSeq,
                ack = session.clientSeq,
                flags = 0x04 // RST
            )
            writeToTun(rstPacket)

            val sessionKey = "${session.clientIp}:${session.clientPort}->${session.destIp}:${session.destPort}"
            tcpSessions.remove(sessionKey)
        }
    }

    private fun handleRead(key: SelectionKey, buffer: ByteBuffer) {
        val channel = key.channel()
        buffer.clear()

        if (channel is SocketChannel) {
            val session = key.attachment() as? TcpSession ?: return
            try {
                val bytesRead = channel.read(buffer)
                if (bytesRead == -1) {
                    key.cancel()
                    channel.close()

                    val finPacket = ProxyPacket.buildTcpPacket(
                        srcIp = session.destIp,
                        destIp = session.clientIp,
                        srcPort = session.destPort,
                        destPort = session.clientPort,
                        seq = session.hostSeq,
                        ack = session.clientSeq,
                        flags = 0x11 // FIN-ACK
                    )
                    writeToTun(finPacket)

                    val sessionKey = "${session.clientIp}:${session.clientPort}->${session.destIp}:${session.destPort}"
                    tcpSessions.remove(sessionKey)
                    return
                }

                if (bytesRead > 0) {
                    buffer.flip()
                    val payload = ByteArray(bytesRead)
                    buffer.get(payload)

                    throttleBytes(session.clientIp, bytesRead)

                    val dataPacket = ProxyPacket.buildTcpPacket(
                        srcIp = session.destIp,
                        destIp = session.clientIp,
                        srcPort = session.destPort,
                        destPort = session.clientPort,
                        seq = session.hostSeq,
                        ack = session.clientSeq,
                        flags = 0x18, // ACK-PSH
                        payload = payload
                    )
                    writeToTun(dataPacket)
                    session.hostSeq += bytesRead
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to read from TCP channel", e)
                key.cancel()
                try {
                    channel.close()
                } catch (_: Exception) {}

                val rstPacket = ProxyPacket.buildTcpPacket(
                    srcIp = session.destIp,
                    destIp = session.clientIp,
                    srcPort = session.destPort,
                    destPort = session.clientPort,
                    seq = session.hostSeq,
                    ack = session.clientSeq,
                    flags = 0x04 // RST
                )
                writeToTun(rstPacket)

                val sessionKey = "${session.clientIp}:${session.clientPort}->${session.destIp}:${session.destPort}"
                tcpSessions.remove(sessionKey)
            }
        } else if (channel is DatagramChannel) {
            val session = key.attachment() as? UdpSession ?: return
            try {
                val bytesRead = channel.read(buffer)
                if (bytesRead > 0) {
                    buffer.flip()
                    val payload = ByteArray(bytesRead)
                    buffer.get(payload)

                    throttleBytes(session.clientIp, bytesRead)

                    val udpResponse = ProxyPacket.buildUdpPacket(
                        srcIp = session.destIp,
                        destIp = session.clientIp,
                        srcPort = session.destPort,
                        destPort = session.clientPort,
                        payload = payload
                    )
                    writeToTun(udpResponse)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to read from UDP channel", e)
                key.cancel()
                try {
                    channel.close()
                } catch (_: Exception) {}

                val sessionKey = "${session.clientIp}:${session.clientPort}->${session.destIp}:${session.destPort}"
                udpSessions.remove(sessionKey)
            }
        }
    }
}
