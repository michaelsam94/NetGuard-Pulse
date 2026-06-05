package com.michael.netguardplus.system.vpn.proxy

import java.nio.ByteBuffer

/**
 * Parses raw packets read from the TUN interface.
 */
class ProxyPacket(val buffer: ByteBuffer) {
    val ipVersion: Int
    val ipHeaderLength: Int
    val totalLength: Int
    val protocol: Int
    val sourceIp: String
    val destIp: String

    var sourcePort: Int = 0
    var destPort: Int = 0

    // TCP specific fields
    var tcpSeq: Long = 0L
    var tcpAck: Long = 0L
    var tcpFlags: Int = 0
    var tcpHeaderLength: Int = 0
    var tcpPayloadOffset: Int = 0
    var tcpPayloadLength: Int = 0

    // UDP specific fields
    var udpLength: Int = 0
    var udpPayloadOffset: Int = 0
    var udpPayloadLength: Int = 0

    init {
        val versionAndIhl = buffer.get(0).toInt() and 0xFF
        ipVersion = versionAndIhl shr 4
        ipHeaderLength = (versionAndIhl and 0x0F) * 4
        totalLength = buffer.getShort(2).toInt() and 0xFFFF
        protocol = buffer.get(9).toInt() and 0xFF

        sourceIp = readIpv4Address(12)
        destIp = readIpv4Address(16)

        if (protocol == 6 && totalLength >= ipHeaderLength + 20) { // TCP
            sourcePort = buffer.getShort(ipHeaderLength).toInt() and 0xFFFF
            destPort = buffer.getShort(ipHeaderLength + 2).toInt() and 0xFFFF
            tcpSeq = buffer.getInt(ipHeaderLength + 4).toLong() and 0xFFFFFFFFL
            tcpAck = buffer.getInt(ipHeaderLength + 8).toLong() and 0xFFFFFFFFL
            val flagsVal = buffer.getShort(ipHeaderLength + 12).toInt() and 0xFFFF
            tcpHeaderLength = ((flagsVal shr 12) and 0x0F) * 4
            tcpFlags = flagsVal and 0xFF
            tcpPayloadOffset = ipHeaderLength + tcpHeaderLength
            tcpPayloadLength = (totalLength - tcpPayloadOffset).coerceAtLeast(0)
        } else if (protocol == 17 && totalLength >= ipHeaderLength + 8) { // UDP
            sourcePort = buffer.getShort(ipHeaderLength).toInt() and 0xFFFF
            destPort = buffer.getShort(ipHeaderLength + 2).toInt() and 0xFFFF
            udpLength = buffer.getShort(ipHeaderLength + 4).toInt() and 0xFFFF
            udpPayloadOffset = ipHeaderLength + 8
            udpPayloadLength = (udpLength - 8).coerceAtLeast(0)
        }
    }

    val isTcp: Boolean get() = protocol == 6
    val isUdp: Boolean get() = protocol == 17

    val isTcpSyn: Boolean get() = isTcp && (tcpFlags and 0x02) != 0
    val isTcpAck: Boolean get() = isTcp && (tcpFlags and 0x10) != 0
    val isTcpFin: Boolean get() = isTcp && (tcpFlags and 0x01) != 0
    val isTcpRst: Boolean get() = isTcp && (tcpFlags and 0x04) != 0
    val isTcpPsh: Boolean get() = isTcp && (tcpFlags and 0x08) != 0

    private fun readIpv4Address(offset: Int): String {
        return "${buffer.get(offset).toInt() and 0xFF}.${buffer.get(offset + 1).toInt() and 0xFF}." +
                "${buffer.get(offset + 2).toInt() and 0xFF}.${buffer.get(offset + 3).toInt() and 0xFF}"
    }

    companion object {
        /**
         * Reconstructs a TCP packet inside a byte array.
         */
        fun buildTcpPacket(
            srcIp: String, destIp: String, srcPort: Int, destPort: Int,
            seq: Long, ack: Long, flags: Int, payload: ByteArray? = null
        ): ByteArray {
            val ipLen = 20
            val tcpLen = 20
            val payLen = payload?.size ?: 0
            val totalLen = ipLen + tcpLen + payLen
            val buffer = ByteBuffer.allocate(totalLen)

            // IP Header
            buffer.put(0, ((4 shl 4) or 5).toByte()) // Version 4, IHL 5 (20 bytes)
            buffer.put(1, 0.toByte())
            buffer.putShort(2, totalLen.toShort())
            buffer.putShort(4, 0.toShort()) // ID
            buffer.putShort(6, 0.toShort()) // Flags
            buffer.put(8, 64.toByte()) // TTL
            buffer.put(9, 6.toByte()) // Protocol TCP
            buffer.putShort(10, 0.toShort()) // IP Checksum placeholder
            writeIpv4Address(buffer, 12, srcIp)
            writeIpv4Address(buffer, 16, destIp)

            val ipChecksum = calculateChecksum(buffer, 0, ipLen)
            buffer.putShort(10, ipChecksum)

            // TCP Header
            buffer.putShort(20, srcPort.toShort())
            buffer.putShort(22, destPort.toShort())
            buffer.putInt(24, seq.toInt())
            buffer.putInt(28, ack.toInt())
            val offsetAndFlags = ((5 shl 12) or flags).toShort()
            buffer.putShort(32, offsetAndFlags)
            buffer.putShort(34, 16384.toShort()) // Window size
            buffer.putShort(36, 0.toShort()) // Checksum placeholder
            buffer.putShort(38, 0.toShort()) // Urgent pointer

            if (payload != null) {
                buffer.position(40)
                buffer.put(payload)
            }

            val tcpChecksum = calculateTcpChecksum(buffer, srcIp, destIp, tcpLen + payLen)
            buffer.putShort(36, tcpChecksum)

            return buffer.array()
        }

        /**
         * Reconstructs a UDP packet inside a byte array.
         */
        fun buildUdpPacket(
            srcIp: String, destIp: String, srcPort: Int, destPort: Int, payload: ByteArray
        ): ByteArray {
            val ipLen = 20
            val udpLen = 8
            val payLen = payload.size
            val totalLen = ipLen + udpLen + payLen
            val buffer = ByteBuffer.allocate(totalLen)

            // IP Header
            buffer.put(0, ((4 shl 4) or 5).toByte())
            buffer.put(1, 0.toByte())
            buffer.putShort(2, totalLen.toShort())
            buffer.putShort(4, 0.toShort())
            buffer.putShort(6, 0.toShort())
            buffer.put(8, 64.toByte())
            buffer.put(9, 17.toByte()) // Protocol UDP
            buffer.putShort(10, 0.toShort())
            writeIpv4Address(buffer, 12, srcIp)
            writeIpv4Address(buffer, 16, destIp)

            val ipChecksum = calculateChecksum(buffer, 0, ipLen)
            buffer.putShort(10, ipChecksum)

            // UDP Header
            buffer.putShort(20, srcPort.toShort())
            buffer.putShort(22, destPort.toShort())
            buffer.putShort(24, (udpLen + payLen).toShort())
            buffer.putShort(26, 0.toShort()) // Checksum optional in IPv4 (0 = disabled)

            buffer.position(28)
            buffer.put(payload)

            return buffer.array()
        }

        private fun writeIpv4Address(buffer: ByteBuffer, offset: Int, ip: String) {
            val parts = ip.split(".")
            if (parts.size == 4) {
                buffer.put(offset, parts[0].toInt().toByte())
                buffer.put(offset + 1, parts[1].toInt().toByte())
                buffer.put(offset + 2, parts[2].toInt().toByte())
                buffer.put(offset + 3, parts[3].toInt().toByte())
            }
        }

        private fun calculateChecksum(buffer: ByteBuffer, offset: Int, length: Int): Short {
            var sum = 0
            var i = offset
            while (i < offset + length) {
                val word = ((buffer.get(i).toInt() and 0xFF) shl 8) or (buffer.get(i + 1).toInt() and 0xFF)
                sum += word
                i += 2
            }
            while (sum shr 16 != 0) {
                sum = (sum and 0xFFFF) + (sum shr 16)
            }
            return (sum.inv()).toShort()
        }

        private fun calculateTcpChecksum(buffer: ByteBuffer, srcIp: String, destIp: String, tcpAndPayloadLength: Int): Short {
            var sum = 0
            val srcParts = srcIp.split(".")
            val destParts = destIp.split(".")
            if (srcParts.size == 4 && destParts.size == 4) {
                sum += (srcParts[0].toInt() shl 8) or srcParts[1].toInt()
                sum += (srcParts[2].toInt() shl 8) or srcParts[3].toInt()
                sum += (destParts[0].toInt() shl 8) or destParts[1].toInt()
                sum += (destParts[2].toInt() shl 8) or destParts[3].toInt()
            }
            sum += 6 // Protocol TCP
            sum += tcpAndPayloadLength

            var i = 20
            while (i < 20 + tcpAndPayloadLength) {
                val word = if (i == 20 + tcpAndPayloadLength - 1) {
                    (buffer.get(i).toInt() and 0xFF) shl 8
                } else {
                    ((buffer.get(i).toInt() and 0xFF) shl 8) or (buffer.get(i + 1).toInt() and 0xFF)
                }
                sum += word
                i += 2
            }
            while (sum shr 16 != 0) {
                sum = (sum and 0xFFFF) + (sum shr 16)
            }
            return (sum.inv()).toShort()
        }
    }
}
