package com.drmohammed.antiporn.core.network.dns

import android.net.VpnService
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class DohUpstreamClientTest {

    private val fakeVpnService = object : VpnService() {}

    private fun buildFakeDnsResponse(txId: Short = 0x1234.toShort()): ByteArray {
        val bytes = ByteArray(32)
        bytes[0] = ((txId.toInt() ushr 8) and 0xFF).toByte()
        bytes[1] = (txId.toInt() and 0xFF).toByte()
        bytes[2] = 0x81.toByte() // QR=1, RD=1
        bytes[3] = 0x80.toByte() // RA=1, RCODE=0 (NOERROR)
        bytes[4] = 0x00.toByte()
        bytes[5] = 0x01.toByte() // QDCOUNT = 1
        bytes[6] = 0x00.toByte()
        bytes[7] = 0x01.toByte() // ANCOUNT = 1
        return bytes
    }

    private fun buildFakeDnsQuery(txId: Short = 0x1234.toShort()): ByteArray {
        val bytes = ByteArray(28)
        bytes[0] = ((txId.toInt() ushr 8) and 0xFF).toByte()
        bytes[1] = (txId.toInt() and 0xFF).toByte()
        bytes[2] = 0x01.toByte() // RD=1
        bytes[3] = 0x00.toByte()
        bytes[4] = 0x00.toByte()
        bytes[5] = 0x01.toByte() // QDCOUNT = 1
        return bytes
    }

    @Test
    fun testDohUpstreamClient_successfulQuery_returnsValidResponse() = runBlocking {
        val query = buildFakeDnsQuery()
        val expectedReply = buildFakeDnsResponse()
        val visitedEndpoints = mutableListOf<String>()

        val client = DohUpstreamClient(
            vpnService = fakeVpnService,
            clockMs = { 10_000L },
            exchangeExecutor = { endpoint, payload, _ ->
                visitedEndpoints.add(endpoint.ip)
                assertEquals(query.size, payload.size)
                expectedReply
            }
        )

        val reply = client.query(query, timeoutMs = 1200)
        assertNotNull(reply)
        assertEquals(expectedReply.size, reply?.size)
        assertTrue(visitedEndpoints.isNotEmpty())
        assertFalse(client.isCircuitOpen(DohUpstreamClient.CLOUDFLARE_FAMILY_PRIMARY))
    }

    @Test
    fun testDohUpstreamClient_circuitBreakerTripsAfterThreeFailures() = runBlocking {
        var nowMs = 10_000L
        val query = buildFakeDnsQuery()

        val client = DohUpstreamClient(
            vpnService = fakeVpnService,
            clockMs = { nowMs },
            exchangeExecutor = { endpoint, _, _ ->
                throw IOException("Connection refused for ${endpoint.ip}")
            }
        )

        // Run queries that fail
        for (i in 1..3) {
            val reply = client.query(query, timeoutMs = 400)
            assertNull(reply)
        }

        // Circuit breaker should now be open for endpoints
        assertTrue(client.isCircuitOpen(DohUpstreamClient.CLOUDFLARE_FAMILY_PRIMARY))

        // On network changed, circuit breakers must reset cleanly
        client.onNetworkChanged()
        assertFalse(client.isCircuitOpen(DohUpstreamClient.CLOUDFLARE_FAMILY_PRIMARY))
    }

    @Test
    fun testDohUpstreamClient_prioritizesConfiguredHostEndpoints() = runBlocking {
        val query = buildFakeDnsQuery()
        val queriedIps = mutableListOf<String>()

        val client = DohUpstreamClient(
            vpnService = fakeVpnService,
            clockMs = { 10_000L },
            exchangeExecutor = { endpoint, _, _ ->
                queriedIps.add(endpoint.ip)
                buildFakeDnsResponse()
            }
        )

        client.query(query, timeoutMs = 1000, preferredHost = "family.adguard-dns.com")
        // AdGuard Primary (94.140.14.15) must be prioritized first
        assertEquals(DohUpstreamClient.ADGUARD_FAMILY_PRIMARY.ip, queriedIps.firstOrNull())
    }

    @Test
    fun testNextDnsEndpoints_simpleProfile_generatesCorrectEndpointAndPath() {
        val client = DohUpstreamClient(fakeVpnService)
        val endpoints = client.getPrioritizedEndpoints("1bb7e3.dns.nextdns.io")
        assertTrue(endpoints.isNotEmpty())
        val first = endpoints.first()
        assertEquals("45.90.28.0", first.ip)
        assertEquals("dns.nextdns.io", first.host)
        assertEquals("/1bb7e3", first.path)
    }

    @Test
    fun testNextDnsEndpoints_profileWithDeviceLabel_generatesProfileAndDevicePath() {
        val client = DohUpstreamClient(fakeVpnService)
        val endpoints = client.getPrioritizedEndpoints("phone-1bb7e3.dns.nextdns.io")
        assertTrue(endpoints.isNotEmpty())
        val first = endpoints.first()
        assertEquals("45.90.28.0", first.ip)
        assertEquals("dns.nextdns.io", first.host)
        assertEquals("/1bb7e3/phone", first.path)
    }

    @Test
    fun testControlDFreeDnsEndpoints_slug_generatesCorrectEndpointAndPath() {
        val client = DohUpstreamClient(fakeVpnService)
        val endpoints = client.getPrioritizedEndpoints("no-ads-porn.freedns.controld.com")
        assertTrue(endpoints.isNotEmpty())
        val first = endpoints.first()
        assertEquals("76.76.2.11", first.ip)
        assertEquals("freedns.controld.com", first.host)
        assertEquals("/no-ads-porn", first.path)
    }

    @Test
    fun testResolveBootstrapCandidates_nextDns_returnsBothV4AndV6() {
        val candidates = DnsResilienceProbeClient.resolveBootstrapCandidates(
            "1bb7e3.dns.nextdns.io",
            LinkIpFamilies(hasIpv4 = true, hasIpv6 = true)
        )
        assertTrue(candidates.contains("45.90.28.0"))
        assertTrue(candidates.contains("2a07:a8c0::"))
    }

    @Test
    fun testDohUpstreamClient_ipv6OnlyLink_routesToIpv6Endpoints() = runBlocking {
        val query = buildFakeDnsQuery()
        val queriedIps = mutableListOf<String>()

        val client = DohUpstreamClient(
            vpnService = fakeVpnService,
            clockMs = { 10_000L },
            exchangeExecutor = { endpoint, _, _ ->
                queriedIps.add(endpoint.ip)
                buildFakeDnsResponse()
            }
        )

        client.query(
            rawPayload = query,
            timeoutMs = 1000,
            preferredHost = "1bb7e3.dns.nextdns.io",
            families = LinkIpFamilies(hasIpv4 = false, hasIpv6 = true)
        )
        assertEquals("2a07:a8c0::", queriedIps.firstOrNull())
    }
}
