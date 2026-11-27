package com.drmohammed.antiporn.data.repository

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import androidx.annotation.VisibleForTesting
import com.drmohammed.antiporn.R
import com.drmohammed.antiporn.core.engine.ProtectionEngine
import com.drmohammed.antiporn.core.model.DevicePolicyBundle
import com.drmohammed.antiporn.core.policy.AppPolicyRegistry
import com.drmohammed.antiporn.core.time.TrustedTimeProvider
import com.drmohammed.antiporn.core.util.SafeAndroidLog as Log
import com.drmohammed.antiporn.curfew.CurfewAppResolver
import com.drmohammed.antiporn.curfew.CurfewConfig
import com.drmohammed.antiporn.curfew.CurfewEnforcer
import com.drmohammed.antiporn.curfew.ZeroTrustForegroundWatcher
import com.drmohammed.antiporn.data.local.db.HisnRoomDatabase
import com.drmohammed.antiporn.data.local.db.dao.PendingApprovalDao
import com.drmohammed.antiporn.data.local.db.entity.PendingApprovalEntity
import com.drmohammed.antiporn.telemetry.DpmExecutionResult
import com.drmohammed.antiporn.telemetry.SafeDpmGateway
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber

enum class ApprovalDecisionResult {
    APPROVED,
    APPROVED_CURFEW_DEFERRED,
    BLOCKED_BLACKLISTED,
    BLOCKED_REJECTED,
    BLOCKED_SELF_CONTROL_FORBIDDEN,
    FAILED_NOT_DEVICE_OWNER,
    FAILED_DPM_ERROR
}

/**
 * Repository responsible for managing Zero-Trust app installation approvals.
 * Coordinates immediate local suspension and delegates Firestore sync, notifications,
 * and baseline reconciliation to [ApprovalCloudSyncBridge], [ApprovalNotificationCoordinator],
 * and [ZeroTrustBaselineHandler].
 */
class ApprovalRepository(
    context: Context,
    private val approvalDao: PendingApprovalDao = HisnRoomDatabase.getInstance(context).pendingApprovalDao()
) {

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val stateLock = Any()

    @VisibleForTesting
    internal var policyProvider: () -> DevicePolicyBundle = {
        PolicyRepository.getInstance(appContext).getCurrentPolicy()
    }

    @VisibleForTesting
    internal var currentTimeMillisProvider: () -> Long = {
        TrustedTimeProvider.currentTimeMillis(appContext)
    }

    @VisibleForTesting
    internal var isDeviceOwnerProvider: () -> Boolean = {
        SafeDpmGateway.isDeviceOwner(appContext)
    }

    @VisibleForTesting
    internal var isBlockActiveProvider: () -> Boolean = {
        CurfewEnforcer.isEffectiveBlockActive(appContext)
    }

    @VisibleForTesting
    internal var immunePackagesProvider: () -> Set<String> = {
        CurfewAppResolver.resolveImmunePackages(appContext)
    }

    @VisibleForTesting
    internal var appLabelProvider: (String) -> String = { packageName ->
        try {
            val pm = appContext.packageManager
            val appInfo = pm.getApplicationInfo(packageName, 0)
            pm.getApplicationLabel(appInfo).toString()
        } catch (e: Exception) {
            Log.w(TAG, "Failed resolving app label for $packageName", e)
            packageName
        }
    }

    @VisibleForTesting
    internal var isPackageInstalledProvider: (String) -> Boolean = { packageName ->
        try {
            appContext.packageManager.getPackageInfo(packageName, 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        } catch (e: Exception) {
            Log.w(TAG, "Error checking package info for $packageName", e)
            false
        }
    }

    @VisibleForTesting
    internal var isAppHiddenProvider: (String) -> Boolean = { packageName ->
        SafeDpmGateway.isApplicationHiddenBlocking(appContext, packageName).getOrNull() == true
    }

    @VisibleForTesting
    internal var setAppHiddenProvider: (String, Boolean) -> Boolean = { packageName, hidden ->
        SafeDpmGateway.setApplicationHiddenBlocking(appContext, packageName, hidden).getOrNull() ?: false
    }

    @VisibleForTesting
    internal var setPackagesSuspendedProvider: (Array<String>, Boolean) -> Boolean = { packages, suspended ->
        try {
            val dpmResult = SafeDpmGateway.setPackagesSuspendedBlocking(appContext, packages, suspended)
            val failed = dpmResult.getOrNull()
            dpmResult is DpmExecutionResult.Success &&
                (failed == null || packages.none { failed.contains(it) })
        } catch (e: Exception) {
            Log.w(TAG, "Exception calling setPackagesSuspended($suspended) for ${packages.joinToString()}", e)
            false
        }
    }

    @VisibleForTesting
    internal var suspendWithMessageProvider: (String, String) -> Boolean = { packageName, message ->
        try {
            SafeDpmGateway.setPackageSuspendedWithMessageBlocking(
                context = appContext,
                packageName = packageName,
                message = message
            ).getOrNull() == true
        } catch (e: Exception) {
            Log.w(TAG, "Exception calling setPackageSuspendedWithMessage for $packageName", e)
            false
        }
    }

    @VisibleForTesting
    internal var watcherSyncer: () -> Unit = {
        ZeroTrustForegroundWatcher.syncWatcherState(appContext)
    }

    @VisibleForTesting
    internal var foregroundEvaluator: (String) -> Unit = { packageName ->
        ZeroTrustForegroundWatcher.evaluateCurrentForegroundApp(appContext, immediateTargetPackage = packageName)
    }

    private val notificationCoordinator = ApprovalNotificationCoordinator(
        appContext = appContext,
        appLabelResolver = { pkg -> resolveAppLabel(pkg) }
    )

    @VisibleForTesting
    internal var notificationDispatcher: (String, Boolean, Boolean, String?) -> Unit = { pkg, approved, deferred, decidedBy ->
        notificationCoordinator.postApprovalNotification(pkg, approved, deferred, decidedBy)
    }

    @VisibleForTesting
    internal var cloudDecisionSyncer: (String) -> Unit = { packageName ->
        cloudSyncBridge.syncApprovalDecisionAsync(packageName)
    }

    @VisibleForTesting
    internal var firestoreUploader: (suspend (PendingApprovalEntity) -> Boolean)? = null

    @VisibleForTesting
    internal var stringProvider: (Int) -> String = { resId ->
        appContext.getString(resId)
    }

    @VisibleForTesting
    internal var systemCriticalChecker: (String) -> Boolean = { packageName ->
        CurfewAppResolver.isSystemCriticalOrImmune(appContext, packageName)
    }

    @VisibleForTesting
    internal var installedPackagesProvider: (() -> List<PackageInfo>)? = null

    @VisibleForTesting
    internal var isPackageSuspendedProvider: (String) -> Boolean = { packageName ->
        SafeDpmGateway.isPackageSuspendedBlocking(appContext, packageName).getOrNull() == true
    }

    private val cloudSyncBridge = ApprovalCloudSyncBridge(
        appContext = appContext,
        approvalDao = approvalDao,
        scope = scope,
        policyProvider = { policyProvider() },
        currentTimeMillisProvider = { currentTimeMillisProvider() },
        appLabelResolver = { pkg -> resolveAppLabel(pkg) },
        firestoreUploaderProvider = { firestoreUploader },
        onUnsuspendAndApprove = { pkg, decidedBy, sync, forceInv ->
            unsuspendAndApprove(pkg, decidedBy, syncToCloud = sync, forceInventorySync = forceInv)
        },
        onRecordRejection = { pkg, decidedBy, sync ->
            recordRejection(pkg, decidedBy, syncToCloud = sync)
        }
    )

    private val baselineHandler = ZeroTrustBaselineHandler(
        appContext = appContext,
        approvalDao = approvalDao,
        stateLock = stateLock,
        policyProvider = { policyProvider() },
        currentTimeMillisProvider = { currentTimeMillisProvider() },
        isDeviceOwnerProvider = { isDeviceOwnerProvider() },
        isBlockActiveProvider = { isBlockActiveProvider() },
        immunePackagesProvider = { immunePackagesProvider() },
        appLabelResolver = { pkg -> resolveAppLabel(pkg) },
        installedPackagesProvider = { installedPackagesProvider },
        isPackageSuspendedProvider = { isPackageSuspendedProvider },
        isAppHiddenProvider = { isAppHiddenProvider },
        setAppHiddenProvider = { setAppHiddenProvider },
        setPackagesSuspendedProvider = { setPackagesSuspendedProvider },
        systemCriticalChecker = { systemCriticalChecker },
        watcherSyncer = { watcherSyncer },
        notificationCanceller = { pkg -> notificationCoordinator.cancelFreezeNotification(pkg) },
        cloudDeleter = { pkg -> cloudSyncBridge.deleteApprovalFromCloudAsync(pkg) },
        freezeLocalFn = { pkg, showToast, isHighRisk -> freezeAndSubmitApprovalLocal(pkg, showToast, isHighRisk) },
        cloudSyncTrigger = {
            scope.launch {
                try {
                    syncPendingApprovalsToCloud()
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    Log.w(TAG, "Failed syncing reconciled approvals to cloud", e)
                }
            }
        }
    )

    val pendingApprovalsFlow: Flow<List<PendingApprovalEntity>> = approvalDao.getPendingApprovalsFlow()

    private fun resolveAppLabel(packageName: String): String = appLabelProvider(packageName)

    fun freezeAndSubmitApproval(packageName: String) {
        if (systemCriticalChecker(packageName)) {
            Log.i(TAG, "Package $packageName is immune/self/system. Skipping freeze and approval submission.")
            return
        }
        scope.launch { freezeAndSubmitApprovalSync(packageName) }
    }

    /**
     * Immediately freezes a newly installed package locally via DevicePolicyManager,
     * writes the record to SQLite, and synchronizes the active Zero-Trust foreground watcher.
     */
    fun freezeAndSubmitApprovalLocal(
        packageName: String,
        showToast: Boolean = true,
        isHighRisk: Boolean = false
    ): Boolean {
        if (systemCriticalChecker(packageName)) {
            Log.i(TAG, "IRON GUARD: Package $packageName is critical system / launcher / dialer / keyboard / immune. Skipping freeze.")
            return false
        }
        if (packageName == CurfewConfig.GOOGLE_PLAY_STORE_PACKAGE || CurfewConfig.KNOWN_APP_STORES.contains(packageName)) {
            Log.i(TAG, "IRON GUARD: App Store $packageName is immune from Zero-Trust freeze.")
            return false
        }
        val appInfo = try { appContext.packageManager.getApplicationInfo(packageName, 0) } catch (_: Exception) { null }
        if (appInfo != null && ((appInfo.flags and ApplicationInfo.FLAG_SYSTEM != 0) || (appInfo.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP != 0))) {
            Log.i(TAG, "IRON GUARD: System package $packageName is immune from Zero-Trust freeze.")
            return false
        }
        if (!isDeviceOwnerProvider()) {
            Log.w(TAG, "Cannot freeze $packageName: Not Device/Profile Owner")
            return false
        }

        val freezeMessage = if (isHighRisk) {
            stringProvider(R.string.toast_high_risk_app_suspended)
        } else {
            stringProvider(R.string.toast_app_suspended_by_guardian)
        }

        var preservedRejected = false
        var appNameForNotif = packageName
        synchronized(stateLock) {
            val suspended = suspendWithMessageProvider(packageName, freezeMessage)
            try {
                if (isAppHiddenProvider(packageName)) {
                    setAppHiddenProvider(packageName, false)
                    Log.i(TAG, "Self-healing: Unhid package $packageName during Zero-Trust freeze")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Self-healing unhide check failed for $packageName", e)
            }

            if (suspended) {
                Log.w(TAG, "[FREEZE_SUCCESS] Instant 0ms freeze applied to package: $packageName (showToast=$showToast, isHighRisk=$isHighRisk)")
            } else {
                Log.w(TAG, "[FREEZE_WARNING] Device suspension reported unconfirmed for $packageName; proceeding with Zero-Trust pending approval without hiding.")
            }

            val existing = approvalDao.getApprovalByPackageNameBlocking(packageName)
            if (existing?.isRejected == true) {
                Log.w(TAG, "Preserving existing REJECTED status for $packageName; skipping PENDING overwrite.")
                preservedRejected = true
            } else {
                appNameForNotif = resolveAppLabel(packageName)
                approvalDao.savePendingApprovalBlocking(
                    PendingApprovalEntity(
                        packageName = packageName,
                        appName = appNameForNotif,
                        requestedAt = currentTimeMillisProvider(),
                        status = PendingApprovalEntity.STATUS_PENDING,
                        isCloudSynced = false
                    )
                )
            }
        }

        if (showToast) {
            try {
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    try {
                        val alertMsg = if (isHighRisk) {
                            appContext.getString(R.string.toast_high_risk_app_suspended)
                        } else {
                            appContext.getString(R.string.toast_app_suspended_by_guardian)
                        }
                        android.widget.Toast.makeText(appContext, alertMsg, android.widget.Toast.LENGTH_LONG).show()
                    } catch (te: Exception) {
                        Log.w(TAG, "Failed showing toast alert: ${te.message}")
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error posting toast to main looper: ${e.message}")
            }
        }

        if (!preservedRejected) {
            notificationCoordinator.postFreezeNotification(packageName, appNameForNotif, isHighRisk)
        }
        watcherSyncer()
        foregroundEvaluator(packageName)
        return true
    }

    suspend fun freezeAndSubmitApprovalSync(packageName: String): Boolean = withContext(Dispatchers.IO) {
        val localSuccess = freezeAndSubmitApprovalLocal(
            packageName = packageName,
            isHighRisk = AppPolicyRegistry.isHighRisk(packageName)
        )
        if (!localSuccess) return@withContext false

        val entity = approvalDao.getPendingApprovalsBlocking().find { it.packageName == packageName }
            ?: return@withContext false

        val uploadSuccess = withTimeoutOrNull(3500L) {
            uploadSingleApprovalSync(entity)
        } ?: false

        Log.i(TAG, "freezeAndSubmitApprovalSync completed for $packageName (uploaded=$uploadSuccess)")
        uploadSuccess
    }

    suspend fun syncPendingApprovalsToCloud() = cloudSyncBridge.syncPendingApprovalsToCloud()

    suspend fun uploadSingleApprovalSync(approval: PendingApprovalEntity): Boolean =
        cloudSyncBridge.uploadSingleApprovalSync(approval)

    suspend fun uploadSingleApproval(approval: PendingApprovalEntity): Boolean =
        cloudSyncBridge.uploadSingleApprovalSync(approval)

    /**
     * Unfreezes and approves an application.
     * Guaranteed Invariants:
     * 1. Custom-blacklisted applications are NEVER unsuspended.
     * 2. If Night Curfew or Nap is currently active and the app is not whitelisted, approval
     *    is recorded locally while suspension remains active until morning curfew wakeup.
     */
    fun unsuspendAndApprove(
        packageName: String,
        decidedBy: String? = null,
        syncToCloud: Boolean = true,
        forceInventorySync: Boolean = false,
        overrideRejected: Boolean = false
    ): ApprovalDecisionResult {
        val policy = policyProvider()
        if (policy.customBlacklistPackages.contains(packageName)) {
            Log.w(TAG, "Security Alert: Cannot unsuspend $packageName - currently in custom blacklist! Skipping unsuspend without poisoning approval record.")
            return ApprovalDecisionResult.BLOCKED_BLACKLISTED
        }

        val isSelf = decidedBy?.equals("SELF", ignoreCase = true) == true
        val isSupervised = policy.isSupervised || !policy.guardianUid.isNullOrBlank()
        val now = currentTimeMillisProvider()
        val targetSynced = if (syncToCloud) 0 else 1
        val isBlockActive = isBlockActiveProvider()
        val isWhitelisted = CurfewConfig.STATIC_WHITELIST.contains(packageName) ||
            immunePackagesProvider().contains(packageName) ||
            policy.customCurfewWhitelistPackages.contains(packageName)

        var outcome: ApprovalDecisionResult = ApprovalDecisionResult.FAILED_DPM_ERROR
        var notifyDeferred: Boolean? = null

        synchronized(stateLock) {
            if (isSelf) {
                val existing = approvalDao.getApprovalByPackageNameBlocking(packageName)
                val isExistingSelfRejected = existing?.decidedBy?.equals("SELF", ignoreCase = true) == true
                val isExistingGuardianRejected = existing?.isRejected == true && !isExistingSelfRejected
                val canOverrideGuardianRejection = overrideRejected &&
                    (!isSupervised || (policy.isSelfControlAllowed && policy.selfControlAppsAllowed))
                if (isExistingGuardianRejected && !canOverrideGuardianRejection) {
                    Log.w(TAG, "Security Alert: Ward attempted self-approval for $packageName which was REJECTED by guardian! Aborting self-approval.")
                    return ApprovalDecisionResult.BLOCKED_REJECTED
                }
                if (syncToCloud) {
                    val isPermissionAllowed = if (existing?.isPending == true) {
                        policy.selfControlNewAppsAllowed
                    } else {
                        policy.selfControlNewAppsAllowed || policy.selfControlAppsAllowed
                    }
                    if (isSupervised && (!policy.isSelfControlAllowed || !isPermissionAllowed)) {
                        Log.w(TAG, "Security Alert: Ward attempted self-approval for $packageName while self-control for new/app approvals is forbidden by supervisor!")
                        return ApprovalDecisionResult.BLOCKED_SELF_CONTROL_FORBIDDEN
                    }
                }
            }

            if (!isDeviceOwnerProvider()) return ApprovalDecisionResult.FAILED_NOT_DEVICE_OWNER
            val resolvedAppLabel = resolveAppLabel(packageName)

            if (isBlockActive && !isWhitelisted) {
                Log.i(TAG, "Package $packageName approved by $decidedBy, but kept suspended until curfew wakeup")
                approvalDao.upsertApprovalStatusBlocking(
                    packageName = packageName,
                    appName = resolvedAppLabel,
                    status = PendingApprovalEntity.STATUS_APPROVED,
                    decidedBy = decidedBy,
                    decidedAt = now,
                    isSynced = targetSynced
                )
                outcome = ApprovalDecisionResult.APPROVED_CURFEW_DEFERRED
                notifyDeferred = true
            } else {
                try {
                    try {
                        if (isAppHiddenProvider(packageName)) {
                            val unhidden = setAppHiddenProvider(packageName, false)
                            Log.i(TAG, "Unhid package $packageName on approval: $unhidden")
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Exception unhiding package $packageName on approval", e)
                    }

                    val isDpmSuccess = setPackagesSuspendedProvider(arrayOf(packageName), false)
                    if (!isDpmSuccess) {
                        if (!isPackageInstalledProvider(packageName)) {
                            Log.i(TAG, "Package $packageName is uninstalled; marking pending approval as approved/resolved")
                            approvalDao.upsertApprovalStatusBlocking(
                                packageName = packageName,
                                appName = resolvedAppLabel,
                                status = PendingApprovalEntity.STATUS_APPROVED,
                                decidedBy = decidedBy,
                                decidedAt = now,
                                isSynced = targetSynced
                            )
                            outcome = ApprovalDecisionResult.APPROVED
                        } else {
                            Log.e(TAG, "Failed unfreezing package $packageName: DPM returned failure")
                            return ApprovalDecisionResult.FAILED_DPM_ERROR
                        }
                    } else {
                        approvalDao.upsertApprovalStatusBlocking(
                            packageName = packageName,
                            appName = resolvedAppLabel,
                            status = PendingApprovalEntity.STATUS_APPROVED,
                            decidedBy = decidedBy,
                            decidedAt = now,
                            isSynced = targetSynced
                        )
                        Log.i(TAG, "Unfrozen and approved package: $packageName")
                        outcome = ApprovalDecisionResult.APPROVED
                        notifyDeferred = false
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed unfreezing package $packageName", e)
                    return ApprovalDecisionResult.FAILED_DPM_ERROR
                }
            }
        }

        watcherSyncer()
        if (syncToCloud) cloudDecisionSyncer(packageName)
        if (outcome == ApprovalDecisionResult.APPROVED && forceInventorySync) {
            try {
                AppInventoryManager.getInstance(appContext).syncInventoryToCloudAsync(force = true)
            } catch (e: Exception) {
                Log.w(TAG, "Failed triggering forced inventory sync after approval of $packageName", e)
            }
        }
        notifyDeferred?.let { deferred ->
            notificationDispatcher(packageName, true, deferred, decidedBy)
        }
        return outcome
    }

    fun removePendingApproval(packageName: String) {
        try {
            var preservedRejected = false
            synchronized(stateLock) {
                val existing = approvalDao.getApprovalByPackageNameBlocking(packageName)
                if (existing?.isRejected == true) {
                    Log.i(TAG, "Preserving REJECTED approval record for $packageName on uninstall to prevent uninstall-reinstall bypass")
                    preservedRejected = true
                } else {
                    approvalDao.deletePendingApprovalBlocking(packageName)
                }
            }
            watcherSyncer()
            if (!preservedRejected) {
                notificationCoordinator.cancelFreezeNotification(packageName)
                cloudSyncBridge.deleteApprovalFromCloudAsync(packageName)
                Log.i(TAG, "Removed pending approval for package: $packageName")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed removing pending approval for package $packageName", e)
        }
    }

    fun recordRejection(
        packageName: String,
        decidedBy: String? = null,
        syncToCloud: Boolean = true
    ) {
        try {
            synchronized(stateLock) {
                if (isDeviceOwnerProvider() && isPackageInstalledProvider(packageName)) {
                    val suspendMessage = runCatching {
                        stringProvider(R.string.toast_app_suspended_by_guardian)
                    }.getOrNull().orEmpty()
                    val suspended = suspendWithMessageProvider(packageName, suspendMessage)
                    if (!suspended) {
                        Log.w(TAG, "Immediate re-freeze reported unconfirmed for rejected package: $packageName")
                    }
                }
                val resolvedAppLabel = resolveAppLabel(packageName)
                val now = currentTimeMillisProvider()
                val targetSynced = if (syncToCloud) 0 else 1
                approvalDao.upsertApprovalStatusBlocking(
                    packageName = packageName,
                    appName = resolvedAppLabel,
                    status = PendingApprovalEntity.STATUS_REJECTED,
                    decidedBy = decidedBy,
                    decidedAt = now,
                    isSynced = targetSynced
                )
                Log.i(TAG, "Recorded rejection for package: $packageName by $decidedBy")
            }
            watcherSyncer()
            if (syncToCloud) cloudDecisionSyncer(packageName)
            notificationDispatcher(packageName, false, false, decidedBy)
        } catch (e: Exception) {
            Log.e(TAG, "Failed recording rejection for package $packageName", e)
        }
    }

    fun recordApproval(packageName: String, appName: String, decidedBy: String = "AUTO_APPROVED_UNLOCKED") {
        try {
            synchronized(stateLock) {
                val existing = approvalDao.getApprovalByPackageNameBlocking(packageName)
                if (existing?.isRejected == true) {
                    Timber.tag(TAG).w("Preserving REJECTED record for $packageName; refusing recordApproval($decidedBy)")
                    return
                }
                val now = currentTimeMillisProvider()
                approvalDao.savePendingApprovalBlocking(
                    PendingApprovalEntity(
                        packageName = packageName,
                        appName = appName,
                        requestedAt = now,
                        status = PendingApprovalEntity.STATUS_APPROVED,
                        decidedBy = decidedBy,
                        decidedAt = now,
                        isCloudSynced = true
                    )
                )
                Timber.tag(TAG).i("Recorded approval for $packageName decidedBy $decidedBy")
            }
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed recording approval for $packageName")
        }
    }

    /**
     * Atomically filters [candidates] to those still locally PENDING/FALLBACK_HIDDEN and
     * suspends them via DPM while holding [stateLock], preventing TOCTOU races with [unsuspendAndApprove].
     */
    internal fun enforcePendingSuspensionsLocked(candidates: List<String>) {
        if (candidates.isEmpty() || !isDeviceOwnerProvider()) return
        synchronized(stateLock) {
            val stillPending = approvalDao.getActualPendingApprovalsBlocking().map { it.packageName }.toSet()
            val confirmed = candidates.filter { stillPending.contains(it) && !isPackageSuspendedProvider(it) }
            if (confirmed.isEmpty()) return
            try {
                setPackagesSuspendedProvider(confirmed.toTypedArray(), true)
                Log.i(TAG, "Event-driven Zero-Trust reconciliation: Enforced DPM suspension on ${confirmed.size} packages: $confirmed")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to enforce DPM suspension during syncWatcherState", e)
            }
        }
    }

    fun startApprovalsSync(wardUid: String) = cloudSyncBridge.startApprovalsSync(wardUid)

    fun stopApprovalsSync() = cloudSyncBridge.stopApprovalsSync()

    suspend fun resolveWardApproval(
        wardUid: String,
        packageName: String,
        approve: Boolean,
        guardianName: String
    ): Boolean = cloudSyncBridge.resolveWardApproval(wardUid, packageName, approve, guardianName)

    fun getLocalPendingApprovals(): List<PendingApprovalEntity> = approvalDao.getPendingApprovalsBlocking()

    fun getActualLocalPendingApprovals(): List<PendingApprovalEntity> = approvalDao.getActualPendingApprovalsBlocking()

    fun ensureInitialBaselineSeeded() = baselineHandler.ensureInitialBaselineSeeded()

    fun ensureInitialBaselineSeededAsync() {
        scope.launch {
            try {
                ensureInitialBaselineSeeded()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.e(TAG, "Error in ensureInitialBaselineSeededAsync", e)
            }
        }
    }

    fun reconcileUnapprovedInstalledApps() = baselineHandler.reconcileUnapprovedInstalledApps()

    fun reconcileUnapprovedInstalledAppsAsync() {
        scope.launch {
            try {
                reconcileUnapprovedInstalledApps()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.e(TAG, "Error in reconcileUnapprovedInstalledAppsAsync", e)
            }
        }
    }

    fun getDeviceOwnerEnabledTimestamp(): Long = baselineHandler.getDeviceOwnerEnabledTimestamp()

    fun recordDeviceOwnerEnabledTimestamp(timestamp: Long = TrustedTimeProvider.currentTimeMillis(appContext)) =
        baselineHandler.recordDeviceOwnerEnabledTimestamp(timestamp)

    fun migrateLegacyHighRiskFreezesIfNeeded() = baselineHandler.migrateLegacyHighRiskFreezesIfNeeded()

    fun syncZeroTrustBaselineAndReconcile() {
        if (!ProtectionEngine.isDeviceOwner(appContext)) return
        getDeviceOwnerEnabledTimestamp()
        try {
            migrateLegacyHighRiskFreezesIfNeeded()
        } catch (e: Exception) {
            Log.e(TAG, "Error during migrateLegacyHighRiskFreezesIfNeeded; continuing baseline & reconcile", e)
        }
        ensureInitialBaselineSeeded()
        reconcileUnapprovedInstalledApps()
    }

    fun syncZeroTrustBaselineAndReconcileAsync(onComplete: (() -> Unit)? = null) {
        scope.launch {
            try {
                syncZeroTrustBaselineAndReconcile()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.e(TAG, "Error in syncZeroTrustBaselineAndReconcileAsync", e)
            } finally {
                onComplete?.invoke()
            }
        }
    }

    companion object {
        private const val TAG = "AntiPorn_ApprovalRepo"
        internal const val PREFS_NAME = "zero_trust_approval_prefs"
        internal const val KEY_BASELINE_SEEDED = "is_zero_trust_baseline_seeded"
        internal const val KEY_HIGH_RISK_NORMALIZATION_V1 = "legacy_high_risk_freeze_normalized_v1"
        const val KEY_DEVICE_OWNER_ENABLED_AT = "device_owner_enabled_at_timestamp"
        internal const val DO_ACTIVATION_GRACE_PERIOD_MS = 15_000L

        @Volatile
        private var instance: ApprovalRepository? = null

        fun getInstance(context: Context): ApprovalRepository {
            return instance ?: synchronized(this) {
                instance ?: try {
                    org.koin.core.context.GlobalContext.getOrNull()?.get<ApprovalRepository>()
                } catch (_: Throwable) {
                    null
                } ?: ApprovalRepository(context.applicationContext).also {
                    instance = it
                }
            }
        }

        @VisibleForTesting
        fun setInstance(repo: ApprovalRepository?) {
            instance = repo
        }
    }
}
