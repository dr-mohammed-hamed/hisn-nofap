package com.drmohammed.antiporn.core.network.dns

import android.net.VpnService
import com.drmohammed.antiporn.core.model.DevicePolicyBundle
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicLong

class DnsUpstreamForwarderTest {

    @Test
    fun testExcludedDnsIpsIncludeActiveTunAndLoopback() {
        assertTrue(DnsWireValidator.isExcludedDnsIp("10.255.255.1"))
        assertTrue(DnsWireValidator.isExcludedDnsIp("10.255.255.2"))
        assertTrue(DnsWireValidator.isExcludedDnsIp("127.0.0.1"))
        assertTrue(DnsWireValidator.isExcludedDnsIp("::1"))
        assertTrue(DnsWireValidator.isExcludedDnsIp("fd00::1"))
        assertFalse(DnsWireValidator.isExcludedDnsIp("1.1.1.3"))
    }

    @Test
    fun testLocalIntranetDomainDetection() {
        assertTrue(DnsWireValidator.isLocalIntranetDomain("router"))
        assertTrue(DnsWireValidator.isLocalIntranetDomain("printer.local"))
        assertTrue(DnsWireValidator.isLocalIntranetDomain("nas.lan"))
        assertTrue(DnsWireValidator.isLocalIntranetDomain("gateway.home.arpa."))
        assertFalse(DnsWireValidator.isLocalIntranetDomain("example.com"))
        assertFalse(DnsWireValidator.isLocalIntranetDomain(""))
    }

    @Test
    fun testValidDnsResponseRejectsTruncatedNonResponseAndTxMismatch() {
        val req = buildMinimalQuery(0x1234.toShort())
        val validReply = buildDnsAnswerPayload(txId = 0x1234.toShort(), ttlSeconds = 60L)
        assertTrue(DnsWireValidator.isValidDnsResponse(validReply, validReply.size, req))

        // Truncated (< 12 bytes)
        assertFalse(DnsWireValidator.isValidDnsResponse(ByteArray(2), 2, req))

        // QR=0 (query, not response)
        val queryNotResp = validReply.copyOf().apply { this[2] = (this[2].toInt() and 0x7F).toByte() }
        assertFalse(DnsWireValidator.isValidDnsResponse(queryNotResp, queryNotResp.size, req))

        // Transaction ID mismatch
        val wrongTx = buildDnsAnswerPayload(txId = 0x5678.toShort(), ttlSeconds = 60L)
        assertFalse(DnsWireValidator.isValidDnsResponse(wrongTx, wrongTx.size, req))
    }

    @Test
    fun testExtractTtlRespectsZeroAndRfc2181Msb() {
        // Normal TTL clamped to [10, 120]
        assertEquals(45L, DnsWireValidator.extractTtl(buildDnsAnswerPayload(0x1111.toShort(), 45L)))
        assertEquals(10L, DnsWireValidator.extractTtl(buildDnsAnswerPayload(0x1111.toShort(), 3L)))
        assertEquals(120L, DnsWireValidator.extractTtl(buildDnsAnswerPayload(0x1111.toShort(), 3600L)))

        // TTL == 0 must return 0 (do not cache)
        assertEquals(0L, DnsWireValidator.extractTtl(buildDnsAnswerPayload(0x1111.toShort(), 0L)))

        // RFC 2181 §8: MSB set (> Int.MAX_VALUE) treated as 0 (do not cache)
        assertEquals(0L, DnsWireValidator.extractTtl(buildDnsAnswerPayload(0x1111.toShort(), 0x80000005L)))
    }

    @Test
    fun testDotBootstrapQueryAndIpv4AnswerExtraction() {
        val query = requireNotNull(DotUpstreamClient.buildMinimalAQuery("no-ads-porn.freedns.controld.com", 0x4242.toShort()))
        assertTrue(query.size > 16)
        assertEquals(0x42.toByte(), query[0])
        assertEquals(0x42.toByte(), query[1])

        val answer = buildDnsAnswerPayload(txId = 0x4242.toShort(), ttlSeconds = 120L, ipv4 = byteArrayOf(76, 76, 2, 11))
        val extracted = DotUpstreamClient.extractFirstIpv4FromDnsResponse(answer)
        assertNotNull(extracted)
        assertEquals("76.76.2.11", extracted?.hostAddress)

        // Malformed short buffer safely returns null
        assertNull(DotUpstreamClient.extractFirstIpv4FromDnsResponse(ByteArray(8)))
    }

    @Test
    fun testDotHostSanitizationFramingAndCircuitBreakerWithInjectedClock() {
        // Host sanitization preserves IPv6 literals and strips :port cleanly
        assertEquals("dns.example.com", DotUpstreamClient.sanitizeHost("DNS.Example.COM.:853"))
        assertEquals("127.0.0.1", DotUpstreamClient.sanitizeHost("127.0.0.1:53"))
        assertEquals("fd00::1", DotUpstreamClient.sanitizeHost("fd00::1"))
        assertEquals("2606:4700:4700::1113", DotUpstreamClient.sanitizeHost("[2606:4700:4700::1113]:853"))

        // RFC 7858 2-byte big-endian length framing
        val rawQuery = buildMinimalQuery(0x3344.toShort())
        val framed = requireNotNull(DotUpstreamClient.buildFramedQuery(rawQuery))
        assertEquals(rawQuery.size + 2, framed.size)
        val framedLen = ((framed[0].toInt() and 0xFF) shl 8) or (framed[1].toInt() and 0xFF)
        assertEquals(rawQuery.size, framedLen)
        assertArrayEquals(rawQuery, framed.copyOfRange(2, framed.size))
        assertNull("Payloads shorter than 12-byte DNS header must be rejected", DotUpstreamClient.buildFramedQuery(ByteArray(11)))

        // Monotonic clock circuit breaker: 3 failures open circuit for 30_000ms
        val fakeClock = AtomicLong(1_000L)
        var bootstrapAttempts = 0
        val client = DotUpstreamClient(
            vpnService = object : VpnService() {},
            clockMs = { fakeClock.get() }
        ) { _, _, _ ->
            bootstrapAttempts++
            null // Force bootstrap failure
        }

        assertFalse(client.isCircuitOpen())
        assertNull(client.query("custom-dot.example.org", rawQuery, 500))
        assertNull(client.query("custom-dot.example.org", rawQuery, 500))
        assertFalse(client.isCircuitOpen())
        assertNull(client.query("custom-dot.example.org", rawQuery, 500))
        assertTrue("3 consecutive failures must open DoT circuit breaker", client.isCircuitOpen())
        val attemptsAtTrip = bootstrapAttempts

        // While circuit is open, queries short-circuit without calling bootstrap
        fakeClock.addAndGet(10_000L)
        assertNull(client.query("custom-dot.example.org", rawQuery, 500))
        assertEquals(attemptsAtTrip, bootstrapAttempts)

        // After 30_000ms cooldown expires, circuit closes and retries
        fakeClock.addAndGet(20_000L)
        assertFalse(client.isCircuitOpen())
        assertNull(client.query("custom-dot.example.org", rawQuery, 500))
        assertTrue("Query must execute again after cooldown", bootstrapAttempts > attemptsAtTrip)
    }

    @Test
    fun testForwarderPortStrippingCacheGuardAndServfailRefusedFailover() = runBlocking {
        // Port stripping & excluded IP checks
        assertEquals("127.0.0.1", DnsWireValidator.normalizeCustomHost("127.0.0.1:53"))
        assertTrue(DnsWireValidator.isExcludedDnsIp("127.0.0.1:53"))
        assertTrue(DnsWireValidator.isExcludedDnsIp("fd00::2"))

        // TC=1 and non-NOERROR/NXDOMAIN RCODE must NOT be cached
        val noErrorReply = buildDnsAnswerPayload(txId = 0x1001.toShort(), ttlSeconds = 60L, flags = 0x8180.toShort())
        val nxDomainReply = buildDnsAnswerPayload(txId = 0x1001.toShort(), ttlSeconds = 60L, flags = 0x8183.toShort())
        val truncatedReply = buildDnsAnswerPayload(txId = 0x1001.toShort(), ttlSeconds = 60L, flags = 0x8380.toShort()) // TC=1
        val servfailReply = buildDnsAnswerPayload(txId = 0x1001.toShort(), ttlSeconds = 60L, flags = 0x8182.toShort())  // RCODE=2
        val refusedReply = buildDnsAnswerPayload(txId = 0x1001.toShort(), ttlSeconds = 60L, flags = 0x8185.toShort())   // RCODE=5
        val formerrReply = buildDnsAnswerPayload(txId = 0x1001.toShort(), ttlSeconds = 60L, flags = 0x8181.toShort())   // RCODE=1

        assertTrue(DnsWireValidator.isCacheableDnsResponse(noErrorReply))
        assertTrue(DnsWireValidator.isCacheableDnsResponse(nxDomainReply))
        assertFalse("TC=1 truncated response must not be cached", DnsWireValidator.isCacheableDnsResponse(truncatedReply))
        assertFalse("SERVFAIL must not be cached", DnsWireValidator.isCacheableDnsResponse(servfailReply))
        assertFalse("REFUSED must not be cached", DnsWireValidator.isCacheableDnsResponse(refusedReply))
        assertFalse("FORMERR must not be cached", DnsWireValidator.isCacheableDnsResponse(formerrReply))

        // Failover test: Default Cloudflare preset tries DoT first; SERVFAIL from DoT & primary UDP fails over to healthy secondary UDP
        val calls = mutableListOf<String>()
        val cache = DnsLruCache(16)
        val forwarder = DnsUpstreamForwarder(
            vpnService = object : VpnService() {},
            lruCache = cache,
            dotQueryExecutor = { host, _, _ ->
                calls.add("dot:$host")
                servfailReply
            },
            udpQueryExecutor = { ip, _, _ ->
                calls.add("udp:$ip")
                when (ip) {
                    DnsUpstreamForwarder.PRIMARY_SAFE_DNS_IPV4 -> refusedReply
                    DnsUpstreamForwarder.SECONDARY_SAFE_DNS_IPV4 -> noErrorReply
                    else -> null
                }
            }
        )

        val query = buildMinimalQuery(0x1001.toShort())
        val resolved = requireNotNull(forwarder.resolve("example.com", 1, 0x1001.toShort(), query))
        assertEquals(0, resolved[3].toInt() and 0x0F) // Healthy NOERROR returned
        assertEquals(
            listOf(
                "dot:${DevicePolicyBundle.CLOUDFLARE_FAMILY_DNS_HOST}",
                "udp:${DnsUpstreamForwarder.PRIMARY_SAFE_DNS_IPV4}",
                "udp:${DnsUpstreamForwarder.SECONDARY_SAFE_DNS_IPV4}"
            ),
            calls
        )
        assertNotNull("Healthy failover response must be cached", cache.get("example.com", 1))

        // If every tier returns SERVFAIL/REFUSED, best soft fallback is returned and NOT cached
        val allFailCache = DnsLruCache(16)
        val allFailForwarder = DnsUpstreamForwarder(
            vpnService = object : VpnService() {},
            lruCache = allFailCache,
            dotQueryExecutor = { _, _, _ -> servfailReply },
            udpQueryExecutor = { _, _, _ -> refusedReply }
        )
        val fallback = requireNotNull(allFailForwarder.resolve("failing.example.org", 1, 0x1001.toShort(), query))
        assertEquals(2, fallback[3].toInt() and 0x0F) // SERVFAIL fallback returned
        assertNull("Soft failure fallback must NOT be cached", allFailCache.get("failing.example.org", 1))
    }

    private fun buildMinimalQuery(txId: Short): ByteArray {
        return requireNotNull(DotUpstreamClient.buildMinimalAQuery("example.com", txId))
    }

    private fun buildDnsAnswerPayload(
        txId: Short,
        ttlSeconds: Long,
        ipv4: ByteArray = byteArrayOf(93.toByte(), 184.toByte(), 216.toByte(), 34.toByte()),
        flags: Short = 0x8180.toShort()
    ): ByteArray {
        val qName = byteArrayOf(
            7, 'e'.code.toByte(), 'x'.code.toByte(), 'a'.code.toByte(), 'm'.code.toByte(),
            'p'.code.toByte(), 'l'.code.toByte(), 'e'.code.toByte(),
            3, 'c'.code.toByte(), 'o'.code.toByte(), 'm'.code.toByte(), 0
        )
        val total = 12 + qName.size + 4 + 2 + 10 + 4
        val out = ByteArray(total)
        val buf = ByteBuffer.wrap(out)
        buf.putShort(txId)
        buf.putShort(flags)
        buf.putShort(1.toShort())      // QDCOUNT=1
        buf.putShort(1.toShort())      // ANCOUNT=1
        buf.putShort(0.toShort())
        buf.putShort(0.toShort())
        buf.put(qName)
        buf.putShort(1.toShort()) // QTYPE=A
        buf.putShort(1.toShort()) // QCLASS=IN
        buf.putShort(0xC00C.toShort()) // Pointer to QNAME
        buf.putShort(1.toShort()) // TYPE=A
        buf.putShort(1.toShort()) // CLASS=IN
        buf.putInt(ttlSeconds.toInt())
        buf.putShort(4.toShort()) // RDLENGTH=4
        buf.put(ipv4)
        return out
    }
}
