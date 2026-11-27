package com.drmohammed.antiporn

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.SystemClock
import com.drmohammed.antiporn.core.policy.AppPolicyRegistry
import com.drmohammed.antiporn.curfew.CurfewAppResolver
import com.drmohammed.antiporn.curfew.CurfewConfig
import com.drmohammed.antiporn.curfew.CurfewEnforcer
import com.drmohammed.antiporn.curfew.ZeroTrustForegroundWatcher
import com.drmohammed.antiporn.data.repository.AppInventoryManager
import com.drmohammed.antiporn.data.repository.ApprovalRepository
import com.drmohammed.antiporn.data.repository.PolicyRepository
import com.drmohammed.antiporn.shields.AppSuspensionShield
import com.drmohammed.antiporn.shields.BrowserShield
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap

/**
 * Centralized, debounced engine for processing package installation and removal events.
 * Invoked by both [PackageChangeReceiver] (manifest) and [DnsWatchdogService] (dynamic receiver)
 * to ensure robust Zero-Trust enforcement without duplicate execution.
 */
object PackageChangeHandler {

    private const val TAG = "AntiPorn_PkgReceiver"
    private const val DEBOUNCE_WINDOW_MS = 3000L

    private val handlerScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val handledAddEvents = ConcurrentHashMap<String, Long>()
    private val handledRemoveEvents = ConcurrentHashMap<String, Long>()
    private val packageLocks = ConcurrentHashMap<String, Mutex>()

    private fun lockForPackage(packageName: String): Mutex =
        packageLocks.computeIfAbsent(packageName) { Mutex() }

    private fun tryAcquireDebounce(events: ConcurrentHashMap<String, Long>, packageName: String, now: Long): Boolean {
        if (events.size > 100) {
            events.entries.removeIf { now - it.value > 600_000L }
        }
        var acquired = false
        events.compute(packageName) { _, lastHandled ->
            if (lastHandled != null && now - lastHandled < DEBOUNCE_WINDOW_MS) {
                lastHandled
            } else {
                acquired = true
                now
            }
        }
        return acquired
    }

    internal data class PackageAddedDecision(
        val shouldFreeze: Boolean,
        val isCustomBlacklisted: Boolean,
        val isAlreadyApproved: Boolean,
        val autoApproveDecidedBy: String?
    )

    @androidx.annotation.VisibleForTesting
    internal fun evaluatePackageAddedDecision(
        packageName: String,
        isReplacing: Boolean,
        policy: com.drmohammed.antiporn.core.model.DevicePolicyBundle,
        localApprovals: List<com.drmohammed.antiporn.data.local.db.entity.PendingApprovalEntity>
    ): PackageAddedDecision {
        val isAlreadyApproved = localApprovals.any {
            it.packageName == packageName && it.isApproved
        }
        val isAlreadyRejected = localApprovals.any {
            it.packageName == packageName && it.isRejected
        }
        val isAlreadyPendingOrRejected = localApprovals.any {
            it.packageName == packageName && (it.isPending || it.isRejected)
        }
        val isCustomBlacklisted = policy.customBlacklistPackages.contains(packageName)
        val isReplacingUnseeded = isReplacing &&
            !isAlreadyApproved &&
            !isCustomBlacklisted &&
            !isAlreadyPendingOrRejected
        val shouldFreeze = if (isReplacingUnseeded) {
            false
        } else {
            isAlreadyRejected ||
                isCustomBlacklisted ||
                (policy.lockNewInstallsUntilApproved && !isAlreadyApproved)
        }
        val autoApproveDecidedBy = if (!shouldFreeze && !isAlreadyApproved && !isCustomBlacklisted && !isAlreadyPendingOrRejected) {
            if (isReplacingUnseeded && policy.lockNewInstallsUntilApproved) {
                "INITIAL_DEVICE_BASELINE"
            } else {
                "AUTO_APPROVED_UNLOCKED"
            }
        } else {
            null
        }
        return PackageAddedDecision(
            shouldFreeze = shouldFreeze,
            isCustomBlacklisted = isCustomBlacklisted,
            isAlreadyApproved = isAlreadyApproved,
            autoApproveDecidedBy = autoApproveDecidedBy
        )
    }

    /**
     * Handles package installation / update with Zero-Trust enforcement,
     * thread-safe debouncing, and rich diagnostic logging.
     */
    fun handlePackageAdded(
        context: Context,
        packageName: String,
        isReplacing: Boolean,
        source: String,
        onComplete: (() -> Unit)? = null
    ) {
        val now = SystemClock.elapsedRealtime()
        if (!tryAcquireDebounce(handledAddEvents, packageName, now)) {
            Timber.tag(TAG).d("[DEBOUNCE] Skipping duplicate package added event for $packageName from $source")
            onComplete?.invoke()
            return
        }

        Timber.tag(TAG).w("[PACKAGE_ADDED] New package detected: $packageName (source: $source, isReplacing: $isReplacing)")
        CurfewAppResolver.invalidateCache()

        val appContext = context.applicationContext
        try {
            handlerScope.launch {
                try {
                    lockForPackage(packageName).withLock {
                        val approvalRepo = ApprovalRepository.getInstance(appContext)
                        val isHighRisk = AppPolicyRegistry.isHighRisk(packageName)
                        val localApprovals = approvalRepo.getLocalPendingApprovals()
                        val policy = PolicyRepository.getInstance(appContext).getCurrentPolicy()
                        val decision = evaluatePackageAddedDecision(
                            packageName = packageName,
                            isReplacing = isReplacing,
                            policy = policy,
                            localApprovals = localApprovals
                        )
                        val isAlreadyApproved = decision.isAlreadyApproved
                        val isCustomBlacklisted = decision.isCustomBlacklisted
                        val shouldFreeze = decision.shouldFreeze

                        Timber.tag(TAG).w("[ZERO_TRUST_CHECK] Package: $packageName | lockNewInstalls=${policy.lockNewInstallsUntilApproved} | isHighRisk=$isHighRisk | isCustomBlacklisted=$isCustomBlacklisted | isReplacing=$isReplacing | isAlreadyApproved=$isAlreadyApproved | shouldFreeze=$shouldFreeze")

                        if (shouldFreeze) {
                            val isImmune = CurfewAppResolver.isSystemCriticalOrImmune(appContext, packageName)
                            if (isImmune) {
                                Timber.tag(TAG).w("[IMMUNE_SKIP] Package $packageName is critical system component, launcher, dialer, or keyboard. Skipping freeze.")
                            } else if (isCustomBlacklisted && isAlreadyApproved) {
                                Timber.tag(TAG).w("[BLACKLIST_FREEZE] Freezing custom-blacklisted package $packageName without overwriting existing approval state")
                                val msg = appContext.getString(R.string.toast_app_suspended_by_guardian)
                                com.drmohammed.antiporn.telemetry.SafeDpmGateway.setPackageSuspendedWithMessageBlocking(
                                    context = appContext,
                                    packageName = packageName,
                                    message = msg
                                )
                            } else {
                                Timber.tag(TAG).w("[ZERO_TRUST_FREEZE] Zero-Trust active: Freezing unapproved package $packageName (isReplacing=$isReplacing, isHighRisk=$isHighRisk) in 0ms and submitting approval ticket")
                                val frozen = approvalRepo.freezeAndSubmitApprovalLocal(packageName, isHighRisk = isHighRisk)
                                Timber.tag(TAG).w("[FREEZE_STATUS] Result of local freeze for $packageName: $frozen")
                            }
                        } else {
                            val decidedBy = decision.autoApproveDecidedBy
                            if (decidedBy != null) {
                                val appName = resolveAppLabelSafe(appContext, packageName)
                                Timber.tag(TAG).i("[AUTO_APPROVE] Recording approval ($decidedBy) for app $packageName ($appName)")
                                approvalRepo.recordApproval(packageName, appName, decidedBy)
                            }
                            Timber.tag(TAG).w("[CURFEW_EVAL] Package $packageName approved or not subject to Zero-Trust freeze (replacing=$isReplacing, approved=$isAlreadyApproved). Evaluating curfew status...")
                            CurfewEnforcer.handlePackageAdded(appContext, packageName)
                        }

                        // Auto-whitelist Islamic/Quran, productive, or essential applications in curfew policy
                        // Strictly prevent rogue app/browser/store curfew bypasses and respect Zero-Trust locking
                        val isZeroTrustBlocked = shouldFreeze
                        val isBypassTarget = AppPolicyRegistry.isRestrictedBrowser(packageName) ||
                            CurfewConfig.KNOWN_APP_STORES.contains(packageName) ||
                            CurfewConfig.CURFEW_SEARCH_APPS.contains(packageName) ||
                            policy.customBlacklistPackages.contains(packageName)

                        if (!isZeroTrustBlocked && !isBypassTarget) {
                            try {
                                val isExplicitlyExempt = AppPolicyRegistry.isExemptFromCurfew(packageName)
                                val pm = appContext.packageManager
                                val appInfo = getApplicationInfoCompat(pm, packageName)

                                val isEligible = isExplicitlyExempt || (appInfo != null && (
                                    CurfewAppResolver.isIslamicOrQuranApp(pm, appInfo) ||
                                        CurfewAppResolver.isAudiobookOrPodcastApp(pm, appInfo) ||
                                        CurfewAppResolver.isMedicalOrStudyApp(pm, appInfo) ||
                                        CurfewAppResolver.isMediaOrAlternativePlayerApp(pm, appInfo)
                                    ))

                                if (isEligible) {
                                    PolicyRepository.getInstance(appContext).addAppToCurfewWhitelist(packageName)
                                }
                            } catch (e: Exception) {
                                Timber.tag(TAG).w(e, "Error checking auto-whitelist eligibility for $packageName")
                            }
                        }

                        if (AppPolicyRegistry.isRestrictedBrowser(packageName)) {
                            try {
                                val currentPolicy = PolicyRepository.getInstance(appContext).getCurrentPolicy()
                                val restrictionsBundle = BrowserShield.buildBrowserRestrictionsBundle(currentPolicy)
                                com.drmohammed.antiporn.telemetry.SafeDpmGateway.setApplicationRestrictionsBlocking(appContext, packageName, restrictionsBundle)
                                Timber.tag(TAG).i("Applied browser restrictions to newly added/replaced restricted browser via SafeDpmGateway: $packageName")
                            } catch (e: Exception) {
                                Timber.tag(TAG).e(e, "Failed applying browser restrictions to $packageName")
                            }
                        }

                        // Active foreground monitoring sync for newly blocked or pending package
                        ZeroTrustForegroundWatcher.syncWatcherState(appContext)
                    }

                    // Dispatch cloud synchronization asynchronously
                    handlerScope.launch {
                        try {
                            ApprovalRepository.getInstance(appContext).syncPendingApprovalsToCloud()
                        } catch (e: Exception) {
                            Timber.tag(TAG).w("Async approval cloud sync failed for $packageName: ${e.message}")
                        }
                        try {
                            AppInventoryManager.getInstance(appContext).syncInventoryToCloud(force = true)
                        } catch (e: Exception) {
                            Timber.tag(TAG).w("Async inventory sync failed for $packageName: ${e.message}")
                        }
                    }
                } catch (e: Exception) {
                    Timber.tag(TAG).e(e, "[ERROR] Error handling added package $packageName in $source")
                } finally {
                    onComplete?.invoke()
                }
            }
        } catch (e: Throwable) {
            Timber.tag(TAG).e(e, "Failed to launch coroutine in handlePackageAdded")
            onComplete?.invoke()
        }
    }

    private fun getApplicationInfoCompat(
        pm: android.content.pm.PackageManager?,
        packageName: String
    ): android.content.pm.ApplicationInfo? {
        if (pm == null) return null
        return try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                pm.getApplicationInfo(packageName, android.content.pm.PackageManager.ApplicationInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.getApplicationInfo(packageName, 0)
            }
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Failed reading ApplicationInfo for $packageName")
            null
        }
    }

    private fun resolveAppLabelSafe(context: Context, packageName: String): String {
        val pm = context.packageManager ?: return packageName
        val appInfo = getApplicationInfoCompat(pm, packageName) ?: return packageName
        return try {
            pm.getApplicationLabel(appInfo).toString()
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Failed resolving label for $packageName")
            packageName
        }
    }

    /**
     * Handles package uninstallation, cleaning up approval records and syncing watcher state.
     */
    fun handlePackageRemoved(
        context: Context,
        packageName: String,
        isReplacing: Boolean = false,
        source: String,
        onComplete: (() -> Unit)? = null
    ) {
        if (isReplacing) {
            Timber.tag(TAG).i("[PACKAGE_UPDATING] Package $packageName is being replaced/updated. Preserving approval records & watcher state.")
            onComplete?.invoke()
            return
        }

        val now = SystemClock.elapsedRealtime()
        if (!tryAcquireDebounce(handledRemoveEvents, packageName, now)) {
            Timber.tag(TAG).d("[DEBOUNCE] Skipping duplicate package removed event for $packageName from $source")
            onComplete?.invoke()
            return
        }

        Timber.tag(TAG).i("[PACKAGE_REMOVED] Package removed: $packageName (source: $source)")
        CurfewAppResolver.invalidateCache()

        val appContext = context.applicationContext
        try {
            handlerScope.launch {
                try {
                    lockForPackage(packageName).withLock {
                        ApprovalRepository.getInstance(appContext).removePendingApproval(packageName)
                        ZeroTrustForegroundWatcher.syncWatcherState(appContext)
                    }

                    try {
                        AppInventoryManager.getInstance(appContext).syncInventoryToCloud(force = true)
                    } catch (e: Exception) {
                        Timber.tag(TAG).w("Async inventory sync failed on package removed: $packageName: ${e.message}")
                    }
                } catch (e: Exception) {
                    Timber.tag(TAG).e(e, "[ERROR] Error handling package removed: $packageName")
                } finally {
                    onComplete?.invoke()
                }
            }
        } catch (e: Throwable) {
            Timber.tag(TAG).e(e, "Failed to launch coroutine in handlePackageRemoved")
            onComplete?.invoke()
        }
    }
}

/**
 * Manifest-declared BroadcastReceiver for package lifecycle events.
 * Serves as fallback and handles silent uninstallation statuses.
 */
class PackageChangeReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "AntiPorn_PkgReceiver"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent == null) return
        val action = intent.action ?: return
        val data = intent.data
        val packageName = data?.schemeSpecificPart

        val isDeviceOwner = com.drmohammed.antiporn.core.engine.ProtectionEngine.isDeviceOwner(context)
        Timber.tag(TAG).w("[STATIC_RECEIVER] onReceive: action=$action | package=$packageName | isDeviceOwner=$isDeviceOwner")

        when (action) {
            Intent.ACTION_PACKAGE_ADDED -> {
                if (packageName.isNullOrBlank()) return
                val isReplacing = intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)
                val pendingResult = goAsync()
                try {
                    PackageChangeHandler.handlePackageAdded(
                        context = context,
                        packageName = packageName,
                        isReplacing = isReplacing,
                        source = "Static_Manifest",
                        onComplete = {
                            try {
                                pendingResult.finish()
                            } catch (e: Exception) {
                                Timber.tag(TAG).w(e, "Error finishing pendingResult in onComplete")
                            }
                        }
                    )
                } catch (e: Throwable) {
                    Timber.tag(TAG).e(e, "Exception invoking handlePackageAdded")
                    try {
                        pendingResult.finish()
                    } catch (_: Exception) {}
                }
            }

            Intent.ACTION_PACKAGE_REMOVED -> {
                if (packageName.isNullOrBlank()) return
                val isReplacing = intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)
                if (isReplacing) {
                    Timber.tag(TAG).i("[STATIC_RECEIVER] Package $packageName is being replaced/updated (EXTRA_REPLACING=true). Ignoring temporary removal.")
                    return
                }
                val pendingResult = goAsync()
                try {
                    PackageChangeHandler.handlePackageRemoved(
                        context = context,
                        packageName = packageName,
                        isReplacing = isReplacing,
                        source = "Static_Manifest",
                        onComplete = {
                            try {
                                pendingResult.finish()
                            } catch (e: Exception) {
                                Timber.tag(TAG).w(e, "Error finishing pendingResult in onComplete")
                            }
                        }
                    )
                } catch (e: Throwable) {
                    Timber.tag(TAG).e(e, "Exception invoking handlePackageRemoved")
                    try {
                        pendingResult.finish()
                    } catch (_: Exception) {}
                }
            }

            AppSuspensionShield.ACTION_UNINSTALL_STATUS -> {
                val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
                val pkgName = intent.getStringExtra(PackageInstaller.EXTRA_PACKAGE_NAME) ?: "unknown"
                val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: ""
                when (status) {
                    PackageInstaller.STATUS_SUCCESS -> {
                        Timber.tag(TAG).i("[UNINSTALL_SUCCESS] Silent uninstall succeeded for $pkgName")
                    }
                    PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                        Timber.tag(TAG).w("[UNINSTALL_PENDING] Silent uninstall requires user action for $pkgName")
                    }
                    else -> {
                        Timber.tag(TAG).w("[UNINSTALL_FAILED] Silent uninstall failed for $pkgName (status: $status, msg: $message)")
                    }
                }
            }
        }
    }
}
