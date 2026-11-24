package com.drmohammed.antiporn.core.network.dns

import android.content.Context
import android.net.VpnService
import android.os.SystemClock
import androidx.annotation.VisibleForTesting
import com.drmohammed.antiporn.core.network.PhysicalNetworkResolver
import timber.log.Timber
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

data class LinkIpFamilies(
    val hasIpv4: Boolean,
    val hasIpv6: Boolean
)

/**
 * RFC 8484 DNS-over-HTTPS (DoH on TCP 443) upstream client protected via [VpnService.protect].
 * Connects directly to static high-availability dual-stack Family DNS IPv4/IPv6 addresses with
 * TLS SNI, eliminating any bootstrap DNS dependency and bypassing network-level DoT (Port 853) blocks.
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
    private val linkFamiliesProvider: (() -> LinkIpFamilies)? = null,
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

        private val handshakeWatchdog = Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "AntiPorn-DohHandshakeWatchdog").apply { isDaemon = true }
        }

        // Static Family Endpoints (Direct IPv4 with SNI; Zero Bootstrap DNS required!)
        val CLOUDFLARE_FAMILY_PRIMARY = DohEndpoint("1.1.1.3", "family.cloudflare-dns.com", "/dns-query")
        val CLOUDFLARE_FAMILY_SECONDARY = DohEndpoint("1.0.0.3", "family.cloudflare-dns.com", "/dns-query")
        val ADGUARD_FAMILY_PRIMARY = DohEndpoint("94.140.14.15", "family.adguard-dns.com", "/dns-query")
        val ADGUARD_FAMILY_SECONDARY = DohEndpoint("94.140.15.16", "family.adguard-dns.com", "/dns-query")
        val CLEANBROWSING_FAMILY_PRIMARY = DohEndpoint("185.228.168.168", "doh.cleanbrowsing.org", "/doh/family-filter/")
        val CLEANBROWSING_FAMILY_SECONDARY = DohEndpoint("185.228.169.168", "doh.cleanbrowsing.org", "/doh/family-filter/")

        // Static Family Endpoints (Direct IPv6 with SNI for IPv6-only / Dual-Stack networks)
        val CLOUDFLARE_FAMILY_V6_PRIMARY = DohEndpoint("2606:4700:4700::1113", "family.cloudflare-dns.com", "/dns-query")
        val CLOUDFLARE_FAMILY_V6_SECONDARY = DohEndpoint("2606:4700:4700::1003", "family.cloudflare-dns.com", "/dns-query")
        val ADGUARD_FAMILY_V6_PRIMARY = DohEndpoint("2a10:50c0::bad1:ff", "family.adguard-dns.com", "/dns-query")
        val ADGUARD_FAMILY_V6_SECONDARY = DohEndpoint("2a10:50c0::bad2:ff", "family.adguard-dns.com", "/dns-query")
        val CLEANBROWSING_FAMILY_V6_PRIMARY = DohEndpoint("2a0d:2a00:1::", "doh.cleanbrowsing.org", "/doh/family-filter/")
        val CLEANBROWSING_FAMILY_V6_SECONDARY = DohEndpoint("2a0d:2a00:2::", "doh.cleanbrowsing.org", "/doh/family-filter/")

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

        internal fun detectActivePhysicalLinkFamilies(context: Context): LinkIpFamilies =
            PhysicalNetworkResolver.detectActivePhysicalLinkFamilies(context)
    }

    private data class CircuitState(
        var failures: Int = 0,
        var openUntilMs: Long = Long.MIN_VALUE
    )

    private val circuits = ConcurrentHashMap<String, CircuitState>()
    private val activeSockets: MutableSet<Socket> = ConcurrentHashMap.newKeySet()
    private val wireExchange = DohHttpWireExchange(vpnService, clockMs, handshakeWatchdog, activeSockets)

    @Volatile
    private var cachedLinkFamilies: LinkIpFamilies? = null

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

    private fun isNoRouteOrTeardownFailure(error: Throwable?, reason: String): Boolean {
        if (DnsResilienceManager.isRestoringPrimaryNow()) return true
        val looksLikeNoRoute = error is NoRouteToHostException ||
            (error is ConnectException && (
                error.message?.contains("ENETUNREACH", ignoreCase = true) == true ||
                    error.message?.contains("EHOSTUNREACH", ignoreCase = true) == true ||
                    error.message?.contains("Network is unreachable", ignoreCase = true) == true
                )) ||
            reason.contains("ENETUNREACH", ignoreCase = true) ||
            reason.contains("Network is unreachable", ignoreCase = true)
        if (!looksLikeNoRoute) return false
        return PhysicalNetworkResolver.isDevicePhysicallyOffline(vpnService)
    }

    private fun recordFailure(endpoint: DohEndpoint, reason: String, error: Throwable? = null) {
        if (isNoRouteOrTeardownFailure(error, reason)) {
            Timber.tag(TAG).d("Suppressing DoH circuit breaker on offline/teardown error for ${endpoint.ip}: $reason")
            return
        }
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
        cachedLinkFamilies = null
        val snapshot = activeSockets.toList()
        activeSockets.clear()
        snapshot.forEach { wireExchange.closeQuietly(it) }
    }

    fun onNetworkChanged() {
        resetState()
    }

    fun clearAll() {
        resetState()
    }

    private fun resolveLinkFamilies(): LinkIpFamilies {
        linkFamiliesProvider?.let { return it() }
        cachedLinkFamilies?.let { return it }
        return detectActivePhysicalLinkFamilies(vpnService).also { cachedLinkFamilies = it }
    }

    /**
     * Resolves a raw DNS query over DNS-over-HTTPS (Port 443).
     * Rotates across reliable family endpoints respecting circuit breakers.
     */
    suspend fun query(
        rawPayload: ByteArray,
        timeoutMs: Int = DEFAULT_DOH_TIMEOUT_MS,
        preferredHost: String? = null
    ): ByteArray? {
        if (rawPayload.size !in DnsPacketParser.DNS_HEADER_LEN..4_096) return null

        val orderedEndpoints = getPrioritizedEndpoints(preferredHost)
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
                    wireExchange.execute(endpoint, rawPayload, epTimeout)
                }
            } catch (e: Exception) {
                recordFailure(endpoint, e.message ?: e.javaClass.simpleName, e)
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
    internal fun getPrioritizedEndpoints(
        preferredHost: String?,
        families: LinkIpFamilies = resolveLinkFamilies()
    ): List<DohEndpoint> {
        val clean = preferredHost?.lowercase()?.trim().orEmpty()
        val v4Preferred = when {
            clean.contains("adguard") -> listOf(ADGUARD_FAMILY_PRIMARY, ADGUARD_FAMILY_SECONDARY)
            clean.contains("cleanbrowsing") -> listOf(CLEANBROWSING_FAMILY_PRIMARY, CLEANBROWSING_FAMILY_SECONDARY)
            clean.contains("cloudflare") -> listOf(CLOUDFLARE_FAMILY_PRIMARY, CLOUDFLARE_FAMILY_SECONDARY)
            else -> emptyList()
        }
        val v6Preferred = when {
            clean.contains("adguard") -> listOf(ADGUARD_FAMILY_V6_PRIMARY, ADGUARD_FAMILY_V6_SECONDARY)
            clean.contains("cleanbrowsing") -> listOf(CLEANBROWSING_FAMILY_V6_PRIMARY, CLEANBROWSING_FAMILY_V6_SECONDARY)
            clean.contains("cloudflare") -> listOf(CLOUDFLARE_FAMILY_V6_PRIMARY, CLOUDFLARE_FAMILY_V6_SECONDARY)
            else -> emptyList()
        }

        val ordered = when {
            families.hasIpv6 && !families.hasIpv4 ->
                v6Preferred + DEFAULT_V6_ENDPOINTS + v4Preferred + DEFAULT_ENDPOINTS
            families.hasIpv6 && families.hasIpv4 ->
                v4Preferred + v6Preferred + DEFAULT_ENDPOINTS + DEFAULT_V6_ENDPOINTS
            else ->
                v4Preferred + DEFAULT_ENDPOINTS
        }
        return ordered.distinctBy { it.ip }
    }
}
