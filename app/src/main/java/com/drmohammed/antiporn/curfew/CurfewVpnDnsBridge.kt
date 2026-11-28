package com.drmohammed.antiporn.curfew

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.os.Build
import androidx.annotation.VisibleForTesting
import com.drmohammed.antiporn.core.model.ShieldResult
import com.drmohammed.antiporn.core.model.ShieldStatus
import com.drmohammed.antiporn.core.network.PhysicalNetworkResolver
import com.drmohammed.antiporn.shields.DnsShield
import com.drmohammed.antiporn.telemetry.SafeDpmGateway

enum class DnsFastHealOutcome {
    SKIPPED_UNSUPPORTED_SDK,
    SKIPPED_NOT_DEVICE_OWNER,
    SKIPPED_ACTIVE_TRANSITION,
    SKIPPED_VPN_EXPECTED,
    ALREADY_COMPLIANT,
    HEALED_DIRECT,
    PERMISSION_DENIED_NEEDS_DPM,
    FAILED_NEEDS_DPM
}

data class DnsFastHealResult(
    val outcome: DnsFastHealOutcome,
    val observedMode: String? = null,
    val observedHost: String? = null,
    val expectedHost: String? = null,
    val latencyMs: Long = 0L,
    val errorMessage: String? = null
)

enum class UnaffiliatedDnsNoticeState {
    NONE,
    EMERGENCY_VPN_ACTIVE,
    DOT_LOCKED_NO_CUSTOMIZE
}

sealed class WebFilterCapabilityResult {
    data object Allowed : WebFilterCapabilityResult()
    data class BlockedDnsHostChange(val lockedHost: String, val requestedHost: String) : WebFilterCapabilityResult()
    data class BlockedLocalFilterByLockedDot(val lockedHost: String) : WebFilterCapabilityResult()
    data class BlockedHostilePrivateDns(val hostileHost: String) : WebFilterCapabilityResult()
}

/**
 * Facade coordinating Always-On VPN lockdown and Private DNS state during curfew transitions
 * and shield verification. Delegates to [PrivateDnsSettingsBridge], [DnsFastHealEngine],
 * [AlwaysOnVpnPolicyBridge], [StandaloneDotEnforcer], and [DnsShieldPolicyEvaluator].
 */
internal object CurfewVpnDnsBridge {

    internal const val COMPAT_PRIVATE_DNS_SET_NO_ERROR = 0
    internal const val COMPAT_PRIVATE_DNS_SET_ERROR_HOST_NOT_SERVING = 1
    internal const val COMPAT_PRIVATE_DNS_SET_ERROR_FAILURE_SETTING = 2

    internal fun isDirectGlobalWriteDenied(): Boolean = PrivateDnsSettingsBridge.directGlobalWriteDenied

    fun hasActiveVerifiedFamilyDns(context: Context): Boolean =
        PrivateDnsSettingsBridge.hasActiveVerifiedFamilyDns(context)

    internal fun hasActiveVerifiedFamilyDns(globalMode: String?, globalHost: String?): Boolean =
        PrivateDnsSettingsBridge.hasActiveVerifiedFamilyDns(globalMode, globalHost)

    internal fun hasActiveVerifiedFamilyDns(
        globalMode: String?,
        globalHost: String?,
        approvedPolicyHost: String?
    ): Boolean = PrivateDnsSettingsBridge.hasActiveVerifiedFamilyDns(globalMode, globalHost, approvedPolicyHost)

    fun getBlockingNonFamilyPrivateDnsHost(context: Context): String? =
        PrivateDnsSettingsBridge.getBlockingNonFamilyPrivateDnsHost(context)

    fun hasUnclearableHostilePrivateDns(context: Context): Boolean =
        PrivateDnsSettingsBridge.hasUnclearableHostilePrivateDns(context)

    @VisibleForTesting
    internal fun resolveTargetAlwaysOnPackagePure(
        packageName: String,
        requiredMode: CurfewBlackholeVpnService.VpnMode,
        shouldBlockExternalVpn: Boolean
    ): String? = AlwaysOnVpnConsentHelper.resolveTargetAlwaysOnPackagePure(packageName, requiredMode, shouldBlockExternalVpn)

    fun resolveTargetAlwaysOnPackage(
        context: Context,
        requiredMode: CurfewBlackholeVpnService.VpnMode,
        shouldBlockExternalVpn: Boolean? = null
    ): String? = AlwaysOnVpnConsentHelper.resolveTargetAlwaysOnPackage(context, requiredMode, shouldBlockExternalVpn)

    fun needsManualEmergencyVpnConsent(context: Context): Boolean =
        AlwaysOnVpnConsentHelper.needsManualEmergencyVpnConsent(context)

    fun isDoTLeakingAndUnaffiliatedBlocked(context: Context): Boolean =
        DnsShieldPolicyEvaluator.isDoTLeakingAndUnaffiliatedBlocked(context)

    @VisibleForTesting
    internal fun isDoTLeakingAndUnaffiliatedBlockedInternal(
        sdkInt: Int,
        isBlocked: Boolean,
        hasVerifiedFamilyDns: Boolean,
        canWriteSecure: Boolean,
        hasBlockingNonFamilyHost: Boolean
    ): Boolean = DnsShieldPolicyEvaluator.isDoTLeakingAndUnaffiliatedBlockedInternal(
        sdkInt, isBlocked, hasVerifiedFamilyDns, canWriteSecure, hasBlockingNonFamilyHost
    )

    @VisibleForTesting
    internal fun evaluateUnaffiliatedDnsNoticeStateInternal(
        unaffiliatedBlocked: Boolean,
        canWriteSecure: Boolean,
        isDotLeaking: Boolean,
        isLocalVpnTunnelHealthy: Boolean,
        hasVerifiedFamilyDns: Boolean
    ): UnaffiliatedDnsNoticeState = DnsShieldPolicyEvaluator.evaluateUnaffiliatedDnsNoticeStateInternal(
        unaffiliatedBlocked, canWriteSecure, isDotLeaking, isLocalVpnTunnelHealthy, hasVerifiedFamilyDns
    )

    fun evaluateUnaffiliatedDnsNoticeState(context: Context): UnaffiliatedDnsNoticeState =
        DnsShieldPolicyEvaluator.evaluateUnaffiliatedDnsNoticeState(context)

    @VisibleForTesting
    internal fun evaluateWebFilterCapabilityInternal(
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
    ): WebFilterCapabilityResult = DnsShieldPolicyEvaluator.evaluateWebFilterCapabilityInternal(
        sdkInt, isDeviceOwner, unaffiliatedBlocked, canWriteSecure, globalMode,
        globalHost, blockingHostileHost, initialDnsHost, requestedDnsHost, wantsLocalVpnFiltering
    )

    fun evaluateWebFilterCapability(
        context: Context,
        initialDnsHost: String,
        requestedDnsHost: String,
        wantsLocalVpnFiltering: Boolean
    ): WebFilterCapabilityResult = DnsShieldPolicyEvaluator.evaluateWebFilterCapability(
        context, initialDnsHost, requestedDnsHost, wantsLocalVpnFiltering
    )

    @VisibleForTesting
    internal fun shouldAllowUnaffiliatedDpmReprobe(
        countReliable: Boolean,
        nowMs: Long,
        lastRetryMs: Long,
        intervalMs: Long = 5 * 60 * 1_000L
    ): Boolean = StandaloneDotEnforcer.shouldAllowUnaffiliatedDpmReprobe(countReliable, nowMs, lastRetryMs, intervalMs)

    @VisibleForTesting
    internal fun resetUnaffiliatedRetryForTest() = StandaloneDotEnforcer.resetUnaffiliatedRetryForTest()

    @VisibleForTesting
    internal fun resetFastHealCircuitBreakerForTest() {
        PrivateDnsSettingsBridge.directGlobalWriteDenied = false
    }

    @VisibleForTesting
    internal fun isDirectGlobalWriteDeniedForTest(): Boolean = PrivateDnsSettingsBridge.directGlobalWriteDenied

    @VisibleForTesting
    internal fun writePrivateDnsPairOrdered(
        mode: String,
        specifier: String,
        writeGlobal: (String, String) -> Boolean
    ): Boolean = PrivateDnsSettingsBridge.writePrivateDnsPairOrdered(mode, specifier, writeGlobal)

    internal fun writePrivateDnsAtomic(context: Context, mode: String, specifier: String): Boolean =
        PrivateDnsSettingsBridge.writePrivateDnsAtomic(context, mode, specifier)

    internal fun writeGlobalSettingWithAtomicUnlock(context: Context, key: String, value: String): Boolean =
        PrivateDnsSettingsBridge.writeGlobalSettingWithAtomicUnlock(context, key, value)

    var isPrivateDnsBlockedByUnaffiliatedUsers: Boolean
        get() = PrivateDnsSettingsBridge.isUnaffiliatedBlockedCached
        internal set(value) {
            PrivateDnsSettingsBridge.isUnaffiliatedBlockedCached = value
        }

    fun isPrivateDnsBlockedByUnaffiliatedUsers(context: Context): Boolean =
        PrivateDnsSettingsBridge.isPrivateDnsBlockedByUnaffiliatedUsers(context)

    internal fun updatePrivateDnsBlockedByUnaffiliatedUsers(context: Context, blocked: Boolean) =
        PrivateDnsSettingsBridge.updatePrivateDnsBlockedByUnaffiliatedUsers(context, blocked)

    @VisibleForTesting
    internal fun setPrivateDnsBlockedByUnaffiliatedUsersForTest(blocked: Boolean) {
        PrivateDnsSettingsBridge.isUnaffiliatedBlockedCached = blocked
    }

    fun ensureBlackholeVpnActive(
        context: Context,
        alwaysOnPkg: String?,
        isLockdown: Boolean
    ): Pair<Boolean, Boolean> = AlwaysOnVpnPolicyBridge.ensureBlackholeVpnActive(context, alwaysOnPkg, isLockdown)

    internal fun isLocalVpnDnsStateConsistent(
        dpmMode: Int?,
        globalMode: String?,
        globalHost: String?,
        isUnaffiliatedBlocked: Boolean = isPrivateDnsBlockedByUnaffiliatedUsers,
        approvedPolicyHost: String? = null
    ): Boolean = PrivateDnsSettingsBridge.isLocalVpnDnsStateConsistent(
        dpmMode, globalMode, globalHost, isUnaffiliatedBlocked, approvedPolicyHost
    )

    internal fun isStandaloneDnsStateConsistent(
        dpmMode: Int?,
        dpmHost: String?,
        globalMode: String?,
        globalHost: String?,
        targetHost: String,
        isUnaffiliatedBlocked: Boolean = isPrivateDnsBlockedByUnaffiliatedUsers,
        isOfflinePreArmedPendingDpm: Boolean = false
    ): Boolean = PrivateDnsSettingsBridge.isStandaloneDnsStateConsistent(
        dpmMode, dpmHost, globalMode, globalHost, targetHost, isUnaffiliatedBlocked, isOfflinePreArmedPendingDpm
    )

    internal fun isDevicePhysicallyOffline(context: Context): Boolean =
        PhysicalNetworkResolver.isDevicePhysicallyOffline(context)

    internal fun preArmStandaloneDotFailClosed(
        context: Context,
        targetHost: String = DnsShield.getEffectiveDnsHost(context)
    ): Boolean = DnsFastHealEngine.preArmStandaloneDotFailClosed(context, targetHost)

    fun isLocalVpnExpectedActive(context: Context): Boolean =
        AlwaysOnVpnConsentHelper.isLocalVpnExpectedActive(context)

    fun tryFastHealGlobalPrivateDns(context: Context): DnsFastHealResult =
        DnsFastHealEngine.tryFastHealGlobalPrivateDns(context)

    @VisibleForTesting
    internal fun tryFastHealGlobalPrivateDnsInternal(
        sdkInt: Int = Build.VERSION_CODES.Q,
        hasActiveTransitions: () -> Boolean,
        isDeviceOwner: () -> Boolean,
        isLocalVpnExpected: () -> Boolean,
        getExpectedHost: () -> String,
        readGlobal: (String) -> String?,
        markInternalMutation: () -> Unit,
        writeGlobal: (String, String) -> Boolean,
        clearDnsRestorePending: () -> Unit = {},
        writePrivateDnsPair: ((mode: String, specifier: String) -> Boolean)? = null
    ): DnsFastHealResult = DnsFastHealEngine.tryFastHealGlobalPrivateDnsInternal(
        sdkInt, hasActiveTransitions, isDeviceOwner, isLocalVpnExpected,
        getExpectedHost, readGlobal, markInternalMutation, writeGlobal,
        clearDnsRestorePending, writePrivateDnsPair
    )

    fun isStandaloneDnsShieldActive(context: Context): Boolean =
        DnsShieldPolicyEvaluator.isStandaloneDnsShieldActive(context)

    fun isLocalVpnDnsModeHealthy(context: Context): Boolean =
        DnsShieldPolicyEvaluator.isLocalVpnDnsModeHealthy(context)

    fun restoreDnsShieldOnCurfewRelease(context: Context): Boolean =
        AlwaysOnVpnPolicyBridge.restoreDnsShieldOnCurfewRelease(context)

    fun ensureOpportunisticDnsForLocalVpn(context: Context): Boolean =
        AlwaysOnVpnPolicyBridge.ensureOpportunisticDnsForLocalVpn(context)

    fun ensureOpportunisticDnsForLocalVpn(
        context: Context,
        @Suppress("UNUSED_PARAMETER") dpm: DevicePolicyManager,
        @Suppress("UNUSED_PARAMETER") admin: ComponentName
    ): Boolean = AlwaysOnVpnPolicyBridge.ensureOpportunisticDnsForLocalVpn(context)

    fun applyDnsShieldPolicy(
        context: Context,
        @Suppress("UNUSED_PARAMETER") dpm: DevicePolicyManager,
        @Suppress("UNUSED_PARAMETER") admin: ComponentName,
        shieldId: String,
        targetHost: String
    ): ShieldResult = DnsShieldPolicyEvaluator.applyDnsShieldPolicy(context, shieldId, targetHost)

    fun checkDnsShieldStatus(
        context: Context,
        @Suppress("UNUSED_PARAMETER") dpm: DevicePolicyManager,
        @Suppress("UNUSED_PARAMETER") admin: ComponentName,
        targetHost: String
    ): ShieldStatus = DnsShieldPolicyEvaluator.checkDnsShieldStatus(context, targetHost)

    @VisibleForTesting
    internal fun applyStandaloneDotInternal(
        targetHost: String,
        currentDpmMode: Int,
        currentDpmHost: String?,
        globalMode: String?,
        globalHost: String?,
        isDeviceOwner: Boolean,
        markInternalMutation: () -> Unit,
        writeGlobal: (String, String) -> Boolean,
        setDpmPrivateDns: (String) -> Pair<Boolean, Int>,
        clearDnsRestorePending: () -> Unit = {},
        writePrivateDnsPair: ((mode: String, specifier: String) -> Boolean)? = null,
        isDeviceOffline: Boolean = false,
        markPendingDpmSync: (Boolean) -> Unit = {},
        readGlobalAfterDpm: (() -> Pair<String?, String?>)? = null
    ): Pair<Int, Boolean> = StandaloneDotEnforcer.applyStandaloneDotInternal(
        targetHost = targetHost,
        currentDpmMode = currentDpmMode,
        currentDpmHost = currentDpmHost,
        globalMode = globalMode,
        globalHost = globalHost,
        isDeviceOwner = isDeviceOwner,
        markInternalMutation = markInternalMutation,
        writeGlobal = writeGlobal,
        setDpmPrivateDns = setDpmPrivateDns,
        clearDnsRestorePending = clearDnsRestorePending,
        writePrivateDnsPair = writePrivateDnsPair,
        isDeviceOffline = isDeviceOffline,
        markPendingDpmSync = markPendingDpmSync,
        readGlobalAfterDpm = readGlobalAfterDpm
    )

    internal fun isVpnConsentGranted(context: Context): Boolean =
        AlwaysOnVpnConsentHelper.isVpnConsentGranted(context)

    fun reconcileNonBlackholeVpnAndDns(
        context: Context,
        requiredMode: CurfewBlackholeVpnService.VpnMode = CurfewBlackholeVpnService.evaluateRequiredMode(context),
        alwaysOnPkg: String? = SafeDpmGateway.getAlwaysOnVpnPackageBlocking(context, recordSuccess = false).getOrNull(),
        isLockdown: Boolean = SafeDpmGateway.isAlwaysOnVpnLockdownEnabledBlocking(context, recordSuccess = false).getOrNull() == true
    ): Boolean = AlwaysOnVpnPolicyBridge.reconcileNonBlackholeVpnAndDns(context, requiredMode, alwaysOnPkg, isLockdown)

    fun isBlackholeVpnLockdownEnforced(
        context: Context,
        @Suppress("UNUSED_PARAMETER") dpm: DevicePolicyManager,
        @Suppress("UNUSED_PARAMETER") admin: ComponentName
    ): Boolean = AlwaysOnVpnPolicyBridge.isBlackholeVpnLockdownEnforced(context)

    fun isLingeringVpnLockdownPresent(
        context: Context,
        @Suppress("UNUSED_PARAMETER") dpm: DevicePolicyManager,
        @Suppress("UNUSED_PARAMETER") admin: ComponentName
    ): Boolean = AlwaysOnVpnPolicyBridge.isLingeringVpnLockdownPresent(context)
}
