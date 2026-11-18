package com.drmohammed.antiporn.core.network.dns

import timber.log.Timber
import java.net.InetAddress

/**
 * Stateless DNS wire-format validation, TTL extraction, host normalization,
 * and recursion/leak exclusion helpers shared by upstream forwarders.
 */
object DnsWireValidator {

    private const val TAG = "AntiPorn_DnsForwarder"

    // DNS Flag Day 2020 (RFC 6891): 1232-byte UDP payload prevents IPv4/IPv6 fragmentation
    const val EDNS0_SAFE_UDP_PAYLOAD_SIZE = 1232
    // Standard 1500-byte TUN MTU minus IP (20B/40B) and UDP (8B) headers
    const val MAX_IPV4_UDP_DNS_PAYLOAD = 1472
    const val MAX_IPV6_UDP_DNS_PAYLOAD = 1452

    private const val RR_TYPE_OPT = 41

    val EXCLUDED_DNS_IPS: Set<String>
        get() = DnsIpPolicy.EXCLUDED_DNS_IPS

    internal fun isExcludedDnsIp(ip: String): Boolean = DnsIpPolicy.isExcludedDnsIp(ip)

    internal fun isNonPublicOrReservedIp(addr: InetAddress): Boolean = DnsIpPolicy.isNonPublicOrReservedIp(addr)

    internal fun isDisallowedPhysicalDnsIp(addr: InetAddress): Boolean = DnsIpPolicy.isDisallowedPhysicalDnsIp(addr)

    internal fun isKnownCaptivePortalProbeDomain(qName: String): Boolean =
        DnsIpPolicy.isKnownCaptivePortalProbeDomain(qName)

    internal fun normalizeCustomHost(rawHost: String?): String? =
        rawHost?.let { DotUpstreamClient.sanitizeHost(it) }?.takeIf { it.isNotBlank() }

    internal fun isSoftFailureResponse(reply: ByteArray): Boolean {
        if (reply.size < DnsPacketParser.DNS_HEADER_LEN) return false
        val rcode = reply[3].toInt() and 0x0F
        return rcode == 2 || rcode == 5 // SERVFAIL or REFUSED
    }

    internal fun isCacheableDnsResponse(reply: ByteArray): Boolean {
        if (reply.size < DnsPacketParser.DNS_HEADER_LEN) return false
        val flagsHi = reply[2].toInt() and 0xFF
        // Must be QR=1 (response), OPCODE=0 (standard query), TC=0 (not truncated)
        if ((flagsHi and 0x80) == 0 || (flagsHi and 0x78) != 0 || (flagsHi and 0x02) != 0) return false
        val rcode = reply[3].toInt() and 0x0F
        if (rcode != 0 && rcode != 3) return false // NOERROR or NXDOMAIN only

        val arCount = readUint16(reply, 10)
        if (arCount > 0) {
            val additionalStart = findAdditionalSectionOffset(reply) ?: return false
            if (hasNonZeroEdnsExtendedRcode(reply, additionalStart, arCount)) return false
        }
        return true
    }

    internal fun isLocalIntranetDomain(qName: String): Boolean = DnsIpPolicy.isLocalIntranetDomain(qName)

    internal fun isValidDnsResponse(reply: ByteArray, length: Int, requestPayload: ByteArray): Boolean {
        if (length < DnsPacketParser.DNS_HEADER_LEN || reply.size < length) return false
        if (requestPayload.size < DnsPacketParser.DNS_HEADER_LEN) return false
        // QR bit must be 1 (Response) and OPCODE must be 0 (Standard Query)
        val flagsHi = reply[2].toInt() and 0xFF
        if ((flagsHi and 0x80) == 0 || (flagsHi and 0x78) != 0) return false
        // Transaction ID must match client query
        if (reply[0] != requestPayload[0] || reply[1] != requestPayload[1]) return false

        // RFC 5452 §9.1: Verify Question section (QDCOUNT, QNAME case-insensitively, QTYPE, QCLASS)
        val reqQdCount = readUint16(requestPayload, 4)
        val repQdCount = readUint16(reply, 4)
        if (reqQdCount != 1) return true
        if (repQdCount != 1) return false

        val reqNameEnd = skipDomainName(requestPayload, DnsPacketParser.DNS_HEADER_LEN) ?: return false
        val repNameEnd = skipDomainName(reply, DnsPacketParser.DNS_HEADER_LEN) ?: return false
        if (reqNameEnd + 4 > requestPayload.size || repNameEnd + 4 > length) return false
        for (i in 0 until 4) {
            if (requestPayload[reqNameEnd + i] != reply[repNameEnd + i]) return false
        }
        return wireNamesEqualIgnoreCase(
            requestPayload, DnsPacketParser.DNS_HEADER_LEN, reqNameEnd,
            reply, DnsPacketParser.DNS_HEADER_LEN, repNameEnd
        )
    }

    private fun wireNamesEqualIgnoreCase(
        a: ByteArray, aStart: Int, aEnd: Int,
        b: ByteArray, bStart: Int, bEnd: Int
    ): Boolean {
        if (aEnd - aStart != bEnd - bStart) return false
        var i = aStart
        var j = bStart
        while (i < aEnd) {
            val lenA = a[i].toInt() and 0xFF
            val lenB = b[j].toInt() and 0xFF
            if (lenA != lenB || lenA > 63) return false
            i++
            j++
            if (lenA == 0) return i == aEnd && j == bEnd
            if (i + lenA > aEnd || j + lenB > bEnd) return false
            for (k in 0 until lenA) {
                val ca = a[i + k].toInt() and 0xFF
                val cb = b[j + k].toInt() and 0xFF
                val la = if (ca in 'A'.code..'Z'.code) ca + 32 else ca
                val lb = if (cb in 'A'.code..'Z'.code) cb + 32 else cb
                if (la != lb) return false
            }
            i += lenA
            j += lenB
        }
        return false
    }

    fun parseIpv4ToInetAddress(rawText: String): InetAddress? =
        DnsIpPolicy.parseIpv4ToInetAddress(rawText)

    internal fun extractTtl(dnsPayload: ByteArray): Long {
        if (dnsPayload.size < DnsPacketParser.DNS_HEADER_LEN) return 0L
        val anCount = readUint16(dnsPayload, 6)
        if (anCount <= 0) return 30L

        return try {
            val qdCount = readUint16(dnsPayload, 4)
            var offset = DnsPacketParser.DNS_HEADER_LEN
            for (q in 0 until qdCount) {
                if (offset >= dnsPayload.size) return 0L
                offset = skipDomainName(dnsPayload, offset) ?: return 0L
                offset += 4 // QTYPE (2) + QCLASS (2)
                if (offset > dnsPayload.size) return 0L
            }

            // RFC 2181 §5.2: Take minimum TTL across all Answer RRs; return 0L on any malformed/uncacheable RR
            var minTtl = Long.MAX_VALUE
            for (a in 0 until anCount) {
                if (offset >= dnsPayload.size) return 0L
                offset = skipDomainName(dnsPayload, offset) ?: return 0L
                if (offset + 10 > dnsPayload.size) return 0L
                val ttl = ((dnsPayload[offset + 4].toLong() and 0xFF) shl 24) or
                    ((dnsPayload[offset + 5].toLong() and 0xFF) shl 16) or
                    ((dnsPayload[offset + 6].toLong() and 0xFF) shl 8) or
                    (dnsPayload[offset + 7].toLong() and 0xFF)
                // RFC 1035 / RFC 2181 §8: TTL=0 or MSB-set (> Int.MAX_VALUE) must NOT be cached
                if (ttl <= 0L || ttl > Int.MAX_VALUE.toLong()) return 0L
                if (ttl < minTtl) minTtl = ttl
                val rdLen = readUint16(dnsPayload, offset + 8)
                offset += 10 + rdLen
                if (offset > dnsPayload.size) return 0L
            }
            if (minTtl == Long.MAX_VALUE) 0L else minTtl.coerceIn(10L, 120L)
        } catch (e: Exception) {
            Timber.tag(TAG).d(e, "Failed extracting DNS TTL")
            0L
        }
    }

    private fun findAdditionalSectionOffset(reply: ByteArray): Int? {
        val qdCount = readUint16(reply, 4)
        val anCount = readUint16(reply, 6)
        val nsCount = readUint16(reply, 8)
        var offset = DnsPacketParser.DNS_HEADER_LEN
        for (q in 0 until qdCount) {
            if (offset >= reply.size) return null
            offset = skipDomainName(reply, offset) ?: return null
            offset += 4
            if (offset > reply.size) return null
        }
        val nonArCount = anCount + nsCount
        for (i in 0 until nonArCount) {
            if (offset >= reply.size) return null
            offset = skipDomainName(reply, offset) ?: return null
            if (offset + 10 > reply.size) return null
            val rdLen = readUint16(reply, offset + 8)
            offset += 10 + rdLen
            if (offset > reply.size) return null
        }
        return offset
    }

    /**
     * Clamps any RFC 6891 EDNS0 OPT record's advertised UDP payload size (CLASS field)
     * to [maxPayload] (default 1232 bytes per DNS Flag Day 2020) so upstream resolvers
     * never return fragmented or MTU-exceeding UDP responses.
     * Zero-copy fast path when ARCOUNT == 0 or OPT size is already <= [maxPayload].
     */
    internal fun clampEdnsUdpPayloadSize(
        rawPayload: ByteArray,
        maxPayload: Int = EDNS0_SAFE_UDP_PAYLOAD_SIZE
    ): ByteArray {
        if (rawPayload.size < DnsPacketParser.DNS_HEADER_LEN || maxPayload <= 0) return rawPayload
        val arCount = readUint16(rawPayload, 10)
        if (arCount <= 0) return rawPayload

        val qdCount = readUint16(rawPayload, 4)
        val anCount = readUint16(rawPayload, 6)
        val nsCount = readUint16(rawPayload, 8)

        var offset = DnsPacketParser.DNS_HEADER_LEN
        for (q in 0 until qdCount) {
            if (offset >= rawPayload.size) return rawPayload
            offset = skipDomainName(rawPayload, offset) ?: return rawPayload
            offset += 4 // QTYPE (2) + QCLASS (2)
            if (offset > rawPayload.size) return rawPayload
        }

        val nonArCount = anCount + nsCount
        for (i in 0 until nonArCount) {
            if (offset >= rawPayload.size) return rawPayload
            offset = skipDomainName(rawPayload, offset) ?: return rawPayload
            if (offset + 10 > rawPayload.size) return rawPayload
            val rdLen = readUint16(rawPayload, offset + 8)
            offset += 10
            if (offset + rdLen > rawPayload.size) return rawPayload
            offset += rdLen
        }

        var result: ByteArray? = null
        for (i in 0 until arCount) {
            if (offset >= rawPayload.size) return result ?: rawPayload
            val isRootLabel = rawPayload[offset] == 0.toByte()
            val afterName = skipDomainName(rawPayload, offset) ?: return result ?: rawPayload
            if (afterName + 10 > rawPayload.size) return result ?: rawPayload
            val rrType = readUint16(rawPayload, afterName)
            val rdLen = readUint16(rawPayload, afterName + 8)
            val rrDataStart = afterName + 10
            if (rrDataStart + rdLen > rawPayload.size) return result ?: rawPayload
            if (isRootLabel && rrType == RR_TYPE_OPT) {
                // RFC 6891 §6.1.2: NAME (0x00) + TYPE (41) + CLASS (2-byte UDP payload size)
                val advertisedSize = readUint16(rawPayload, afterName + 2)
                if (advertisedSize > maxPayload) {
                    val copy = result ?: rawPayload.copyOf().also { result = it }
                    copy[afterName + 2] = ((maxPayload ushr 8) and 0xFF).toByte()
                    copy[afterName + 3] = (maxPayload and 0xFF).toByte()
                }
            }
            offset = rrDataStart + rdLen
        }
        return result ?: rawPayload
    }

    /**
     * Ensures a DNS wire response fits within [maxDnsPayload] bytes for a single TUN MTU write.
     * - O(1) zero-copy fast path when `reply.size <= maxDnsPayload`.
     * - Slow path progressively trims Additional, then Authority+Additional (safe because RFC 1035
     *   compression pointers never point forward into later sections), keeping TC=0 when possible.
     * - If Answers alone exceed [maxDnsPayload] or wire sections are malformed, returns a clean
     *   RFC 1035/2181 truncated (TC=1) response.
     */
    internal fun fitDnsResponseToMtu(reply: ByteArray, maxDnsPayload: Int): ByteArray {
        if (reply.size <= maxDnsPayload || reply.size < DnsPacketParser.DNS_HEADER_LEN) {
            return reply
        }

        val qdCount = readUint16(reply, 4)
        val anCount = readUint16(reply, 6)
        val nsCount = readUint16(reply, 8)

        var offset = DnsPacketParser.DNS_HEADER_LEN
        var questionEnd: Int? = null
        var answersEnd: Int? = null
        var authorityEnd: Int? = null
        var parseOk = true

        for (q in 0 until qdCount) {
            if (offset >= reply.size) {
                parseOk = false
                break
            }
            val next = skipDomainName(reply, offset)
            if (next == null || next + 4 > reply.size) {
                parseOk = false
                break
            }
            offset = next + 4
        }

        if (parseOk) {
            questionEnd = offset
            for (a in 0 until anCount) {
                if (offset >= reply.size) {
                    parseOk = false
                    break
                }
                val next = skipDomainName(reply, offset)
                if (next == null || next + 10 > reply.size) {
                    parseOk = false
                    break
                }
                val rdLen = readUint16(reply, next + 8)
                val rrEnd = next + 10 + rdLen
                if (rrEnd > reply.size) {
                    parseOk = false
                    break
                }
                offset = rrEnd
            }
        }

        if (parseOk) {
            answersEnd = offset
            for (ns in 0 until nsCount) {
                if (offset >= reply.size) {
                    parseOk = false
                    break
                }
                val next = skipDomainName(reply, offset)
                if (next == null || next + 10 > reply.size) {
                    parseOk = false
                    break
                }
                val rdLen = readUint16(reply, next + 8)
                val rrEnd = next + 10 + rdLen
                if (rrEnd > reply.size) {
                    parseOk = false
                    break
                }
                offset = rrEnd
            }
        }

        if (parseOk) {
            authorityEnd = offset
        }

        // RFC 6891 §6.1.3: Never strip an OPT RR carrying a non-zero Extended RCODE (e.g., BADVERS=16)
        // with TC=0, as doing so would misrepresent an extended error as a cacheable NOERROR.
        val arCount = readUint16(reply, 10)
        val hasExtendedRcode = authorityEnd != null &&
            hasNonZeroEdnsExtendedRcode(reply, authorityEnd, arCount)

        // Step 1: Strip Additional records only (TC unchanged; safe per RFC 1035 backward-only pointers)
        if (!hasExtendedRcode && authorityEnd != null && authorityEnd <= maxDnsPayload) {
            val trimmed = reply.copyOf(authorityEnd)
            trimmed[10] = 0 // ARCOUNT = 0
            trimmed[11] = 0
            return trimmed
        }

        // Step 2: Strip Authority + Additional records (TC unchanged)
        if (!hasExtendedRcode && answersEnd != null && answersEnd <= maxDnsPayload) {
            val trimmed = reply.copyOf(answersEnd)
            trimmed[8] = 0  // NSCOUNT = 0
            trimmed[9] = 0
            trimmed[10] = 0 // ARCOUNT = 0
            trimmed[11] = 0
            return trimmed
        }

        // Step 3: Even Answers exceed MTU (or malformed wire structure) -> minimal TC=1 response.
        // Note: DnsTunnelWorker currently sends TCP RST on port 53, so huge Answer-only responses
        // > MTU will not follow up over TCP/53 in Beta, while normal A/AAAA/HTTPS responses are well under 500B.
        return if (questionEnd != null && questionEnd <= maxDnsPayload) {
            val truncated = reply.copyOf(questionEnd)
            truncated[2] = (truncated[2].toInt() or 0x02).toByte() // TC=1
            truncated[6] = 0; truncated[7] = 0   // ANCOUNT = 0
            truncated[8] = 0; truncated[9] = 0   // NSCOUNT = 0
            truncated[10] = 0; truncated[11] = 0 // ARCOUNT = 0
            truncated
        } else {
            val truncated = reply.copyOf(DnsPacketParser.DNS_HEADER_LEN)
            truncated[2] = (truncated[2].toInt() or 0x02).toByte() // TC=1
            truncated[4] = 0; truncated[5] = 0   // QDCOUNT = 0
            truncated[6] = 0; truncated[7] = 0   // ANCOUNT = 0
            truncated[8] = 0; truncated[9] = 0   // NSCOUNT = 0
            truncated[10] = 0; truncated[11] = 0 // ARCOUNT = 0
            truncated
        }
    }

    private fun hasNonZeroEdnsExtendedRcode(reply: ByteArray, additionalStart: Int, arCount: Int): Boolean {
        if (arCount <= 0) return false
        var offset = additionalStart
        for (i in 0 until arCount) {
            if (offset >= reply.size) return false
            val isRootLabel = reply[offset] == 0.toByte()
            val afterName = skipDomainName(reply, offset) ?: return false
            if (afterName + 10 > reply.size) return false
            val rrType = readUint16(reply, afterName)
            val rdLen = readUint16(reply, afterName + 8)
            val rrEnd = afterName + 10 + rdLen
            if (rrEnd > reply.size) return false
            // RFC 6891 §6.1.2: OPT TTL byte 0 is EXTENDED-RCODE (upper 8 bits)
            if (isRootLabel && rrType == RR_TYPE_OPT && reply[afterName + 4] != 0.toByte()) {
                return true
            }
            offset = rrEnd
        }
        return false
    }

    private fun readUint16(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xFF) shl 8) or (bytes[offset + 1].toInt() and 0xFF)

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
