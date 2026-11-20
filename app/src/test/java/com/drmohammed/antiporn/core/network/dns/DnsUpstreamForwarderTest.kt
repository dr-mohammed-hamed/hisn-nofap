package com.drmohammed.antiporn.core.network.dns

import android.net.VpnService
import com.drmohammed.antiporn.core.model.DevicePolicyBundle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class DnsUpstreamForwarderTest {

    @Test
    fun testForwarderPortStrippingCacheGuardAndServfailRefusedFailover() = runBlocking {
        assertEquals("127.0.0.1", DnsWireValidator.normalizeCustomHost("127.0.0.1:53"))
        assertTrue(DnsWireValidator.isExcludedDnsIp("127.0.0.1:53"))
        assertTrue(DnsWireValidator.isExcludedDnsIp("fd00::2"))

        val noErrorReply = buildDnsAnswerPayload(txId = 0x1001.toShort(), ttlSeconds = 60L, flags = 0x8180.toShort())
        val nxDomainReply = buildDnsAnswerPayload(txId = 0x1001.toShort(), ttlSeconds = 60L, flags = 0x8183.toShort())
        val truncatedReply = buildDnsAnswerPayload(txId = 0x1001.toShort(), ttlSeconds = 60L, flags = 0x8380.toShort())
        val servfailReply = buildDnsAnswerPayload(txId = 0x1001.toShort(), ttlSeconds = 60L, flags = 0x8182.toShort())
        val refusedReply = buildDnsAnswerPayload(txId = 0x1001.toShort(), ttlSeconds = 60L, flags = 0x8185.toShort())
        val formerrReply = buildDnsAnswerPayload(txId = 0x1001.toShort(), ttlSeconds = 60L, flags = 0x8181.toShort())

        assertTrue(DnsWireValidator.isCacheableDnsResponse(noErrorReply))
        assertTrue(DnsWireValidator.isCacheableDnsResponse(nxDomainReply))
        assertFalse(DnsWireValidator.isCacheableDnsResponse(truncatedReply))
        assertFalse(DnsWireValidator.isCacheableDnsResponse(servfailReply))
        assertFalse(DnsWireValidator.isCacheableDnsResponse(refusedReply))
        assertFalse(DnsWireValidator.isCacheableDnsResponse(formerrReply))

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
        assertEquals(0, resolved[3].toInt() and 0x0F)
        assertEquals(
            listOf(
                "dot:${DevicePolicyBundle.CLOUDFLARE_FAMILY_DNS_HOST}",
                "udp:${DnsUpstreamForwarder.PRIMARY_SAFE_DNS_IPV4}",
                "udp:${DnsUpstreamForwarder.SECONDARY_SAFE_DNS_IPV4}"
            ),
            calls
        )
        assertNotNull(cache.get("example.com", 1))
    }

    @Test
    fun testCustomDnsHostRoutingSelfBootstrapGuardTier2FailoverAndStaleQueryDiscard() = runBlocking {
        val calls = mutableListOf<String>()
        val noErrorReply = buildDnsAnswerPayload(txId = 0x2001.toShort(), ttlSeconds = 60L)
        val forwarder = DnsUpstreamForwarder(
            vpnService = object : VpnService() {},
            lruCache = DnsLruCache(16),
            dotQueryExecutor = { host, _, _ ->
                calls.add("dot:$host")
                null
            },
            udpQueryExecutor = { ip, _, _ ->
                calls.add("udp:$ip")
                if (ip == DnsUpstreamForwarder.OPENDNS_FAMILY_PRIMARY) noErrorReply else null
            }
        )

        // 1. Custom DoT host -> tries custom DoT, then Tier 1 Cloudflare UDP, then Tier 2 OpenDNS FamilyShield
        forwarder.setCustomDnsHost("custom-profile.dns.nextdns.io")
        val q = buildMinimalQuery(0x2001.toShort())
        val res = requireNotNull(forwarder.resolve("example.com", 1, 0x2001.toShort(), q))
        assertEquals(0, res[3].toInt() and 0x0F)
        assertEquals(
            listOf(
                "dot:custom-profile.dns.nextdns.io",
                "udp:${DnsUpstreamForwarder.PRIMARY_SAFE_DNS_IPV4}",
                "udp:${DnsUpstreamForwarder.SECONDARY_SAFE_DNS_IPV4}",
                "udp:${DnsUpstreamForwarder.OPENDNS_FAMILY_PRIMARY}"
            ),
            calls
        )

        // 2. Self-bootstrap anti-recursion guard: querying the custom DoT host itself must skip custom DoT
        calls.clear()
        val selfQuery = requireNotNull(DotUpstreamClient.buildMinimalAQuery("custom-profile.dns.nextdns.io", 0x2002.toShort()))
        forwarder.resolve("custom-profile.dns.nextdns.io", 1, 0x2002.toShort(), selfQuery)
        assertFalse("Self-bootstrap query must skip custom DoT to prevent recursion", calls.contains("dot:custom-profile.dns.nextdns.io"))

        // 3. Private/loopback custom numeric IPv4 must be rejected (never dialed)
        calls.clear()
        forwarder.setCustomDnsHost("192.168.1.1")
        forwarder.resolve("example.org", 1, 0x2003.toShort(), buildMinimalQuery(0x2003.toShort()))
        assertFalse("Private RFC 1918 custom IPv4 must not be dialed", calls.contains("udp:192.168.1.1"))

        // 4. Stale queued query exceeding maxQueryAgeMs must be discarded immediately without upstream calls
        calls.clear()
        val stale = forwarder.resolve(
            qName = "stale.example.com",
            qType = 1,
            transactionId = 0x2004.toShort(),
            rawDnsPayload = buildMinimalQuery(0x2004.toShort()),
            enqueuedAtMs = 1L,
            maxQueryAgeMs = 1L
        )
        assertNull("Stale query must be dropped before hitting upstream", stale)
        assertTrue("No upstream queries should run for stale query", calls.isEmpty())
    }

    @Test
    fun testForwarderCacheHitIpv4ToIpv6MtuGuardAndEdnsClamping() = runBlocking {
        val cache = DnsLruCache(16)
        val observedUpstreamSizes = mutableListOf<Int>()
        val oversized = buildMultiSectionResponse(
            txId = 0x3001.toShort(),
            answerRdLen = 400,
            authorityRdLen = 400,
            additionalRdLen = 600 // Total ~1468B (> 1452 IPv6 MTU, <= 1472 IPv4 MTU)
        )
        assertTrue(oversized.size in (DnsWireValidator.MAX_IPV6_UDP_DNS_PAYLOAD + 1)..DnsWireValidator.MAX_IPV4_UDP_DNS_PAYLOAD)

        val forwarder = DnsUpstreamForwarder(
            vpnService = object : VpnService() {},
            lruCache = cache,
            dotQueryExecutor = { _, rawPayload, _ ->
                val optOffset = rawPayload.size - 11
                observedUpstreamSizes.add(((rawPayload[optOffset + 3].toInt() and 0xFF) shl 8) or (rawPayload[optOffset + 4].toInt() and 0xFF))
                oversized.copyOf().apply { this[0] = rawPayload[0]; this[1] = rawPayload[1] }
            },
            udpQueryExecutor = { _, _, _ -> null }
        )

        val queryWith4096Edns = buildQueryWithEdnsOpt(0x3001.toShort(), 4096)
        val ipv4Result = requireNotNull(
            forwarder.resolve("edns.example.com", 16, 0x3001.toShort(), queryWith4096Edns, maxResponsePayloadBytes = DnsWireValidator.MAX_IPV4_UDP_DNS_PAYLOAD)
        )
        assertEquals(listOf(DnsWireValidator.EDNS0_SAFE_UDP_PAYLOAD_SIZE), observedUpstreamSizes)
        assertEquals(oversized.size, ipv4Result.size)

        val ipv6Result = requireNotNull(
            forwarder.resolve("edns.example.com", 16, 0x3002.toShort(), queryWith4096Edns, maxResponsePayloadBytes = DnsWireValidator.MAX_IPV6_UDP_DNS_PAYLOAD)
        )
        assertEquals(1, observedUpstreamSizes.size) // Cache hit!
        assertTrue(ipv6Result.size <= DnsWireValidator.MAX_IPV6_UDP_DNS_PAYLOAD)
        assertEquals(0, ipv6Result[2].toInt() and 0x02) // TC=0
    }

    @Test
    fun testForwarderOnNetworkChangedFencesInFlightCacheWritesAndClearResponseCache() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val udpCalls = AtomicInteger(0)
        val cache = DnsLruCache(64)

        val forwarder = DnsUpstreamForwarder(
            vpnService = object : VpnService() {},
            lruCache = cache,
            dotQueryExecutor = { _, _, _ -> null },
            udpQueryExecutor = { _, rawPayload, _ ->
                val callNum = udpCalls.incrementAndGet()
                val txId = (((rawPayload[0].toInt() and 0xFF) shl 8) or (rawPayload[1].toInt() and 0xFF)).toShort()
                if (callNum == 1) {
                    entered.countDown()
                    release.await(3, TimeUnit.SECONDS)
                }
                buildDnsAnswerPayload(txId = txId, ttlSeconds = 120L)
            }
        )

        val q1 = buildMinimalQuery(0x8701.toShort())
        val inFlight = async(Dispatchers.IO) {
            forwarder.resolve("stale-handoff.example.com", 1, 0x8701.toShort(), q1)
        }
        assertTrue(entered.await(3, TimeUnit.SECONDS))
        forwarder.onNetworkChanged()
        release.countDown()
        assertNotNull(inFlight.await())

        assertNull("Pre-handoff in-flight UDP response must be fenced from lruCache", cache.get("stale-handoff.example.com", 1))

        val q2 = buildMinimalQuery(0x8702.toShort())
        assertNotNull(forwarder.resolve("fresh.example.com", 1, 0x8702.toShort(), q2))
        assertNotNull(cache.get("fresh.example.com", 1))
        forwarder.clearResponseCache()
        assertNull(cache.get("fresh.example.com", 1))
    }

    private fun buildMinimalQuery(txId: Short): ByteArray =
        requireNotNull(DotUpstreamClient.buildMinimalAQuery("example.com", txId))

    private fun buildQueryWithEdnsOpt(txId: Short, advertisedUdpSize: Int): ByteArray {
        val base = buildMinimalQuery(txId)
        val out = ByteArray(base.size + 11)
        System.arraycopy(base, 0, out, 0, base.size)
        out[10] = 0; out[11] = 1
        val optOffset = base.size
        out[optOffset] = 0; out[optOffset + 1] = 0; out[optOffset + 2] = 41
        out[optOffset + 3] = ((advertisedUdpSize ushr 8) and 0xFF).toByte()
        out[optOffset + 4] = (advertisedUdpSize and 0xFF).toByte()
        return out
    }

    private fun buildMultiSectionResponse(txId: Short, answerRdLen: Int, authorityRdLen: Int, additionalRdLen: Int): ByteArray {
        val qName = byteArrayOf(7, 'e'.code.toByte(), 'x'.code.toByte(), 'a'.code.toByte(), 'm'.code.toByte(), 'p'.code.toByte(), 'l'.code.toByte(), 'e'.code.toByte(), 3, 'c'.code.toByte(), 'o'.code.toByte(), 'm'.code.toByte(), 0)
        val total = 12 + qName.size + 4 + (2 + 10 + answerRdLen) + (2 + 10 + authorityRdLen) + (2 + 10 + additionalRdLen)
        val out = ByteArray(total)
        val buf = ByteBuffer.wrap(out)
        buf.putShort(txId); buf.putShort(0x8180.toShort())
        buf.putShort(1); buf.putShort(1); buf.putShort(1); buf.putShort(1)
        buf.put(qName); buf.putShort(16); buf.putShort(1)
        fun putTxt(rdLen: Int) {
            buf.putShort(0xC00C.toShort()); buf.putShort(16); buf.putShort(1); buf.putInt(60); buf.putShort(rdLen.toShort())
            buf.put(ByteArray(rdLen) { 0x41 })
        }
        putTxt(answerRdLen); putTxt(authorityRdLen); putTxt(additionalRdLen)
        return out
    }

    @Test
    fun testForwarder_whenInLocalVpnFallback_skipsDotAndQueriesDohDirectly() = runBlocking {
        val calls = mutableListOf<String>()
        val noErrorReply = buildDnsAnswerPayload(txId = 0x3001.toShort(), ttlSeconds = 60L)
        val forwarder = DnsUpstreamForwarder(
            vpnService = object : VpnService() {},
            lruCache = DnsLruCache(16),
            dotQueryExecutor = { host, _, _ ->
                calls.add("dot:$host")
                null
            },
            dohQueryExecutor = { _, _ ->
                calls.add("doh:443")
                noErrorReply
            },
            udpQueryExecutor = { ip, _, _ ->
                calls.add("udp:$ip")
                null
            }
        )

        // Mock resilience state into FALLBACK_LOCAL_VPN_DOH
        val stateLockField = DnsResilienceManager.javaClass.getDeclaredField("stateLock")
        stateLockField.isAccessible = true
        val lock = stateLockField.get(DnsResilienceManager)
        val activeModeField = DnsResilienceManager.javaClass.getDeclaredField("activeMode")
        activeModeField.isAccessible = true
        synchronized(lock) {
            activeModeField.set(DnsResilienceManager, DnsResilienceManager.DnsResilienceMode.FALLBACK_LOCAL_VPN_DOH)
        }

        try {
            val query = buildMinimalQuery(0x3001.toShort())
            val resolved = requireNotNull(forwarder.resolve("example.com", 1, 0x3001.toShort(), query))
            assertEquals(0, resolved[3].toInt() and 0x0F)
            // Dot must be completely SKIPPED! Doh must be called directly!
            assertEquals(listOf("doh:443"), calls)
        } finally {
            synchronized(lock) {
                activeModeField.set(DnsResilienceManager, DnsResilienceManager.DnsResilienceMode.PRIMARY_STRICT_DOT)
            }
        }
    }

    private fun buildDnsAnswerPayload(
        txId: Short,
        ttlSeconds: Long,
        ipv4: ByteArray = byteArrayOf(93.toByte(), 184.toByte(), 216.toByte(), 34.toByte()),
        flags: Short = 0x8180.toShort()
    ): ByteArray {
        val qName = byteArrayOf(7, 'e'.code.toByte(), 'x'.code.toByte(), 'a'.code.toByte(), 'm'.code.toByte(), 'p'.code.toByte(), 'l'.code.toByte(), 'e'.code.toByte(), 3, 'c'.code.toByte(), 'o'.code.toByte(), 'm'.code.toByte(), 0)
        val out = ByteArray(12 + qName.size + 4 + 2 + 10 + 4)
        val buf = ByteBuffer.wrap(out)
        buf.putShort(txId); buf.putShort(flags)
        buf.putShort(1); buf.putShort(1); buf.putShort(0); buf.putShort(0)
        buf.put(qName); buf.putShort(1); buf.putShort(1)
        buf.putShort(0xC00C.toShort()); buf.putShort(1); buf.putShort(1)
        buf.putInt(ttlSeconds.toInt()); buf.putShort(4); buf.put(ipv4)
        return out
    }
}
