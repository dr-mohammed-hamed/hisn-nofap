package com.drmohammed.antiporn.core.network.dns

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.ByteBuffer

class TcpRstPacketBuilderTest {

    @Test
    fun testBuildTcpRstResponseForSyn() {
        val srcIp = byteArrayOf(10, 0, 0, 1)
        val dstIp = byteArrayOf(10, (-1).toByte(), (-1).toByte(), 2) // 10.255.255.2
        val synPacket = buildSyntheticTcpPacket(
            srcIp = srcIp,
            dstIp = dstIp,
            srcPort = 54321,
            dstPort = 853, // DoT port
            seq = 1000L,
            ack = 0L,
            flags = 0x02 // SYN
        )

        val rstPacket = requireNotNull(DnsPacketParser.buildTcpRstResponse(synPacket, synPacket.size)) {
            "RST packet must be generated for SYN"
        }
        assertEquals(40, rstPacket.size) // 20 IP + 20 TCP

        val buf = ByteBuffer.wrap(rstPacket)

        // Verify IPv4 Header
        val versionAndIhl = buf.get(0).toInt() and 0xFF
        assertEquals(4, versionAndIhl ushr 4)
        assertEquals(5, versionAndIhl and 0x0F)
        assertEquals(40, buf.getShort(2).toInt() and 0xFFFF)
        assertEquals(6, buf.get(9).toInt() and 0xFF) // Protocol TCP

        val respSrcIp = ByteArray(4)
        val respDstIp = ByteArray(4)
        buf.position(12)
        buf.get(respSrcIp)
        buf.get(respDstIp)
        assertArrayEquals("Source and Destination IPs must be swapped", dstIp, respSrcIp)
        assertArrayEquals("Source and Destination IPs must be swapped", srcIp, respDstIp)

        // Verify TCP Header
        buf.position(20)
        assertEquals("Source port must be 853 (swapped)", 853, buf.short.toInt() and 0xFFFF)
        assertEquals("Destination port must be 54321 (swapped)", 54321, buf.short.toInt() and 0xFFFF)
        assertEquals("Sequence number for SYN response must be 0", 0, buf.int)
        assertEquals("Ack number must be clientSeq + 1", 1001, buf.int)

        val dataOffset = (buf.get().toInt() and 0xFF) ushr 4
        assertEquals("TCP data offset must be 5 (20 bytes)", 5, dataOffset)

        val flags = buf.get().toInt() and 0xFF
        assertEquals("TCP flags must have RST (0x04) and ACK (0x10) set", 0x14, flags)

        assertEquals("TCP window size must be 0", 0, buf.short.toInt() and 0xFFFF)

        val storedTcpChecksum = buf.short
        assertNotEquals("TCP checksum must be non-zero", 0.toShort(), storedTcpChecksum)

        // Independent RFC 1071 ones'-complement verification over pseudo-header + TCP segment (must fold to 0xFFFF)
        assertEquals(
            "RFC 1071 IPv4 TCP checksum verification must fold to 0xFFFF",
            0xFFFF,
            verifyIpv4TransportChecksumRfc1071(
                srcIp = respSrcIp,
                dstIp = respDstIp,
                protocol = 6,
                segment = rstPacket,
                offset = 20,
                length = 20
            )
        )
    }

    @Test
    fun testBuildTcpRstResponseForAck() {
        val srcIp = byteArrayOf(10, 0, 0, 5)
        val dstIp = byteArrayOf(10, (-1).toByte(), (-1).toByte(), 2)
        val ackPacket = buildSyntheticTcpPacket(
            srcIp = srcIp,
            dstIp = dstIp,
            srcPort = 60000,
            dstPort = 53, // DNS over TCP
            seq = 5000L,
            ack = 1200L,
            flags = 0x10 // ACK
        )

        val rstPacket = requireNotNull(DnsPacketParser.buildTcpRstResponse(ackPacket, ackPacket.size))

        val buf = ByteBuffer.wrap(rstPacket)
        buf.position(24)
        assertEquals("Sequence number must equal client's ack", 1200, buf.int)
        assertEquals("Ack number must be 0 for RFC 9293 ACK-only RST", 0, buf.int)
        buf.position(33)
        assertEquals("TCP flags for incoming ACK=1 must be RST-only (0x04)", 0x04, buf.get().toInt() and 0xFF)
    }

    @Test
    fun testBuildIpv6TcpRstResponseForSyn() {
        val srcIp = ByteArray(16).apply { this[0] = 0xfd.toByte(); this[15] = 0x02 }
        val dstIp = ByteArray(16).apply { this[0] = 0x26; this[1] = 0x06; this[15] = 0x11 }
        val packet = ByteArray(60)
        val buf = ByteBuffer.wrap(packet)
        buf.putInt(0x60000000)
        buf.putShort(20.toShort())
        buf.put(6.toByte()) // TCP
        buf.put(64.toByte())
        buf.put(srcIp)
        buf.put(dstIp)
        buf.position(40)
        buf.putShort(51234.toShort())
        buf.putShort(853.toShort())
        buf.putInt(7777)
        buf.putInt(0)
        buf.put(0x50.toByte())
        buf.put(0x02.toByte()) // SYN
        buf.putShort(65535.toShort())
        buf.putShort(0.toShort())
        buf.putShort(0.toShort())

        val rst = requireNotNull(DnsPacketParser.buildIpv6TcpRstResponse(packet, packet.size))
        assertEquals(60, rst.size)
        val rBuf = ByteBuffer.wrap(rst)
        assertEquals(6, (rBuf.get(0).toInt() and 0xFF) ushr 4)
        assertEquals(6, rBuf.get(6).toInt() and 0xFF)
        val respSrcIp = ByteArray(16)
        val respDstIp = ByteArray(16)
        rBuf.position(8)
        rBuf.get(respSrcIp)
        rBuf.get(respDstIp)
        assertArrayEquals("IPv6 src/dst must be swapped", dstIp, respSrcIp)
        assertArrayEquals("IPv6 src/dst must be swapped", srcIp, respDstIp)
        rBuf.position(40)
        assertEquals(853, rBuf.short.toInt() and 0xFFFF)
        assertEquals(51234, rBuf.short.toInt() and 0xFFFF)
        assertEquals(0, rBuf.int)
        assertEquals(7778, rBuf.int)
        assertEquals(5, (rBuf.get().toInt() and 0xFF) ushr 4)
        assertEquals(0x14, rBuf.get().toInt() and 0xFF)
        assertEquals(
            "RFC 1071 IPv6 TCP checksum verification must fold to 0xFFFF",
            0xFFFF,
            verifyIpv6TransportChecksumRfc1071(
                srcIp = respSrcIp,
                dstIp = respDstIp,
                nextHeader = 6,
                segment = rst,
                offset = 40,
                length = 20
            )
        )

        // Incoming IPv6 ACK=1 must yield RST-only (0x04) with SEQ=clientAck and ACK=0
        val ackPacket = packet.copyOf()
        val ackBuf = ByteBuffer.wrap(ackPacket)
        ackBuf.position(44)
        ackBuf.putInt(9000) // seq
        ackBuf.putInt(4321) // ack
        ackBuf.put(0x50.toByte())
        ackBuf.put(0x10.toByte()) // ACK=1
        val ackRst = requireNotNull(DnsPacketParser.buildIpv6TcpRstResponse(ackPacket, ackPacket.size))
        val ackRstBuf = ByteBuffer.wrap(ackRst)
        ackRstBuf.position(44)
        assertEquals(4321, ackRstBuf.int)
        assertEquals(0, ackRstBuf.int)
        ackRstBuf.position(53)
        assertEquals(0x04, ackRstBuf.get().toInt() and 0xFF)
        assertEquals(
            0xFFFF,
            verifyIpv6TransportChecksumRfc1071(dstIp, srcIp, 6, ackRst, 40, 20)
        )

        // Incoming IPv6 RST must be rejected to prevent RST storm
        val rstIn = packet.copyOf().apply { this[53] = 0x04.toByte() }
        assertNull(DnsPacketParser.buildIpv6TcpRstResponse(rstIn, rstIn.size))
    }

    @Test
    fun testBuildIpv6RefusedResponseAndChecksum() {
        val srcIp = ByteArray(16).apply { this[0] = 0xfd.toByte(); this[15] = 0x01 }
        val dstIp = ByteArray(16).apply { this[0] = 0xfd.toByte(); this[15] = 0x02 }
        val qName = byteArrayOf(
            7, 'e'.code.toByte(), 'x'.code.toByte(), 'a'.code.toByte(), 'm'.code.toByte(),
            'p'.code.toByte(), 'l'.code.toByte(), 'e'.code.toByte(),
            3, 'c'.code.toByte(), 'o'.code.toByte(), 'm'.code.toByte(), 0
        )
        val dnsLen = 12 + qName.size + 4
        val udpLen = 8 + dnsLen
        val packet = ByteArray(40 + udpLen)
        val buf = ByteBuffer.wrap(packet)
        buf.putInt(0x60000000)
        buf.putShort(udpLen.toShort())
        buf.put(17.toByte()) // UDP
        buf.put(64.toByte())
        buf.put(srcIp)
        buf.put(dstIp)
        buf.position(40)
        buf.putShort(55123.toShort())
        buf.putShort(53.toShort())
        buf.putShort(udpLen.toShort())
        buf.putShort(0.toShort())
        buf.putShort(0x2A2B.toShort()) // txId
        buf.putShort(0x0100.toShort()) // standard query
        buf.putShort(1.toShort())
        buf.putShort(0.toShort())
        buf.putShort(0.toShort())
        buf.putShort(0.toShort())
        buf.put(qName)
        buf.putShort(28.toShort()) // AAAA
        buf.putShort(1.toShort())  // IN

        val refused = requireNotNull(DnsPacketParser.buildIpv6RefusedResponse(packet, packet.size))
        val rBuf = ByteBuffer.wrap(refused)
        assertEquals(6, (rBuf.get(0).toInt() and 0xFF) ushr 4)
        assertEquals(17, rBuf.get(6).toInt() and 0xFF)
        val respSrc = ByteArray(16)
        val respDst = ByteArray(16)
        rBuf.position(8)
        rBuf.get(respSrc)
        rBuf.get(respDst)
        assertArrayEquals(dstIp, respSrc)
        assertArrayEquals(srcIp, respDst)
        rBuf.position(40)
        assertEquals(53, rBuf.short.toInt() and 0xFFFF)
        assertEquals(55123, rBuf.short.toInt() and 0xFFFF)
        val respUdpLen = rBuf.short.toInt() and 0xFFFF
        assertEquals(udpLen, respUdpLen)
        val respChecksum = rBuf.short.toInt() and 0xFFFF
        assertNotEquals(0, respChecksum)
        assertEquals(0x2A2B.toShort(), rBuf.short)
        assertEquals(0x8185.toShort(), rBuf.short) // QR=1, RD=1, RA=1, RCODE=5 (REFUSED)
        assertEquals(
            0xFFFF,
            verifyIpv6TransportChecksumRfc1071(respSrc, respDst, 17, refused, 40, respUdpLen)
        )

        // Length > packet.size must be safely rejected
        assertNull(DnsPacketParser.buildIpv6RefusedResponse(packet, packet.size + 1))
    }

    @Test
    fun testBuildTcpRstResponseRejectsIncomingRst() {
        val rstInput = buildSyntheticTcpPacket(
            srcIp = byteArrayOf(10, 0, 0, 1),
            dstIp = byteArrayOf(10, (-1).toByte(), (-1).toByte(), 2),
            srcPort = 54321,
            dstPort = 853,
            seq = 1000L,
            ack = 0L,
            flags = 0x04 // Incoming RST
        )

        val response = DnsPacketParser.buildTcpRstResponse(rstInput, rstInput.size)
        assertNull("Must not reply with RST to an incoming RST (storm prevention)", response)
    }

    @Test
    fun testBuildTcpRstResponseRejectsInvalidPackets() {
        // Too short
        assertNull(DnsPacketParser.buildTcpRstResponse(ByteArray(30), 30))

        // Non-TCP (UDP protocol = 17)
        val udpPacket = ByteArray(40).apply {
            this[0] = 0x45
            this[9] = 17 // UDP
        }
        assertNull(DnsPacketParser.buildTcpRstResponse(udpPacket, udpPacket.size))
    }

    private fun verifyIpv4TransportChecksumRfc1071(
        srcIp: ByteArray,
        dstIp: ByteArray,
        protocol: Int,
        segment: ByteArray,
        offset: Int,
        length: Int
    ): Int {
        var sum = 0L
        sum += ((srcIp[0].toInt() and 0xFF) shl 8) or (srcIp[1].toInt() and 0xFF)
        sum += ((srcIp[2].toInt() and 0xFF) shl 8) or (srcIp[3].toInt() and 0xFF)
        sum += ((dstIp[0].toInt() and 0xFF) shl 8) or (dstIp[1].toInt() and 0xFF)
        sum += ((dstIp[2].toInt() and 0xFF) shl 8) or (dstIp[3].toInt() and 0xFF)
        sum += (protocol and 0xFF)
        sum += (length and 0xFFFF)
        var i = offset
        val end = offset + length
        while (i < end - 1) {
            sum += ((segment[i].toInt() and 0xFF) shl 8) or (segment[i + 1].toInt() and 0xFF)
            i += 2
        }
        if (i < end) {
            sum += (segment[i].toInt() and 0xFF) shl 8
        }
        while ((sum ushr 16) != 0L) {
            sum = (sum and 0xFFFF) + (sum ushr 16)
        }
        return (sum and 0xFFFF).toInt()
    }

    private fun verifyIpv6TransportChecksumRfc1071(
        srcIp: ByteArray,
        dstIp: ByteArray,
        nextHeader: Int,
        segment: ByteArray,
        offset: Int,
        length: Int
    ): Int {
        var sum = 0L
        for (i in 0 until 16 step 2) {
            sum += ((srcIp[i].toInt() and 0xFF) shl 8) or (srcIp[i + 1].toInt() and 0xFF)
            sum += ((dstIp[i].toInt() and 0xFF) shl 8) or (dstIp[i + 1].toInt() and 0xFF)
        }
        sum += ((length ushr 16) and 0xFFFF)
        sum += (length and 0xFFFF)
        sum += (nextHeader and 0xFF)
        var i = offset
        val end = offset + length
        while (i < end - 1) {
            sum += ((segment[i].toInt() and 0xFF) shl 8) or (segment[i + 1].toInt() and 0xFF)
            i += 2
        }
        if (i < end) {
            sum += (segment[i].toInt() and 0xFF) shl 8
        }
        while ((sum ushr 16) != 0L) {
            sum = (sum and 0xFFFF) + (sum ushr 16)
        }
        return (sum and 0xFFFF).toInt()
    }

    private fun buildSyntheticTcpPacket(
        srcIp: ByteArray,
        dstIp: ByteArray,
        srcPort: Int,
        dstPort: Int,
        seq: Long,
        ack: Long,
        flags: Int,
        window: Int = 65535
    ): ByteArray {
        val totalLen = 40
        val packet = ByteArray(totalLen)
        val buf = ByteBuffer.wrap(packet)

        // IPv4 Header
        buf.put(0x45.toByte())
        buf.put(0x00.toByte())
        buf.putShort(totalLen.toShort())
        buf.putShort(100.toShort())
        buf.putShort(0x4000.toShort())
        buf.put(64.toByte())
        buf.put(6.toByte()) // TCP
        buf.putShort(0.toShort())
        buf.put(srcIp)
        buf.put(dstIp)

        // TCP Header
        buf.position(20)
        buf.putShort(srcPort.toShort())
        buf.putShort(dstPort.toShort())
        buf.putInt(seq.toInt())
        buf.putInt(ack.toInt())
        buf.put(0x50.toByte()) // Data Offset = 5 (20 bytes)
        buf.put(flags.toByte())
        buf.putShort(window.toShort())
        buf.putShort(0.toShort())
        buf.putShort(0.toShort())

        return packet
    }
}
