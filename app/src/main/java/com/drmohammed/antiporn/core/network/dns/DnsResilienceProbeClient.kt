package com.drmohammed.antiporn.core.network.dns

import android.net.Network
import com.drmohammed.antiporn.core.model.DevicePolicyBundle
import timber.log.Timber
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * Client for resolving bootstrap DNS IPs and testing TLS Private DNS (DoT Port 853) connectivity.
 */
object DnsResilienceProbeClient {

    private const val TAG = "AntiPorn_DnsProbe"
    const val DOT_PORT = 853
    const val DOT_PROBE_TIMEOUT_MS = 2_000

    val PRESET_BOOTSTRAP_IPV4 = mapOf(
        "family.cloudflare-dns.com" to listOf("1.1.1.3", "1.0.0.3"),
        "family.adguard-dns.com" to listOf("94.140.14.15", "94.140.15.16"),
        "family-filter-dns.cleanbrowsing.org" to listOf("185.228.168.168", "185.228.169.168"),
        "family.controld.com" to listOf("76.76.2.3", "76.76.10.3"),
        DevicePolicyBundle.CONTROLD_FAMILY_DNS_HOST to listOf("76.76.2.11", "76.76.10.11"),
        DevicePolicyBundle.CONTROLD_NO_ADS_PORN_DNS_HOST to listOf("76.76.2.11", "76.76.10.11")
    )

    val PRESET_BOOTSTRAP_IPV6 = mapOf(
        "family.cloudflare-dns.com" to listOf("2606:4700:4700::1113", "2606:4700:4700::1003"),
        "family.adguard-dns.com" to listOf("2a10:50c0::bad1:ff", "2a10:50c0::bad2:ff"),
        "family-filter-dns.cleanbrowsing.org" to listOf("2a0d:2a00:1::", "2a0d:2a00:2::"),
        "family.controld.com" to listOf("2606:1a40::3", "2606:1a40:1::3"),
        DevicePolicyBundle.CONTROLD_FAMILY_DNS_HOST to listOf("2606:1a40::11", "2606:1a40:1::11"),
        DevicePolicyBundle.CONTROLD_NO_ADS_PORN_DNS_HOST to listOf("2606:1a40::11", "2606:1a40:1::11")
    )

    /**
     * Resolves high-availability Bootstrap IP addresses for DoH/DoT endpoints without OS DNS dependency.
     */
    fun resolveBootstrapCandidates(
        host: String,
        families: LinkIpFamilies = LinkIpFamilies(hasIpv4 = true, hasIpv6 = false)
    ): List<String> {
        val clean = host.lowercase().trim().trimEnd('.')
        val v4Preset = PRESET_BOOTSTRAP_IPV4[clean]
        val v6Preset = PRESET_BOOTSTRAP_IPV6[clean]

        val (v4List, v6List) = if (v4Preset != null || v6Preset != null) {
            (v4Preset ?: emptyList()) to (v6Preset ?: emptyList())
        } else {
            when {
                clean.endsWith(".dns.nextdns.io") -> {
                    listOf("45.90.28.0", "45.90.30.0") to listOf("2a07:a8c0::", "2a07:a8c1::")
                }
                clean.endsWith(".freedns.controld.com") -> {
                    listOf("76.76.2.11", "76.76.10.11") to listOf("2606:1a40::11", "2606:1a40:1::11")
                }
                clean.endsWith(".dns.controld.com") -> {
                    listOf("76.76.2.22", "76.76.10.22") to listOf("2606:1a40::22", "2606:1a40:1::22")
                }
                else -> emptyList<String>() to emptyList<String>()
            }
        }

        val candidates = mutableListOf<String>()
        if (families.hasIpv4) {
            candidates.addAll(v4List)
        }
        if (families.hasIpv6) {
            candidates.addAll(v6List)
        }
        if (!families.hasIpv4 && !families.hasIpv6) {
            candidates.addAll(v4List)
        }
        return candidates.distinct()
    }

    /**
     * Tests TLS DoT (Port 853) connectivity directly on the specified physical network.
     */
    fun testDotHostOnNetwork(
        network: Network,
        host: String,
        families: LinkIpFamilies = LinkIpFamilies(hasIpv4 = true, hasIpv6 = false),
        dotProbeOverride: ((Network, String) -> Boolean)? = null
    ): Boolean {
        dotProbeOverride?.let { return it(network, host) }
        var rawSocket: Socket? = null
        var sslSocket: SSLSocket? = null
        return try {
            val resolvedAddr = try {
                val allAddrs = network.getAllByName(host)?.toList().orEmpty()
                if (families.hasIpv4) {
                    allAddrs.firstOrNull { it is java.net.Inet4Address } ?: allAddrs.firstOrNull()
                } else if (families.hasIpv6) {
                    allAddrs.firstOrNull { it is java.net.Inet6Address } ?: allAddrs.firstOrNull()
                } else {
                    allAddrs.firstOrNull()
                } ?: network.getByName(host)
            } catch (e: Exception) {
                val candidates = resolveBootstrapCandidates(host, families)
                candidates.firstNotNullOfOrNull { ipStr ->
                    try {
                        InetAddress.getByName(ipStr)
                    } catch (_: Exception) {
                        null
                    }
                } ?: run {
                    Timber.tag(TAG).d("Failed resolving $host on network: ${e.message}")
                    return false
                }
            }

            rawSocket = network.socketFactory.createSocket()
            rawSocket.soTimeout = DOT_PROBE_TIMEOUT_MS
            rawSocket.connect(InetSocketAddress(resolvedAddr, DOT_PORT), DOT_PROBE_TIMEOUT_MS)

            val factory = SSLSocketFactory.getDefault() as? SSLSocketFactory
                ?: throw IOException("Default SSLSocketFactory unavailable")
            val wrapped = factory.createSocket(rawSocket, host, DOT_PORT, true) as? SSLSocket
                ?: throw IOException("Failed to wrap socket with SSLSocket")
            sslSocket = wrapped
            val sslParams = wrapped.sslParameters
            sslParams.serverNames = listOf(SNIHostName(host))
            sslParams.endpointIdentificationAlgorithm = "HTTPS"
            wrapped.sslParameters = sslParams
            wrapped.soTimeout = DOT_PROBE_TIMEOUT_MS
            wrapped.startHandshake()

            val session = wrapped.session
            val hostnameOk = HttpsURLConnection.getDefaultHostnameVerifier().verify(host, session)
            hostnameOk
        } catch (e: Exception) {
            Timber.tag(TAG).d("DoT probe failed for $host on network: ${e.message}")
            false
        } finally {
            try {
                sslSocket?.close()
            } catch (_: Exception) {}
            try {
                rawSocket?.close()
            } catch (_: Exception) {}
        }
    }
}
