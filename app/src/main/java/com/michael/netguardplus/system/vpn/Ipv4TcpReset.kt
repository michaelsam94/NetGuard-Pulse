package com.michael.netguardplus.system.vpn

/**
 * Builds a TCP RST reply for IPv4 packets seen on the VPN TUN interface.
 */
object Ipv4TcpReset {

    private const val IP_PROTOCOL_TCP = 6

    fun buildResetIfTcp(packet: ByteArray, length: Int): ByteArray? {
        if (length < 40) return null
        val versionAndIhl = packet[0].toInt() and 0xFF
        if (versionAndIhl shr 4 != 4) return null
        val ipHeaderLength = (versionAndIhl and 0x0F) * 4
        if (length < ipHeaderLength + 20) return null
        if ((packet[9].toInt() and 0xFF) != IP_PROTOCOL_TCP) return null

        val tcpOffset = ipHeaderLength
        val tcpHeaderLength = ((packet[tcpOffset + 12].toInt() shr 4) and 0x0F) * 4
        if (tcpHeaderLength < 20) return null

        val totalLength = ipHeaderLength + tcpHeaderLength
        val response = ByteArray(totalLength)

        System.arraycopy(packet, 0, response, 0, ipHeaderLength)
        System.arraycopy(packet, 16, response, 12, 4)
        System.arraycopy(packet, 12, response, 16, 4)

        writeUint16(response, 2, totalLength)
        response[8] = 64
        response[10] = 0
        response[11] = 0
        writeIpChecksum(response, ipHeaderLength)

        System.arraycopy(packet, tcpOffset, response, tcpOffset, tcpHeaderLength)
        val srcPortOffset = tcpOffset
        val srcPort = readPort(packet, srcPortOffset)
        val dstPort = readPort(packet, srcPortOffset + 2)
        writePort(response, srcPortOffset, dstPort)
        writePort(response, srcPortOffset + 2, srcPort)

        val seq = readUint32(packet, tcpOffset + 4)
        val ack = readUint32(packet, tcpOffset + 8)
        writeUint32(response, tcpOffset + 4, ack)
        writeUint32(response, tcpOffset + 8, seq + 1)

        response[tcpOffset + 13] = 0x14 // RST + ACK
        response[tcpOffset + 14] = 0
        response[tcpOffset + 15] = 0
        writeUint16(response, tcpOffset + 16, 0)
        writeUint16(response, tcpOffset + 18, 0)
        writeTcpChecksum(response, ipHeaderLength, tcpHeaderLength)

        return response
    }

    private fun readPort(buffer: ByteArray, offset: Int): Int {
        return ((buffer[offset].toInt() and 0xFF) shl 8) or (buffer[offset + 1].toInt() and 0xFF)
    }

    private fun writePort(buffer: ByteArray, offset: Int, port: Int) {
        buffer[offset] = (port shr 8).toByte()
        buffer[offset + 1] = (port and 0xFF).toByte()
    }

    private fun readUint32(buffer: ByteArray, offset: Int): Long {
        return ((buffer[offset].toLong() and 0xFF) shl 24) or
            ((buffer[offset + 1].toLong() and 0xFF) shl 16) or
            ((buffer[offset + 2].toLong() and 0xFF) shl 8) or
            (buffer[offset + 3].toLong() and 0xFF)
    }

    private fun writeUint32(buffer: ByteArray, offset: Int, value: Long) {
        buffer[offset] = ((value shr 24) and 0xFF).toByte()
        buffer[offset + 1] = ((value shr 16) and 0xFF).toByte()
        buffer[offset + 2] = ((value shr 8) and 0xFF).toByte()
        buffer[offset + 3] = (value and 0xFF).toByte()
    }

    private fun writeUint16(buffer: ByteArray, offset: Int, value: Int) {
        buffer[offset] = (value shr 8).toByte()
        buffer[offset + 1] = (value and 0xFF).toByte()
    }

    private fun writeIpChecksum(packet: ByteArray, headerLength: Int) {
        packet[10] = 0
        packet[11] = 0
        writeUint16(packet, 10, ipChecksum(packet, 0, headerLength))
    }

    private fun writeTcpChecksum(packet: ByteArray, ipHeaderLength: Int, tcpLength: Int) {
        val tcpOffset = ipHeaderLength
        packet[tcpOffset + 16] = 0
        packet[tcpOffset + 17] = 0
        val checksum = tcpChecksum(packet, ipHeaderLength, tcpLength)
        if (checksum != 0) {
            writeUint16(packet, tcpOffset + 16, checksum)
        }
    }

    private fun ipChecksum(data: ByteArray, offset: Int, length: Int): Int {
        var sum = 0
        var i = offset
        while (i < offset + length - 1) {
            sum += ((data[i].toInt() and 0xFF) shl 8) or (data[i + 1].toInt() and 0xFF)
            i += 2
        }
        if (length and 1 != 0) {
            sum += (data[offset + length - 1].toInt() and 0xFF) shl 8
        }
        while (sum shr 16 != 0) {
            sum = (sum and 0xFFFF) + (sum shr 16)
        }
        return sum.inv() and 0xFFFF
    }

    private fun tcpChecksum(packet: ByteArray, ipHeaderLength: Int, tcpLength: Int): Int {
        var sum = 0
        for (i in 12 until 20 step 2) {
            sum += ((packet[i].toInt() and 0xFF) shl 8) or (packet[i + 1].toInt() and 0xFF)
        }
        sum += IP_PROTOCOL_TCP
        sum += tcpLength
        val tcpOffset = ipHeaderLength
        var i = tcpOffset
        while (i < tcpOffset + tcpLength - 1) {
            sum += ((packet[i].toInt() and 0xFF) shl 8) or (packet[i + 1].toInt() and 0xFF)
            i += 2
        }
        if (tcpLength and 1 != 0) {
            sum += (packet[tcpOffset + tcpLength - 1].toInt() and 0xFF) shl 8
        }
        while (sum shr 16 != 0) {
            sum = (sum and 0xFFFF) + (sum shr 16)
        }
        return sum.inv() and 0xFFFF
    }
}
