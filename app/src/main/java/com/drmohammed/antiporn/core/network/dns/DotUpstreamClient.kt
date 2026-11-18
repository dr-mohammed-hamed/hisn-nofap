package com.drmohammed.antiporn.core.network.dns

import android.net.VpnService
import android.os.SystemClock
import com.drmohammed.antiporn.core.model.DevicePolicyBundle
import com.drmohammed.antiporn.telemetry.BlackBoxRecorder
import timber.log.Timber
import java.io.DataInputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * RFC 7858 DNS-over-TLS (Port 853) upstream client protected via [VpnService.protect].
 * Features:
 * - Protected UDP bootstrap resolution + 10-minute IP cache (zero OS getByName blocking on hot path).
 * - Plain TCP socket protection + connect prior to TLS upgrade (avoids Conscrypt API 24-27 SNI & protect() failures).
 * - Circuit breaker (30s cooldown after 3 consecutive failures) to prevent semaphore starvation.
 * - Debounced BlackBox telemetry (max 1 failure event per 60s).
 */
internal class DotUpstreamClient(
    private val vpnService: VpnService,
    private val clockMs: () -> Long = {
        try {
            SystemClock.elapsedRealtime()
        } catch (_: Throwable) {
            System.nanoTime() / 1_000_000L
        }
    },
    private val udpBootstrapQuery: (serverIp: String, rawQuery: ByteArray, timeoutMs: Int) -> ByteArray?
) {

    companion object {
        private const val TAG = "AntiPorn_DotClient"
        private const val DOT_PORT = 853
        private const val DEFAULT_DOT_TIMEOUT_MS = 1_500
        private const val BOOTSTRAP_CACHE_TTL_MS = 10L * 60L * 1000L
        private const val CIRCUIT_FAILURE_THRESHOLD = 3
        private const val CIRCUIT_COOLDOWN_MS = 30_000L
        private const val TELEMETRY_DEBOUNCE_MS = 60_000L
        private const val CLEANBROWSING_FAMILY_HOST = "family-filter-dns.cleanbrowsing.org"

        internal fun sanitizeHost(rawHost: String): String {
            val trimmed = rawHost.trim().lowercase()
            if (trimmed.isEmpty()) return ""
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

        internal fun buildMinimalAQuery(hostname: String, txId: Short): ByteArray? {
            val labels = hostname.trim().lowercase().trimEnd('.').split('.')
            if (labels.isEmpty() || labels.any { it.isEmpty() || it.length > 63 }) return null
            var qNameLen = 1
            for (label in labels) qNameLen += 1 + label.length
            val payload = ByteArray(12 + qNameLen + 4)
            val buf = ByteBuffer.wrap(payload)
            buf.putShort(txId)
            buf.putShort(0x0100.toShort()) // Standard query, RD=1
            buf.putShort(1.toShort())      // QDCOUNT=1
            buf.putShort(0.toShort())
            buf.putShort(0.toShort())
            buf.putShort(0.toShort())
            for (label in labels) {
                buf.put(label.length.toByte())
                buf.put(label.toByteArray(Charsets.US_ASCII))
            }
            buf.put(0.toByte())
            buf.putShort(1.toShort()) // QTYPE=A
            buf.putShort(1.toShort()) // QCLASS=IN
            return payload
        }

        internal fun extractFirstIpv4FromDnsResponse(dnsPayload: ByteArray): InetAddress? {
            if (dnsPayload.size < DnsPacketParser.DNS_HEADER_LEN) return null
            val anCount = ((dnsPayload[6].toInt() and 0xFF) shl 8) or (dnsPayload[7].toInt() and 0xFF)
            if (anCount <= 0) return null
            val qdCount = ((dnsPayload[4].toInt() and 0xFF) shl 8) or (dnsPayload[5].toInt() and 0xFF)

            var offset = 12
            for (q in 0 until qdCount) {
                offset = skipDnsName(dnsPayload, offset) ?: return null
                offset += 4 // QTYPE + QCLASS
                if (offset > dnsPayload.size) return null
            }

            for (a in 0 until anCount) {
                offset = skipDnsName(dnsPayload, offset) ?: return null
                if (offset + 10 > dnsPayload.size) return null
                val type = ((dnsPayload[offset].toInt() and 0xFF) shl 8) or (dnsPayload[offset + 1].toInt() and 0xFF)
                val clazz = ((dnsPayload[offset + 2].toInt() and 0xFF) shl 8) or (dnsPayload[offset + 3].toInt() and 0xFF)
                val rdLen = ((dnsPayload[offset + 8].toInt() and 0xFF) shl 8) or (dnsPayload[offset + 9].toInt() and 0xFF)
                offset += 10
                if (offset + rdLen > dnsPayload.size) return null
                if (type == 1 && clazz == 1 && rdLen == 4) {
                    val ipBytes = dnsPayload.copyOfRange(offset, offset + 4)
                    return runCatching { InetAddress.getByAddress(ipBytes) }.getOrNull()
                }
                offset += rdLen
            }
            return null
        }

        private fun skipDnsName(dnsPayload: ByteArray, startOffset: Int): Int? {
            var offset = startOffset
            var steps = 0
            while (offset < dnsPayload.size && steps < 128) {
                steps++
                val len = dnsPayload[offset].toInt() and 0xFF
                if (len == 0) return offset + 1
                if ((len and 0xC0) == 0xC0) return if (offset + 2 <= dnsPayload.size) offset + 2 else null
                if ((len and 0xC0) != 0) return null // Invalid RFC 1035 label bits
                offset += 1 + len
            }
            return null
        }
    }

    private data class CachedBootstrapIp(val address: InetAddress, val expiresAtMs: Long)

    private val bootstrapCache = ConcurrentHashMap<String, CachedBootstrapIp>()
    private val consecutiveFailures = AtomicInteger(0)
    private val circuitOpenUntilMs = AtomicLong(Long.MIN_VALUE)
    private val lastFailureTelemetryMs = AtomicLong(-TELEMETRY_DEBOUNCE_MS)
    private val bootstrapTxSeq = AtomicInteger(0x4000)

    internal fun isCircuitOpen(): Boolean = clockMs() < circuitOpenUntilMs.get()

    fun resetState() {
        consecutiveFailures.set(0)
        circuitOpenUntilMs.set(Long.MIN_VALUE)
    }

    fun clearAll() {
        resetState()
        bootstrapCache.clear()
    }

    fun query(host: String, rawPayload: ByteArray, timeoutMs: Int = DEFAULT_DOT_TIMEOUT_MS): ByteArray? {
        val now = clockMs()
        if (now < circuitOpenUntilMs.get()) {
            return null
        }

        val framedQuery = buildFramedQuery(rawPayload) ?: return null
        val cleanHost = sanitizeHost(host)
        if (cleanHost.isBlank()) return null

        val targetIp = resolveBootstrapIp(cleanHost) ?: run {
            recordFailure(cleanHost, "Bootstrap IP resolution failed")
            return null
        }

        var plainSocket: Socket? = null
        var sslSocket: SSLSocket? = null
        return try {
            val tcp = Socket()
            plainSocket = tcp
            tcp.bind(null) // Allocate native OS socket FD before VpnService.protect()
            tcp.tcpNoDelay = true
            if (!vpnService.protect(tcp)) {
                Timber.tag(TAG).w("VpnService.protect() failed for DoT TCP socket")
                recordFailure(cleanHost, "VpnService.protect() returned false")
                return null
            }
            tcp.connect(InetSocketAddress(targetIp, DOT_PORT), timeoutMs)
            tcp.soTimeout = timeoutMs

            val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
            val raw = factory.createSocket(tcp, cleanHost, DOT_PORT, true) as SSLSocket
            sslSocket = raw
            raw.soTimeout = timeoutMs

            val sslParams = raw.sslParameters
            sslParams.serverNames = listOf(SNIHostName(cleanHost))
            sslParams.endpointIdentificationAlgorithm = "HTTPS"
            raw.sslParameters = sslParams

            raw.startHandshake()
            if (!HttpsURLConnection.getDefaultHostnameVerifier().verify(cleanHost, raw.session)) {
                Timber.tag(TAG).w("TLS hostname verification failed for DoT upstream")
                recordFailure(cleanHost, "TLS hostname verification failed")
                return null
            }

            // RFC 7858 2-byte length prefix framing sent in a single write
            raw.outputStream.write(framedQuery)
            raw.outputStream.flush()

            val inStream = DataInputStream(raw.inputStream)
            val respLen = inStream.readUnsignedShort()
            if (respLen < DnsPacketParser.DNS_HEADER_LEN || respLen > 4096) {
                recordFailure(cleanHost, "Invalid DoT response length: $respLen")
                return null
            }
            val dnsReply = ByteArray(respLen)
            inStream.readFully(dnsReply)

            if (!DnsWireValidator.isValidDnsResponse(dnsReply, dnsReply.size, rawPayload)) {
                Timber.tag(TAG).w("Discarded invalid DoT response payload")
                recordFailure(cleanHost, "Invalid DoT response payload")
                return null
            }

            consecutiveFailures.set(0)
            dnsReply
        } catch (e: Exception) {
            Timber.tag(TAG).d(e, "DoT upstream query failed")
            recordFailure(cleanHost, e.message)
            null
        } finally {
            try {
                sslSocket?.close()
            } catch (_: Exception) {
            }
            try {
                if (sslSocket == null) plainSocket?.close()
            } catch (_: Exception) {
            }
        }
    }

    private fun resolveBootstrapIp(cleanHost: String): InetAddress? {
        // 1. Exact known preset mapping (zero latency)
        when (cleanHost) {
            DevicePolicyBundle.CLOUDFLARE_FAMILY_DNS_HOST.lowercase() ->
                return DnsUpstreamForwarder.parseIpv4ToInetAddress(DnsUpstreamForwarder.PRIMARY_SAFE_DNS_IPV4)
            DevicePolicyBundle.ADGUARD_FAMILY_DNS_HOST.lowercase() ->
                return DnsUpstreamForwarder.parseIpv4ToInetAddress(DnsUpstreamForwarder.ADGUARD_FAMILY_PRIMARY)
            CLEANBROWSING_FAMILY_HOST ->
                return DnsUpstreamForwarder.parseIpv4ToInetAddress(DnsUpstreamForwarder.CLEANBROWSING_FAMILY_PRIMARY)
        }

        // 2. Direct IPv4 literal
        DnsUpstreamForwarder.parseIpv4ToInetAddress(cleanHost)?.let { return it }

        // 3. Cached bootstrap IP
        val now = clockMs()
        bootstrapCache[cleanHost]?.let { cached ->
            if (now < cached.expiresAtMs) return cached.address
        }

        // 4. Protected UDP bootstrap query to Safe Family DNS (never OS getByName)
        val txId = (bootstrapTxSeq.incrementAndGet() and 0xFFFF).toShort()
        val query = buildMinimalAQuery(cleanHost, txId) ?: return null
        val bootstrapReply = udpBootstrapQuery(DnsUpstreamForwarder.PRIMARY_SAFE_DNS_IPV4, query, 900)
            ?: udpBootstrapQuery(DnsUpstreamForwarder.SECONDARY_SAFE_DNS_IPV4, query, 900)
            ?: return null

        val resolved = extractFirstIpv4FromDnsResponse(bootstrapReply) ?: return null
        val hostStr = resolved.hostAddress ?: return null
        if (DnsWireValidator.isExcludedDnsIp(hostStr)) return null

        bootstrapCache[cleanHost] = CachedBootstrapIp(resolved, now + BOOTSTRAP_CACHE_TTL_MS)
        return resolved
    }

    private fun recordFailure(cleanHost: String, errorDetails: String?) {
        val now = clockMs()
        val failures = consecutiveFailures.incrementAndGet()
        if (failures >= CIRCUIT_FAILURE_THRESHOLD) {
            circuitOpenUntilMs.set(now + CIRCUIT_COOLDOWN_MS)
        }
        val prev = lastFailureTelemetryMs.get()
        if (now - prev >= TELEMETRY_DEBOUNCE_MS && lastFailureTelemetryMs.compareAndSet(prev, now)) {
            try {
                BlackBoxRecorder.record(
                    context = vpnService,
                    tag = BlackBoxRecorder.TAG_NETWORK,
                    actionName = "dnsUpstreamDotFail",
                    isSuccess = false,
                    errorDetails = errorDetails,
                    metadata = "host=$cleanHost,failures=$failures"
                )
            } catch (_: Exception) {
            }
        }
    }
}
