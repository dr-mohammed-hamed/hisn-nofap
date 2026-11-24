package com.drmohammed.antiporn.curfew

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.os.Build
import android.os.UserManager
import android.provider.Settings
import com.drmohammed.antiporn.accessibility.control.AccessibilitySelfActivator
import com.drmohammed.antiporn.core.network.DnsFilteringValidator
import com.drmohammed.antiporn.data.repository.PolicyRepository
import com.drmohammed.antiporn.telemetry.BlackBoxRecorder
import com.drmohammed.antiporn.telemetry.DpmErrorType
import com.drmohammed.antiporn.telemetry.DpmExecutionResult
import com.drmohammed.antiporn.telemetry.SafeDpmGateway
import timber.log.Timber

/**
 * Handles low-level `Settings.Global` Private DNS atomic unlock/write/relock,
 * DPM Private DNS state inspection, unaffiliated user tracking, and consistency checks.
 */
internal object PrivateDnsSettingsBridge {

    private const val TAG = "AntiPorn_PrivDnsBridge"
    private const val COMPAT_PRIVATE_DNS_MODE_UNKNOWN = 0
    private const val COMPAT_PRIVATE_DNS_MODE_OFF = 1
    private const val COMPAT_PRIVATE_DNS_MODE_OPPORTUNISTIC = 2
    private const val COMPAT_PRIVATE_DNS_MODE_PROVIDER_HOSTNAME = 3

    private val dnsRestrictionLock = Any()

    @Volatile
    var directGlobalWriteDenied: Boolean = false

    @Volatile
    private var inMemoryPrivateDnsBlockedByUnaffiliatedUsers: Boolean? = null

    fun privateDnsModeUnknown(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) DevicePolicyManager.PRIVATE_DNS_MODE_UNKNOWN
        else COMPAT_PRIVATE_DNS_MODE_UNKNOWN

    fun privateDnsModeOff(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) DevicePolicyManager.PRIVATE_DNS_MODE_OFF
        else COMPAT_PRIVATE_DNS_MODE_OFF

    fun privateDnsModeOpportunistic(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) DevicePolicyManager.PRIVATE_DNS_MODE_OPPORTUNISTIC
        else COMPAT_PRIVATE_DNS_MODE_OPPORTUNISTIC

    fun privateDnsModeProviderHostname(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) DevicePolicyManager.PRIVATE_DNS_MODE_PROVIDER_HOSTNAME
        else COMPAT_PRIVATE_DNS_MODE_PROVIDER_HOSTNAME

    fun readGlobalSetting(context: Context, key: String): String? = try {
        Settings.Global.getString(context.contentResolver, key)
    } catch (e: Exception) {
        Timber.tag(TAG).w(e, "Failed reading Settings.Global key=$key")
        null
    }

    fun readDpmPrivateDnsMode(context: Context): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return -1
        return SafeDpmGateway.getGlobalPrivateDnsModeBlocking(
            context = context,
            tag = BlackBoxRecorder.TAG_SHIELD,
            recordSuccess = false
        ).getOrNull() ?: -1
    }

    fun readDpmPrivateDnsHost(context: Context): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        return SafeDpmGateway.getGlobalPrivateDnsHostBlocking(
            context = context,
            tag = BlackBoxRecorder.TAG_SHIELD,
            recordSuccess = false
        ).getOrNull()
    }

    fun readApprovedPolicyDnsHost(context: Context): String? = try {
        PolicyRepository.getInstance(context).getCurrentPolicy().dnsHost.trim().takeIf { it.isNotBlank() }
    } catch (e: Exception) {
        Timber.tag(TAG).d(e, "Failed reading approved policy DNS host")
        null
    }

    fun hasActiveVerifiedFamilyDns(
        globalMode: String?,
        globalHost: String?,
        approvedPolicyHost: String? = null
    ): Boolean {
        if (globalMode != "hostname" || globalHost.isNullOrBlank()) return false
        val cleanHost = globalHost.trim()
        if (DnsFilteringValidator.isKnownFamilyPreset(cleanHost)) return true
        val approved = approvedPolicyHost?.trim()
        return !approved.isNullOrBlank() && cleanHost.equals(approved, ignoreCase = true)
    }

    fun hasActiveVerifiedFamilyDns(context: Context): Boolean {
        return hasActiveVerifiedFamilyDns(
            readGlobalSetting(context, "private_dns_mode"),
            readGlobalSetting(context, "private_dns_specifier"),
            readApprovedPolicyDnsHost(context)
        )
    }

    fun getBlockingNonFamilyPrivateDnsHost(context: Context): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        val globalMode = readGlobalSetting(context, "private_dns_mode")
        val globalHost = readGlobalSetting(context, "private_dns_specifier")
        val approvedHost = readApprovedPolicyDnsHost(context)
        return if (globalMode == "hostname" && !globalHost.isNullOrBlank() &&
            !hasActiveVerifiedFamilyDns(globalMode, globalHost, approvedHost)
        ) {
            globalHost
        } else {
            null
        }
    }

    fun hasUnclearableHostilePrivateDns(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        if (!isPrivateDnsBlockedByUnaffiliatedUsers(context)) return false
        if (AccessibilitySelfActivator.canWriteSecureSettings(context)) return false
        return getBlockingNonFamilyPrivateDnsHost(context) != null
    }

    var isUnaffiliatedBlockedCached: Boolean
        get() = inMemoryPrivateDnsBlockedByUnaffiliatedUsers ?: false
        set(value) {
            inMemoryPrivateDnsBlockedByUnaffiliatedUsers = value
        }

    fun isPrivateDnsBlockedByUnaffiliatedUsers(context: Context): Boolean {
        val countResult = com.drmohammed.antiporn.core.engine.SecondaryProfileDetector.countSecondaryUsersAndProfiles(context)
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

    fun updatePrivateDnsBlockedByUnaffiliatedUsers(context: Context, blocked: Boolean) {
        inMemoryPrivateDnsBlockedByUnaffiliatedUsers = blocked
        CurfewConfig.setPrivateDnsBlockedByUnaffiliatedUsers(context, blocked)
    }

    fun isUnaffiliatedUsersException(res: DpmExecutionResult<*>): Boolean =
        res is DpmExecutionResult.Failure &&
            res.errorType == DpmErrorType.SECURITY_EXCEPTION &&
            res.message.contains("unaffiliated", ignoreCase = true)

    fun isLocalVpnDnsStateConsistent(
        dpmMode: Int?,
        globalMode: String?,
        globalHost: String?,
        isUnaffiliatedBlocked: Boolean = isUnaffiliatedBlockedCached,
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

    fun isStandaloneDnsStateConsistent(
        dpmMode: Int?,
        dpmHost: String?,
        globalMode: String?,
        globalHost: String?,
        targetHost: String,
        isUnaffiliatedBlocked: Boolean = isUnaffiliatedBlockedCached,
        isOfflinePreArmedPendingDpm: Boolean = false
    ): Boolean {
        if (targetHost.isBlank()) return false
        val globalMatches = globalMode == "hostname" && globalHost?.equals(targetHost, ignoreCase = true) == true
        val dpmMatches = dpmMode == privateDnsModeProviderHostname() && dpmHost?.equals(targetHost, ignoreCase = true) == true
        val dpmUnavailableOrFallback = dpmMode == null || dpmMode < 0 || dpmMode == privateDnsModeUnknown() || isUnaffiliatedBlocked
        return globalMatches && (dpmMatches || dpmUnavailableOrFallback || isOfflinePreArmedPendingDpm)
    }

    fun writePrivateDnsPairOrdered(mode: String, specifier: String, writeGlobal: (String, String) -> Boolean): Boolean {
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

    fun writePrivateDnsAtomic(context: Context, mode: String, specifier: String): Boolean {
        if (!AccessibilitySelfActivator.canWriteSecureSettings(context)) {
            directGlobalWriteDenied = true
            Timber.tag(TAG).d("writePrivateDnsAtomic: skipped, WRITE_SECURE_SETTINGS is denied")
            return false
        }
        val putGlobal: (String, String) -> Boolean = { key, value ->
            Settings.Global.putString(context.contentResolver, key, value)
        }
        if (!SafeDpmGateway.isDeviceOwner(context) || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            val ok = writePrivateDnsPairOrdered(mode, specifier, putGlobal)
            if (ok) directGlobalWriteDenied = false
            return ok
        }
        return executeWithDnsRestrictionUnlock(context, "writePrivateDnsAtomic") {
            writePrivateDnsPairOrdered(mode, specifier, putGlobal)
        }
    }

    fun writeGlobalSettingWithAtomicUnlock(context: Context, key: String, value: String): Boolean {
        if (!AccessibilitySelfActivator.canWriteSecureSettings(context)) {
            directGlobalWriteDenied = true
            Timber.tag(TAG).d("writeGlobalSettingWithAtomicUnlock: skipped, WRITE_SECURE_SETTINGS is denied")
            return false
        }
        val isDnsKey = key == "private_dns_mode" || key == "private_dns_specifier"
        if (!isDnsKey || !SafeDpmGateway.isDeviceOwner(context) || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return Settings.Global.putString(context.contentResolver, key, value)
        }
        return executeWithDnsRestrictionUnlock(context, "writeGlobalSettingWithAtomicUnlock") {
            Settings.Global.putString(context.contentResolver, key, value)
        }
    }

    private fun executeWithDnsRestrictionUnlock(
        context: Context,
        actionName: String,
        writeAction: () -> Boolean
    ): Boolean {
        return synchronized(dnsRestrictionLock) {
            var settingsSecurityException: SecurityException? = null
            val dpmResult = SafeDpmGateway.executeRawBlocking(
                context = context,
                tag = BlackBoxRecorder.TAG_SHIELD,
                actionName = actionName
            ) { dpm, admin ->
                val um = context.getSystemService(UserManager::class.java)
                val wasRestricted = try {
                    dpm.getUserRestrictions(admin)?.getBoolean(UserManager.DISALLOW_CONFIG_PRIVATE_DNS, false) == true ||
                        um?.hasUserRestriction(UserManager.DISALLOW_CONFIG_PRIVATE_DNS) == true
                } catch (e: Exception) {
                    Timber.tag(TAG).d(e, "Failed checking DISALLOW_CONFIG_PRIVATE_DNS restriction")
                    false
                }
                if (wasRestricted) {
                    dpm.clearUserRestriction(admin, UserManager.DISALLOW_CONFIG_PRIVATE_DNS)
                }
                try {
                    try {
                        writeAction()
                    } catch (se: SecurityException) {
                        settingsSecurityException = se
                        false
                    }
                } finally {
                    if (wasRestricted) {
                        try {
                            dpm.addUserRestriction(admin, UserManager.DISALLOW_CONFIG_PRIVATE_DNS)
                        } catch (e: Exception) {
                            Timber.tag(TAG).w(e, "Failed relocking DISALLOW_CONFIG_PRIVATE_DNS in finally")
                        }
                    }
                }
            }
            settingsSecurityException?.let { throw it }
            val writeSuccess = when (dpmResult) {
                is DpmExecutionResult.Success -> dpmResult.data
                is DpmExecutionResult.Failure -> {
                    Timber.tag(TAG).w(dpmResult.cause, "DPM unlock failed in $actionName; attempting direct Settings.Global write")
                    writeAction()
                }
            }
            if (writeSuccess) directGlobalWriteDenied = false
            writeSuccess
        }
    }

    fun lockDnsAndNetworkRestrictions(context: Context) {
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
            if (!res.isSuccess) Timber.tag(TAG).w("Failed enforcing DISALLOW_CONFIG_PRIVATE_DNS")
        }
        if (restrictions?.getBoolean(UserManager.DISALLOW_NETWORK_RESET, false) != true) {
            val res = SafeDpmGateway.addUserRestrictionBlocking(
                context = context,
                userRestriction = UserManager.DISALLOW_NETWORK_RESET,
                tag = BlackBoxRecorder.TAG_SHIELD
            )
            if (!res.isSuccess) Timber.tag(TAG).w("Failed enforcing DISALLOW_NETWORK_RESET")
        }
    }
}
