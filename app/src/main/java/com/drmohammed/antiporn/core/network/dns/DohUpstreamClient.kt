package com.drmohammed.antiporn.core.network.dns

import android.net.VpnService
import android.os.SystemClock
import androidx.annotation.VisibleForTesting
import timber.log.Timber
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * RFC 8484 DNS-over-HTTPS (DoH on TCP 443) upstream client protected via [VpnService.protect].
 * Connects directly to static high-availability Family DNS IPv4 addresses with TLS SNI,
 * eliminating any bootstrap DNS dependency and bypassing network-level DoT (Port 853) blocks.
 */
class DohUpstreamClient(
    private val vpnService: VpnService,
    private val clockMs: () -> Long = {
        try {
            SystemClock.elapsedRealtime()
        } catch (_: RuntimeException) {
            System.nanoTime() / 1_000_000L
        }
    },
    private val exchangeExecutor: (suspend (endpoint: DohEndpoint, rawPayload: ByteArray, timeoutMs: Int) -> ByteArray?)? = null
) {

    data class DohEndpoint(
        val ip: String,
        val host: String,
        val path: String
    )

    companion object {
        private const val TAG = "AntiPorn_DohClient"
        const val DOH_PORT = 443
        const val DEFAULT_DOH_TIMEOUT_MS = 2_000
        private const val CIRCUIT_FAILURE_THRESHOLD = 3
        private const val CIRCUIT_COOLDOWN_MS = 30_000L
        private const val MAX_HTTP_HEADER_BYTES = 8_192
        private const val MAX_DOH_RESPONSE_BYTES = 4_096

        private val handshakeWatchdog = java.util.concurrent.Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "AntiPorn-DohHandshakeWatchdog").apply { isDaemon = true }
        }

        // Static Family Endpoints (Direct IPv4 with SNI; Zero Bootstrap DNS required!)
        val CLOUDFLARE_FAMILY_PRIMARY = DohEndpoint("1.1.1.3", "family.cloudflare-dns.com", "/dns-query")
        val CLOUDFLARE_FAMILY_SECONDARY = DohEndpoint("1.0.0.3", "family.cloudflare-dns.com", "/dns-query")
        val ADGUARD_FAMILY_PRIMARY = DohEndpoint("94.140.14.15", "family.adguard-dns.com", "/dns-query")
        val ADGUARD_FAMILY_SECONDARY = DohEndpoint("94.140.15.16", "family.adguard-dns.com", "/dns-query")
        val CLEANBROWSING_FAMILY_PRIMARY = DohEndpoint("185.228.168.168", "doh.cleanbrowsing.org", "/doh/family-filter/")
        val CLEANBROWSING_FAMILY_SECONDARY = DohEndpoint("185.228.169.168", "doh.cleanbrowsing.org", "/doh/family-filter/")

        val CLOUDFLARE_FAMILY_V6_PRIMARY = DohEndpoint("2606:4700:4700::1113", "family.cloudflare-dns.com", "/dns-query")
        val CLOUDFLARE_FAMILY_V6_SECONDARY = DohEndpoint("2606:4700:4700::1003", "family.cloudflare-dns.com", "/dns-query")
        val ADGUARD_FAMILY_V6_PRIMARY = DohEndpoint("2a10:50c0::bad1:ff", "family.adguard-dns.com", "/dns-query")
        val ADGUARD_FAMILY_V6_SECONDARY = DohEndpoint("2a10:50c0::bad2:ff", "family.adguard-dns.com", "/dns-query")
        val CLEANBROWSING_FAMILY_V6_PRIMARY = DohEndpoint("2a0d:2a00:1::", "doh.cleanbrowsing.org", "/doh/family-filter/")
        val CLEANBROWSING_FAMILY_V6_SECONDARY = DohEndpoint("2a0d:2a00:2::", "doh.cleanbrowsing.org", "/doh/family-filter/")

        // Official Anycast Constants for NextDNS and Control D
        const val NEXTDNS_IPV4_PRIMARY = "45.90.28.0"
        const val NEXTDNS_IPV4_SECONDARY = "45.90.30.0"
        const val NEXTDNS_IPV6_PRIMARY = "2a07:a8c0::"
        const val NEXTDNS_IPV6_SECONDARY = "2a07:a8c1::"
        const val NEXTDNS_HOST = "dns.nextdns.io"

        const val CONTROLD_FREEDNS_IPV4_PRIMARY = "76.76.2.11"
        const val CONTROLD_FREEDNS_IPV4_SECONDARY = "76.76.10.11"
        const val CONTROLD_FREEDNS_IPV6_PRIMARY = "2606:1a40::11"
        const val CONTROLD_FREEDNS_IPV6_SECONDARY = "2606:1a40:1::11"
        const val CONTROLD_FREEDNS_HOST = "freedns.controld.com"

        const val CONTROLD_CUSTOM_IPV4_PRIMARY = "76.76.2.22"
        const val CONTROLD_CUSTOM_IPV4_SECONDARY = "76.76.10.22"
        const val CONTROLD_CUSTOM_IPV6_PRIMARY = "2606:1a40::22"
        const val CONTROLD_CUSTOM_IPV6_SECONDARY = "2606:1a40:1::22"
        const val CONTROLD_CUSTOM_HOST = "dns.controld.com"

        val DEFAULT_ENDPOINTS = listOf(
            CLOUDFLARE_FAMILY_PRIMARY,
            ADGUARD_FAMILY_PRIMARY,
            CLOUDFLARE_FAMILY_SECONDARY,
            CLEANBROWSING_FAMILY_PRIMARY
        )

        val DEFAULT_V6_ENDPOINTS = listOf(
            CLOUDFLARE_FAMILY_V6_PRIMARY,
            ADGUARD_FAMILY_V6_PRIMARY,
            CLOUDFLARE_FAMILY_V6_SECONDARY,
            CLEANBROWSING_FAMILY_V6_PRIMARY
        )
    }

    private data class CircuitState(
        var failures: Int = 0,
        @Volatile var openUntilMs: Long = Long.MIN_VALUE
    )

    private val circuits = ConcurrentHashMap<String, CircuitState>()

    fun isCircuitOpen(endpoint: DohEndpoint): Boolean {
        val state = circuits[endpoint.ip] ?: return false
        return clockMs() < state.openUntilMs
    }

    private fun recordSuccess(endpoint: DohEndpoint) {
        circuits[endpoint.ip]?.let { state ->
            synchronized(state) {
                state.failures = 0
                state.openUntilMs = Long.MIN_VALUE
            }
        }
    }

    private fun recordFailure(endpoint: DohEndpoint, reason: String) {
        val state = circuits.computeIfAbsent(endpoint.ip) { CircuitState() }
        synchronized(state) {
            state.failures++
            if (state.failures >= CIRCUIT_FAILURE_THRESHOLD) {
                state.openUntilMs = clockMs() + CIRCUIT_COOLDOWN_MS
                Timber.tag(TAG).w("DoH circuit opened for ${endpoint.ip} (${endpoint.host}) for ${CIRCUIT_COOLDOWN_MS}ms. Reason: $reason")
            } else {
                Timber.tag(TAG).d("DoH failure (${state.failures}/$CIRCUIT_FAILURE_THRESHOLD) for ${endpoint.ip}: $reason")
            }
        }
    }

    fun resetState() {
        circuits.clear()
    }

    fun onNetworkChanged() {
        resetState()
    }

    fun clearAll() {
        resetState()
    }

    /**
     * Resolves a raw DNS query over DNS-over-HTTPS (Port 443).
     * Rotates across reliable family endpoints respecting circuit breakers.
     */
    suspend fun query(
        rawPayload: ByteArray,
        timeoutMs: Int = DEFAULT_DOH_TIMEOUT_MS,
        preferredHost: String? = null,
        families: LinkIpFamilies = LinkIpFamilies(hasIpv4 = true, hasIpv6 = false)
    ): ByteArray? {
        if (rawPayload.size !in DnsPacketParser.DNS_HEADER_LEN..MAX_DOH_RESPONSE_BYTES) return null

        val orderedEndpoints = getPrioritizedEndpoints(preferredHost, families)
        val queryDeadlineMs = clockMs() + timeoutMs

        for (endpoint in orderedEndpoints) {
            val remaining = (queryDeadlineMs - clockMs()).toInt()
            if (remaining < 250) break
            val epTimeout = minOf(remaining, 1_000)

            if (isCircuitOpen(endpoint)) {
                Timber.tag(TAG).d("Skipping open circuit endpoint: ${endpoint.ip}")
                continue
            }

            val reply = try {
                if (exchangeExecutor != null) {
                    exchangeExecutor.invoke(endpoint, rawPayload, epTimeout)
                } else {
                    executeDohHttp(endpoint, rawPayload, epTimeout)
                }
            } catch (e: Exception) {
                recordFailure(endpoint, e.message ?: e.javaClass.simpleName)
                null
            }

            if (reply != null && reply.size >= DnsPacketParser.DNS_HEADER_LEN) {
                recordSuccess(endpoint)
                return reply
            }
        }
        return null
    }

    @VisibleForTesting
    internal fun buildDynamicCustomDohEndpoints(preferredHost: String?): Pair<List<DohEndpoint>, List<DohEndpoint>> {
        val clean = preferredHost?.lowercase()?.trim()?.trimEnd('.').orEmpty()
        if (clean.isBlank()) return emptyList<DohEndpoint>() to emptyList<DohEndpoint>()

        // NextDNS Case
        if (clean.endsWith(".dns.nextdns.io")) {
            val prefix = clean.removeSuffix(".dns.nextdns.io")
            if (prefix.length in 4..128 && !prefix.startsWith('-') && !prefix.endsWith('-') && prefix.matches(Regex("^[a-z0-9-]+$"))) {
                val path = if (!prefix.contains('-')) {
                    "/$prefix"
                } else {
                    val profileId = prefix.substringAfterLast('-')
                    val deviceLabel = prefix.substringBeforeLast('-')
                    if (profileId.matches(Regex("^[a-f0-9]{4,12}$")) && deviceLabel.isNotBlank()) {
                        "/$profileId/$deviceLabel"
                    } else {
                        "/$prefix"
                    }
                }
                val v4 = listOf(
                    DohEndpoint(NEXTDNS_IPV4_PRIMARY, NEXTDNS_HOST, path),
                    DohEndpoint(NEXTDNS_IPV4_SECONDARY, NEXTDNS_HOST, path)
                )
                val v6 = listOf(
                    DohEndpoint(NEXTDNS_IPV6_PRIMARY, NEXTDNS_HOST, path),
                    DohEndpoint(NEXTDNS_IPV6_SECONDARY, NEXTDNS_HOST, path)
                )
                return v4 to v6
            }
        }

        // Control D FreeDNS Case
        if (clean.endsWith(".freedns.controld.com")) {
            val filterSlug = clean.removeSuffix(".freedns.controld.com")
            if (filterSlug.length in 1..128 && !filterSlug.startsWith('-') && !filterSlug.endsWith('-') && filterSlug.matches(Regex("^[a-z0-9-]+$"))) {
                val path = "/$filterSlug"
                val v4 = listOf(
                    DohEndpoint(CONTROLD_FREEDNS_IPV4_PRIMARY, CONTROLD_FREEDNS_HOST, path),
                    DohEndpoint(CONTROLD_FREEDNS_IPV4_SECONDARY, CONTROLD_FREEDNS_HOST, path)
                )
                val v6 = listOf(
                    DohEndpoint(CONTROLD_FREEDNS_IPV6_PRIMARY, CONTROLD_FREEDNS_HOST, path),
                    DohEndpoint(CONTROLD_FREEDNS_IPV6_SECONDARY, CONTROLD_FREEDNS_HOST, path)
                )
                return v4 to v6
            }
        }

        // Control D Paid/Custom Case
        if (clean.endsWith(".dns.controld.com")) {
            val resolverId = clean.removeSuffix(".dns.controld.com")
            if (resolverId.length in 2..64 && !resolverId.startsWith('-') && !resolverId.endsWith('-') && resolverId.matches(Regex("^[a-z0-9-]+$"))) {
                val path = "/$resolverId"
                val v4 = listOf(
                    DohEndpoint(CONTROLD_CUSTOM_IPV4_PRIMARY, CONTROLD_CUSTOM_HOST, path),
                    DohEndpoint(CONTROLD_CUSTOM_IPV4_SECONDARY, CONTROLD_CUSTOM_HOST, path)
                )
                val v6 = listOf(
                    DohEndpoint(CONTROLD_CUSTOM_IPV6_PRIMARY, CONTROLD_CUSTOM_HOST, path),
                    DohEndpoint(CONTROLD_CUSTOM_IPV6_SECONDARY, CONTROLD_CUSTOM_HOST, path)
                )
                return v4 to v6
            }
        }

        return emptyList<DohEndpoint>() to emptyList<DohEndpoint>()
    }

    @VisibleForTesting
    internal fun getPrioritizedEndpoints(
        preferredHost: String?,
        families: LinkIpFamilies = LinkIpFamilies(hasIpv4 = true, hasIpv6 = false)
    ): List<DohEndpoint> {
        val (dynV4, dynV6) = buildDynamicCustomDohEndpoints(preferredHost)
        val clean = preferredHost?.lowercase()?.trim()?.trimEnd('.').orEmpty()

        val (v4Preferred, v6Preferred) = if (dynV4.isNotEmpty() || dynV6.isNotEmpty()) {
            dynV4 to dynV6
        } else {
            when {
                clean.contains("adguard") -> listOf(ADGUARD_FAMILY_PRIMARY, ADGUARD_FAMILY_SECONDARY) to listOf(ADGUARD_FAMILY_V6_PRIMARY, ADGUARD_FAMILY_V6_SECONDARY)
                clean.contains("cleanbrowsing") -> listOf(CLEANBROWSING_FAMILY_PRIMARY, CLEANBROWSING_FAMILY_SECONDARY) to listOf(CLEANBROWSING_FAMILY_V6_PRIMARY, CLEANBROWSING_FAMILY_V6_SECONDARY)
                clean.contains("cloudflare") -> listOf(CLOUDFLARE_FAMILY_PRIMARY, CLOUDFLARE_FAMILY_SECONDARY) to listOf(CLOUDFLARE_FAMILY_V6_PRIMARY, CLOUDFLARE_FAMILY_V6_SECONDARY)
                else -> emptyList<DohEndpoint>() to emptyList<DohEndpoint>()
            }
        }

        val prioritized = mutableListOf<DohEndpoint>()
        val fallbacks = mutableListOf<DohEndpoint>()

        if (families.hasIpv4) {
            prioritized.addAll(v4Preferred)
            fallbacks.addAll(DEFAULT_ENDPOINTS)
        }
        if (families.hasIpv6) {
            prioritized.addAll(v6Preferred)
            fallbacks.addAll(DEFAULT_V6_ENDPOINTS)
        }
        if (!families.hasIpv4 && !families.hasIpv6) {
            prioritized.addAll(v4Preferred)
            fallbacks.addAll(DEFAULT_ENDPOINTS)
        }

        return (prioritized + fallbacks).distinctBy { it.ip }
    }

    /**
     * Sends an RFC 8484 binary POST request to the target DoH endpoint over a protected TLS socket.
     */
    private fun executeDohHttp(endpoint: DohEndpoint, rawPayload: ByteArray, timeoutMs: Int): ByteArray? {
        var baseSocket: Socket? = null
        var sslSocket: SSLSocket? = null
        val deadlineMs = clockMs() + timeoutMs

        try {
            baseSocket = Socket()
            baseSocket.tcpNoDelay = true
            // CRITICAL: Protect socket so it bypasses our local VPN TUN interface and routes via physical network
            if (!vpnService.protect(baseSocket)) {
                baseSocket.close()
                throw IOException("VpnService.protect() failed for DoH socket to ${endpoint.ip}")
            }

            val connectTimeout = (deadlineMs - clockMs()).toInt().coerceIn(100, timeoutMs)
            baseSocket.soTimeout = connectTimeout
            baseSocket.connect(InetSocketAddress(endpoint.ip, DOH_PORT), connectTimeout)

            val sslFactory = SSLSocketFactory.getDefault() as? SSLSocketFactory
                ?: throw IOException("Default SSLSocketFactory unavailable")
            val wrapped = sslFactory.createSocket(baseSocket, endpoint.host, DOH_PORT, true) as? SSLSocket
                ?: throw IOException("Failed to wrap socket with SSLSocket")
            sslSocket = wrapped

            val sslParams = wrapped.sslParameters
            sslParams.serverNames = listOf(SNIHostName(endpoint.host))
            sslParams.endpointIdentificationAlgorithm = "HTTPS"
            wrapped.sslParameters = sslParams

            val handshakeTimeout = (deadlineMs - clockMs()).toInt().coerceIn(100, timeoutMs)
            wrapped.soTimeout = handshakeTimeout

            val handshakeDeadlineTask = handshakeWatchdog.schedule(
                {
                    try {
                        wrapped.close()
                    } catch (_: Exception) {}
                    try {
                        baseSocket.close()
                    } catch (_: Exception) {}
                },
                handshakeTimeout.toLong(),
                java.util.concurrent.TimeUnit.MILLISECONDS
            )

            try {
                wrapped.startHandshake()
            } finally {
                handshakeDeadlineTask.cancel(false)
            }

            if (!HttpsURLConnection.getDefaultHostnameVerifier().verify(endpoint.host, wrapped.session)) {
                throw IOException("TLS certificate hostname verification failed for ${endpoint.host}")
            }

            val readTimeout = (deadlineMs - clockMs()).toInt().coerceAtLeast(100)
            wrapped.soTimeout = readTimeout

            val outStream = DataOutputStream(wrapped.outputStream)
            val inStream = DataInputStream(wrapped.inputStream)

            // RFC 8484 POST Request
            val requestHeader = "POST ${endpoint.path} HTTP/1.1\r\n" +
                "Host: ${endpoint.host}\r\n" +
                "User-Agent: AntiPorn-DoH\r\n" +
                "Accept: application/dns-message\r\n" +
                "Content-Type: application/dns-message\r\n" +
                "Content-Length: ${rawPayload.size}\r\n" +
                "Connection: close\r\n\r\n"

            outStream.write(requestHeader.toByteArray(Charsets.US_ASCII))
            outStream.write(rawPayload)
            outStream.flush()

            // Read HTTP Response Status Line
            val statusLine = readLineAscii(inStream, deadlineMs) ?: throw IOException("Empty HTTP response from DoH server")
            val statusParts = statusLine.split(" ")
            if (statusParts.size < 2 || statusParts[1] != "200") {
                throw IOException("DoH server returned non-200 HTTP status: $statusLine")
            }

            // Read Headers
            var contentLength = -1
            var isChunked = false

            while (true) {
                val headerLine = readLineAscii(inStream, deadlineMs) ?: break
                if (headerLine.isEmpty()) break // End of headers

                val colonIdx = headerLine.indexOf(':')
                if (colonIdx > 0) {
                    val name = headerLine.substring(0, colonIdx).trim().lowercase()
                    val value = headerLine.substring(colonIdx + 1).trim()
                    when (name) {
                        "content-length" -> contentLength = value.toIntOrNull() ?: -1
                        "transfer-encoding" -> isChunked = value.contains("chunked", ignoreCase = true)
                    }
                }
            }

            // Read Body
            val responseBody = when {
                contentLength in 0..MAX_DOH_RESPONSE_BYTES -> {
                    val buf = ByteArray(contentLength)
                    inStream.readFully(buf)
                    buf
                }
                isChunked -> {
                    readChunkedBody(inStream, deadlineMs)
                }
                contentLength > MAX_DOH_RESPONSE_BYTES -> {
                    throw IOException("DoH response body too large: $contentLength bytes")
                }
                else -> {
                    readBodyUntilEof(inStream, deadlineMs)
                }
            }

            if (responseBody.size < DnsPacketParser.DNS_HEADER_LEN) {
                throw IOException("DoH response body too short: ${responseBody.size} bytes")
            }
            if (!DnsWireValidator.isValidDnsResponse(responseBody, responseBody.size, rawPayload)) {
                throw IOException("DoH response failed wire validation for ${endpoint.host}")
            }
            return responseBody
        } finally {
            try {
                sslSocket?.close()
            } catch (_: Exception) {}
            try {
                baseSocket?.close()
            } catch (_: Exception) {}
        }
    }

    private fun readLineAscii(inStream: DataInputStream, deadlineMs: Long): String? {
        val sb = StringBuilder(64)
        while (sb.length < MAX_HTTP_HEADER_BYTES) {
            val remaining = (deadlineMs - clockMs()).toInt()
            if (remaining <= 0) throw SocketTimeoutException("DoH header read deadline exceeded")
            val b = inStream.read()
            if (b == -1) {
                return if (sb.isEmpty()) null else sb.toString()
            }
            val c = b.toChar()
            if (c == '\n') {
                return sb.toString().trimEnd('\r')
            }
            sb.append(c)
        }
        throw IOException("HTTP header line exceeded maximum length")
    }

    private fun readChunkedBody(inStream: DataInputStream, deadlineMs: Long): ByteArray {
        val out = ByteArrayOutputStream()
        while (true) {
            val hexLenStr = readLineAscii(inStream, deadlineMs) ?: throw EOFException("Unexpected EOF reading chunk length")
            val cleanHex = hexLenStr.substringBefore(';').trim()
            val chunkSize = cleanHex.toIntOrNull(16) ?: throw IOException("Invalid chunk length: $hexLenStr")
            if (chunkSize <= 0) {
                // Read trailing empty line
                readLineAscii(inStream, deadlineMs)
                break
            }
            if (out.size() + chunkSize > MAX_DOH_RESPONSE_BYTES) {
                throw IOException("Chunked DoH body exceeded maximum size")
            }
            val chunkBuf = ByteArray(chunkSize)
            inStream.readFully(chunkBuf)
            out.write(chunkBuf)
            // Consume trailing \r\n or \n after chunk data using readLineAscii
            readLineAscii(inStream, deadlineMs)
        }
        return out.toByteArray()
    }

    private fun readBodyUntilEof(inStream: DataInputStream, deadlineMs: Long): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(1024)
        while (true) {
            val remaining = (deadlineMs - clockMs()).toInt()
            if (remaining <= 0) throw SocketTimeoutException("DoH response read deadline exceeded")
            val count = inStream.read(buf)
            if (count == -1) break
            if (out.size() + count > MAX_DOH_RESPONSE_BYTES) {
                throw IOException("DoH response body exceeded maximum size ($MAX_DOH_RESPONSE_BYTES bytes)")
            }
            out.write(buf, 0, count)
        }
        return out.toByteArray()
    }
}
