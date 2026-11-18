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
    fun testYouTubeSafeSearchVipResponseForIpv6AaaaVip() {
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

        val vipResponse = DnsPacketParser.buildSafeSearchVipResponse(parsed)

        val buf = ByteBuffer.wrap(vipResponse)
        buf.position(28)
        assertEquals(0x9ABC.toShort(), buf.short) // Transaction ID
        assertEquals(0x8180.toShort(), buf.short) // Flags: NOERROR
        assertEquals(1.toShort(), buf.short)      // QDCOUNT = 1
        assertEquals(1.toShort(), buf.short)      // ANCOUNT = 1 (AAAA VIP record)
        assertEquals(0.toShort(), buf.short)      // NSCOUNT = 0
        assertEquals(0.toShort(), buf.short)      // ARCOUNT = 0

        // Skip question section to answer RR (28 bytes total)
        val answerOffset = 28 + 12 + parsed.questionSection.size
        assertEquals(answerOffset + 28, vipResponse.size)
        buf.position(answerOffset)
        assertEquals(0xC0.toByte(), buf.get())
        assertEquals(0x0C.toByte(), buf.get())    // Compression pointer
        assertEquals(28, buf.short.toInt() and 0xFFFF) // TYPE: AAAA (28)
        assertEquals(1, buf.short.toInt() and 0xFFFF)  // CLASS: IN (1)
        assertEquals(300, buf.int)                     // TTL: 300s
        assertEquals(16, buf.short.toInt() and 0xFFFF) // RDLENGTH: 16
        val rdata = ByteArray(16)
        buf.get(rdata)
        assertArrayEquals(DnsPacketParser.YOUTUBE_RESTRICTED_VIP_IPV6, rdata)
    }

    @Test
    fun testYouTubeSafeSearchVipResponseForHttpsNodata() {
        val queryPacket = buildSyntheticDnsQueryPacket(
            srcIp = byteArrayOf(10, 0, 0, 1),
            dstIp = byteArrayOf(10, (-1).toByte(), (-1).toByte(), 1),
            srcPort = 54321,
            dstPort = 53,
            txId = 0x7788.toShort(),
            domain = "www.youtube.com",
            qType = 65 // HTTPS / SVCB record
        )

        val parsed = requireNotNull(DnsPacketParser.parseDnsQuery(queryPacket, queryPacket.size))
        assertEquals(65, parsed.qType)

        val nodataResponse = DnsPacketParser.buildSafeSearchVipResponse(parsed)
        val buf = ByteBuffer.wrap(nodataResponse)
        buf.position(28)
        assertEquals(0x7788.toShort(), buf.short) // Transaction ID
        assertEquals(0x8180.toShort(), buf.short) // Flags: NOERROR
        assertEquals(1.toShort(), buf.short)      // QDCOUNT = 1
        assertEquals(0.toShort(), buf.short)      // ANCOUNT = 0 (NODATA)
        assertEquals(28 + 12 + parsed.questionSection.size, nodataResponse.size)
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

    @Test
    fun testParseIpv6DnsQueryStandardHeader() {
        val srcIp = ipv6Fd00(1)
        val dstIp = ipv6Fd00(2)
        val packet = buildSyntheticIpv6DnsQueryPacket(
            srcIp = srcIp,
            dstIp = dstIp,
            srcPort = 51234,
            dstPort = 53,
            txId = 0x4242.toShort(),
            domain = "example.com",
            qType = 28
        )

        val parsed = requireNotNull(DnsPacketParser.parseDnsQuery(packet, packet.size))
        assertEquals(6, parsed.ipVersion)
        assertEquals(0, parsed.ipId)
        assertArrayEquals(srcIp, parsed.srcIp)
        assertArrayEquals(dstIp, parsed.dstIp)
        assertEquals(51234, parsed.srcPort)
        assertEquals(53, parsed.dstPort)
        assertEquals(0x4242.toShort(), parsed.transactionId)
        assertEquals("example.com", parsed.qName)
        assertEquals(28, parsed.qType)
    }

    @Test
    fun testParseIpv6DnsQueryWithExtensionHeaders() {
        val srcIp = ipv6Fd00(1)
        val dstIp = ipv6Fd00(2)

        // Hop-by-Hop (0): hdrExtLen = 0 -> (0 + 1) * 8 = 8 bytes, nextHeader = 60 (Destination Options)
        val hopByHop = ByteArray(8).apply {
            this[0] = 60.toByte() // nextHeader = Destination Options
            this[1] = 0.toByte()  // hdrExtLen = 0 -> 8 bytes
        }
        // Destination Options (60): hdrExtLen = 1 -> (1 + 1) * 8 = 16 bytes, nextHeader = 17 (UDP)
        val destOpts = ByteArray(16).apply {
            this[0] = 17.toByte() // nextHeader = UDP
            this[1] = 1.toByte()  // hdrExtLen = 1 -> 16 bytes
        }
        val packet = buildSyntheticIpv6DnsQueryPacket(
            srcIp = srcIp,
            dstIp = dstIp,
            srcPort = 52345,
            dstPort = 53,
            txId = 0x1357.toShort(),
            domain = "docs.google.com",
            qType = 1,
            firstNextHeader = 0,
            extensionHeaders = hopByHop + destOpts
        )

        val parsed = requireNotNull(DnsPacketParser.parseDnsQuery(packet, packet.size))
        assertEquals(6, parsed.ipVersion)
        assertArrayEquals(srcIp, parsed.srcIp)
        assertArrayEquals(dstIp, parsed.dstIp)
        assertEquals(52345, parsed.srcPort)
        assertEquals(53, parsed.dstPort)
        assertEquals(0x1357.toShort(), parsed.transactionId)
        assertEquals("docs.google.com", parsed.qName)
        assertEquals(1, parsed.qType)
    }

    @Test
    fun testParseIpv6DnsQueryRejectsFragmentAndMalformedExtensionHeaders() {
        val srcIp = ipv6Fd00(1)
        val dstIp = ipv6Fd00(2)

        // 1. Direct IPv6 Fragment header (44) must be rejected
        val fragExt = ByteArray(8).apply { this[0] = 17.toByte() }
        val fragmentedDirect = buildSyntheticIpv6DnsQueryPacket(
            srcIp = srcIp,
            dstIp = dstIp,
            srcPort = 50001,
            dstPort = 53,
            txId = 0x2222.toShort(),
            domain = "example.com",
            firstNextHeader = 44,
            extensionHeaders = fragExt
        )
        assertNull("IPv6 Fragment header (44) must be rejected", DnsPacketParser.parseDnsQuery(fragmentedDirect, fragmentedDirect.size))

        // 2. Fragment header chained after Hop-by-Hop must also be rejected
        val hopToFrag = ByteArray(8).apply {
            this[0] = 44.toByte()
            this[1] = 0.toByte()
        }
        val fragmentedChained = buildSyntheticIpv6DnsQueryPacket(
            srcIp = srcIp,
            dstIp = dstIp,
            srcPort = 50002,
            dstPort = 53,
            txId = 0x2223.toShort(),
            domain = "example.com",
            firstNextHeader = 0,
            extensionHeaders = hopToFrag + fragExt
        )
        assertNull("Chained IPv6 Fragment header (44) must be rejected", DnsPacketParser.parseDnsQuery(fragmentedChained, fragmentedChained.size))

        // 3. Malformed / out-of-bounds extension header length must be rejected
        val oobExt = ByteArray(8).apply {
            this[0] = 17.toByte()
            this[1] = 200.toByte() // (200 + 1) * 8 = 1608 bytes -> far past actualIpEnd
        }
        val malformedOob = buildSyntheticIpv6DnsQueryPacket(
            srcIp = srcIp,
            dstIp = dstIp,
            srcPort = 50003,
            dstPort = 53,
            txId = 0x2224.toShort(),
            domain = "example.com",
            firstNextHeader = 60,
            extensionHeaders = oobExt
        )
        assertNull("Out-of-bounds IPv6 extension header must be rejected", DnsPacketParser.parseDnsQuery(malformedOob, malformedOob.size))

        // 4. Excessive extension header hops (> 4) must be rejected
        val fiveHops = ByteArray(40)
        for (h in 0 until 5) {
            fiveHops[h * 8] = if (h == 4) 17.toByte() else 60.toByte()
            fiveHops[h * 8 + 1] = 0.toByte()
        }
        val tooManyHops = buildSyntheticIpv6DnsQueryPacket(
            srcIp = srcIp,
            dstIp = dstIp,
            srcPort = 50004,
            dstPort = 53,
            txId = 0x2225.toShort(),
            domain = "example.com",
            firstNextHeader = 0,
            extensionHeaders = fiveHops
        )
        assertNull("IPv6 packets with >4 extension headers must be rejected", DnsPacketParser.parseDnsQuery(tooManyHops, tooManyHops.size))
    }

    @Test
    fun testBuildIpv6NxDomainResponseAndOddLengthChecksum() {
        val srcIp = ipv6Fd00(1)
        val dstIp = ipv6Fd00(2)

        // "a.com" -> QNAME = 7 bytes -> DNS = 12 + 7 + 4 = 23 bytes (ODD length!)
        // "ab.com" -> QNAME = 8 bytes -> DNS = 12 + 8 + 4 = 24 bytes (EVEN length!)
        for (domain in listOf("a.com", "ab.com")) {
            val queryPacket = buildSyntheticIpv6DnsQueryPacket(
                srcIp = srcIp,
                dstIp = dstIp,
                srcPort = 54321,
                dstPort = 53,
                txId = 0x3344.toShort(),
                domain = domain,
                qType = 28
            )
            val parsed = requireNotNull(DnsPacketParser.parseDnsQuery(queryPacket, queryPacket.size))
            assertEquals(6, parsed.ipVersion)

            val response = DnsPacketParser.buildNxDomainResponse(parsed)
            val buf = ByteBuffer.wrap(response)

            // Verify IPv6 header
            assertEquals(6, (buf.get(0).toInt() and 0xFF) ushr 4)
            val payloadLen = buf.getShort(4).toInt() and 0xFFFF
            assertEquals(response.size - DnsPacketParser.IPV6_HEADER_LEN, payloadLen)
            assertEquals(17, buf.get(6).toInt() and 0xFF) // UDP
            assertEquals(64, buf.get(7).toInt() and 0xFF) // Hop Limit

            val respSrcIp = ByteArray(16)
            val respDstIp = ByteArray(16)
            buf.position(8)
            buf.get(respSrcIp)
            buf.get(respDstIp)
            assertArrayEquals(dstIp, respSrcIp)
            assertArrayEquals(srcIp, respDstIp)

            // Verify UDP header
            buf.position(40)
            assertEquals(53, buf.short.toInt() and 0xFFFF)
            assertEquals(54321, buf.short.toInt() and 0xFFFF)
            val respUdpLen = buf.short.toInt() and 0xFFFF
            assertEquals(payloadLen, respUdpLen)
            val respChecksum = buf.short.toInt() and 0xFFFF
            assertNotEquals("RFC 8200 forbids 0 checksum for IPv6 UDP", 0, respChecksum)
            assertEquals(
                "IPv6 UDP NXDOMAIN checksum must verify to 0xFFFF for $domain",
                0xFFFF,
                verifyIpv6UdpChecksumRfc8200(respSrcIp, respDstIp, response, 40, respUdpLen)
            )

            // Verify DNS header
            buf.position(48)
            assertEquals(0x3344.toShort(), buf.short)
            assertEquals(0x8183, buf.short.toInt() and 0xFFFF) // RCODE=3 (NXDOMAIN)
            assertEquals(1, buf.short.toInt() and 0xFFFF)      // QDCOUNT = 1
            assertEquals(0, buf.short.toInt() and 0xFFFF)      // ANCOUNT = 0
        }
    }

    @Test
    fun testBuildIpv6SafeSearchVipResponseForA_Aaaa_AndHttps() {
        val srcIp = ipv6Fd00(1)
        val dstIp = ipv6Fd00(2)

        // 1. A query over IPv6 transport -> IPv4 VIP 216.239.38.120
        val aQueryPacket = buildSyntheticIpv6DnsQueryPacket(srcIp, dstIp, 55001, 53, 0x1001.toShort(), "www.youtube.com", qType = 1)
        val aParsed = requireNotNull(DnsPacketParser.parseDnsQuery(aQueryPacket, aQueryPacket.size))
        val aResp = DnsPacketParser.buildSafeSearchVipResponse(aParsed)
        val aBuf = ByteBuffer.wrap(aResp)
        assertEquals(6, (aBuf.get(0).toInt() and 0xFF) ushr 4)
        val aUdpLen = aBuf.getShort(44).toInt() and 0xFFFF
        assertNotEquals(0, aBuf.getShort(46).toInt() and 0xFFFF)
        assertEquals(0xFFFF, verifyIpv6UdpChecksumRfc8200(dstIp, srcIp, aResp, 40, aUdpLen))
        aBuf.position(48)
        assertEquals(0x1001.toShort(), aBuf.short)
        assertEquals(0x8180.toShort(), aBuf.short)
        assertEquals(1.toShort(), aBuf.short)
        assertEquals(1.toShort(), aBuf.short) // ANCOUNT = 1
        val aAnswerOffset = 48 + 12 + aParsed.questionSection.size
        aBuf.position(aAnswerOffset + 2) // skip compression pointer
        assertEquals(1, aBuf.short.toInt() and 0xFFFF)  // TYPE A
        assertEquals(1, aBuf.short.toInt() and 0xFFFF)  // CLASS IN
        assertEquals(300, aBuf.int)                     // TTL 300
        assertEquals(4, aBuf.short.toInt() and 0xFFFF)  // RDLENGTH 4
        val aRdata = ByteArray(4)
        aBuf.get(aRdata)
        assertArrayEquals(DnsPacketParser.YOUTUBE_RESTRICTED_VIP_IPV4, aRdata)

        // 2. AAAA query over IPv6 transport -> IPv6 VIP 2001:4860:4802:32::78
        val aaaaQueryPacket = buildSyntheticIpv6DnsQueryPacket(srcIp, dstIp, 55002, 53, 0x1002.toShort(), "www.youtube.com", qType = 28)
        val aaaaParsed = requireNotNull(DnsPacketParser.parseDnsQuery(aaaaQueryPacket, aaaaQueryPacket.size))
        val aaaaResp = DnsPacketParser.buildSafeSearchVipResponse(aaaaParsed)
        val aaaaBuf = ByteBuffer.wrap(aaaaResp)
        assertEquals(6, (aaaaBuf.get(0).toInt() and 0xFF) ushr 4)
        val aaaaUdpLen = aaaaBuf.getShort(44).toInt() and 0xFFFF
        assertNotEquals(0, aaaaBuf.getShort(46).toInt() and 0xFFFF)
        assertEquals(0xFFFF, verifyIpv6UdpChecksumRfc8200(dstIp, srcIp, aaaaResp, 40, aaaaUdpLen))
        aaaaBuf.position(48)
        assertEquals(0x1002.toShort(), aaaaBuf.short)
        assertEquals(0x8180.toShort(), aaaaBuf.short)
        assertEquals(1.toShort(), aaaaBuf.short)
        assertEquals(1.toShort(), aaaaBuf.short) // ANCOUNT = 1
        val aaaaAnswerOffset = 48 + 12 + aaaaParsed.questionSection.size
        aaaaBuf.position(aaaaAnswerOffset + 2)
        assertEquals(28, aaaaBuf.short.toInt() and 0xFFFF) // TYPE AAAA
        assertEquals(1, aaaaBuf.short.toInt() and 0xFFFF)  // CLASS IN
        assertEquals(300, aaaaBuf.int)                     // TTL 300
        assertEquals(16, aaaaBuf.short.toInt() and 0xFFFF) // RDLENGTH 16
        val aaaaRdata = ByteArray(16)
        aaaaBuf.get(aaaaRdata)
        assertArrayEquals(DnsPacketParser.YOUTUBE_RESTRICTED_VIP_IPV6, aaaaRdata)

        // 3. HTTPS query (qType == 65) over IPv6 transport -> NODATA (NOERROR, ANCOUNT = 0)
        val httpsQueryPacket = buildSyntheticIpv6DnsQueryPacket(srcIp, dstIp, 55003, 53, 0x1003.toShort(), "www.youtube.com", qType = 65)
        val httpsParsed = requireNotNull(DnsPacketParser.parseDnsQuery(httpsQueryPacket, httpsQueryPacket.size))
        val httpsResp = DnsPacketParser.buildSafeSearchVipResponse(httpsParsed)
        val httpsBuf = ByteBuffer.wrap(httpsResp)
        assertEquals(6, (httpsBuf.get(0).toInt() and 0xFF) ushr 4)
        val httpsUdpLen = httpsBuf.getShort(44).toInt() and 0xFFFF
        assertNotEquals(0, httpsBuf.getShort(46).toInt() and 0xFFFF)
        assertEquals(0xFFFF, verifyIpv6UdpChecksumRfc8200(dstIp, srcIp, httpsResp, 40, httpsUdpLen))
        httpsBuf.position(48)
        assertEquals(0x1003.toShort(), httpsBuf.short)
        assertEquals(0x8180.toShort(), httpsBuf.short)
        assertEquals(1.toShort(), httpsBuf.short)
        assertEquals(0.toShort(), httpsBuf.short) // ANCOUNT = 0 (NODATA)
        assertEquals(48 + 12 + httpsParsed.questionSection.size, httpsResp.size)
    }

    @Test
    fun testWrapUpstreamDnsResponseIpv6AndChecksum() {
        val clientIp = ipv6Fd00(1)
        val serverIp = ipv6Fd00(2)
        // Test both odd (13B) and even (12B) payloads
        val payloads = listOf(
            byteArrayOf(0x12, 0x34, (-127).toByte(), (-128).toByte(), 0, 1, 0, 1, 0, 0, 0, 0),
            byteArrayOf(0x56, 0x78, (-127).toByte(), (-128).toByte(), 0, 1, 0, 1, 0, 0, 0, 0, 0x07)
        )
        for (dnsPayload in payloads) {
            val wrapped = DnsPacketParser.wrapUpstreamDnsResponse(
                clientIp = clientIp,
                serverIp = serverIp,
                clientPort = 54321,
                serverPort = 53,
                dnsResponsePayload = dnsPayload
            )
            val expectedUdpLen = 8 + dnsPayload.size
            assertEquals(40 + expectedUdpLen, wrapped.size)

            val buf = ByteBuffer.wrap(wrapped)
            assertEquals(6, (buf.get(0).toInt() and 0xFF) ushr 4)
            assertEquals(expectedUdpLen, buf.getShort(4).toInt() and 0xFFFF)
            assertEquals(17, buf.get(6).toInt() and 0xFF)
            assertEquals(64, buf.get(7).toInt() and 0xFF)

            val outSrc = ByteArray(16)
            val outDst = ByteArray(16)
            buf.position(8)
            buf.get(outSrc)
            buf.get(outDst)
            assertArrayEquals(serverIp, outSrc)
            assertArrayEquals(clientIp, outDst)

            buf.position(40)
            assertEquals(53, buf.short.toInt() and 0xFFFF)
            assertEquals(54321, buf.short.toInt() and 0xFFFF)
            assertEquals(expectedUdpLen, buf.short.toInt() and 0xFFFF)
            val checksum = buf.short.toInt() and 0xFFFF
            assertNotEquals(0, checksum)
            assertEquals(
                "Wrapped IPv6 UDP response checksum must verify to 0xFFFF",
                0xFFFF,
                verifyIpv6UdpChecksumRfc8200(serverIp, clientIp, wrapped, 40, expectedUdpLen)
            )

            val extracted = ByteArray(dnsPayload.size)
            buf.get(extracted)
            assertArrayEquals(dnsPayload, extracted)
        }
    }

    private fun ipv6Fd00(lastByte: Int): ByteArray = ByteArray(16).apply {
        this[0] = 0xfd.toByte()
        this[15] = lastByte.toByte()
    }

    private fun buildSyntheticIpv6DnsQueryPacket(
        srcIp: ByteArray,
        dstIp: ByteArray,
        srcPort: Int,
        dstPort: Int,
        txId: Short,
        domain: String,
        qType: Short = 1,
        firstNextHeader: Int = 17,
        extensionHeaders: ByteArray = ByteArray(0)
    ): ByteArray {
        val labels = domain.split(".")
        var qNameLen = 1
        for (label in labels) {
            qNameLen += 1 + label.length
        }
        val dnsLen = 12 + qNameLen + 4
        val udpLen = 8 + dnsLen
        val payloadLen = extensionHeaders.size + udpLen
        val packet = ByteArray(40 + payloadLen)
        val buf = ByteBuffer.wrap(packet)

        // IPv6 Header (40 bytes)
        buf.putInt(0x60000000)
        buf.putShort(payloadLen.toShort())
        buf.put(firstNextHeader.toByte())
        buf.put(64.toByte())
        buf.put(srcIp)
        buf.put(dstIp)

        // Optional Extension Headers
        if (extensionHeaders.isNotEmpty()) {
            buf.put(extensionHeaders)
        }

        // UDP Header
        buf.putShort(srcPort.toShort())
        buf.putShort(dstPort.toShort())
        buf.putShort(udpLen.toShort())
        buf.putShort(0.toShort())

        // DNS Header
        buf.putShort(txId)
        buf.putShort(0x0100.toShort())
        buf.putShort(1.toShort())
        buf.putShort(0.toShort())
        buf.putShort(0.toShort())
        buf.putShort(0.toShort())

        // Question: QNAME + QTYPE + QCLASS
        for (label in labels) {
            buf.put(label.length.toByte())
            buf.put(label.toByteArray(Charsets.US_ASCII))
        }
        buf.put(0x00.toByte())
        buf.putShort(qType)
        buf.putShort(1.toShort())

        return packet
    }

    private fun verifyIpv6UdpChecksumRfc8200(
        srcIp: ByteArray,
        dstIp: ByteArray,
        packet: ByteArray,
        udpOffset: Int,
        udpLength: Int
    ): Int {
        var sum = 0L
        for (i in 0 until 16 step 2) {
            sum += ((srcIp[i].toInt() and 0xFF) shl 8) or (srcIp[i + 1].toInt() and 0xFF)
            sum += ((dstIp[i].toInt() and 0xFF) shl 8) or (dstIp[i + 1].toInt() and 0xFF)
        }
        sum += ((udpLength ushr 16) and 0xFFFF)
        sum += (udpLength and 0xFFFF)
        sum += 17 // Next Header = UDP
        var i = udpOffset
        val end = udpOffset + udpLength
        while (i < end - 1) {
            sum += ((packet[i].toInt() and 0xFF) shl 8) or (packet[i + 1].toInt() and 0xFF)
            i += 2
        }
        if (i < end) {
            sum += (packet[i].toInt() and 0xFF) shl 8
        }
        while ((sum ushr 16) != 0L) {
            sum = (sum and 0xFFFF) + (sum ushr 16)
        }
        return (sum and 0xFFFF).toInt()
    }

    @Test
    fun testParseIpv6DnsQueryRejectsZeroUndersizedAndTruncatedPayloadLen() {
        val validPacket = buildSyntheticIpv6DnsQueryPacket(
            srcIp = ByteArray(16).apply { this[0] = 0xfd.toByte(); this[15] = 0x01 },
            dstIp = ByteArray(16).apply { this[0] = 0xfd.toByte(); this[15] = 0x02 },
            srcPort = 51000,
            dstPort = 53,
            txId = 0x4444.toShort(),
            domain = "example.com",
            qType = 28
        )
        assertNotNull(DnsPacketParser.parseDnsQuery(validPacket, validPacket.size))

        // payloadLen == 0 (invalid / jumbogram) must be rejected
        val zeroPayload = validPacket.copyOf()
        zeroPayload[4] = 0; zeroPayload[5] = 0
        assertNull(DnsPacketParser.parseDnsQuery(zeroPayload, zeroPayload.size))

        // payloadLen < UDP_HEADER_LEN + DNS_HEADER_LEN (20) must be rejected even with trailing bytes
        val smallPayload = validPacket.copyOf()
        smallPayload[4] = 0; smallPayload[5] = 12
        assertNull(DnsPacketParser.parseDnsQuery(smallPayload, smallPayload.size))

        // Truncated packet (40 + payloadLen > length) must be rejected
        assertNull(DnsPacketParser.parseDnsQuery(validPacket, validPacket.size - 1))
    }

    @Test
    fun testIpv6UdpChecksumZeroConvertsTo0xFFFFPerRfc8200() {
        val srcIp = ByteArray(16)
        val dstIp = ByteArray(16)
        // Construct an 8-byte UDP header whose 16-bit one's complement sum + pseudo-header (udpLen=8, nextHdr=17 -> 25) == 0xFFFF
        val udpLen = 8
        val complementWord = (0xFFFF - (udpLen + 17)) // 0xFFE6
        val udpPacket = ByteArray(8)
        udpPacket[0] = ((complementWord ushr 8) and 0xFF).toByte()
        udpPacket[1] = (complementWord and 0xFF).toByte()
        // Remaining bytes 0 -> sum = 0xFFE6 + 0x0019 = 0xFFFF -> ~sum = 0 -> RFC 8200 mandates 0xFFFF
        val checksum = com.drmohammed.antiporn.core.network.dns.Ipv6DnsPacketCodec.calculateIpv6UdpChecksum(
            srcIp = srcIp,
            dstIp = dstIp,
            udpLen = udpLen,
            udpPacket = udpPacket,
            udpOffset = 0,
            udpPacketLen = udpLen
        )
        assertEquals(0xFFFF.toShort(), checksum)
    }
}
