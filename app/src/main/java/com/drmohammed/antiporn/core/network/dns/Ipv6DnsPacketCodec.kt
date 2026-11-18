package com.drmohammed.antiporn.core.network.dns

import java.nio.ByteBuffer

/**
 * IPv6 UDP/53 packet parsing, header writing, and RFC 8200 §8.1 checksum helpers
 * extracted from [DnsPacketParser] to keep packet codec responsibilities modular.
 */
internal object Ipv6DnsPacketCodec {

    internal const val IPV6_HEADER_LEN = 40
    private const val UDP_HEADER_LEN = DnsPacketParser.UDP_HEADER_LEN
    private const val DNS_HEADER_LEN = DnsPacketParser.DNS_HEADER_LEN
    private const val DNS_PORT = DnsPacketParser.DNS_PORT

    internal data class Ipv6UpperLayer(
        val protocol: Int,
        val headerOffset: Int,
        val actualIpEnd: Int
    )

    internal fun resolveUpperLayer(buffer: ByteBuffer, length: Int): Ipv6UpperLayer? {
        if (length < IPV6_HEADER_LEN) return null
        val version = (buffer.get(0).toInt() and 0xFF) ushr 4
        if (version != 6) return null

        val payloadLen = buffer.getShort(4).toInt() and 0xFFFF
        val actualIpEnd = IPV6_HEADER_LEN + payloadLen
        if (payloadLen <= 0 || actualIpEnd > length) return null

        // Walk IPv6 extension headers safely per RFC 8200 (Hop-by-Hop=0, Routing=43, Destination Options=60)
        var nextHeader = buffer.get(6).toInt() and 0xFF
        var offset = IPV6_HEADER_LEN
        var hops = 0
        while (nextHeader == 0 || nextHeader == 43 || nextHeader == 60) {
            if (hops++ >= 4 || offset + 2 > actualIpEnd) return null
            val next = buffer.get(offset).toInt() and 0xFF
            val hdrExtLen = buffer.get(offset + 1).toInt() and 0xFF
            val extLen = (hdrExtLen + 1) * 8
            if (offset + extLen > actualIpEnd) return null
            offset += extLen
            nextHeader = next
        }
        // Reject IPv6 Fragment header (44)
        if (nextHeader == 44) return null
        return Ipv6UpperLayer(protocol = nextHeader, headerOffset = offset, actualIpEnd = actualIpEnd)
    }

    internal fun parseIpv6DnsQuery(
        packet: ByteArray,
        length: Int,
        buffer: ByteBuffer,
        onDnsPayload: (
            packet: ByteArray,
            dnsOffset: Int,
            dnsPayloadLength: Int,
            srcIp: ByteArray,
            dstIp: ByteArray,
            srcPort: Int,
            dstPort: Int,
            ipId: Int,
            ipVersion: Int
        ) -> DnsPacketParser.ParsedDnsQuery?
    ): DnsPacketParser.ParsedDnsQuery? {
        if (length < IPV6_HEADER_LEN + UDP_HEADER_LEN + DNS_HEADER_LEN) return null
        val upper = resolveUpperLayer(buffer, length) ?: return null
        if (upper.protocol != 17) return null

        val offset = upper.headerOffset
        val actualIpEnd = upper.actualIpEnd
        if (offset + UDP_HEADER_LEN + DNS_HEADER_LEN > actualIpEnd) return null

        val srcIp = ByteArray(16)
        val dstIp = ByteArray(16)
        buffer.position(8)
        buffer.get(srcIp)
        buffer.get(dstIp)

        // Validate UDP Header at offset
        buffer.position(offset)
        val srcPort = buffer.short.toInt() and 0xFFFF
        val dstPort = buffer.short.toInt() and 0xFFFF
        val udpLength = buffer.short.toInt() and 0xFFFF
        buffer.short // skip checksum

        if (dstPort != DNS_PORT || udpLength < UDP_HEADER_LEN + DNS_HEADER_LEN) return null
        if (udpLength > actualIpEnd - offset) return null // Reject truncated UDP datagrams

        val dnsPayloadLength = udpLength - UDP_HEADER_LEN
        if (dnsPayloadLength < DNS_HEADER_LEN) return null

        return onDnsPayload(
            packet,
            offset + UDP_HEADER_LEN,
            dnsPayloadLength,
            srcIp,
            dstIp,
            srcPort,
            dstPort,
            0,
            6
        )
    }

    internal fun writeIpv6Headers(
        buffer: ByteBuffer,
        udpTotalLen: Int,
        srcIp: ByteArray,
        dstIp: ByteArray,
        srcPort: Int,
        dstPort: Int
    ) {
        // IPv6 Header (40 bytes)
        buffer.putInt(0x60000000) // Version 6, Traffic Class 0, Flow Label 0
        buffer.putShort(udpTotalLen.toShort()) // Payload Length
        buffer.put(17.toByte()) // Next Header = UDP
        buffer.put(64.toByte()) // Hop Limit
        buffer.put(srcIp)
        buffer.put(dstIp)

        // UDP Header (8 bytes) at offset 40
        buffer.position(IPV6_HEADER_LEN)
        buffer.putShort(srcPort.toShort())
        buffer.putShort(dstPort.toShort())
        buffer.putShort(udpTotalLen.toShort())
        buffer.putShort(0.toShort()) // Checksum placeholder (filled after DNS payload)
    }

    internal fun calculateIpv6UdpChecksum(
        srcIp: ByteArray,
        dstIp: ByteArray,
        udpLen: Int,
        udpPacket: ByteArray,
        udpOffset: Int,
        udpPacketLen: Int
    ): Short {
        var sum = 0L

        // Pseudo-header: Src IPv6 (16 bytes)
        for (i in 0 until 16 step 2) {
            sum += (((srcIp[i].toInt() and 0xFF) shl 8) or (srcIp[i + 1].toInt() and 0xFF))
        }
        // Pseudo-header: Dst IPv6 (16 bytes)
        for (i in 0 until 16 step 2) {
            sum += (((dstIp[i].toInt() and 0xFF) shl 8) or (dstIp[i + 1].toInt() and 0xFF))
        }
        // UDP length (32-bit upper-layer packet length in pseudo header per RFC 8200 §8.1)
        sum += ((udpLen ushr 16) and 0xFFFF)
        sum += (udpLen and 0xFFFF)
        // Next header = 17
        sum += 17

        // UDP packet data
        var i = udpOffset
        val end = udpOffset + udpPacketLen
        while (i < end - 1) {
            sum += (((udpPacket[i].toInt() and 0xFF) shl 8) or (udpPacket[i + 1].toInt() and 0xFF))
            i += 2
        }
        if (i < end) {
            sum += ((udpPacket[i].toInt() and 0xFF) shl 8)
        }

        while ((sum ushr 16) > 0) {
            sum = (sum and 0xFFFF) + (sum ushr 16)
        }

        var result = (sum.inv() and 0xFFFF).toInt()
        if (result == 0) result = 0xFFFF
        return result.toShort()
    }
}
