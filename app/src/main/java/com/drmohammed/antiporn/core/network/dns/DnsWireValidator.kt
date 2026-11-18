package com.drmohammed.antiporn.core.network.dns

import timber.log.Timber
import java.net.InetAddress

/**
 * Stateless DNS wire-format validation, TTL extraction, host normalization,
 * and recursion/leak exclusion helpers shared by upstream forwarders.
 */
object DnsWireValidator {

    private const val TAG = "AntiPorn_DnsForwarder"

    val EXCLUDED_DNS_IPS: Set<String> = setOf(
        "10.255.255.1",
        "10.255.255.2",
        "fd00::1",
        "fd00::2",
        "127.0.0.1",
        "::1",
        "0.0.0.0"
    )

    internal fun isExcludedDnsIp(ip: String): Boolean =
        EXCLUDED_DNS_IPS.contains(DotUpstreamClient.sanitizeHost(ip))

    internal fun normalizeCustomHost(rawHost: String?): String? =
        rawHost?.let { DotUpstreamClient.sanitizeHost(it) }?.takeIf { it.isNotBlank() }

    internal fun isSoftFailureResponse(reply: ByteArray): Boolean {
        if (reply.size < DnsPacketParser.DNS_HEADER_LEN) return false
        val rcode = reply[3].toInt() and 0x0F
        return rcode == 2 || rcode == 5 // SERVFAIL or REFUSED
    }

    internal fun isCacheableDnsResponse(reply: ByteArray): Boolean {
        if (reply.size < DnsPacketParser.DNS_HEADER_LEN) return false
        val isTruncated = (reply[2].toInt() and 0x02) != 0
        if (isTruncated) return false
        val rcode = reply[3].toInt() and 0x0F
        return rcode == 0 || rcode == 3 // NOERROR or NXDOMAIN only
    }

    internal fun isLocalIntranetDomain(qName: String): Boolean {
        val clean = qName.trim().lowercase().trimEnd('.')
        if (clean.isBlank()) return false
        if (!clean.contains('.')) return true // router, gateway, localhost
        return clean.endsWith(".local") || clean.endsWith(".lan") || clean.endsWith(".home.arpa")
    }

    internal fun isValidDnsResponse(reply: ByteArray, length: Int, requestPayload: ByteArray): Boolean {
        if (length < DnsPacketParser.DNS_HEADER_LEN || reply.size < length) return false
        if (requestPayload.size < 2) return false
        // QR bit (bit 7 of byte 2) must be 1 (Response)
        if ((reply[2].toInt() and 0x80) == 0) return false
        // Transaction ID must match client query (prevents stray/spoofed UDP cache poisoning)
        return reply[0] == requestPayload[0] && reply[1] == requestPayload[1]
    }

    /**
     * Parses a numeric IPv4 address into an InetAddress with 0ms overhead
     * and ZERO network/DNS resolution, completely eliminating recursive getByName loops.
     */
    fun parseIpv4ToInetAddress(rawText: String): InetAddress? {
        val clean = rawText.trim().substringBefore(':').trim()
        val parts = clean.split('.')
        if (parts.size != 4) return null
        val bytes = ByteArray(4)
        for (i in 0..3) {
            val intVal = parts[i].toIntOrNull() ?: return null
            if (intVal !in 0..255) return null
            bytes[i] = intVal.toByte()
        }
        return try {
            InetAddress.getByAddress(bytes)
        } catch (_: Exception) {
            null
        }
    }

    internal fun extractTtl(dnsPayload: ByteArray): Long {
        if (dnsPayload.size < DnsPacketParser.DNS_HEADER_LEN) return 0L
        val anCount = ((dnsPayload[6].toInt() and 0xFF) shl 8) or (dnsPayload[7].toInt() and 0xFF)
        if (anCount <= 0) return 30L

        try {
            val qdCount = ((dnsPayload[4].toInt() and 0xFF) shl 8) or (dnsPayload[5].toInt() and 0xFF)
            var offset = 12
            // Skip Question sections
            for (q in 0 until qdCount) {
                if (offset >= dnsPayload.size) return 60L
                offset = skipDomainName(dnsPayload, offset) ?: return 60L
                offset += 4 // QTYPE (2) + QCLASS (2)
                if (offset > dnsPayload.size) return 60L
            }

            // First Answer section: NAME + TYPE (2) + CLASS (2) + TTL (4)
            if (offset < dnsPayload.size) {
                offset = skipDomainName(dnsPayload, offset) ?: return 60L
                offset += 4 // Skip TYPE (2) + CLASS (2)
                if (offset + 4 <= dnsPayload.size) {
                    val ttl = ((dnsPayload[offset].toLong() and 0xFF) shl 24) or
                        ((dnsPayload[offset + 1].toLong() and 0xFF) shl 16) or
                        ((dnsPayload[offset + 2].toLong() and 0xFF) shl 8) or
                        (dnsPayload[offset + 3].toLong() and 0xFF)
                    // RFC 1035 / RFC 2181 §8: TTL=0 or MSB-set (> Int.MAX_VALUE) must NOT be cached
                    return if (ttl <= 0L || ttl > Int.MAX_VALUE.toLong()) 0L else ttl.coerceIn(10L, 120L)
                }
            }
        } catch (e: Exception) {
            Timber.tag(TAG).d(e, "Failed extracting DNS TTL")
        }
        return 60L
    }

    private fun skipDomainName(dnsPayload: ByteArray, startOffset: Int): Int? {
        var offset = startOffset
        var steps = 0
        while (offset < dnsPayload.size && steps < 128) {
            steps++
            val len = dnsPayload[offset].toInt() and 0xFF
            if (len == 0) return offset + 1
            if ((len and 0xC0) == 0xC0) return if (offset + 2 <= dnsPayload.size) offset + 2 else null
            if ((len and 0xC0) != 0) return null // Reject reserved/invalid RFC 1035 label bits
            offset += 1 + len
        }
        return null
    }
}
