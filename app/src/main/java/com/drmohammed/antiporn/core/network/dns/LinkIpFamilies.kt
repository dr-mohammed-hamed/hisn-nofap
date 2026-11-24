package com.drmohammed.antiporn.core.network.dns

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.NetworkCapabilities
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

@Suppress("DEPRECATION")
fun resolveActivePhysicalLinkIpFamilies(context: Context): LinkIpFamilies {
    return try {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return LinkIpFamilies()
        for (net in cm.allNetworks) {
            val caps = cm.getNetworkCapabilities(net) ?: continue
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue
            if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) continue
            val props = cm.getLinkProperties(net) ?: continue
            return props.toLinkIpFamilies()
        }
        LinkIpFamilies()
    } catch (_: Throwable) {
        LinkIpFamilies()
    }
}
