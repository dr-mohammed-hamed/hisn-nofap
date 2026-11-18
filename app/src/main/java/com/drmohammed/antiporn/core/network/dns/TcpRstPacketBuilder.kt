package com.drmohammed.antiporn.core.network.dns

import java.nio.ByteBuffer

/**
 * Builds RFC 793 / RFC 9293 compliant IPv4 and IPv6 TCP RST packets for instant fallback
 * when Android probes port 853 (Opportunistic DoT) or port 53 (TCP DNS) on the local TUN.
 */
internal object TcpRstPacketBuilder {

    private const val IPV4_HEADER_MIN_LEN = 20
    private const val IPV6_HEADER_LEN = 40
    private const val TCP_HEADER_MIN_LEN = 20
    private const val PROTOCOL_TCP = 6

    fun buildIpv4TcpRstResponse(packet: ByteArray, length: Int): ByteArray? {
        if (length > packet.size || length < IPV4_HEADER_MIN_LEN + TCP_HEADER_MIN_LEN) return null

        val buffer = ByteBuffer.wrap(packet, 0, length)
        val versionAndIhl = buffer.get(0).toInt() and 0xFF
        if ((versionAndIhl ushr 4) != 4) return null

        val ihl = (versionAndIhl and 0x0F) * 4
        if (ihl < IPV4_HEADER_MIN_LEN || length < ihl + TCP_HEADER_MIN_LEN) return null

        // Reject ALL IPv4 fragments: both MF (0x2000) and non-zero fragment offset (0x1FFF)
        if ((buffer.getShort(6).toInt() and 0x3FFF) != 0) return null

        val protocol = buffer.get(9).toInt() and 0xFF
        if (protocol != PROTOCOL_TCP) return null

        val srcIp = ByteArray(4)
        val dstIp = ByteArray(4)
        buffer.position(12)
        buffer.get(srcIp)
        buffer.get(dstIp)

        val totalIpLen = buffer.getShort(2).toInt() and 0xFFFF
        val actualIpLen = if (totalIpLen in (ihl + TCP_HEADER_MIN_LEN)..length) totalIpLen else length

        val tcpInfo = parseIncomingTcp(buffer, ihl, actualIpLen) ?: return null

        val respTotalLen = IPV4_HEADER_MIN_LEN + TCP_HEADER_MIN_LEN
        val response = ByteArray(respTotalLen)
        val respBuffer = ByteBuffer.wrap(response)

        // IPv4 Header (20 bytes)
        respBuffer.put(0x45.toByte())
        respBuffer.put(0x00.toByte())
        respBuffer.putShort(respTotalLen.toShort())
        respBuffer.putShort(0.toShort())
        respBuffer.putShort(0x4000.toShort()) // Don't Fragment
        respBuffer.put(64.toByte())
        respBuffer.put(PROTOCOL_TCP.toByte())
        respBuffer.putShort(0.toShort()) // Checksum placeholder
        respBuffer.put(dstIp) // Swapped Src IP
        respBuffer.put(srcIp) // Swapped Dst IP
        respBuffer.putShort(10, DnsPacketParser.calculateIpChecksum(response, 0, IPV4_HEADER_MIN_LEN))

        // TCP Header (20 bytes)
        writeTcpRstHeader(respBuffer, IPV4_HEADER_MIN_LEN, tcpInfo)
        val tcpChecksum = calculateIpv4TcpChecksum(dstIp, srcIp, response, IPV4_HEADER_MIN_LEN, TCP_HEADER_MIN_LEN)
        respBuffer.putShort(IPV4_HEADER_MIN_LEN + 16, tcpChecksum)
        return response
    }

    fun buildIpv6TcpRstResponse(packet: ByteArray, length: Int): ByteArray? {
        if (length > packet.size || length < IPV6_HEADER_LEN + TCP_HEADER_MIN_LEN) return null

        val buffer = ByteBuffer.wrap(packet, 0, length)
        val upper = Ipv6DnsPacketCodec.resolveUpperLayer(buffer, length) ?: return null
        if (upper.protocol != PROTOCOL_TCP) return null
        if (upper.headerOffset + TCP_HEADER_MIN_LEN > upper.actualIpEnd) return null

        val srcIp = ByteArray(16)
        val dstIp = ByteArray(16)
        buffer.position(8)
        buffer.get(srcIp)
        buffer.get(dstIp)

        val tcpInfo = parseIncomingTcp(buffer, upper.headerOffset, upper.actualIpEnd) ?: return null

        val respTotalLen = IPV6_HEADER_LEN + TCP_HEADER_MIN_LEN
        val response = ByteArray(respTotalLen)
        val respBuffer = ByteBuffer.wrap(response)

        // IPv6 Header (40 bytes)
        respBuffer.putInt(0x60000000) // Version 6, Traffic Class 0, Flow Label 0
        respBuffer.putShort(TCP_HEADER_MIN_LEN.toShort()) // Payload Length = 20
        respBuffer.put(PROTOCOL_TCP.toByte()) // Next Header = TCP
        respBuffer.put(64.toByte()) // Hop Limit = 64
        respBuffer.put(dstIp) // Swapped Src IPv6
        respBuffer.put(srcIp) // Swapped Dst IPv6

        // TCP Header (20 bytes)
        writeTcpRstHeader(respBuffer, IPV6_HEADER_LEN, tcpInfo)
        val tcpChecksum = calculateIpv6TcpChecksum(dstIp, srcIp, response, IPV6_HEADER_LEN, TCP_HEADER_MIN_LEN)
        respBuffer.putShort(IPV6_HEADER_LEN + 16, tcpChecksum)
        return response
    }

    fun calculateIpv4TcpChecksum(
        srcIp: ByteArray,
        dstIp: ByteArray,
        tcpPacket: ByteArray,
        tcpOffset: Int,
        tcpLen: Int
    ): Short {
        var sum = 0L
        for (i in 0 until 4 step 2) {
            sum += (((srcIp[i].toInt() and 0xFF) shl 8) or (srcIp[i + 1].toInt() and 0xFF))
        }
        for (i in 0 until 4 step 2) {
            sum += (((dstIp[i].toInt() and 0xFF) shl 8) or (dstIp[i + 1].toInt() and 0xFF))
        }
        sum += PROTOCOL_TCP
        sum += (tcpLen and 0xFFFF)
        return finishTcpChecksum(sum, tcpPacket, tcpOffset, tcpLen)
    }

    fun calculateIpv6TcpChecksum(
        srcIp: ByteArray,
        dstIp: ByteArray,
        tcpPacket: ByteArray,
        tcpOffset: Int,
        tcpLen: Int
    ): Short {
        var sum = 0L
        for (i in 0 until 16 step 2) {
            sum += (((srcIp[i].toInt() and 0xFF) shl 8) or (srcIp[i + 1].toInt() and 0xFF))
        }
        for (i in 0 until 16 step 2) {
            sum += (((dstIp[i].toInt() and 0xFF) shl 8) or (dstIp[i + 1].toInt() and 0xFF))
        }
        sum += ((tcpLen ushr 16) and 0xFFFF)
        sum += (tcpLen and 0xFFFF)
        sum += PROTOCOL_TCP
        return finishTcpChecksum(sum, tcpPacket, tcpOffset, tcpLen)
    }

    private data class TcpRstParams(
        val srcPort: Int,
        val dstPort: Int,
        val respSeq: Long,
        val respAck: Long,
        val respFlags: Byte
    )

    private fun parseIncomingTcp(buffer: ByteBuffer, tcpOffset: Int, actualIpLen: Int): TcpRstParams? {
        buffer.position(tcpOffset)
        val srcPort = buffer.short.toInt() and 0xFFFF
        val dstPort = buffer.short.toInt() and 0xFFFF
        val clientSeq = buffer.int.toLong() and 0xFFFFFFFFL
        val clientAck = buffer.int.toLong() and 0xFFFFFFFFL
        val dataOffsetByte = buffer.get().toInt() and 0xFF
        val tcpDataOffset = (dataOffsetByte ushr 4) * 4
        if (tcpDataOffset < TCP_HEADER_MIN_LEN || actualIpLen < tcpOffset + tcpDataOffset) return null

        val tcpFlags = buffer.get().toInt() and 0xFF
        // Never reply to an incoming RST (RFC 9293 §3.5.2)
        if ((tcpFlags and 0x04) != 0) return null

        val hasFin = (tcpFlags and 0x01) != 0
        val hasSyn = (tcpFlags and 0x02) != 0
        val hasAck = (tcpFlags and 0x10) != 0
        val payloadLen = maxOf(0, actualIpLen - tcpOffset - tcpDataOffset)

        // RFC 793 §3.4 / RFC 9293 §3.5.2:
        // Incoming ACK=1 -> <SEQ=SEG.ACK><CTL=RST> (ACK=0, ackNum=0)
        // Incoming ACK=0 -> <SEQ=0><ACK=SEG.SEQ+SEG.LEN><CTL=RST,ACK>
        val respSeq: Long
        val respAck: Long
        val respFlags: Byte
        if (hasAck) {
            respFlags = 0x04.toByte()
            respSeq = clientAck
            respAck = 0L
        } else {
            respFlags = 0x14.toByte()
            respSeq = 0L
            val segLen = payloadLen.toLong() + (if (hasSyn) 1L else 0L) + (if (hasFin) 1L else 0L)
            respAck = (clientSeq + segLen) and 0xFFFFFFFFL
        }

        return TcpRstParams(
            srcPort = srcPort,
            dstPort = dstPort,
            respSeq = respSeq,
            respAck = respAck,
            respFlags = respFlags
        )
    }

    private fun writeTcpRstHeader(respBuffer: ByteBuffer, tcpOffset: Int, params: TcpRstParams) {
        respBuffer.position(tcpOffset)
        respBuffer.putShort(params.dstPort.toShort()) // Swapped Src Port
        respBuffer.putShort(params.srcPort.toShort()) // Swapped Dst Port
        respBuffer.putInt(params.respSeq.toInt())
        respBuffer.putInt(params.respAck.toInt())
        respBuffer.put(0x50.toByte()) // Data Offset = 5 (20 bytes)
        respBuffer.put(params.respFlags)
        respBuffer.putShort(0.toShort()) // Window Size = 0
        respBuffer.putShort(0.toShort()) // Checksum placeholder
        respBuffer.putShort(0.toShort()) // Urgent Pointer = 0
    }

    private fun finishTcpChecksum(initialSum: Long, tcpPacket: ByteArray, tcpOffset: Int, tcpLen: Int): Short {
        var sum = initialSum
        var i = tcpOffset
        val end = tcpOffset + tcpLen
        while (i < end - 1) {
            sum += (((tcpPacket[i].toInt() and 0xFF) shl 8) or (tcpPacket[i + 1].toInt() and 0xFF))
            i += 2
        }
        if (i < end) {
            sum += ((tcpPacket[i].toInt() and 0xFF) shl 8)
        }
        while ((sum ushr 16) > 0) {
            sum = (sum and 0xFFFF) + (sum ushr 16)
        }
        // RFC 793 / RFC 1624: 0x0000 is a valid TCP checksum (unlike UDP's 0xFFFF substitution)
        return (sum.inv() and 0xFFFF).toShort()
    }
}
