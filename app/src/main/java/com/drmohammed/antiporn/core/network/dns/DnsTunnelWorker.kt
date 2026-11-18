package com.drmohammed.antiporn.core.network.dns

import android.os.ParcelFileDescriptor
import android.os.SystemClock
import com.drmohammed.antiporn.core.model.DevicePolicyBundle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicInteger

/**
 * Worker coroutine loop that handles reading, policy evaluation, local synthesis,
 * and upstream forwarding for DNS packets traversing the local TUN interface.
 */
class DnsTunnelWorker(
    private val forwarder: DnsUpstreamForwarder,
    private val policyProvider: () -> DevicePolicyBundle,
    private val customBlockedProvider: () -> Set<String>,
    private val isRunningProvider: () -> Boolean
) {

    companion object {
        private const val TAG = "AntiPorn_DnsWorker"
        private const val PACKET_BUFFER_SIZE = 32767
        internal const val MAX_IN_FLIGHT_QUERIES = 96
        private const val MAX_QUERY_AGE_MS = 3_500L

        private fun monotonicNowMs(): Long = try {
            SystemClock.elapsedRealtime()
        } catch (_: RuntimeException) {
            System.nanoTime() / 1_000_000L
        }

        /**
         * Inspects an incoming IPv4 or IPv6 packet for TCP probes on ports 853 (DoT),
         * 443 (trapped DoH IPs), or 53 (TCP DNS), including IPv6 extension headers.
         * Returns a ready-to-write TCP RST packet if intercepted, or null otherwise.
         */
        internal fun tryBuildTcpRstForProbe(packet: ByteArray, length: Int): ByteArray? {
            if (length < 20 || length > packet.size) return null
            val version = (packet[0].toInt() and 0xFF) ushr 4
            if (version == 6 && length >= 44) {
                val upper = Ipv6DnsPacketCodec.resolveUpperLayer(ByteBuffer.wrap(packet, 0, length), length)
                    ?: return null
                if (upper.protocol == 6 && upper.headerOffset + 4 <= upper.actualIpEnd) {
                    val dstPort = ((packet[upper.headerOffset + 2].toInt() and 0xFF) shl 8) or
                        (packet[upper.headerOffset + 3].toInt() and 0xFF)
                    if (dstPort == 853 || dstPort == 443 || dstPort == 53) {
                        return DnsPacketParser.buildIpv6TcpRstResponse(packet, length)
                    }
                }
            } else if (version == 4) {
                val protocol = packet[9].toInt() and 0xFF
                if (protocol == 6) {
                    val ihl = (packet[0].toInt() and 0x0F) * 4
                    if (ihl >= 20 && length >= ihl + 4) {
                        val dstPort = ((packet[ihl + 2].toInt() and 0xFF) shl 8) or
                            (packet[ihl + 3].toInt() and 0xFF)
                        if (dstPort == 853 || dstPort == 443 || dstPort == 53) {
                            return DnsPacketParser.buildTcpRstResponse(packet, length)
                        }
                    }
                }
            }
            return null
        }

        internal fun selectMaxResponsePayloadBytes(ipVersion: Int): Int =
            if (ipVersion == 6) {
                DnsWireValidator.MAX_IPV6_UDP_DNS_PAYLOAD
            } else {
                DnsWireValidator.MAX_IPV4_UDP_DNS_PAYLOAD
            }
    }

    private val outStreamLock = Any()
    @Volatile
    private var activeInStream: FileInputStream? = null
    @Volatile
    private var outStreamClosed = false

    fun closeActiveStreams() {
        val inRef = activeInStream
        activeInStream = null
        try {
            inRef?.close()
        } catch (_: IOException) {
        }
    }

    fun start(
        scope: CoroutineScope,
        forwarderScope: CoroutineScope,
        pfd: ParcelFileDescriptor
    ): Job {
        return scope.launch(Dispatchers.IO) {
            val inFlightQueries = AtomicInteger(0)
            var inStream: FileInputStream? = null
            var outStream: FileOutputStream? = null
            val packetBuffer = ByteArray(PACKET_BUFFER_SIZE)

            Timber.tag(TAG).i("DNS Filtering Loop active on TUN interface (24/7 Defense)")

            try {
                inStream = FileInputStream(pfd.fileDescriptor)
                activeInStream = inStream
                outStream = FileOutputStream(pfd.fileDescriptor)
                synchronized(outStreamLock) {
                    outStreamClosed = false
                }

                while (isActive && isRunningProvider()) {
                    val bytesRead = try {
                        inStream.read(packetBuffer)
                    } catch (e: IOException) {
                        Timber.tag(TAG).d("TUN read IOException: ${e.message}")
                        break // Interface closed or handover initiated
                    }
                    if (bytesRead < 0) {
                        Timber.tag(TAG).d("TUN interface EOF reached; breaking read loop")
                        break
                    }
                    if (bytesRead == 0) continue

                    val version = (packetBuffer[0].toInt() and 0xFF) ushr 4
                    Timber.tag(TAG).d("TUN packet read: $bytesRead bytes, ipVersion=$version")

                    val tcpRst = tryBuildTcpRstForProbe(packetBuffer, bytesRead)
                    if (tcpRst != null) {
                        Timber.tag(TAG).i("Instant IPv$version TCP RST sent for TCP probe -> fast fallback")
                        writeToTun(outStream, tcpRst)
                        continue
                    }

                    val parsedQuery = DnsPacketParser.parseDnsQuery(packetBuffer, bytesRead)
                    if (parsedQuery == null) {
                        Timber.tag(TAG).d("Non-DNS or unparsed packet received on TUN (version=$version, len=$bytesRead)")
                        continue
                    }
                    Timber.tag(TAG).d("DNS Query received (ipVersion=${parsedQuery.ipVersion}, type=${parsedQuery.qType}, srcPort=${parsedQuery.srcPort})")
                    val policy = policyProvider()
                    val customBlocked = customBlockedProvider()
                    val action = DnsPolicyEvaluator.evaluate(parsedQuery, policy, customBlocked)

                    when (action) {
                        is DnsPolicyEvaluator.PolicyAction.BlockPorn,
                        is DnsPolicyEvaluator.PolicyAction.BlockAds,
                        is DnsPolicyEvaluator.PolicyAction.BlockTelegramWeb,
                        is DnsPolicyEvaluator.PolicyAction.BlockCustom,
                        is DnsPolicyEvaluator.PolicyAction.BlockDohBypass -> {
                            val nxResponse = DnsPacketParser.buildNxDomainResponse(parsedQuery)
                            writeToTun(outStream, nxResponse)
                        }
                        is DnsPolicyEvaluator.PolicyAction.SynthesizeYouTubeRestricted -> {
                            val vipResponse = DnsPacketParser.buildSafeSearchVipResponse(parsedQuery)
                            writeToTun(outStream, vipResponse)
                        }
                        is DnsPolicyEvaluator.PolicyAction.Pass -> {
                            val currentInFlight = inFlightQueries.incrementAndGet()
                            if (currentInFlight > MAX_IN_FLIGHT_QUERIES) {
                                inFlightQueries.decrementAndGet()
                                Timber.tag(TAG).w("Dropped excess DNS query (type=${parsedQuery.qType}, inFlight=$currentInFlight)")
                                continue
                            }
                            val activeOut = outStream
                            val enqueuedAtMs = monotonicNowMs()
                            val maxPayloadBytes = selectMaxResponsePayloadBytes(parsedQuery.ipVersion)
                            val workerJob = forwarderScope.launch {
                                try {
                                    val replyPayload = forwarder.resolve(
                                        qName = parsedQuery.qName,
                                        qType = parsedQuery.qType,
                                        transactionId = parsedQuery.transactionId,
                                        rawDnsPayload = parsedQuery.rawDnsPayload,
                                        enqueuedAtMs = enqueuedAtMs,
                                        maxQueryAgeMs = MAX_QUERY_AGE_MS,
                                        maxResponsePayloadBytes = maxPayloadBytes
                                    )
                                    if (replyPayload != null && isActive && isRunningProvider()) {
                                        val clientPacket = DnsPacketParser.wrapUpstreamDnsResponse(
                                            clientIp = parsedQuery.srcIp,
                                            serverIp = parsedQuery.dstIp,
                                            clientPort = parsedQuery.srcPort,
                                            serverPort = parsedQuery.dstPort,
                                            dnsResponsePayload = replyPayload
                                        )
                                        writeToTun(activeOut, clientPacket)
                                    }
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    Timber.tag(TAG).w("Upstream DNS query failed (type=${parsedQuery.qType}): ${e.message}")
                                }
                            }
                            workerJob.invokeOnCompletion { inFlightQueries.decrementAndGet() }
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.tag(TAG).d(e, "DNS Filtering Loop terminated")
            } finally {
                forwarderScope.coroutineContext.cancelChildren()
                activeInStream = null
                synchronized(outStreamLock) {
                    outStreamClosed = true
                    try {
                        outStream?.close()
                    } catch (e: IOException) {
                        Timber.tag(TAG).d(e, "TUN outStream close failed")
                    }
                }
                try {
                    inStream?.close()
                } catch (e: IOException) {
                    Timber.tag(TAG).d(e, "TUN inStream close failed")
                }
            }
        }
    }

    private fun writeToTun(outStream: FileOutputStream, packet: ByteArray) {
        synchronized(outStreamLock) {
            if (outStreamClosed) return
            try {
                outStream.write(packet)
                Timber.tag(TAG).d("Sent response packet to TUN (${packet.size} bytes)")
            } catch (e: IOException) {
                Timber.tag(TAG).w(e, "Failed writing DNS response packet to TUN")
            }
        }
    }
}
