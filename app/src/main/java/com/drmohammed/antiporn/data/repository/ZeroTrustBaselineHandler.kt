package com.drmohammed.antiporn.data.repository

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import com.drmohammed.antiporn.R
import com.drmohammed.antiporn.core.engine.ProtectionEngine
import com.drmohammed.antiporn.core.model.DevicePolicyBundle
import com.drmohammed.antiporn.core.policy.AppPolicyRegistry
import com.drmohammed.antiporn.core.time.TrustedTimeProvider
import com.drmohammed.antiporn.core.util.SafeAndroidLog as Log
import com.drmohammed.antiporn.curfew.CurfewAppResolver
import com.drmohammed.antiporn.curfew.CurfewConfig
import com.drmohammed.antiporn.data.local.db.dao.PendingApprovalDao
import com.drmohammed.antiporn.data.local.db.entity.PendingApprovalEntity
import com.drmohammed.antiporn.telemetry.SafeDpmGateway

/**
 * Handles Zero-Trust initial device baseline seeding, stray-app reconciliation,
 * and one-time legacy high-risk freeze migration for [ApprovalRepository].
 * Slow PM/DPM inspection passes run outside [stateLock]; state commits and
 * unfreeze actions acquire [stateLock] briefly to stay atomic against concurrent decisions.
 */
internal class ZeroTrustBaselineHandler(
    private val appContext: Context,
    private val approvalDao: PendingApprovalDao,
    private val stateLock: Any,
    private val policyProvider: () -> DevicePolicyBundle,
    private val currentTimeMillisProvider: () -> Long,
    private val isDeviceOwnerProvider: () -> Boolean,
    private val isBlockActiveProvider: () -> Boolean,
    private val immunePackagesProvider: () -> Set<String>,
    private val appLabelResolver: (String) -> String,
    private val installedPackagesProvider: () -> (() -> List<PackageInfo>)?,
    private val isPackageSuspendedProvider: () -> (String) -> Boolean,
    private val isAppHiddenProvider: () -> (String) -> Boolean,
    private val setAppHiddenProvider: () -> (String, Boolean) -> Boolean,
    private val setPackagesSuspendedProvider: () -> (Array<String>, Boolean) -> Boolean,
    private val systemCriticalChecker: () -> (String) -> Boolean,
    private val watcherSyncer: () -> () -> Unit,
    private val notificationCanceller: (String) -> Unit,
    private val cloudDeleter: (String) -> Unit,
    private val freezeLocalFn: (packageName: String, showToast: Boolean, isHighRisk: Boolean) -> Boolean,
    private val cloudSyncTrigger: () -> Unit
) {

    private data class InstalledPackagesQueryResult(
        val packages: List<PackageInfo>,
        val queryFailed: Boolean
    )

    fun ensureInitialBaselineSeeded() {
        val prefs = appContext.getSharedPreferences(ApprovalRepository.PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.getBoolean(ApprovalRepository.KEY_BASELINE_SEEDED, false)) return

        Log.i(TAG, "🌱 [SEED_BASELINE] Establishing Initial Zero-Trust Baseline for pre-existing apps...")
        val pm = appContext.packageManager
        val isDo = SafeDpmGateway.isDeviceOwner(appContext)
        val queryResult = getInstalledPackagesCompat(pm, "baseline seeding")
        val installedPackages = queryResult.packages

        val packagesToUnsuspend = mutableListOf<String>()
        val packagesToUnhide = mutableListOf<String>()
        val candidateEntities = mutableListOf<PendingApprovalEntity>()

        val policy = if (isDo) policyProvider() else null
        val isBlockActive = if (isDo) isBlockActiveProvider() else false
        val immunePackages = if (isDo) immunePackagesProvider() else emptySet()

        for (pkgInfo in installedPackages) {
            val pkg = pkgInfo.packageName ?: continue
            if (pkg == appContext.packageName) continue

            val appName = resolveAppName(pm, pkgInfo)
            val nowMs = TrustedTimeProvider.currentTimeMillis(appContext)
            candidateEntities.add(
                PendingApprovalEntity(
                    packageName = pkg,
                    appName = appName,
                    requestedAt = nowMs,
                    status = PendingApprovalEntity.STATUS_APPROVED,
                    decidedBy = "INITIAL_DEVICE_BASELINE",
                    decidedAt = nowMs,
                    isCloudSynced = true
                )
            )

            if (isDo && policy != null) {
                val isWhitelisted = CurfewConfig.STATIC_WHITELIST.contains(pkg) || immunePackages.contains(pkg)
                if (policy.customBlacklistPackages.contains(pkg)) continue
                val isHidden = isAppHiddenProvider()(pkg)
                if (isHidden) packagesToUnhide.add(pkg)
                if (!isBlockActive || isWhitelisted) {
                    if (isPackageSuspendedProvider()(pkg) || isHidden) {
                        packagesToUnsuspend.add(pkg)
                    }
                }
            }
        }

        var baselineClean = !queryResult.queryFailed
        synchronized(stateLock) {
            val existingByPkg = approvalDao.getPendingApprovalsBlocking().associateBy { it.packageName }
            val safeEntities = candidateEntities.filter { entity ->
                val existing = existingByPkg[entity.packageName]
                existing == null || existing.isPending
            }
            val safeSet = safeEntities.map { it.packageName }.toSet()
            val safeUnhide = packagesToUnhide.filter { existingByPkg[it]?.isRejected != true && (safeSet.contains(it) || existingByPkg[it]?.isApproved == true) }
            val safeUnsuspend = packagesToUnsuspend.filter { existingByPkg[it]?.isRejected != true && (safeSet.contains(it) || existingByPkg[it]?.isApproved == true) }

            if (safeEntities.isNotEmpty()) {
                approvalDao.savePendingApprovalsBlocking(safeEntities)
            }
            if (isDo) {
                for (pkg in safeUnhide) {
                    try {
                        if (!setAppHiddenProvider()(pkg, false)) baselineClean = false
                    } catch (e: Exception) {
                        baselineClean = false
                        Log.w(TAG, "Failed to unhide baseline package $pkg", e)
                    }
                }
                if (safeUnsuspend.isNotEmpty()) {
                    Log.w(TAG, "🌱 [UNFREEZE_BASELINE] Unfreezing ${safeUnsuspend.size} pre-existing baseline packages...")
                    try {
                        if (!setPackagesSuspendedProvider()(safeUnsuspend.toTypedArray(), false)) {
                            baselineClean = false
                        }
                    } catch (e: Exception) {
                        baselineClean = false
                        Log.e(TAG, "Failed to unsuspend baseline packages via DPM", e)
                    }
                }
            }
        }

        watcherSyncer()()
        if (baselineClean) {
            prefs.edit().putBoolean(ApprovalRepository.KEY_BASELINE_SEEDED, true).apply()
            Log.i(TAG, "✅ [SEED_BASELINE] Initial Zero-Trust Baseline successfully established with ${installedPackages.size} packages.")
        } else {
            Log.w(TAG, "⚠️ [SEED_BASELINE] Baseline seeding completed with PM/DPM failures; deferring completion flag for retry.")
        }
    }

    fun reconcileUnapprovedInstalledApps() {
        if (!ProtectionEngine.isDeviceOwner(appContext)) return
        val policy = policyProvider()
        if (!policy.lockNewInstallsUntilApproved) return

        ensureInitialBaselineSeeded()

        if (!SafeDpmGateway.isDeviceOwner(appContext)) return
        val pm = appContext.packageManager ?: return
        val queryResult = getInstalledPackagesCompat(pm, "Zero-Trust reconciliation")
        if (queryResult.queryFailed) return
        val installedPackages = queryResult.packages

        val localApprovals = approvalDao.getPendingApprovalsBlocking()
        val approvedSet = localApprovals.filter { it.isApproved }.map { it.packageName }.toSet()
        val pendingSet = localApprovals.filter { it.isPending }.map { it.packageName }.toSet()
        val rejectedSet = localApprovals.filter { it.isRejected }.map { it.packageName }.toSet()

        var strayAppFrozen = false
        val doEnabledAt = getDeviceOwnerEnabledTimestamp()

        for (pkgInfo in installedPackages) {
            val pkg = pkgInfo.packageName ?: continue
            if (pkg == appContext.packageName) continue
            if (pkg == CurfewConfig.GOOGLE_PLAY_STORE_PACKAGE || CurfewConfig.KNOWN_APP_STORES.contains(pkg)) continue

            val appInfo = pkgInfo.applicationInfo
            val isSystemApp = appInfo != null && (
                (appInfo.flags and ApplicationInfo.FLAG_SYSTEM != 0) ||
                    (appInfo.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP != 0)
                )
            if (isSystemApp) continue

            if (approvedSet.contains(pkg)) {
                try {
                    if (isAppHiddenProvider()(pkg)) {
                        setAppHiddenProvider()(pkg, false)
                        Log.i(TAG, "Universal Self-Healing: Unhid legacy hidden approved package $pkg")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Universal Self-Healing failed for approved $pkg", e)
                }
                continue
            }
            if (CurfewAppResolver.isSystemCriticalOrImmune(appContext, pkg)) continue

            if (!rejectedSet.contains(pkg)) {
                try {
                    if (isAppHiddenProvider()(pkg)) {
                        setAppHiddenProvider()(pkg, false)
                        Log.i(TAG, "Universal Self-Healing: Unhid legacy hidden package $pkg")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Universal Self-Healing failed for $pkg", e)
                }
            }

            if (policy.customCurfewWhitelistPackages.contains(pkg)) {
                Log.i(TAG, "🌱 [AUTO_ADOPT_WHITELISTED] Package '$pkg' is in customCurfewWhitelistPackages. Auto-adopting into approved baseline.")
                val appName = resolveAppName(pm, pkgInfo)
                val nowMs = TrustedTimeProvider.currentTimeMillis(appContext)
                synchronized(stateLock) {
                    val latest = approvalDao.getApprovalByPackageNameBlocking(pkg)
                    if (latest?.isRejected != true) {
                        approvalDao.savePendingApprovalBlocking(
                            PendingApprovalEntity(
                                packageName = pkg,
                                appName = appName,
                                requestedAt = pkgInfo.firstInstallTime.takeIf { it > 0 } ?: nowMs,
                                status = PendingApprovalEntity.STATUS_APPROVED,
                                decidedBy = "GUARDIAN_POLICY_WHITELIST",
                                decidedAt = nowMs,
                                isCloudSynced = true
                            )
                        )
                    }
                }
                continue
            }

            val firstInstallTime = pkgInfo.firstInstallTime
            val isPreExisting = doEnabledAt > 0L && firstInstallTime > 0L && firstInstallTime <= (doEnabledAt + ApprovalRepository.DO_ACTIVATION_GRACE_PERIOD_MS)
            if (isPreExisting) {
                Log.i(TAG, "🌱 [AUTO_ADOPT_PRE_EXISTING] Package '$pkg' was installed prior to Device Owner activation ($firstInstallTime <= $doEnabledAt). Auto-adopting into approved baseline.")
                val appName = resolveAppName(pm, pkgInfo)
                val nowMs = TrustedTimeProvider.currentTimeMillis(appContext)
                synchronized(stateLock) {
                    val latest = approvalDao.getApprovalByPackageNameBlocking(pkg)
                    if (latest?.isRejected != true) {
                        approvalDao.savePendingApprovalBlocking(
                            PendingApprovalEntity(
                                packageName = pkg,
                                appName = appName,
                                requestedAt = firstInstallTime,
                                status = PendingApprovalEntity.STATUS_APPROVED,
                                decidedBy = "INITIAL_DEVICE_BASELINE",
                                decidedAt = nowMs,
                                isCloudSynced = true
                            )
                        )
                    }
                }
                continue
            }

            if (rejectedSet.contains(pkg)) {
                val isSuspended = isPackageSuspendedProvider()(pkg)
                val isHidden = isAppHiddenProvider()(pkg)
                if (!isSuspended && !isHidden) {
                    Log.w(TAG, "🚨 [RECONCILE_REJECTED_APP] Rejected package '$pkg' was active. Enforcing suspension/hide!")
                    val suspended = SafeDpmGateway.setPackageSuspendedWithMessageBlocking(
                        context = appContext,
                        packageName = pkg,
                        message = appContext.getString(R.string.toast_app_suspended_by_guardian)
                    ).getOrNull() == true
                    if (!suspended) {
                        try {
                            setAppHiddenProvider()(pkg, true)
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to hide rejected package $pkg", e)
                        }
                    }
                }
                continue
            }

            if (pendingSet.contains(pkg)) {
                if (!isPackageSuspendedProvider()(pkg)) {
                    val isHighRisk = AppPolicyRegistry.isHighRisk(pkg)
                    val msg = if (isHighRisk) {
                        appContext.getString(R.string.toast_high_risk_app_suspended)
                    } else {
                        appContext.getString(R.string.toast_app_suspended_by_guardian)
                    }
                    SafeDpmGateway.setPackageSuspendedWithMessageBlocking(appContext, pkg, msg)
                }
                continue
            }

            Log.w(TAG, "🚨 [RECONCILE_STRAY_APP] Found unapproved active package '$pkg' (escaped receiver). Freezing silently in background!")
            val isHighRisk = AppPolicyRegistry.isHighRisk(pkg)
            if (freezeLocalFn(pkg, false, isHighRisk)) {
                strayAppFrozen = true
            }
        }

        if (strayAppFrozen) {
            cloudSyncTrigger()
        }
    }

    fun getDeviceOwnerEnabledTimestamp(): Long {
        val prefs = appContext.getSharedPreferences(ApprovalRepository.PREFS_NAME, Context.MODE_PRIVATE)
        var ts = prefs.getLong(ApprovalRepository.KEY_DEVICE_OWNER_ENABLED_AT, 0L)
        if (ts == 0L && ProtectionEngine.isDeviceOwner(appContext)) {
            ts = TrustedTimeProvider.currentTimeMillis(appContext)
            prefs.edit().putLong(ApprovalRepository.KEY_DEVICE_OWNER_ENABLED_AT, ts).apply()
            Log.i(TAG, "Recorded initial Device Owner enabled timestamp: $ts")
        }
        return ts
    }

    fun recordDeviceOwnerEnabledTimestamp(timestamp: Long = TrustedTimeProvider.currentTimeMillis(appContext)) {
        val prefs = appContext.getSharedPreferences(ApprovalRepository.PREFS_NAME, Context.MODE_PRIVATE)
        val existing = prefs.getLong(ApprovalRepository.KEY_DEVICE_OWNER_ENABLED_AT, 0L)
        if (existing == 0L || timestamp > 0L) {
            prefs.edit().putLong(ApprovalRepository.KEY_DEVICE_OWNER_ENABLED_AT, timestamp).apply()
            Log.i(TAG, "Device Owner enabled timestamp recorded: $timestamp")
        }
    }

    /**
     * One-time migration for existing devices to unfreeze high-risk apps that were
     * forcibly frozen by legacy baseline/reconciliation logic.
     */
    fun migrateLegacyHighRiskFreezesIfNeeded() {
        val prefs = appContext.getSharedPreferences(ApprovalRepository.PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.getBoolean(ApprovalRepository.KEY_HIGH_RISK_NORMALIZATION_V1, false)) return
        if (!isDeviceOwnerProvider()) return

        val policy = policyProvider()
        val pm = appContext.packageManager
        val customProvider = installedPackagesProvider()
        val queryResult = if (customProvider != null) {
            InstalledPackagesQueryResult(customProvider.invoke(), queryFailed = false)
        } else {
            val nonNullPm = pm ?: return
            getInstalledPackagesCompat(nonNullPm, "legacy high-risk freeze normalization")
        }
        val installedPackages = queryResult.packages
        val approvalMap = approvalDao.getPendingApprovalsBlocking().associateBy { it.packageName }
        val isBlockActive = isBlockActiveProvider()
        val immunePackages = immunePackagesProvider()
        val doEnabledAt = getDeviceOwnerEnabledTimestamp()
        val nowMs = currentTimeMillisProvider()

        val entitiesToApprove = mutableListOf<PendingApprovalEntity>()
        val packagesToUnsuspend = mutableListOf<String>()
        val packagesToUnhide = mutableListOf<String>()
        val migratedPendingPackages = mutableListOf<String>()
        val criticalChecker = systemCriticalChecker()

        for (pkgInfo in installedPackages) {
            val pkg = pkgInfo.packageName ?: continue
            if (pkg == appContext.packageName) continue
            if (!AppPolicyRegistry.isHighRisk(pkg)) continue

            val appInfo = pkgInfo.applicationInfo
            val isSystemApp = appInfo != null && (
                (appInfo.flags and ApplicationInfo.FLAG_SYSTEM != 0) ||
                    (appInfo.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP != 0)
                )
            if (isSystemApp) continue
            if (pkg == CurfewConfig.GOOGLE_PLAY_STORE_PACKAGE || CurfewConfig.KNOWN_APP_STORES.contains(pkg)) continue
            if (criticalChecker(pkg)) continue
            if (policy.customBlacklistPackages.contains(pkg)) continue

            val existingApproval = approvalMap[pkg]
            if (existingApproval?.isRejected == true) continue
            if (com.drmohammed.antiporn.waqfa.engine.WaqfaCooldownStore.isPackageInCooldown(appContext, pkg)) continue
            if (com.drmohammed.antiporn.pageshield.engine.PageShieldFreezeStore.isFrozen(appContext, pkg)) continue

            val isNullOrPending = existingApproval == null || existingApproval.isPending
            val decidedBy: String? = if (!policy.lockNewInstallsUntilApproved) {
                "AUTO_APPROVED_UNLOCKED"
            } else if (
                isNullOrPending &&
                doEnabledAt > 0L &&
                pkgInfo.firstInstallTime > 0L &&
                pkgInfo.firstInstallTime <= (doEnabledAt + ApprovalRepository.DO_ACTIVATION_GRACE_PERIOD_MS)
            ) {
                "INITIAL_DEVICE_BASELINE"
            } else if (existingApproval?.isApproved == true) {
                existingApproval.decidedBy ?: "INITIAL_DEVICE_BASELINE"
            } else {
                null
            }
            if (decidedBy == null) continue

            if (isNullOrPending) {
                if (existingApproval?.isPending == true) {
                    migratedPendingPackages.add(pkg)
                }
                entitiesToApprove.add(
                    PendingApprovalEntity(
                        packageName = pkg,
                        appName = resolveAppName(pm, pkgInfo),
                        requestedAt = pkgInfo.firstInstallTime.takeIf { it > 0L } ?: nowMs,
                        status = PendingApprovalEntity.STATUS_APPROVED,
                        decidedBy = decidedBy,
                        decidedAt = nowMs,
                        isCloudSynced = true
                    )
                )
            }

            if (isAppHiddenProvider()(pkg)) {
                packagesToUnhide.add(pkg)
            }
            if (!isBlockActive || immunePackages.contains(pkg)) {
                if (isPackageSuspendedProvider()(pkg)) {
                    packagesToUnsuspend.add(pkg)
                }
            }
        }

        var migrationClean = !queryResult.queryFailed
        var committedChanges = false
        synchronized(stateLock) {
            val latestByPkg = approvalDao.getPendingApprovalsBlocking().associateBy { it.packageName }
            val safeEntities = entitiesToApprove.filter { entity ->
                val latest = latestByPkg[entity.packageName]
                latest == null || latest.isPending
            }
            val safeUnhide = packagesToUnhide.filter { latestByPkg[it]?.isRejected != true }
            val safeUnsuspend = packagesToUnsuspend.filter { latestByPkg[it]?.isRejected != true }

            if (safeEntities.isNotEmpty()) {
                approvalDao.savePendingApprovalsBlocking(safeEntities)
                committedChanges = true
            }
            for (pkg in safeUnhide) {
                try {
                    if (!setAppHiddenProvider()(pkg, false)) migrationClean = false
                    committedChanges = true
                } catch (e: Exception) {
                    migrationClean = false
                    Log.w(TAG, "Failed to unhide migrated high-risk package $pkg", e)
                }
            }
            if (safeUnsuspend.isNotEmpty()) {
                Log.i(TAG, "Normalized ${safeUnsuspend.size} legacy high-risk frozen packages")
                try {
                    if (!setPackagesSuspendedProvider()(safeUnsuspend.toTypedArray(), false)) {
                        migrationClean = false
                    }
                    committedChanges = true
                } catch (e: Exception) {
                    migrationClean = false
                    Log.e(TAG, "Failed to unsuspend migrated high-risk packages via DPM", e)
                }
            }
        }

        for (pkg in migratedPendingPackages) {
            notificationCanceller(pkg)
            cloudDeleter(pkg)
        }
        if (committedChanges) {
            watcherSyncer()()
        }
        if (migrationClean) {
            prefs.edit().putBoolean(ApprovalRepository.KEY_HIGH_RISK_NORMALIZATION_V1, true).apply()
        } else {
            Log.w(TAG, "Deferred KEY_HIGH_RISK_NORMALIZATION_V1 due to PM/DPM failure; will retry on next reconcile.")
        }
    }

    private fun getInstalledPackagesCompat(pm: PackageManager, logContext: String): InstalledPackagesQueryResult {
        val matchFlags = PackageManager.MATCH_UNINSTALLED_PACKAGES or PackageManager.MATCH_DISABLED_COMPONENTS
        return try {
            val list = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getInstalledPackages(PackageManager.PackageInfoFlags.of(matchFlags.toLong())) ?: emptyList()
            } else {
                @Suppress("DEPRECATION")
                pm.getInstalledPackages(matchFlags) ?: emptyList()
            }
            InstalledPackagesQueryResult(list, queryFailed = false)
        } catch (e: Exception) {
            Log.w(TAG, "Failed retrieving installed packages for $logContext", e)
            InstalledPackagesQueryResult(emptyList(), queryFailed = true)
        }
    }

    private fun resolveAppName(pm: PackageManager?, pkgInfo: PackageInfo): String {
        val pkg = pkgInfo.packageName ?: return ""
        val appInfo = pkgInfo.applicationInfo
        return try {
            if (pm != null && appInfo != null) pm.getApplicationLabel(appInfo).toString() else appLabelResolver(pkg)
        } catch (e: Exception) {
            Log.w(TAG, "Failed resolving label for $pkg", e)
            pkg
        }
    }

    companion object {
        private const val TAG = "AntiPorn_Baseline"
    }
}
