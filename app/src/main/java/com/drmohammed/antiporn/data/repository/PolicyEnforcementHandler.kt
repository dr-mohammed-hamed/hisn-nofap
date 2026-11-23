package com.drmohammed.antiporn.data.repository

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.UserManager
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import com.drmohammed.antiporn.R
import com.drmohammed.antiporn.accessibility.HisnAccessibilityService
import com.drmohammed.antiporn.accessibility.control.AccessibilityPolicyCoordinator
import com.drmohammed.antiporn.accessibility.control.AccessibilityReconcileResult
import com.drmohammed.antiporn.accessibility.control.AccessibilityRequirementReason
import com.drmohammed.antiporn.accessibility.control.AccessibilitySelfActivator
import com.drmohammed.antiporn.ai.service.ScreenAiProjectionService
import com.drmohammed.antiporn.core.model.DevicePolicyBundle
import com.drmohammed.antiporn.core.model.IslamicTimeZoneRegistry
import com.drmohammed.antiporn.core.policy.AppPolicyRegistry
import com.drmohammed.antiporn.curfew.CurfewAppResolver
import com.drmohammed.antiporn.curfew.CurfewBlackholeVpnService
import com.drmohammed.antiporn.curfew.CurfewConfig
import com.drmohammed.antiporn.curfew.CurfewEnforcer
import com.drmohammed.antiporn.curfew.CurfewForegroundWatcher
import com.drmohammed.antiporn.curfew.CurfewScheduler
import com.drmohammed.antiporn.curfew.CurfewVpnDnsBridge
import com.drmohammed.antiporn.curfew.ZeroTrustForegroundWatcher
import com.drmohammed.antiporn.data.local.db.entity.PendingApprovalEntity
import com.drmohammed.antiporn.notification.registry.NotificationRegistry
import com.drmohammed.antiporn.shields.BrowserShield
import com.drmohammed.antiporn.shields.DnsShield
import com.drmohammed.antiporn.shields.VpnShield
import com.drmohammed.antiporn.telemetry.BlackBoxRecorder
import com.drmohammed.antiporn.telemetry.DpmExecutionResult
import com.drmohammed.antiporn.telemetry.SafeDpmGateway
import com.drmohammed.antiporn.waqfa.engine.WaqfaSessionManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.TimeZone
import java.util.concurrent.atomic.AtomicLong

/**
 * Serializes policy enforcement against system engines (DPM, Curfew, VPN, Browser/DNS shields)
 * while preserving baseline deltas across superseded enforcement requests.
 */
internal class PolicyEnforcementHandler(
    private val appContext: Context,
    private val repositoryScope: CoroutineScope
) {
    private val policyApplyMutex = Mutex()
    private val policyApplySequence = AtomicLong(0L)
    private val baselineLock = Any()

    @Volatile
    private var lastAppliedPolicyBundle: DevicePolicyBundle? = null

    @Volatile
    private var pendingBaselinePreviousPolicy: DevicePolicyBundle? = null

    fun enqueueApply(bundle: DevicePolicyBundle, previousPolicy: DevicePolicyBundle) {
        val applySeq = policyApplySequence.incrementAndGet()
        synchronized(baselineLock) {
            if (pendingBaselinePreviousPolicy == null) {
                pendingBaselinePreviousPolicy = lastAppliedPolicyBundle ?: previousPolicy
            }
        }
        repositoryScope.launch {
            policyApplyMutex.withLock {
                if (applySeq < policyApplySequence.get()) {
                    Log.d(
                        TAG,
                        "Skipping superseded applyPolicyToSystem v${bundle.version} (seq=$applySeq < latest=${policyApplySequence.get()})"
                    )
                    return@withLock
                }
                val effectivePreviousPolicy = synchronized(baselineLock) {
                    val baseline = pendingBaselinePreviousPolicy ?: lastAppliedPolicyBundle ?: previousPolicy
                    pendingBaselinePreviousPolicy = null
                    baseline
                }
                applyInternal(bundle, effectivePreviousPolicy)
            }
        }
    }

    private suspend fun applyInternal(
        bundle: DevicePolicyBundle,
        effectivePreviousPolicy: DevicePolicyBundle
    ) {
        com.drmohammed.antiporn.core.engine.ProtectionTransitionGuard.runInternalTransitionSuspend {
            var appliedSuccessfully = false
            try {
                val isDeviceOwner = SafeDpmGateway.isDeviceOwner(appContext)
                if (isDeviceOwner) {
                    enforceTimeAndDatePolicy(bundle)
                }

                // Reschedule curfew alarms (using the updated timezone) and sync curfew/VPN state
                CurfewScheduler.scheduleNextAlarms(appContext)
                CurfewConfig.setVpnBypassedPackages(appContext, bundle.curfewVpnBypassPackages)
                CurfewEnforcer.syncBlockState(appContext)
                if (bundle.dnsHost != effectivePreviousPolicy.dnsHost) {
                    com.drmohammed.antiporn.core.network.dns.DnsResilienceManager.resetStateSync()
                }
                if (isDeviceOwner) {
                    val requiredVpnMode = CurfewBlackholeVpnService.evaluateRequiredMode(appContext)
                    val targetAlwaysOnPkg = CurfewVpnDnsBridge.resolveTargetAlwaysOnPackage(
                        context = appContext,
                        requiredMode = requiredVpnMode,
                        shouldBlockExternalVpn = bundle.isBlockExternalVpnEnabled
                    )
                    if (targetAlwaysOnPkg != null && !CurfewVpnDnsBridge.isVpnConsentGranted(appContext)) {
                        val lockdown = (requiredVpnMode == CurfewBlackholeVpnService.VpnMode.CURFEW_BLACKHOLE)
                        val bypassed = if (lockdown) CurfewConfig.getVpnBypassedPackages(appContext) else null
                        SafeDpmGateway.setAlwaysOnVpnPackageBlocking(
                            context = appContext,
                            vpnPackage = targetAlwaysOnPkg,
                            lockdown = lockdown,
                            bypassedPackages = bypassed
                        )
                    }
                }
                CurfewBlackholeVpnService.syncPolicy(appContext)

                if (isDeviceOwner) {
                    SafeDpmGateway.executeRaw(
                        context = appContext,
                        tag = BlackBoxRecorder.TAG_SHIELD,
                        actionName = "applyBrowserAndDnsShields"
                    ) { dpm, admin ->
                        BrowserShield.applyBrowserRestrictions(dpm, admin, bundle)
                        DnsShield.apply(appContext, dpm, admin)
                        VpnShield.apply(appContext, dpm, admin)
                    }

                    enforceCustomBlacklistSuspension(bundle)

                    val approvalRepo = ApprovalRepository.getInstance(appContext)
                    reconcileBlacklistAndApprovals(bundle, effectivePreviousPolicy, approvalRepo)
                    releaseNewlyWhitelistedIfAllowed(bundle, approvalRepo)
                    reconcileProtectedPackages(bundle, effectivePreviousPolicy)
                }

                ZeroTrustForegroundWatcher.syncWatcherState(appContext)
                val blacklistChanged = bundle.customBlacklistPackages != effectivePreviousPolicy.customBlacklistPackages
                val whitelistChanged = bundle.customCurfewWhitelistPackages != effectivePreviousPolicy.customCurfewWhitelistPackages
                if (blacklistChanged || whitelistChanged) {
                    CurfewAppResolver.invalidateCache()
                    AppInventoryManager.getInstance(appContext).syncInventoryToCloudAsync(force = true)
                } else {
                    AppInventoryManager.getInstance(appContext).syncInventoryToCloud()
                }
                WaqfaSessionManager.getInstance(appContext).updateConfig(bundle.waqfaConfig)
                com.drmohammed.antiporn.pageshield.engine.PageShieldAccessibilityHook.getInstance().updateConfig(bundle.pageShieldConfig)
                reconcileAccessibilityAndCapabilities(bundle)

                synchronized(baselineLock) {
                    lastAppliedPolicyBundle = bundle
                }
                appliedSuccessfully = true
                Log.i(TAG, "Successfully applied policy bundle v${bundle.version} to system engines")
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.e(TAG, "Error applying policy bundle to system engines", e)
            } finally {
                if (!appliedSuccessfully) {
                    synchronized(baselineLock) {
                        if (lastAppliedPolicyBundle == null || pendingBaselinePreviousPolicy == null) {
                            pendingBaselinePreviousPolicy = effectivePreviousPolicy
                        }
                    }
                }
            }
        }
    }

    private suspend fun enforceTimeAndDatePolicy(bundle: DevicePolicyBundle) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && bundle.timeZoneId.isNotBlank()) {
            val validTz = if (IslamicTimeZoneRegistry.isValidIanaId(bundle.timeZoneId)) {
                bundle.timeZoneId
            } else {
                CurfewConfig.DEFAULT_TIMEZONE_ID
            }
            try {
                TimeZone.setDefault(null)
                val currentTz = TimeZone.getDefault()
                if (currentTz.id != validTz) {
                    SafeDpmGateway.setTimeZone(appContext, validTz)
                    TimeZone.setDefault(null)
                    Log.i(TAG, "Applied dynamic timezone update to: $validTz")
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.e(TAG, "Failed setting timezone ${bundle.timeZoneId} via SafeDpmGateway", e)
            }
        }

        if (bundle.isDateTimeLocked) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                try {
                    SafeDpmGateway.addUserRestriction(appContext, UserManager.DISALLOW_CONFIG_DATE_TIME)
                    Log.i(TAG, "Enforced DISALLOW_CONFIG_DATE_TIME restriction from policy bundle")
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    Log.w(TAG, "Failed applying DISALLOW_CONFIG_DATE_TIME: ${e.message}")
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                try {
                    SafeDpmGateway.setAutoTimeZoneEnabled(appContext, false)
                    SafeDpmGateway.setAutoTimeEnabled(appContext, true)
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    Log.w(TAG, "Failed setting auto time/timezone on Android 11+: ${e.message}")
                }
            } else {
                try {
                    SafeDpmGateway.setAutoTimeRequired(appContext, true)
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    Log.w(TAG, "Failed setting auto time required: ${e.message}")
                }
            }
        } else {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                try {
                    SafeDpmGateway.clearUserRestriction(appContext, UserManager.DISALLOW_CONFIG_DATE_TIME)
                    Log.i(TAG, "Cleared DISALLOW_CONFIG_DATE_TIME restriction from policy bundle (phone settings open)")
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    Log.w(TAG, "Failed clearing DISALLOW_CONFIG_DATE_TIME: ${e.message}")
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                try {
                    SafeDpmGateway.setAutoTimeZoneEnabled(appContext, true)
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    Log.w(TAG, "Failed restoring auto timezone on Android 11+: ${e.message}")
                }
            } else {
                try {
                    SafeDpmGateway.setAutoTimeRequired(appContext, false)
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    Log.w(TAG, "Failed clearing auto time required: ${e.message}")
                }
            }
        }
    }

    private suspend fun enforceCustomBlacklistSuspension(bundle: DevicePolicyBundle) {
        val criticalPackages = CurfewAppResolver.resolveCoreSystemCriticalPackages(appContext)
        val packagesToSuspend = bundle.customBlacklistPackages.filter {
            !criticalPackages.contains(it) && !AppPolicyRegistry.isImmune(it)
        }.toTypedArray()
        if (packagesToSuspend.isNotEmpty()) {
            try {
                val result = SafeDpmGateway.setPackagesSuspended(appContext, packagesToSuspend, true)
                logPackageSuspensionResult("Enforced 24/7 custom blacklist suspension", packagesToSuspend.size, result)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.e(TAG, "Failed suspending customBlacklistPackages", e)
            }
        }
    }

    private suspend fun reconcileBlacklistAndApprovals(
        bundle: DevicePolicyBundle,
        effectivePreviousPolicy: DevicePolicyBundle,
        approvalRepo: ApprovalRepository
    ) {
        val currentBlacklist = bundle.customBlacklistPackages
        val initialApprovals = queryApprovalsSafely(approvalRepo, "applyPolicyToSystem")
        val nonBaselineApprovedPackages = nonBaselineApprovedSet(initialApprovals)
        val localPendingSet = initialApprovals.filter { it.isPending }.map { it.packageName }.toSet()

        val removedFromBlacklist = (effectivePreviousPolicy.customBlacklistPackages - currentBlacklist).filter { pkg ->
            pkg != appContext.packageName &&
                !AppPolicyRegistry.isImmune(pkg) &&
                (!AppPolicyRegistry.isHighRisk(pkg) || nonBaselineApprovedPackages.contains(pkg))
        }

        val guardianName = bundle.guardianName ?: "GUARDIAN_POLICY"
        for (pkg in removedFromBlacklist) {
            if (localPendingSet.contains(pkg)) {
                Log.i(TAG, "Self-healing bridge: auto-approving package '$pkg' removed from custom blacklist by supervisor")
                unsuspendAndApproveSafely(approvalRepo, pkg, guardianName, "removed blacklist package")
            }
        }
        for (pkg in bundle.customCurfewWhitelistPackages) {
            if (localPendingSet.contains(pkg) && (!AppPolicyRegistry.isHighRisk(pkg) || nonBaselineApprovedPackages.contains(pkg))) {
                Log.i(TAG, "Self-healing bridge: auto-approving whitelisted package '$pkg' by supervisor")
                unsuspendAndApproveSafely(approvalRepo, pkg, guardianName, "whitelisted package")
            }
        }

        if (removedFromBlacklist.isNotEmpty()) {
            val isBlockActive = CurfewEnforcer.isEffectiveBlockActive(appContext)
            val immunePackages = CurfewAppResolver.resolveImmunePackages(appContext)
            val refreshedApprovals = queryApprovalsSafely(approvalRepo, "applyPolicyToSystem remaining pending")
            val blockedByZeroTrust = refreshedApprovals.filter {
                it.isPending || it.isRejected
            }.map { it.packageName }.toSet()
            val packagesToUnsuspend = removedFromBlacklist.filter { pkg ->
                (!isBlockActive || immunePackages.contains(pkg)) &&
                    !blockedByZeroTrust.contains(pkg) &&
                    (!AppPolicyRegistry.isHighRisk(pkg) || nonBaselineApprovedPackages.contains(pkg))
            }.toTypedArray()
            if (packagesToUnsuspend.isNotEmpty()) {
                try {
                    val result = SafeDpmGateway.setPackagesSuspended(appContext, packagesToUnsuspend, false)
                    logPackageSuspensionResult("Unsuspended removed blacklist packages", packagesToUnsuspend.size, result)
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    Log.e(TAG, "Failed unsuspending removed blacklist packages", e)
                }
            }
        }
    }

    private suspend fun releaseNewlyWhitelistedIfAllowed(
        bundle: DevicePolicyBundle,
        approvalRepo: ApprovalRepository
    ) {
        val newlyWhitelisted = bundle.customCurfewWhitelistPackages
        if (CurfewEnforcer.isEffectiveBlockActive(appContext) || newlyWhitelisted.isEmpty()) return

        val isZeroTrustActive = bundle.lockNewInstallsUntilApproved
        val updatedApprovals = queryApprovalsSafely(approvalRepo, "curfew whitelist release")
        val zeroTrustApproved = updatedApprovals.filter { it.isApproved }.map { it.packageName }.toSet()
        val updatedNonBaselineApproved = nonBaselineApprovedSet(updatedApprovals)
        val immunePackages = if (isZeroTrustActive) CurfewAppResolver.resolveImmunePackages(appContext) else emptySet()
        val pm = appContext.packageManager

        val whitelistedToUnsuspend = newlyWhitelisted.filter { pkg ->
            val isHighRisk = AppPolicyRegistry.isHighRisk(pkg)
            if (bundle.customBlacklistPackages.contains(pkg) || (isHighRisk && !updatedNonBaselineApproved.contains(pkg))) {
                return@filter false
            }
            val isSystemOrUpdatedApp = try {
                val ai = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    pm.getApplicationInfo(pkg, PackageManager.ApplicationInfoFlags.of(0))
                } else {
                    @Suppress("DEPRECATION")
                    pm.getApplicationInfo(pkg, 0)
                }
                (ai.flags and ApplicationInfo.FLAG_SYSTEM != 0) ||
                    (ai.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP != 0)
            } catch (_: PackageManager.NameNotFoundException) {
                return@filter false
            } catch (e: Exception) {
                Log.w(TAG, "Failed reading ApplicationInfo for whitelisted package $pkg", e)
                false
            }
            val hasApproval = !isZeroTrustActive ||
                (if (isHighRisk) updatedNonBaselineApproved.contains(pkg) else zeroTrustApproved.contains(pkg)) ||
                immunePackages.contains(pkg) ||
                isSystemOrUpdatedApp ||
                pkg == CurfewConfig.GOOGLE_PLAY_STORE_PACKAGE ||
                CurfewConfig.KNOWN_APP_STORES.contains(pkg)

            hasApproval && SafeDpmGateway.isPackageSuspendedBlocking(appContext, pkg).getOrNull() == true
        }.toTypedArray()

        if (whitelistedToUnsuspend.isNotEmpty()) {
            try {
                val result = SafeDpmGateway.setPackagesSuspended(appContext, whitelistedToUnsuspend, false)
                logPackageSuspensionResult("Daytime release for newly whitelisted packages", whitelistedToUnsuspend.size, result)
                AppInventoryManager.getInstance(appContext).syncInventoryToCloudAsync(force = true)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.e(TAG, "Failed unsuspending newly whitelisted packages", e)
            }
        }
    }

    private suspend fun reconcileProtectedPackages(
        bundle: DevicePolicyBundle,
        effectivePreviousPolicy: DevicePolicyBundle
    ) {
        for (pkg in bundle.protectedAppPackages) {
            try {
                SafeDpmGateway.setUninstallBlocked(appContext, pkg, true)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.e(TAG, "Failed setting uninstall blocked for protected package: $pkg", e)
            }
        }

        val unprotectPackages = effectivePreviousPolicy.protectedAppPackages - bundle.protectedAppPackages
        for (pkg in unprotectPackages) {
            try {
                SafeDpmGateway.setUninstallBlocked(appContext, pkg, false)
                Log.i(TAG, "Cleared uninstall blocked for unprotected package: $pkg")
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.e(TAG, "Failed clearing uninstall blocked for package: $pkg", e)
            }
        }

        try {
            val isAppsControlCurrentlyRestricted = SafeDpmGateway.hasUserRestriction(appContext, UserManager.DISALLOW_APPS_CONTROL)
            if (bundle.isClearAppDataAllowed) {
                if (isAppsControlCurrentlyRestricted) {
                    val result = SafeDpmGateway.clearUserRestriction(appContext, UserManager.DISALLOW_APPS_CONTROL)
                    if (result.isSuccess) {
                        Log.i(TAG, "Cleared DISALLOW_APPS_CONTROL restriction (clearing app data allowed by policy)")
                    } else {
                        val msg = (result as? com.drmohammed.antiporn.telemetry.DpmExecutionResult.Failure)?.message ?: "unknown error"
                        Log.w(TAG, "Failed to clear DISALLOW_APPS_CONTROL: $msg")
                    }
                }
            } else {
                if (!isAppsControlCurrentlyRestricted) {
                    val result = SafeDpmGateway.addUserRestriction(appContext, UserManager.DISALLOW_APPS_CONTROL)
                    if (result.isSuccess) {
                        Log.i(TAG, "Enforced DISALLOW_APPS_CONTROL restriction for self-protection via SafeDpmGateway and ${bundle.protectedAppPackages.size} protected packages")
                    } else {
                        val msg = (result as? com.drmohammed.antiporn.telemetry.DpmExecutionResult.Failure)?.message ?: "unknown error"
                        Log.w(TAG, "Failed to enforce DISALLOW_APPS_CONTROL: $msg")
                    }
                }
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.e(TAG, "Failed reconciling DISALLOW_APPS_CONTROL restriction", e)
        }
    }

    private suspend fun queryApprovalsSafely(
        approvalRepo: ApprovalRepository,
        contextLabel: String
    ): List<PendingApprovalEntity> {
        return try {
            approvalRepo.getLocalPendingApprovals()
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.w(TAG, "Failed querying local approvals in $contextLabel", e)
            emptyList()
        }
    }

    private suspend fun unsuspendAndApproveSafely(
        approvalRepo: ApprovalRepository,
        pkg: String,
        guardianName: String,
        label: String
    ) {
        try {
            approvalRepo.unsuspendAndApprove(pkg, guardianName)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.e(TAG, "Failed auto-approving $label '$pkg'", e)
        }
    }

    private fun nonBaselineApprovedSet(approvals: List<PendingApprovalEntity>): Set<String> {
        return approvals.filter {
            it.isApproved
        }.map { it.packageName }.toSet()
    }

    private fun logPackageSuspensionResult(
        actionLabel: String,
        attemptedCount: Int,
        result: DpmExecutionResult<Array<String>>
    ) {
        if (result.isSuccess) {
            val failed = result.getOrNull().orEmpty()
            val succeededCount = (attemptedCount - failed.size).coerceAtLeast(0)
            if (failed.isNotEmpty()) {
                Log.w(TAG, "$actionLabel partially succeeded ($succeededCount/$attemptedCount); failed=${failed.joinToString()}")
            } else {
                Log.i(TAG, "$actionLabel succeeded via SafeDpmGateway for $succeededCount package(s)")
            }
        } else {
            val failure = result as? DpmExecutionResult.Failure
            Log.e(TAG, "$actionLabel failed via SafeDpmGateway: ${failure?.errorType} - ${failure?.message}")
        }
    }

    private fun reconcileAccessibilityAndCapabilities(bundle: DevicePolicyBundle) {
        val reconcileResult = AccessibilityPolicyCoordinator.reconcile(appContext, bundle)

        if (reconcileResult is AccessibilityReconcileResult.NeedsManualActivation) {
            val isLocalVisionFallbackOnly =
                reconcileResult.reasons == setOf(AccessibilityRequirementReason.VISION_AI) &&
                    (ScreenAiProjectionService.isRunning || !bundle.isSupervised)
            if (isLocalVisionFallbackOnly) {
                cancelUrgentNotification(NotificationRegistry.EVENT_REMOTE_A11Y_REQUIRED_ID)
            } else {
                postRemoteAccessibilityRequiredNotification(reconcileResult.reasons)
            }
        } else {
            cancelUrgentNotification(NotificationRegistry.EVENT_REMOTE_A11Y_REQUIRED_ID)
        }

        val isA11yActive = HisnAccessibilityService.instance != null ||
            AccessibilitySelfActivator.isServiceEnabledInSettings(appContext)
        if (AccessibilityPolicyCoordinator.isUsageAccessRequired(bundle, isA11yActive = isA11yActive) &&
            !CurfewForegroundWatcher.hasUsageStatsPermission(appContext)
        ) {
            postRemoteUsageAccessRequiredNotification()
        } else {
            cancelUrgentNotification(NotificationRegistry.EVENT_REMOTE_USAGE_ACCESS_REQUIRED_ID)
        }

        if (!bundle.isScreenAiEnabledGlobally && ScreenAiProjectionService.isRunning) {
            try {
                appContext.stopService(Intent(appContext, ScreenAiProjectionService::class.java))
                Log.i(TAG, "Stopped ScreenAiProjectionService because policy disabled Vision Guard AI")
            } catch (e: Exception) {
                Log.w(TAG, "Failed stopping ScreenAiProjectionService during policy enforcement", e)
            }
        }

        try {
            AccountabilityRepository.getInstance(appContext).syncWardCapabilitiesAsync(recordTamper = false)
        } catch (e: Exception) {
            Log.w(TAG, "Non-critical: failed scheduling ward capabilities sync after policy enforcement", e)
        }
    }

    private fun postRemoteAccessibilityRequiredNotification(reasons: Set<AccessibilityRequirementReason>) {
        try {
            val manager = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
            val separator = appContext.getString(R.string.list_separator)
            val featureNames = reasons.map { reason ->
                when (reason) {
                    AccessibilityRequirementReason.PAGE_SHIELD -> appContext.getString(R.string.feature_name_page_shield)
                    AccessibilityRequirementReason.WAQFA_INSTANT -> appContext.getString(R.string.feature_name_waqfa)
                    AccessibilityRequirementReason.VISION_AI -> appContext.getString(R.string.feature_name_vision_ai)
                }
            }.joinToString(separator)

            val settingsIntent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            val pendingIntent = PendingIntent.getActivity(
                appContext,
                NotificationRegistry.EVENT_REMOTE_A11Y_REQUIRED_ID,
                settingsIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val title = appContext.getString(R.string.notif_remote_a11y_required_title)
            val message = appContext.getString(R.string.notif_remote_a11y_required_msg, featureNames)
            val notification = NotificationCompat.Builder(appContext, NotificationRegistry.CHANNEL_ID_PROTECTION_EVENTS)
                .setSmallIcon(R.drawable.ic_shield_device_owner)
                .setContentTitle(title)
                .setContentText(message)
                .setStyle(NotificationCompat.BigTextStyle().bigText(message))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)
                .build()

            manager.notify(NotificationRegistry.EVENT_REMOTE_A11Y_REQUIRED_ID, notification)
        } catch (e: Throwable) {
            Log.w(TAG, "Could not post remote accessibility required notification", e)
        }
    }

    private fun postRemoteUsageAccessRequiredNotification() {
        try {
            val manager = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
            val settingsIntent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            val pendingIntent = PendingIntent.getActivity(
                appContext,
                NotificationRegistry.EVENT_REMOTE_USAGE_ACCESS_REQUIRED_ID,
                settingsIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val title = appContext.getString(R.string.notif_remote_usage_access_required_title)
            val message = appContext.getString(R.string.notif_remote_usage_access_required_msg)
            val notification = NotificationCompat.Builder(appContext, NotificationRegistry.CHANNEL_ID_PROTECTION_EVENTS)
                .setSmallIcon(R.drawable.ic_shield_device_owner)
                .setContentTitle(title)
                .setContentText(message)
                .setStyle(NotificationCompat.BigTextStyle().bigText(message))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)
                .build()

            manager.notify(NotificationRegistry.EVENT_REMOTE_USAGE_ACCESS_REQUIRED_ID, notification)
        } catch (e: Throwable) {
            Log.w(TAG, "Could not post remote usage access required notification", e)
        }
    }

    private fun cancelUrgentNotification(notificationId: Int) {
        try {
            val manager = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
            manager.cancel(notificationId)
        } catch (e: Throwable) {
            Log.w(TAG, "Could not cancel notification $notificationId", e)
        }
    }

    companion object {
        private const val TAG = "AntiPorn_PolicyEnforcer"
    }
}
