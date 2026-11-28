package com.drmohammed.antiporn.curfew

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.os.Build
import android.os.UserManager
import com.drmohammed.antiporn.R
import com.drmohammed.antiporn.accessibility.control.AccessibilitySelfActivator
import com.drmohammed.antiporn.core.engine.SecondaryProfileDetector
import com.drmohammed.antiporn.core.model.ShieldResult
import com.drmohammed.antiporn.core.model.ShieldStatus
import com.drmohammed.antiporn.core.network.PhysicalNetworkResolver
import com.drmohammed.antiporn.shields.DnsShield
import com.drmohammed.antiporn.telemetry.BlackBoxRecorder
import com.drmohammed.antiporn.telemetry.SafeDpmGateway

/**
 * Evaluates DNS capabilities, applies [DnsShield] policy, and computes [ShieldStatus]
 * for standalone DoT and local VPN filtering modes.
 */
internal object DnsShieldPolicyEvaluator {

    fun isDoTLeakingAndUnaffiliatedBlocked(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        val isBlocked = PrivateDnsSettingsBridge.isPrivateDnsBlockedByUnaffiliatedUsers(context)
        if (!isBlocked) return false
        return isDoTLeakingAndUnaffiliatedBlockedInternal(
            sdkInt = Build.VERSION.SDK_INT,
            isBlocked = isBlocked,
            hasVerifiedFamilyDns = PrivateDnsSettingsBridge.hasActiveVerifiedFamilyDns(context),
            canWriteSecure = AccessibilitySelfActivator.canWriteSecureSettings(context),
            hasBlockingNonFamilyHost = PrivateDnsSettingsBridge.getBlockingNonFamilyPrivateDnsHost(context) != null
        )
    }

    fun isDoTLeakingAndUnaffiliatedBlockedInternal(
        sdkInt: Int,
        isBlocked: Boolean,
        hasVerifiedFamilyDns: Boolean,
        canWriteSecure: Boolean,
        hasBlockingNonFamilyHost: Boolean
    ): Boolean = sdkInt >= Build.VERSION_CODES.Q &&
        isBlocked && !hasVerifiedFamilyDns && !canWriteSecure && !hasBlockingNonFamilyHost

    fun evaluateUnaffiliatedDnsNoticeStateInternal(
        unaffiliatedBlocked: Boolean,
        canWriteSecure: Boolean,
        isDotLeaking: Boolean,
        isLocalVpnTunnelHealthy: Boolean,
        hasVerifiedFamilyDns: Boolean
    ): UnaffiliatedDnsNoticeState {
        if (!unaffiliatedBlocked || canWriteSecure) return UnaffiliatedDnsNoticeState.NONE
        if (isDotLeaking || (isLocalVpnTunnelHealthy && !hasVerifiedFamilyDns)) {
            return UnaffiliatedDnsNoticeState.EMERGENCY_VPN_ACTIVE
        }
        return if (hasVerifiedFamilyDns) UnaffiliatedDnsNoticeState.DOT_LOCKED_NO_CUSTOMIZE
        else UnaffiliatedDnsNoticeState.NONE
    }

    fun evaluateUnaffiliatedDnsNoticeState(context: Context): UnaffiliatedDnsNoticeState {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return UnaffiliatedDnsNoticeState.NONE
        val unaffiliatedBlocked = PrivateDnsSettingsBridge.isPrivateDnsBlockedByUnaffiliatedUsers(context)
        if (!unaffiliatedBlocked) return UnaffiliatedDnsNoticeState.NONE
        if (AccessibilitySelfActivator.canWriteSecureSettings(context)) return UnaffiliatedDnsNoticeState.NONE
        return evaluateUnaffiliatedDnsNoticeStateInternal(
            unaffiliatedBlocked = unaffiliatedBlocked,
            canWriteSecure = false,
            isDotLeaking = isDoTLeakingAndUnaffiliatedBlocked(context),
            isLocalVpnTunnelHealthy = CurfewBlackholeVpnService.isTunnelHealthy(CurfewBlackholeVpnService.VpnMode.DNS_FILTERING_ONLY),
            hasVerifiedFamilyDns = PrivateDnsSettingsBridge.hasActiveVerifiedFamilyDns(context)
        )
    }

    fun evaluateWebFilterCapabilityInternal(
        sdkInt: Int,
        isDeviceOwner: Boolean,
        unaffiliatedBlocked: Boolean,
        canWriteSecure: Boolean,
        globalMode: String?,
        globalHost: String?,
        blockingHostileHost: String?,
        initialDnsHost: String,
        requestedDnsHost: String,
        wantsLocalVpnFiltering: Boolean
    ): WebFilterCapabilityResult {
        if (sdkInt < Build.VERSION_CODES.Q || !isDeviceOwner || !unaffiliatedBlocked || canWriteSecure) {
            return WebFilterCapabilityResult.Allowed
        }
        if (!blockingHostileHost.isNullOrBlank()) {
            return WebFilterCapabilityResult.BlockedHostilePrivateDns(blockingHostileHost)
        }
        if (globalMode == "hostname" && !globalHost.isNullOrBlank()) {
            if (wantsLocalVpnFiltering) return WebFilterCapabilityResult.BlockedLocalFilterByLockedDot(globalHost)
            val cleanRequested = requestedDnsHost.trim()
            val cleanGlobal = globalHost.trim()
            if (!cleanRequested.equals(initialDnsHost.trim(), ignoreCase = true) &&
                !cleanRequested.equals(cleanGlobal, ignoreCase = true)
            ) {
                return WebFilterCapabilityResult.BlockedDnsHostChange(cleanGlobal, cleanRequested)
            }
        }
        return WebFilterCapabilityResult.Allowed
    }

    fun evaluateWebFilterCapability(
        context: Context,
        initialDnsHost: String,
        requestedDnsHost: String,
        wantsLocalVpnFiltering: Boolean
    ): WebFilterCapabilityResult {
        return evaluateWebFilterCapabilityInternal(
            sdkInt = Build.VERSION.SDK_INT,
            isDeviceOwner = SafeDpmGateway.isDeviceOwner(context),
            unaffiliatedBlocked = PrivateDnsSettingsBridge.isPrivateDnsBlockedByUnaffiliatedUsers(context),
            canWriteSecure = AccessibilitySelfActivator.canWriteSecureSettings(context),
            globalMode = PrivateDnsSettingsBridge.readGlobalSetting(context, "private_dns_mode"),
            globalHost = PrivateDnsSettingsBridge.readGlobalSetting(context, "private_dns_specifier"),
            blockingHostileHost = PrivateDnsSettingsBridge.getBlockingNonFamilyPrivateDnsHost(context),
            initialDnsHost = initialDnsHost,
            requestedDnsHost = requestedDnsHost,
            wantsLocalVpnFiltering = wantsLocalVpnFiltering
        )
    }

    fun isStandaloneDnsShieldActive(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return true
        return PrivateDnsSettingsBridge.isStandaloneDnsStateConsistent(
            dpmMode = PrivateDnsSettingsBridge.readDpmPrivateDnsMode(context),
            dpmHost = PrivateDnsSettingsBridge.readDpmPrivateDnsHost(context),
            globalMode = PrivateDnsSettingsBridge.readGlobalSetting(context, "private_dns_mode"),
            globalHost = PrivateDnsSettingsBridge.readGlobalSetting(context, "private_dns_specifier"),
            targetHost = DnsShield.getEffectiveDnsHost(context),
            isUnaffiliatedBlocked = PrivateDnsSettingsBridge.isPrivateDnsBlockedByUnaffiliatedUsers(context)
        )
    }

    fun isLocalVpnDnsModeHealthy(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return true
        return PrivateDnsSettingsBridge.isLocalVpnDnsStateConsistent(
            dpmMode = PrivateDnsSettingsBridge.readDpmPrivateDnsMode(context),
            globalMode = PrivateDnsSettingsBridge.readGlobalSetting(context, "private_dns_mode"),
            globalHost = PrivateDnsSettingsBridge.readGlobalSetting(context, "private_dns_specifier"),
            isUnaffiliatedBlocked = PrivateDnsSettingsBridge.isPrivateDnsBlockedByUnaffiliatedUsers(context),
            approvedPolicyHost = PrivateDnsSettingsBridge.readApprovedPolicyDnsHost(context)
        )
    }

    fun applyDnsShieldPolicy(context: Context, shieldId: String, targetHost: String): ShieldResult {
        val requiredMode = CurfewBlackholeVpnService.evaluateRequiredMode(context)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q && requiredMode == CurfewBlackholeVpnService.VpnMode.IDLE) {
            return ShieldResult(shieldId, false, context.getString(R.string.shield_dns_unsupported))
        }
        if (requiredMode != CurfewBlackholeVpnService.VpnMode.IDLE) {
            return AlwaysOnVpnPolicyBridge.applyLocalVpnDnsPolicy(context, shieldId, targetHost, requiredMode, allowStandaloneFallback = true)
        }

        val (dnsSetResult, usedFallback) = StandaloneDotEnforcer.applyStandaloneDot(context, targetHost)
        PrivateDnsSettingsBridge.lockDnsAndNetworkRestrictions(context)
        val isDpmNoError = dnsSetResult == DevicePolicyManager.PRIVATE_DNS_SET_NO_ERROR

        if (!isDpmNoError && !usedFallback && isDoTLeakingAndUnaffiliatedBlocked(context)) {
            val postMode = CurfewBlackholeVpnService.evaluateRequiredMode(context)
            if (postMode != CurfewBlackholeVpnService.VpnMode.IDLE) {
                return AlwaysOnVpnPolicyBridge.applyLocalVpnDnsPolicy(context, shieldId, targetHost, postMode, allowStandaloneFallback = false)
            }
        }

        val currentGlobalMode = PrivateDnsSettingsBridge.readGlobalSetting(context, "private_dns_mode")
        val currentGlobalHost = PrivateDnsSettingsBridge.readGlobalSetting(context, "private_dns_specifier")
        val isFamilyDnsActive = PrivateDnsSettingsBridge.hasActiveVerifiedFamilyDns(
            currentGlobalMode,
            currentGlobalHost,
            PrivateDnsSettingsBridge.readApprovedPolicyDnsHost(context)
        )
        val unaffiliatedBlocked = PrivateDnsSettingsBridge.isPrivateDnsBlockedByUnaffiliatedUsers(context)
        val isUnaffiliatedFallback = unaffiliatedBlocked && isFamilyDnsActive
        val isUnaffiliatedError = unaffiliatedBlocked && !isFamilyDnsActive
        val blockingHost = if (isUnaffiliatedError) PrivateDnsSettingsBridge.getBlockingNonFamilyPrivateDnsHost(context) else null
        val success = isDpmNoError || usedFallback || isUnaffiliatedFallback
        val msg = when {
            isDpmNoError && !usedFallback -> context.getString(R.string.shield_dns_success)
            usedFallback -> context.getString(R.string.shield_dns_fallback_active)
            isUnaffiliatedFallback -> context.getString(
                R.string.shield_dns_unaffiliated_users_notice,
                currentGlobalHost ?: DnsShield.DEFAULT_DNS_HOST,
                targetHost
            )
            isUnaffiliatedError && blockingHost != null -> {
                val cloneSummary = SecondaryProfileDetector.formatClonedAppsSummary(context)
                    ?: context.getString(R.string.shield_dns_cloned_app_default)
                context.getString(R.string.shield_dns_unaffiliated_hostile_dns_error, blockingHost, cloneSummary)
            }
            isUnaffiliatedError -> context.getString(R.string.shield_dns_unaffiliated_users_error)
            else -> context.getString(R.string.card_dns_inactive)
        }
        val statusCode = if (isUnaffiliatedError) CurfewVpnDnsBridge.COMPAT_PRIVATE_DNS_SET_ERROR_FAILURE_SETTING else dnsSetResult
        return ShieldResult(shieldId, success, msg, statusCode)
    }

    fun checkDnsShieldStatus(context: Context, targetHost: String): ShieldStatus {
        val currentRestrictions = SafeDpmGateway.getUserRestrictionsBlocking(context, BlackBoxRecorder.TAG_SHIELD, recordSuccess = false).getOrNull()
        val isDnsLocked = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            currentRestrictions?.getBoolean(UserManager.DISALLOW_CONFIG_PRIVATE_DNS, false) == true
        } else true
        val globalMode = PrivateDnsSettingsBridge.readGlobalSetting(context, "private_dns_mode")
        val globalSpecifier = PrivateDnsSettingsBridge.readGlobalSetting(context, "private_dns_specifier")
        val modeFromDpm = PrivateDnsSettingsBridge.readDpmPrivateDnsMode(context)

        val requiredVpnMode = CurfewBlackholeVpnService.evaluateRequiredMode(context)
        if (requiredVpnMode != CurfewBlackholeVpnService.VpnMode.IDLE) {
            return checkVpnDnsShieldStatus(context, requiredVpnMode, isDnsLocked, globalMode, globalSpecifier, modeFromDpm)
        }

        val hostFromDpm = PrivateDnsSettingsBridge.readDpmPrivateDnsHost(context)
        val unaffiliatedBlocked = PrivateDnsSettingsBridge.isPrivateDnsBlockedByUnaffiliatedUsers(context)
        val isOfflinePreArmed = PhysicalNetworkResolver.isDevicePhysicallyOffline(context) &&
            CurfewConfig.isPendingDpmPrivateDnsSync(context)
        val isConsistent = PrivateDnsSettingsBridge.isStandaloneDnsStateConsistent(
            dpmMode = modeFromDpm,
            dpmHost = hostFromDpm,
            globalMode = globalMode,
            globalHost = globalSpecifier,
            targetHost = targetHost,
            isUnaffiliatedBlocked = unaffiliatedBlocked,
            isOfflinePreArmedPendingDpm = isOfflinePreArmed
        )
        return when {
            isConsistent && isDnsLocked -> {
                val activeHost = globalSpecifier?.takeIf { it.isNotBlank() } ?: hostFromDpm ?: targetHost
                val dpmHostMatches = modeFromDpm == PrivateDnsSettingsBridge.privateDnsModeProviderHostname() &&
                    hostFromDpm?.equals(targetHost, ignoreCase = true) == true
                if (isOfflinePreArmed && !dpmHostMatches) {
                    ShieldStatus.Active(context.getString(R.string.card_dns_active_prearmed_pending_dpm, activeHost))
                } else {
                    ShieldStatus.Active(context.getString(R.string.card_dns_active, activeHost))
                }
            }
            unaffiliatedBlocked && isDnsLocked -> {
                val approvedHost = PrivateDnsSettingsBridge.readApprovedPolicyDnsHost(context)
                if (PrivateDnsSettingsBridge.hasActiveVerifiedFamilyDns(globalMode, globalSpecifier, approvedHost)) {
                    val currentActiveHost = globalSpecifier?.takeIf { it.isNotBlank() } ?: approvedHost ?: targetHost
                    ShieldStatus.Active(context.getString(R.string.shield_dns_unaffiliated_users_notice, currentActiveHost, targetHost))
                } else {
                    val blockingHost = PrivateDnsSettingsBridge.getBlockingNonFamilyPrivateDnsHost(context)
                    val canWriteSecure = if (blockingHost != null) AccessibilitySelfActivator.canWriteSecureSettings(context) else false
                    val cloneSummary = SecondaryProfileDetector.formatClonedAppsSummary(context)
                        ?: context.getString(R.string.shield_dns_cloned_app_default)
                    if (blockingHost != null && !canWriteSecure) {
                        ShieldStatus.NeedsEnforcement(context.getString(R.string.shield_dns_unaffiliated_hostile_dns_error, blockingHost, cloneSummary))
                    } else {
                        ShieldStatus.NeedsEnforcement(context.getString(R.string.shield_dns_unaffiliated_users_error_with_app, cloneSummary))
                    }
                }
            }
            else -> {
                val currentHostDisplay = globalSpecifier?.takeIf { it.isNotBlank() } ?: hostFromDpm ?: context.getString(R.string.default_dns_text)
                ShieldStatus.NeedsEnforcement(context.getString(R.string.card_dns_pending, currentHostDisplay, targetHost))
            }
        }
    }

    private fun checkVpnDnsShieldStatus(
        context: Context,
        requiredVpnMode: CurfewBlackholeVpnService.VpnMode,
        isDnsLocked: Boolean,
        globalMode: String?,
        globalSpecifier: String?,
        modeFromDpm: Int
    ): ShieldStatus {
        val unaffiliatedBlocked = PrivateDnsSettingsBridge.isPrivateDnsBlockedByUnaffiliatedUsers(context)
        val blockingHost = if (unaffiliatedBlocked) PrivateDnsSettingsBridge.getBlockingNonFamilyPrivateDnsHost(context) else null
        val canWriteSecure = if (blockingHost != null) AccessibilitySelfActivator.canWriteSecureSettings(context) else false
        if (unaffiliatedBlocked && blockingHost != null && !canWriteSecure) {
            val cloneSummary = SecondaryProfileDetector.formatClonedAppsSummary(context)
                ?: context.getString(R.string.shield_dns_cloned_app_default)
            return ShieldStatus.NeedsEnforcement(context.getString(R.string.shield_dns_unaffiliated_hostile_dns_error, blockingHost, cloneSummary))
        }
        if (!AlwaysOnVpnConsentHelper.isVpnConsentGranted(context)) {
            val message = if (AlwaysOnVpnConsentHelper.needsManualEmergencyVpnConsent(context)) {
                context.getString(R.string.shield_dns_emergency_vpn_needs_consent)
            } else {
                context.getString(R.string.shield_dns_local_filter_needs_sync)
            }
            return ShieldStatus.NeedsEnforcement(message)
        }
        val approvedHost = PrivateDnsSettingsBridge.readApprovedPolicyDnsHost(context)
        val isHealthy = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            PrivateDnsSettingsBridge.isLocalVpnDnsStateConsistent(modeFromDpm, globalMode, globalSpecifier, unaffiliatedBlocked, approvedHost)
        } else globalMode != "hostname"
        return if (isHealthy && isDnsLocked && CurfewBlackholeVpnService.isTunnelHealthy(requiredVpnMode)) {
            val isUnaffiliatedFallback = unaffiliatedBlocked && globalMode == "hostname" &&
                PrivateDnsSettingsBridge.hasActiveVerifiedFamilyDns(globalMode, globalSpecifier, approvedHost)
            val message = when {
                isUnaffiliatedFallback -> context.getString(R.string.shield_dns_local_filter_unaffiliated_notice, globalSpecifier ?: DnsShield.DEFAULT_DNS_HOST)
                unaffiliatedBlocked -> context.getString(
                    R.string.shield_dns_emergency_vpn_active_notice,
                    SecondaryProfileDetector.formatClonedAppsSummary(context) ?: context.getString(R.string.shield_dns_cloned_app_default)
                )
                com.drmohammed.antiporn.core.network.dns.DnsResilienceManager.isLocalVpnFallbackActive() ->
                    context.getString(R.string.shield_dns_resilience_doh_active)
                else -> context.getString(R.string.shield_dns_local_filter_active)
            }
            ShieldStatus.Active(message)
        } else {
            ShieldStatus.NeedsEnforcement(context.getString(R.string.shield_dns_local_filter_needs_sync))
        }
    }
}
