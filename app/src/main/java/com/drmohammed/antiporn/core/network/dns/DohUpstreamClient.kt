package com.drmohammed.antiporn.core.network.dns

import android.content.Context
import android.net.VpnService
import android.os.SystemClock
import androidx.annotation.VisibleForTesting
import com.drmohammed.antiporn.core.network.PhysicalNetworkResolver
import kotlinx.coroutines.CancellationException
import timber.log.Timber
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * RFC 8484 DNS-over-HTTPS (DoH on TCP 443) upstream client protected via [VpnService.protect].
 * Connects directly to static high-availability dual-stack Family DNS IPv4/IPv6 addresses
 * (including dynamic NextDNS and Control D custom profiles) with TLS SNI, eliminating any
 * bootstrap DNS dependency and bypassing network-level DoT (Port 853) blocks.
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

        internal fun detectActivePhysicalLinkFamilies(context: Context): LinkIpFamilies =
            PhysicalNetworkResolver.detectActivePhysicalLinkFamilies(context)
    }

    private data class CircuitState(
        var failures: Int = 0,
        @Volatile var openUntilMs: Long = Long.MIN_VALUE
    )

    private val circuits = ConcurrentHashMap<String, CircuitState>()
    @VisibleForTesting
    internal val connectionPool = DohConnectionPool(clockMs = clockMs)
    @VisibleForTesting
    internal var wireConnectionFactory: ((DohEndpoint, Int) -> DohSocketConnection)? = null
    private val wireExchange = DohHttpWireExchange(
        vpnService = vpnService,
        clockMs = clockMs,
        handshakeWatchdog = handshakeWatchdog,
        pool = connectionPool,
        connectionFactoryProvider = { wireConnectionFactory }
    )

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

    private fun recordFailure(
        endpoint: DohEndpoint,
        reason: String,
        expectedGeneration: Long,
        error: Throwable? = null
    ) {
        if (connectionPool.currentGeneration() != expectedGeneration) {
            Timber.tag(TAG).d("Skipping stale-generation DoH failure for ${endpoint.ip}: $reason")
            return
        }
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
        val detached = connectionPool.detachAllForInvalidation()
        circuits.clear()
        cachedLinkFamilies = null
        connectionPool.closeDetached(detached)
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
        preferredHost: String? = null,
        families: LinkIpFamilies = resolveLinkFamilies()
    ): ByteArray? {
        if (rawPayload.size !in DnsPacketParser.DNS_HEADER_LEN..4_096) return null

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

            val attemptGen = connectionPool.currentGeneration()
            val reply = try {
                if (exchangeExecutor != null) {
                    exchangeExecutor.invoke(endpoint, rawPayload, epTimeout)
                } else {
                    wireExchange.execute(endpoint, rawPayload, epTimeout)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                recordFailure(endpoint, e.message ?: e.javaClass.simpleName, attemptGen, e)
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
        families: LinkIpFamilies = resolveLinkFamilies()
    ): List<DohEndpoint> {
        val (dynV4, dynV6) = buildDynamicCustomDohEndpoints(preferredHost)
        val clean = preferredHost?.lowercase()?.trim()?.trimEnd('.').orEmpty()

        val (v4Preferred, v6Preferred) = if (dynV4.isNotEmpty() || dynV6.isNotEmpty()) {
            dynV4 to dynV6
        } else {
            when {
                clean.contains("adguard") ->
                    listOf(ADGUARD_FAMILY_PRIMARY, ADGUARD_FAMILY_SECONDARY) to listOf(ADGUARD_FAMILY_V6_PRIMARY, ADGUARD_FAMILY_V6_SECONDARY)
                clean.contains("cleanbrowsing") ->
                    listOf(CLEANBROWSING_FAMILY_PRIMARY, CLEANBROWSING_FAMILY_SECONDARY) to listOf(CLEANBROWSING_FAMILY_V6_PRIMARY, CLEANBROWSING_FAMILY_V6_SECONDARY)
                clean.contains("cloudflare") ->
                    listOf(CLOUDFLARE_FAMILY_PRIMARY, CLOUDFLARE_FAMILY_SECONDARY) to listOf(CLOUDFLARE_FAMILY_V6_PRIMARY, CLOUDFLARE_FAMILY_V6_SECONDARY)
                else -> emptyList<DohEndpoint>() to emptyList<DohEndpoint>()
            }
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
