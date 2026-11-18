package com.drmohammed.antiporn.core.network

import com.drmohammed.antiporn.core.network.dns.DnsPacketParser
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer

class DnsPacketParserTest {

    @Test
    fun testDomainBlockingWithSubdomainsAndSmartExpansion() {
        val blockedRules = setOf("youtube.com", "tiktok.com")

        // 1. Direct matches
        assertTrue(DnsPacketParser.isDomainBlocked("youtube.com", blockedRules))
        assertTrue(DnsPacketParser.isDomainBlocked("www.youtube.com", blockedRules))
        assertTrue(DnsPacketParser.isDomainBlocked("m.youtube.com", blockedRules))
        assertTrue(DnsPacketParser.isDomainBlocked("music.youtube.com", blockedRules))

        // 2. Smart service expansions for youtube.com
        assertTrue(DnsPacketParser.isDomainBlocked("youtu.be", blockedRules))
        assertTrue(DnsPacketParser.isDomainBlocked("googlevideo.com", blockedRules))
        assertTrue(DnsPacketParser.isDomainBlocked("rr1---sn-4g5ednss.googlevideo.com", blockedRules))
        assertTrue(DnsPacketParser.isDomainBlocked("ytimg.com", blockedRules))
        assertTrue(DnsPacketParser.isDomainBlocked("i.ytimg.com", blockedRules))
        assertTrue(DnsPacketParser.isDomainBlocked("youtube-nocookie.com", blockedRules))

        // 3. TikTok matches
        assertTrue(DnsPacketParser.isDomainBlocked("tiktok.com", blockedRules))
        assertTrue(DnsPacketParser.isDomainBlocked("v16-webapp-prime.tiktok.com", blockedRules))
        assertTrue(DnsPacketParser.isDomainBlocked("tiktokcdn.com", blockedRules))

        // 4. Allowed domains that share root or keywords
        assertFalse(DnsPacketParser.isDomainBlocked("google.com", blockedRules))
        assertFalse(DnsPacketParser.isDomainBlocked("play.google.com", blockedRules))
        assertFalse(DnsPacketParser.isDomainBlocked("wikipedia.org", blockedRules))
        assertFalse(DnsPacketParser.isDomainBlocked("notyoutube.com", blockedRules))
    }

    @Test
    fun testDomainBlockingWithWildcardsAndPaths() {
        val rulesWithWildcardsAndPaths = setOf(
            "*.youtube.com",
            "https://facebook.com/watch?v=123",
            "tiktok.com/",
            "http://instagram.com:8080/feed",
            "*.twitter.com/"
        )

        assertTrue(DnsPacketParser.isDomainBlocked("youtube.com", rulesWithWildcardsAndPaths))
        assertTrue(DnsPacketParser.isDomainBlocked("m.youtube.com", rulesWithWildcardsAndPaths))
        assertTrue(DnsPacketParser.isDomainBlocked("m.youtube.com.", rulesWithWildcardsAndPaths))
        assertTrue(DnsPacketParser.isDomainBlocked("tiktok.com", rulesWithWildcardsAndPaths))
        assertTrue(DnsPacketParser.isDomainBlocked("v16.tiktok.com", rulesWithWildcardsAndPaths))
        assertTrue(DnsPacketParser.isDomainBlocked("twitter.com", rulesWithWildcardsAndPaths))
        assertTrue(DnsPacketParser.isDomainBlocked("api.twitter.com", rulesWithWildcardsAndPaths))

        // Path rules (like /shorts or /watch) MUST NOT block apex domain via DNS!
        // Path rules belong to BrowserShield/PageShield.
        assertFalse("facebook.com must NOT be blocked because rule has URL path /watch",
            DnsPacketParser.isDomainBlocked("facebook.com", rulesWithWildcardsAndPaths))
        assertFalse("instagram.com must NOT be blocked because rule has URL path /feed",
            DnsPacketParser.isDomainBlocked("instagram.com", rulesWithWildcardsAndPaths))

        // Explicit URL path, query, and fragment sanitization checks
        assertEquals("", DnsPacketParser.sanitizeRule("youtube.com/shorts"))
        assertEquals("", DnsPacketParser.sanitizeRule("https://example.com/blocked-page"))
        assertEquals("", DnsPacketParser.sanitizeRule("http://example.com:8080/path/to/page"))
        assertEquals("", DnsPacketParser.sanitizeRule("example.com?filter=active"))
        assertEquals("", DnsPacketParser.sanitizeRule("example.com#section"))
        assertEquals("tiktok.com", DnsPacketParser.sanitizeRule("tiktok.com/"))
        assertEquals("twitter.com", DnsPacketParser.sanitizeRule("*.twitter.com/"))
        assertEquals("example.com", DnsPacketParser.sanitizeRule("example.com:8080"))

        assertFalse(DnsPacketParser.isDomainBlocked("google.com", rulesWithWildcardsAndPaths))
    }

    @Test
    fun testIsDomainBlockedCompiledPreservesTwoLabelWwwDomain() {
        assertTrue(DnsPacketParser.isDomainBlockedCompiled("www.com", setOf("www.com")))
        assertTrue(DnsPacketParser.isDomainBlockedCompiled("WWW.COM.", setOf("www.com")))
        assertTrue(DnsPacketParser.isDomainBlockedCompiled("sub.www.com", setOf("www.com")))
        assertTrue(DnsPacketParser.isDomainBlockedCompiled("www.example.com", setOf("example.com")))
    }

    @Test
    fun testParseDnsQueryAndBuildNxDomainResponse() {
        // Construct a synthetic IPv4/UDP/DNS query for "youtube.com"
        val queryPacket = buildSyntheticDnsQueryPacket(
            srcIp = byteArrayOf(10, 0, 0, 1),
            dstIp = byteArrayOf(10, (-1).toByte(), (-1).toByte(), 1), // 10.255.255.1
            srcPort = 54321,
            dstPort = 53,
            txId = 0x1234.toShort(),
            domain = "youtube.com"
        )

        val parsed = requireNotNull(DnsPacketParser.parseDnsQuery(queryPacket, queryPacket.size))
        assertEquals("youtube.com", parsed.qName)
        assertEquals(54321, parsed.srcPort)
        assertEquals(53, parsed.dstPort)
        assertEquals(0x1234.toShort(), parsed.transactionId)
        assertArrayEquals(byteArrayOf(10, 0, 0, 1), parsed.srcIp)
        assertArrayEquals(byteArrayOf(10, (-1).toByte(), (-1).toByte(), 1), parsed.dstIp)

        // Build NXDOMAIN response
        val response = DnsPacketParser.buildNxDomainResponse(parsed)
        assertNotNull(response)
        assertTrue(response.size > 28 + 12)

        val buf = ByteBuffer.wrap(response)
        // Check IPv4 header
        assertEquals(4, (buf.get(0).toInt() and 0xFF) ushr 4)
        val respSrcIp = ByteArray(4)
        val respDstIp = ByteArray(4)
        buf.position(12)
        buf.get(respSrcIp)
        buf.get(respDstIp)
        assertArrayEquals(byteArrayOf(10, (-1).toByte(), (-1).toByte(), 1), respSrcIp) // Swapped
        assertArrayEquals(byteArrayOf(10, 0, 0, 1), respDstIp)     // Swapped

        // Check UDP header
        buf.position(20)
        val respSrcPort = buf.short.toInt() and 0xFFFF
        val respDstPort = buf.short.toInt() and 0xFFFF
        assertEquals(53, respSrcPort)     // Swapped
        assertEquals(54321, respDstPort)  // Swapped

        // Check DNS header
        buf.position(28)
        val respTxId = buf.short
        val respFlags = buf.short.toInt() and 0xFFFF
        val respQdCount = buf.short.toInt() and 0xFFFF
        val respAnCount = buf.short.toInt() and 0xFFFF

        assertEquals(0x1234.toShort(), respTxId)
        assertEquals(0x8183, respFlags) // Response, Opcode=0, AA=0, RD=1, RA=1, RCODE=3 (NXDOMAIN)
        assertEquals(1, respQdCount)
        assertEquals(0, respAnCount)
    }

    @Test
    fun testMultiQuestionQueryRejected() {
        val queryPacket = buildSyntheticDnsQueryPacket(
            srcIp = byteArrayOf(10, 0, 0, 1),
            dstIp = byteArrayOf(10, (-1).toByte(), (-1).toByte(), 1),
            srcPort = 54321,
            dstPort = 53,
            txId = 0x1234.toShort(),
            domain = "youtube.com"
        )
        // Set QDCOUNT to 2 (offset 32 in packet: 20 IP + 8 UDP + 4 txId+flags = 32)
        queryPacket[32] = 0x00
        queryPacket[33] = 0x02

        val parsed = DnsPacketParser.parseDnsQuery(queryPacket, queryPacket.size)
        assertNull("Multi-question DNS queries must be rejected", parsed)
    }

    @Test
    fun testNonZeroOpcodeRejected() {
        val queryPacket = buildSyntheticDnsQueryPacket(
            srcIp = byteArrayOf(10, 0, 0, 1),
            dstIp = byteArrayOf(10, (-1).toByte(), (-1).toByte(), 1),
            srcPort = 54321,
            dstPort = 53,
            txId = 0x1234.toShort(),
            domain = "youtube.com"
        )
        // Set Opcode = 1 (IQUERY/Status): flags at offset 30: 0x0800 ((1 << 11) = 0x0800)
        queryPacket[30] = 0x08
        queryPacket[31] = 0x00

        val parsed = DnsPacketParser.parseDnsQuery(queryPacket, queryPacket.size)
        assertNull("DNS queries with non-zero Opcode must be rejected", parsed)
    }

    @Test
    fun testUnterminatedQnameRejected() {
        val queryPacket = buildSyntheticDnsQueryPacket(
            srcIp = byteArrayOf(10, 0, 0, 1),
            dstIp = byteArrayOf(10, (-1).toByte(), (-1).toByte(), 1),
            srcPort = 54321,
            dstPort = 53,
            txId = 0x1234.toShort(),
            domain = "youtube.com"
        )
        // Overwrite the terminating 0 byte of QNAME with non-zero length
        // "youtube" (7) + "com" (3) -> 1+7+1+3 = 12 bytes after header (offset 40 + 12 = 52 is the 0x00)
        // Find 0x00 right before QTYPE (1)
        val termIndex = queryPacket.size - 5 // 4 bytes of QTYPE/QCLASS + 1 byte of term 0
        assertEquals(0x00.toByte(), queryPacket[termIndex])
        queryPacket[termIndex] = 0x05 // Corrupt terminator

        val parsed = DnsPacketParser.parseDnsQuery(queryPacket, queryPacket.size)
        assertNull("Unterminated QNAME must be rejected", parsed)
    }

    @Test
    fun testYouTubeSafeSearchVipResponseForIpv4A() {
        val queryPacket = buildSyntheticDnsQueryPacket(
            srcIp = byteArrayOf(10, 0, 0, 1),
            dstIp = byteArrayOf(10, (-1).toByte(), (-1).toByte(), 1),
            srcPort = 54321,
            dstPort = 53,
            txId = 0x5678.toShort(),
            domain = "www.youtube.com",
            qType = 1 // A record
        )

        val parsed = requireNotNull(DnsPacketParser.parseDnsQuery(queryPacket, queryPacket.size))
        assertEquals(1, parsed.qType)
        assertEquals("www.youtube.com", parsed.qName)

        val vipResponse = DnsPacketParser.buildSafeSearchVipResponse(parsed)
        assertNotNull(vipResponse)

        val buf = ByteBuffer.wrap(vipResponse)
        // Check DNS Header at byte 28
        buf.position(28)
        assertEquals(0x5678.toShort(), buf.short) // Transaction ID
        assertEquals(0x8180.toShort(), buf.short) // Flags: NOERROR
        assertEquals(1.toShort(), buf.short)      // QDCOUNT
        assertEquals(1.toShort(), buf.short)      // ANCOUNT = 1 (A record present)

        // Check last 4 bytes: 216.239.38.120
        val last4 = ByteArray(4)
        buf.position(vipResponse.size - 4)
        buf.get(last4)
        assertEquals(216.toByte(), last4[0])
        assertEquals(239.toByte(), last4[1])
        assertEquals(38.toByte(), last4[2])
        assertEquals(120.toByte(), last4[3])
    }

    @Test
    fun testYouTubeSafeSearchVipResponseForIpv6AaaaNodata() {
        val queryPacket = buildSyntheticDnsQueryPacket(
            srcIp = byteArrayOf(10, 0, 0, 1),
            dstIp = byteArrayOf(10, (-1).toByte(), (-1).toByte(), 1),
            srcPort = 54321,
            dstPort = 53,
            txId = 0x9ABC.toShort(),
            domain = "www.youtube.com",
            qType = 28 // AAAA IPv6 record
        )

        val parsed = requireNotNull(DnsPacketParser.parseDnsQuery(queryPacket, queryPacket.size))
        assertEquals(28, parsed.qType)

        val nodataResponse = DnsPacketParser.buildSafeSearchVipResponse(parsed)
        assertNotNull(nodataResponse)

        val buf = ByteBuffer.wrap(nodataResponse)
        buf.position(28)
        assertEquals(0x9ABC.toShort(), buf.short) // Transaction ID
        assertEquals(0x8180.toShort(), buf.short) // Flags: NOERROR
        assertEquals(1.toShort(), buf.short)      // QDCOUNT = 1
        assertEquals(0.toShort(), buf.short)      // ANCOUNT = 0 (Standard NODATA to prevent IPv6 bypass)
    }

    private fun buildSyntheticDnsQueryPacket(
        srcIp: ByteArray,
        dstIp: ByteArray,
        srcPort: Int,
        dstPort: Int,
        txId: Short,
        domain: String,
        qType: Short = 1
    ): ByteArray {
        val labels = domain.split(".")
        var qNameLen = 1 // trailing 0
        for (label in labels) {
            qNameLen += 1 + label.length
        }
        val dnsLen = 12 + qNameLen + 4 // Header + QNAME + QTYPE + QCLASS
        val udpLen = 8 + dnsLen
        val ipLen = 20 + udpLen

        val packet = ByteArray(ipLen)
        val buf = ByteBuffer.wrap(packet)

        // IP Header
        buf.put(0x45.toByte())
        buf.put(0x00.toByte())
        buf.putShort(ipLen.toShort())
        buf.putShort(100.toShort())
        buf.putShort(0x4000.toShort())
        buf.put(64.toByte())
        buf.put(17.toByte())
        buf.putShort(0.toShort()) // checksum placeholder
        buf.put(srcIp)
        buf.put(dstIp)

        // UDP Header
        buf.position(20)
        buf.putShort(srcPort.toShort())
        buf.putShort(dstPort.toShort())
        buf.putShort(udpLen.toShort())
        buf.putShort(0.toShort())

        // DNS Header
        buf.position(28)
        buf.putShort(txId)
        buf.putShort(0x0100.toShort()) // Standard query with RD=1
        buf.putShort(1.toShort())      // QDCOUNT = 1
        buf.putShort(0.toShort())
        buf.putShort(0.toShort())
        buf.putShort(0.toShort())

        // Question: QNAME
        for (label in labels) {
            buf.put(label.length.toByte())
            buf.put(label.toByteArray(Charsets.US_ASCII))
        }
        buf.put(0x00.toByte())
        buf.putShort(qType)       // QTYPE: A (1) or AAAA (28)
        buf.putShort(1.toShort()) // QCLASS: IN (Internet)

        return packet
    }

    @Test
    fun testWrapUpstreamDnsResponseAndPacketHardening() {
        val clientIp = byteArrayOf(10, (-1).toByte(), (-1).toByte(), 1)
        val serverIp = byteArrayOf(10, (-1).toByte(), (-1).toByte(), 2)
        val dnsPayload = byteArrayOf(0x12, 0x34, (-127).toByte(), (-128).toByte(), 0, 1, 0, 1, 0, 0, 0, 0)
        val wrapped = DnsPacketParser.wrapUpstreamDnsResponse(
            clientIp = clientIp,
            serverIp = serverIp,
            clientPort = 54321,
            serverPort = 53,
            dnsResponsePayload = dnsPayload
        )
        assertEquals(20 + 8 + dnsPayload.size, wrapped.size)
        val buf = ByteBuffer.wrap(wrapped)
        assertEquals(4, (buf.get(0).toInt() and 0xFF) ushr 4)
        assertEquals(17, buf.get(9).toInt() and 0xFF)
        assertEquals("IPv4 header checksum must verify to 0xFFFF", 0xFFFF, verifyIpv4HeaderChecksumRfc1071(wrapped))
        val outSrc = ByteArray(4)
        val outDst = ByteArray(4)
        buf.position(12)
        buf.get(outSrc)
        buf.get(outDst)
        assertArrayEquals(serverIp, outSrc)
        assertArrayEquals(clientIp, outDst)
        buf.position(20)
        assertEquals(53, buf.short.toInt() and 0xFFFF)
        assertEquals(54321, buf.short.toInt() and 0xFFFF)
        assertEquals(8 + dnsPayload.size, buf.short.toInt() and 0xFFFF)
        buf.short // udp checksum
        val extractedPayload = ByteArray(dnsPayload.size)
        buf.get(extractedPayload)
        assertArrayEquals(dnsPayload, extractedPayload)

        // Hardening: length > packet.size and IPv4 non-first fragments rejected
        val validQuery = buildSyntheticDnsQueryPacket(
            srcIp = clientIp,
            dstIp = serverIp,
            srcPort = 54321,
            dstPort = 53,
            txId = 0x1111.toShort(),
            domain = "example.com"
        )
        assertNull(DnsPacketParser.parseDnsQuery(validQuery, validQuery.size + 10))
        val fragmentedQuery = validQuery.copyOf().apply {
            this[6] = 0x00
            this[7] = 0x08 // non-zero fragment offset
        }
        assertNull("IPv4 non-first fragments must be rejected", DnsPacketParser.parseDnsQuery(fragmentedQuery, fragmentedQuery.size))
    }

    private fun verifyIpv4HeaderChecksumRfc1071(packet: ByteArray): Int {
        var sum = 0
        var i = 0
        while (i < 20) {
            sum += ((packet[i].toInt() and 0xFF) shl 8) or (packet[i + 1].toInt() and 0xFF)
            i += 2
        }
        while ((sum ushr 16) != 0) {
            sum = (sum and 0xFFFF) + (sum ushr 16)
        }
        return sum and 0xFFFF
    }
}
