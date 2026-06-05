package com.michael.netguardplus.system.vpn

/**
 * Builds IPv4 TCP segments carrying an HTTP captive portal response on the VPN TUN interface.
 */
object CaptivePortalHttpInjector {

    private const val IP_PROTOCOL_TCP = 6
    private const val TCP_FLAG_SYN = 0x02
    private const val TCP_FLAG_ACK = 0x10
    private const val TCP_FLAG_PSH = 0x08
    private const val TCP_FLAG_FIN = 0x01
    private const val HTTP_PORT = 80

    data class ParsedTcp(
        val ipHeaderLength: Int,
        val tcpOffset: Int,
        val tcpHeaderLength: Int,
        val sourceIp: String,
        val destIp: String,
        val sourcePort: Int,
        val destPort: Int,
        val seq: Long,
        val ack: Long,
        val flags: Int,
        val payloadOffset: Int,
        val payloadLength: Int
    )

    fun parseTcp(packet: ByteArray, length: Int): ParsedTcp? {
        if (length < 40) return null
        val versionAndIhl = packet[0].toInt() and 0xFF
        if (versionAndIhl shr 4 != 4) return null
        val ipHeaderLength = (versionAndIhl and 0x0F) * 4
        if (length < ipHeaderLength + 20) return null
        if ((packet[9].toInt() and 0xFF) != IP_PROTOCOL_TCP) return null

        val tcpOffset = ipHeaderLength
        val tcpHeaderLength = ((packet[tcpOffset + 12].toInt() shr 4) and 0x0F) * 4
        if (tcpHeaderLength < 20 || length < tcpOffset + tcpHeaderLength) return null

        val payloadOffset = tcpOffset + tcpHeaderLength
        val totalLength = readUint16(packet, 2)
        val payloadLength = (totalLength - ipHeaderLength - tcpHeaderLength).coerceAtLeast(0)

        return ParsedTcp(
            ipHeaderLength = ipHeaderLength,
            tcpOffset = tcpOffset,
            tcpHeaderLength = tcpHeaderLength,
            sourceIp = readIpv4(packet, 12),
            destIp = readIpv4(packet, 16),
            sourcePort = readPort(packet, tcpOffset),
            destPort = readPort(packet, tcpOffset + 2),
            seq = readUint32(packet, tcpOffset + 4),
            ack = readUint32(packet, tcpOffset + 8),
            flags = packet[tcpOffset + 13].toInt() and 0xFF,
            payloadOffset = payloadOffset,
            payloadLength = payloadLength.coerceAtMost(length - payloadOffset)
        )
    }

    fun isHttpPort(port: Int): Boolean = port == HTTP_PORT || port == 8080

    fun buildResponseIfNeeded(packet: ByteArray, length: Int, html: String): ByteArray? {
        val tcp = parseTcp(packet, length) ?: return null
        if (!isHttpPort(tcp.destPort)) return null

        val synOnly = (tcp.flags and TCP_FLAG_SYN) != 0 && (tcp.flags and TCP_FLAG_ACK) == 0
        if (synOnly) {
            return buildTcpSegment(
                tcp,
                byteArrayOf(),
                seq = 0x2468ACE0L,
                ack = tcp.seq + 1,
                flags = TCP_FLAG_SYN or TCP_FLAG_ACK
            )
        }

        val hasPayload = tcp.payloadLength > 0
        val ackOnly = (tcp.flags and TCP_FLAG_ACK) != 0 && !hasPayload
        if (ackOnly || hasPayload) {
            val body = html.toByteArray(Charsets.UTF_8)
            val headers = buildString {
                append("HTTP/1.1 200 OK\r\n")
                append("Content-Type: text/html; charset=utf-8\r\n")
                append("Content-Length: ${body.size}\r\n")
                append("Connection: close\r\n")
                append("\r\n")
            }.toByteArray(Charsets.US_ASCII)
            val httpPayload = headers + body
            return buildTcpSegment(
                tcp,
                httpPayload,
                seq = tcp.ack,
                ack = tcp.seq + if (hasPayload) tcp.payloadLength else 1,
                flags = TCP_FLAG_ACK or TCP_FLAG_PSH or TCP_FLAG_FIN
            )
        }
        return null
    }

    private fun buildTcpSegment(
        request: ParsedTcp,
        payload: ByteArray,
        seq: Long,
        ack: Long,
        flags: Int
    ): ByteArray {
        val tcpHeaderLength = 20
        val ipHeaderLength = 20
        val totalLength = ipHeaderLength + tcpHeaderLength + payload.size
        val packet = ByteArray(totalLength)

        packet[0] = 0x45
        writeUint16(packet, 2, totalLength)
        packet[8] = 64
        packet[9] = IP_PROTOCOL_TCP.toByte()
        writeIpv4(packet, 12, request.destIp)
        writeIpv4(packet, 16, request.sourceIp)
        writeIpChecksum(packet, ipHeaderLength)

        val tcpOffset = ipHeaderLength
        writePort(packet, tcpOffset, request.destPort)
        writePort(packet, tcpOffset + 2, request.sourcePort)
        writeUint32(packet, tcpOffset + 4, seq)
        writeUint32(packet, tcpOffset + 8, ack)
        packet[tcpOffset + 12] = 0x50
        packet[tcpOffset + 13] = flags.toByte()
        writeUint16(packet, tcpOffset + 14, 65535)
        writeUint16(packet, tcpOffset + 16, 0)
        writeUint16(packet, tcpOffset + 18, 0)

        if (payload.isNotEmpty()) {
            System.arraycopy(payload, 0, packet, tcpOffset + tcpHeaderLength, payload.size)
        }
        writeTcpChecksum(packet, ipHeaderLength, tcpHeaderLength + payload.size)
        return packet
    }

    private fun readIpv4(buffer: ByteArray, offset: Int): String =
        "${buffer[offset].toInt() and 0xFF}.${buffer[offset + 1].toInt() and 0xFF}." +
            "${buffer[offset + 2].toInt() and 0xFF}.${buffer[offset + 3].toInt() and 0xFF}"

    private fun writeIpv4(buffer: ByteArray, offset: Int, ip: String) {
        val parts = ip.split('.')
        for (i in 0 until 4) {
            buffer[offset + i] = parts[i].toInt().toByte()
        }
    }

    private fun readPort(buffer: ByteArray, offset: Int): Int =
        ((buffer[offset].toInt() and 0xFF) shl 8) or (buffer[offset + 1].toInt() and 0xFF)

    private fun writePort(buffer: ByteArray, offset: Int, port: Int) {
        buffer[offset] = (port shr 8).toByte()
        buffer[offset + 1] = (port and 0xFF).toByte()
    }

    private fun readUint16(buffer: ByteArray, offset: Int): Int =
        ((buffer[offset].toInt() and 0xFF) shl 8) or (buffer[offset + 1].toInt() and 0xFF)

    private fun readUint32(buffer: ByteArray, offset: Int): Long =
        ((buffer[offset].toLong() and 0xFF) shl 24) or
            ((buffer[offset + 1].toLong() and 0xFF) shl 16) or
            ((buffer[offset + 2].toLong() and 0xFF) shl 8) or
            (buffer[offset + 3].toLong() and 0xFF)

    private fun writeUint32(buffer: ByteArray, offset: Int, value: Long) {
        buffer[offset] = ((value shr 24) and 0xFF).toByte()
        buffer[offset + 1] = ((value shr 16) and 0xFF).toByte()
        buffer[offset + 2] = ((value shr 8) and 0xFF).toByte()
        buffer[offset + 3] = (value and 0xFF).toByte()
    }

    private fun writeUint16(buffer: ByteArray, offset: Int, value: Int) {
        buffer[offset] = ((value shr 8) and 0xFF).toByte()
        buffer[offset + 1] = (value and 0xFF).toByte()
    }

    private fun writeIpChecksum(packet: ByteArray, headerLength: Int) {
        packet[10] = 0
        packet[11] = 0
        var sum = 0
        var i = 0
        while (i < headerLength) {
            sum += ((packet[i].toInt() and 0xFF) shl 8) or (packet[i + 1].toInt() and 0xFF)
            i += 2
        }
        while (sum shr 16 != 0) {
            sum = (sum and 0xFFFF) + (sum shr 16)
        }
        val checksum = sum.inv() and 0xFFFF
        packet[10] = (checksum shr 8).toByte()
        packet[11] = (checksum and 0xFF).toByte()
    }

    private fun writeTcpChecksum(packet: ByteArray, ipHeaderLength: Int, tcpLength: Int) {
        val tcpOffset = ipHeaderLength
        packet[tcpOffset + 16] = 0
        packet[tcpOffset + 17] = 0
        var sum = 0
        sum += (packet[12].toInt() and 0xFF shl 8) or (packet[13].toInt() and 0xFF)
        sum += (packet[14].toInt() and 0xFF shl 8) or (packet[15].toInt() and 0xFF)
        sum += (packet[16].toInt() and 0xFF shl 8) or (packet[17].toInt() and 0xFF)
        sum += (packet[18].toInt() and 0xFF shl 8) or (packet[19].toInt() and 0xFF)
        sum += IP_PROTOCOL_TCP
        sum += tcpLength
        var i = tcpOffset
        while (i < tcpOffset + tcpLength) {
            sum += ((packet[i].toInt() and 0xFF) shl 8) or (packet[i + 1].toInt() and 0xFF)
            i += 2
        }
        if (tcpLength % 2 != 0) {
            sum += (packet[tcpOffset + tcpLength - 1].toInt() and 0xFF) shl 8
        }
        while (sum shr 16 != 0) {
            sum = (sum and 0xFFFF) + (sum shr 16)
        }
        val checksum = sum.inv() and 0xFFFF
        packet[tcpOffset + 16] = (checksum shr 8).toByte()
        packet[tcpOffset + 17] = (checksum and 0xFF).toByte()
    }
}
