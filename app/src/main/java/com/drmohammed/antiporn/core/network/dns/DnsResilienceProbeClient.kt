package com.drmohammed.antiporn.core.network.dns

import android.net.ConnectivityManager
import android.net.Network
import android.os.SystemClock
import com.drmohammed.antiporn.core.model.DevicePolicyBundle
import com.drmohammed.antiporn.core.network.PhysicalNetworkResolver
import timber.log.Timber
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * Network-bound TLS 443 anti-spoof and RFC 7858 TLS 853 DoT connectivity probes
 * for [DnsResilienceManager]. Enforces cumulative handshake and read deadlines.
 */
internal object DnsResilienceProbeClient {

    private const val TAG = "AntiPorn_DnsResProbe"
    private const val ANTI_SPOOF_TIMEOUT_MS = 1_500
    private const val DOT_PROBE_TIMEOUT_MS = 2_000
    private const val DOT_PROBE_QUERY_DOMAIN = "example.com"

    internal val ALTERNATE_FAMILY_PRESETS = listOf(
        DevicePolicyBundle.ADGUARD_FAMILY_DNS_HOST,
        DevicePolicyBundle.CLOUDFLARE_FAMILY_DNS_HOST,
        DevicePolicyBundle.CONTROLD_NO_ADS_PORN_DNS_HOST,
        "family-filter-dns.cleanbrowsing.org"
    )

    private val PRESET_BOOTSTRAP_IPV4 = mapOf(
        DevicePolicyBundle.CLOUDFLARE_FAMILY_DNS_HOST to listOf("1.1.1.3", "1.0.0.3"),
        DevicePolicyBundle.ADGUARD_FAMILY_DNS_HOST to listOf("94.140.14.15", "94.140.15.16"),
        DevicePolicyBundle.CONTROLD_NO_ADS_PORN_DNS_HOST to listOf("76.76.2.11"),
        "family.controld.com" to listOf("76.76.2.3"),
        "family-filter-dns.cleanbrowsing.org" to listOf("185.228.168.168", "185.228.168.10")
    )

    private val PRESET_BOOTSTRAP_IPV6 = mapOf(
        DevicePolicyBundle.CLOUDFLARE_FAMILY_DNS_HOST to listOf("2606:4700:4700::1113", "2606:4700:4700::1003"),
        DevicePolicyBundle.ADGUARD_FAMILY_DNS_HOST to listOf("2a10:50c0::bad1:ff", "2a10:50c0::bad2:ff"),
        DevicePolicyBundle.CONTROLD_NO_ADS_PORN_DNS_HOST to listOf("2606:1a40::11"),
        "family-filter-dns.cleanbrowsing.org" to listOf("2a0d:2a00:1::", "2a0d:2a00:2::")
    )

    private data class DohTlsProbeEndpoint(
        val ip: String,
        val sniHost: String,
        val isIpv6: Boolean
    )

    private val ANTI_SPOOF_TLS443_ENDPOINTS = listOf(
        DohTlsProbeEndpoint("1.1.1.3", DevicePolicyBundle.CLOUDFLARE_FAMILY_DNS_HOST, isIpv6 = false),
        DohTlsProbeEndpoint("94.140.14.15", DevicePolicyBundle.ADGUARD_FAMILY_DNS_HOST, isIpv6 = false),
        DohTlsProbeEndpoint("2606:4700:4700::1113", DevicePolicyBundle.CLOUDFLARE_FAMILY_DNS_HOST, isIpv6 = true),
        DohTlsProbeEndpoint("2a10:50c0::bad1:ff", DevicePolicyBundle.ADGUARD_FAMILY_DNS_HOST, isIpv6 = true)
    )

    private val probeTxIdCounter = AtomicInteger(0x4000)
    private val handshakeWatchdog = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "AntiPorn-ResProbeWatchdog").apply { isDaemon = true }
    }

    private fun clockMs(): Long = try {
        SystemClock.elapsedRealtime()
    } catch (_: RuntimeException) {
        System.nanoTime() / 1_000_000L
    }

    fun detectLinkFamilies(cm: ConnectivityManager?, network: Network): LinkIpFamilies =
        PhysicalNetworkResolver.detectLinkFamilies(cm, network)

    fun resolveBootstrapCandidates(host: String, families: LinkIpFamilies): List<InetAddress> {
        val clean = host.lowercase().trim()
        val v4Strings = PRESET_BOOTSTRAP_IPV4[clean].orEmpty()
        val v6Strings = PRESET_BOOTSTRAP_IPV6[clean].orEmpty()
        val orderedStrings = when {
            families.hasIpv6 && !families.hasIpv4 -> v6Strings + v4Strings
            families.hasIpv6 && families.hasIpv4 -> v4Strings + v6Strings
            else -> v4Strings
        }
        return orderedStrings.mapNotNull { ipStr ->
            try {
                InetAddress.getByName(ipStr).takeUnless { DnsIpPolicy.isNonPublicOrReservedIp(it) }
            } catch (e: Exception) {
                Timber.tag(TAG).d(e, "Failed parsing bootstrap IP $ipStr for $clean")
                null
            }
        }
    }

    fun verifyPhysicalInternetAndDohReachable(
        network: Network,
        families: LinkIpFamilies = LinkIpFamilies(hasIpv4 = true, hasIpv6 = false),
        internetProbeOverride: ((Network) -> Boolean)? = null
    ): Boolean {
        internetProbeOverride?.let { return it(network) }
        val orderedEndpoints = when {
            families.hasIpv6 && !families.hasIpv4 ->
                ANTI_SPOOF_TLS443_ENDPOINTS.filter { it.isIpv6 } + ANTI_SPOOF_TLS443_ENDPOINTS.filterNot { it.isIpv6 }
            families.hasIpv6 && families.hasIpv4 -> ANTI_SPOOF_TLS443_ENDPOINTS
            else -> ANTI_SPOOF_TLS443_ENDPOINTS.filterNot { it.isIpv6 }
        }
        for (endpoint in orderedEndpoints) {
            if (verifyTls443Endpoint(network, endpoint)) {
                Timber.tag(TAG).d("Anti-Spoof TLS 443 probe succeeded via ${endpoint.ip}:443 (${endpoint.sniHost})")
                return true
            }
        }
        return false
    }

    private fun verifyTls443Endpoint(network: Network, endpoint: DohTlsProbeEndpoint): Boolean {
        var rawSocket: Socket? = null
        return try {
            val addr = InetAddress.getByName(endpoint.ip)
            rawSocket = network.socketFactory.createSocket()
            rawSocket.soTimeout = ANTI_SPOOF_TIMEOUT_MS
            rawSocket.connect(InetSocketAddress(addr, 443), ANTI_SPOOF_TIMEOUT_MS)
            verifyTls443OnSocket(rawSocket, endpoint.sniHost)
        } catch (e: Exception) {
            Timber.tag(TAG).d(e, "Anti-Spoof TLS 443 probe failed for ${endpoint.ip}")
            false
        } finally {
            closeQuietly(rawSocket)
        }
    }

    fun verifyTls443OnSocket(rawSocket: Socket, sniHost: String): Boolean {
        var sslSocket: SSLSocket? = null
        val deadlineMs = clockMs() + ANTI_SPOOF_TIMEOUT_MS
        return try {
            val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
            val wrapped = factory.createSocket(rawSocket, sniHost, 443, true) as SSLSocket
            sslSocket = wrapped
            val sslParams = wrapped.sslParameters
            sslParams.serverNames = listOf(SNIHostName(sniHost))
            sslParams.endpointIdentificationAlgorithm = "HTTPS"
            wrapped.sslParameters = sslParams

            val remaining = (deadlineMs - clockMs()).toInt().coerceAtLeast(100)
            wrapped.soTimeout = remaining
            runHandshakeWithWatchdog(wrapped, rawSocket, remaining.toLong())

            HttpsURLConnection.getDefaultHostnameVerifier().verify(sniHost, wrapped.session)
        } catch (e: Exception) {
            Timber.tag(TAG).d(e, "TLS 443 verification failed for $sniHost")
            false
        } finally {
            closeQuietly(sslSocket)
        }
    }

    fun testDotHostOnNetwork(
        network: Network,
        host: String,
        families: LinkIpFamilies = LinkIpFamilies(hasIpv4 = true, hasIpv6 = false),
        dotProbeOverride: ((Network, String) -> Boolean)? = null
    ): Boolean {
        dotProbeOverride?.let { return it(network, host) }
        val resolvedCandidates = try {
            val addrs = network.getAllByName(host).orEmpty().filterNotNull()
                .filterNot { DnsIpPolicy.isNonPublicOrReservedIp(it) }
            if (families.hasIpv6 && !families.hasIpv4) {
                addrs.filterIsInstance<Inet6Address>() + addrs.filterIsInstance<Inet4Address>()
            } else if (families.hasIpv6) {
                addrs.filterIsInstance<Inet4Address>() + addrs.filterIsInstance<Inet6Address>()
            } else {
                addrs.filterIsInstance<Inet4Address>()
            }
        } catch (e: Exception) {
            Timber.tag(TAG).d(e, "Network.getAllByName failed for DoT probe host=$host")
            emptyList()
        }
        val candidates = (resolvedCandidates + resolveBootstrapCandidates(host, families)).distinct()
        if (candidates.isEmpty()) {
            Timber.tag(TAG).d("No candidate IPs resolved for DoT probe host=$host")
            return false
        }

        val hostDeadlineMs = clockMs() + (DOT_PROBE_TIMEOUT_MS * 2L)
        for (candidateAddr in candidates) {
            val remaining = (hostDeadlineMs - clockMs()).toInt()
            if (remaining < 300) break
            if (probeSingleDotEndpoint(network, host, candidateAddr, remaining.coerceAtMost(DOT_PROBE_TIMEOUT_MS))) {
                return true
            }
        }
        return false
    }

    private fun probeSingleDotEndpoint(
        network: Network,
        host: String,
        targetAddr: InetAddress,
        timeoutMs: Int
    ): Boolean {
        var rawSocket: Socket? = null
        var sslSocket: SSLSocket? = null
        val deadlineMs = clockMs() + timeoutMs
        return try {
            rawSocket = network.socketFactory.createSocket()
            rawSocket.soTimeout = timeoutMs
            rawSocket.connect(InetSocketAddress(targetAddr, 853), timeoutMs)

            val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
            val wrapped = factory.createSocket(rawSocket, host, 853, true) as SSLSocket
            sslSocket = wrapped
            val sslParams = wrapped.sslParameters
            sslParams.serverNames = listOf(SNIHostName(host))
            sslParams.endpointIdentificationAlgorithm = "HTTPS"
            wrapped.sslParameters = sslParams

            val handshakeTimeout = (deadlineMs - clockMs()).toInt().coerceAtLeast(100)
            wrapped.soTimeout = handshakeTimeout
            runHandshakeWithWatchdog(wrapped, rawSocket, handshakeTimeout.toLong())

            if (!HttpsURLConnection.getDefaultHostnameVerifier().verify(host, wrapped.session)) {
                return false
            }
            exchangeRfc7858ProbeOnSocket(wrapped, deadlineMs)
        } catch (e: Exception) {
            Timber.tag(TAG).d("DoT probe failed for $host (${targetAddr.hostAddress}): ${e.message}")
            false
        } finally {
            closeQuietly(sslSocket)
            closeQuietly(rawSocket)
        }
    }

    fun exchangeRfc7858ProbeOnSocket(socket: Socket, deadlineMs: Long = clockMs() + DOT_PROBE_TIMEOUT_MS): Boolean {
        val txId = ((probeTxIdCounter.incrementAndGet()) and 0xFFFF).toShort()
        val rawQuery = DotBootstrapResolver.buildMinimalAQuery(DOT_PROBE_QUERY_DOMAIN, txId) ?: return false
        val framedQuery = DotUpstreamClient.buildFramedQuery(rawQuery) ?: return false
        val out = DataOutputStream(socket.getOutputStream())
        out.write(framedQuery)
        out.flush()

        val input = DataInputStream(socket.getInputStream())
        val lenBuf = ByteArray(2)
        if (!readFullyWithDeadline(socket, input, lenBuf, deadlineMs)) {
            return false
        }
        val responseLength = ((lenBuf[0].toInt() and 0xFF) shl 8) or (lenBuf[1].toInt() and 0xFF)
        if (responseLength < DnsPacketParser.DNS_HEADER_LEN || responseLength > 4096) {
            return false
        }
        val responseBytes = ByteArray(responseLength)
        if (!readFullyWithDeadline(socket, input, responseBytes, deadlineMs)) {
            return false
        }
        return DnsWireValidator.isValidDnsResponse(responseBytes, responseLength, rawQuery) &&
            !DnsWireValidator.isSoftFailureResponse(responseBytes)
    }

    private fun readFullyWithDeadline(
        socket: Socket,
        input: DataInputStream,
        dst: ByteArray,
        deadlineMs: Long
    ): Boolean {
        var offset = 0
        while (offset < dst.size) {
            val remaining = (deadlineMs - clockMs()).toInt()
            if (remaining <= 0) return false
            try {
                socket.soTimeout = remaining.coerceAtLeast(1)
            } catch (_: Exception) {
                // Socket implementations in unit tests may ignore soTimeout updates
            }
            val n = try {
                input.read(dst, offset, dst.size - offset)
            } catch (_: EOFException) {
                return false
            }
            if (n == -1) return false
            offset += n
        }
        return true
    }

    private fun runHandshakeWithWatchdog(sslSocket: SSLSocket, rawSocket: Socket, timeoutMs: Long) {
        val task = handshakeWatchdog.schedule(
            {
                closeQuietly(sslSocket)
                closeQuietly(rawSocket)
            },
            timeoutMs,
            TimeUnit.MILLISECONDS
        )
        try {
            sslSocket.startHandshake()
        } finally {
            task.cancel(false)
        }
    }

    private fun closeQuietly(socket: Socket?) {
        try {
            socket?.close()
        } catch (_: Exception) {
        }
    }
}
