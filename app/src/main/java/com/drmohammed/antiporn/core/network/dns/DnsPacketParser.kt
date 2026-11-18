package com.drmohammed.antiporn.core.network.dns

import java.nio.ByteBuffer

/**
 * Lightweight, zero-dependency DNS and IPv4/IPv6 UDP packet processor.
 * Focuses strictly on extracting QNAME from incoming DNS queries, matching against
 * blocked domains, and generating immediate synthetic NXDOMAIN / SafeSearch VIP replies,
 * while allowing raw payload passthrough for allowed queries.
 */
object DnsPacketParser {

    const val DNS_PORT = 53
    const val IP_HEADER_MIN_LEN = 20
    const val IPV6_HEADER_LEN = 40
    const val UDP_HEADER_LEN = 8
    const val DNS_HEADER_LEN = 12

    // RFC 1035 RCODE 3: Non-Existent Domain (NXDOMAIN)
    // Flags 0x8183: QR=1 (Response), Opcode=0 (Query), AA=0, TC=0, RD=1, RA=1, RCODE=3 (NXDOMAIN)
    private const val FLAGS_NXDOMAIN: Short = 0x8183.toShort()
    private const val FLAGS_NOERROR: Short = 0x8180.toShort()

    val YOUTUBE_RESTRICTED_VIP_IPV4: ByteArray = byteArrayOf(
        216.toByte(), 239.toByte(), 38.toByte(), 120.toByte() // 216.239.38.120
    )

    // Google's official restrict.youtube.com strict IPv6 VIP (2001:4860:4802:32::78)
    val YOUTUBE_RESTRICTED_VIP_IPV6: ByteArray = byteArrayOf(
        0x20, 0x01, 0x48, 0x60, 0x48, 0x02, 0x00, 0x32,
        0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x78
    )

    data class ParsedDnsQuery(
        val srcIp: ByteArray,
        val dstIp: ByteArray,
        val srcPort: Int,
        val dstPort: Int,
        val ipId: Int,
        val transactionId: Short,
        val qName: String,
        val qType: Int = 1,
        val questionSection: ByteArray,
        val rawDnsPayload: ByteArray,
        val ipVersion: Int = 4
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is ParsedDnsQuery) return false
            return srcPort == other.srcPort && dstPort == other.dstPort && ipVersion == other.ipVersion &&
                    transactionId == other.transactionId && qName == other.qName && qType == other.qType &&
                    srcIp.contentEquals(other.srcIp) && dstIp.contentEquals(other.dstIp) &&
                    questionSection.contentEquals(other.questionSection) &&
                    rawDnsPayload.contentEquals(other.rawDnsPayload)
        }

        override fun hashCode(): Int {
            var result = srcPort
            result = 31 * result + dstPort
            result = 31 * result + ipVersion
            result = 31 * result + transactionId
            result = 31 * result + qName.hashCode()
            result = 31 * result + qType
            result = 31 * result + srcIp.contentHashCode()
            result = 31 * result + dstIp.contentHashCode()
            result = 31 * result + questionSection.contentHashCode()
            result = 31 * result + rawDnsPayload.contentHashCode()
            return result
        }
    }

    /**
     * Parses an incoming raw IPv4/UDP or IPv6/UDP packet from the TUN interface.
     * Returns [ParsedDnsQuery] if it is a valid DNS query on port 53, or null otherwise.
     */
    fun parseDnsQuery(packet: ByteArray, length: Int): ParsedDnsQuery? {
        if (length > packet.size || length < IP_HEADER_MIN_LEN + UDP_HEADER_LEN + DNS_HEADER_LEN) return null

        val buffer = ByteBuffer.wrap(packet, 0, length)
        val firstByte = buffer.get(0).toInt() and 0xFF
        val version = firstByte ushr 4

        return when (version) {
            4 -> parseIpv4DnsQuery(packet, length, buffer, firstByte)
            6 -> Ipv6DnsPacketCodec.parseIpv6DnsQuery(packet, length, buffer, ::parseDnsPayload)
            else -> null
        }
    }

    private fun parseIpv4DnsQuery(
        packet: ByteArray,
        length: Int,
        buffer: ByteBuffer,
        versionAndIhl: Int
    ): ParsedDnsQuery? {
        val ihl = (versionAndIhl and 0x0F) * 4
        if (ihl < IP_HEADER_MIN_LEN || length < ihl + UDP_HEADER_LEN + DNS_HEADER_LEN) return null

        val totalIpLen = buffer.getShort(2).toInt() and 0xFFFF
        if (totalIpLen < ihl + UDP_HEADER_LEN + DNS_HEADER_LEN || totalIpLen > length) return null

        // Reject ALL IPv4 fragments: both MF (0x2000) and non-zero fragment offset (0x1FFF)
        if ((buffer.getShort(6).toInt() and 0x3FFF) != 0) return null

        val protocol = buffer.get(9).toInt() and 0xFF
        if (protocol != 17) return null // Must be UDP

        val ipId = buffer.getShort(4).toInt() and 0xFFFF

        val srcIp = ByteArray(4)
        val dstIp = ByteArray(4)
        buffer.position(12)
        buffer.get(srcIp)
        buffer.get(dstIp)

        // Validate UDP Header
        buffer.position(ihl)
        val srcPort = buffer.short.toInt() and 0xFFFF
        val dstPort = buffer.short.toInt() and 0xFFFF
        val udpLength = buffer.short.toInt() and 0xFFFF
        buffer.short // skip checksum

        if (dstPort != DNS_PORT || udpLength < UDP_HEADER_LEN + DNS_HEADER_LEN) return null
        if (udpLength > totalIpLen - ihl) return null // Reject truncated UDP datagrams

        val dnsPayloadLength = udpLength - UDP_HEADER_LEN
        if (dnsPayloadLength < DNS_HEADER_LEN) return null

        return parseDnsPayload(
            packet = packet,
            dnsOffset = ihl + UDP_HEADER_LEN,
            dnsPayloadLength = dnsPayloadLength,
            srcIp = srcIp,
            dstIp = dstIp,
            srcPort = srcPort,
            dstPort = dstPort,
            ipId = ipId,
            ipVersion = 4
        )
    }

    private fun parseDnsPayload(
        packet: ByteArray,
        dnsOffset: Int,
        dnsPayloadLength: Int,
        srcIp: ByteArray,
        dstIp: ByteArray,
        srcPort: Int,
        dstPort: Int,
        ipId: Int,
        ipVersion: Int
    ): ParsedDnsQuery? {
        val rawDnsPayload = ByteArray(dnsPayloadLength)
        System.arraycopy(packet, dnsOffset, rawDnsPayload, 0, dnsPayloadLength)

        // Parse DNS Header
        val dnsBuffer = ByteBuffer.wrap(rawDnsPayload)
        val transactionId = dnsBuffer.short
        val flags = dnsBuffer.short.toInt() and 0xFFFF
        val isQuery = (flags and 0x8000) == 0
        if (!isQuery) return null

        val opcode = (flags and 0x7800) ushr 11
        if (opcode != 0) return null

        val qdCount = dnsBuffer.short.toInt() and 0xFFFF
        if (qdCount != 1) return null

        dnsBuffer.position(DNS_HEADER_LEN) // Skip ANCOUNT, NSCOUNT, ARCOUNT to Question section

        // Extract QNAME
        val questionStart = dnsBuffer.position()
        val labels = mutableListOf<String>()
        var isTerminated = false

        while (dnsBuffer.hasRemaining()) {
            val labelLen = dnsBuffer.get().toInt() and 0xFF
            if (labelLen == 0) {
                isTerminated = true
                break // End of QNAME
            }
            if (labelLen > 63 || dnsBuffer.remaining() < labelLen) return null // Malformed label
            if (dnsBuffer.position() + labelLen - questionStart >= 255) return null // RFC 1035 max 255-byte QNAME

            val labelBytes = ByteArray(labelLen)
            dnsBuffer.get(labelBytes)
            labels.add(String(labelBytes, Charsets.US_ASCII))
        }

        if (!isTerminated || labels.isEmpty() || dnsBuffer.position() - questionStart > 255) return null
        val qName = labels.joinToString(".").lowercase()

        // Read QTYPE and QCLASS (4 bytes)
        if (dnsBuffer.remaining() < 4) return null
        val qType = dnsBuffer.short.toInt() and 0xFFFF
        dnsBuffer.short // skip QCLASS
        val questionEnd = dnsBuffer.position()

        val questionSection = ByteArray(questionEnd - questionStart)
        System.arraycopy(rawDnsPayload, questionStart, questionSection, 0, questionSection.size)

        return ParsedDnsQuery(
            srcIp = srcIp,
            dstIp = dstIp,
            srcPort = srcPort,
            dstPort = dstPort,
            ipId = ipId,
            transactionId = transactionId,
            qName = qName,
            qType = qType,
            questionSection = questionSection,
            rawDnsPayload = rawDnsPayload,
            ipVersion = ipVersion
        )
    }

    fun sanitizeRule(rule: String): String = DomainRuleMatcher.sanitizeRule(rule)

    fun compileEffectiveBlockedDomains(rules: Set<String>): Set<String> =
        DomainRuleMatcher.compileEffectiveBlockedDomains(rules)

    fun isDomainBlockedCompiled(qName: String, compiledBlockedDomains: Set<String>): Boolean =
        DomainRuleMatcher.isDomainBlockedCompiled(qName, compiledBlockedDomains)

    fun isDomainBlocked(qName: String, customBlockedDomains: Set<String>): Boolean =
        DomainRuleMatcher.isDomainBlocked(qName, customBlockedDomains)

    /**
     * Constructs a synthetic IPv4/UDP or IPv6/UDP packet containing an immediate DNS NXDOMAIN response.
     * Swaps source/destination IPs and ports, keeping overhead to a few microseconds.
     */
    fun buildNxDomainResponse(query: ParsedDnsQuery): ByteArray {
        val isIpv6 = query.srcIp.size == 16 && query.dstIp.size == 16
        val ipHeaderLen = if (isIpv6) IPV6_HEADER_LEN else IP_HEADER_MIN_LEN
        val dnsHeaderAndQuestionLen = DNS_HEADER_LEN + query.questionSection.size
        val udpTotalLen = UDP_HEADER_LEN + dnsHeaderAndQuestionLen
        val ipTotalLen = ipHeaderLen + udpTotalLen

        val response = ByteArray(ipTotalLen)
        val buffer = ByteBuffer.wrap(response)

        if (isIpv6) {
            Ipv6DnsPacketCodec.writeIpv6Headers(buffer, udpTotalLen, query.dstIp, query.srcIp, query.dstPort, query.srcPort)
        } else {
            writeHeaders(buffer, response, ipTotalLen, udpTotalLen, query.ipId, query.dstIp, query.srcIp, query.dstPort, query.srcPort)
        }

        // 3. DNS Header (12 bytes)
        buffer.position(ipHeaderLen + UDP_HEADER_LEN)
        buffer.putShort(query.transactionId)
        buffer.putShort(FLAGS_NXDOMAIN)
        buffer.putShort(1.toShort()) // QDCOUNT = 1
        buffer.putShort(0.toShort()) // ANCOUNT = 0
        buffer.putShort(0.toShort()) // NSCOUNT = 0
        buffer.putShort(0.toShort()) // ARCOUNT = 0
        buffer.put(query.questionSection)

        if (isIpv6) {
            val checksum = Ipv6DnsPacketCodec.calculateIpv6UdpChecksum(query.dstIp, query.srcIp, udpTotalLen, response, IPV6_HEADER_LEN, udpTotalLen)
            buffer.putShort(IPV6_HEADER_LEN + 6, checksum)
        }

        return response
    }

    /**
     * Constructs a synthetic IPv4/UDP or IPv6/UDP packet containing a SafeSearch / Restricted Mode VIP response.
     * - For A queries (qType == 1): Returns NOERROR with ANCOUNT=1 pointing to 216.239.38.120 (TTL=300s).
     * - For AAAA queries (qType == 28): Returns NOERROR with ANCOUNT=1 pointing to 2001:4860:4802:32::78 (TTL=300s).
     * - For HTTPS (qType == 65) or other types: Returns a standard NODATA response (NOERROR with ANCOUNT=0).
     */
    fun buildSafeSearchVipResponse(query: ParsedDnsQuery): ByteArray {
        val isIpv6 = query.srcIp.size == 16 && query.dstIp.size == 16
        val ipHeaderLen = if (isIpv6) IPV6_HEADER_LEN else IP_HEADER_MIN_LEN
        val isTypeA = query.qType == 1
        val isTypeAaaa = query.qType == 28
        val answerLen = when {
            isTypeA -> 16
            isTypeAaaa -> 28
            else -> 0
        }
        val dnsHeaderAndQuestionLen = DNS_HEADER_LEN + query.questionSection.size + answerLen
        val udpTotalLen = UDP_HEADER_LEN + dnsHeaderAndQuestionLen
        val ipTotalLen = ipHeaderLen + udpTotalLen

        val response = ByteArray(ipTotalLen)
        val buffer = ByteBuffer.wrap(response)

        if (isIpv6) {
            Ipv6DnsPacketCodec.writeIpv6Headers(buffer, udpTotalLen, query.dstIp, query.srcIp, query.dstPort, query.srcPort)
        } else {
            writeHeaders(buffer, response, ipTotalLen, udpTotalLen, query.ipId, query.dstIp, query.srcIp, query.dstPort, query.srcPort)
        }

        // 3. DNS Header (12 bytes)
        buffer.position(ipHeaderLen + UDP_HEADER_LEN)
        buffer.putShort(query.transactionId)
        buffer.putShort(FLAGS_NOERROR)
        buffer.putShort(1.toShort()) // QDCOUNT = 1
        buffer.putShort(if (isTypeA || isTypeAaaa) 1.toShort() else 0.toShort()) // ANCOUNT
        buffer.putShort(0.toShort()) // NSCOUNT = 0
        buffer.putShort(0.toShort()) // ARCOUNT = 0
        buffer.put(query.questionSection)

        if (isTypeA) {
            buffer.put(0xC0.toByte())
            buffer.put(0x0C.toByte()) // Compression pointer to QNAME at offset 12
            buffer.putShort(1.toShort()) // TYPE: A (1)
            buffer.putShort(1.toShort()) // CLASS: IN (1)
            buffer.putInt(300) // TTL: 300 seconds
            buffer.putShort(4.toShort()) // RDLENGTH: 4
            buffer.put(YOUTUBE_RESTRICTED_VIP_IPV4) // 216.239.38.120
        } else if (isTypeAaaa) {
            buffer.put(0xC0.toByte())
            buffer.put(0x0C.toByte()) // Compression pointer to QNAME at offset 12
            buffer.putShort(28.toShort()) // TYPE: AAAA (28 / 0x001C)
            buffer.putShort(1.toShort()) // CLASS: IN (1)
            buffer.putInt(300) // TTL: 300 seconds
            buffer.putShort(16.toShort()) // RDLENGTH: 16
            buffer.put(YOUTUBE_RESTRICTED_VIP_IPV6) // 2001:4860:4802:32::78
        }

        if (isIpv6) {
            val checksum = Ipv6DnsPacketCodec.calculateIpv6UdpChecksum(query.dstIp, query.srcIp, udpTotalLen, response, IPV6_HEADER_LEN, udpTotalLen)
            buffer.putShort(IPV6_HEADER_LEN + 6, checksum)
        }

        return response
    }

    private fun writeHeaders(
        buffer: ByteBuffer,
        response: ByteArray,
        ipTotalLen: Int,
        udpTotalLen: Int,
        ipId: Int,
        srcIp: ByteArray,
        dstIp: ByteArray,
        srcPort: Int,
        dstPort: Int
    ) {
        // IPv4 Header (20 bytes)
        buffer.put(0x45.toByte())
        buffer.put(0x00.toByte())
        buffer.putShort(ipTotalLen.toShort())
        buffer.putShort(ipId.toShort())
        buffer.putShort(0x4000.toShort())
        buffer.put(64.toByte())
        buffer.put(17.toByte())
        buffer.putShort(0.toShort())
        buffer.put(srcIp)
        buffer.put(dstIp)
        val ipChecksum = calculateIpChecksum(response, 0, IP_HEADER_MIN_LEN)
        buffer.putShort(10, ipChecksum)

        // UDP Header (8 bytes)
        buffer.position(IP_HEADER_MIN_LEN)
        buffer.putShort(srcPort.toShort())
        buffer.putShort(dstPort.toShort())
        buffer.putShort(udpTotalLen.toShort())
        buffer.putShort(0.toShort())
    }

    /**
     * Wraps a raw upstream DNS response payload into an IPv4/UDP or IPv6/UDP packet to send back to the client.
     */
    fun wrapUpstreamDnsResponse(
        clientIp: ByteArray,
        serverIp: ByteArray,
        clientPort: Int,
        serverPort: Int,
        dnsResponsePayload: ByteArray
    ): ByteArray {
        require((clientIp.size == 4 && serverIp.size == 4) || (clientIp.size == 16 && serverIp.size == 16)) {
            "Client and server IPs must both be IPv4 (4 bytes) or IPv6 (16 bytes)"
        }
        val isIpv6 = clientIp.size == 16
        val ipHeaderLen = if (isIpv6) IPV6_HEADER_LEN else IP_HEADER_MIN_LEN
        val udpTotalLen = UDP_HEADER_LEN + dnsResponsePayload.size
        val ipTotalLen = ipHeaderLen + udpTotalLen
        require(udpTotalLen <= 65535 && (isIpv6 || ipTotalLen <= 65535)) {
            "DNS response payload exceeds IP/UDP length limits (udpLen=$udpTotalLen, ipLen=$ipTotalLen)"
        }

        val response = ByteArray(ipTotalLen)
        val buffer = ByteBuffer.wrap(response)

        if (isIpv6) {
            Ipv6DnsPacketCodec.writeIpv6Headers(buffer, udpTotalLen, serverIp, clientIp, serverPort, clientPort)
        } else {
            writeHeaders(buffer, response, ipTotalLen, udpTotalLen, 0, serverIp, clientIp, serverPort, clientPort)
        }

        // DNS Payload
        buffer.position(ipHeaderLen + UDP_HEADER_LEN)
        buffer.put(dnsResponsePayload)

        if (isIpv6) {
            val checksum = Ipv6DnsPacketCodec.calculateIpv6UdpChecksum(serverIp, clientIp, udpTotalLen, response, IPV6_HEADER_LEN, udpTotalLen)
            buffer.putShort(IPV6_HEADER_LEN + 6, checksum)
        }

        return response
    }

    internal fun calculateIpChecksum(data: ByteArray, offset: Int, length: Int): Short {
        var sum = 0
        var i = offset
        val end = offset + length
        while (i < end - 1) {
            val word = ((data[i].toInt() and 0xFF) shl 8) or (data[i + 1].toInt() and 0xFF)
            sum += word
            i += 2
        }
        if (i < end) {
            sum += (data[i].toInt() and 0xFF) shl 8
        }
        while ((sum ushr 16) > 0) {
            sum = (sum and 0xFFFF) + (sum ushr 16)
        }
        return (sum.inv() and 0xFFFF).toShort()
    }

    /**
     * Builds an immediate IPv4/TCP RST packet in response to an incoming TCP probe
     * (e.g. port 853 Opportunistic DoT or port 53 TCP DNS) per RFC 793 / RFC 9293.
     */
    fun buildTcpRstResponse(packet: ByteArray, length: Int): ByteArray? =
        TcpRstPacketBuilder.buildIpv4TcpRstResponse(packet, length)

    /**
     * Builds an immediate IPv6/TCP RST packet in response to an incoming IPv6 TCP probe
     * (e.g. port 853 Opportunistic DoT or port 53 TCP DNS) per RFC 793 / RFC 9293.
     */
    fun buildIpv6TcpRstResponse(packet: ByteArray, length: Int): ByteArray? =
        TcpRstPacketBuilder.buildIpv6TcpRstResponse(packet, length)

    fun calculateIpv4TcpChecksum(
        srcIp: ByteArray,
        dstIp: ByteArray,
        tcpPacket: ByteArray,
        tcpOffset: Int,
        tcpLen: Int
    ): Short = TcpRstPacketBuilder.calculateIpv4TcpChecksum(srcIp, dstIp, tcpPacket, tcpOffset, tcpLen)

    fun calculateIpv6TcpChecksum(
        srcIp: ByteArray,
        dstIp: ByteArray,
        tcpPacket: ByteArray,
        tcpOffset: Int,
        tcpLen: Int
    ): Short = TcpRstPacketBuilder.calculateIpv6TcpChecksum(srcIp, dstIp, tcpPacket, tcpOffset, tcpLen)
}
