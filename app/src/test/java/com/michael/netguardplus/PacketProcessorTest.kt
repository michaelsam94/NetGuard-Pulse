package com.michael.netguardplus

import com.michael.netguardplus.system.hotspot.limit.PacketProcessor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PacketProcessorTest {

    @Test
    fun extractsCorrectSourceIpFromValidIpv4Packet() {
        val packet = ByteArray(40)
        packet[0] = 0x45
        packet[12] = 192.toByte()
        packet[13] = 168.toByte()
        packet[14] = 43.toByte()
        packet[15] = 100.toByte()
        assertEquals("192.168.43.100", PacketProcessor.extractSourceIp(packet))
    }

    @Test
    fun extractsDestIpAndProtocol() {
        val packet = ByteArray(40)
        packet[0] = 0x45
        packet[9] = 17
        packet[12] = 10.toByte()
        packet[13] = 0.toByte()
        packet[14] = 0.toByte()
        packet[15] = 2.toByte()
        packet[16] = 8.toByte()
        packet[17] = 8.toByte()
        packet[18] = 8.toByte()
        packet[19] = 8.toByte()
        assertEquals("10.0.0.2", PacketProcessor.extractSourceIp(packet))
        assertEquals("8.8.8.8", PacketProcessor.extractDestIp(packet))
        assertEquals(PacketProcessor.PROTO_UDP, PacketProcessor.extractProtocol(packet))
    }

    @Test
    fun returnsEmptyForIpv6Version() {
        val packet = ByteArray(40)
        packet[0] = 0x60
        assertEquals("", PacketProcessor.extractSourceIp(packet))
        assertNull(PacketProcessor.parse(packet))
    }
}
