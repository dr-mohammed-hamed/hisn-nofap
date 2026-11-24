package com.drmohammed.antiporn.core.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import com.drmohammed.antiporn.core.network.dns.LinkIpFamilies
import timber.log.Timber
import java.net.Inet4Address
import java.net.Inet6Address

/**
 * Shared physical (non-VPN) network inspection helper used across DNS resilience,
 * DoH/DoT forwarding, watchdog drift evaluation, and Black Box telemetry.
 */
internal object PhysicalNetworkResolver {

    private const val TAG = "AntiPorn_PhysNet"

    data class PhysicalNetworkState(
        val network: Network?,
        val transport: String?,
        val isValidated: Boolean
    )

    fun resolveTransportLabel(caps: NetworkCapabilities?): String? {
        if (caps == null) return null
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WIFI"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "CELLULAR"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ETHERNET"
            else -> null
        }
    }

    @Suppress("DEPRECATION")
    fun getAllPhysicalInternetNetworks(cm: ConnectivityManager): List<Network> {
        return try {
            cm.allNetworks.filter { net ->
                val caps = cm.getNetworkCapabilities(net)
                caps != null &&
                    !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                    caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            }
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Failed enumerating physical internet networks")
            emptyList()
        }
    }

    fun resolveActivePhysicalNetwork(context: Context): Network? {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return null
            resolveActivePhysicalNetwork(cm)
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Failed resolving active physical network")
            null
        }
    }

    fun resolveActivePhysicalNetwork(cm: ConnectivityManager): Network? {
        val activeNet = cm.activeNetwork
        if (activeNet != null) {
            val caps = cm.getNetworkCapabilities(activeNet)
            if (caps != null && !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                return activeNet
            }
        }
        val physicalNets = getAllPhysicalInternetNetworks(cm)
        return physicalNets.firstOrNull { net ->
            cm.getNetworkCapabilities(net)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
        } ?: physicalNets.firstOrNull()
    }

    fun detectLinkFamilies(cm: ConnectivityManager?, network: Network?): LinkIpFamilies {
        if (cm == null || network == null) return LinkIpFamilies(hasIpv4 = true, hasIpv6 = false)
        return try {
            val lp = cm.getLinkProperties(network)
                ?: return LinkIpFamilies(hasIpv4 = true, hasIpv6 = false)
            val hasV4 = lp.linkAddresses.any { linkAddr ->
                val addr = linkAddr.address
                addr is Inet4Address && !addr.isLoopbackAddress && !addr.isLinkLocalAddress
            }
            val hasV6 = lp.linkAddresses.any { linkAddr ->
                val addr = linkAddr.address
                addr is Inet6Address && !addr.isLoopbackAddress && !addr.isLinkLocalAddress
            }
            if (!hasV4 && !hasV6) LinkIpFamilies(hasIpv4 = true, hasIpv6 = false)
            else LinkIpFamilies(hasIpv4 = hasV4, hasIpv6 = hasV6)
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Failed detecting link IP families")
            LinkIpFamilies(hasIpv4 = true, hasIpv6 = false)
        }
    }

    fun detectActivePhysicalLinkFamilies(context: Context): LinkIpFamilies {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return LinkIpFamilies(hasIpv4 = true, hasIpv6 = false)
            val candidate = resolveActivePhysicalNetwork(cm)
                ?: return LinkIpFamilies(hasIpv4 = true, hasIpv6 = false)
            detectLinkFamilies(cm, candidate)
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Failed detecting active physical link IP families")
            LinkIpFamilies(hasIpv4 = true, hasIpv6 = false)
        }
    }

    fun resolveActivePhysicalNetworkState(context: Context): PhysicalNetworkState {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return PhysicalNetworkState(null, null, false)
            val candidate = resolveActivePhysicalNetwork(cm)
                ?: return PhysicalNetworkState(null, null, false)
            val caps = cm.getNetworkCapabilities(candidate)
                ?: return PhysicalNetworkState(candidate, null, false)
            val transport = resolveTransportLabel(caps) ?: "OTHER"
            val isValidated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            PhysicalNetworkState(candidate, transport, isValidated)
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Failed resolving active physical network state")
            PhysicalNetworkState(null, null, false)
        }
    }

    fun isDevicePhysicallyOffline(context: Context): Boolean {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return false
            val activeNet = cm.activeNetwork
            if (activeNet != null) {
                val activeCaps = cm.getNetworkCapabilities(activeNet)
                if (activeCaps != null && !activeCaps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                    return false
                }
            }
            getAllPhysicalInternetNetworks(cm).isEmpty()
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Failed checking physical offline state")
            false
        }
    }

    fun describeActivePhysicalNetwork(context: Context): String {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return "UNAVAILABLE"
            val target = resolveActivePhysicalNetwork(cm)
                ?: return "OFFLINE (no active physical internet network)"
            val caps = cm.getNetworkCapabilities(target) ?: return "net=$target, caps=null"
            val transport = com.drmohammed.antiporn.services.NetworkWatchdog.resolveTransportLabel(caps)
            val validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            val captive = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL)
            "transport=$transport, validated=$validated, captive=$captive, net=$target"
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Failed collecting active physical network summary")
            "READ_ERROR (${e.javaClass.simpleName})"
        }
    }
}
