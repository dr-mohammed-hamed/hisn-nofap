package com.drmohammed.antiporn.core.network.dns

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.SystemClock
import com.drmohammed.antiporn.core.model.DevicePolicyBundle
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import timber.log.Timber
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/**
 * Enterprise-grade DNS Upstream Forwarder implementing 3-tier failover,
 * micro LRU caching, bounded concurrency, zero-lookup socket creation,
 * strict leak prevention, and YouTube comments preservation.
 */
class DnsUpstreamForwarder(
    private val vpnService: VpnService,
    @Suppress("UNUSED_PARAMETER") private val policyProvider: () -> DevicePolicyBundle = { DevicePolicyBundle.defaultPolicy() },
    private val lruCache: DnsLruCache = DnsLruCache(512),
    private val udpQueryExecutor: ((ipAddress: String, rawPayload: ByteArray, timeoutMs: Int) -> ByteArray?)? = null,
    private val dotQueryExecutor: ((host: String, rawPayload: ByteArray, timeoutMs: Int) -> ByteArray?)? = null
) {

    companion object {
        private const val TAG = "AntiPorn_DnsForwarder"
        private const val DOT_TIMEOUT_MS = 1_500

        // Tier 1: Cloudflare Family (Strict adult filtering with YouTube comments preserved)
        const val PRIMARY_SAFE_DNS_IPV4 = "1.1.1.3"
        const val SECONDARY_SAFE_DNS_IPV4 = "1.0.0.3"

        // Tier 2: Safe Adult-Filtering Fallback (No unfiltered 8.8.8.8 / 1.1.1.1)
        const val OPENDNS_FAMILY_PRIMARY = "208.67.222.123" // OpenDNS FamilyShield Primary
        const val OPENDNS_FAMILY_SECONDARY = "208.67.220.123" // OpenDNS FamilyShield Secondary
        const val ADGUARD_FAMILY_PRIMARY = "94.140.14.15" // AdGuard Family
        const val CLEANBROWSING_FAMILY_PRIMARY = "185.228.168.168" // CleanBrowsing Family
        private const val CLEANBROWSING_FAMILY_HOST = "family-filter-dns.cleanbrowsing.org"

        fun parseIpv4ToInetAddress(rawText: String): InetAddress? =
            DnsWireValidator.parseIpv4ToInetAddress(rawText)
    }

    private val queryPermits = Semaphore(32)
    private val monotonicClockMs: () -> Long = {
        try {
            SystemClock.elapsedRealtime()
        } catch (_: Throwable) {
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
            dotClient.resetState()
        }
    }

    fun clearCache() {
        lruCache.clear()
        dotClient.clearAll()
    }

    /**
     * Resolves a raw DNS query with Micro LRU cache and 3-tier failover.
     * Returns raw DNS response payload, or null if all tiers fail.
     */
    suspend fun resolve(
        qName: String,
        qType: Int,
        transactionId: Short,
        rawDnsPayload: ByteArray,
        enqueuedAtMs: Long = 0L,
        maxQueryAgeMs: Long = 3_500L
    ): ByteArray? {
        // 1. Check LRU Cache (0ms hit)
        val cached = lruCache.get(qName, qType)
        if (cached != null && cached.size >= DnsPacketParser.DNS_HEADER_LEN) {
            cached[0] = (transactionId.toInt() ushr 8).toByte()
            cached[1] = (transactionId.toInt() and 0xFF).toByte()
            return cached
        }

        // 2. Query upstream via Bounded Protected Sockets
        return queryPermits.withPermit {
            currentCoroutineContext().ensureActive()
            if (enqueuedAtMs > 0L && (monotonicClockMs() - enqueuedAtMs) > maxQueryAgeMs) {
                Timber.tag(TAG).d("Discarding stale queued DNS query for $qName")
                return@withPermit null
            }
            var softFallback: ByteArray? = null
            var reply = queryTier1(qName, rawDnsPayload)
            if (reply != null && DnsWireValidator.isSoftFailureResponse(reply)) {
                softFallback = reply
                reply = null
            }
            if (reply == null) {
                Timber.tag(TAG).d("Tier 1 DNS timeout/fail, attempting Tier 2 (OpenDNS/AdGuard Family)")
                val tier2Reply = queryTier2(rawDnsPayload)
                if (tier2Reply != null && DnsWireValidator.isSoftFailureResponse(tier2Reply)) {
                    softFallback = softFallback ?: tier2Reply
                } else {
                    reply = tier2Reply
                }
            }
            // Leak Prevention: Only fall back to Physical Network DNS (Tier 3) for local intranet domains
            // or when a physical (non-VPN) network explicitly reports NET_CAPABILITY_CAPTIVE_PORTAL.
            // Never cache Tier 3 responses or leak general internet queries to unfiltered ISP DNS.
            var usedTier3 = false
            if (reply == null && isCaptiveOrLocalDomain(qName)) {
                Timber.tag(TAG).w("Tier 2 DNS failed for captive/local domain, querying Tier 3 (Physical Network DNS)")
                reply = queryTier3(rawDnsPayload)
                usedTier3 = reply != null
            }

            if (reply == null) {
                reply = softFallback
            }

            if (reply != null && !usedTier3 && DnsWireValidator.isCacheableDnsResponse(reply)) {
                val ttl = DnsWireValidator.extractTtl(reply)
                if (ttl > 0L) {
                    lruCache.put(qName, qType, reply, ttl)
                }
            }
            reply
        }
    }

    private fun isCaptiveOrLocalDomain(qName: String): Boolean {
        if (DnsWireValidator.isLocalIntranetDomain(qName)) return true

        // Check physical (non-VPN) networks ONLY for an explicit Captive Portal flag
        try {
            val cm = vpnService.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
            for (net in cm.allNetworks) {
                val caps = cm.getNetworkCapabilities(net) ?: continue
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue
                if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL)) {
                    return true
                }
            }
        } catch (e: Exception) {
            Timber.tag(TAG).d(e, "Failed checking physical network captive portal capabilities")
        }
        return false
    }

    private suspend fun executeUdp(ipAddress: String, rawPayload: ByteArray, timeoutMs: Int): ByteArray? {
        currentCoroutineContext().ensureActive()
        return udpQueryExecutor?.invoke(ipAddress, rawPayload, timeoutMs)
            ?: sendUdpQuery(ipAddress, rawPayload, timeoutMs)
    }

    private suspend fun executeDot(host: String, rawPayload: ByteArray, timeoutMs: Int): ByteArray? {
        currentCoroutineContext().ensureActive()
        return dotQueryExecutor?.invoke(host, rawPayload, timeoutMs)
            ?: dotClient.query(host, rawPayload, timeoutMs)
    }

    private suspend fun queryTier1(qName: String, rawPayload: ByteArray): ByteArray? {
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

        // 1. Custom numeric IPv4 or DoT hostname (ControlD, NextDNS, custom DoT)
        if (cleanHost != null && !isDefaultCloudflarePreset && !isSelfBootstrapQuery) {
            val parsedIp = DnsWireValidator.parseIpv4ToInetAddress(cleanHost)
            if (parsedIp != null) {
                val canonicalIp = parsedIp.hostAddress ?: ""
                if (canonicalIp.isNotEmpty() && !DnsWireValidator.isExcludedDnsIp(canonicalIp) && !DnsWireValidator.isExcludedDnsIp(cleanHost)) {
                    acceptOrSaveSoft(executeUdp(canonicalIp, rawPayload, timeoutMs = 900))?.let { return it }
                }
            } else if (!DnsWireValidator.isExcludedDnsIp(cleanHost)) {
                acceptOrSaveSoft(executeDot(cleanHost, rawPayload, timeoutMs = DOT_TIMEOUT_MS))?.let { return it }
                Timber.tag(TAG).w("DoT query failed or circuit open; falling back to Safe Family UDP")
            }
        }

        // 2. Exact CleanBrowsing Family preset fallback
        if (cleanHost == CLEANBROWSING_FAMILY_PRIMARY || cleanHost == CLEANBROWSING_FAMILY_HOST) {
            acceptOrSaveSoft(executeUdp(CLEANBROWSING_FAMILY_PRIMARY, rawPayload, timeoutMs = 900))?.let { return it }
        }

        // 3. Exact AdGuard Family preset fallback
        if (cleanHost == ADGUARD_FAMILY_PRIMARY || cleanHost == DevicePolicyBundle.ADGUARD_FAMILY_DNS_HOST.lowercase()) {
            acceptOrSaveSoft(executeUdp(ADGUARD_FAMILY_PRIMARY, rawPayload, timeoutMs = 900))?.let { return it }
        }

        // 4. Default: Cloudflare Family (encrypted DoT first when default preset, then UDP 1.1.1.3 & 1.0.0.3)
        if (isDefaultCloudflarePreset && normalizedQName != cloudflareHost) {
            acceptOrSaveSoft(executeDot(DevicePolicyBundle.CLOUDFLARE_FAMILY_DNS_HOST, rawPayload, timeoutMs = DOT_TIMEOUT_MS))
                ?.let { return it }
        }

        acceptOrSaveSoft(executeUdp(PRIMARY_SAFE_DNS_IPV4, rawPayload, timeoutMs = 900))?.let { return it }
        acceptOrSaveSoft(executeUdp(SECONDARY_SAFE_DNS_IPV4, rawPayload, timeoutMs = 900))?.let { return it }
        return softFallback
    }

    private suspend fun queryTier2(rawPayload: ByteArray): ByteArray? {
        var softFallback: ByteArray? = null
        val servers = arrayOf(OPENDNS_FAMILY_PRIMARY, OPENDNS_FAMILY_SECONDARY, ADGUARD_FAMILY_PRIMARY)
        for (server in servers) {
            val reply = executeUdp(server, rawPayload, timeoutMs = 1200) ?: continue
            if (!DnsWireValidator.isSoftFailureResponse(reply)) return reply
            softFallback = softFallback ?: reply
        }
        return softFallback
    }

    private suspend fun queryTier3(rawPayload: ByteArray): ByteArray? {
        val physicalDnsServers = resolvePhysicalNetworkDns()
        for (dnsServer in physicalDnsServers) {
            val reply = executeUdp(dnsServer, rawPayload, timeoutMs = 1500)
            if (reply != null) return reply
        }
        return null
    }

    private fun sendUdpQuery(ipAddress: String, rawPayload: ByteArray, timeoutMs: Int): ByteArray? {
        val target = DnsWireValidator.parseIpv4ToInetAddress(ipAddress) ?: return null
        var socket: DatagramSocket? = null
        return try {
            socket = DatagramSocket().apply {
                soTimeout = timeoutMs
            }
            if (!vpnService.protect(socket)) {
                Timber.tag(TAG).w("VpnService.protect() failed for UDP socket to $ipAddress")
                return null
            }
            socket.connect(target, DnsPacketParser.DNS_PORT)

            val sendPacket = DatagramPacket(rawPayload, rawPayload.size, target, DnsPacketParser.DNS_PORT)
            socket.send(sendPacket)

            val receiveBuffer = ByteArray(4096)
            val recvPacket = DatagramPacket(receiveBuffer, receiveBuffer.size)
            socket.receive(recvPacket)

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
        } catch (e: Exception) {
            Timber.tag(TAG).d(e, "UDP DNS query to $ipAddress failed")
            null
        } finally {
            try {
                socket?.close()
            } catch (_: Exception) {
            }
        }
    }

    private fun resolvePhysicalNetworkDns(): List<String> {
        val result = mutableListOf<String>()
        try {
            val cm = vpnService.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return result
            val networks = cm.allNetworks
            for (net in networks) {
                val caps = cm.getNetworkCapabilities(net) ?: continue
                if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                    caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                ) {
                    val linkProps = cm.getLinkProperties(net) ?: continue
                    for (inetAddress in linkProps.dnsServers) {
                        val hostAddress = inetAddress.hostAddress
                        if (!hostAddress.isNullOrBlank() &&
                            !DnsWireValidator.EXCLUDED_DNS_IPS.contains(hostAddress) &&
                            !hostAddress.contains(':') &&
                            DnsWireValidator.parseIpv4ToInetAddress(hostAddress) != null &&
                            !result.contains(hostAddress)
                        ) {
                            result.add(hostAddress)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Could not resolve physical network DNS")
        }
        return result
    }
}
