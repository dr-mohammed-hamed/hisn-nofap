package com.drmohammed.antiporn.core.network.dns

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.ByteBuffer

class DnsTunnelWorkerTest {

    @Test
    fun testSelectMaxResponsePayloadBytesMatchesIpFamily() {
        assertEquals(DnsWireValidator.MAX_IPV4_UDP_DNS_PAYLOAD, DnsTunnelWorker.selectMaxResponsePayloadBytes(4))
        assertEquals(DnsWireValidator.MAX_IPV6_UDP_DNS_PAYLOAD, DnsTunnelWorker.selectMaxResponsePayloadBytes(6))
    }

    @Test
    fun testTryBuildTcpRstForProbeInterceptsIpv4AndIpv6IncludingExtensionHeaders() {
        // IPv4 TCP SYN to port 853 -> intercepted
        val ipv4Syn853 = buildIpv4TcpSyn(dstPort = 853)
        val ipv4Rst = DnsTunnelWorker.tryBuildTcpRstForProbe(ipv4Syn853, ipv4Syn853.size)
        assertNotNull("IPv4 TCP probe on port 853 must return RST", ipv4Rst)

        // IPv4 TCP SYN to port 80 -> NOT intercepted
        val ipv4Syn80 = buildIpv4TcpSyn(dstPort = 80)
        assertNull("IPv4 TCP on port 80 must not be intercepted", DnsTunnelWorker.tryBuildTcpRstForProbe(ipv4Syn80, ipv4Syn80.size))

        // IPv6 TCP SYN to port 853 with Hop-by-Hop extension header (Next Header = 0 -> 6) -> intercepted
        val ipv6ExtSyn = buildIpv6TcpSynWithHopByHop(dstPort = 853)
        val ipv6Rst = DnsTunnelWorker.tryBuildTcpRstForProbe(ipv6ExtSyn, ipv6ExtSyn.size)
        assertNotNull("IPv6 TCP probe with extension header on port 853 must return RST", ipv6Rst)
        assertEquals(6, ((ipv6Rst?.get(0)?.toInt() ?: 0) and 0xFF) ushr 4)
        assertEquals(6, (ipv6Rst?.get(6)?.toInt() ?: 0) and 0xFF) // TCP

        // IPv6 TCP SYN to port 53 & 443 -> intercepted
        assertNotNull(DnsTunnelWorker.tryBuildTcpRstForProbe(buildIpv6TcpSynWithHopByHop(53), 68))
        assertNotNull(DnsTunnelWorker.tryBuildTcpRstForProbe(buildIpv6TcpSynWithHopByHop(443), 68))
        // IPv6 TCP SYN to port 8080 -> ignored
        assertNull(DnsTunnelWorker.tryBuildTcpRstForProbe(buildIpv6TcpSynWithHopByHop(8080), 68))
    }

    @Test
    fun testIpv4FirstFragmentWithMfBitAndTruncatedUdpAreRejected() {
        val validQuery = buildIpv4UdpDnsQuery(flagsAndFrag = 0x4000)
        assertNotNull(DnsPacketParser.parseDnsQuery(validQuery, validQuery.size))

        // MF=1 (0x2000) with fragOffset=0 (first IPv4 fragment) must be rejected
        val firstFrag = buildIpv4UdpDnsQuery(flagsAndFrag = 0x2000)
        assertNull("IPv4 first fragment (MF=1) must be rejected", DnsPacketParser.parseDnsQuery(firstFrag, firstFrag.size))

        // Truncated UDP datagram (udpLength > totalIpLen - ihl) must be rejected
        val truncatedUdp = buildIpv4UdpDnsQuery(flagsAndFrag = 0x4000, udpLenOverride = 500)
        assertNull("Truncated UDP datagram must be rejected", DnsPacketParser.parseDnsQuery(truncatedUdp, truncatedUdp.size))

        // Invalid IPv4 totalLength > buffer length must be rejected
        val badTotalLen = buildIpv4UdpDnsQuery(flagsAndFrag = 0x4000, ipTotalLenOverride = 999)
        assertNull("Malformed IPv4 totalLength > packet length must be rejected", DnsPacketParser.parseDnsQuery(badTotalLen, badTotalLen.size))
    }

    @Test
    fun testCalculateIpChecksumHandlesOddLengthSafely() {
        val oddBytes = byteArrayOf(0x45, 0x00, 0x00)
        val checksum = DnsPacketParser.calculateIpChecksum(oddBytes, 0, oddBytes.size)
        // Must not throw ArrayIndexOutOfBoundsException
        assertEquals((0x4500.inv() and 0xFFFF).toShort(), checksum)
    }

    private fun buildIpv4TcpSyn(dstPort: Int): ByteArray {
        val pkt = ByteArray(40)
        val buf = ByteBuffer.wrap(pkt)
        buf.put(0x45.toByte()); buf.put(0); buf.putShort(40); buf.putShort(1); buf.putShort(0x4000)
        buf.put(64); buf.put(6); buf.putShort(0)
        buf.put(byteArrayOf(10, 255.toByte(), 255.toByte(), 1))
        buf.put(byteArrayOf(1, 1, 1, 3))
        buf.putShort(54321.toShort()); buf.putShort(dstPort.toShort())
        buf.putInt(100); buf.putInt(0); buf.put(0x50.toByte()); buf.put(0x02.toByte())
        buf.putShort(65535.toShort()); buf.putShort(0); buf.putShort(0)
        return pkt
    }

    private fun buildIpv6TcpSynWithHopByHop(dstPort: Int): ByteArray {
        // 40B IPv6 + 8B Hop-by-Hop ext hdr + 20B TCP = 68B
        val pkt = ByteArray(68)
        val buf = ByteBuffer.wrap(pkt)
        buf.putInt(0x60000000)
        buf.putShort(28) // 8 + 20
        buf.put(0) // Next Header = Hop-by-Hop (0)
        buf.put(64)
        buf.put(ByteArray(16).apply { this[0] = 0xFD.toByte(); this[15] = 1 })
        buf.put(ByteArray(16).apply { this[0] = 0xFD.toByte(); this[15] = 2 })
        // Hop-by-Hop extension header (8 bytes): nextHeader=6 (TCP), hdrExtLen=0 -> (0+1)*8 = 8
        buf.put(6); buf.put(0); buf.put(ByteArray(6))
        // TCP header at offset 48
        buf.putShort(54321.toShort()); buf.putShort(dstPort.toShort())
        buf.putInt(200); buf.putInt(0); buf.put(0x50.toByte()); buf.put(0x02.toByte())
        buf.putShort(65535.toShort()); buf.putShort(0); buf.putShort(0)
        return pkt
    }

    private fun buildIpv4UdpDnsQuery(
        flagsAndFrag: Int,
        ipTotalLenOverride: Int? = null,
        udpLenOverride: Int? = null
    ): ByteArray {
        val dns = requireNotNull(DotUpstreamClient.buildMinimalAQuery("example.com", 0x1234.toShort()))
        val udpLen = 8 + dns.size
        val ipLen = 20 + udpLen
        val pkt = ByteArray(ipLen)
        val buf = ByteBuffer.wrap(pkt)
        buf.put(0x45.toByte()); buf.put(0)
        buf.putShort((ipTotalLenOverride ?: ipLen).toShort())
        buf.putShort(1)
        buf.putShort(flagsAndFrag.toShort())
        buf.put(64); buf.put(17); buf.putShort(0)
        buf.put(byteArrayOf(10, 255.toByte(), 255.toByte(), 1))
        buf.put(byteArrayOf(10, 255.toByte(), 255.toByte(), 2))
        buf.putShort(51234.toShort()); buf.putShort(53)
        buf.putShort((udpLenOverride ?: udpLen).toShort()); buf.putShort(0)
        buf.put(dns)
        return pkt
    }
}
