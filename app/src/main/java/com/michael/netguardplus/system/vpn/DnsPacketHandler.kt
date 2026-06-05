package com.michael.netguardplus.system.vpn

import java.net.InetAddress

/**
 * Handles DNS-only packets on the VPN TUN interface.
 * Supports IPv4 and IPv6 UDP DNS queries.
 */
object DnsPacketHandler {

    private const val IP_PROTOCOL_UDP = 17
    private const val IP_PROTOCOL_TCP = 6
    private const val DNS_PORT = 53
    private const val IPV6_HEADER_LENGTH = 40

    /** IPv4 sinkhole address returned for blocked hotspot clients and parental blocks. */
    const val SINKHOLE_IPV4 = "0.0.0.0"

    data class ParsedDnsQuery(
        val rawPacket: ByteArray,
        val packetLength: Int,
        val ipHeaderLength: Int,
        val udpOffset: Int,
        val clientPort: Int,
        val sourceIp: String,
        val destDnsIp: String,
        val dnsPayloadOffset: Int,
        val dnsPayloadLength: Int,
        val domain: String?,
        val isIpv6: Boolean = false,
        val isTcp: Boolean = false,
        val tcpSeq: Long = 0,
        val tcpAck: Long = 0
    )

    fun parseDnsQuery(buffer: ByteArray, length: Int): ParsedDnsQuery? {
        return parseIpv4UdpQuery(buffer, length, strict = true)
            ?: parseIpv4UdpQuery(buffer, length, strict = false)
            ?: parseIpv4TcpQuery(buffer, length, strict = true)
            ?: parseIpv4TcpQuery(buffer, length, strict = false)
            ?: parseIpv6UdpQuery(buffer, length, strict = true)
            ?: parseIpv6UdpQuery(buffer, length, strict = false)
            ?: parseIpv6TcpQuery(buffer, length, strict = true)
            ?: parseIpv6TcpQuery(buffer, length, strict = false)
    }

    private fun parseIpv4UdpQuery(buffer: ByteArray, length: Int, strict: Boolean): ParsedDnsQuery? {
        if (length < 28) return null

        val versionAndIhl = buffer[0].toInt() and 0xFF
        if (versionAndIhl shr 4 != 4) return null

        val ipHeaderLength = (versionAndIhl and 0x0F) * 4
        if (ipHeaderLength < 20 || length < ipHeaderLength + 8) return null
        if ((buffer[9].toInt() and 0xFF) != IP_PROTOCOL_UDP) return null

        val udpOffset = ipHeaderLength
        val srcPort = readPort(buffer, udpOffset)
        val dstPort = readPort(buffer, udpOffset + 2)
        if (dstPort != DNS_PORT && srcPort != DNS_PORT) return null

        val clientPort = if (dstPort == DNS_PORT) srcPort else dstPort
        val dnsOffset = udpOffset + 8
        val dnsLength = resolveDnsLength(buffer, length, udpOffset, dnsOffset)
        if (dnsLength < 12) return null

        val dnsPayload = buffer.copyOfRange(dnsOffset, dnsOffset + dnsLength)
        val domain = extractDomainFromDnsPayload(dnsPayload)
        if (strict && domain == null) return null

        return ParsedDnsQuery(
            rawPacket = buffer.copyOf(length),
            packetLength = length,
            ipHeaderLength = ipHeaderLength,
            udpOffset = udpOffset,
            clientPort = clientPort,
            sourceIp = readIpv4(buffer, 12),
            destDnsIp = readIpv4(buffer, 16),
            dnsPayloadOffset = dnsOffset,
            dnsPayloadLength = dnsLength,
            domain = domain,
            isIpv6 = false,
            isTcp = false
        )
    }

    private fun parseIpv4TcpQuery(buffer: ByteArray, length: Int, strict: Boolean): ParsedDnsQuery? {
        if (length < 40) return null
        val versionAndIhl = buffer[0].toInt() and 0xFF
        if (versionAndIhl shr 4 != 4) return null
        val ipHeaderLength = (versionAndIhl and 0x0F) * 4
        if ((buffer[9].toInt() and 0xFF) != IP_PROTOCOL_TCP) return null

        return parseTcpDnsAt(
            buffer = buffer,
            length = length,
            transportOffset = ipHeaderLength,
            networkHeaderLength = ipHeaderLength,
            sourceIp = readIpv4(buffer, 12),
            destIp = readIpv4(buffer, 16),
            isIpv6 = false,
            strict = strict
        )
    }

    private fun parseIpv6UdpQuery(buffer: ByteArray, length: Int, strict: Boolean): ParsedDnsQuery? {
        if (length < IPV6_HEADER_LENGTH + 8) return null
        if ((buffer[0].toInt() shr 4) != 6) return null

        val udpOffset = locateIpv6UdpOffset(buffer, length) ?: return null
        val srcPort = readPort(buffer, udpOffset)
        val dstPort = readPort(buffer, udpOffset + 2)
        if (dstPort != DNS_PORT && srcPort != DNS_PORT) return null

        val clientPort = if (dstPort == DNS_PORT) srcPort else dstPort
        val dnsOffset = udpOffset + 8
        val dnsLength = resolveDnsLength(buffer, length, udpOffset, dnsOffset)
        if (dnsLength < 12) return null

        val dnsPayload = buffer.copyOfRange(dnsOffset, dnsOffset + dnsLength)
        val domain = extractDomainFromDnsPayload(dnsPayload)
        if (strict && domain == null) return null

        return ParsedDnsQuery(
            rawPacket = buffer.copyOf(length),
            packetLength = length,
            ipHeaderLength = udpOffset,
            udpOffset = udpOffset,
            clientPort = clientPort,
            sourceIp = readIpv6(buffer, 8),
            destDnsIp = readIpv6(buffer, 24),
            dnsPayloadOffset = dnsOffset,
            dnsPayloadLength = dnsLength,
            domain = domain,
            isIpv6 = true,
            isTcp = false
        )
    }

    private fun parseIpv6TcpQuery(buffer: ByteArray, length: Int, strict: Boolean): ParsedDnsQuery? {
        if (length < IPV6_HEADER_LENGTH + 20) return null
        if ((buffer[0].toInt() shr 4) != 6) return null

        val tcpOffset = locateIpv6TcpOffset(buffer, length) ?: return null
        return parseTcpDnsAt(
            buffer = buffer,
            length = length,
            transportOffset = tcpOffset,
            networkHeaderLength = tcpOffset,
            sourceIp = readIpv6(buffer, 8),
            destIp = readIpv6(buffer, 24),
            isIpv6 = true,
            strict = strict
        )
    }

    private fun parseTcpDnsAt(
        buffer: ByteArray,
        length: Int,
        transportOffset: Int,
        networkHeaderLength: Int,
        sourceIp: String,
        destIp: String,
        isIpv6: Boolean,
        strict: Boolean
    ): ParsedDnsQuery? {
        val srcPort = readPort(buffer, transportOffset)
        val dstPort = readPort(buffer, transportOffset + 2)
        if (dstPort != DNS_PORT && srcPort != DNS_PORT) return null

        val clientPort = if (dstPort == DNS_PORT) srcPort else dstPort
        val tcpHeaderLength = ((buffer[transportOffset + 12].toInt() shr 4) and 0x0F) * 4
        if (tcpHeaderLength < 20) return null

        val payloadStart = transportOffset + tcpHeaderLength
        if (payloadStart + 2 > length) return null

        val available = length - payloadStart - 2
        val dnsLengthField = readUint16(buffer, payloadStart)
        val dnsLength = minOf(dnsLengthField, available)
        if (dnsLength < 12) return null

        val dnsOffset = payloadStart + 2
        val dnsPayload = buffer.copyOfRange(dnsOffset, dnsOffset + dnsLength)
        val domain = extractDomainFromDnsPayload(dnsPayload)
        if (strict && domain == null) return null

        return ParsedDnsQuery(
            rawPacket = buffer.copyOf(length),
            packetLength = length,
            ipHeaderLength = networkHeaderLength,
            udpOffset = transportOffset,
            clientPort = clientPort,
            sourceIp = sourceIp,
            destDnsIp = destIp,
            dnsPayloadOffset = dnsOffset,
            dnsPayloadLength = dnsLength,
            domain = domain,
            isIpv6 = isIpv6,
            isTcp = true,
            tcpSeq = readUint32(buffer, transportOffset + 4),
            tcpAck = readUint32(buffer, transportOffset + 8)
        )
    }

    private fun locateIpv6TcpOffset(buffer: ByteArray, length: Int): Int? {
        var nextHeader = buffer[6].toInt() and 0xFF
        var offset = IPV6_HEADER_LENGTH
        var hops = 0
        while (hops++ < 12) {
            when (nextHeader) {
                IP_PROTOCOL_TCP -> return offset
                IP_PROTOCOL_UDP -> return null
                44 -> {
                    if (offset + 8 > length) return null
                    nextHeader = buffer[offset].toInt() and 0xFF
                    offset += 8
                }
                0, 43, 60 -> {
                    if (offset + 8 > length) return null
                    val extLen = ((buffer[offset + 1].toInt() and 0xFF) + 1) * 8
                    if (offset + extLen > length) return null
                    nextHeader = buffer[offset].toInt() and 0xFF
                    offset += extLen
                }
                else -> return null
            }
        }
        return scanForTcpDns(buffer, length)
    }

    private fun scanForTcpDns(buffer: ByteArray, length: Int): Int? {
        for (offset in IPV6_HEADER_LENGTH until length - 24) {
            val srcPort = readPort(buffer, offset)
            val dstPort = readPort(buffer, offset + 2)
            if (dstPort != DNS_PORT && srcPort != DNS_PORT) continue
            val tcpHeaderLength = ((buffer[offset + 12].toInt() shr 4) and 0x0F) * 4
            if (tcpHeaderLength < 20) continue
            val dnsOffset = offset + tcpHeaderLength + 2
            if (dnsOffset + 12 > length) continue
            if (!isDnsQueryPacket(buffer, dnsOffset)) continue
            return offset
        }
        return null
    }

    /** Walk IPv6 extension headers (fragment, routing, etc.) to find the UDP header. */
    private fun locateIpv6UdpOffset(buffer: ByteArray, length: Int): Int? {
        locateIpv6UdpOffsetByExtensions(buffer, length)?.let { return it }
        return scanForUdpDns(buffer, length)
    }

    private fun locateIpv6UdpOffsetByExtensions(buffer: ByteArray, length: Int): Int? {
        var nextHeader = buffer[6].toInt() and 0xFF
        var offset = IPV6_HEADER_LENGTH
        var hops = 0
        while (hops++ < 12) {
            when (nextHeader) {
                IP_PROTOCOL_UDP -> return offset
                IP_PROTOCOL_TCP -> return null
                44 -> {
                    if (offset + 8 > length) return null
                    nextHeader = buffer[offset].toInt() and 0xFF
                    offset += 8
                }
                0, 43, 60 -> {
                    if (offset + 8 > length) return null
                    val extLen = ((buffer[offset + 1].toInt() and 0xFF) + 1) * 8
                    if (offset + extLen > length) return null
                    nextHeader = buffer[offset].toInt() and 0xFF
                    offset += extLen
                }
                51 -> {
                    if (offset + 8 > length) return null
                    val extLen = (buffer[offset + 1].toInt() and 0xFF) * 4 + 8
                    if (offset + extLen > length) return null
                    nextHeader = buffer[offset].toInt() and 0xFF
                    offset += extLen
                }
                else -> return null
            }
        }
        return null
    }

    /** Fallback for OEM ROMs with unusual IPv6 extension header chains (e.g. Oppo ColorOS). */
    private fun scanForUdpDns(buffer: ByteArray, length: Int): Int? {
        for (offset in IPV6_HEADER_LENGTH until length - 20) {
            val srcPort = readPort(buffer, offset)
            val dstPort = readPort(buffer, offset + 2)
            if (dstPort != DNS_PORT && srcPort != DNS_PORT) continue
            val dnsOffset = offset + 8
            if (dnsOffset + 12 > length) continue
            if (!isDnsQueryPacket(buffer, dnsOffset)) continue
            return offset
        }
        return null
    }

    private fun isDnsQueryPacket(buffer: ByteArray, dnsOffset: Int): Boolean {
        val flags = buffer[dnsOffset + 2].toInt() and 0xFF
        if (flags and 0x80 != 0) return false
        return readUint16(buffer, dnsOffset + 4) >= 1
    }

    private fun resolveDnsLength(
        buffer: ByteArray,
        packetLength: Int,
        udpOffset: Int,
        dnsOffset: Int
    ): Int {
        val available = (packetLength - dnsOffset).coerceAtLeast(0)
        val udpLengthField = readUint16(buffer, udpOffset + 4)
        return if (udpLengthField >= 8) {
            minOf(udpLengthField - 8, available)
        } else {
            available
        }
    }

    private fun readIpv4(buffer: ByteArray, offset: Int): String {
        return "${buffer[offset].toInt() and 0xFF}.${buffer[offset + 1].toInt() and 0xFF}." +
            "${buffer[offset + 2].toInt() and 0xFF}.${buffer[offset + 3].toInt() and 0xFF}"
    }

    private fun readIpv6(buffer: ByteArray, offset: Int): String {
        val addr = ByteArray(16)
        System.arraycopy(buffer, offset, addr, 0, 16)
        return InetAddress.getByAddress(addr).hostAddress ?: "::"
    }

    fun extractDomainFromDnsPayload(payload: ByteArray): String? {
        if (payload.size < 13) return null
        return readDnsName(payload, 12, payload.size)
    }

    private fun readDnsName(payload: ByteArray, start: Int, limit: Int, depth: Int = 0): String? {
        if (depth > 8 || start >= limit) return null
        val parts = mutableListOf<String>()
        var pos = start
        var jumped = false
        var jumpEnd = start

        while (pos < limit) {
            val len = payload[pos].toInt() and 0xFF
            if (len == 0) {
                if (!jumped) jumpEnd = pos + 1
                break
            }
            if (len and 0xC0 == 0xC0) {
                if (pos + 1 >= limit) return null
                val pointer = ((len and 0x3F) shl 8) or (payload[pos + 1].toInt() and 0xFF)
                if (!jumped) jumpEnd = pos + 2
                jumped = true
                pos = pointer
                continue
            }
            if (len > 63 || pos + 1 + len > limit) return null
            pos++
            parts.add(String(payload, pos, len, Charsets.US_ASCII))
            pos += len
        }
        return parts.takeIf { it.isNotEmpty() }?.joinToString(".")
    }

    fun describeUnparsedPacket(buffer: ByteArray, length: Int): String {
        if (length < 1) return "empty"
        val version = buffer[0].toInt() shr 4
        return if (version == 6 && length >= 7) {
            "ipVersion=6 nextHeader=${buffer[6].toInt() and 0xFF} payloadLen=${readUint16(buffer, 4)}"
        } else if (version == 4 && length >= 10) {
            "ipVersion=4 proto=${buffer[9].toInt() and 0xFF}"
        } else {
            "ipVersion=$version"
        }
    }

    fun forwardDnsQuery(
        query: ParsedDnsQuery,
        forwardPayload: (ByteArray) -> ByteArray?
    ): ByteArray? {
        val dnsPayload = query.rawPacket.copyOfRange(
            query.dnsPayloadOffset,
            query.dnsPayloadOffset + query.dnsPayloadLength
        )
        return forwardPayload(dnsPayload)
    }

    fun buildBlockedDnsResponse(query: ParsedDnsQuery): ByteArray {
        val dnsPayload = query.rawPacket.copyOfRange(
            query.dnsPayloadOffset,
            query.dnsPayloadOffset + query.dnsPayloadLength
        )
        val responsePayload = synthesizeSinkholeResponse(dnsPayload, SINKHOLE_IPV4)
        return buildResponsePacket(query, responsePayload)
    }

    /** Sinkhole all lookups to [portalIpv4] (hotspot gateway) for a limit-reached hotspot client. */
    fun buildHotspotLimitSinkholeResponse(
        query: ParsedDnsQuery,
        portalIpv4: String = SINKHOLE_IPV4
    ): ByteArray {
        val dnsPayload = query.rawPacket.copyOfRange(
            query.dnsPayloadOffset,
            query.dnsPayloadOffset + query.dnsPayloadLength
        )
        val responsePayload = synthesizeSinkholeResponse(dnsPayload, portalIpv4)
        return buildResponsePacket(query, responsePayload)
    }

    /** Raw UDP DNS response for standalone hotspot DNS server (no IP packet wrapper). */
    fun buildRawCaptivePortalResponse(queryPayload: ByteArray, portalIpv4: String): ByteArray =
        synthesizeSinkholeResponse(queryPayload, portalIpv4)

    fun buildServFailResponse(query: ParsedDnsQuery): ByteArray {
        val dnsPayload = query.rawPacket.copyOfRange(
            query.dnsPayloadOffset,
            query.dnsPayloadOffset + query.dnsPayloadLength
        )
        val responsePayload = synthesizeServFail(dnsPayload)
        return buildResponsePacket(query, responsePayload)
    }

    fun buildResponsePacket(query: ParsedDnsQuery, dnsResponsePayload: ByteArray): ByteArray {
        return when {
            query.isTcp && query.isIpv6 -> buildIpv6TcpResponsePacket(query, dnsResponsePayload)
            query.isTcp -> buildIpv4TcpResponsePacket(query, dnsResponsePayload)
            query.isIpv6 -> buildIpv6ResponsePacket(query, dnsResponsePayload)
            else -> buildIpv4ResponsePacket(query, dnsResponsePayload)
        }
    }

    private fun buildIpv4ResponsePacket(query: ParsedDnsQuery, dnsResponsePayload: ByteArray): ByteArray {
        val udpLength = 8 + dnsResponsePayload.size
        val totalLength = query.udpOffset + udpLength
        val packet = ByteArray(totalLength)

        System.arraycopy(query.rawPacket, 0, packet, 0, query.udpOffset)
        System.arraycopy(query.rawPacket, 16, packet, 12, 4)
        System.arraycopy(query.rawPacket, 12, packet, 16, 4)

        writeUint16(packet, 2, totalLength)
        packet[8] = 64
        packet[10] = 0
        packet[11] = 0
        writeIpChecksum(packet, query.ipHeaderLength)

        val udpOffset = query.udpOffset
        writePort(packet, udpOffset, DNS_PORT)
        writePort(packet, udpOffset + 2, query.clientPort)
        writeUint16(packet, udpOffset + 4, udpLength)
        packet[udpOffset + 6] = 0
        packet[udpOffset + 7] = 0

        System.arraycopy(dnsResponsePayload, 0, packet, udpOffset + 8, dnsResponsePayload.size)
        writeUdpChecksum(packet, query.ipHeaderLength, udpLength)
        return packet
    }

    private fun buildIpv4TcpResponsePacket(query: ParsedDnsQuery, dnsResponsePayload: ByteArray): ByteArray {
        val tcpPayloadLength = 2 + dnsResponsePayload.size
        val tcpHeaderLength = 20
        val totalLength = query.udpOffset + tcpHeaderLength + tcpPayloadLength
        val packet = ByteArray(totalLength)

        System.arraycopy(query.rawPacket, 0, packet, 0, query.udpOffset)
        System.arraycopy(query.rawPacket, 16, packet, 12, 4)
        System.arraycopy(query.rawPacket, 12, packet, 16, 4)

        writeUint16(packet, 2, totalLength)
        packet[8] = 64
        packet[10] = 0
        packet[11] = 0
        writeIpChecksum(packet, query.ipHeaderLength)

        val tcpOffset = query.udpOffset
        writePort(packet, tcpOffset, DNS_PORT)
        writePort(packet, tcpOffset + 2, query.clientPort)
        writeUint32(packet, tcpOffset + 4, query.tcpAck)
        writeUint32(packet, tcpOffset + 8, query.tcpSeq + query.dnsPayloadLength + 2)
        packet[tcpOffset + 12] = 0x50
        packet[tcpOffset + 13] = 0x18
        writeUint16(packet, tcpOffset + 14, 0)
        writeUint16(packet, tcpOffset + 16, 0)
        writeUint16(packet, tcpOffset + 18, 0)

        val payloadStart = tcpOffset + tcpHeaderLength
        writeUint16(packet, payloadStart, dnsResponsePayload.size)
        System.arraycopy(dnsResponsePayload, 0, packet, payloadStart + 2, dnsResponsePayload.size)
        writeTcpChecksum(packet, query.ipHeaderLength, tcpHeaderLength + tcpPayloadLength)
        return packet
    }

    private fun buildIpv6TcpResponsePacket(query: ParsedDnsQuery, dnsResponsePayload: ByteArray): ByteArray {
        val tcpPayloadLength = 2 + dnsResponsePayload.size
        val tcpHeaderLength = 20
        val totalLength = query.udpOffset + tcpHeaderLength + tcpPayloadLength
        val packet = ByteArray(totalLength)

        System.arraycopy(query.rawPacket, 0, packet, 0, query.udpOffset)
        System.arraycopy(query.rawPacket, 24, packet, 8, 16)
        System.arraycopy(query.rawPacket, 8, packet, 24, 16)

        writeUint16(packet, 4, totalLength - IPV6_HEADER_LENGTH)
        packet[7] = 64

        val tcpOffset = query.udpOffset
        writePort(packet, tcpOffset, DNS_PORT)
        writePort(packet, tcpOffset + 2, query.clientPort)
        writeUint32(packet, tcpOffset + 4, query.tcpAck)
        writeUint32(packet, tcpOffset + 8, query.tcpSeq + query.dnsPayloadLength + 2)
        packet[tcpOffset + 12] = 0x50
        packet[tcpOffset + 13] = 0x18
        writeUint16(packet, tcpOffset + 14, 0)
        writeUint16(packet, tcpOffset + 16, 0)
        writeUint16(packet, tcpOffset + 18, 0)

        val payloadStart = tcpOffset + tcpHeaderLength
        writeUint16(packet, payloadStart, dnsResponsePayload.size)
        System.arraycopy(dnsResponsePayload, 0, packet, payloadStart + 2, dnsResponsePayload.size)
        writeIpv6TcpChecksum(packet, tcpOffset, tcpHeaderLength + tcpPayloadLength)
        return packet
    }

    private fun buildIpv6ResponsePacket(query: ParsedDnsQuery, dnsResponsePayload: ByteArray): ByteArray {
        val udpLength = 8 + dnsResponsePayload.size
        val totalLength = query.udpOffset + udpLength
        val packet = ByteArray(totalLength)

        System.arraycopy(query.rawPacket, 0, packet, 0, query.udpOffset)
        System.arraycopy(query.rawPacket, 24, packet, 8, 16)
        System.arraycopy(query.rawPacket, 8, packet, 24, 16)

        writeUint16(packet, 4, totalLength - IPV6_HEADER_LENGTH)
        packet[7] = 64

        val udpOffset = query.udpOffset
        writePort(packet, udpOffset, DNS_PORT)
        writePort(packet, udpOffset + 2, query.clientPort)
        writeUint16(packet, udpOffset + 4, udpLength)
        packet[udpOffset + 6] = 0
        packet[udpOffset + 7] = 0

        System.arraycopy(dnsResponsePayload, 0, packet, udpOffset + 8, dnsResponsePayload.size)
        writeIpv6UdpChecksum(packet, udpOffset, udpLength)
        return packet
    }

    private fun synthesizeSinkholeResponse(
        queryPayload: ByteArray,
        sinkholeIpv4: String = SINKHOLE_IPV4
    ): ByteArray {
        if (queryPayload.size < 12) return synthesizeServFail(queryPayload)

        val qtype = readQuestionType(queryPayload)
        var pos = 12
        while (pos < queryPayload.size) {
            val labelLength = queryPayload[pos].toInt() and 0xFF
            if (labelLength == 0) {
                pos++
                break
            }
            pos += 1 + labelLength
        }
        val questionEnd = pos + 4
        if (questionEnd > queryPayload.size) return synthesizeNxDomain(queryPayload)

        return when (qtype) {
            1 -> buildSinkholeARecordBody(queryPayload, questionEnd, sinkholeIpv4)
            28 -> buildSinkholeAAAARecord(queryPayload, questionEnd)
            else -> synthesizeNxDomain(queryPayload)
        }
    }

    fun readQuestionType(query: ParsedDnsQuery): Int {
        val payload = query.rawPacket.copyOfRange(
            query.dnsPayloadOffset,
            query.dnsPayloadOffset + query.dnsPayloadLength
        )
        return readQuestionType(payload)
    }

    private fun readQuestionType(payload: ByteArray): Int {
        var pos = 12
        while (pos < payload.size) {
            val labelLength = payload[pos].toInt() and 0xFF
            if (labelLength == 0) {
                pos++
                break
            }
            pos += 1 + labelLength
        }
        if (pos + 2 > payload.size) return 1
        return readUint16(payload, pos)
    }

    private fun buildSinkholeARecordBody(
        queryPayload: ByteArray,
        questionEnd: Int,
        sinkholeIpv4: String = SINKHOLE_IPV4
    ): ByteArray {
        val answer = ByteArray(16)
        answer[0] = 0xC0.toByte()
        answer[1] = 0x0C
        writeUint16(answer, 2, 1)
        writeUint16(answer, 4, 1)
        writeUint16(answer, 6, 0)
        writeUint16(answer, 8, 300)
        writeUint16(answer, 10, 4)
        writeIpv4(answer, 12, sinkholeIpv4)

        val response = ByteArray(questionEnd + answer.size)
        System.arraycopy(queryPayload, 0, response, 0, questionEnd)
        response[2] = 0x81.toByte()
        response[3] = 0x80.toByte()
        response[6] = 0x00
        response[7] = 0x01
        System.arraycopy(answer, 0, response, questionEnd, answer.size)
        return response
    }

    private fun buildSinkholeAAAARecord(queryPayload: ByteArray, questionEnd: Int): ByteArray {
        val answer = ByteArray(28)
        answer[0] = 0xC0.toByte()
        answer[1] = 0x0C
        writeUint16(answer, 2, 28)
        writeUint16(answer, 4, 1)
        writeUint16(answer, 6, 0)
        writeUint16(answer, 8, 300)
        writeUint16(answer, 10, 16)

        val response = ByteArray(questionEnd + answer.size)
        System.arraycopy(queryPayload, 0, response, 0, questionEnd)
        response[2] = 0x81.toByte()
        response[3] = 0x80.toByte()
        response[6] = 0x00
        response[7] = 0x01
        System.arraycopy(answer, 0, response, questionEnd, answer.size)
        return response
    }

    private fun synthesizeNxDomain(queryPayload: ByteArray): ByteArray {
        val response = queryPayload.copyOf()
        response[2] = ((response[2].toInt() and 0xFF) or 0x80).toByte()
        response[3] = ((response[3].toInt() and 0xF0) or 0x03).toByte()
        response[6] = 0
        response[7] = 0
        return response
    }

    private fun synthesizeServFail(queryPayload: ByteArray): ByteArray {
        val response = queryPayload.copyOf()
        response[2] = ((response[2].toInt() and 0xFF) or 0x80).toByte()
        response[3] = ((response[3].toInt() and 0xF0) or 0x02).toByte()
        response[6] = 0
        response[7] = 0
        return response
    }

    private fun readPort(buffer: ByteArray, offset: Int): Int {
        return ((buffer[offset].toInt() and 0xFF) shl 8) or (buffer[offset + 1].toInt() and 0xFF)
    }

    private fun writePort(buffer: ByteArray, offset: Int, port: Int) {
        buffer[offset] = (port shr 8).toByte()
        buffer[offset + 1] = (port and 0xFF).toByte()
    }

    private fun readUint16(buffer: ByteArray, offset: Int): Int {
        return ((buffer[offset].toInt() and 0xFF) shl 8) or (buffer[offset + 1].toInt() and 0xFF)
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

    private fun writeIpv4(buffer: ByteArray, offset: Int, ip: String) {
        val parts = ip.split('.')
        if (parts.size != 4) return
        for (i in 0 until 4) {
            buffer[offset + i] = (parts[i].toIntOrNull() ?: 0).toByte()
        }
    }

    private fun writeIpChecksum(packet: ByteArray, headerLength: Int) {
        packet[10] = 0
        packet[11] = 0
        writeUint16(packet, 10, ipChecksum(packet, 0, headerLength))
    }

    private fun writeUdpChecksum(packet: ByteArray, ipHeaderLength: Int, udpLength: Int) {
        val udpOffset = ipHeaderLength
        packet[udpOffset + 6] = 0
        packet[udpOffset + 7] = 0
        val checksum = udpChecksum(packet, ipHeaderLength, udpLength)
        if (checksum != 0) {
            writeUint16(packet, udpOffset + 6, checksum)
        }
    }

    private fun writeIpv6UdpChecksum(packet: ByteArray, udpOffset: Int, udpLength: Int) {
        packet[udpOffset + 6] = 0
        packet[udpOffset + 7] = 0
        val checksum = ipv6UdpChecksum(packet, udpOffset, udpLength)
        writeUint16(packet, udpOffset + 6, if (checksum == 0) 0xFFFF else checksum)
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

    private fun udpChecksum(packet: ByteArray, ipHeaderLength: Int, udpLength: Int): Int {
        var sum = 0
        for (i in 12 until 20 step 2) {
            sum += ((packet[i].toInt() and 0xFF) shl 8) or (packet[i + 1].toInt() and 0xFF)
        }
        sum += IP_PROTOCOL_UDP
        sum += udpLength

        val udpOffset = ipHeaderLength
        var i = udpOffset
        while (i < udpOffset + udpLength - 1) {
            sum += ((packet[i].toInt() and 0xFF) shl 8) or (packet[i + 1].toInt() and 0xFF)
            i += 2
        }
        if (udpLength and 1 != 0) {
            sum += (packet[udpOffset + udpLength - 1].toInt() and 0xFF) shl 8
        }
        while (sum shr 16 != 0) {
            sum = (sum and 0xFFFF) + (sum shr 16)
        }
        return sum.inv() and 0xFFFF
    }

    private fun ipv6UdpChecksum(packet: ByteArray, udpOffset: Int, udpLength: Int): Int {
        var sum = 0
        for (i in 8 until 40 step 2) {
            sum += ((packet[i].toInt() and 0xFF) shl 8) or (packet[i + 1].toInt() and 0xFF)
        }
        sum += IP_PROTOCOL_UDP
        sum += udpLength

        var i = udpOffset
        while (i < udpOffset + udpLength - 1) {
            sum += ((packet[i].toInt() and 0xFF) shl 8) or (packet[i + 1].toInt() and 0xFF)
            i += 2
        }
        if (udpLength and 1 != 0) {
            sum += (packet[udpOffset + udpLength - 1].toInt() and 0xFF) shl 8
        }
        while (sum shr 16 != 0) {
            sum = (sum and 0xFFFF) + (sum shr 16)
        }
        return sum.inv() and 0xFFFF
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

    private fun writeIpv6TcpChecksum(packet: ByteArray, tcpOffset: Int, tcpLength: Int) {
        packet[tcpOffset + 16] = 0
        packet[tcpOffset + 17] = 0
        val checksum = ipv6TcpChecksum(packet, tcpOffset, tcpLength)
        writeUint16(packet, tcpOffset + 16, if (checksum == 0) 0xFFFF else checksum)
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

    private fun ipv6TcpChecksum(packet: ByteArray, tcpOffset: Int, tcpLength: Int): Int {
        var sum = 0
        for (i in 8 until 40 step 2) {
            sum += ((packet[i].toInt() and 0xFF) shl 8) or (packet[i + 1].toInt() and 0xFF)
        }
        sum += IP_PROTOCOL_TCP
        sum += tcpLength

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
