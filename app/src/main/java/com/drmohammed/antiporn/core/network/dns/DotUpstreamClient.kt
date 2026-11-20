package com.drmohammed.antiporn.core.network.dns

import android.net.VpnService
import android.os.SystemClock
import com.drmohammed.antiporn.telemetry.BlackBoxRecorder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * RFC 7858 DNS-over-TLS (Port 853) upstream client protected via [VpnService.protect].
 * Delegates TLS connection pooling & active-socket tracking to [DotConnectionPool]
 * and single-flight bootstrap resolution to [DotBootstrapResolver].
 */
internal class DotUpstreamClient(
    private val vpnService: VpnService,
    private val clockMs: () -> Long = {
        try {
            SystemClock.elapsedRealtime()
        } catch (_: RuntimeException) {
            System.nanoTime() / 1_000_000L
        }
    },
    private val connectionFactory: ((targetIp: InetAddress, cleanHost: String, timeoutMs: Int) -> DotConnection)? = null,
    private val udpBootstrapQuery: suspend (serverIp: String, rawQuery: ByteArray, timeoutMs: Int) -> ByteArray?
) {

    companion object {
        private const val TAG = "AntiPorn_DotClient"
        private const val DOT_PORT = 853
        private const val DEFAULT_DOT_TIMEOUT_MS = 1_500
        private const val CIRCUIT_FAILURE_THRESHOLD = 3
        private const val CIRCUIT_COOLDOWN_MS = 30_000L
        private const val TELEMETRY_DEBOUNCE_MS = 60_000L

        internal const val MAX_POOL_SIZE = 4
        internal const val MAX_ACTIVE_CONNECTIONS = 8
        internal const val POOL_IDLE_TIMEOUT_MS = 20_000L
        private const val MIN_RETRY_BUDGET_MS = 300L

        private val handshakeWatchdog = Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "AntiPorn-DotHandshakeWatchdog").apply { isDaemon = true }
        }

        internal fun sanitizeHost(rawHost: String): String {
            var trimmed = rawHost.trim().lowercase()
            if (trimmed.isEmpty()) return ""
            for (scheme in arrayOf("tls://", "https://", "http://")) {
                if (trimmed.startsWith(scheme)) {
                    trimmed = trimmed.removePrefix(scheme).substringBefore('/')
                    break
                }
            }
            val stripped = when {
                trimmed.startsWith("[") && trimmed.contains("]") ->
                    trimmed.substringAfter("[").substringBefore("]")
                trimmed.count { it == ':' } == 1 ->
                    trimmed.substringBefore(':')
                else -> trimmed
            }
            return stripped.trim().trimEnd('.').trim()
        }

        internal fun buildFramedQuery(rawPayload: ByteArray): ByteArray? {
            if (rawPayload.size !in DnsPacketParser.DNS_HEADER_LEN..65535) return null
            val framed = ByteArray(2 + rawPayload.size)
            framed[0] = ((rawPayload.size ushr 8) and 0xFF).toByte()
            framed[1] = (rawPayload.size and 0xFF).toByte()
            System.arraycopy(rawPayload, 0, framed, 2, rawPayload.size)
            return framed
        }

        internal fun buildMinimalAQuery(hostname: String, txId: Short): ByteArray? =
            DotBootstrapResolver.buildMinimalAQuery(hostname, txId)

        internal fun extractFirstIpv4FromDnsResponse(dnsPayload: ByteArray): InetAddress? =
            DotBootstrapResolver.extractFirstIpv4FromDnsResponse(dnsPayload)
    }

    private val pool = DotConnectionPool(
        clockMs = clockMs,
        maxPoolSize = MAX_POOL_SIZE,
        idleTimeoutMs = POOL_IDLE_TIMEOUT_MS
    )

    private val activeDotPermits = Semaphore(MAX_ACTIVE_CONNECTIONS)

    private val bootstrapResolver = DotBootstrapResolver(
        clockMs = clockMs,
        currentGeneration = { pool.currentGeneration() },
        udpBootstrapQuery = udpBootstrapQuery,
        directDotBootstrapQuery = { targetIp, sniHost, rawQuery, timeoutMs ->
            queryDirectDotByIp(targetIp, sniHost, rawQuery, timeoutMs)
        }
    )

    private val circuitLock = Any()
    private var consecutiveFailures = 0
    @Volatile
    private var circuitOpenUntilMs = Long.MIN_VALUE
    private val lastFailureTelemetryMs = AtomicLong(-TELEMETRY_DEBOUNCE_MS)

    internal fun isCircuitOpen(): Boolean = clockMs() < circuitOpenUntilMs

    fun resetState() {
        val detached = synchronized(circuitLock) {
            consecutiveFailures = 0
            circuitOpenUntilMs = Long.MIN_VALUE
            pool.detachAllForInvalidation()
        }
        bootstrapResolver.clearAndCancelAll()
        pool.closeDetached(detached)
    }

    fun onNetworkChanged() {
        resetState()
    }

    fun clearAll() {
        resetState()
    }

    suspend fun query(host: String, rawPayload: ByteArray, timeoutMs: Int = DEFAULT_DOT_TIMEOUT_MS): ByteArray? {
        val startMs = clockMs()
        if (startMs < circuitOpenUntilMs) {
            return null
        }

        val framedQuery = buildFramedQuery(rawPayload) ?: return null
        val cleanHost = sanitizeHost(host)
        if (cleanHost.isBlank()) return null

        val bootstrapDeadlineMs = startMs + minOf(timeoutMs.toLong(), DotBootstrapResolver.MAX_BOOTSTRAP_WAIT_MS)
        var targetIp: InetAddress? = null
        // Allow at most 1 retry if a network handoff bumped generation mid-bootstrap
        for (attempt in 0..1) {
            val bootstrapGen = pool.currentGeneration()
            when (val outcome = bootstrapResolver.resolve(cleanHost, bootstrapDeadlineMs, bootstrapGen)) {
                is BootstrapOutcome.Resolved -> {
                    targetIp = outcome.address
                    break
                }
                is BootstrapOutcome.LeaderFailed -> {
                    recordFailure(cleanHost, "Bootstrap IP resolution failed", outcome.generation)
                    return null
                }
                BootstrapOutcome.CoalescedOrCancelled -> {
                    val remaining = bootstrapDeadlineMs - clockMs()
                    if (attempt == 0 && pool.currentGeneration() != bootstrapGen && remaining >= MIN_RETRY_BUDGET_MS) {
                        continue
                    }
                    return null
                }
            }
        }
        val resolvedIp = targetIp ?: return null

        // Start unified connect+handshake+read deadline after cold-start bootstrap completes
        val queryDeadlineMs = clockMs() + timeoutMs.coerceAtLeast(1)
        return executeDotQuery(
            targetIp = resolvedIp,
            cleanHost = cleanHost,
            rawPayload = rawPayload,
            framedQuery = framedQuery,
            deadlineMs = queryDeadlineMs,
            recordFailures = true
        )
    }

    private suspend fun executeDotQuery(
        targetIp: InetAddress,
        cleanHost: String,
        rawPayload: ByteArray,
        framedQuery: ByteArray,
        deadlineMs: Long,
        recordFailures: Boolean
    ): ByteArray? {
        val poolKey = "$cleanHost|${targetIp.hostAddress ?: ""}"
        var forceFresh = false

        while (true) {
            val now = clockMs()
            val remainingBeforePermit = (deadlineMs - now).toInt()
            if (remainingBeforePermit <= 0) {
                if (recordFailures) {
                    recordFailure(cleanHost, "DoT deadline exceeded", pool.currentGeneration())
                }
                return null
            }

            val acquired = withTimeoutOrNull(remainingBeforePermit.toLong()) {
                activeDotPermits.acquire()
                true
            } ?: false
            if (!acquired) {
                if (recordFailures) {
                    recordFailure(cleanHost, "DoT active connection permit timeout", pool.currentGeneration())
                }
                return null
            }

            val borrowNow = clockMs()
            val borrowed = pool.borrowOrPrepareHandle(poolKey, borrowNow, forceFresh)
            val wasReused = borrowed.conn != null
            var conn: DotConnection? = borrowed.conn
            val generation = borrowed.generation
            val handle = borrowed.handle
            var keepAlive = false

            try {
                val activeConn = conn ?: openConnection(targetIp, cleanHost, deadlineMs, handle).also {
                    conn = it
                }

                val readRemaining = (deadlineMs - clockMs()).toInt()
                if (readRemaining <= 0) {
                    throw SocketTimeoutException("DoT deadline exceeded before exchange")
                }

                val dnsReply = activeConn.exchange(framedQuery, readRemaining)
                if (!DnsWireValidator.isValidDnsResponse(dnsReply, dnsReply.size, rawPayload)) {
                    Timber.tag(TAG).w("Discarded invalid DoT response payload")
                    if (recordFailures) {
                        recordFailure(cleanHost, "Invalid DoT response payload", generation)
                    }
                    return null
                }

                keepAlive = true
                if (recordFailures) {
                    resetFailureCountIfGenerationMatches(generation)
                }
                return dnsReply
            } catch (e: CancellationException) {
                throw e
            } catch (e: SocketTimeoutException) {
                Timber.tag(TAG).d(e, "DoT upstream query timed out (reused=$wasReused)")
                val isCurrentGen = generation == pool.currentGeneration()
                val remainingAfterError = deadlineMs - clockMs()
                if (isCurrentGen && wasReused && !forceFresh && remainingAfterError >= MIN_RETRY_BUDGET_MS) {
                    forceFresh = true
                } else {
                    if (recordFailures) {
                        recordFailure(cleanHost, e.message, generation)
                    }
                    return null
                }
            } catch (e: IOException) {
                Timber.tag(TAG).d(e, "DoT upstream I/O failure (reused=$wasReused)")
                val isCurrentGen = generation == pool.currentGeneration()
                val remainingAfterError = deadlineMs - clockMs()
                if (isCurrentGen && wasReused && !forceFresh && remainingAfterError >= MIN_RETRY_BUDGET_MS) {
                    forceFresh = true
                } else {
                    if (!wasReused && recordFailures) {
                        recordFailure(cleanHost, e.message, generation)
                    }
                    return null
                }
            } catch (e: Exception) {
                Timber.tag(TAG).d(e, "DoT upstream query failed (reused=$wasReused)")
                if (recordFailures) {
                    recordFailure(cleanHost, e.message, generation)
                }
                return null
            } finally {
                pool.releaseConnection(poolKey, conn, generation, handle, keepAlive)
                activeDotPermits.release()
            }
        }
    }

    private fun openConnection(
        targetIp: InetAddress,
        cleanHost: String,
        deadlineMs: Long,
        activeHandle: ActiveHandle
    ): DotConnection {
        val customFactory = connectionFactory
        if (customFactory != null) {
            val remaining = (deadlineMs - clockMs()).toInt()
            if (remaining <= 0) {
                throw SocketTimeoutException("DoT deadline exceeded before connect")
            }
            val conn = customFactory.invoke(targetIp, cleanHost, remaining)
            if (!activeHandle.attach(conn)) {
                throw IOException("DoT connection invalidated by network change")
            }
            return conn
        }
        return openProductionConnection(targetIp, cleanHost, deadlineMs, activeHandle)
    }

    private fun openProductionConnection(
        targetIp: InetAddress,
        cleanHost: String,
        deadlineMs: Long,
        activeHandle: ActiveHandle
    ): DotConnection {
        val connectTimeout = (deadlineMs - clockMs()).toInt()
        if (connectTimeout <= 0) {
            throw SocketTimeoutException("DoT deadline exceeded before connect")
        }

        val tcp = Socket()
        if (!activeHandle.attach(tcp)) {
            throw IOException("DoT TCP socket invalidated before connect")
        }

        tcp.bind(null) // Allocate native OS socket FD before VpnService.protect()
        tcp.tcpNoDelay = true
        if (!vpnService.protect(tcp)) {
            Timber.tag(TAG).w("VpnService.protect() failed for DoT TCP socket")
            throw IOException("VpnService.protect() returned false")
        }

        tcp.connect(InetSocketAddress(targetIp, DOT_PORT), connectTimeout)

        val handshakeTimeout = (deadlineMs - clockMs()).toInt()
        if (handshakeTimeout <= 0) {
            throw SocketTimeoutException("DoT deadline exceeded before TLS handshake")
        }
        tcp.soTimeout = handshakeTimeout

        val factory = SSLSocketFactory.getDefault() as? SSLSocketFactory
            ?: throw IOException("Default SSLSocketFactory unavailable")
        val raw = factory.createSocket(tcp, cleanHost, DOT_PORT, true) as? SSLSocket
            ?: throw IOException("Failed creating SSLSocket for DoT upstream")
        if (!activeHandle.attach(raw)) {
            throw IOException("DoT TLS socket invalidated before handshake")
        }
        raw.soTimeout = handshakeTimeout

        val sslParams = raw.sslParameters
        val isIpLiteral = DnsWireValidator.parseIpv4ToInetAddress(cleanHost) != null || cleanHost.contains(':')
        if (!isIpLiteral) {
            try {
                sslParams.serverNames = listOf(SNIHostName(cleanHost))
            } catch (e: IllegalArgumentException) {
                throw IOException("Invalid DoT SNI hostname", e)
            }
        }
        sslParams.endpointIdentificationAlgorithm = "HTTPS"
        raw.sslParameters = sslParams

        // Enforce strict total handshake deadline even against trickling multi-flight TLS peers
        val handshakeDeadlineTask = handshakeWatchdog.schedule(
            { activeHandle.close() },
            handshakeTimeout.toLong(),
            TimeUnit.MILLISECONDS
        )
        try {
            raw.startHandshake()
        } finally {
            handshakeDeadlineTask.cancel(false)
        }
        if (!HttpsURLConnection.getDefaultHostnameVerifier().verify(cleanHost, raw.session)) {
            Timber.tag(TAG).w("TLS hostname verification failed for DoT upstream")
            throw IOException("TLS hostname verification failed")
        }

        return SocketDotConnection(raw, clockMs)
    }

    private suspend fun queryDirectDotByIp(
        targetIp: InetAddress,
        sniHost: String,
        rawQuery: ByteArray,
        timeoutMs: Int
    ): ByteArray? {
        val framed = buildFramedQuery(rawQuery) ?: return null
        val directDeadlineMs = clockMs() + timeoutMs.coerceAtLeast(1)
        return executeDotQuery(
            targetIp = targetIp,
            cleanHost = sanitizeHost(sniHost),
            rawPayload = rawQuery,
            framedQuery = framed,
            deadlineMs = directDeadlineMs,
            recordFailures = false
        )
    }

    private fun resetFailureCountIfGenerationMatches(expectedGeneration: Long) {
        synchronized(circuitLock) {
            if (pool.currentGeneration() == expectedGeneration) {
                consecutiveFailures = 0
            }
        }
    }

    private fun recordFailure(cleanHost: String, errorDetails: String?, expectedGeneration: Long) {
        val now = clockMs()
        val failures: Int
        synchronized(circuitLock) {
            if (pool.currentGeneration() != expectedGeneration) return
            consecutiveFailures += 1
            failures = consecutiveFailures
            if (failures >= CIRCUIT_FAILURE_THRESHOLD) {
                circuitOpenUntilMs = now + CIRCUIT_COOLDOWN_MS
            }
        }
        val prev = lastFailureTelemetryMs.get()
        if (now - prev >= TELEMETRY_DEBOUNCE_MS && lastFailureTelemetryMs.compareAndSet(prev, now)) {
            try {
                BlackBoxRecorder.record(
                    context = vpnService,
                    tag = BlackBoxRecorder.TAG_NETWORK,
                    actionName = "DOT_UPSTREAM_FAILURE",
                    isSuccess = false,
                    errorDetails = errorDetails ?: "DoT failure",
                    metadata = "host=$cleanHost, failures=$failures"
                )
            } catch (e: Exception) {
                Timber.tag(TAG).d(e, "Skipped BlackBoxRecorder telemetry for DoT failure")
            }
        }
    }
}
