package com.drmohammed.antiporn.core.network.dns

import java.net.InetAddress

/**
 * IP address classification and local/captive-portal domain allowlist rules
 * for DNS leak prevention and anti-SSRF/anti-rebinding protection.
 */
internal object DnsIpPolicy {

    val EXCLUDED_DNS_IPS: Set<String> = setOf(
        "10.255.255.1",
        "10.255.255.2",
        "fd00::1",
        "fd00::2",
        "127.0.0.1",
        "::1",
        "0.0.0.0"
    )

    private val CAPTIVE_PORTAL_PROBE_DOMAINS: Set<String> = setOf(
        "connectivitycheck.gstatic.com",
        "connectivitycheck.android.com",
        "clients3.google.com",
        "clients4.google.com",
        "clients.l.google.com",
        "captive.apple.com",
        "www.msftconnecttest.com",
        "www.msftncsi.com",
        "detectportal.firefox.com",
        "connectivitycheck.platform.hicloud.com",
        "connectivitycheck.cbg-app.huawei.com",
        "connect.rom.miui.com",
        "wifi.vivo.com.cn",
        "conn1.oppomobile.com",
        "conn2.oppomobile.com",
        "connectivitycheck.samsung.com",
        "d2uzsrnmmf6tds.cloudfront.net"
    )

    internal fun isExcludedDnsIp(ip: String): Boolean {
        val normalized = DotUpstreamClient.sanitizeHost(ip)
        if (EXCLUDED_DNS_IPS.contains(normalized)) return true
        val parsedV4 = parseIpv4ToInetAddress(normalized)
        if (parsedV4 != null) {
            return isDisallowedPhysicalDnsIp(parsedV4)
        }
        if (normalized.contains(':')) {
            val parsedV6 = try {
                InetAddress.getByName(normalized)
            } catch (_: Exception) {
                null
            }
            if (parsedV6 != null) {
                return isDisallowedPhysicalDnsIp(parsedV6)
            }
        }
        return false
    }

    /**
     * Rejects loopback, unspecified, link-local, RFC 1918 site-local, ULA, CGNAT,
     * multicast/reserved/broadcast, and local TUN addresses for public DoT / custom upstreams.
     */
    internal fun isNonPublicOrReservedIp(addr: InetAddress): Boolean {
        if (isDisallowedPhysicalDnsIp(addr) || addr.isSiteLocalAddress) return true
        val b = addr.address
        if (b.size == 4) {
            val b0 = b[0].toInt() and 0xFF
            val b1 = b[1].toInt() and 0xFF
            if (b0 == 100 && b1 in 64..127) return true // RFC 6598 CGNAT 100.64.0.0/10
            if (b0 == 192 && b1 == 0 && (b[2].toInt() and 0xFF) == 2) return true // TEST-NET-1
            if (b0 == 198 && (b1 == 18 || b1 == 19)) return true // Benchmark 198.18.0.0/15
        } else if (b.size == 16) {
            val b0 = b[0].toInt() and 0xFF
            if ((b0 and 0xFE) == 0xFC) return true // RFC 4193 IPv6 ULA fc00::/7
        }
        return false
    }

    /**
     * Rejects loopback, unspecified, link-local, multicast/reserved/broadcast, and our own VPN TUN IPs,
     * while permitting RFC 1918 router IPs (e.g. 192.168.1.1) for Tier 3 physical router DNS.
     */
    internal fun isDisallowedPhysicalDnsIp(addr: InetAddress): Boolean {
        if (addr.isAnyLocalAddress || addr.isLoopbackAddress ||
            addr.isLinkLocalAddress || addr.isMulticastAddress
        ) return true
        val b = addr.address
        if (b.size == 4) {
            val b0 = b[0].toInt() and 0xFF
            val b1 = b[1].toInt() and 0xFF
            val b2 = b[2].toInt() and 0xFF
            val b3 = b[3].toInt() and 0xFF
            if (b0 == 0 || b0 == 127 || b0 >= 224) return true // 0/8, 127/8, multicast/reserved/broadcast
            if (b0 == 10 && b1 == 255 && b2 == 255 && (b3 == 1 || b3 == 2)) return true
        } else if (b.size == 16) {
            val isFd00Prefix = (b[0].toInt() and 0xFF) == 0xFD &&
                (1..14).all { b[it] == 0.toByte() } &&
                (b[15] == 1.toByte() || b[15] == 2.toByte())
            if (isFd00Prefix) return true
        }
        val hostStr = addr.hostAddress ?: ""
        return EXCLUDED_DNS_IPS.contains(DotUpstreamClient.sanitizeHost(hostStr))
    }

    internal fun isKnownCaptivePortalProbeDomain(qName: String): Boolean {
        val clean = qName.trim().lowercase().trimEnd('.')
        if (clean.isBlank()) return false
        return CAPTIVE_PORTAL_PROBE_DOMAINS.contains(clean)
    }

    internal fun isLocalIntranetDomain(qName: String): Boolean {
        val clean = qName.trim().lowercase().trimEnd('.')
        if (clean.isBlank()) return false
        if (!clean.contains('.')) return true // router, gateway, localhost
        return clean.endsWith(".local") || clean.endsWith(".lan") || clean.endsWith(".home.arpa")
    }

    /**
     * Parses a numeric IPv4 address into an InetAddress with 0ms overhead
     * and ZERO network/DNS resolution, rejecting signed octets and malformed inputs.
     */
    fun parseIpv4ToInetAddress(rawText: String): InetAddress? {
        val clean = rawText.trim().substringBefore(':').trim()
        val parts = clean.split('.')
        if (parts.size != 4) return null
        val bytes = ByteArray(4)
        for (i in 0..3) {
            val part = parts[i]
            if (part.isEmpty() || part.length > 3 || !part.all { it in '0'..'9' }) return null
            val intVal = part.toIntOrNull() ?: return null
            if (intVal !in 0..255) return null
            bytes[i] = intVal.toByte()
        }
        return try {
            InetAddress.getByAddress(bytes)
        } catch (_: Exception) {
            null
        }
    }
}
