package com.drmohammed.antiporn.core.network.dns

import android.os.ParcelFileDescriptor
import android.os.SystemClock
import com.drmohammed.antiporn.core.model.DevicePolicyBundle
import kotlinx.coroutines.*
import timber.log.Timber
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
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
        private const val MAX_IN_FLIGHT_QUERIES = 96
        private const val MAX_QUERY_AGE_MS = 3_500L

        private fun monotonicNowMs(): Long = try {
            SystemClock.elapsedRealtime()
        } catch (_: Throwable) {
            System.nanoTime() / 1_000_000L
        }
    }

    private val outStreamLock = Any()

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
                outStream = FileOutputStream(pfd.fileDescriptor)

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

                    if (version == 6) {
                        if (bytesRead >= 44 && (packetBuffer[6].toInt() and 0xFF) == 6) {
                            val dstPort = ((packetBuffer[42].toInt() and 0xFF) shl 8) or (packetBuffer[43].toInt() and 0xFF)
                            // Instant RST on 853 (DoT), 443 (trapped DoH IPs), and 53 (no local TCP DNS proxy -> fast UDP fallback)
                            if (dstPort == 853 || dstPort == 443 || dstPort == 53) {
                                val ipv6Rst = DnsPacketParser.buildIpv6TcpRstResponse(packetBuffer, bytesRead)
                                if (ipv6Rst != null) {
                                    Timber.tag(TAG).i("Instant IPv6 TCP RST sent for TCP probe on port $dstPort -> fast fallback")
                                    writeToTun(outStream, ipv6Rst)
                                }
                                continue
                            }
                        }
                        val ipv6Refused = DnsPacketParser.buildIpv6RefusedResponse(packetBuffer, bytesRead)
                        if (ipv6Refused != null) {
                            Timber.tag(TAG).i("Instant REFUSED sent for IPv6 DNS query -> forces fast IPv4 fallback")
                            writeToTun(outStream, ipv6Refused)
                        }
                        continue
                    }

                    // Intercept IPv4 TCP probes on 853 (Opportunistic DoT), 443 (trapped DoH resolver IPs),
                    // and 53 (no local TCP DNS proxy, so instant RST forces immediate UDP:53 retry without SYN hang)
                    if (version == 4 && bytesRead >= 20) {
                        val protocol = packetBuffer[9].toInt() and 0xFF
                        if (protocol == 6) { // TCP
                            val ihl = (packetBuffer[0].toInt() and 0x0F) * 4
                            if (ihl >= 20 && bytesRead >= ihl + 4) {
                                val dstPort = ((packetBuffer[ihl + 2].toInt() and 0xFF) shl 8) or (packetBuffer[ihl + 3].toInt() and 0xFF)
                                if (dstPort == 853 || dstPort == 443 || dstPort == 53) {
                                    val tcpRst = DnsPacketParser.buildTcpRstResponse(packetBuffer, bytesRead)
                                    if (tcpRst != null) {
                                        Timber.tag(TAG).i("Instant TCP RST sent for TCP probe on port $dstPort -> fast fallback")
                                        writeToTun(outStream, tcpRst)
                                    }
                                    continue
                                }
                            }
                        }
                    }

                    val parsedQuery = DnsPacketParser.parseDnsQuery(packetBuffer, bytesRead)
                    if (parsedQuery == null) {
                        Timber.tag(TAG).d("Non-DNS or unparsed packet received on TUN (version=$version, len=$bytesRead)")
                        continue
                    }
                    Timber.tag(TAG).i("DNS Query: ${parsedQuery.qName} (type=${parsedQuery.qType}) from ${parsedQuery.srcPort}")
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
                                Timber.tag(TAG).w("Dropped excess DNS query for ${parsedQuery.qName} (inFlight=$currentInFlight)")
                                continue
                            }
                            val activeOut = outStream
                            val enqueuedAtMs = monotonicNowMs()
                            forwarderScope.launch {
                                try {
                                    val replyPayload = forwarder.resolve(
                                        qName = parsedQuery.qName,
                                        qType = parsedQuery.qType,
                                        transactionId = parsedQuery.transactionId,
                                        rawDnsPayload = parsedQuery.rawDnsPayload,
                                        enqueuedAtMs = enqueuedAtMs,
                                        maxQueryAgeMs = MAX_QUERY_AGE_MS
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
                                    Timber.tag(TAG).w("Upstream DNS query failed for ${parsedQuery.qName}: ${e.message}")
                                } finally {
                                    inFlightQueries.decrementAndGet()
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Timber.tag(TAG).d("DNS Filtering Loop terminated: ${e.message}")
            } finally {
                forwarderScope.coroutineContext.cancelChildren()
                try { inStream?.close() } catch (_: Exception) {}
                try { outStream?.close() } catch (_: Exception) {}
            }
        }
    }

    private fun writeToTun(outStream: FileOutputStream, packet: ByteArray) {
        synchronized(outStreamLock) {
            try {
                outStream.write(packet)
                Timber.tag(TAG).d("Sent response packet to TUN (${packet.size} bytes)")
            } catch (e: IOException) {
                Timber.tag(TAG).w(e, "Failed writing DNS response packet to TUN")
            }
        }
    }
}
