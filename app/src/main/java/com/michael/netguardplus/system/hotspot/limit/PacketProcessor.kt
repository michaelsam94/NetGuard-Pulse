package com.michael.netguardplus.system.hotspot.limit

/**
 * Parses raw IPv4 packets from the VPN TUN interface.
 */
object PacketProcessor {

    const val PROTO_TCP = 6
    const val PROTO_UDP = 17

    data class ParsedIpv4(
        val sourceIp: String,
        val destIp: String,
        val protocol: Int
    )

    fun extractSourceIp(packet: ByteArray): String {
        if (packet.size < 20) return ""
        val version = (packet[0].toInt() and 0xFF) shr 4
        if (version != 4) return ""
        return ipv4FromOffset(packet, 12)
    }

    fun extractDestIp(packet: ByteArray): String {
        if (packet.size < 20) return ""
        val version = (packet[0].toInt() and 0xFF) shr 4
        if (version != 4) return ""
        return ipv4FromOffset(packet, 16)
    }

    fun extractProtocol(packet: ByteArray): Int {
        if (packet.size < 10) return 0
        val version = (packet[0].toInt() and 0xFF) shr 4
        if (version != 4) return 0
        return packet[9].toInt() and 0xFF
    }

    fun parse(packet: ByteArray, length: Int = packet.size): ParsedIpv4? {
        if (length < 20) return null
        val version = (packet[0].toInt() and 0xFF) shr 4
        if (version != 4) return null
        val end = length.coerceAtMost(packet.size)
        if (end < 20) return null
        return ParsedIpv4(
            sourceIp = ipv4FromOffset(packet, 12),
            destIp = ipv4FromOffset(packet, 16),
            protocol = packet[9].toInt() and 0xFF
        )
    }

    private fun ipv4FromOffset(packet: ByteArray, offset: Int): String {
        if (offset + 3 >= packet.size) return ""
        return "${packet[offset].toInt() and 0xFF}.${packet[offset + 1].toInt() and 0xFF}." +
            "${packet[offset + 2].toInt() and 0xFF}.${packet[offset + 3].toInt() and 0xFF}"
    }
}
