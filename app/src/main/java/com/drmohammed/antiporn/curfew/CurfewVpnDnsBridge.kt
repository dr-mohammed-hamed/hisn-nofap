package com.drmohammed.antiporn.curfew

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.net.VpnService
import android.os.Build
import android.os.Looper
import android.os.SystemClock
import android.os.UserManager
import android.provider.Settings
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.annotation.VisibleForTesting
import com.drmohammed.antiporn.R
import com.drmohammed.antiporn.core.engine.ProtectionTransitionGuard
import com.drmohammed.antiporn.core.engine.SecondaryProfileDetector
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

    private const val UNAFFILIATED_REPROBE_INTERVAL_MS = 5 * 60 * 1_000L

    @Volatile
    private var directGlobalWriteDenied: Boolean = false

    @Volatile
    private var lastUnaffiliatedDpmRetryElapsedMs: Long = 0L

    internal fun isDirectGlobalWriteDenied(): Boolean = directGlobalWriteDenied

    private fun safeElapsedRealtimeMs(): Long = try {
        SystemClock.elapsedRealtime()
    } catch (_: RuntimeException) {
        System.nanoTime() / 1_000_000L
    }

    private fun readApprovedPolicyDnsHost(context: Context): String? {
        return try {
            com.drmohammed.antiporn.data.repository.PolicyRepository.getInstance(context)
                .getCurrentPolicy()
                .dnsHost
                .trim()
                .takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            null
        }
    }

    internal fun hasActiveVerifiedFamilyDns(globalMode: String?, globalHost: String?): Boolean {
        return hasActiveVerifiedFamilyDns(globalMode, globalHost, approvedPolicyHost = null)
    }

    internal fun hasActiveVerifiedFamilyDns(
        globalMode: String?,
        globalHost: String?,
        approvedPolicyHost: String?
    ): Boolean {
        if (globalMode != "hostname" || globalHost.isNullOrBlank()) return false
        val cleanHost = globalHost.trim()
        if (com.drmohammed.antiporn.core.network.DnsFilteringValidator.isKnownFamilyPreset(cleanHost)) {
            return true
        }
        val approved = approvedPolicyHost?.trim()
        return !approved.isNullOrBlank() && cleanHost.equals(approved, ignoreCase = true)
    }

    fun hasActiveVerifiedFamilyDns(context: Context): Boolean {
        val globalMode = readGlobalSetting(context, "private_dns_mode")
        val globalHost = readGlobalSetting(context, "private_dns_specifier")
        val approvedHost = readApprovedPolicyDnsHost(context)
        return hasActiveVerifiedFamilyDns(globalMode, globalHost, approvedHost)
    }

    /**
     * Returns the pre-existing non-family Private DNS hostname if Android Private DNS
     * is locked to a strict hostname that is NOT a known verified family filter preset
     * and NOT the approved policy DNS host.
     */
    fun getBlockingNonFamilyPrivateDnsHost(context: Context): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        val globalMode = readGlobalSetting(context, "private_dns_mode")
        val globalHost = readGlobalSetting(context, "private_dns_specifier")
        val approvedHost = readApprovedPolicyDnsHost(context)
        return if (globalMode == "hostname" &&
            !globalHost.isNullOrBlank() &&
            !hasActiveVerifiedFamilyDns(globalMode, globalHost, approvedHost)
        ) {
            globalHost
        } else {
            null
        }
    }

    fun hasUnclearableHostilePrivateDns(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        val isBlocked = isPrivateDnsBlockedByUnaffiliatedUsers(context)
        if (!isBlocked) return false
        val canWriteSecure = com.drmohammed.antiporn.accessibility.control.AccessibilitySelfActivator.canWriteSecureSettings(context)
        if (canWriteSecure) return false
        return getBlockingNonFamilyPrivateDnsHost(context) != null
    }

    @VisibleForTesting
    internal fun resolveTargetAlwaysOnPackagePure(
        packageName: String,
        requiredMode: CurfewBlackholeVpnService.VpnMode,
        shouldBlockExternalVpn: Boolean
    ): String? {
        return when (requiredMode) {
            CurfewBlackholeVpnService.VpnMode.CURFEW_BLACKHOLE -> packageName
            CurfewBlackholeVpnService.VpnMode.DNS_FILTERING_ONLY -> if (shouldBlockExternalVpn) packageName else null
            CurfewBlackholeVpnService.VpnMode.IDLE -> null
        }
    }

    fun resolveTargetAlwaysOnPackage(
        context: Context,
        requiredMode: CurfewBlackholeVpnService.VpnMode,
        shouldBlockExternalVpn: Boolean? = null
    ): String? {
        val blockExternal = shouldBlockExternalVpn ?: try {
            com.drmohammed.antiporn.data.repository.PolicyRepository.getInstance(context)
                .getCurrentPolicy().isBlockExternalVpnEnabled
        } catch (_: Exception) {
            true
        }
        return resolveTargetAlwaysOnPackagePure(context.packageName, requiredMode, blockExternal)
    }

    /**
     * Returns true when Emergency Local VPN DNS filtering is required on Android Q+,
     * VPN consent has not been granted yet, and external VPN coexistence (`isBlockExternalVpnEnabled == false`)
     * prevents silent `setAlwaysOnVpnPackage` auto-grant.
     */
    fun needsManualEmergencyVpnConsent(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        if (CurfewBlackholeVpnService.evaluateRequiredMode(context) != CurfewBlackholeVpnService.VpnMode.DNS_FILTERING_ONLY) {
            return false
        }
        if (isVpnConsentGranted(context)) return false
        val policy = try {
            com.drmohammed.antiporn.data.repository.PolicyRepository.getInstance(context).getCurrentPolicy()
        } catch (_: Exception) {
            null
        }
        return policy?.isBlockExternalVpnEnabled == false
    }

    /**
     * Evaluates whether system DoT is leaking pornography while the app is blocked
     * from enforcing DoT due to unaffiliated user profiles (e.g. cloned apps).
     *
     * Invariant:
     * - If active verified family DoT is already set on the system: returns false (DoT protects natively).
     * - If the app has WRITE_SECURE_SETTINGS: returns false (app can enforce DoT via Settings.Global).
     * - If a pre-existing non-family strict DoT host cannot be cleared: returns false (prevent total internet blackout).
     * - If unaffiliated profiles block DPM AND system DoT is unverified/leaking: returns true (Emergency VPN required).
     */
    fun isDoTLeakingAndUnaffiliatedBlocked(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        val isBlocked = isPrivateDnsBlockedByUnaffiliatedUsers(context)
        if (!isBlocked) return false

        val hasVerifiedFamily = hasActiveVerifiedFamilyDns(context)
        val canWriteSecure = com.drmohammed.antiporn.accessibility.control.AccessibilitySelfActivator.canWriteSecureSettings(context)
        val hasBlockingHost = getBlockingNonFamilyPrivateDnsHost(context) != null
        return isDoTLeakingAndUnaffiliatedBlockedInternal(
            sdkInt = Build.VERSION.SDK_INT,
            isBlocked = isBlocked,
            hasVerifiedFamilyDns = hasVerifiedFamily,
            canWriteSecure = canWriteSecure,
            hasBlockingNonFamilyHost = hasBlockingHost
        )
    }

    @VisibleForTesting
    internal fun isDoTLeakingAndUnaffiliatedBlockedInternal(
        sdkInt: Int,
        isBlocked: Boolean,
        hasVerifiedFamilyDns: Boolean,
        canWriteSecure: Boolean,
        hasBlockingNonFamilyHost: Boolean
    ): Boolean {
        if (sdkInt < Build.VERSION_CODES.Q) return false
        if (!isBlocked) return false
        if (hasVerifiedFamilyDns) return false
        if (canWriteSecure) return false
        if (hasBlockingNonFamilyHost) return false
        return true
    }

    @VisibleForTesting
    internal fun evaluateUnaffiliatedDnsNoticeStateInternal(
        unaffiliatedBlocked: Boolean,
        canWriteSecure: Boolean,
        isDotLeaking: Boolean,
        isLocalVpnTunnelHealthy: Boolean,
        hasVerifiedFamilyDns: Boolean
    ): UnaffiliatedDnsNoticeState {
        if (!unaffiliatedBlocked || canWriteSecure) {
            return UnaffiliatedDnsNoticeState.NONE
        }
        if (isDotLeaking || (isLocalVpnTunnelHealthy && !hasVerifiedFamilyDns)) {
            return UnaffiliatedDnsNoticeState.EMERGENCY_VPN_ACTIVE
        }
        if (hasVerifiedFamilyDns) {
            return UnaffiliatedDnsNoticeState.DOT_LOCKED_NO_CUSTOMIZE
        }
        return UnaffiliatedDnsNoticeState.NONE
    }

    fun evaluateUnaffiliatedDnsNoticeState(context: Context): UnaffiliatedDnsNoticeState {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return UnaffiliatedDnsNoticeState.NONE
        val unaffiliatedBlocked = isPrivateDnsBlockedByUnaffiliatedUsers(context)
        if (!unaffiliatedBlocked) return UnaffiliatedDnsNoticeState.NONE
        val canWriteSecure = com.drmohammed.antiporn.accessibility.control.AccessibilitySelfActivator.canWriteSecureSettings(context)
        if (canWriteSecure) return UnaffiliatedDnsNoticeState.NONE
        val isDotLeaking = isDoTLeakingAndUnaffiliatedBlocked(context)
        val isTunnelHealthy = CurfewBlackholeVpnService.isTunnelHealthy(CurfewBlackholeVpnService.VpnMode.DNS_FILTERING_ONLY)
        val hasVerifiedFamily = hasActiveVerifiedFamilyDns(context)
        return evaluateUnaffiliatedDnsNoticeStateInternal(
            unaffiliatedBlocked = unaffiliatedBlocked,
            canWriteSecure = canWriteSecure,
            isDotLeaking = isDotLeaking,
            isLocalVpnTunnelHealthy = isTunnelHealthy,
            hasVerifiedFamilyDns = hasVerifiedFamily
        )
    }

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
    ): WebFilterCapabilityResult {
        if (sdkInt < Build.VERSION_CODES.Q) return WebFilterCapabilityResult.Allowed
        if (!isDeviceOwner) return WebFilterCapabilityResult.Allowed
        if (!unaffiliatedBlocked || canWriteSecure) return WebFilterCapabilityResult.Allowed

        if (!blockingHostileHost.isNullOrBlank()) {
            return WebFilterCapabilityResult.BlockedHostilePrivateDns(blockingHostileHost)
        }

        if (globalMode == "hostname" && !globalHost.isNullOrBlank()) {
            if (wantsLocalVpnFiltering) {
                return WebFilterCapabilityResult.BlockedLocalFilterByLockedDot(globalHost)
            }
            val cleanRequested = requestedDnsHost.trim()
            val cleanInitial = initialDnsHost.trim()
            val cleanGlobal = globalHost.trim()
            val userChangedDns = !cleanRequested.equals(cleanInitial, ignoreCase = true)
            val differsFromOsHost = !cleanRequested.equals(cleanGlobal, ignoreCase = true)
            if (userChangedDns && differsFromOsHost) {
                return WebFilterCapabilityResult.BlockedDnsHostChange(
                    lockedHost = cleanGlobal,
                    requestedHost = cleanRequested
                )
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
        val sdkInt = Build.VERSION.SDK_INT
        val isDo = SafeDpmGateway.isDeviceOwner(context)
        val unaffiliatedBlocked = isPrivateDnsBlockedByUnaffiliatedUsers(context)
        val canWriteSecure = com.drmohammed.antiporn.accessibility.control.AccessibilitySelfActivator.canWriteSecureSettings(context)
        val globalMode = readGlobalSetting(context, "private_dns_mode")
        val globalHost = readGlobalSetting(context, "private_dns_specifier")
        val blockingHost = getBlockingNonFamilyPrivateDnsHost(context)
        return evaluateWebFilterCapabilityInternal(
            sdkInt = sdkInt,
            isDeviceOwner = isDo,
            unaffiliatedBlocked = unaffiliatedBlocked,
            canWriteSecure = canWriteSecure,
            globalMode = globalMode,
            globalHost = globalHost,
            blockingHostileHost = blockingHost,
            initialDnsHost = initialDnsHost,
            requestedDnsHost = requestedDnsHost,
            wantsLocalVpnFiltering = wantsLocalVpnFiltering
        )
    }

    @VisibleForTesting
    internal fun shouldAllowUnaffiliatedDpmReprobe(
        countReliable: Boolean,
        nowMs: Long,
        lastRetryMs: Long,
        intervalMs: Long = UNAFFILIATED_REPROBE_INTERVAL_MS
    ): Boolean {
        return !countReliable && (lastRetryMs == 0L || (nowMs - lastRetryMs) >= intervalMs)
    }

    @VisibleForTesting
    internal fun resetUnaffiliatedRetryForTest() {
        lastUnaffiliatedDpmRetryElapsedMs = 0L
    }

    @VisibleForTesting
    internal fun resetFastHealCircuitBreakerForTest() {
        directGlobalWriteDenied = false
    }

    @VisibleForTesting
    internal fun isDirectGlobalWriteDeniedForTest(): Boolean = directGlobalWriteDenied

    private val dnsRestrictionLock = Any()

    /**
     * Writes `private_dns_mode` and `private_dns_specifier` in the transition-safe order:
     * - When activating strict `hostname` mode: write `private_dns_specifier` FIRST, then `private_dns_mode`
     *   (aborts mode write if specifier write fails, preventing `mode=hostname` + empty/stale specifier blackout).
     * - When leaving `hostname` mode (`opportunistic` / `off`): write `private_dns_mode` FIRST, then clear
     *   `private_dns_specifier` (preventing a transient `mode=hostname` + empty specifier blackout).
     */
    @VisibleForTesting
    internal fun writePrivateDnsPairOrdered(
        mode: String,
        specifier: String,
        writeGlobal: (String, String) -> Boolean
    ): Boolean {
        return if (mode == "hostname") {
            val specOk = writeGlobal("private_dns_specifier", specifier)
            val modeOk = if (specOk) writeGlobal("private_dns_mode", mode) else false
            specOk && modeOk
        } else {
            val modeOk = writeGlobal("private_dns_mode", mode)
            val specOk = if (modeOk) writeGlobal("private_dns_specifier", specifier) else false
            modeOk && specOk
        }
    }

    /**
     * Executes a single-lock atomic unlock -> write -> relock of Private DNS Settings.Global properties
     * when [UserManager.DISALLOW_CONFIG_PRIVATE_DNS] is enforced.
     * Uses [writePrivateDnsPairOrdered] so Android's ConnectivityService never observes `mode=hostname`
     * with a stale or blank specifier.
     */
    internal fun writePrivateDnsAtomic(context: Context, mode: String, specifier: String): Boolean {
        val canWriteSecure = com.drmohammed.antiporn.accessibility.control.AccessibilitySelfActivator.canWriteSecureSettings(context)
        if (!canWriteSecure) {
            directGlobalWriteDenied = true
            safeLogD("writePrivateDnsAtomic: skipped, WRITE_SECURE_SETTINGS is denied")
            return false
        }
        val putGlobal: (String, String) -> Boolean = { key, value ->
            Settings.Global.putString(context.contentResolver, key, value)
        }
        if (!SafeDpmGateway.isDeviceOwner(context) || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            val ok = writePrivateDnsPairOrdered(mode, specifier, putGlobal)
            if (ok) {
                directGlobalWriteDenied = false
            }
            return ok
        }
        return synchronized(dnsRestrictionLock) {
            var wasRestricted = false
            val dpmResult = SafeDpmGateway.executeRawBlocking(
                context = context,
                tag = BlackBoxRecorder.TAG_SHIELD,
                actionName = "writePrivateDnsAtomic"
            ) { dpm, admin ->
                val um = context.getSystemService(UserManager::class.java)
                wasRestricted = try {
                    dpm.getUserRestrictions(admin)?.getBoolean(UserManager.DISALLOW_CONFIG_PRIVATE_DNS, false) == true ||
                        um?.hasUserRestriction(UserManager.DISALLOW_CONFIG_PRIVATE_DNS) == true
                } catch (_: Exception) {
                    false
                }
                if (wasRestricted) {
                    dpm.clearUserRestriction(admin, UserManager.DISALLOW_CONFIG_PRIVATE_DNS)
                }
                try {
                    writePrivateDnsPairOrdered(mode, specifier, putGlobal)
                } finally {
                    if (wasRestricted) {
                        try {
                            dpm.addUserRestriction(admin, UserManager.DISALLOW_CONFIG_PRIVATE_DNS)
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed relocking DISALLOW_CONFIG_PRIVATE_DNS in finally", e)
                        }
                    }
                }
            }
            val writeSuccess = when (dpmResult) {
                is DpmExecutionResult.Success -> dpmResult.data
                is DpmExecutionResult.Failure -> {
                    dpmResult.cause?.let { throw it }
                    writePrivateDnsPairOrdered(mode, specifier, putGlobal)
                }
            }
            if (writeSuccess) {
                directGlobalWriteDenied = false
            }
            writeSuccess
        }
    }

    /**
     * Executes atomic unlock -> write -> relock of Private DNS Settings.Global properties
     * when [UserManager.DISALLOW_CONFIG_PRIVATE_DNS] is enforced by Device Owner.
     * Prevents SettingsProvider SecurityException when unaffiliated users block standard DPM API.
     */
    internal fun writeGlobalSettingWithAtomicUnlock(context: Context, key: String, value: String): Boolean {
        val canWriteSecure = com.drmohammed.antiporn.accessibility.control.AccessibilitySelfActivator.canWriteSecureSettings(context)
        if (!canWriteSecure) {
            directGlobalWriteDenied = true
            safeLogD("writeGlobalSettingWithAtomicUnlock: skipped, WRITE_SECURE_SETTINGS is denied")
            return false
        }
        val isDnsKey = key == "private_dns_mode" || key == "private_dns_specifier"
        if (!isDnsKey || !SafeDpmGateway.isDeviceOwner(context) || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return Settings.Global.putString(context.contentResolver, key, value)
        }
        return synchronized(dnsRestrictionLock) {
            var wasRestricted = false
            val dpmResult = SafeDpmGateway.executeRawBlocking(
                context = context,
                tag = BlackBoxRecorder.TAG_SHIELD,
                actionName = "writeGlobalSettingWithAtomicUnlock"
            ) { dpm, admin ->
                val um = context.getSystemService(UserManager::class.java)
                wasRestricted = try {
                    dpm.getUserRestrictions(admin)?.getBoolean(UserManager.DISALLOW_CONFIG_PRIVATE_DNS, false) == true ||
                        um?.hasUserRestriction(UserManager.DISALLOW_CONFIG_PRIVATE_DNS) == true
                } catch (_: Exception) {
                    false
                }
                if (wasRestricted) {
                    dpm.clearUserRestriction(admin, UserManager.DISALLOW_CONFIG_PRIVATE_DNS)
                }
                try {
                    Settings.Global.putString(context.contentResolver, key, value)
                } finally {
                    if (wasRestricted) {
                        try {
                            dpm.addUserRestriction(admin, UserManager.DISALLOW_CONFIG_PRIVATE_DNS)
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed relocking DISALLOW_CONFIG_PRIVATE_DNS in finally", e)
                        }
                    }
                }
            }
            val writeSuccess = when (dpmResult) {
                is com.drmohammed.antiporn.telemetry.DpmExecutionResult.Success -> dpmResult.data
                is com.drmohammed.antiporn.telemetry.DpmExecutionResult.Failure -> {
                    dpmResult.cause?.let { throw it }
                    Settings.Global.putString(context.contentResolver, key, value)
                }
            }
            if (writeSuccess) {
                directGlobalWriteDenied = false
            }
            writeSuccess
        }
    }

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
        val countResult = SecondaryProfileDetector.countSecondaryUsersAndProfiles(context)
        if (countResult.reliable && countResult.count == 0) {
            if (inMemoryPrivateDnsBlockedByUnaffiliatedUsers == true ||
                CurfewConfig.isPrivateDnsBlockedByUnaffiliatedUsers(context)
            ) {
                updatePrivateDnsBlockedByUnaffiliatedUsers(context, false)
            } else {
                inMemoryPrivateDnsBlockedByUnaffiliatedUsers = false
            }
            return false
        }
        inMemoryPrivateDnsBlockedByUnaffiliatedUsers?.let { return it }
        val persisted = CurfewConfig.isPrivateDnsBlockedByUnaffiliatedUsers(context)
        if (persisted) {
            inMemoryPrivateDnsBlockedByUnaffiliatedUsers = true
            return true
        }
        return false
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
        isUnaffiliatedBlocked: Boolean = isPrivateDnsBlockedByUnaffiliatedUsers,
        approvedPolicyHost: String? = null
    ): Boolean {
        val isUnaffiliatedFallback = isUnaffiliatedBlocked &&
            globalMode == "hostname" &&
            hasActiveVerifiedFamilyDns(globalMode, globalHost, approvedPolicyHost)
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
            writeGlobal = { key, value -> writeGlobalSettingWithAtomicUnlock(context, key, value) },
            clearDnsRestorePending = {
                if (CurfewConfig.isDnsRestorePending(context)) {
                    CurfewConfig.setDnsRestorePending(context, false)
                }
            },
            writePrivateDnsPair = { mode, specifier -> writePrivateDnsAtomic(context, mode, specifier) }
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
        clearDnsRestorePending: () -> Unit = {},
        writePrivateDnsPair: ((mode: String, specifier: String) -> Boolean)? = null
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
            val writeSucceeded = if (writePrivateDnsPair != null) {
                writePrivateDnsPair("hostname", expectedHost)
            } else {
                val hostWritten = writeGlobal("private_dns_specifier", expectedHost)
                val modeWritten = if (hostWritten) writeGlobal("private_dns_mode", "hostname") else false
                hostWritten && modeWritten
            }
            val latencyMs = ((System.nanoTime() - startNanos) / 1_000_000L).coerceAtLeast(0L)
            if (!writeSucceeded) {
                safeLogW("Fast-heal Settings.Global.putString returned false (expectedHost=$expectedHost)")
                return DnsFastHealResult(
                    outcome = DnsFastHealOutcome.FAILED_NEEDS_DPM,
                    observedMode = globalMode,
                    observedHost = globalHost,
                    expectedHost = expectedHost,
                    latencyMs = latencyMs,
                    errorMessage = "Settings.Global.putString returned false"
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
        val approvedHost = readApprovedPolicyDnsHost(context)
        return isLocalVpnDnsStateConsistent(
            dpmMode = dpmMode,
            globalMode = globalMode,
            globalHost = globalHost,
            isUnaffiliatedBlocked = unaffiliatedBlocked,
            approvedPolicyHost = approvedHost
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
                val atomicOk = writePrivateDnsAtomic(context, "opportunistic", "")
                if (atomicOk) {
                    directGlobalWriteDenied = false
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
        val approvedHost = readApprovedPolicyDnsHost(context)
        return isLocalVpnDnsStateConsistent(
            dpmMode = verifiedMode,
            globalMode = verifiedGlobalMode,
            globalHost = verifiedGlobalHost,
            isUnaffiliatedBlocked = unaffiliatedBlocked,
            approvedPolicyHost = approvedHost
        )
    }

    private fun applyLocalVpnDnsPolicy(
        context: Context,
        shieldId: String,
        targetHost: String,
        requiredMode: CurfewBlackholeVpnService.VpnMode,
        allowStandaloneFallback: Boolean
    ): ShieldResult {
        if (!isVpnConsentGranted(context) && SafeDpmGateway.isDeviceOwner(context)) {
            val lockdown = requiredMode == CurfewBlackholeVpnService.VpnMode.CURFEW_BLACKHOLE
            val bypassed = if (lockdown) CurfewConfig.getVpnBypassedPackages(context) else null
            val targetAlwaysOnPkg = resolveTargetAlwaysOnPackage(context, requiredMode)

            if (targetAlwaysOnPkg != null) {
                SafeDpmGateway.setAlwaysOnVpnPackageBlocking(
                    context = context,
                    vpnPackage = targetAlwaysOnPkg,
                    lockdown = lockdown,
                    bypassedPackages = bypassed
                )
            }
        }
        val hasVpnConsent = isVpnConsentGranted(context)
        if (!hasVpnConsent) {
            Log.w(TAG, "VpnService.prepare returned non-null; refusing opportunistic DoT downgrade and preserving standalone DoT")
            if (allowStandaloneFallback && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                applyStandaloneDot(context, targetHost)
            }
            lockDnsAndNetworkRestrictions(context)
            val message = if (needsManualEmergencyVpnConsent(context)) {
                context.getString(R.string.shield_dns_emergency_vpn_needs_consent)
            } else {
                context.getString(R.string.card_dns_inactive)
            }
            return ShieldResult(
                shieldId = shieldId,
                success = false,
                message = message,
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
            if (allowStandaloneFallback && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
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
            val approvedHost = readApprovedPolicyDnsHost(context)
            val isUnaffiliatedFallback = unaffiliatedBlocked &&
                currentGlobalMode == "hostname" &&
                hasActiveVerifiedFamilyDns(currentGlobalMode, currentGlobalHost, approvedHost)
            val message = if (isUnaffiliatedFallback) {
                val currentHost = currentGlobalHost ?: DnsShield.DEFAULT_DNS_HOST
                context.getString(R.string.shield_dns_local_filter_unaffiliated_notice, currentHost)
            } else if (unaffiliatedBlocked) {
                val cloneSummary = SecondaryProfileDetector.formatClonedAppsSummary(context)
                    ?: context.getString(R.string.shield_dns_cloned_app_default)
                context.getString(R.string.shield_dns_emergency_vpn_active_notice, cloneSummary)
            } else if (com.drmohammed.antiporn.core.network.dns.DnsResilienceManager.isLocalVpnFallbackActive()) {
                context.getString(R.string.shield_dns_resilience_doh_active)
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
            val unaffiliatedBlocked = isPrivateDnsBlockedByUnaffiliatedUsers(context)
            val blockingHost = getBlockingNonFamilyPrivateDnsHost(context)
            if (unaffiliatedBlocked && blockingHost != null) {
                // Zero-Blackout Guarantee: tear down local DNS VPN immediately when strict non-family Private DNS
                // cannot be downgraded; otherwise TUN TCP RST on port 853 causes a total device internet blackout.
                if (requiredMode == CurfewBlackholeVpnService.VpnMode.DNS_FILTERING_ONLY &&
                    !CurfewConfig.isInstantInternetKillEnabled(context) &&
                    !CurfewEnforcer.isEffectiveBlockActive(context)
                ) {
                    if (CurfewBlackholeVpnService.isServiceOrTunnelActive() &&
                        CurfewBlackholeVpnService.currentMode == CurfewBlackholeVpnService.VpnMode.DNS_FILTERING_ONLY
                    ) {
                        CurfewBlackholeVpnService.forceReleaseVpnAndAwait(context, 1500L)
                    }
                    val currentAlwaysOn = SafeDpmGateway.getAlwaysOnVpnPackageBlocking(context, recordSuccess = false).getOrNull()
                    if (currentAlwaysOn == context.packageName) {
                        SafeDpmGateway.setAlwaysOnVpnPackageBlocking(context, null, false)
                    }
                }
                val cloneSummary = SecondaryProfileDetector.formatClonedAppsSummary(context)
                    ?: context.getString(R.string.shield_dns_cloned_app_default)
                ShieldResult(
                    shieldId = shieldId,
                    success = false,
                    message = context.getString(
                        R.string.shield_dns_unaffiliated_hostile_dns_error,
                        blockingHost,
                        cloneSummary
                    ),
                    statusCode = COMPAT_PRIVATE_DNS_SET_ERROR_FAILURE_SETTING
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
            return applyLocalVpnDnsPolicy(
                context = context,
                shieldId = shieldId,
                targetHost = targetHost,
                requiredMode = requiredMode,
                allowStandaloneFallback = true
            )
        }

        val (dnsSetResult, usedFallback) = applyStandaloneDot(context, targetHost)
        lockDnsAndNetworkRestrictions(context)
        val isDpmNoError = dnsSetResult == DevicePolicyManager.PRIVATE_DNS_SET_NO_ERROR

        if (!isDpmNoError && !usedFallback && isDoTLeakingAndUnaffiliatedBlocked(context)) {
            val postMode = CurfewBlackholeVpnService.evaluateRequiredMode(context)
            if (postMode != CurfewBlackholeVpnService.VpnMode.IDLE) {
                return applyLocalVpnDnsPolicy(
                    context = context,
                    shieldId = shieldId,
                    targetHost = targetHost,
                    requiredMode = postMode,
                    allowStandaloneFallback = false
                )
            }
        }

        val currentGlobalMode = readGlobalSetting(context, "private_dns_mode")
        val currentGlobalHost = readGlobalSetting(context, "private_dns_specifier")
        val approvedHost = readApprovedPolicyDnsHost(context)
        val isFamilyDnsActive = hasActiveVerifiedFamilyDns(currentGlobalMode, currentGlobalHost, approvedHost)
        val unaffiliatedBlocked = isPrivateDnsBlockedByUnaffiliatedUsers(context)
        val isUnaffiliatedFallback = unaffiliatedBlocked && isFamilyDnsActive
        val isUnaffiliatedError = unaffiliatedBlocked && !isFamilyDnsActive
        val blockingHost = if (isUnaffiliatedError) getBlockingNonFamilyPrivateDnsHost(context) else null
        val success = isDpmNoError || usedFallback || isUnaffiliatedFallback
        val msg = when {
            isDpmNoError && !usedFallback -> context.getString(R.string.shield_dns_success)
            usedFallback -> context.getString(R.string.shield_dns_fallback_active)
            isUnaffiliatedFallback -> {
                val currentHost = currentGlobalHost ?: DnsShield.DEFAULT_DNS_HOST
                context.getString(R.string.shield_dns_unaffiliated_users_notice, currentHost, targetHost)
            }
            isUnaffiliatedError && blockingHost != null -> {
                val cloneSummary = SecondaryProfileDetector.formatClonedAppsSummary(context)
                    ?: context.getString(R.string.shield_dns_cloned_app_default)
                context.getString(R.string.shield_dns_unaffiliated_hostile_dns_error, blockingHost, cloneSummary)
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
            val unaffiliatedBlocked = isPrivateDnsBlockedByUnaffiliatedUsers(context)
            val blockingHost = if (unaffiliatedBlocked) getBlockingNonFamilyPrivateDnsHost(context) else null
            val canWriteSecure = if (blockingHost != null) {
                com.drmohammed.antiporn.accessibility.control.AccessibilitySelfActivator.canWriteSecureSettings(context)
            } else {
                false
            }
            if (unaffiliatedBlocked && blockingHost != null && !canWriteSecure) {
                val cloneSummary = SecondaryProfileDetector.formatClonedAppsSummary(context)
                    ?: context.getString(R.string.shield_dns_cloned_app_default)
                return ShieldStatus.NeedsEnforcement(
                    context.getString(
                        R.string.shield_dns_unaffiliated_hostile_dns_error,
                        blockingHost,
                        cloneSummary
                    )
                )
            }
            if (!isVpnConsentGranted(context)) {
                val message = if (needsManualEmergencyVpnConsent(context)) {
                    context.getString(R.string.shield_dns_emergency_vpn_needs_consent)
                } else {
                    context.getString(R.string.shield_dns_local_filter_needs_sync)
                }
                return ShieldStatus.NeedsEnforcement(message)
            }
            val approvedHost = readApprovedPolicyDnsHost(context)
            val isHealthy = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                isLocalVpnDnsStateConsistent(
                    dpmMode = modeFromDpm,
                    globalMode = globalMode,
                    globalHost = globalSpecifier,
                    isUnaffiliatedBlocked = unaffiliatedBlocked,
                    approvedPolicyHost = approvedHost
                )
            } else {
                globalMode != "hostname"
            }
            val isVpnServiceUp = CurfewBlackholeVpnService.isTunnelHealthy(requiredVpnMode)
            return if (isHealthy && isDnsLocked && isVpnServiceUp) {
                val isUnaffiliatedFallback = unaffiliatedBlocked &&
                    globalMode == "hostname" &&
                    hasActiveVerifiedFamilyDns(globalMode, globalSpecifier, approvedHost)
                val message = if (isUnaffiliatedFallback) {
                    val currentHost = globalSpecifier ?: DnsShield.DEFAULT_DNS_HOST
                    context.getString(R.string.shield_dns_local_filter_unaffiliated_notice, currentHost)
                } else if (unaffiliatedBlocked) {
                    val cloneSummary = SecondaryProfileDetector.formatClonedAppsSummary(context)
                        ?: context.getString(R.string.shield_dns_cloned_app_default)
                    context.getString(R.string.shield_dns_emergency_vpn_active_notice, cloneSummary)
                } else if (com.drmohammed.antiporn.core.network.dns.DnsResilienceManager.isLocalVpnFallbackActive()) {
                    context.getString(R.string.shield_dns_resilience_doh_active)
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
            val approvedHost = readApprovedPolicyDnsHost(context)
            val currentActiveHost = globalSpecifier?.takeIf { it.isNotBlank() }
            if (currentActiveHost != null && hasActiveVerifiedFamilyDns(globalMode, currentActiveHost, approvedHost)) {
                val explanation = context.getString(R.string.shield_dns_unaffiliated_users_notice, currentActiveHost, targetHost)
                ShieldStatus.Active(explanation)
            } else {
                val blockingHost = getBlockingNonFamilyPrivateDnsHost(context)
                val canWriteSecure = if (blockingHost != null) {
                    com.drmohammed.antiporn.accessibility.control.AccessibilitySelfActivator.canWriteSecureSettings(context)
                } else {
                    false
                }
                val cloneSummary = SecondaryProfileDetector.formatClonedAppsSummary(context)
                    ?: context.getString(R.string.shield_dns_cloned_app_default)
                if (blockingHost != null && !canWriteSecure) {
                    ShieldStatus.NeedsEnforcement(
                        context.getString(
                            R.string.shield_dns_unaffiliated_hostile_dns_error,
                            blockingHost,
                            cloneSummary
                        )
                    )
                } else {
                    ShieldStatus.NeedsEnforcement(
                        context.getString(R.string.shield_dns_unaffiliated_users_error_with_app, cloneSummary)
                    )
                }
            }
        } else {
            val currentHostDisplay = globalSpecifier?.takeIf { it.isNotBlank() }
                ?: hostFromDpm
                ?: context.getString(R.string.default_dns_text)
            ShieldStatus.NeedsEnforcement(context.getString(R.string.card_dns_pending, currentHostDisplay, targetHost))
        }
    }

    fun preArmStandaloneDotFailClosed(
        context: Context,
        host: String = DnsShield.getEffectiveDnsHost(context)
    ): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        if (!SafeDpmGateway.isDeviceOwner(context)) return false
        val targetHost = host.ifBlank { DnsShield.getEffectiveDnsHost(context) }
        safeLogI("preArmStandaloneDotFailClosed: Arming standalone DoT ($targetHost) as fail-closed defense")
        val (resultCode, usedFallback) = applyStandaloneDot(context, targetHost)
        val armed = if (resultCode == COMPAT_PRIVATE_DNS_SET_NO_ERROR || usedFallback) {
            true
        } else {
            ProtectionTransitionGuard.markInternalDnsMutation()
            writePrivateDnsAtomic(context, "hostname", targetHost)
        }
        lockDnsAndNetworkRestrictions(context)
        return armed
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
            writeGlobal = { key, value -> writeGlobalSettingWithAtomicUnlock(context, key, value) },
            setDpmPrivateDns = { host ->
                val unaffiliatedAlreadyBlocked = isPrivateDnsBlockedByUnaffiliatedUsers(context)
                val countResult = SecondaryProfileDetector.countSecondaryUsersAndProfiles(context)
                val nowMs = safeElapsedRealtimeMs()
                val shouldRetryDpm = !unaffiliatedAlreadyBlocked ||
                    shouldAllowUnaffiliatedDpmReprobe(
                        countReliable = countResult.reliable,
                        nowMs = nowMs,
                        lastRetryMs = lastUnaffiliatedDpmRetryElapsedMs
                    )
                if (!shouldRetryDpm) {
                    safeLogD("Skipping redundant DPM call in applyStandaloneDot: unaffiliated users present")
                    false to COMPAT_PRIVATE_DNS_SET_ERROR_FAILURE_SETTING
                } else {
                    if (unaffiliatedAlreadyBlocked) {
                        lastUnaffiliatedDpmRetryElapsedMs = nowMs
                        safeLogD("Re-probing DPM setGlobalPrivateDns because secondary user count is unreliable")
                    }
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
                        lastUnaffiliatedDpmRetryElapsedMs = 0L
                        updatePrivateDnsBlockedByUnaffiliatedUsers(context, false)
                    }
                    val resultCode = when (dpmRes) {
                        is DpmExecutionResult.Success -> dpmRes.data
                        is DpmExecutionResult.Failure -> COMPAT_PRIVATE_DNS_SET_ERROR_FAILURE_SETTING
                    }
                    dpmRes.isSuccess to resultCode
                }
            },
            clearDnsRestorePending = {
                if (CurfewConfig.isDnsRestorePending(context)) {
                    CurfewConfig.setDnsRestorePending(context, false)
                }
            },
            writePrivateDnsPair = { mode, specifier -> writePrivateDnsAtomic(context, mode, specifier) },
            readGlobalAfterDpm = {
                readGlobalSetting(context, "private_dns_mode") to readGlobalSetting(context, "private_dns_specifier")
            }
        )

        if (dnsSetResult != COMPAT_PRIVATE_DNS_SET_NO_ERROR && !isPrivateDnsBlockedByUnaffiliatedUsers(context)) {
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
        clearDnsRestorePending: () -> Unit = {},
        writePrivateDnsPair: ((mode: String, specifier: String) -> Boolean)? = null,
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
        readGlobalAfterDpm = readGlobalAfterDpm,
        isDirectGlobalWriteDenied = { directGlobalWriteDenied },
        setDirectGlobalWriteDenied = { directGlobalWriteDenied = it },
        dpmModeHostname = privateDnsModeProviderHostname(),
        compatNoError = COMPAT_PRIVATE_DNS_SET_NO_ERROR,
        compatHostNotServing = COMPAT_PRIVATE_DNS_SET_ERROR_HOST_NOT_SERVING,
        compatFailureSetting = COMPAT_PRIVATE_DNS_SET_ERROR_FAILURE_SETTING,
        logI = ::safeLogI,
        logD = ::safeLogD,
        logW = ::safeLogW
    )

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
                val targetAlwaysOnPkg = resolveTargetAlwaysOnPackage(context, requiredMode)

                if (alwaysOnPkg != targetAlwaysOnPkg || isLockdown) {
                    val vpnRes = SafeDpmGateway.setAlwaysOnVpnPackageBlocking(context, targetAlwaysOnPkg, false)
                    if (!vpnRes.isSuccess) {
                        Log.e(TAG, "Failed configuring Always-on VPN for DNS filtering mode (target=$targetAlwaysOnPkg)")
                        allOk = false
                    } else {
                        Log.i(TAG, "Always-on VPN configured to target=$targetAlwaysOnPkg (lockdown=false)")
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
                        if (hasUnclearableHostilePrivateDns(context)) {
                            CurfewBlackholeVpnService.forceReleaseVpnAndAwait(context, 1500L)
                            if (alwaysOnPkg == context.packageName || targetAlwaysOnPkg == context.packageName) {
                                SafeDpmGateway.setAlwaysOnVpnPackageBlocking(context, null, false)
                            }
                        }
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
                val isMainThread = try {
                    Looper.myLooper() == Looper.getMainLooper()
                } catch (_: RuntimeException) {
                    false
                }
                if (vpnWasTornDown && !isMainThread) {
                    try {
                        Thread.sleep(450L)
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                    }
                }
                if (vpnWasTornDown || !isStandaloneDnsShieldActive(context)) {
                    var restored = restoreDnsShieldOnCurfewRelease(context)
                    if (!restored) {
                        if (!PhysicalNetworkResolver.isDevicePhysicallyOffline(context) && !isMainThread) {
                            try {
                                Thread.sleep(600L)
                            } catch (_: InterruptedException) {
                                Thread.currentThread().interrupt()
                            }
                            restored = restoreDnsShieldOnCurfewRelease(context)
                        } else if (!PhysicalNetworkResolver.isDevicePhysicallyOffline(context)) {
                            restored = restoreDnsShieldOnCurfewRelease(context)
                        }
                        if (!restored) {
                            val targetHost = DnsShield.getEffectiveDnsHost(context)
                            val preArmed = StandaloneDotEnforcer.preArmStandaloneDotFailClosed(context, targetHost)
                            if (preArmed) {
                                Timber.tag(TAG).i("Post-curfew DPM DNS deferred (Doze/transient); fail-closed Settings.Global pre-armed to $targetHost")
                                restored = true
                            }
                        }
                    }
                    if (!restored) {
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
