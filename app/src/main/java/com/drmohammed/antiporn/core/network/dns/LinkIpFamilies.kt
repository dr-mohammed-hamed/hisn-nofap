package com.drmohammed.antiporn.core.network.dns

import android.content.Context
import android.net.LinkProperties
import com.drmohammed.antiporn.core.network.PhysicalNetworkResolver
import java.net.Inet4Address
import java.net.Inet6Address

/**
 * Encapsulates the IP address families (IPv4, IPv6) available or desired on a physical network link.
 */
data class LinkIpFamilies(
    val hasIpv4: Boolean = true,
    val hasIpv6: Boolean = false
)

fun LinkProperties?.toLinkIpFamilies(): LinkIpFamilies {
    val addrs = try {
        this?.linkAddresses?.mapNotNull { it.address }
    } catch (_: Throwable) {
        null
    } ?: return LinkIpFamilies()
    val hasV4 = addrs.any { it is Inet4Address && !it.isLoopbackAddress && !it.isLinkLocalAddress }
    val hasV6 = addrs.any { it is Inet6Address && !it.isLoopbackAddress && !it.isLinkLocalAddress }
    return if (!hasV4 && !hasV6) LinkIpFamilies() else LinkIpFamilies(hasIpv4 = hasV4, hasIpv6 = hasV6)
}

fun resolveActivePhysicalLinkIpFamilies(context: Context): LinkIpFamilies =
    PhysicalNetworkResolver.detectActivePhysicalLinkFamilies(context)
