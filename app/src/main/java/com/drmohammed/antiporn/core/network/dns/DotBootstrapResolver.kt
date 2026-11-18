package com.drmohammed.antiporn.core.network.dns

import com.drmohammed.antiporn.core.model.DevicePolicyBundle
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.net.InetAddress
import java.nio.ByteBuffer
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

internal sealed class BootstrapOutcome {
    data class Resolved(val address: InetAddress) : BootstrapOutcome()
    data class LeaderFailed(val generation: Long) : BootstrapOutcome()
    object CoalescedOrCancelled : BootstrapOutcome()
}

/**
 * Single-flight DoT bootstrap IP resolver with preset fast-path, generation-fenced TTL cache,
 * encrypted Direct DoT first (1.1.1.3:853), and protected UDP/53 fallback (1.1.1.3 -> 1.0.0.3).
 * Uses suspending [CompletableDeferred] coalescing so followers never pin IO threads.
 */
internal class DotBootstrapResolver(
    private val clockMs: () -> Long,
    private val currentGeneration: () -> Long,
    private val udpBootstrapQuery: suspend (serverIp: String, rawQuery: ByteArray, timeoutMs: Int) -> ByteArray?,
    private val directDotBootstrapQuery: suspend (targetIp: InetAddress, sniHost: String, rawQuery: ByteArray, timeoutMs: Int) -> ByteArray?
) {
    private data class CachedBootstrapIp(
        val address: InetAddress,
        val expiresAtMs: Long,
        val generation: Long
    )

    private val bootstrapCache = ConcurrentHashMap<String, CachedBootstrapIp>()
    private val inFlightBootstrap = ConcurrentHashMap<String, CompletableDeferred<InetAddress?>>()
    private val txRandom = SecureRandom()

    fun clearAndCancelAll() {
        cancelInFlight()
        bootstrapCache.clear()
    }

    fun cancelInFlight() {
        val entries = inFlightBootstrap.entries.toList()
        for ((key, deferred) in entries) {
            if (inFlightBootstrap.remove(key, deferred)) {
                deferred.complete(null)
            }
        }
    }

    suspend fun resolve(cleanHost: String, deadlineMs: Long, startGen: Long): BootstrapOutcome {
        // 1. Exact known preset mapping (zero latency)
        when (cleanHost) {
            DevicePolicyBundle.CLOUDFLARE_FAMILY_DNS_HOST.lowercase() ->
                DnsUpstreamForwarder.parseIpv4ToInetAddress(DnsUpstreamForwarder.PRIMARY_SAFE_DNS_IPV4)
                    ?.let { return BootstrapOutcome.Resolved(it) }
            DevicePolicyBundle.ADGUARD_FAMILY_DNS_HOST.lowercase() ->
                DnsUpstreamForwarder.parseIpv4ToInetAddress(DnsUpstreamForwarder.ADGUARD_FAMILY_PRIMARY)
                    ?.let { return BootstrapOutcome.Resolved(it) }
            DnsUpstreamForwarder.CLEANBROWSING_FAMILY_HOST ->
                DnsUpstreamForwarder.parseIpv4ToInetAddress(DnsUpstreamForwarder.CLEANBROWSING_FAMILY_PRIMARY)
                    ?.let { return BootstrapOutcome.Resolved(it) }
        }

        // 2. Direct IPv4 literal (strictly reject loopback, RFC 1918, link-local, CGNAT, multicast, TUN)
        DnsUpstreamForwarder.parseIpv4ToInetAddress(cleanHost)?.let { literalIp ->
            return if (!DnsWireValidator.isNonPublicOrReservedIp(literalIp)) {
                BootstrapOutcome.Resolved(literalIp)
            } else {
                BootstrapOutcome.LeaderFailed(startGen)
            }
        }

        // 3. Generation-fenced cached bootstrap IP
        val now = clockMs()
        bootstrapCache[cleanHost]?.let { cached ->
            if (cached.generation == startGen && now < cached.expiresAtMs) {
                return BootstrapOutcome.Resolved(cached.address)
            }
        }

        if (currentGeneration() != startGen) {
            return BootstrapOutcome.CoalescedOrCancelled
        }

        // 4. Single-flight suspending coalescing keyed by host + generation
        val flightKey = "$cleanHost#$startGen"
        val myDeferred = CompletableDeferred<InetAddress?>()
        val existing = inFlightBootstrap.putIfAbsent(flightKey, myDeferred)
        if (existing != null) {
            val waitMs = (deadlineMs - clockMs()).coerceIn(1L, MAX_BOOTSTRAP_WAIT_MS)
            val waited = withTimeoutOrNull(waitMs) { existing.await() }
            return if (waited != null && currentGeneration() == startGen) {
                BootstrapOutcome.Resolved(waited)
            } else {
                BootstrapOutcome.CoalescedOrCancelled
            }
        }

        // Guard against race where generation bumped right before putIfAbsent
        if (currentGeneration() != startGen) {
            myDeferred.complete(null)
            inFlightBootstrap.remove(flightKey, myDeferred)
            return BootstrapOutcome.CoalescedOrCancelled
        }

        var resolved: InetAddress? = null
        try {
            val txId = (txRandom.nextInt(0x10000) and 0xFFFF).toShort()
            val query = buildMinimalAQuery(cleanHost, txId)
                ?: return BootstrapOutcome.LeaderFailed(startGen)

            fun extractValidIp(reply: ByteArray?): InetAddress? {
                if (reply == null) return null
                if (!DnsWireValidator.isValidDnsResponse(reply, reply.size, query)) return null
                return extractFirstPublicIpv4FromDnsResponse(reply)
            }

            // 1. Prefer encrypted Direct DoT (1.1.1.3:853) first to prevent cleartext hostname leaks & UDP spoofing.
            //    Does not call resolve(), so recursion is impossible.
            var candidate: InetAddress? = null
            val cloudflareHost = DevicePolicyBundle.CLOUDFLARE_FAMILY_DNS_HOST.lowercase()
            if (currentGeneration() == startGen && cleanHost != cloudflareHost) {
                val remainingDotMs = (deadlineMs - clockMs()).toInt().coerceAtMost(DOT_BOOTSTRAP_TIMEOUT_MS)
                if (remainingDotMs > 0) {
                    val cloudflareIp = DnsUpstreamForwarder.parseIpv4ToInetAddress(
                        DnsUpstreamForwarder.PRIMARY_SAFE_DNS_IPV4
                    )
                    if (cloudflareIp != null) {
                        candidate = extractValidIp(
                            directDotBootstrapQuery(
                                cloudflareIp,
                                DevicePolicyBundle.CLOUDFLARE_FAMILY_DNS_HOST,
                                query,
                                remainingDotMs
                            )
                        )
                    }
                }
            }

            // 2. Protected UDP/53 fallback to Safe Family DNS only if Direct DoT (port 853) is blocked/unavailable
            if (candidate == null && currentGeneration() == startGen) {
                val remainingUdpMs = (deadlineMs - clockMs()).toInt().coerceAtMost(UDP_BOOTSTRAP_TIMEOUT_MS)
                if (remainingUdpMs > 0) {
                    candidate = extractValidIp(
                        udpBootstrapQuery(DnsUpstreamForwarder.PRIMARY_SAFE_DNS_IPV4, query, remainingUdpMs)
                    )
                }
            }

            if (candidate == null && currentGeneration() == startGen) {
                val remainingUdpMs = (deadlineMs - clockMs()).toInt().coerceAtMost(UDP_BOOTSTRAP_TIMEOUT_MS)
                if (remainingUdpMs > 0) {
                    candidate = extractValidIp(
                        udpBootstrapQuery(DnsUpstreamForwarder.SECONDARY_SAFE_DNS_IPV4, query, remainingUdpMs)
                    )
                }
            }

            if (candidate != null) {
                resolved = candidate
                if (currentGeneration() == startGen) {
                    bootstrapCache[cleanHost] = CachedBootstrapIp(
                        address = candidate,
                        expiresAtMs = clockMs() + BOOTSTRAP_CACHE_TTL_MS,
                        generation = startGen
                    )
                    return BootstrapOutcome.Resolved(candidate)
                }
                return BootstrapOutcome.CoalescedOrCancelled
            }
            return if (currentGeneration() == startGen) {
                BootstrapOutcome.LeaderFailed(startGen)
            } else {
                BootstrapOutcome.CoalescedOrCancelled
            }
        } finally {
            myDeferred.complete(resolved)
            inFlightBootstrap.remove(flightKey, myDeferred)
        }
    }

    companion object {
        private const val BOOTSTRAP_CACHE_TTL_MS = 10L * 60L * 1000L
        private const val UDP_BOOTSTRAP_TIMEOUT_MS = 600
        private const val DOT_BOOTSTRAP_TIMEOUT_MS = 1_200
        internal const val MAX_BOOTSTRAP_WAIT_MS = 2_600L

        internal fun buildMinimalAQuery(hostname: String, txId: Short): ByteArray? {
            val labels = hostname.trim().lowercase().trimEnd('.').split('.')
            if (labels.isEmpty() || labels.any { label ->
                    label.isEmpty() || label.length > 63 || label.any { it.code !in 0x21..0x7E }
                }
            ) return null
            var qNameLen = 1
            for (label in labels) qNameLen += 1 + label.length
            if (qNameLen > 255) return null
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

        internal fun extractFirstIpv4FromDnsResponse(dnsPayload: ByteArray): InetAddress? =
            extractIpv4FromDnsResponse(dnsPayload, requirePublic = false)

        internal fun extractFirstPublicIpv4FromDnsResponse(dnsPayload: ByteArray): InetAddress? =
            extractIpv4FromDnsResponse(dnsPayload, requirePublic = true)

        private fun extractIpv4FromDnsResponse(dnsPayload: ByteArray, requirePublic: Boolean): InetAddress? {
            if (dnsPayload.size < DnsPacketParser.DNS_HEADER_LEN) return null
            val flagsHi = dnsPayload[2].toInt() and 0xFF
            if ((flagsHi and 0x80) == 0 || (flagsHi and 0x78) != 0) return null // QR=1, OPCODE=0
            if ((dnsPayload[3].toInt() and 0x0F) != 0) return null // RCODE=NOERROR

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
                    val addr = try {
                        InetAddress.getByAddress(ipBytes)
                    } catch (_: Exception) {
                        null
                    }
                    if (addr != null && (!requirePublic || !DnsWireValidator.isNonPublicOrReservedIp(addr))) {
                        return addr
                    }
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
}
