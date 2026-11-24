package com.drmohammed.antiporn.curfew

import android.content.Context
import android.net.VpnService
import android.os.Build
import com.drmohammed.antiporn.data.repository.PolicyRepository
import timber.log.Timber

/**
 * Resolves Always-On VPN target packages, VPN consent readiness, and TUN health synchronization.
 */
internal object AlwaysOnVpnConsentHelper {

    private const val TAG = "AntiPorn_VpnConsent"

    fun resolveTargetAlwaysOnPackagePure(
        packageName: String,
        requiredMode: CurfewBlackholeVpnService.VpnMode,
        shouldBlockExternalVpn: Boolean
    ): String? = when (requiredMode) {
        CurfewBlackholeVpnService.VpnMode.CURFEW_BLACKHOLE -> packageName
        CurfewBlackholeVpnService.VpnMode.DNS_FILTERING_ONLY -> if (shouldBlockExternalVpn) packageName else null
        CurfewBlackholeVpnService.VpnMode.IDLE -> null
    }

    fun resolveTargetAlwaysOnPackage(
        context: Context,
        requiredMode: CurfewBlackholeVpnService.VpnMode,
        shouldBlockExternalVpn: Boolean? = null
    ): String? {
        val blockExternal = shouldBlockExternalVpn ?: try {
            PolicyRepository.getInstance(context).getCurrentPolicy().isBlockExternalVpnEnabled
        } catch (e: Exception) {
            Timber.tag(TAG).d(e, "Failed reading isBlockExternalVpnEnabled; defaulting true")
            true
        }
        return resolveTargetAlwaysOnPackagePure(context.packageName, requiredMode, blockExternal)
    }

    fun isVpnConsentGranted(context: Context): Boolean = try {
        VpnService.prepare(context) == null
    } catch (e: Exception) {
        Timber.tag(TAG).w(e, "VpnService.prepare check failed")
        false
    }

    fun isLocalVpnExpectedActive(context: Context): Boolean =
        CurfewBlackholeVpnService.evaluateRequiredMode(context) != CurfewBlackholeVpnService.VpnMode.IDLE &&
            isVpnConsentGranted(context)

    fun needsManualEmergencyVpnConsent(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        if (CurfewBlackholeVpnService.evaluateRequiredMode(context) != CurfewBlackholeVpnService.VpnMode.DNS_FILTERING_ONLY) {
            return false
        }
        if (isVpnConsentGranted(context)) return false
        val policy = try {
            PolicyRepository.getInstance(context).getCurrentPolicy()
        } catch (e: Exception) {
            Timber.tag(TAG).d(e, "Failed reading policy for manual emergency VPN consent check")
            null
        }
        return policy?.isBlockExternalVpnEnabled == false
    }

    fun waitForAndVerifyVpnReady(
        context: Context,
        expectedMode: CurfewBlackholeVpnService.VpnMode = CurfewBlackholeVpnService.evaluateRequiredMode(context)
    ): Boolean {
        if (!isVpnConsentGranted(context)) return false
        val ready = if (CurfewBlackholeVpnService.isTunnelHealthy(expectedMode)) true
        else CurfewBlackholeVpnService.awaitTunnelReadyBlocking(expectedMode = expectedMode)
        return ready && isVpnConsentGranted(context) && CurfewBlackholeVpnService.isTunnelHealthy(expectedMode)
    }
}
