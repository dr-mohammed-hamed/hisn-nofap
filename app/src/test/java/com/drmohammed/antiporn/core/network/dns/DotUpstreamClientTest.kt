package com.drmohammed.antiporn.core.network.dns

import android.net.VpnService
import com.drmohammed.antiporn.core.model.DevicePolicyBundle
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.EOFException
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class DotUpstreamClientTest {

    @Test
    fun testHostSanitizationFramingAndQueryValidation() {
        assertEquals("dns.example.com", DotUpstreamClient.sanitizeHost("DNS.Example.COM.:853"))
        assertEquals("dns.adguard.com", DotUpstreamClient.sanitizeHost("tls://dns.adguard.com:853/dns-query"))
        assertEquals("127.0.0.1", DotUpstreamClient.sanitizeHost("127.0.0.1:53"))
        assertEquals("fd00::1", DotUpstreamClient.sanitizeHost("fd00::1"))
        assertEquals("2606:4700:4700::1113", DotUpstreamClient.sanitizeHost("[2606:4700:4700::1113]:853"))

        // RFC 1035 255-byte QNAME cap & non-ASCII rejection
        val over255 = (1..10).joinToString(".") { "a".repeat(30) }
        assertNull("QNAME > 255 wire bytes must be rejected", DotUpstreamClient.buildMinimalAQuery(over255, 0x1001.toShort()))
        assertNull("Non-ASCII host must be rejected", DotUpstreamClient.buildMinimalAQuery("bad\uD83D\uDE00.com", 0x1001.toShort()))

        // RFC 7858 framing
        val rawQuery = buildMinimalQuery(0x3344.toShort())
        val framed = requireNotNull(DotUpstreamClient.buildFramedQuery(rawQuery))
        assertEquals(rawQuery.size + 2, framed.size)
        assertEquals(rawQuery.size, ((framed[0].toInt() and 0xFF) shl 8) or (framed[1].toInt() and 0xFF))
        assertArrayEquals(rawQuery, framed.copyOfRange(2, framed.size))
        assertNull(DotUpstreamClient.buildFramedQuery(ByteArray(11)))

        // Bootstrap answer extraction checks QR=1 & RCODE=0 and skips private IPs when requirePublic=true
        val validReply = buildDnsAnswerPayload(0x4242.toShort(), "custom.dot.io", 120L, byteArrayOf(76, 76, 2, 11))
        assertEquals("76.76.2.11", DotUpstreamClient.extractFirstIpv4FromDnsResponse(validReply)?.hostAddress)
        val nonResponse = validReply.copyOf().apply { this[2] = (this[2].toInt() and 0x7F).toByte() }
        assertNull("QR=0 query must be rejected", DotUpstreamClient.extractFirstIpv4FromDnsResponse(nonResponse))
        val servfail = validReply.copyOf().apply { this[3] = ((this[3].toInt() and 0xF0) or 2).toByte() }
        assertNull("RCODE!=0 must be rejected", DotUpstreamClient.extractFirstIpv4FromDnsResponse(servfail))

        // First A record is loopback 127.0.0.2 (rebound), second A record is public 76.76.2.11 -> picks public!
        val mixedReply = buildTwoARecordAnswer(0x4242.toShort(), "custom.dot.io", byteArrayOf(127, 0, 0, 2), byteArrayOf(76, 76, 2, 11))
        assertEquals("76.76.2.11", DotBootstrapResolver.extractFirstPublicIpv4FromDnsResponse(mixedReply)?.hostAddress)
    }

    @Test
    fun testCircuitBreakerWithInjectedClockAndDirectDotPreferredFirst() = runBlocking {
        val fakeClock = AtomicLong(1_000L)
        val bootstrapAttempts = AtomicInteger(0)
        val rawQuery = buildMinimalQuery(0x3344.toShort())
        val client = DotUpstreamClient(
            vpnService = object : VpnService() {},
            clockMs = { fakeClock.get() },
            connectionFactory = { _, _, _ -> throw IOException("Direct DoT blocked") }
        ) { _, _, _ ->
            bootstrapAttempts.incrementAndGet()
            null
        }

        assertFalse(client.isCircuitOpen())
        assertNull(client.query("custom-dot.example.org", rawQuery, 500))
        assertNull(client.query("custom-dot.example.org", rawQuery, 500))
        assertFalse(client.isCircuitOpen())
        assertNull(client.query("custom-dot.example.org", rawQuery, 500))
        assertTrue("3 consecutive failures must open DoT circuit breaker", client.isCircuitOpen())
        val attemptsAtTrip = bootstrapAttempts.get()

        fakeClock.addAndGet(10_000L)
        assertNull(client.query("custom-dot.example.org", rawQuery, 500))
        assertEquals(attemptsAtTrip, bootstrapAttempts.get())

        fakeClock.addAndGet(20_000L)
        assertFalse(client.isCircuitOpen())
        assertNull(client.query("custom-dot.example.org", rawQuery, 500))
        assertTrue(bootstrapAttempts.get() > attemptsAtTrip)
    }

    @Test
    fun testBootstrapPrefersEncryptedDirectDotOverCleartextUdpAndRejectsPrivateIpLiterals() = runBlocking {
        val fakeClock = AtomicLong(5_000L)
        val udpCalls = AtomicInteger(0)
        val dotTargets = CopyOnWriteArrayList<String>()

        val client = DotUpstreamClient(
            vpnService = object : VpnService() {},
            clockMs = { fakeClock.get() },
            connectionFactory = { targetIp, cleanHost, _ ->
                dotTargets.add("${targetIp.hostAddress}|$cleanHost")
                FakeDotConnection(
                    onExchange = { framed, _ ->
                        val txId = readTxId(framed, 2)
                        if (targetIp.hostAddress == DnsUpstreamForwarder.PRIMARY_SAFE_DNS_IPV4) {
                            buildDnsAnswerPayload(txId, "nextdns-custom.example.io", 120L, byteArrayOf(45, 90, 28, 12))
                        } else {
                            buildDnsAnswerPayload(txId, "example.com", 60L, byteArrayOf(93.toByte(), 184.toByte(), 216.toByte(), 34.toByte()))
                        }
                    }
                )
            }
        ) { _, _, _ ->
            udpCalls.incrementAndGet()
            null
        }

        val reply = client.query("nextdns-custom.example.io", buildMinimalQuery(0x7101.toShort()), 1_500)
        assertNotNull("Encrypted Direct DoT bootstrap must resolve custom DoT host", reply)
        assertEquals("Cleartext UDP bootstrap must NOT be called when Direct DoT succeeds", 0, udpCalls.get())
        assertEquals(
            listOf(
                "${DnsUpstreamForwarder.PRIMARY_SAFE_DNS_IPV4}|${DevicePolicyBundle.CLOUDFLARE_FAMILY_DNS_HOST}",
                "45.90.28.12|nextdns-custom.example.io"
            ),
            dotTargets.toList()
        )

        // Direct private/loopback IP literal must be rejected (anti-SSRF)
        assertNull(client.query("127.0.0.2", buildMinimalQuery(0x7102.toShort()), 500))
        assertNull(client.query("192.168.1.1", buildMinimalQuery(0x7103.toShort()), 500))
    }

    @Test
    fun testBootstrapFallsBackToUdpWhenDirectDotBlockedAndCoalescesSingleFlight() = runBlocking {
        val fakeClock = AtomicLong(5_000L)
        val udpBootstrapCalls = AtomicInteger(0)
        val leaderEntered = CountDownLatch(1)
        val allowLeaderFinish = CountDownLatch(1)

        val client = DotUpstreamClient(
            vpnService = object : VpnService() {},
            clockMs = { fakeClock.get() },
            connectionFactory = { targetIp, cleanHost, _ ->
                if (targetIp.hostAddress == DnsUpstreamForwarder.PRIMARY_SAFE_DNS_IPV4) {
                    throw IOException("Port 853 to 1.1.1.3 blocked")
                }
                assertEquals("76.76.2.11", targetIp.hostAddress)
                assertEquals("custom-singleflight.example.net", cleanHost)
                FakeDotConnection(
                    onExchange = { framed, _ ->
                        buildDnsAnswerPayload(readTxId(framed, 2), "example.com", 60L)
                    }
                )
            }
        ) { serverIp, rawQuery, _ ->
            if (serverIp == DnsUpstreamForwarder.PRIMARY_SAFE_DNS_IPV4) {
                udpBootstrapCalls.incrementAndGet()
                leaderEntered.countDown()
                allowLeaderFinish.await(3, TimeUnit.SECONDS)
                buildDnsAnswerPayload(readTxId(rawQuery, 0), "custom-singleflight.example.net", 120L, byteArrayOf(76, 76, 2, 11))
            } else {
                null
            }
        }

        val deferreds = (0 until 4).map { idx ->
            async(Dispatchers.IO) {
                client.query("custom-singleflight.example.net", buildMinimalQuery((0x7000 + idx).toShort()), 1_500)
            }
        }
        assertTrue(leaderEntered.await(3, TimeUnit.SECONDS))
        Thread.sleep(40L)
        allowLeaderFinish.countDown()
        val results = deferreds.awaitAll()
        results.forEach { assertNotNull("All coalesced callers must resolve cleanly", it) }
        assertEquals("Single-flight coalescing must invoke UDP bootstrap only ONCE", 1, udpBootstrapCalls.get())
    }

    @Test
    fun testPoolReuseIdleEvictionReusedTimeoutRetryOldestEvictionAndUnifiedDeadline() = runBlocking {
        val fakeClock = AtomicLong(10_000L)
        val created = CopyOnWriteArrayList<FakeDotConnection>()
        val nextBehavior = ArrayDeque<(ByteArray, Int) -> ByteArray>()

        val client = DotUpstreamClient(
            vpnService = object : VpnService() {},
            clockMs = { fakeClock.get() },
            connectionFactory = { _, _, _ ->
                FakeDotConnection(
                    onExchange = { framed, readTimeoutMs ->
                        val handler = nextBehavior.removeFirstOrNull()
                        if (handler != null) {
                            handler(framed, readTimeoutMs)
                        } else {
                            buildDnsAnswerPayload(readTxId(framed, 2), "example.com", 60L)
                        }
                    }
                ).also { created.add(it) }
            }
        ) { _, _, _ -> null }

        val q1 = buildMinimalQuery(0x1101.toShort())
        assertNotNull(client.query(DevicePolicyBundle.CLOUDFLARE_FAMILY_DNS_HOST, q1, 1_500))
        assertNotNull(client.query(DevicePolicyBundle.CLOUDFLARE_FAMILY_DNS_HOST, q1, 1_500))
        assertEquals(1, created.size)

        // Idle eviction after 20_000ms
        fakeClock.addAndGet(DotUpstreamClient.POOL_IDLE_TIMEOUT_MS)
        assertNotNull(client.query(DevicePolicyBundle.CLOUDFLARE_FAMILY_DNS_HOST, q1, 1_500))
        assertEquals(2, created.size)
        assertTrue(created[0].closed.get())

        // Reused EOFException retries once on fresh connection without tripping breaker
        nextBehavior.addLast { _, _ -> throw EOFException("Peer closed idle TLS socket") }
        assertNotNull(client.query(DevicePolicyBundle.CLOUDFLARE_FAMILY_DNS_HOST, q1, 1_500))
        assertEquals(3, created.size)
        assertFalse(client.isCircuitOpen())

        // Reused SocketTimeoutException with remaining budget retries ONCE on fresh socket (NAT blackhole recovery)
        nextBehavior.addLast { _, _ -> throw SocketTimeoutException("NAT blackhole timeout") }
        assertNotNull("Reused SocketTimeoutException with remaining budget must retry on fresh socket",
            client.query(DevicePolicyBundle.CLOUDFLARE_FAMILY_DNS_HOST, q1, 1_500))
        assertEquals(4, created.size)
        assertFalse(client.isCircuitOpen())

        // Pool overflow evicts OLDEST connection (not freshest!)
        val pool = DotConnectionPool(clockMs = { fakeClock.get() }, maxPoolSize = 2, idleTimeoutMs = 20_000L)
        val c1 = FakeDotConnection(onExchange = { _, _ -> ByteArray(0) })
        val c2 = FakeDotConnection(onExchange = { _, _ -> ByteArray(0) })
        val c3 = FakeDotConnection(onExchange = { _, _ -> ByteArray(0) })
        val s1 = pool.borrowOrPrepareHandle("k", 100L, true)
        val s2 = pool.borrowOrPrepareHandle("k", 101L, true)
        val s3 = pool.borrowOrPrepareHandle("k", 102L, true)
        pool.releaseConnection("k", c1, s1.generation, s1.handle, true)
        pool.releaseConnection("k", c2, s2.generation, s2.handle, true)
        pool.releaseConnection("k", c3, s3.generation, s3.handle, true)
        assertTrue("Oldest connection c1 must be evicted when pool is full", c1.closed.get())
        assertFalse("Freshest connection c3 must be kept in pool", c3.closed.get())
    }

    @Test
    fun testOnNetworkChangedAndResetStateFencesStaleFailuresAndClearsBootstrap() = runBlocking {
        val fakeClock = AtomicLong(2_000L)
        val entered = CountDownLatch(4)
        val unblock = CountDownLatch(1)

        val client = DotUpstreamClient(
            vpnService = object : VpnService() {},
            clockMs = { fakeClock.get() },
            connectionFactory = { _, _, _ ->
                FakeDotConnection(
                    onExchange = { _, _ ->
                        entered.countDown()
                        unblock.await(3, TimeUnit.SECONDS)
                        throw IOException("Closed by network handoff")
                    }
                )
            }
        ) { _, _, _ -> null }

        val jobs = (0 until 4).map { idx ->
            async(Dispatchers.IO) {
                client.query(DevicePolicyBundle.CLOUDFLARE_FAMILY_DNS_HOST, buildMinimalQuery((0x8100 + idx).toShort()), 1_500)
            }
        }
        assertTrue(entered.await(3, TimeUnit.SECONDS))
        client.onNetworkChanged()
        unblock.countDown()
        jobs.awaitAll().forEach { assertNull(it) }
        assertFalse("Old-generation socket errors after onNetworkChanged must NEVER trip breaker", client.isCircuitOpen())
    }

    @Test
    fun testSocketDotConnectionFramingAndUnifiedDeadline() {
        val fakeClock = AtomicLong(1_000L)
        val validReply = buildDnsAnswerPayload(0x8501.toShort(), "example.com", 60L)
        val validFrame = ByteArray(2 + validReply.size).apply {
            this[0] = ((validReply.size ushr 8) and 0xFF).toByte()
            this[1] = (validReply.size and 0xFF).toByte()
            System.arraycopy(validReply, 0, this, 2, validReply.size)
        }
        val queryFrame = requireNotNull(DotUpstreamClient.buildFramedQuery(buildMinimalQuery(0x8501.toShort())))
        val trickleSocket = FakeSslSocket(TrickleInputStream(validFrame) { fakeClock.addAndGet(1L) })
        val received = SocketDotConnection(trickleSocket, clockMs = { fakeClock.get() }).exchange(queryFrame, 500)
        assertTrue(validReply.contentEquals(received))

        val slowClock = AtomicLong(10_000L)
        val slowSocket = FakeSslSocket(TrickleInputStream(validFrame) { slowClock.addAndGet(100L) })
        var timedOut = false
        try {
            SocketDotConnection(slowSocket, clockMs = { slowClock.get() }).exchange(queryFrame, 250)
        } catch (_: SocketTimeoutException) {
            timedOut = true
        }
        assertTrue("Trickling TLS peer exceeding deadline must throw SocketTimeoutException", timedOut)
    }

    @Test
    fun testNoRouteEnetunreachDuringRestoringPrimary_doesNotTripCircuitBreaker() = runBlocking {
        val fakeClock = AtomicLong(1_000L)
        val rawQuery = buildMinimalQuery(0x9001.toShort())
        val client = DotUpstreamClient(
            vpnService = object : VpnService() {},
            clockMs = { fakeClock.get() },
            connectionFactory = { _, _, _ ->
                throw ConnectException("failed to connect to /94.140.14.15 (port 853): connect failed: ENETUNREACH (Network is unreachable)")
            }
        ) { _, _, _ -> null }

        val stateLockField = DnsResilienceManager.javaClass.getDeclaredField("stateLock")
        stateLockField.isAccessible = true
        val lock = requireNotNull(stateLockField.get(DnsResilienceManager))
        val restoringField = DnsResilienceManager.javaClass.getDeclaredField("isRestoringPrimary")
        restoringField.isAccessible = true

        synchronized(lock) {
            restoringField.setBoolean(DnsResilienceManager, true)
        }
        try {
            repeat(5) {
                assertNull(client.query(DevicePolicyBundle.ADGUARD_FAMILY_DNS_HOST, rawQuery, 300))
            }
            assertFalse(
                "ENETUNREACH during primary teardown fence must not trip DoT circuit breaker",
                client.isCircuitOpen()
            )
        } finally {
            synchronized(lock) {
                restoringField.setBoolean(DnsResilienceManager, false)
            }
        }
    }

    @Test
    fun testConsecutiveCircuitTripsSameGeneration_appliesExponentialCooldownBackoff_andResetsOnNetworkChange() = runBlocking {
        val fakeClock = AtomicLong(1_000L)
        val rawQuery = buildMinimalQuery(0x9002.toShort())
        val client = DotUpstreamClient(
            vpnService = object : VpnService() {},
            clockMs = { fakeClock.get() },
            connectionFactory = { _, _, _ -> throw IOException("TLS handshake timeout") }
        ) { _, _, _ -> null }

        // Trip 1 (3 failures at t=1_000) -> 30s cooldown (open until t=31_000)
        repeat(3) { assertNull(client.query("custom-backoff.example.org", rawQuery, 300)) }
        assertTrue(client.isCircuitOpen())
        fakeClock.addAndGet(29_999L)
        assertTrue(client.isCircuitOpen())
        fakeClock.addAndGet(1L) // t=31_000
        assertFalse(client.isCircuitOpen())

        // Trip 2 in same generation (3 failures at t=31_000) -> 120s cooldown (open until t=151_000)
        repeat(3) { assertNull(client.query("custom-backoff.example.org", rawQuery, 300)) }
        assertTrue(client.isCircuitOpen())
        fakeClock.addAndGet(30_000L) // t=61_000
        assertTrue("2nd same-generation trip must exceed 30s (120s cooldown)", client.isCircuitOpen())
        fakeClock.addAndGet(89_999L) // t=150_999
        assertTrue(client.isCircuitOpen())
        fakeClock.addAndGet(1L) // t=151_000
        assertFalse(client.isCircuitOpen())

        // Trip 3 in same generation (3 failures at t=151_000) -> 300s cooldown (open until t=451_000)
        repeat(3) { assertNull(client.query("custom-backoff.example.org", rawQuery, 300)) }
        assertTrue(client.isCircuitOpen())
        fakeClock.addAndGet(299_999L) // t=450_999
        assertTrue("3rd same-generation trip must hold 300s cooldown", client.isCircuitOpen())
        fakeClock.addAndGet(1L) // t=451_000
        assertFalse(client.isCircuitOpen())

        // Reset generation (network switch) clears escalation back to 30s
        client.resetState()
        repeat(3) { assertNull(client.query("custom-backoff.example.org", rawQuery, 300)) }
        assertTrue(client.isCircuitOpen())
        fakeClock.addAndGet(30_000L)
        assertFalse("After resetState(), backoff must start cleanly at 30s again", client.isCircuitOpen())
    }

    private fun readTxId(bytes: ByteArray, offset: Int): Short =
        (((bytes[offset].toInt() and 0xFF) shl 8) or (bytes[offset + 1].toInt() and 0xFF)).toShort()

    private fun buildMinimalQuery(txId: Short): ByteArray =
        requireNotNull(DotUpstreamClient.buildMinimalAQuery("example.com", txId))

    private fun buildDnsAnswerPayload(
        txId: Short,
        domain: String,
        ttlSeconds: Long,
        ipv4: ByteArray = byteArrayOf(93.toByte(), 184.toByte(), 216.toByte(), 34.toByte())
    ): ByteArray {
        val labels = domain.split('.')
        var qLen = 1
        for (l in labels) qLen += 1 + l.length
        val out = ByteArray(12 + qLen + 4 + 2 + 10 + 4)
        val buf = ByteBuffer.wrap(out)
        buf.putShort(txId); buf.putShort(0x8180.toShort())
        buf.putShort(1); buf.putShort(1); buf.putShort(0); buf.putShort(0)
        for (l in labels) { buf.put(l.length.toByte()); buf.put(l.toByteArray(Charsets.US_ASCII)) }
        buf.put(0); buf.putShort(1); buf.putShort(1)
        buf.putShort(0xC00C.toShort()); buf.putShort(1); buf.putShort(1)
        buf.putInt(ttlSeconds.toInt()); buf.putShort(4); buf.put(ipv4)
        return out
    }

    private fun buildTwoARecordAnswer(txId: Short, domain: String, ip1: ByteArray, ip2: ByteArray): ByteArray {
        val labels = domain.split('.')
        var qLen = 1
        for (l in labels) qLen += 1 + l.length
        val out = ByteArray(12 + qLen + 4 + (2 + 10 + 4) * 2)
        val buf = ByteBuffer.wrap(out)
        buf.putShort(txId); buf.putShort(0x8180.toShort())
        buf.putShort(1); buf.putShort(2); buf.putShort(0); buf.putShort(0)
        for (l in labels) { buf.put(l.length.toByte()); buf.put(l.toByteArray(Charsets.US_ASCII)) }
        buf.put(0); buf.putShort(1); buf.putShort(1)
        buf.putShort(0xC00C.toShort()); buf.putShort(1); buf.putShort(1); buf.putInt(60); buf.putShort(4); buf.put(ip1)
        buf.putShort(0xC00C.toShort()); buf.putShort(1); buf.putShort(1); buf.putInt(60); buf.putShort(4); buf.put(ip2)
        return out
    }

    private class FakeDotConnection(
        private val onExchange: (framedQuery: ByteArray, readTimeoutMs: Int) -> ByteArray,
        private val onClose: (FakeDotConnection) -> Unit = {}
    ) : DotConnection {
        val closed = AtomicBoolean(false)
        override val isUsable: Boolean get() = !closed.get()
        override fun exchange(framedQuery: ByteArray, readTimeoutMs: Int): ByteArray = onExchange(framedQuery, readTimeoutMs)
        override fun close() { if (closed.compareAndSet(false, true)) onClose(this) }
    }

    private class TrickleInputStream(
        private val data: ByteArray,
        private val onChunkRead: () -> Unit
    ) : java.io.InputStream() {
        private var pos = 0
        override fun read(): Int {
            if (pos >= data.size) return -1
            onChunkRead()
            return data[pos++].toInt() and 0xFF
        }
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (pos >= data.size) return -1
            if (len <= 0) return 0
            onChunkRead()
            b[off] = data[pos++]
            return 1
        }
    }

    private class FakeSslSocket(private val inputStream: java.io.InputStream) : javax.net.ssl.SSLSocket() {
        private val out = java.io.ByteArrayOutputStream()
        private var closed = false
        private var timeout = 0
        override fun getInputStream() = inputStream
        override fun getOutputStream() = out
        override fun isClosed() = closed
        override fun isConnected() = !closed
        override fun isInputShutdown() = false
        override fun isOutputShutdown() = false
        override fun setSoTimeout(timeout: Int) { this.timeout = timeout }
        override fun getSoTimeout() = timeout
        override fun close() { closed = true }
        override fun getSupportedCipherSuites() = emptyArray<String>()
        override fun getEnabledCipherSuites() = emptyArray<String>()
        override fun setEnabledCipherSuites(suites: Array<out String>?) {}
        override fun getSupportedProtocols() = emptyArray<String>()
        override fun getEnabledProtocols() = emptyArray<String>()
        override fun setEnabledProtocols(protocols: Array<out String>?) {}
        override fun getSession(): javax.net.ssl.SSLSession = throw UnsupportedOperationException()
        override fun addHandshakeCompletedListener(listener: javax.net.ssl.HandshakeCompletedListener?) {}
        override fun removeHandshakeCompletedListener(listener: javax.net.ssl.HandshakeCompletedListener?) {}
        override fun startHandshake() {}
        override fun setUseClientMode(mode: Boolean) {}
        override fun getUseClientMode() = true
        override fun setNeedClientAuth(need: Boolean) {}
        override fun getNeedClientAuth() = false
        override fun setWantClientAuth(want: Boolean) {}
        override fun getWantClientAuth() = false
        override fun setEnableSessionCreation(flag: Boolean) {}
        override fun getEnableSessionCreation() = true
    }
}
