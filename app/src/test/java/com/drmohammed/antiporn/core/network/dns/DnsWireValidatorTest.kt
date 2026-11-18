package com.drmohammed.antiporn.core.network.dns

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.nio.ByteBuffer

class DnsWireValidatorTest {

    @Test
    fun testExcludedAndNonPublicIpFiltering() {
        assertTrue(DnsWireValidator.isExcludedDnsIp("10.255.255.1"))
        assertTrue(DnsWireValidator.isExcludedDnsIp("10.255.255.2"))
        assertTrue(DnsWireValidator.isExcludedDnsIp("127.0.0.1"))
        assertTrue(DnsWireValidator.isExcludedDnsIp("127.0.0.2"))
        assertTrue(DnsWireValidator.isExcludedDnsIp("::1"))
        assertTrue(DnsWireValidator.isExcludedDnsIp("0:0:0:0:0:0:0:1"))
        assertTrue(DnsWireValidator.isExcludedDnsIp("fd00::1"))
        assertTrue(DnsWireValidator.isExcludedDnsIp("fd00:0:0:0:0:0:0:1"))
        assertFalse(DnsWireValidator.isExcludedDnsIp("1.1.1.3"))

        // Public vs non-public / reserved IPs for DoT bootstrap & custom upstreams
        assertTrue(DnsWireValidator.isNonPublicOrReservedIp(InetAddress.getByName("127.0.0.2")))
        assertTrue(DnsWireValidator.isNonPublicOrReservedIp(InetAddress.getByName("169.254.169.254")))
        assertTrue(DnsWireValidator.isNonPublicOrReservedIp(InetAddress.getByName("192.168.1.1")))
        assertTrue(DnsWireValidator.isNonPublicOrReservedIp(InetAddress.getByName("10.0.0.1")))
        assertTrue(DnsWireValidator.isNonPublicOrReservedIp(InetAddress.getByName("172.16.0.1")))
        assertTrue(DnsWireValidator.isNonPublicOrReservedIp(InetAddress.getByName("100.64.0.1")))
        assertTrue(DnsWireValidator.isNonPublicOrReservedIp(InetAddress.getByName("224.0.0.1")))
        assertTrue(DnsWireValidator.isNonPublicOrReservedIp(InetAddress.getByName("0.1.2.3")))
        assertTrue(DnsWireValidator.isNonPublicOrReservedIp(InetAddress.getByName("255.255.255.255")))
        assertFalse(DnsWireValidator.isNonPublicOrReservedIp(InetAddress.getByName("1.1.1.3")))
        assertFalse(DnsWireValidator.isNonPublicOrReservedIp(InetAddress.getByName("76.76.2.11")))

        // Physical router DNS permits RFC 1918 router IP but rejects loopback & TUN IPs
        assertFalse(DnsWireValidator.isDisallowedPhysicalDnsIp(InetAddress.getByName("192.168.1.1")))
        assertTrue(DnsWireValidator.isDisallowedPhysicalDnsIp(InetAddress.getByName("127.0.0.2")))
        assertTrue(DnsWireValidator.isDisallowedPhysicalDnsIp(InetAddress.getByName("10.255.255.2")))
    }

    @Test
    fun testParseIpv4RejectsSignedOctetsAndMalformedInputs() {
        assertNotNull(DnsWireValidator.parseIpv4ToInetAddress("1.1.1.3"))
        assertNull(DnsWireValidator.parseIpv4ToInetAddress("+1.2.3.4"))
        assertNull(DnsWireValidator.parseIpv4ToInetAddress("-0.0.0.0"))
        assertNull(DnsWireValidator.parseIpv4ToInetAddress("256.1.1.1"))
        assertNull(DnsWireValidator.parseIpv4ToInetAddress("1.2.3"))
    }

    @Test
    fun testLocalIntranetAndCaptivePortalProbeDomainAllowlist() {
        assertTrue(DnsWireValidator.isLocalIntranetDomain("router"))
        assertTrue(DnsWireValidator.isLocalIntranetDomain("printer.local"))
        assertTrue(DnsWireValidator.isLocalIntranetDomain("nas.lan"))
        assertTrue(DnsWireValidator.isLocalIntranetDomain("gateway.home.arpa."))
        assertFalse(DnsWireValidator.isLocalIntranetDomain("example.com"))
        assertFalse(DnsWireValidator.isLocalIntranetDomain(""))

        assertTrue(DnsWireValidator.isKnownCaptivePortalProbeDomain("connectivitycheck.gstatic.com"))
        assertTrue(DnsWireValidator.isKnownCaptivePortalProbeDomain("CAPTIVE.APPLE.COM."))
        assertTrue(DnsWireValidator.isKnownCaptivePortalProbeDomain("detectportal.firefox.com"))
        assertFalse(DnsWireValidator.isKnownCaptivePortalProbeDomain("example.com"))
        assertFalse(DnsWireValidator.isKnownCaptivePortalProbeDomain("blocked-adult-site.example"))
    }

    @Test
    fun testValidDnsResponseEnforcesQrOpcodeTxIdAndQuestionMatch() {
        val req = requireNotNull(DotUpstreamClient.buildMinimalAQuery("example.com", 0x1234.toShort()))
        val validReply = buildAnswer(txId = 0x1234.toShort(), domain = "ExAmPlE.CoM", ttlSeconds = 60L)
        assertTrue("Case-insensitive QNAME match must pass RFC 5452", DnsWireValidator.isValidDnsResponse(validReply, validReply.size, req))

        // Truncated (< 12 bytes)
        assertFalse(DnsWireValidator.isValidDnsResponse(ByteArray(2), 2, req))

        // QR=0 (query, not response)
        val queryNotResp = validReply.copyOf().apply { this[2] = (this[2].toInt() and 0x7F).toByte() }
        assertFalse(DnsWireValidator.isValidDnsResponse(queryNotResp, queryNotResp.size, req))

        // Non-zero OPCODE
        val badOpcode = validReply.copyOf().apply { this[2] = (this[2].toInt() or 0x08).toByte() }
        assertFalse("Non-zero OPCODE must be rejected", DnsWireValidator.isValidDnsResponse(badOpcode, badOpcode.size, req))

        // Transaction ID mismatch
        val wrongTx = buildAnswer(txId = 0x5678.toShort(), domain = "example.com", ttlSeconds = 60L)
        assertFalse(DnsWireValidator.isValidDnsResponse(wrongTx, wrongTx.size, req))

        // Spoofed QNAME with matching TXID must be rejected
        val spoofedDomain = buildAnswer(txId = 0x1234.toShort(), domain = "attacker.com", ttlSeconds = 60L)
        assertFalse("Mismatched QNAME must be rejected to prevent cache poisoning", DnsWireValidator.isValidDnsResponse(spoofedDomain, spoofedDomain.size, req))

        // Spoofed QTYPE (AAAA instead of A) must be rejected
        val spoofedQType = buildAnswer(txId = 0x1234.toShort(), domain = "example.com", ttlSeconds = 60L, qType = 28)
        assertFalse("Mismatched QTYPE must be rejected", DnsWireValidator.isValidDnsResponse(spoofedQType, spoofedQType.size, req))

        // QDCOUNT=0 reply for QDCOUNT=1 request must be rejected
        val zeroQd = ByteArray(12).apply {
            this[0] = 0x12; this[1] = 0x34
            this[2] = 0x81.toByte(); this[3] = 0x80.toByte()
        }
        assertFalse("QDCOUNT=0 reply must be rejected", DnsWireValidator.isValidDnsResponse(zeroQd, zeroQd.size, req))
    }

    @Test
    fun testExtractTtlTakesMinimumAcrossAllAnswersAndReturnsZeroOnMalformed() {
        assertEquals(45L, DnsWireValidator.extractTtl(buildAnswer(0x1111.toShort(), "example.com", 45L)))
        assertEquals(10L, DnsWireValidator.extractTtl(buildAnswer(0x1111.toShort(), "example.com", 3L)))
        assertEquals(120L, DnsWireValidator.extractTtl(buildAnswer(0x1111.toShort(), "example.com", 3600L)))
        assertEquals(0L, DnsWireValidator.extractTtl(buildAnswer(0x1111.toShort(), "example.com", 0L)))
        assertEquals(0L, DnsWireValidator.extractTtl(buildAnswer(0x1111.toShort(), "example.com", 0x80000005L)))

        // Multi-answer: CNAME (3600s) + A (15s) -> must return 15L (not 120L!)
        val multi = buildTwoAnswerPayload(ttl1 = 3600L, ttl2 = 15L)
        assertEquals(15L, DnsWireValidator.extractTtl(multi))

        // Multi-answer where 2nd answer has TTL=0 -> uncacheable (0L)
        val multiZero = buildTwoAnswerPayload(ttl1 = 3600L, ttl2 = 0L)
        assertEquals(0L, DnsWireValidator.extractTtl(multiZero))

        // Malformed truncated answer section must return 0L (never 60L!)
        val malformed = ByteArray(20).apply {
            this[2] = 0x81.toByte(); this[3] = 0x80.toByte()
            this[5] = 1 // QDCOUNT=1
            this[7] = 1 // ANCOUNT=1
            this[12] = 0x40.toByte() // invalid label
        }
        assertEquals("Malformed packet must return 0L TTL, not 60L", 0L, DnsWireValidator.extractTtl(malformed))
    }

    @Test
    fun testIsCacheableRejectsSmallEdnsExtendedRcodeBadVers() {
        val base = buildAnswer(0x2222.toShort(), "example.com", 60L)
        // Append small 11-byte OPT with EXTENDED-RCODE=1 (BADVERS=16) while total size << MTU
        val optBadVers = byteArrayOf(0, 0, 41, 0x04, 0xD0.toByte(), 0x01, 0, 0, 0, 0, 0)
        val packet = ByteArray(base.size + optBadVers.size)
        System.arraycopy(base, 0, packet, 0, base.size)
        packet[10] = 0; packet[11] = 1 // ARCOUNT=1
        System.arraycopy(optBadVers, 0, packet, base.size, optBadVers.size)

        assertFalse("Small BADVERS (EDNS Extended RCODE) response must NEVER be cacheable", DnsWireValidator.isCacheableDnsResponse(packet))
    }

    @Test
    fun testClampEdnsAndMtuTrimming() {
        val base = buildAnswer(0x8601.toShort(), "example.com", 60L)
        val optWithBadVers = ByteArray(1500)
        optWithBadVers[0] = 0
        optWithBadVers[1] = 0; optWithBadVers[2] = 41
        optWithBadVers[3] = 0x04; optWithBadVers[4] = 0xD0.toByte()
        optWithBadVers[5] = 0x01
        val rdLen = optWithBadVers.size - 11
        optWithBadVers[9] = ((rdLen ushr 8) and 0xFF).toByte()
        optWithBadVers[10] = (rdLen and 0xFF).toByte()
        val withBadVers = ByteArray(base.size + optWithBadVers.size)
        System.arraycopy(base, 0, withBadVers, 0, base.size)
        withBadVers[10] = 0; withBadVers[11] = 1
        System.arraycopy(optWithBadVers, 0, withBadVers, base.size, optWithBadVers.size)

        val fitted = DnsWireValidator.fitDnsResponseToMtu(withBadVers, DnsWireValidator.MAX_IPV4_UDP_DNS_PAYLOAD)
        assertEquals(0x02, fitted[2].toInt() and 0x02) // TC=1
        assertFalse(DnsWireValidator.isCacheableDnsResponse(fitted))
    }

    private fun buildAnswer(txId: Short, domain: String, ttlSeconds: Long, qType: Int = 1): ByteArray {
        val labels = domain.split('.')
        var qLen = 1
        for (l in labels) qLen += 1 + l.length
        val out = ByteArray(12 + qLen + 4 + 2 + 10 + 4)
        val buf = ByteBuffer.wrap(out)
        buf.putShort(txId)
        buf.putShort(0x8180.toShort())
        buf.putShort(1.toShort())
        buf.putShort(1.toShort())
        buf.putShort(0.toShort())
        buf.putShort(0.toShort())
        for (l in labels) {
            buf.put(l.length.toByte())
            buf.put(l.toByteArray(Charsets.US_ASCII))
        }
        buf.put(0.toByte())
        buf.putShort(qType.toShort())
        buf.putShort(1.toShort())
        buf.putShort(0xC00C.toShort())
        buf.putShort(1.toShort())
        buf.putShort(1.toShort())
        buf.putInt(ttlSeconds.toInt())
        buf.putShort(4.toShort())
        buf.put(byteArrayOf(93.toByte(), 184.toByte(), 216.toByte(), 34.toByte()))
        return out
    }

    private fun buildTwoAnswerPayload(ttl1: Long, ttl2: Long): ByteArray {
        val qName = byteArrayOf(7, 'e'.code.toByte(), 'x'.code.toByte(), 'a'.code.toByte(), 'm'.code.toByte(), 'p'.code.toByte(), 'l'.code.toByte(), 'e'.code.toByte(), 3, 'c'.code.toByte(), 'o'.code.toByte(), 'm'.code.toByte(), 0)
        val out = ByteArray(12 + qName.size + 4 + (2 + 10 + 4) * 2)
        val buf = ByteBuffer.wrap(out)
        buf.putShort(0x3333.toShort())
        buf.putShort(0x8180.toShort())
        buf.putShort(1.toShort())
        buf.putShort(2.toShort()) // ANCOUNT=2
        buf.putShort(0.toShort())
        buf.putShort(0.toShort())
        buf.put(qName)
        buf.putShort(1.toShort())
        buf.putShort(1.toShort())
        // Answer 1
        buf.putShort(0xC00C.toShort()); buf.putShort(1.toShort()); buf.putShort(1.toShort())
        buf.putInt(ttl1.toInt()); buf.putShort(4.toShort()); buf.put(byteArrayOf(1, 2, 3, 4))
        // Answer 2
        buf.putShort(0xC00C.toShort()); buf.putShort(1.toShort()); buf.putShort(1.toShort())
        buf.putInt(ttl2.toInt()); buf.putShort(4.toShort()); buf.put(byteArrayOf(5, 6, 7, 8))
        return out
    }
}
