package com.drmohammed.antiporn.core.network.dns

import android.net.VpnService
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.HandshakeCompletedListener
import javax.net.ssl.SSLSession
import javax.net.ssl.SSLSocket

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
    fun testDohUpstreamClient_ipv6OnlyNetwork_prioritizesIpv6FamilyEndpoints() = runBlocking {
        val query = buildFakeDnsQuery()
        val queriedIps = mutableListOf<String>()

        val client = DohUpstreamClient(
            vpnService = fakeVpnService,
            clockMs = { 10_000L },
            linkFamiliesProvider = { LinkIpFamilies(hasIpv4 = false, hasIpv6 = true) },
            exchangeExecutor = { endpoint, _, _ ->
                queriedIps.add(endpoint.ip)
                buildFakeDnsResponse()
            }
        )

        val prioritized = client.getPrioritizedEndpoints("family.adguard-dns.com")
        assertEquals(DohUpstreamClient.ADGUARD_FAMILY_V6_PRIMARY.ip, prioritized[0].ip)
        assertEquals(DohUpstreamClient.ADGUARD_FAMILY_V6_SECONDARY.ip, prioritized[1].ip)
        assertTrue(prioritized.any { it.ip == DohUpstreamClient.CLOUDFLARE_FAMILY_V6_PRIMARY.ip })

        val reply = client.query(query, timeoutMs = 1000, preferredHost = "family.adguard-dns.com")
        assertNotNull(reply)
        assertEquals(DohUpstreamClient.ADGUARD_FAMILY_V6_PRIMARY.ip, queriedIps.firstOrNull())
    }

    @Test
    fun testDohUpstreamClient_dualStack_includesIpv6FallbacksForCloudflareAdGuardCleanBrowsing() = runBlocking {
        val query = buildFakeDnsQuery()
        val queriedIps = mutableListOf<String>()

        val client = DohUpstreamClient(
            vpnService = fakeVpnService,
            clockMs = { 10_000L },
            linkFamiliesProvider = { LinkIpFamilies(hasIpv4 = true, hasIpv6 = true) },
            exchangeExecutor = { endpoint, _, _ ->
                queriedIps.add(endpoint.ip)
                if (!endpoint.ip.contains(":")) {
                    throw IOException("Simulated IPv4 path timeout for ${endpoint.ip}")
                }
                buildFakeDnsResponse()
            }
        )

        val endpoints = client.getPrioritizedEndpoints("doh.cleanbrowsing.org")
        assertTrue(endpoints.any { it.ip == DohUpstreamClient.CLEANBROWSING_FAMILY_PRIMARY.ip })
        assertTrue(endpoints.any { it.ip == DohUpstreamClient.CLEANBROWSING_FAMILY_V6_PRIMARY.ip })
        assertTrue(endpoints.any { it.ip == DohUpstreamClient.CLOUDFLARE_FAMILY_V6_PRIMARY.ip })
        assertTrue(endpoints.any { it.ip == DohUpstreamClient.ADGUARD_FAMILY_V6_PRIMARY.ip })

        val reply = client.query(query, timeoutMs = 1000, preferredHost = "doh.cleanbrowsing.org")
        assertNotNull("Dual-stack query should seamlessly fall back to IPv6 endpoint", reply)
        assertEquals(DohUpstreamClient.CLEANBROWSING_FAMILY_V6_PRIMARY.ip, queriedIps.lastOrNull())
    }

    @Test
    fun testDohUpstreamClient_duringRestoringPrimary_doesNotTripCircuitOnEnetunreach() = runBlocking {
        val query = buildFakeDnsQuery()
        val client = DohUpstreamClient(
            vpnService = fakeVpnService,
            clockMs = { 10_000L },
            linkFamiliesProvider = { LinkIpFamilies(hasIpv4 = true, hasIpv6 = false) },
            exchangeExecutor = { _, _, _ ->
                throw java.net.ConnectException("ENETUNREACH (Network is unreachable)")
            }
        )

        val stateLockField = DnsResilienceManager.javaClass.getDeclaredField("stateLock")
        stateLockField.isAccessible = true
        val lock = requireNotNull(stateLockField.get(DnsResilienceManager))
        val restoringField = DnsResilienceManager.javaClass.getDeclaredField("isRestoringPrimary")
        restoringField.isAccessible = true

        synchronized(lock) {
            restoringField.setBoolean(DnsResilienceManager, true)
        }
        try {
            repeat(4) {
                assertNull(client.query(query, timeoutMs = 500))
            }
            for (ep in DohUpstreamClient.DEFAULT_ENDPOINTS) {
                assertFalse("DoH circuit breaker must not open for ${ep.ip} during restoring fence", client.isCircuitOpen(ep))
            }
        } finally {
            synchronized(lock) {
                restoringField.setBoolean(DnsResilienceManager, false)
            }
        }
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

    @Test
    fun testDohConnectionPool_reusesKeepAliveConnectionsAcrossQueries() = runBlocking {
        val query1 = requireNotNull(DotBootstrapResolver.buildMinimalAQuery("example.com", 0x1111))
        val query2 = requireNotNull(DotBootstrapResolver.buildMinimalAQuery("example.com", 0x2222))
        val acceptCount = AtomicInteger(0)
        val capturedHeaders = mutableListOf<String>()
        val serverDone = CountDownLatch(1)

        val server = ServerSocket(0)
        val worker = Thread {
            try {
                server.use { srv ->
                    srv.accept().use { sock ->
                        acceptCount.incrementAndGet()
                        val input = sock.getInputStream()
                        val out = sock.getOutputStream()
                        repeat(2) {
                            val (reqHeaders, dnsBody) = readHttpPostRequest(input)
                            synchronized(capturedHeaders) { capturedHeaders.add(reqHeaders) }
                            val dnsReply = buildValidReplyForQuery(dnsBody)
                            val httpResp = "HTTP/1.1 200 OK\r\n" +
                                "Content-Type: application/dns-message\r\n" +
                                "Content-Length: ${dnsReply.size}\r\n" +
                                "Connection: keep-alive\r\n\r\n"
                            out.write(httpResp.toByteArray(Charsets.US_ASCII))
                            out.write(dnsReply)
                            out.flush()
                        }
                    }
                }
            } catch (_: Exception) {
            } finally {
                serverDone.countDown()
            }
        }
        worker.isDaemon = true
        worker.start()

        val openedSockets = mutableListOf<Socket>()
        val factoryCalls = AtomicInteger(0)
        val client = DohUpstreamClient(
            vpnService = fakeVpnService,
            clockMs = { 10_000L },
            linkFamiliesProvider = { LinkIpFamilies(hasIpv4 = true, hasIpv6 = false) }
        )
        client.wireConnectionFactory = { _, timeoutMs ->
            factoryCalls.incrementAndGet()
            val raw = Socket("127.0.0.1", server.localPort).apply { soTimeout = timeoutMs }
            synchronized(openedSockets) { openedSockets.add(raw) }
            val ssl = LoopbackSslSocket(raw)
            DohSocketConnection(raw, ssl, DataOutputStream(ssl.outputStream), DataInputStream(ssl.inputStream))
        }

        val reply1 = client.query(query1, timeoutMs = 1500, preferredHost = "family.cloudflare-dns.com")
        val reply2 = client.query(query2, timeoutMs = 1500, preferredHost = "family.cloudflare-dns.com")

        assertNotNull(reply1)
        assertNotNull(reply2)
        assertEquals(0x11.toByte(), reply1!![0])
        assertEquals(0x22.toByte(), reply2!![0])
        assertEquals("Second query must reuse pooled connection", 1, factoryCalls.get())
        assertEquals("Server must accept only 1 TCP connection for both queries", 1, acceptCount.get())
        assertTrue(capturedHeaders.all { it.contains("Connection: keep-alive", ignoreCase = true) })
        assertEquals(
            1,
            client.connectionPool.idleCountForTest(
                client.connectionPool.poolKey(DohUpstreamClient.CLOUDFLARE_FAMILY_PRIMARY)
            )
        )
        client.onNetworkChanged()
        assertTrue(serverDone.await(2, TimeUnit.SECONDS))
        assertTrue("Pooled socket must close on onNetworkChanged", openedSockets.first().isClosed)
    }

    @Test
    fun testDohConnectionPool_onNetworkChanged_invalidatesGenerationAndClosesIdleSockets() = runBlocking {
        val query = requireNotNull(DotBootstrapResolver.buildMinimalAQuery("example.com", 0x3333))
        val server = ServerSocket(0)
        val serverReady = CountDownLatch(1)
        val worker = Thread {
            try {
                server.use { srv ->
                    // Connection 1
                    srv.accept().use { sock ->
                        val (_, dnsBody) = readHttpPostRequest(sock.getInputStream())
                        val dnsReply = buildValidReplyForQuery(dnsBody)
                        val httpResp = "HTTP/1.1 200 OK\r\nContent-Length: ${dnsReply.size}\r\nConnection: keep-alive\r\n\r\n"
                        sock.getOutputStream().write(httpResp.toByteArray(Charsets.US_ASCII) + dnsReply)
                        sock.getOutputStream().flush()
                        serverReady.countDown()
                        // Hold open briefly until client closes on network change
                        sock.getInputStream().read()
                    }
                    // Connection 2 after invalidation
                    srv.accept().use { sock ->
                        val (_, dnsBody) = readHttpPostRequest(sock.getInputStream())
                        val dnsReply = buildValidReplyForQuery(dnsBody)
                        val httpResp = "HTTP/1.1 200 OK\r\nContent-Length: ${dnsReply.size}\r\nConnection: close\r\n\r\n"
                        sock.getOutputStream().write(httpResp.toByteArray(Charsets.US_ASCII) + dnsReply)
                        sock.getOutputStream().flush()
                    }
                }
            } catch (_: Exception) {
            }
        }
        worker.isDaemon = true
        worker.start()

        val createdSockets = mutableListOf<Socket>()
        val client = DohUpstreamClient(
            vpnService = fakeVpnService,
            clockMs = { 10_000L },
            linkFamiliesProvider = { LinkIpFamilies(hasIpv4 = true, hasIpv6 = false) }
        )
        client.wireConnectionFactory = { _, timeoutMs ->
            val raw = Socket("127.0.0.1", server.localPort).apply { soTimeout = timeoutMs }
            synchronized(createdSockets) { createdSockets.add(raw) }
            val ssl = LoopbackSslSocket(raw)
            DohSocketConnection(raw, ssl, DataOutputStream(ssl.outputStream), DataInputStream(ssl.inputStream))
        }

        val initialGen = client.connectionPool.currentGeneration()
        assertNotNull(client.query(query, timeoutMs = 1500, preferredHost = "family.cloudflare-dns.com"))
        assertTrue(serverReady.await(2, TimeUnit.SECONDS))
        assertEquals(1, client.connectionPool.totalIdleCountForTest())

        client.onNetworkChanged()
        assertEquals(initialGen + 1, client.connectionPool.currentGeneration())
        assertEquals(0, client.connectionPool.totalIdleCountForTest())
        assertTrue("First pooled socket must be closed after onNetworkChanged()", createdSockets[0].isClosed)

        val replyAfterHandoff = client.query(query, timeoutMs = 1500, preferredHost = "family.cloudflare-dns.com")
        assertNotNull(replyAfterHandoff)
        assertEquals(2, createdSockets.size)
        // Server sent Connection: close on 2nd response -> must not be returned to pool
        assertEquals(0, client.connectionPool.totalIdleCountForTest())
    }

    @Test
    fun testDohConnectionPool_reusedSocketEof_retriesOnceOnFreshSocket() = runBlocking {
        val query1 = requireNotNull(DotBootstrapResolver.buildMinimalAQuery("example.com", 0x4444))
        val query2 = requireNotNull(DotBootstrapResolver.buildMinimalAQuery("example.com", 0x5555))
        val server = ServerSocket(0)
        val serverDone = CountDownLatch(1)

        val worker = Thread {
            try {
                server.use { srv ->
                    // Connection 1: serves query1 with keep-alive, then closes cleanly upon receiving query2 (EOF)
                    srv.accept().use { sock1 ->
                        val (_, body1) = readHttpPostRequest(sock1.getInputStream())
                        val rep1 = buildValidReplyForQuery(body1)
                        val resp1 = "HTTP/1.1 200 OK\r\nContent-Length: ${rep1.size}\r\nConnection: keep-alive\r\n\r\n"
                        sock1.getOutputStream().write(resp1.toByteArray(Charsets.US_ASCII) + rep1)
                        sock1.getOutputStream().flush()
                        // Read second request on stale pooled socket, then close immediately (EOF!)
                        readHttpPostRequest(sock1.getInputStream())
                    }
                    // Connection 2: retry on fresh socket succeeds
                    srv.accept().use { sock2 ->
                        val (_, body2) = readHttpPostRequest(sock2.getInputStream())
                        val rep2 = buildValidReplyForQuery(body2)
                        val resp2 = "HTTP/1.1 200 OK\r\nContent-Length: ${rep2.size}\r\nConnection: keep-alive\r\n\r\n"
                        sock2.getOutputStream().write(resp2.toByteArray(Charsets.US_ASCII) + rep2)
                        sock2.getOutputStream().flush()
                    }
                }
            } catch (_: Exception) {
            } finally {
                serverDone.countDown()
            }
        }
        worker.isDaemon = true
        worker.start()

        val endpointsVisited = mutableListOf<String>()
        val client = DohUpstreamClient(
            vpnService = fakeVpnService,
            clockMs = { 10_000L },
            linkFamiliesProvider = { LinkIpFamilies(hasIpv4 = true, hasIpv6 = false) }
        )
        client.wireConnectionFactory = { endpoint, timeoutMs ->
            synchronized(endpointsVisited) { endpointsVisited.add(endpoint.ip) }
            val raw = Socket("127.0.0.1", server.localPort).apply { soTimeout = timeoutMs }
            val ssl = LoopbackSslSocket(raw)
            DohSocketConnection(raw, ssl, DataOutputStream(ssl.outputStream), DataInputStream(ssl.inputStream))
        }

        val reply1 = client.query(query1, timeoutMs = 1500, preferredHost = "family.cloudflare-dns.com")
        assertNotNull(reply1)
        val reply2 = client.query(query2, timeoutMs = 1500, preferredHost = "family.cloudflare-dns.com")
        assertNotNull("Query must succeed via single fresh-socket retry after reused EOF", reply2)
        assertEquals(0x55.toByte(), reply2!![0])
        assertEquals(
            "Retry on EOF must re-dial the same primary endpoint without failing over",
            listOf(
                DohUpstreamClient.CLOUDFLARE_FAMILY_PRIMARY.ip,
                DohUpstreamClient.CLOUDFLARE_FAMILY_PRIMARY.ip
            ),
            endpointsVisited
        )
        assertFalse(client.isCircuitOpen(DohUpstreamClient.CLOUDFLARE_FAMILY_PRIMARY))
        client.onNetworkChanged()
        assertTrue(serverDone.await(2, TimeUnit.SECONDS))
    }

    @Test
    fun testDohHttpWireExchange_chunkedWithTrailers_reusesKeepAliveAndRejectsAmbiguousOrOverflowFraming() = runBlocking {
        val query1 = requireNotNull(DotBootstrapResolver.buildMinimalAQuery("example.com", 0x6666))
        val query2 = requireNotNull(DotBootstrapResolver.buildMinimalAQuery("example.com", 0x7777))
        val server = ServerSocket(0)
        val serverDone = CountDownLatch(1)
        val worker = Thread {
            try {
                server.use { srv ->
                    // Connection 1: valid chunked response with HTTP trailer, followed by reused keep-alive query
                    srv.accept().use { sock ->
                        val (_, b1) = readHttpPostRequest(sock.getInputStream())
                        val rep1 = buildValidReplyForQuery(b1)
                        val chunkHex = rep1.size.toString(16)
                        val out = sock.getOutputStream()
                        out.write(
                            ("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\nConnection: keep-alive\r\n\r\n" +
                                "$chunkHex\r\n").toByteArray(Charsets.US_ASCII)
                        )
                        out.write(rep1)
                        out.write("\r\n0\r\nX-Trailer: ignored\r\n\r\n".toByteArray(Charsets.US_ASCII))
                        out.flush()

                        val (_, b2) = readHttpPostRequest(sock.getInputStream())
                        val rep2 = buildValidReplyForQuery(b2)
                        out.write(
                            ("HTTP/1.1 200 OK\r\nContent-Length: ${rep2.size}\r\nConnection: close\r\n\r\n")
                                .toByteArray(Charsets.US_ASCII) + rep2
                        )
                        out.flush()
                    }
                    // Connection 2: ambiguous Transfer-Encoding: chunked + Content-Length must be rejected
                    srv.accept().use { sock ->
                        readHttpPostRequest(sock.getInputStream())
                        sock.getOutputStream().write(
                            ("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\nContent-Length: 4\r\n\r\n0\r\n\r\n")
                                .toByteArray(Charsets.US_ASCII)
                        )
                        sock.getOutputStream().flush()
                    }
                }
            } catch (_: Exception) {
            } finally {
                serverDone.countDown()
            }
        }
        worker.isDaemon = true
        worker.start()

        val factoryCalls = AtomicInteger(0)
        val client = DohUpstreamClient(
            vpnService = fakeVpnService,
            clockMs = { 10_000L },
            linkFamiliesProvider = { LinkIpFamilies(hasIpv4 = true, hasIpv6 = false) }
        )
        client.wireConnectionFactory = { _, timeoutMs ->
            factoryCalls.incrementAndGet()
            val raw = Socket("127.0.0.1", server.localPort).apply { soTimeout = timeoutMs }
            val ssl = LoopbackSslSocket(raw)
            DohSocketConnection(raw, ssl, DataOutputStream(ssl.outputStream), DataInputStream(ssl.inputStream))
        }

        val r1 = client.query(query1, timeoutMs = 1500, preferredHost = "family.cloudflare-dns.com")
        val r2 = client.query(query2, timeoutMs = 1500, preferredHost = "family.cloudflare-dns.com")
        assertNotNull(r1)
        assertNotNull(r2)
        assertEquals(0x66.toByte(), r1!![0])
        assertEquals(0x77.toByte(), r2!![0])
        assertEquals("Trailer-terminated chunked response must keep socket reusable", 1, factoryCalls.get())

        val ambiguousReply = client.query(query1, timeoutMs = 400, preferredHost = "family.cloudflare-dns.com")
        assertNull("Ambiguous chunked + Content-Length response must be rejected", ambiguousReply)
        assertTrue(serverDone.await(2, TimeUnit.SECONDS))
    }

    @Test
    fun testDohUpstreamClient_networkChangedDoesNotPoisonCircuitBreakerAndRethrowsCancellation() = runBlocking {
        var nowMs = 10_000L
        lateinit var client: DohUpstreamClient
        client = DohUpstreamClient(
            vpnService = fakeVpnService,
            clockMs = { nowMs },
            linkFamiliesProvider = { LinkIpFamilies(hasIpv4 = true, hasIpv6 = false) },
            exchangeExecutor = { _, _, _ ->
                // Simulate network handoff occurring while query is in flight
                client.onNetworkChanged()
                throw java.net.SocketException("Socket closed")
            }
        )

        val query = buildFakeDnsQuery()
        repeat(4) {
            assertNull(client.query(query, timeoutMs = 1000, preferredHost = "family.cloudflare-dns.com"))
        }
        assertFalse(
            "Stale-generation socket closures during network change must not open circuit breaker",
            client.isCircuitOpen(DohUpstreamClient.CLOUDFLARE_FAMILY_PRIMARY)
        )

        val cancelledClient = DohUpstreamClient(
            vpnService = fakeVpnService,
            clockMs = { nowMs },
            linkFamiliesProvider = { LinkIpFamilies(hasIpv4 = true, hasIpv6 = false) },
            exchangeExecutor = { _, _, _ ->
                throw kotlinx.coroutines.CancellationException("cancelled")
            }
        )
        var threwCancellation = false
        try {
            cancelledClient.query(query, timeoutMs = 1000, preferredHost = "family.cloudflare-dns.com")
        } catch (_: kotlinx.coroutines.CancellationException) {
            threwCancellation = true
        }
        assertTrue("DohUpstreamClient.query must rethrow CancellationException", threwCancellation)
    }

    @Test
    fun testDohConnectionPool_evictsBeyondMaxSizeAndExpiresIdleConnections() {
        var nowMs = 1_000L
        val pool = DohConnectionPool(clockMs = { nowMs })
        val key = pool.poolKey(DohUpstreamClient.CLOUDFLARE_FAMILY_PRIMARY)
        val sockets = List(5) { Socket() }
        val conns = sockets.map { raw ->
            val ssl = LoopbackSslSocket(raw, forceConnected = true)
            DohSocketConnection(raw, ssl, DataOutputStream(java.io.ByteArrayOutputStream()), DataInputStream(java.io.ByteArrayInputStream(ByteArray(0))))
        }

        conns.forEach { conn ->
            val slot = pool.borrowOrPrepareHandle(key, nowMs, forceFresh = true)
            pool.releaseConnection(key, conn, slot.generation, slot.handle, keepAlive = true)
        }
        assertEquals("Pool must cap idle sockets at MAX_POOL_SIZE (4)", DohConnectionPool.MAX_POOL_SIZE, pool.idleCountForTest(key))
        assertTrue("Oldest evicted socket must be closed", sockets.first().isClosed)

        nowMs += DohConnectionPool.POOL_IDLE_TIMEOUT_MS + 1L
        val afterExpiry = pool.borrowOrPrepareHandle(key, nowMs, forceFresh = false)
        assertNull("Expired idle sockets must not be reused", afterExpiry.conn)
        assertEquals(0, pool.idleCountForTest(key))
        assertTrue("Expired idle sockets must be closed on borrow", sockets.drop(1).all { it.isClosed })
    }

    private fun readHttpPostRequest(input: InputStream): Pair<String, ByteArray> {
        val headerBuf = StringBuilder()
        var contentLength = 0
        while (true) {
            val line = readAsciiLine(input) ?: break
            if (line.isEmpty()) break
            headerBuf.append(line).append('\n')
            if (line.startsWith("Content-Length:", ignoreCase = true)) {
                contentLength = line.substringAfter(':').trim().toIntOrNull() ?: 0
            }
        }
        val body = ByteArray(contentLength)
        DataInputStream(input).readFully(body)
        return headerBuf.toString() to body
    }

    private fun readAsciiLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = input.read()
            if (b == -1) return if (sb.isEmpty()) null else sb.toString()
            val c = b.toChar()
            if (c == '\n') return sb.toString().trimEnd('\r')
            sb.append(c)
        }
    }

    private fun buildValidReplyForQuery(query: ByteArray): ByteArray {
        val rep = query.copyOf()
        rep[2] = 0x81.toByte() // QR=1, RD=1
        rep[3] = 0x80.toByte() // RA=1, NOERROR
        return rep
    }

    private class LoopbackSslSocket(
        private val delegate: Socket,
        private val forceConnected: Boolean = false
    ) : SSLSocket() {
        override fun getInputStream(): InputStream = delegate.getInputStream()
        override fun getOutputStream(): OutputStream = delegate.getOutputStream()
        override fun isClosed(): Boolean = delegate.isClosed
        override fun isConnected(): Boolean = forceConnected || delegate.isConnected
        override fun isInputShutdown(): Boolean = delegate.isInputShutdown
        override fun isOutputShutdown(): Boolean = delegate.isOutputShutdown
        override fun setSoTimeout(timeout: Int) {
            delegate.soTimeout = timeout
        }
        override fun close() = delegate.close()
        override fun getSupportedCipherSuites(): Array<String> = emptyArray()
        override fun getEnabledCipherSuites(): Array<String> = emptyArray()
        override fun setEnabledCipherSuites(suites: Array<out String>?) {}
        override fun getSupportedProtocols(): Array<String> = emptyArray()
        override fun getEnabledProtocols(): Array<String> = emptyArray()
        override fun setEnabledProtocols(protocols: Array<out String>?) {}
        override fun getSession(): SSLSession? = null
        override fun addHandshakeCompletedListener(listener: HandshakeCompletedListener?) {}
        override fun removeHandshakeCompletedListener(listener: HandshakeCompletedListener?) {}
        override fun startHandshake() {}
        override fun setUseClientMode(mode: Boolean) {}
        override fun getUseClientMode(): Boolean = true
        override fun setNeedClientAuth(need: Boolean) {}
        override fun getNeedClientAuth(): Boolean = false
        override fun setWantClientAuth(want: Boolean) {}
        override fun getWantClientAuth(): Boolean = false
        override fun setEnableSessionCreation(flag: Boolean) {}
        override fun getEnableSessionCreation(): Boolean = true
    }
}
