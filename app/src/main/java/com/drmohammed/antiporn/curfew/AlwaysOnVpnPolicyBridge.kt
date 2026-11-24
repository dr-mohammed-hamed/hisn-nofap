package com.drmohammed.antiporn.curfew

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi
import com.drmohammed.antiporn.R
import com.drmohammed.antiporn.core.engine.ProtectionTransitionGuard
import com.drmohammed.antiporn.core.engine.SecondaryProfileDetector
import com.drmohammed.antiporn.core.model.ShieldResult
import com.drmohammed.antiporn.shields.DnsShield
import com.drmohammed.antiporn.telemetry.BlackBoxRecorder
import com.drmohammed.antiporn.telemetry.SafeDpmGateway
import timber.log.Timber

/**
 * Coordinates Always-On VPN lockdown, local DNS filtering VPN activation/teardown,
 * and opportunistic Private DNS synchronization.
 */
internal object AlwaysOnVpnPolicyBridge {

    private const val TAG = "AntiPorn_VpnPolicyBridge"

    @Volatile
    private var lastAppliedBypassedPackages: Set<String>? = null

    fun restoreDnsShieldOnCurfewRelease(context: Context): Boolean {
        if (!SafeDpmGateway.isDeviceOwner(context)) return true
        val result = SafeDpmGateway.executeRawBlocking(
            context = context,
            tag = BlackBoxRecorder.TAG_SHIELD,
            actionName = "restoreDnsShieldOnCurfewRelease"
        ) { dpm, admin ->
            DnsShield.apply(context, dpm, admin)
        }
        return result.getOrNull()?.success == true
    }

    fun ensureOpportunisticDnsForLocalVpn(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return true
        if (!SafeDpmGateway.isDeviceOwner(context)) return false
        return ensureOpportunisticDnsForLocalVpnInternal(context)
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    fun ensureOpportunisticDnsForLocalVpnInternal(context: Context): Boolean {
        ProtectionTransitionGuard.markInternalDnsMutation()
        var dpmOk = true
        val dpmMode = PrivateDnsSettingsBridge.readDpmPrivateDnsMode(context)
        if (dpmMode != DevicePolicyManager.PRIVATE_DNS_MODE_OPPORTUNISTIC) {
            val dpmRes = SafeDpmGateway.setGlobalPrivateDnsOpportunisticBlocking(context, BlackBoxRecorder.TAG_SHIELD)
            if (PrivateDnsSettingsBridge.isUnaffiliatedUsersException(dpmRes)) {
                PrivateDnsSettingsBridge.updatePrivateDnsBlockedByUnaffiliatedUsers(context, true)
                BlackBoxRecorder.recordShield(context, DnsShield.id, false, "setGlobalPrivateDnsOpportunistic blocked: unaffiliated users")
            } else if (dpmRes.isSuccess) {
                PrivateDnsSettingsBridge.updatePrivateDnsBlockedByUnaffiliatedUsers(context, false)
            }
            if (!dpmRes.isSuccess || dpmRes.getOrNull() != DevicePolicyManager.PRIVATE_DNS_SET_NO_ERROR) {
                dpmOk = false
                Timber.tag(TAG).w("SafeDpmGateway setGlobalPrivateDnsOpportunisticBlocking failed")
            }
        }
        val globalMode = PrivateDnsSettingsBridge.readGlobalSetting(context, "private_dns_mode")
        if (!dpmOk || globalMode == "hostname") {
            try {
                if (PrivateDnsSettingsBridge.writePrivateDnsAtomic(context, "opportunistic", "")) {
                    PrivateDnsSettingsBridge.directGlobalWriteDenied = false
                }
            } catch (e: SecurityException) {
                PrivateDnsSettingsBridge.directGlobalWriteDenied = true
                Timber.tag(TAG).w(e, "Best-effort Settings.Global opportunistic fallback blocked by SecurityException")
            } catch (e: Exception) {
                Timber.tag(TAG).w(e, "Could not write Settings.Global opportunistic mode for Local VPN")
            }
        }
        PrivateDnsSettingsBridge.lockDnsAndNetworkRestrictions(context)
        CurfewConfig.setPendingDpmPrivateDnsSync(context, false)
        return PrivateDnsSettingsBridge.isLocalVpnDnsStateConsistent(
            dpmMode = PrivateDnsSettingsBridge.readDpmPrivateDnsMode(context),
            globalMode = PrivateDnsSettingsBridge.readGlobalSetting(context, "private_dns_mode"),
            globalHost = PrivateDnsSettingsBridge.readGlobalSetting(context, "private_dns_specifier"),
            isUnaffiliatedBlocked = PrivateDnsSettingsBridge.isPrivateDnsBlockedByUnaffiliatedUsers(context),
            approvedPolicyHost = PrivateDnsSettingsBridge.readApprovedPolicyDnsHost(context)
        )
    }

    fun ensureBlackholeVpnActive(context: Context, alwaysOnPkg: String?, isLockdown: Boolean): Pair<Boolean, Boolean> {
        var changed = false
        var dpmOk = true
        try {
            val bypassedPackages = CurfewConfig.getVpnBypassedPackages(context)
            val bypassChanged = lastAppliedBypassedPackages != bypassedPackages
            if (alwaysOnPkg != context.packageName || !isLockdown || bypassChanged) {
                val res = SafeDpmGateway.setAlwaysOnVpnPackageBlocking(context, context.packageName, true, bypassedPackages)
                if (res.isSuccess) {
                    lastAppliedBypassedPackages = bypassedPackages
                    Timber.tag(TAG).i("Always-on Blackhole VPN configured (bypassCount=${bypassedPackages.size})")
                    changed = true
                } else {
                    dpmOk = false
                    Timber.tag(TAG).e("Failed configuring Always-On Blackhole VPN via SafeDpmGateway")
                }
            }
            if (dpmOk || AlwaysOnVpnConsentHelper.isVpnConsentGranted(context)) {
                if (!CurfewBlackholeVpnService.isServiceOrTunnelActive() ||
                    CurfewBlackholeVpnService.currentMode != CurfewBlackholeVpnService.VpnMode.CURFEW_BLACKHOLE
                ) {
                    CurfewBlackholeVpnService.start(context)
                    changed = true
                } else if (bypassChanged) {
                    CurfewBlackholeVpnService.syncPolicy(context)
                    changed = true
                }
            } else {
                dpmOk = false
                Timber.tag(TAG).w("Skipping Blackhole VPN start: Always-On failed & consent missing")
            }
            if (AlwaysOnVpnConsentHelper.waitForAndVerifyVpnReady(context, CurfewBlackholeVpnService.VpnMode.CURFEW_BLACKHOLE)) {
                if (!ensureOpportunisticDnsForLocalVpn(context)) {
                    dpmOk = false
                    Timber.tag(TAG).w("Failed ensuring opportunistic DNS while activating Blackhole VPN")
                }
            } else {
                dpmOk = false
                Timber.tag(TAG).w("Blackhole VPN consent or TUN not ready; skipping opportunistic downgrade")
            }
        } catch (e: Exception) {
            dpmOk = false
            Timber.tag(TAG).e(e, "Error configuring Always-On Blackhole VPN")
        }
        return changed to dpmOk
    }

    fun applyLocalVpnDnsPolicy(
        context: Context,
        shieldId: String,
        targetHost: String,
        requiredMode: CurfewBlackholeVpnService.VpnMode,
        allowStandaloneFallback: Boolean
    ): ShieldResult {
        if (!AlwaysOnVpnConsentHelper.isVpnConsentGranted(context) && SafeDpmGateway.isDeviceOwner(context)) {
            val lockdown = requiredMode == CurfewBlackholeVpnService.VpnMode.CURFEW_BLACKHOLE
            val bypassed = if (lockdown) CurfewConfig.getVpnBypassedPackages(context) else null
            AlwaysOnVpnConsentHelper.resolveTargetAlwaysOnPackage(context, requiredMode)?.let { targetPkg ->
                SafeDpmGateway.setAlwaysOnVpnPackageBlocking(context, targetPkg, lockdown, bypassed)
            }
        }
        if (!AlwaysOnVpnConsentHelper.isVpnConsentGranted(context)) {
            Timber.tag(TAG).w("VpnService.prepare returned non-null; preserving standalone DoT")
            if (allowStandaloneFallback && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                StandaloneDotEnforcer.applyStandaloneDot(context, targetHost)
            }
            PrivateDnsSettingsBridge.lockDnsAndNetworkRestrictions(context)
            val message = if (AlwaysOnVpnConsentHelper.needsManualEmergencyVpnConsent(context)) {
                context.getString(R.string.shield_dns_emergency_vpn_needs_consent)
            } else {
                context.getString(R.string.card_dns_inactive)
            }
            return ShieldResult(shieldId, false, message, CurfewVpnDnsBridge.COMPAT_PRIVATE_DNS_SET_ERROR_FAILURE_SETTING)
        }

        try {
            CurfewBlackholeVpnService.syncPolicy(context)
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Failed to sync Unified VPN policy from DnsShield")
        }

        if (!AlwaysOnVpnConsentHelper.waitForAndVerifyVpnReady(context, requiredMode)) {
            Timber.tag(TAG).w("VPN TUN verification failed; preserving standalone DoT")
            if (allowStandaloneFallback && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                StandaloneDotEnforcer.applyStandaloneDot(context, targetHost)
            }
            PrivateDnsSettingsBridge.lockDnsAndNetworkRestrictions(context)
            return ShieldResult(shieldId, false, context.getString(R.string.card_dns_inactive), CurfewVpnDnsBridge.COMPAT_PRIVATE_DNS_SET_ERROR_FAILURE_SETTING)
        }

        val opportunisticOk = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ensureOpportunisticDnsForLocalVpnInternal(context)
        } else {
            PrivateDnsSettingsBridge.lockDnsAndNetworkRestrictions(context)
            true
        }
        if (opportunisticOk) {
            val currentGlobalMode = PrivateDnsSettingsBridge.readGlobalSetting(context, "private_dns_mode")
            val currentGlobalHost = PrivateDnsSettingsBridge.readGlobalSetting(context, "private_dns_specifier")
            val unaffiliatedBlocked = PrivateDnsSettingsBridge.isPrivateDnsBlockedByUnaffiliatedUsers(context)
            val approvedHost = PrivateDnsSettingsBridge.readApprovedPolicyDnsHost(context)
            val isUnaffiliatedFallback = unaffiliatedBlocked && currentGlobalMode == "hostname" &&
                PrivateDnsSettingsBridge.hasActiveVerifiedFamilyDns(currentGlobalMode, currentGlobalHost, approvedHost)
            val message = when {
                isUnaffiliatedFallback -> context.getString(R.string.shield_dns_local_filter_unaffiliated_notice, currentGlobalHost ?: DnsShield.DEFAULT_DNS_HOST)
                unaffiliatedBlocked -> context.getString(
                    R.string.shield_dns_emergency_vpn_active_notice,
                    SecondaryProfileDetector.formatClonedAppsSummary(context) ?: context.getString(R.string.shield_dns_cloned_app_default)
                )
                else -> context.getString(R.string.shield_dns_local_filter_active)
            }
            return ShieldResult(shieldId, true, message, CurfewVpnDnsBridge.COMPAT_PRIVATE_DNS_SET_NO_ERROR)
        }

        val unaffiliatedBlocked = PrivateDnsSettingsBridge.isPrivateDnsBlockedByUnaffiliatedUsers(context)
        val blockingHost = PrivateDnsSettingsBridge.getBlockingNonFamilyPrivateDnsHost(context)
        if (unaffiliatedBlocked && blockingHost != null) {
            if (requiredMode == CurfewBlackholeVpnService.VpnMode.DNS_FILTERING_ONLY &&
                !CurfewConfig.isInstantInternetKillEnabled(context) &&
                !CurfewEnforcer.isEffectiveBlockActive(context)
            ) {
                if (CurfewBlackholeVpnService.isServiceOrTunnelActive() &&
                    CurfewBlackholeVpnService.currentMode == CurfewBlackholeVpnService.VpnMode.DNS_FILTERING_ONLY
                ) {
                    CurfewBlackholeVpnService.forceReleaseVpnAndAwait(context, 1500L)
                }
                if (SafeDpmGateway.getAlwaysOnVpnPackageBlocking(context, recordSuccess = false).getOrNull() == context.packageName) {
                    SafeDpmGateway.setAlwaysOnVpnPackageBlocking(context, null, false)
                }
            }
            val cloneSummary = SecondaryProfileDetector.formatClonedAppsSummary(context) ?: context.getString(R.string.shield_dns_cloned_app_default)
            return ShieldResult(
                shieldId,
                false,
                context.getString(R.string.shield_dns_unaffiliated_hostile_dns_error, blockingHost, cloneSummary),
                CurfewVpnDnsBridge.COMPAT_PRIVATE_DNS_SET_ERROR_FAILURE_SETTING
            )
        }
        return ShieldResult(shieldId, false, context.getString(R.string.card_dns_inactive), CurfewVpnDnsBridge.COMPAT_PRIVATE_DNS_SET_ERROR_FAILURE_SETTING)
    }

    fun reconcileNonBlackholeVpnAndDns(
        context: Context,
        requiredMode: CurfewBlackholeVpnService.VpnMode,
        alwaysOnPkg: String?,
        isLockdown: Boolean
    ): Boolean {
        return try {
            var allOk = true
            lastAppliedBypassedPackages = null
            if (requiredMode == CurfewBlackholeVpnService.VpnMode.DNS_FILTERING_ONLY) {
                val targetAlwaysOnPkg = AlwaysOnVpnConsentHelper.resolveTargetAlwaysOnPackage(context, requiredMode)
                if (alwaysOnPkg != targetAlwaysOnPkg || isLockdown) {
                    val vpnRes = SafeDpmGateway.setAlwaysOnVpnPackageBlocking(context, targetAlwaysOnPkg, false)
                    if (!vpnRes.isSuccess) {
                        Timber.tag(TAG).e("Failed configuring Always-on VPN (target=$targetAlwaysOnPkg)")
                        allOk = false
                    } else {
                        Timber.tag(TAG).i("Always-on VPN configured to target=$targetAlwaysOnPkg (lockdown=false)")
                    }
                }
                if (!CurfewBlackholeVpnService.isServiceOrTunnelActive() ||
                    CurfewBlackholeVpnService.currentMode != CurfewBlackholeVpnService.VpnMode.DNS_FILTERING_ONLY
                ) {
                    CurfewBlackholeVpnService.syncPolicy(context)
                }
                if (!AlwaysOnVpnConsentHelper.waitForAndVerifyVpnReady(context, requiredMode)) {
                    allOk = false
                } else if (!DnsShieldPolicyEvaluator.isLocalVpnDnsModeHealthy(context) && !ensureOpportunisticDnsForLocalVpn(context)) {
                    allOk = false
                    if (PrivateDnsSettingsBridge.hasUnclearableHostilePrivateDns(context)) {
                        CurfewBlackholeVpnService.forceReleaseVpnAndAwait(context, 1500L)
                        if (alwaysOnPkg == context.packageName || targetAlwaysOnPkg == context.packageName) {
                            SafeDpmGateway.setAlwaysOnVpnPackageBlocking(context, null, false)
                        }
                    }
                }
            } else {
                if (alwaysOnPkg == context.packageName || (alwaysOnPkg == null && isLockdown)) {
                    val vpnRes = SafeDpmGateway.setAlwaysOnVpnPackageBlocking(context, null, false)
                    if (!vpnRes.isSuccess) {
                        Timber.tag(TAG).e("Failed clearing Always-on Blackhole VPN via SafeDpmGateway")
                        allOk = false
                    } else {
                        Timber.tag(TAG).i("Always-on Blackhole VPN released via SafeDpmGateway")
                    }
                }
                var vpnWasTornDown = false
                if (CurfewBlackholeVpnService.isServiceOrTunnelActive()) {
                    CurfewBlackholeVpnService.forceReleaseVpnAndAwait(context, 2000L)
                    vpnWasTornDown = true
                }
                if ((vpnWasTornDown || !DnsShieldPolicyEvaluator.isStandaloneDnsShieldActive(context)) &&
                    !restoreDnsShieldOnCurfewRelease(context)
                ) {
                    allOk = false
                }
            }
            Timber.tag(TAG).i("VPN Blackhole is disabled and network is released (ok=$allOk).")
            allOk
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Error releasing Always-On Blackhole VPN")
            false
        }
    }

    fun isBlackholeVpnLockdownEnforced(context: Context): Boolean = try {
        val currentPkg = SafeDpmGateway.getAlwaysOnVpnPackageBlocking(context, tag = BlackBoxRecorder.TAG_SHIELD, recordSuccess = false).getOrNull()
        val isLockdown = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            SafeDpmGateway.isAlwaysOnVpnLockdownEnabledBlocking(context, tag = BlackBoxRecorder.TAG_SHIELD, recordSuccess = false).getOrNull() ?: false
        } else true
        currentPkg == context.packageName && isLockdown
    } catch (e: Exception) {
        Timber.tag(TAG).w(e, "Failed querying always-on VPN package during curfew check")
        false
    }

    fun isLingeringVpnLockdownPresent(context: Context): Boolean = try {
        val isBlackholeRunning = CurfewBlackholeVpnService.isServiceOrTunnelActive() &&
            CurfewBlackholeVpnService.currentMode == CurfewBlackholeVpnService.VpnMode.CURFEW_BLACKHOLE
        val currentPkg = SafeDpmGateway.getAlwaysOnVpnPackageBlocking(context, tag = BlackBoxRecorder.TAG_SHIELD, recordSuccess = false).getOrNull()
        val isVpnLingeringWhenIdle = CurfewBlackholeVpnService.evaluateRequiredMode(context) == CurfewBlackholeVpnService.VpnMode.IDLE &&
            currentPkg == context.packageName
        val isLockdownActive = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            SafeDpmGateway.isAlwaysOnVpnLockdownEnabledBlocking(context, tag = BlackBoxRecorder.TAG_SHIELD, recordSuccess = false).getOrNull() ?: false
        } else false
        isBlackholeRunning || isVpnLingeringWhenIdle || isLockdownActive
    } catch (e: Exception) {
        Timber.tag(TAG).w(e, "Failed querying always-on VPN lockdown during release check")
        false
    }
}
