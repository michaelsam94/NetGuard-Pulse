package com.michael.netguardplus.system.vpn.proxy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

class ProxyPacketTest {

    @Test
    fun testBuildAndParseTcpPacket() {
        val srcIp = "192.168.43.10"
        val destIp = "8.8.8.8"
        val srcPort = 55432
        val destPort = 80
        val seq = 123456L
        val ack = 654321L
        val flags = 0x12 // SYN-ACK
        val payload = "Hello World".toByteArray()

        val packetBytes = ProxyPacket.buildTcpPacket(
            srcIp = srcIp,
            destIp = destIp,
            srcPort = srcPort,
            destPort = destPort,
            seq = seq,
            ack = ack,
            flags = flags,
            payload = payload
        )

        val buffer = ByteBuffer.wrap(packetBytes)
        val packet = ProxyPacket(buffer)

        assertTrue(packet.isTcp)
        assertEquals(srcIp, packet.sourceIp)
        assertEquals(destIp, packet.destIp)
        assertEquals(srcPort, packet.sourcePort)
        assertEquals(destPort, packet.destPort)
        assertEquals(seq, packet.tcpSeq)
        assertEquals(ack, packet.tcpAck)
        assertEquals(flags, packet.tcpFlags)
        assertEquals(payload.size, packet.tcpPayloadLength)

        val parsedPayload = ByteArray(packet.tcpPayloadLength)
        packet.buffer.position(packet.tcpPayloadOffset)
        packet.buffer.get(parsedPayload)
        assertEquals("Hello World", String(parsedPayload))
    }

    @Test
    fun testBuildAndParseUdpPacket() {
        val srcIp = "192.168.43.10"
        val destIp = "8.8.4.4"
        val srcPort = 12345
        val destPort = 53
        val payload = "DNS Query Details".toByteArray()

        val packetBytes = ProxyPacket.buildUdpPacket(
            srcIp = srcIp,
            destIp = destIp,
            srcPort = srcPort,
            destPort = destPort,
            payload = payload
        )

        val buffer = ByteBuffer.wrap(packetBytes)
        val packet = ProxyPacket(buffer)

        assertTrue(packet.isUdp)
        assertEquals(srcIp, packet.sourceIp)
        assertEquals(destIp, packet.destIp)
        assertEquals(srcPort, packet.sourcePort)
        assertEquals(destPort, packet.destPort)
        assertEquals(payload.size, packet.udpPayloadLength)

        val parsedPayload = ByteArray(packet.udpPayloadLength)
        packet.buffer.position(packet.udpPayloadOffset)
        packet.buffer.get(parsedPayload)
        assertEquals("DNS Query Details", String(parsedPayload))
    }
}
