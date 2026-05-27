package com.example.system.vpn

import java.nio.ByteBuffer

data class IpPacket(
    val isDnsQuery: Boolean,
    val dnsQueryDomain: String?,
    val protocol: Int,
    val sourceIp: String,
    val destIp: String
)

object PacketParser {

    /**
     * Parses a raw IP packet directly from the virtual Tun interface.
     */
    fun parseIpPacket(buffer: ByteBuffer, length: Int): IpPacket {
        if (length < 40) return IpPacket(false, null, 0, "", "")

        val versionAndIHL = buffer.get(0).toInt() and 0xFF
        val version = versionAndIHL shr 4
        if (version != 4) {
            // Only support IPv4 for parsing
            return IpPacket(false, null, 0, "", "")
        }

        val ihl = (versionAndIHL and 0x0F) * 4
        val protocol = buffer.get(9).toInt() and 0xFF

        // Extract IP addresses
        val srcIp = "${buffer.get(12).toInt() and 0xFF}.${buffer.get(13).toInt() and 0xFF}.${buffer.get(14).toInt() and 0xFF}.${buffer.get(15).toInt() and 0xFF}"
        val destIp = "${buffer.get(16).toInt() and 0xFF}.${buffer.get(17).toInt() and 0xFF}.${buffer.get(18).toInt() and 0xFF}.${buffer.get(19).toInt() and 0xFF}"

        // Protocol 17 is UDP
        if (protocol == 17) {
            val udpOffset = ihl
            val destPort = ((buffer.get(udpOffset + 2).toInt() and 0xFF) shl 8) or (buffer.get(udpOffset + 3).toInt() and 0xFF)
            
            // Port 53 is DNS
            if (destPort == 53) {
                val dnsOffset = udpOffset + 8 // UDP header is 8 bytes
                val domain = parseDnsDomain(buffer, dnsOffset, length)
                if (domain != null) {
                    return IpPacket(true, domain, protocol, srcIp, destIp)
                }
            }
        }

        return IpPacket(false, null, protocol, srcIp, destIp)
    }

    private fun parseDnsDomain(buffer: ByteBuffer, dnsOffset: Int, length: Int): String? {
        try {
            // DNS Question section is at dnsOffset + 12 (DNS Header is 12 bytes)
            var currentPos = dnsOffset + 12
            if (currentPos >= length) return null

            val domainParts = mutableListOf<String>()
            while (currentPos < length) {
                val labelLength = buffer.get(currentPos).toInt() and 0xFF
                if (labelLength == 0) {
                    break // End of domain labels
                }
                currentPos++
                if (currentPos + labelLength > length) return null

                val labelBytes = ByteArray(labelLength)
                for (i in 0 until labelLength) {
                    labelBytes[i] = buffer.get(currentPos + i)
                }
                domainParts.add(String(labelBytes))
                currentPos += labelLength
            }

            if (domainParts.isNotEmpty()) {
                return domainParts.joinToString(".")
            }
        } catch (e: Exception) {
            // Safe fallthrough
        }
        return null
    }
}
