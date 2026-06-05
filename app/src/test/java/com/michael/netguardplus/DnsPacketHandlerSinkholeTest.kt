package com.michael.netguardplus

import com.michael.netguardplus.system.vpn.DnsPacketHandler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DnsPacketHandlerSinkholeTest {

    @Test
    fun buildHotspotLimitSinkholeResponse_returnsARecordForZeroIpv4ByDefault() {
        val queryPacket = buildMinimalDnsQueryPacket(
            sourceIp = "192.168.43.10",
            destIp = "192.168.43.1",
            domain = "example.com"
        )
        val query = DnsPacketHandler.parseDnsQuery(queryPacket, queryPacket.size)
        assertNotNull(query)

        val response = DnsPacketHandler.buildHotspotLimitSinkholeResponse(query!!)
        assertSinkholeIpv4(response, query, "0.0.0.0")
    }

    @Test
    fun buildHotspotLimitSinkholeResponse_returnsCaptivePortalGatewayIp() {
        val queryPacket = buildMinimalDnsQueryPacket(
            sourceIp = "192.168.43.10",
            destIp = "192.168.43.1",
            domain = "example.com"
        )
        val query = DnsPacketHandler.parseDnsQuery(queryPacket, queryPacket.size)
        assertNotNull(query)

        val response = DnsPacketHandler.buildHotspotLimitSinkholeResponse(query!!, "192.168.43.1")
        assertSinkholeIpv4(response, query, "192.168.43.1")
    }

    @Test
    fun buildRawCaptivePortalResponse_returnsGatewayARecord() {
        val queryPacket = buildMinimalDnsQueryPacket(
            sourceIp = "192.168.43.10",
            destIp = "192.168.43.1",
            domain = "example.com"
        )
        val query = DnsPacketHandler.parseDnsQuery(queryPacket, queryPacket.size)
        assertNotNull(query)

        val dnsPayload = query!!.rawPacket.copyOfRange(
            query.dnsPayloadOffset,
            query.dnsPayloadOffset + query.dnsPayloadLength
        )
        val response = DnsPacketHandler.buildRawCaptivePortalResponse(dnsPayload, "192.168.43.1")

        assertTrue(response.size >= 16)
        assertEquals(0x81.toByte(), response[2])
        assertEquals(0x80.toByte(), response[3])
        assertEquals(1, response[7].toInt() and 0xFF)
        assertEquals(192, response[response.size - 4].toInt() and 0xFF)
        assertEquals(168, response[response.size - 3].toInt() and 0xFF)
        assertEquals(43, response[response.size - 2].toInt() and 0xFF)
        assertEquals(1, response[response.size - 1].toInt() and 0xFF)
    }

    private fun assertSinkholeIpv4(
        response: ByteArray,
        query: DnsPacketHandler.ParsedDnsQuery,
        expectedIp: String
    ) {
        val dnsPayload = response.copyOfRange(query.dnsPayloadOffset, response.size)

        assertTrue(dnsPayload.size >= 16)
        assertEquals(0x81.toByte(), dnsPayload[2])
        assertEquals(0x80.toByte(), dnsPayload[3])
        assertEquals(1, dnsPayload[7].toInt() and 0xFF)

        val parts = expectedIp.split('.').map { it.toInt() }
        val answerStart = dnsPayload.size - 4
        assertEquals(parts[0], dnsPayload[answerStart].toInt() and 0xFF)
        assertEquals(parts[1], dnsPayload[answerStart + 1].toInt() and 0xFF)
        assertEquals(parts[2], dnsPayload[answerStart + 2].toInt() and 0xFF)
        assertEquals(parts[3], dnsPayload[answerStart + 3].toInt() and 0xFF)
    }

    private fun buildMinimalDnsQueryPacket(
        sourceIp: String,
        destIp: String,
        domain: String
    ): ByteArray {
        val labels = domain.split('.')
        val qname = ByteArray(labels.sumOf { it.length + 1 } + 1)
        var pos = 0
        for (label in labels) {
            qname[pos++] = label.length.toByte()
            label.toByteArray().copyInto(qname, pos)
            pos += label.length
        }
        qname[pos] = 0

        val dnsPayload = ByteArray(12 + qname.size + 4)
        dnsPayload[0] = 0x12
        dnsPayload[1] = 0x34
        dnsPayload[2] = 0x01
        dnsPayload[5] = 0x01
        System.arraycopy(qname, 0, dnsPayload, 12, qname.size)
        val typeOffset = 12 + qname.size
        dnsPayload[typeOffset] = 0x00
        dnsPayload[typeOffset + 1] = 0x01
        dnsPayload[typeOffset + 2] = 0x00
        dnsPayload[typeOffset + 3] = 0x01

        val src = parseIpv4(sourceIp)
        val dst = parseIpv4(destIp)
        val udpLength = 8 + dnsPayload.size
        val totalLength = 20 + udpLength
        val packet = ByteArray(totalLength)
        packet[0] = 0x45
        packet[2] = ((totalLength shr 8) and 0xFF).toByte()
        packet[3] = (totalLength and 0xFF).toByte()
        packet[8] = 64
        packet[9] = 17
        src.copyInto(packet, 12)
        dst.copyInto(packet, 16)
        writePort(packet, 20, 54321)
        writePort(packet, 22, 53)
        packet[24] = ((udpLength shr 8) and 0xFF).toByte()
        packet[25] = (udpLength and 0xFF).toByte()
        dnsPayload.copyInto(packet, 28)
        return packet
    }

    private fun parseIpv4(ip: String): ByteArray =
        ip.split('.').map { it.toInt().toByte() }.toByteArray()

    private fun writePort(buffer: ByteArray, offset: Int, port: Int) {
        buffer[offset] = (port shr 8).toByte()
        buffer[offset + 1] = (port and 0xFF).toByte()
    }
}
