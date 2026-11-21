package com.drmohammed.antiporn.curfew

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.net.VpnService
import android.os.Build
import android.os.UserManager
import android.provider.Settings
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.annotation.VisibleForTesting
import com.drmohammed.antiporn.R
import com.drmohammed.antiporn.core.engine.ProtectionTransitionGuard
import com.drmohammed.antiporn.core.model.ShieldResult
import com.drmohammed.antiporn.core.model.ShieldStatus
import com.drmohammed.antiporn.shields.DnsShield
import com.drmohammed.antiporn.telemetry.BlackBoxRecorder
import com.drmohammed.antiporn.telemetry.DpmExecutionResult
import com.drmohammed.antiporn.telemetry.SafeDpmGateway
import timber.log.Timber

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

/**
 * Coordinates Always-On VPN lockdown and Private DNS state during curfew transitions
 * and shield verification without racing async TUN establishment.
 */
internal object CurfewVpnDnsBridge {

    private const val TAG = "AntiPorn_CurfewVpnBridge"
    private const val COMPAT_PRIVATE_DNS_MODE_UNKNOWN = 0
    private const val COMPAT_PRIVATE_DNS_MODE_OFF = 1
    private const val COMPAT_PRIVATE_DNS_MODE_OPPORTUNISTIC = 2
    private const val COMPAT_PRIVATE_DNS_MODE_PROVIDER_HOSTNAME = 3
    private const val COMPAT_PRIVATE_DNS_SET_NO_ERROR = 0
    internal const val COMPAT_PRIVATE_DNS_SET_ERROR_HOST_NOT_SERVING = 1
    private const val COMPAT_PRIVATE_DNS_SET_ERROR_FAILURE_SETTING = 2

    @Volatile
    private var directGlobalWriteDenied: Boolean = false

    internal fun isDirectGlobalWriteDenied(): Boolean = directGlobalWriteDenied

    internal fun hasActiveVerifiedFamilyDns(globalMode: String?, globalHost: String?): Boolean {
        return globalMode == "hostname" &&
            !globalHost.isNullOrBlank() &&
            com.drmohammed.antiporn.core.network.DnsFilteringValidator.isKnownFamilyPreset(globalHost)
    }

    @VisibleForTesting
    internal fun resetFastHealCircuitBreakerForTest() {
        directGlobalWriteDenied = false
    }

    @VisibleForTesting
    internal fun isDirectGlobalWriteDeniedForTest(): Boolean = directGlobalWriteDenied

    private fun privateDnsModeUnknown(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) DevicePolicyManager.PRIVATE_DNS_MODE_UNKNOWN
        else COMPAT_PRIVATE_DNS_MODE_UNKNOWN

    private fun privateDnsModeOff(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) DevicePolicyManager.PRIVATE_DNS_MODE_OFF
        else COMPAT_PRIVATE_DNS_MODE_OFF

    private fun privateDnsModeOpportunistic(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) DevicePolicyManager.PRIVATE_DNS_MODE_OPPORTUNISTIC
        else COMPAT_PRIVATE_DNS_MODE_OPPORTUNISTIC

    private fun privateDnsModeProviderHostname(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) DevicePolicyManager.PRIVATE_DNS_MODE_PROVIDER_HOSTNAME
        else COMPAT_PRIVATE_DNS_MODE_PROVIDER_HOSTNAME

    @Volatile
    private var lastAppliedBypassedPackages: Set<String>? = null

    @Volatile
    private var inMemoryPrivateDnsBlockedByUnaffiliatedUsers: Boolean? = null

    var isPrivateDnsBlockedByUnaffiliatedUsers: Boolean
        get() = inMemoryPrivateDnsBlockedByUnaffiliatedUsers ?: false
        internal set(value) {
            inMemoryPrivateDnsBlockedByUnaffiliatedUsers = value
        }

    fun isPrivateDnsBlockedByUnaffiliatedUsers(context: Context): Boolean {
        inMemoryPrivateDnsBlockedByUnaffiliatedUsers?.let { return it }
        val persisted = CurfewConfig.isPrivateDnsBlockedByUnaffiliatedUsers(context)
        if (persisted) {
            inMemoryPrivateDnsBlockedByUnaffiliatedUsers = true
            return true
        }
        val userManager = context.getSystemService(Context.USER_SERVICE) as? UserManager
        val hasMultiUsers = try {
            (userManager?.userProfiles?.size ?: 1) > 1
        } catch (_: Exception) {
            false
        }
        return hasMultiUsers
    }

    internal fun updatePrivateDnsBlockedByUnaffiliatedUsers(context: Context, blocked: Boolean) {
        inMemoryPrivateDnsBlockedByUnaffiliatedUsers = blocked
        CurfewConfig.setPrivateDnsBlockedByUnaffiliatedUsers(context, blocked)
    }

    @VisibleForTesting
    internal fun setPrivateDnsBlockedByUnaffiliatedUsersForTest(blocked: Boolean) {
        inMemoryPrivateDnsBlockedByUnaffiliatedUsers = blocked
    }

    private fun isUnaffiliatedUsersException(res: com.drmohammed.antiporn.telemetry.DpmExecutionResult<*>): Boolean {
        return res is com.drmohammed.antiporn.telemetry.DpmExecutionResult.Failure &&
            res.errorType == com.drmohammed.antiporn.telemetry.DpmErrorType.SECURITY_EXCEPTION &&
            res.message.contains("unaffiliated", ignoreCase = true)
    }

    fun ensureBlackholeVpnActive(
        context: Context,
        alwaysOnPkg: String?,
        isLockdown: Boolean
    ): Pair<Boolean, Boolean> {
        var changed = false
        var dpmOk = true
        try {
            val bypassedPackages = CurfewConfig.getVpnBypassedPackages(context)
            val bypassChanged = lastAppliedBypassedPackages != bypassedPackages
            if (alwaysOnPkg != context.packageName || !isLockdown || bypassChanged) {
                val res = SafeDpmGateway.setAlwaysOnVpnPackageBlocking(
                    context = context,
                    vpnPackage = context.packageName,
                    lockdown = true,
                    bypassedPackages = bypassedPackages
                )
                if (res.isSuccess) {
                    lastAppliedBypassedPackages = bypassedPackages
                    Log.i(TAG, "Always-on Blackhole VPN configured to ${context.packageName} with lockdown=true (bypassCount=${bypassedPackages.size})")
                    changed = true
                } else {
                    dpmOk = false
                    Log.e(TAG, "Failed configuring Always-On Blackhole VPN via SafeDpmGateway")
                }
            }
            if (dpmOk || isVpnConsentGranted(context)) {
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
                Log.w(TAG, "Skipping Blackhole VPN start because Always-On setup failed and VPN consent is not granted")
            }
            if (waitForAndVerifyVpnReady(context, CurfewBlackholeVpnService.VpnMode.CURFEW_BLACKHOLE)) {
                if (!ensureOpportunisticDnsForLocalVpn(context)) {
                    dpmOk = false
                    Log.w(TAG, "Failed ensuring opportunistic DNS while activating Blackhole VPN")
                }
            } else {
                dpmOk = false
                Log.w(TAG, "Blackhole VPN consent or TUN not ready; skipping opportunistic Private DNS downgrade")
            }
        } catch (e: Exception) {
            dpmOk = false
            Log.e(TAG, "Error configuring Always-On Blackhole VPN", e)
        }
        return changed to dpmOk
    }

    internal fun isLocalVpnDnsStateConsistent(
        dpmMode: Int?,
        globalMode: String?,
        globalHost: String?,
        isUnaffiliatedBlocked: Boolean = isPrivateDnsBlockedByUnaffiliatedUsers
    ): Boolean {
        val isUnaffiliatedFallback = isUnaffiliatedBlocked &&
            globalMode == "hostname" &&
            hasActiveVerifiedFamilyDns(globalMode, globalHost)
        val isGlobalHealthy = globalMode == "opportunistic" ||
            globalMode == "off" ||
            globalMode == null ||
            isUnaffiliatedFallback
        val isDpmHealthy = dpmMode == privateDnsModeOpportunistic() ||
            dpmMode == privateDnsModeOff() ||
            dpmMode == null ||
            dpmMode < 0 ||
            dpmMode == privateDnsModeUnknown() ||
            isUnaffiliatedBlocked
        return isGlobalHealthy && isDpmHealthy
    }

    internal fun isStandaloneDnsStateConsistent(
        dpmMode: Int?,
        dpmHost: String?,
        globalMode: String?,
        globalHost: String?,
        targetHost: String,
        isUnaffiliatedBlocked: Boolean = isPrivateDnsBlockedByUnaffiliatedUsers
    ): Boolean {
        if (targetHost.isBlank()) return false
        val globalMatches = globalMode == "hostname" &&
            globalHost?.equals(targetHost, ignoreCase = true) == true
        val dpmMatches = dpmMode == privateDnsModeProviderHostname() &&
            dpmHost?.equals(targetHost, ignoreCase = true) == true
        val dpmUnavailableOrFallback = dpmMode == null ||
            dpmMode < 0 ||
            dpmMode == privateDnsModeUnknown() ||
            isUnaffiliatedBlocked
        return globalMatches && (dpmMatches || dpmUnavailableOrFallback)
    }

    fun isLocalVpnExpectedActive(context: Context): Boolean {
        return CurfewBlackholeVpnService.evaluateRequiredMode(context) != CurfewBlackholeVpnService.VpnMode.IDLE &&
            isVpnConsentGranted(context)
    }

    /**
     * Zero-latency (~1-3ms) silent fast-path that restores `Settings.Global` Private DNS immediately
     * when external tampering is detected, with zero blocking DPM calls and zero polling.
     */
    fun tryFastHealGlobalPrivateDns(context: Context): DnsFastHealResult {
        return tryFastHealGlobalPrivateDnsInternal(
            sdkInt = Build.VERSION.SDK_INT,
            hasActiveTransitions = { ProtectionTransitionGuard.hasActiveTransitions() },
            isDeviceOwner = { SafeDpmGateway.isDeviceOwner(context) },
            isLocalVpnExpected = { isLocalVpnExpectedActive(context) },
            getExpectedHost = { DnsShield.getEffectiveDnsHost(context) },
            readGlobal = { key -> readGlobalSetting(context, key) },
            markInternalMutation = { ProtectionTransitionGuard.markInternalDnsMutation() },
            writeGlobal = { key, value -> Settings.Global.putString(context.contentResolver, key, value) },
            clearDnsRestorePending = {
                if (CurfewConfig.isDnsRestorePending(context)) {
                    CurfewConfig.setDnsRestorePending(context, false)
                }
            }
        )
    }

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
        clearDnsRestorePending: () -> Unit = {}
    ): DnsFastHealResult {
        if (sdkInt < Build.VERSION_CODES.Q) {
            return DnsFastHealResult(outcome = DnsFastHealOutcome.SKIPPED_UNSUPPORTED_SDK)
        }
        if (hasActiveTransitions()) {
            return DnsFastHealResult(outcome = DnsFastHealOutcome.SKIPPED_ACTIVE_TRANSITION)
        }
        if (!isDeviceOwner()) {
            return DnsFastHealResult(outcome = DnsFastHealOutcome.SKIPPED_NOT_DEVICE_OWNER)
        }
        if (isLocalVpnExpected()) {
            return DnsFastHealResult(outcome = DnsFastHealOutcome.SKIPPED_VPN_EXPECTED)
        }

        val expectedHost = getExpectedHost()
        if (expectedHost.isBlank()) {
            return DnsFastHealResult(
                outcome = DnsFastHealOutcome.FAILED_NEEDS_DPM,
                expectedHost = expectedHost,
                errorMessage = "Effective DNS host is blank"
            )
        }

        val globalMode = readGlobal("private_dns_mode")
        val globalHost = readGlobal("private_dns_specifier")

        // Check compliance FIRST to prevent any ContentObserver feedback loop.
        if (globalMode == "hostname" && globalHost?.equals(expectedHost, ignoreCase = true) == true) {
            return DnsFastHealResult(
                outcome = DnsFastHealOutcome.ALREADY_COMPLIANT,
                observedMode = globalMode,
                observedHost = globalHost,
                expectedHost = expectedHost
            )
        }

        if (directGlobalWriteDenied) {
            return DnsFastHealResult(
                outcome = DnsFastHealOutcome.PERMISSION_DENIED_NEEDS_DPM,
                observedMode = globalMode,
                observedHost = globalHost,
                expectedHost = expectedHost,
                errorMessage = "Direct Settings.Global write circuit breaker open"
            )
        }

        val startNanos = System.nanoTime()
        return try {
            markInternalMutation()
            val modeWritten = writeGlobal("private_dns_mode", "hostname")
            val hostWritten = writeGlobal("private_dns_specifier", expectedHost)
            val latencyMs = ((System.nanoTime() - startNanos) / 1_000_000L).coerceAtLeast(0L)
            if (!modeWritten || !hostWritten) {
                safeLogW("Fast-heal Settings.Global.putString returned false (mode=$modeWritten, host=$hostWritten)")
                return DnsFastHealResult(
                    outcome = DnsFastHealOutcome.FAILED_NEEDS_DPM,
                    observedMode = globalMode,
                    observedHost = globalHost,
                    expectedHost = expectedHost,
                    latencyMs = latencyMs,
                    errorMessage = "Settings.Global.putString returned false (mode=$modeWritten, host=$hostWritten)"
                )
            }
            clearDnsRestorePending()
            safeLogI("Fast-healed Private DNS via Settings.Global to $expectedHost in ${latencyMs}ms (was mode=$globalMode, host=$globalHost)")
            DnsFastHealResult(
                outcome = DnsFastHealOutcome.HEALED_DIRECT,
                observedMode = globalMode,
                observedHost = globalHost,
                expectedHost = expectedHost,
                latencyMs = latencyMs
            )
        } catch (e: SecurityException) {
            directGlobalWriteDenied = true
            val latencyMs = ((System.nanoTime() - startNanos) / 1_000_000L).coerceAtLeast(0L)
            safeLogW("Direct Settings.Global private DNS write denied (SecurityException); tripping circuit breaker", e)
            DnsFastHealResult(
                outcome = DnsFastHealOutcome.PERMISSION_DENIED_NEEDS_DPM,
                observedMode = globalMode,
                observedHost = globalHost,
                expectedHost = expectedHost,
                latencyMs = latencyMs,
                errorMessage = e.message ?: e.javaClass.simpleName
            )
        } catch (e: Exception) {
            val latencyMs = ((System.nanoTime() - startNanos) / 1_000_000L).coerceAtLeast(0L)
            safeLogW("Direct Settings.Global private DNS fast-heal failed; delegating to DPM fast-path", e)
            DnsFastHealResult(
                outcome = DnsFastHealOutcome.FAILED_NEEDS_DPM,
                observedMode = globalMode,
                observedHost = globalHost,
                expectedHost = expectedHost,
                latencyMs = latencyMs,
                errorMessage = e.message ?: e.javaClass.simpleName
            )
        }
    }

    fun isStandaloneDnsShieldActive(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return true
        val targetHost = DnsShield.getEffectiveDnsHost(context)
        val dpmMode = SafeDpmGateway.getGlobalPrivateDnsModeBlocking(context, recordSuccess = false).getOrNull()
        val dpmHost = SafeDpmGateway.getGlobalPrivateDnsHostBlocking(context, recordSuccess = false).getOrNull()
        val globalMode = readGlobalSetting(context, "private_dns_mode")
        val globalHost = readGlobalSetting(context, "private_dns_specifier")
        val unaffiliatedBlocked = isPrivateDnsBlockedByUnaffiliatedUsers(context)
        return isStandaloneDnsStateConsistent(
            dpmMode = dpmMode,
            dpmHost = dpmHost,
            globalMode = globalMode,
            globalHost = globalHost,
            targetHost = targetHost,
            isUnaffiliatedBlocked = unaffiliatedBlocked
        )
    }

    fun isLocalVpnDnsModeHealthy(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return true
        val dpmMode = SafeDpmGateway.getGlobalPrivateDnsModeBlocking(context, recordSuccess = false).getOrNull()
        val globalMode = readGlobalSetting(context, "private_dns_mode")
        val globalHost = readGlobalSetting(context, "private_dns_specifier")
        val unaffiliatedBlocked = isPrivateDnsBlockedByUnaffiliatedUsers(context)
        return isLocalVpnDnsStateConsistent(
            dpmMode = dpmMode,
            globalMode = globalMode,
            globalHost = globalHost,
            isUnaffiliatedBlocked = unaffiliatedBlocked
        )
    }

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

    fun ensureOpportunisticDnsForLocalVpn(
        context: Context,
        @Suppress("UNUSED_PARAMETER") dpm: DevicePolicyManager,
        @Suppress("UNUSED_PARAMETER") admin: ComponentName
    ): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return true
        if (!SafeDpmGateway.isDeviceOwner(context)) return false
        return ensureOpportunisticDnsForLocalVpnInternal(context)
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun ensureOpportunisticDnsForLocalVpnInternal(context: Context): Boolean {
        com.drmohammed.antiporn.core.engine.ProtectionTransitionGuard.markInternalDnsMutation()
        var dpmOk = true
        val dpmMode = readDpmPrivateDnsMode(context)
        if (dpmMode != DevicePolicyManager.PRIVATE_DNS_MODE_OPPORTUNISTIC) {
            val dpmRes = SafeDpmGateway.setGlobalPrivateDnsOpportunisticBlocking(
                context = context,
                tag = BlackBoxRecorder.TAG_SHIELD
            )
            if (isUnaffiliatedUsersException(dpmRes)) {
                updatePrivateDnsBlockedByUnaffiliatedUsers(context, true)
                BlackBoxRecorder.recordShield(
                    context = context,
                    shieldId = DnsShield.id,
                    isSuccess = false,
                    details = "setGlobalPrivateDnsOpportunistic blocked: unaffiliated users"
                )
            } else if (dpmRes.isSuccess) {
                updatePrivateDnsBlockedByUnaffiliatedUsers(context, false)
            }
            if (!dpmRes.isSuccess || dpmRes.getOrNull() != DevicePolicyManager.PRIVATE_DNS_SET_NO_ERROR) {
                dpmOk = false
                Log.w(TAG, "SafeDpmGateway setGlobalPrivateDnsOpportunisticBlocking failed")
            }
        }
        val globalMode = readGlobalSetting(context, "private_dns_mode")
        if (!dpmOk || globalMode == "hostname") {
            try {
                val modeOk = Settings.Global.putString(context.contentResolver, "private_dns_mode", "opportunistic")
                Settings.Global.putString(context.contentResolver, "private_dns_specifier", "")
                if (modeOk) {
                    updatePrivateDnsBlockedByUnaffiliatedUsers(context, false)
                }
            } catch (e: SecurityException) {
                directGlobalWriteDenied = true
                Timber.tag(TAG).w(e, "Best-effort Settings.Global opportunistic fallback blocked by SecurityException")
            } catch (e: Exception) {
                Log.w(TAG, "Could not write Settings.Global opportunistic mode for Local VPN", e)
            }
        }
        lockDnsAndNetworkRestrictions(context)
        val verifiedMode = readDpmPrivateDnsMode(context)
        val verifiedGlobalMode = readGlobalSetting(context, "private_dns_mode")
        val verifiedGlobalHost = readGlobalSetting(context, "private_dns_specifier")
        val unaffiliatedBlocked = isPrivateDnsBlockedByUnaffiliatedUsers(context)
        return isLocalVpnDnsStateConsistent(
            dpmMode = verifiedMode,
            globalMode = verifiedGlobalMode,
            globalHost = verifiedGlobalHost,
            isUnaffiliatedBlocked = unaffiliatedBlocked
        )
    }

    fun applyDnsShieldPolicy(
        context: Context,
        dpm: DevicePolicyManager,
        admin: ComponentName,
        shieldId: String,
        targetHost: String
    ): ShieldResult {
        val requiredMode = CurfewBlackholeVpnService.evaluateRequiredMode(context)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q && requiredMode == CurfewBlackholeVpnService.VpnMode.IDLE) {
            return ShieldResult(
                shieldId = shieldId,
                success = false,
                message = context.getString(R.string.shield_dns_unsupported)
            )
        }
        if (requiredMode != CurfewBlackholeVpnService.VpnMode.IDLE) {
            if (!isVpnConsentGranted(context) && SafeDpmGateway.isDeviceOwner(context)) {
                val lockdown = requiredMode == CurfewBlackholeVpnService.VpnMode.CURFEW_BLACKHOLE
                val bypassed = if (lockdown) CurfewConfig.getVpnBypassedPackages(context) else null
                SafeDpmGateway.setAlwaysOnVpnPackageBlocking(
                    context = context,
                    vpnPackage = context.packageName,
                    lockdown = lockdown,
                    bypassedPackages = bypassed
                )
            }
            val hasVpnConsent = isVpnConsentGranted(context)
            if (!hasVpnConsent) {
                Log.w(TAG, "VpnService.prepare returned non-null; refusing opportunistic DoT downgrade and preserving standalone DoT")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    applyStandaloneDot(context, targetHost)
                }
                lockDnsAndNetworkRestrictions(context)
                return ShieldResult(
                    shieldId = shieldId,
                    success = false,
                    message = context.getString(R.string.card_dns_inactive),
                    statusCode = COMPAT_PRIVATE_DNS_SET_ERROR_FAILURE_SETTING
                )
            }

            try {
                CurfewBlackholeVpnService.syncPolicy(context)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to sync Unified VPN policy from DnsShield", e)
            }

            val vpnReady = waitForAndVerifyVpnReady(context, requiredMode)
            if (!vpnReady) {
                Log.w(TAG, "VPN TUN verification failed; preserving standalone DoT instead of opportunistic downgrade")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    applyStandaloneDot(context, targetHost)
                }
                lockDnsAndNetworkRestrictions(context)
                return ShieldResult(
                    shieldId = shieldId,
                    success = false,
                    message = context.getString(R.string.card_dns_inactive),
                    statusCode = COMPAT_PRIVATE_DNS_SET_ERROR_FAILURE_SETTING
                )
            }

            val opportunisticOk = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ensureOpportunisticDnsForLocalVpnInternal(context)
            } else {
                lockDnsAndNetworkRestrictions(context)
                true
            }
            return if (opportunisticOk) {
                val currentGlobalMode = readGlobalSetting(context, "private_dns_mode")
                val currentGlobalHost = readGlobalSetting(context, "private_dns_specifier")
                val unaffiliatedBlocked = isPrivateDnsBlockedByUnaffiliatedUsers(context)
                val isUnaffiliatedFallback = unaffiliatedBlocked &&
                    currentGlobalMode == "hostname" &&
                    hasActiveVerifiedFamilyDns(currentGlobalMode, currentGlobalHost)
                val message = if (isUnaffiliatedFallback) {
                    val currentHost = currentGlobalHost ?: DnsShield.DEFAULT_DNS_HOST
                    context.getString(R.string.shield_dns_local_filter_unaffiliated_notice, currentHost)
                } else {
                    context.getString(R.string.shield_dns_local_filter_active)
                }
                ShieldResult(
                    shieldId = shieldId,
                    success = true,
                    message = message,
                    statusCode = COMPAT_PRIVATE_DNS_SET_NO_ERROR
                )
            } else {
                ShieldResult(
                    shieldId = shieldId,
                    success = false,
                    message = context.getString(R.string.card_dns_inactive),
                    statusCode = COMPAT_PRIVATE_DNS_SET_ERROR_FAILURE_SETTING
                )
            }
        }

        val (dnsSetResult, usedFallback) = applyStandaloneDot(context, targetHost)
        lockDnsAndNetworkRestrictions(context)
        val isDpmNoError = dnsSetResult == DevicePolicyManager.PRIVATE_DNS_SET_NO_ERROR
        val currentGlobalMode = readGlobalSetting(context, "private_dns_mode")
        val currentGlobalHost = readGlobalSetting(context, "private_dns_specifier")
        val isFamilyDnsActive = hasActiveVerifiedFamilyDns(currentGlobalMode, currentGlobalHost)
        val unaffiliatedBlocked = isPrivateDnsBlockedByUnaffiliatedUsers(context)
        val isUnaffiliatedFallback = unaffiliatedBlocked && isFamilyDnsActive
        val isUnaffiliatedError = unaffiliatedBlocked && !isFamilyDnsActive
        val success = isDpmNoError || usedFallback || isUnaffiliatedFallback
        val msg = when {
            isDpmNoError && !usedFallback -> context.getString(R.string.shield_dns_success)
            usedFallback -> context.getString(R.string.shield_dns_fallback_active)
            isUnaffiliatedFallback -> {
                val currentHost = currentGlobalHost ?: DnsShield.DEFAULT_DNS_HOST
                context.getString(R.string.shield_dns_unaffiliated_users_notice, currentHost, targetHost)
            }
            isUnaffiliatedError -> context.getString(R.string.shield_dns_unaffiliated_users_error)
            else -> context.getString(R.string.card_dns_inactive)
        }
        val statusCode = if (isUnaffiliatedError) {
            COMPAT_PRIVATE_DNS_SET_ERROR_FAILURE_SETTING
        } else {
            dnsSetResult
        }
        return ShieldResult(
            shieldId = shieldId,
            success = success,
            message = msg,
            statusCode = statusCode
        )
    }

    fun checkDnsShieldStatus(
        context: Context,
        @Suppress("UNUSED_PARAMETER") dpm: DevicePolicyManager,
        @Suppress("UNUSED_PARAMETER") admin: ComponentName,
        targetHost: String
    ): ShieldStatus {
        val currentRestrictions = SafeDpmGateway.getUserRestrictionsBlocking(
            context = context,
            tag = BlackBoxRecorder.TAG_SHIELD,
            recordSuccess = false
        ).getOrNull()
        val isDnsLocked = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            currentRestrictions?.getBoolean(UserManager.DISALLOW_CONFIG_PRIVATE_DNS, false) == true
        } else {
            true // Private DNS setting does not exist on Android < Q; Local VPN DNS handles enforcement
        }
        val globalMode = readGlobalSetting(context, "private_dns_mode")
        val globalSpecifier = readGlobalSetting(context, "private_dns_specifier")
        val modeFromDpm = readDpmPrivateDnsMode(context)

        val requiredVpnMode = CurfewBlackholeVpnService.evaluateRequiredMode(context)
        if (requiredVpnMode != CurfewBlackholeVpnService.VpnMode.IDLE) {
            if (!isVpnConsentGranted(context)) {
                return ShieldStatus.NeedsEnforcement(context.getString(R.string.shield_dns_local_filter_needs_sync))
            }
            val unaffiliatedBlocked = isPrivateDnsBlockedByUnaffiliatedUsers(context)
            val isHealthy = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                isLocalVpnDnsStateConsistent(
                    dpmMode = modeFromDpm,
                    globalMode = globalMode,
                    globalHost = globalSpecifier,
                    isUnaffiliatedBlocked = unaffiliatedBlocked
                )
            } else {
                globalMode != "hostname"
            }
            val isVpnServiceUp = CurfewBlackholeVpnService.isTunnelHealthy(requiredVpnMode)
            return if (isHealthy && isDnsLocked && isVpnServiceUp) {
                val isUnaffiliatedFallback = unaffiliatedBlocked &&
                    globalMode == "hostname" &&
                    hasActiveVerifiedFamilyDns(globalMode, globalSpecifier)
                val message = if (isUnaffiliatedFallback) {
                    val currentHost = globalSpecifier ?: DnsShield.DEFAULT_DNS_HOST
                    context.getString(R.string.shield_dns_local_filter_unaffiliated_notice, currentHost)
                } else {
                    context.getString(R.string.shield_dns_local_filter_active)
                }
                ShieldStatus.Active(message)
            } else {
                ShieldStatus.NeedsEnforcement(context.getString(R.string.shield_dns_local_filter_needs_sync))
            }
        }

        val hostFromDpm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            SafeDpmGateway.getGlobalPrivateDnsHostBlocking(
                context = context,
                tag = BlackBoxRecorder.TAG_SHIELD,
                recordSuccess = false
            ).getOrNull()
        } else {
            null
        }
        val unaffiliatedBlocked = isPrivateDnsBlockedByUnaffiliatedUsers(context)
        val isConsistent = isStandaloneDnsStateConsistent(
            dpmMode = modeFromDpm,
            dpmHost = hostFromDpm,
            globalMode = globalMode,
            globalHost = globalSpecifier,
            targetHost = targetHost,
            isUnaffiliatedBlocked = unaffiliatedBlocked
        )
        return if (isConsistent && isDnsLocked) {
            val activeHost = globalSpecifier?.takeIf { it.isNotBlank() } ?: hostFromDpm ?: targetHost
            ShieldStatus.Active(context.getString(R.string.card_dns_active, activeHost))
        } else if (unaffiliatedBlocked && isDnsLocked) {
            if (hasActiveVerifiedFamilyDns(globalMode, globalSpecifier)) {
                val currentActiveHost = globalSpecifier!!
                val explanation = context.getString(R.string.shield_dns_unaffiliated_users_notice, currentActiveHost, targetHost)
                ShieldStatus.Active(explanation)
            } else {
                ShieldStatus.NeedsEnforcement(context.getString(R.string.shield_dns_unaffiliated_users_error))
            }
        } else {
            val currentHostDisplay = globalSpecifier?.takeIf { it.isNotBlank() }
                ?: hostFromDpm
                ?: context.getString(R.string.default_dns_text)
            ShieldStatus.NeedsEnforcement(context.getString(R.string.card_dns_pending, currentHostDisplay, targetHost))
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun applyStandaloneDot(
        context: Context,
        targetHost: String
    ): Pair<Int, Boolean> {
        val currentMode = readDpmPrivateDnsMode(context)
        val currentHost = SafeDpmGateway.getGlobalPrivateDnsHostBlocking(
            context = context,
            tag = BlackBoxRecorder.TAG_SHIELD,
            recordSuccess = false
        ).getOrNull()
        val globalMode = readGlobalSetting(context, "private_dns_mode")
        val globalHost = readGlobalSetting(context, "private_dns_specifier")

        val (dnsSetResult, usedFallback) = applyStandaloneDotInternal(
            targetHost = targetHost,
            currentDpmMode = currentMode,
            currentDpmHost = currentHost,
            globalMode = globalMode,
            globalHost = globalHost,
            isDeviceOwner = SafeDpmGateway.isDeviceOwner(context),
            markInternalMutation = { ProtectionTransitionGuard.markInternalDnsMutation() },
            writeGlobal = { key, value -> Settings.Global.putString(context.contentResolver, key, value) },
            setDpmPrivateDns = { host ->
                val dpmRes = SafeDpmGateway.setGlobalPrivateDnsBlocking(
                    context = context,
                    host = host,
                    tag = BlackBoxRecorder.TAG_SHIELD
                )
                if (isUnaffiliatedUsersException(dpmRes)) {
                    updatePrivateDnsBlockedByUnaffiliatedUsers(context, true)
                    BlackBoxRecorder.recordShield(
                        context = context,
                        shieldId = DnsShield.id,
                        isSuccess = false,
                        details = "setGlobalPrivateDnsBlocking blocked: unaffiliated users host=$host"
                    )
                } else if (dpmRes.isSuccess) {
                    updatePrivateDnsBlockedByUnaffiliatedUsers(context, false)
                }
                val resultCode = when (dpmRes) {
                    is DpmExecutionResult.Success -> dpmRes.data
                    is DpmExecutionResult.Failure -> COMPAT_PRIVATE_DNS_SET_ERROR_FAILURE_SETTING
                }
                dpmRes.isSuccess to resultCode
            },
            clearDnsRestorePending = {
                if (CurfewConfig.isDnsRestorePending(context)) {
                    CurfewConfig.setDnsRestorePending(context, false)
                }
            }
        )

        if (dnsSetResult != COMPAT_PRIVATE_DNS_SET_NO_ERROR) {
            BlackBoxRecorder.recordShield(
                context = context,
                shieldId = DnsShield.id,
                isSuccess = false,
                details = "setGlobalPrivateDns returned error code=$dnsSetResult host=$targetHost"
            )
        }

        if (dnsSetResult == COMPAT_PRIVATE_DNS_SET_ERROR_HOST_NOT_SERVING) {
            safeLogW("applyStandaloneDot: host $targetHost is NOT serving (error 1); notifying DnsResilienceManager")
            com.drmohammed.antiporn.core.network.dns.DnsResilienceManager.onDpmHostNotServing(context, targetHost)
        }

        return dnsSetResult to usedFallback
    }

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
        clearDnsRestorePending: () -> Unit = {}
    ): Pair<Int, Boolean> {
        markInternalMutation()
        var dnsSetResult = COMPAT_PRIVATE_DNS_SET_NO_ERROR
        var usedFallback = false

        val dpmMatches = currentDpmMode == privateDnsModeProviderHostname() &&
            currentDpmHost?.equals(targetHost, ignoreCase = true) == true
        val globalMatches = globalMode == "hostname" &&
            globalHost?.equals(targetHost, ignoreCase = true) == true

        if (!dpmMatches || !globalMatches) {
            var dpmSucceeded = dpmMatches

            if (isDeviceOwner) {
                // Step 1: When Device Owner, invoke DPM FIRST to validate the host
                if (!dpmMatches) {
                    val (dpmOk, resultCode) = setDpmPrivateDns(targetHost)
                    dnsSetResult = resultCode
                    safeLogI("setGlobalPrivateDnsBlocking to $targetHost, result: $dnsSetResult (ok=$dpmOk)")
                    dpmSucceeded = dpmOk && dnsSetResult == COMPAT_PRIVATE_DNS_SET_NO_ERROR
                }

                // Step 2: Handle Settings.Global write based on DPM result
                if (dnsSetResult == COMPAT_PRIVATE_DNS_SET_ERROR_HOST_NOT_SERVING) {
                    // CRITICAL INVARIANT: Host is NOT serving on port 853; NEVER write to Settings.Global!
                    usedFallback = false
                } else if (!globalMatches && (dpmSucceeded || dnsSetResult == COMPAT_PRIVATE_DNS_SET_ERROR_FAILURE_SETTING)) {
                    // Write Settings.Global if DPM succeeded OR if DPM failed with OEM error 2 (fallback)
                    if (!directGlobalWriteDenied) {
                        try {
                            val modeOk = writeGlobal("private_dns_mode", "hostname")
                            val hostOk = writeGlobal("private_dns_specifier", targetHost)
                            val globalWriteSucceeded = modeOk && hostOk
                            if (globalWriteSucceeded) {
                                safeLogI("Enforced private DNS via Settings.Global for $targetHost (dpmSucceeded=$dpmSucceeded)")
                                isPrivateDnsBlockedByUnaffiliatedUsers = false
                                if (!dpmSucceeded) {
                                    usedFallback = true
                                }
                            } else {
                                safeLogW("Settings.Global.putString returned false for private DNS ($targetHost)")
                            }
                        } catch (e: SecurityException) {
                            directGlobalWriteDenied = true
                            safeLogW("SecurityException writing Settings.Global for private DNS; circuit breaker tripped", e)
                        } catch (e: Exception) {
                            safeLogW("Could not write Settings.Global for private DNS", e)
                        }
                    } else {
                        safeLogD("Skipping Settings.Global write in applyStandaloneDot (circuit breaker active)")
                    }
                } else if (!dpmSucceeded && !globalMatches && dnsSetResult == COMPAT_PRIVATE_DNS_SET_NO_ERROR) {
                    dnsSetResult = COMPAT_PRIVATE_DNS_SET_ERROR_FAILURE_SETTING
                }
            } else {
                // Not Device Owner: fallback to Settings.Global direct write (e.g. ADB-provisioned)
                if (!globalMatches) {
                    if (!directGlobalWriteDenied) {
                        try {
                            val modeOk = writeGlobal("private_dns_mode", "hostname")
                            val hostOk = writeGlobal("private_dns_specifier", targetHost)
                            if (modeOk && hostOk) {
                                safeLogI("Enforced private DNS via Settings.Global directly (non-DO) to $targetHost")
                                usedFallback = true
                            }
                        } catch (e: SecurityException) {
                            directGlobalWriteDenied = true
                            safeLogW("SecurityException writing Settings.Global (non-DO)", e)
                        } catch (e: Exception) {
                            safeLogW("Could not write Settings.Global (non-DO)", e)
                        }
                    }
                }
                dnsSetResult = COMPAT_PRIVATE_DNS_SET_ERROR_FAILURE_SETTING
            }
        } else {
            safeLogD("Private DNS already set to $targetHost via both DPM and Settings.Global, skipping redundant write")
        }

        val isSuccess = (dnsSetResult == COMPAT_PRIVATE_DNS_SET_NO_ERROR && !usedFallback) ||
            (usedFallback && dnsSetResult != COMPAT_PRIVATE_DNS_SET_ERROR_HOST_NOT_SERVING) ||
            (dpmMatches && globalMatches)
        if (isSuccess) {
            clearDnsRestorePending()
        }
        return dnsSetResult to usedFallback
    }

    private fun lockDnsAndNetworkRestrictions(context: Context) {
        val restrictions = SafeDpmGateway.getUserRestrictionsBlocking(
            context = context,
            tag = BlackBoxRecorder.TAG_SHIELD,
            recordSuccess = false
        ).getOrNull()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            restrictions?.getBoolean(UserManager.DISALLOW_CONFIG_PRIVATE_DNS, false) != true
        ) {
            val res = SafeDpmGateway.addUserRestrictionBlocking(
                context = context,
                userRestriction = UserManager.DISALLOW_CONFIG_PRIVATE_DNS,
                tag = BlackBoxRecorder.TAG_SHIELD
            )
            if (!res.isSuccess) {
                Log.w(TAG, "Failed enforcing DISALLOW_CONFIG_PRIVATE_DNS via SafeDpmGateway")
            }
        }
        if (restrictions?.getBoolean(UserManager.DISALLOW_NETWORK_RESET, false) != true) {
            val res = SafeDpmGateway.addUserRestrictionBlocking(
                context = context,
                userRestriction = UserManager.DISALLOW_NETWORK_RESET,
                tag = BlackBoxRecorder.TAG_SHIELD
            )
            if (!res.isSuccess) {
                Log.w(TAG, "Failed enforcing DISALLOW_NETWORK_RESET via SafeDpmGateway")
            }
        }
    }

    internal fun isVpnConsentGranted(context: Context): Boolean {
        return try {
            VpnService.prepare(context) == null
        } catch (e: Exception) {
            Log.w(TAG, "VpnService.prepare check failed", e)
            false
        }
    }

    private fun waitForAndVerifyVpnReady(
        context: Context,
        expectedMode: CurfewBlackholeVpnService.VpnMode = CurfewBlackholeVpnService.evaluateRequiredMode(context)
    ): Boolean {
        if (!isVpnConsentGranted(context)) return false
        val ready = if (CurfewBlackholeVpnService.isTunnelHealthy(expectedMode)) {
            true
        } else {
            CurfewBlackholeVpnService.awaitTunnelReadyBlocking(expectedMode = expectedMode)
        }
        return ready &&
            isVpnConsentGranted(context) &&
            CurfewBlackholeVpnService.isTunnelHealthy(expectedMode)
    }

    private fun readDpmPrivateDnsMode(context: Context): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return -1
        return SafeDpmGateway.getGlobalPrivateDnsModeBlocking(
            context = context,
            tag = BlackBoxRecorder.TAG_SHIELD,
            recordSuccess = false
        ).getOrNull() ?: -1
    }

    fun reconcileNonBlackholeVpnAndDns(
        context: Context,
        requiredMode: CurfewBlackholeVpnService.VpnMode = CurfewBlackholeVpnService.evaluateRequiredMode(context),
        alwaysOnPkg: String? = SafeDpmGateway.getAlwaysOnVpnPackageBlocking(context, recordSuccess = false).getOrNull(),
        isLockdown: Boolean = SafeDpmGateway.isAlwaysOnVpnLockdownEnabledBlocking(context, recordSuccess = false).getOrNull() == true
    ): Boolean {
        return try {
            var allOk = true
            lastAppliedBypassedPackages = null
            if (requiredMode == CurfewBlackholeVpnService.VpnMode.DNS_FILTERING_ONLY) {
                if (alwaysOnPkg != context.packageName || isLockdown) {
                    val vpnRes = SafeDpmGateway.setAlwaysOnVpnPackageBlocking(context, context.packageName, false)
                    if (!vpnRes.isSuccess) {
                        Log.e(TAG, "Failed resetting Always-on VPN lockdown=false for DNS filtering mode")
                        allOk = false
                    } else {
                        Log.i(TAG, "Always-on VPN configured to DNS-only filtering mode (lockdown=false)")
                    }
                }
                if (!CurfewBlackholeVpnService.isServiceOrTunnelActive() ||
                    CurfewBlackholeVpnService.currentMode != CurfewBlackholeVpnService.VpnMode.DNS_FILTERING_ONLY
                ) {
                    CurfewBlackholeVpnService.syncPolicy(context)
                }
                if (!waitForAndVerifyVpnReady(context, requiredMode)) {
                    allOk = false
                } else if (!isLocalVpnDnsModeHealthy(context)) {
                    if (!ensureOpportunisticDnsForLocalVpn(context)) {
                        allOk = false
                    }
                }
            } else {
                if (alwaysOnPkg == context.packageName || (alwaysOnPkg == null && isLockdown)) {
                    val vpnRes = SafeDpmGateway.setAlwaysOnVpnPackageBlocking(context, null, false)
                    if (!vpnRes.isSuccess) {
                        Log.e(TAG, "Failed clearing Always-on Blackhole VPN via SafeDpmGateway")
                        allOk = false
                    } else {
                        Log.i(TAG, "Always-on Blackhole VPN released via SafeDpmGateway")
                    }
                }
                var vpnWasTornDown = false
                if (CurfewBlackholeVpnService.isServiceOrTunnelActive()) {
                    CurfewBlackholeVpnService.forceReleaseVpnAndAwait(context, 2000L)
                    vpnWasTornDown = true
                }
                if (vpnWasTornDown || !isStandaloneDnsShieldActive(context)) {
                    if (!restoreDnsShieldOnCurfewRelease(context)) {
                        allOk = false
                    }
                }
            }
            Log.i(TAG, "VPN Blackhole is disabled and network is released (ok=$allOk).")
            allOk
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing Always-On Blackhole VPN", e)
            false
        }
    }

    fun isBlackholeVpnLockdownEnforced(
        context: Context,
        @Suppress("UNUSED_PARAMETER") dpm: DevicePolicyManager,
        @Suppress("UNUSED_PARAMETER") admin: ComponentName
    ): Boolean {
        return try {
            val currentPkg = SafeDpmGateway.getAlwaysOnVpnPackageBlocking(
                context = context,
                tag = BlackBoxRecorder.TAG_SHIELD,
                recordSuccess = false
            ).getOrNull()
            val isPkgMatch = currentPkg == context.packageName
            val isLockdown = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                SafeDpmGateway.isAlwaysOnVpnLockdownEnabledBlocking(
                    context = context,
                    tag = BlackBoxRecorder.TAG_SHIELD,
                    recordSuccess = false
                ).getOrNull() ?: false
            } else {
                true
            }
            isPkgMatch && isLockdown
        } catch (e: Exception) {
            Log.w(TAG, "Failed to query always-on VPN package during curfew check", e)
            false
        }
    }

    fun isLingeringVpnLockdownPresent(
        context: Context,
        @Suppress("UNUSED_PARAMETER") dpm: DevicePolicyManager,
        @Suppress("UNUSED_PARAMETER") admin: ComponentName
    ): Boolean {
        return try {
            val isBlackholeRunning = CurfewBlackholeVpnService.isServiceOrTunnelActive() &&
                CurfewBlackholeVpnService.currentMode == CurfewBlackholeVpnService.VpnMode.CURFEW_BLACKHOLE
            val currentPkg = SafeDpmGateway.getAlwaysOnVpnPackageBlocking(
                context = context,
                tag = BlackBoxRecorder.TAG_SHIELD,
                recordSuccess = false
            ).getOrNull()
            val isVpnLingeringWhenIdle = CurfewBlackholeVpnService.evaluateRequiredMode(context) == CurfewBlackholeVpnService.VpnMode.IDLE &&
                currentPkg == context.packageName
            val isLockdownActive = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                SafeDpmGateway.isAlwaysOnVpnLockdownEnabledBlocking(
                    context = context,
                    tag = BlackBoxRecorder.TAG_SHIELD,
                    recordSuccess = false
                ).getOrNull() ?: false
            } else {
                false
            }
            isBlackholeRunning || isVpnLingeringWhenIdle || isLockdownActive
        } catch (e: Exception) {
            Log.w(TAG, "Failed to query always-on VPN lockdown during release check", e)
            false
        }
    }

    private fun readGlobalSetting(context: Context, key: String): String? {
        return try {
            Settings.Global.getString(context.contentResolver, key)
        } catch (e: Exception) {
            safeLogW("Failed reading Settings.Global key=$key", e)
            null
        }
    }

    private fun safeLogI(message: String) {
        try {
            Log.i(TAG, message)
        } catch (_: RuntimeException) {
            // Allow pure JUnit tests where android.util.Log is not mocked
        }
    }

    private fun safeLogD(message: String) {
        try {
            Log.d(TAG, message)
        } catch (_: RuntimeException) {
            // Allow pure JUnit tests where android.util.Log is not mocked
        }
    }

    private fun safeLogW(message: String, throwable: Throwable? = null) {
        try {
            if (throwable != null) {
                Log.w(TAG, message, throwable)
            } else {
                Log.w(TAG, message)
            }
        } catch (_: RuntimeException) {
            // Allow pure JUnit tests where android.util.Log is not mocked
        }
    }
}
