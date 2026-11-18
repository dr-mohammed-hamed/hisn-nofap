package com.drmohammed.antiporn.core.network.dns

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.SystemClock
import com.drmohammed.antiporn.core.model.DevicePolicyBundle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import timber.log.Timber
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Enterprise-grade DNS Upstream Forwarder implementing 3-tier failover,
 * generation-fenced micro LRU caching, bounded concurrency, unified query deadlines,
 * tracked UDP sockets, strict leak prevention, and YouTube comments preservation.
 */
class DnsUpstreamForwarder(
    private val vpnService: VpnService,
    private val lruCache: DnsLruCache = DnsLruCache(512),
    private val udpQueryExecutor: ((ipAddress: String, rawPayload: ByteArray, timeoutMs: Int) -> ByteArray?)? = null,
    private val dotQueryExecutor: ((host: String, rawPayload: ByteArray, timeoutMs: Int) -> ByteArray?)? = null
) {

    companion object {
        private const val TAG = "AntiPorn_DnsForwarder"
        private const val DOT_TIMEOUT_MS = 1_500
        private const val TIER1_UDP_TIMEOUT_MS = 900
        private const val TIER2_UDP_TIMEOUT_MS = 1_200
        private const val TIER3_UDP_TIMEOUT_MS = 1_500

        // Tier 1: Cloudflare Family (Strict adult filtering with YouTube comments preserved)
        const val PRIMARY_SAFE_DNS_IPV4 = "1.1.1.3"
        const val SECONDARY_SAFE_DNS_IPV4 = "1.0.0.3"

        // Tier 2: Safe Adult-Filtering Fallback (No unfiltered 8.8.8.8 / 1.1.1.1)
        const val OPENDNS_FAMILY_PRIMARY = "208.67.222.123" // OpenDNS FamilyShield Primary
        const val OPENDNS_FAMILY_SECONDARY = "208.67.220.123" // OpenDNS FamilyShield Secondary
        const val ADGUARD_FAMILY_PRIMARY = "94.140.14.15" // AdGuard Family
        const val CLEANBROWSING_FAMILY_PRIMARY = "185.228.168.168" // CleanBrowsing Family
        internal const val CLEANBROWSING_FAMILY_HOST = "family-filter-dns.cleanbrowsing.org"

        fun parseIpv4ToInetAddress(rawText: String): InetAddress? =
            DnsWireValidator.parseIpv4ToInetAddress(rawText)
    }

    private data class PhysicalDnsTarget(
        val network: Network,
        val serverIp: String
    )

    private val queryPermits = Semaphore(32)
    private val networkGeneration = AtomicLong(0L)
    private val activeUdpSockets = ConcurrentHashMap.newKeySet<DatagramSocket>()
    private val monotonicClockMs: () -> Long = {
        try {
            SystemClock.elapsedRealtime()
        } catch (_: RuntimeException) {
            System.nanoTime() / 1_000_000L
        }
    }
    private val dotClient = DotUpstreamClient(vpnService, monotonicClockMs) { serverIp, query, timeoutMs ->
        sendUdpQuery(serverIp, query, timeoutMs)
    }

    @Volatile
    private var customDnsHost: String? = null

    fun setCustomDnsHost(host: String?) {
        val normalized = host?.trim()?.takeIf { it.isNotBlank() }
        if (customDnsHost != normalized) {
            customDnsHost = normalized
            networkGeneration.incrementAndGet()
            lruCache.clear()
            dotClient.resetState()
        }
    }

    fun clearResponseCache() {
        networkGeneration.incrementAndGet()
        lruCache.clear()
    }

    fun clearCache() {
        networkGeneration.incrementAndGet()
        lruCache.clear()
        dotClient.clearAll()
        closeAllActiveUdpSockets()
    }

    fun onNetworkChanged() {
        networkGeneration.incrementAndGet()
        lruCache.clear()
        dotClient.onNetworkChanged()
        closeAllActiveUdpSockets()
    }

    private fun closeAllActiveUdpSockets() {
        val snapshot = activeUdpSockets.toList()
        activeUdpSockets.clear()
        for (socket in snapshot) {
            try {
                socket.close()
            } catch (_: Exception) {
            }
        }
    }

    /**
     * Resolves a raw DNS query with generation-fenced Micro LRU cache and 3-tier failover.
     * Enforces a unified [maxQueryAgeMs] deadline across all tiers so stalled networks cannot starve permits.
     */
    suspend fun resolve(
        qName: String,
        qType: Int,
        transactionId: Short,
        rawDnsPayload: ByteArray,
        enqueuedAtMs: Long = 0L,
        maxQueryAgeMs: Long = 3_500L,
        maxResponsePayloadBytes: Int = DnsWireValidator.MAX_IPV4_UDP_DNS_PAYLOAD
    ): ByteArray? {
        val lookupGen = networkGeneration.get()
        // 1. Check LRU Cache (0ms hit) with generation & per-family MTU guard
        val cached = lruCache.get(qName, qType, expectedGeneration = lookupGen)
        if (cached != null && cached.size >= DnsPacketParser.DNS_HEADER_LEN) {
            cached[0] = (transactionId.toInt() ushr 8).toByte()
            cached[1] = (transactionId.toInt() and 0xFF).toByte()
            return DnsWireValidator.fitDnsResponseToMtu(cached, maxResponsePayloadBytes)
        }

        // 2. Query upstream via Bounded Protected Sockets under unified deadline
        return queryPermits.withPermit {
            currentCoroutineContext().ensureActive()
            val startGen = networkGeneration.get()
            val nowMs = monotonicClockMs()
            val deadlineMs = if (enqueuedAtMs > 0L) {
                enqueuedAtMs + maxQueryAgeMs
            } else {
                nowMs + maxQueryAgeMs
            }
            if (deadlineMs - nowMs <= 0L) {
                Timber.tag(TAG).d("Discarding stale queued DNS query (qType=$qType)")
                return@withPermit null
            }

            fun isAborted(): Boolean = networkGeneration.get() != startGen || (deadlineMs - monotonicClockMs()) <= 0L

            val clampedPayload = DnsWireValidator.clampEdnsUdpPayloadSize(rawDnsPayload)
            var softFallback: ByteArray? = null
            var reply = queryTier1(qName, clampedPayload, deadlineMs, startGen)
            if (reply != null && DnsWireValidator.isSoftFailureResponse(reply)) {
                softFallback = reply
                reply = null
            }
            if (reply == null && !isAborted()) {
                Timber.tag(TAG).d("Tier 1 DNS timeout/fail, attempting Tier 2 (OpenDNS/AdGuard Family)")
                val tier2Reply = queryTier2(clampedPayload, deadlineMs, startGen)
                if (tier2Reply != null && DnsWireValidator.isSoftFailureResponse(tier2Reply)) {
                    softFallback = softFallback ?: tier2Reply
                } else {
                    reply = tier2Reply
                }
            }

            // Leak Prevention: Allow Tier 3 (Physical Network DNS) ONLY for local intranet domains
            // or known OS captive-portal probe domains when a physical network reports NET_CAPABILITY_CAPTIVE_PORTAL.
            // Never leak general internet queries to unfiltered ISP/router DNS, and never cache Tier 3 replies.
            var usedTier3 = false
            if (reply == null && !isAborted()) {
                val isLocalIntranet = DnsWireValidator.isLocalIntranetDomain(qName)
                val isCaptiveProbe = !isLocalIntranet && DnsWireValidator.isKnownCaptivePortalProbeDomain(qName)
                if (isLocalIntranet || isCaptiveProbe) {
                    val targets = resolvePhysicalNetworkDns(captiveOnly = isCaptiveProbe)
                    if (targets.isNotEmpty()) {
                        Timber.tag(TAG).w("Tier 2 DNS failed for local/captive-probe domain, querying Tier 3")
                        reply = queryTier3(clampedPayload, targets, deadlineMs, startGen)
                        usedTier3 = reply != null
                    }
                }
            }

            if (reply == null) {
                reply = softFallback
            }

            val fittedReply = reply?.let { DnsWireValidator.fitDnsResponseToMtu(it, maxResponsePayloadBytes) }
            if (fittedReply != null &&
                !usedTier3 &&
                DnsWireValidator.isCacheableDnsResponse(fittedReply)
            ) {
                val ttl = DnsWireValidator.extractTtl(fittedReply)
                if (ttl > 0L) {
                    lruCache.putIfGenerationMatches(
                        qName = qName,
                        qType = qType,
                        payload = fittedReply,
                        ttlSeconds = ttl,
                        expectedGeneration = startGen,
                        currentGenerationProvider = { networkGeneration.get() }
                    )
                }
            }
            fittedReply
        }
    }

    private suspend fun executeUdp(
        ipAddress: String,
        rawPayload: ByteArray,
        timeoutMs: Int,
        bindNetwork: Network? = null
    ): ByteArray? {
        currentCoroutineContext().ensureActive()
        if (timeoutMs <= 0) return null
        return udpQueryExecutor?.invoke(ipAddress, rawPayload, timeoutMs)
            ?: sendUdpQuery(ipAddress, rawPayload, timeoutMs, bindNetwork)
    }

    private suspend fun executeDot(host: String, rawPayload: ByteArray, timeoutMs: Int): ByteArray? {
        currentCoroutineContext().ensureActive()
        if (timeoutMs <= 0) return null
        return dotQueryExecutor?.invoke(host, rawPayload, timeoutMs)
            ?: dotClient.query(host, rawPayload, timeoutMs)
    }

    private fun remainingTimeout(deadlineMs: Long, startGen: Long, capMs: Int): Int {
        if (networkGeneration.get() != startGen) return 0
        val remaining = (deadlineMs - monotonicClockMs()).toInt()
        return if (remaining <= 0) 0 else remaining.coerceAtMost(capMs)
    }

    private suspend fun queryTier1(
        qName: String,
        rawPayload: ByteArray,
        deadlineMs: Long,
        startGen: Long
    ): ByteArray? {
        val cleanHost = DnsWireValidator.normalizeCustomHost(customDnsHost)
        val normalizedQName = qName.trim().lowercase().trimEnd('.')
        val cloudflareHost = DevicePolicyBundle.CLOUDFLARE_FAMILY_DNS_HOST.lowercase()
        val isDefaultCloudflarePreset = cleanHost == null || cleanHost == cloudflareHost
        val isSelfBootstrapQuery = cleanHost != null && normalizedQName == cleanHost
        var softFallback: ByteArray? = null

        fun acceptOrSaveSoft(candidate: ByteArray?): ByteArray? {
            if (candidate == null) return null
            if (DnsWireValidator.isSoftFailureResponse(candidate)) {
                softFallback = softFallback ?: candidate
                return null
            }
            return candidate
        }

        // 1. Custom numeric IPv4 (public only) or DoT hostname (ControlD, NextDNS, custom DoT)
        if (cleanHost != null && !isDefaultCloudflarePreset && !isSelfBootstrapQuery) {
            val parsedIp = DnsWireValidator.parseIpv4ToInetAddress(cleanHost)
            if (parsedIp != null) {
                val canonicalIp = parsedIp.hostAddress ?: ""
                if (canonicalIp.isNotEmpty() && !DnsWireValidator.isNonPublicOrReservedIp(parsedIp)) {
                    val t = remainingTimeout(deadlineMs, startGen, TIER1_UDP_TIMEOUT_MS)
                    acceptOrSaveSoft(executeUdp(canonicalIp, rawPayload, timeoutMs = t))?.let { return it }
                }
            } else if (!DnsWireValidator.isExcludedDnsIp(cleanHost)) {
                val t = remainingTimeout(deadlineMs, startGen, DOT_TIMEOUT_MS)
                acceptOrSaveSoft(executeDot(cleanHost, rawPayload, timeoutMs = t))?.let { return it }
                Timber.tag(TAG).w("DoT query failed or circuit open; falling back to Safe Family UDP")
            }
        }

        // 2. Exact CleanBrowsing Family preset fallback
        if (cleanHost == CLEANBROWSING_FAMILY_PRIMARY || cleanHost == CLEANBROWSING_FAMILY_HOST) {
            val t = remainingTimeout(deadlineMs, startGen, TIER1_UDP_TIMEOUT_MS)
            acceptOrSaveSoft(executeUdp(CLEANBROWSING_FAMILY_PRIMARY, rawPayload, timeoutMs = t))?.let { return it }
        }

        // 3. Exact AdGuard Family preset fallback
        if (cleanHost == ADGUARD_FAMILY_PRIMARY || cleanHost == DevicePolicyBundle.ADGUARD_FAMILY_DNS_HOST.lowercase()) {
            val t = remainingTimeout(deadlineMs, startGen, TIER1_UDP_TIMEOUT_MS)
            acceptOrSaveSoft(executeUdp(ADGUARD_FAMILY_PRIMARY, rawPayload, timeoutMs = t))?.let { return it }
        }

        // 4. Default: Cloudflare Family (encrypted DoT first when default preset, then UDP 1.1.1.3 & 1.0.0.3)
        if (isDefaultCloudflarePreset && normalizedQName != cloudflareHost) {
            val t = remainingTimeout(deadlineMs, startGen, DOT_TIMEOUT_MS)
            acceptOrSaveSoft(executeDot(DevicePolicyBundle.CLOUDFLARE_FAMILY_DNS_HOST, rawPayload, timeoutMs = t))
                ?.let { return it }
        }

        val tPrimary = remainingTimeout(deadlineMs, startGen, TIER1_UDP_TIMEOUT_MS)
        acceptOrSaveSoft(executeUdp(PRIMARY_SAFE_DNS_IPV4, rawPayload, timeoutMs = tPrimary))?.let { return it }
        val tSecondary = remainingTimeout(deadlineMs, startGen, TIER1_UDP_TIMEOUT_MS)
        acceptOrSaveSoft(executeUdp(SECONDARY_SAFE_DNS_IPV4, rawPayload, timeoutMs = tSecondary))?.let { return it }
        return softFallback
    }

    private suspend fun queryTier2(rawPayload: ByteArray, deadlineMs: Long, startGen: Long): ByteArray? {
        var softFallback: ByteArray? = null
        val servers = arrayOf(OPENDNS_FAMILY_PRIMARY, OPENDNS_FAMILY_SECONDARY, ADGUARD_FAMILY_PRIMARY)
        for (server in servers) {
            val t = remainingTimeout(deadlineMs, startGen, TIER2_UDP_TIMEOUT_MS)
            if (t <= 0) break
            val reply = executeUdp(server, rawPayload, timeoutMs = t) ?: continue
            if (!DnsWireValidator.isSoftFailureResponse(reply)) return reply
            softFallback = softFallback ?: reply
        }
        return softFallback
    }

    private suspend fun queryTier3(
        rawPayload: ByteArray,
        targets: List<PhysicalDnsTarget>,
        deadlineMs: Long,
        startGen: Long
    ): ByteArray? {
        for (target in targets) {
            val t = remainingTimeout(deadlineMs, startGen, TIER3_UDP_TIMEOUT_MS)
            if (t <= 0) break
            val reply = executeUdp(target.serverIp, rawPayload, timeoutMs = t, bindNetwork = target.network)
            if (reply != null) return reply
        }
        return null
    }

    private suspend fun sendUdpQuery(
        ipAddress: String,
        rawPayload: ByteArray,
        timeoutMs: Int,
        bindNetwork: Network? = null
    ): ByteArray? {
        val target = DnsWireValidator.parseIpv4ToInetAddress(ipAddress) ?: return null
        var socket: DatagramSocket? = null
        var cancelHandle: kotlinx.coroutines.DisposableHandle? = null
        return try {
            val activeSocket = DatagramSocket().apply {
                soTimeout = timeoutMs.coerceAtLeast(1)
            }
            socket = activeSocket
            activeUdpSockets.add(activeSocket)
            cancelHandle = currentCoroutineContext()[Job]?.invokeOnCompletion { cause ->
                if (cause != null) {
                    try {
                        activeSocket.close()
                    } catch (_: Exception) {
                    }
                }
            }
            currentCoroutineContext().ensureActive()

            if (!vpnService.protect(activeSocket)) {
                Timber.tag(TAG).w("VpnService.protect() failed for UDP socket")
                return null
            }
            if (bindNetwork != null) {
                bindNetwork.bindSocket(activeSocket)
            }
            activeSocket.connect(target, DnsPacketParser.DNS_PORT)

            val sendPacket = DatagramPacket(rawPayload, rawPayload.size, target, DnsPacketParser.DNS_PORT)
            activeSocket.send(sendPacket)

            val receiveBuffer = ByteArray(4096)
            val recvPacket = DatagramPacket(receiveBuffer, receiveBuffer.size)
            activeSocket.receive(recvPacket)

            if (recvPacket.address != target || recvPacket.port != DnsPacketParser.DNS_PORT) {
                Timber.tag(TAG).w("Discarded stray UDP DNS packet from unexpected source")
                return null
            }

            val len = recvPacket.length
            if (!DnsWireValidator.isValidDnsResponse(receiveBuffer, len, rawPayload)) {
                Timber.tag(TAG).w("Discarded malformed or mismatched UDP DNS reply (len=$len)")
                return null
            }

            val dnsReply = ByteArray(len)
            System.arraycopy(receiveBuffer, 0, dnsReply, 0, len)
            dnsReply
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.tag(TAG).d(e, "UDP DNS query failed")
            null
        } finally {
            cancelHandle?.dispose()
            socket?.let { s ->
                activeUdpSockets.remove(s)
                try {
                    s.close()
                } catch (_: IOException) {
                }
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun resolvePhysicalNetworkDns(captiveOnly: Boolean): List<PhysicalDnsTarget> {
        val result = mutableListOf<PhysicalDnsTarget>()
        val seenKeys = HashSet<String>()
        try {
            val cm = vpnService.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return result
            val networks = cm.allNetworks
            for (net in networks) {
                val caps = cm.getNetworkCapabilities(net) ?: continue
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue
                if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) continue
                if (captiveOnly && !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL)) continue

                val linkProps = cm.getLinkProperties(net) ?: continue
                for (inetAddress in linkProps.dnsServers) {
                    val hostAddress = inetAddress.hostAddress ?: continue
                    if (hostAddress.isBlank() || hostAddress.contains(':')) continue
                    val parsed = DnsWireValidator.parseIpv4ToInetAddress(hostAddress) ?: continue
                    if (DnsWireValidator.isDisallowedPhysicalDnsIp(parsed)) continue
                    val dedupKey = "$net|$hostAddress"
                    if (seenKeys.add(dedupKey)) {
                        result.add(PhysicalDnsTarget(net, hostAddress))
                    }
                }
            }
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Could not resolve physical network DNS")
        }
        return result
    }
}
