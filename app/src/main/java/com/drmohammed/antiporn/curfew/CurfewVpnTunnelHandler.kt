package com.drmohammed.antiporn.curfew

import android.content.Context
import android.net.VpnService
import android.os.Build
import com.drmohammed.antiporn.R
import com.drmohammed.antiporn.core.engine.ProtectionTransitionGuard
import com.drmohammed.antiporn.curfew.CurfewBlackholeVpnService.VpnMode
import com.drmohammed.antiporn.telemetry.SafeDpmGateway
import timber.log.Timber

/**
 * Configures TUN builder routes, bypassed application exclusions, and Always-On VPN
 * DevicePolicyManager bindings for [CurfewBlackholeVpnService].
 */
internal object CurfewVpnTunnelHandler {

    private const val TAG = "AntiPorn_UnifiedVpn"

    data class TunnelConfigResult(
        val builder: VpnService.Builder,
        val appliedBypassedPackages: Set<String>?
    )

    fun prepareTunnelConfig(
        service: CurfewBlackholeVpnService,
        appContext: Context,
        targetMode: VpnMode,
        lastBypassedPackages: Set<String>?
    ): TunnelConfigResult {
        val sessionName = if (targetMode == VpnMode.CURFEW_BLACKHOLE) {
            service.getString(R.string.curfew_vpn_session_name)
        } else {
            service.getString(R.string.curfew_vpn_dns_filter_session)
        }

        val builder = service.createTunnelBuilder()
            .setSession(sessionName)
            .addAddress("10.255.255.1", 30)
            .setMtu(1500)
            .setBlocking(true)

        if (targetMode == VpnMode.DNS_FILTERING_ONLY) {
            configureDnsSinkholeRoutes(builder)
        } else {
            builder.addDnsServer("10.255.255.1")
            builder.addAddress("fd00::1", 126)
            builder.addDnsServer("fd00::1")
            builder.addRoute("0.0.0.0", 0)
            builder.addRoute("::", 0)
        }

        builder.addDisallowedApplication(service.packageName)

        var appliedBypassedPackages: Set<String>? = null
        if (targetMode == VpnMode.CURFEW_BLACKHOLE) {
            val bypassedPackages = CurfewConfig.getVpnBypassedPackages(appContext)
            appliedBypassedPackages = bypassedPackages
            syncAlwaysOnBlackhole(appContext, service.packageName, bypassedPackages, lastBypassedPackages)
            for (pkg in bypassedPackages) {
                if (pkg == service.packageName) continue
                try {
                    builder.addDisallowedApplication(pkg)
                } catch (e: Exception) {
                    Timber.tag(TAG).w(e, "Failed to bypass package $pkg from VPN")
                }
            }
        } else {
            syncAlwaysOnDnsMode(appContext, targetMode)
        }

        return TunnelConfigResult(builder, appliedBypassedPackages)
    }

    private fun configureDnsSinkholeRoutes(builder: VpnService.Builder) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setMetered(false)
        }
        builder.addDnsServer("10.255.255.2")
        builder.addRoute("10.255.255.2", 32)
        // Sinkhole hardcoded public IPv4 DNS/DoH resolvers (Hisn is excluded via addDisallowedApplication + protect())
        builder.addRoute("8.8.8.8", 32)
        builder.addRoute("8.8.4.4", 32)
        builder.addRoute("1.1.1.1", 32)
        builder.addRoute("1.0.0.1", 32)
        builder.addRoute("9.9.9.9", 32)
        builder.addRoute("149.112.112.112", 32)
        // OpenDNS Unfiltered
        builder.addRoute("208.67.222.222", 32)
        builder.addRoute("208.67.220.220", 32)
        // AdGuard Unfiltered
        builder.addRoute("94.140.14.14", 32)
        builder.addRoute("94.140.15.15", 32)
        builder.addRoute("94.140.14.140", 32)
        builder.addRoute("94.140.14.141", 32)
        // Control D Unfiltered
        builder.addRoute("76.76.2.0", 32)
        builder.addRoute("76.76.10.0", 32)
        // Quad9 Alt
        builder.addRoute("9.9.9.10", 32)
        builder.addRoute("9.9.9.11", 32)
        builder.addRoute("149.112.112.9", 32)
        builder.addRoute("149.112.112.10", 32)
        builder.addRoute("149.112.112.11", 32)
        // Control D Unfiltered Alt
        builder.addRoute("76.76.2.1", 32)
        builder.addRoute("76.76.10.1", 32)
        builder.addRoute("76.76.2.2", 32)
        builder.addRoute("76.76.10.2", 32)
        // DNS.SB
        builder.addRoute("185.222.222.222", 32)
        builder.addRoute("45.11.45.11", 32)
        // Mullvad Unfiltered
        builder.addRoute("194.242.2.2", 32)
        // Yandex Unfiltered
        builder.addRoute("77.88.8.8", 32)
        builder.addRoute("77.88.8.1", 32)
        // Level3
        builder.addRoute("4.2.2.1", 32)
        builder.addRoute("4.2.2.2", 32)

        builder.addAddress("fd00::1", 126)
        builder.addDnsServer("fd00::2")
        builder.addRoute("fd00::2", 128)
        builder.addRoute("2001:4860:4860::8888", 128)
        builder.addRoute("2001:4860:4860::8844", 128)
        builder.addRoute("2606:4700:4700::1111", 128)
        builder.addRoute("2606:4700:4700::1001", 128)
        // Quad9 IPv6
        builder.addRoute("2620:fe::fe", 128)
        builder.addRoute("2620:fe::9", 128)
        builder.addRoute("2620:fe::fe:9", 128)
        builder.addRoute("2620:fe::10", 128)
        builder.addRoute("2620:fe::11", 128)
        // OpenDNS Unfiltered IPv6
        builder.addRoute("2620:119:35::35", 128)
        builder.addRoute("2620:119:53::53", 128)
        // AdGuard Unfiltered IPv6
        builder.addRoute("2a10:50c0::ad1:ff", 128)
        builder.addRoute("2a10:50c0::ad2:ff", 128)
        builder.addRoute("2a10:50c0::1:ff", 128)
        builder.addRoute("2a10:50c0::2:ff", 128)
        // Control D Unfiltered IPv6
        builder.addRoute("2606:1a40::", 128)
        builder.addRoute("2606:1a40:1::", 128)
        builder.addRoute("2606:1a40::1", 128)
        builder.addRoute("2606:1a40::2", 128)
        // DNS.SB IPv6
        builder.addRoute("2a09::", 128)
        builder.addRoute("2a11::", 128)
        // Mullvad Unfiltered IPv6
        builder.addRoute("2a07:e340::2", 128)
        // Yandex Unfiltered IPv6
        builder.addRoute("2a02:6b8::feed:0ff", 128)
        // Note: Cloudflare Family IPv6 (2606:4700:4700::1113 / 1003) is intentionally omitted
        // from TUN sinkholing to allow Android's Private DNS probe to validate connectivity
        // cleanly when Private DNS remains pointing to family.cloudflare-dns.com.
    }

    private fun syncAlwaysOnBlackhole(
        appContext: Context,
        selfPackage: String,
        bypassedPackages: Set<String>,
        lastBypassedPackages: Set<String>?
    ) {
        if (ProtectionTransitionGuard.isTeardownInProgress || !SafeDpmGateway.isDeviceOwner(appContext)) return
        try {
            val alwaysOnPkg = SafeDpmGateway.getAlwaysOnVpnPackageBlocking(appContext, recordSuccess = false).getOrNull()
            val isLockdown = SafeDpmGateway.isAlwaysOnVpnLockdownEnabledBlocking(appContext, recordSuccess = false).getOrNull() == true
            if (alwaysOnPkg != selfPackage || !isLockdown || lastBypassedPackages != bypassedPackages) {
                SafeDpmGateway.setAlwaysOnVpnPackageBlocking(
                    context = appContext,
                    vpnPackage = selfPackage,
                    lockdown = true,
                    bypassedPackages = bypassedPackages
                )
            }
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Could not update Always-On VPN lockdown bypassed packages")
        }
    }

    private fun syncAlwaysOnDnsMode(appContext: Context, targetMode: VpnMode) {
        if (ProtectionTransitionGuard.isTeardownInProgress || !SafeDpmGateway.isDeviceOwner(appContext)) return
        try {
            val alwaysOnPkg = SafeDpmGateway.getAlwaysOnVpnPackageBlocking(appContext, recordSuccess = false).getOrNull()
            val isLockdown = SafeDpmGateway.isAlwaysOnVpnLockdownEnabledBlocking(appContext, recordSuccess = false).getOrNull() == true
            val targetAlwaysOnPkg = CurfewVpnDnsBridge.resolveTargetAlwaysOnPackage(appContext, targetMode)
            if (alwaysOnPkg != targetAlwaysOnPkg || isLockdown) {
                SafeDpmGateway.setAlwaysOnVpnPackageBlocking(
                    context = appContext,
                    vpnPackage = targetAlwaysOnPkg,
                    lockdown = false
                )
                Timber.tag(TAG).i("Always-On VPN configured to target=$targetAlwaysOnPkg with lockdown=false for daytime DNS mode")
            }
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Could not configure Always-On VPN for daytime DNS mode")
        }
    }
}
