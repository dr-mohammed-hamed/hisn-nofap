package com.drmohammed.antiporn.data.repository

import android.content.ComponentName
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import com.drmohammed.antiporn.core.util.SafeAndroidLog as Log
import androidx.annotation.VisibleForTesting
import com.drmohammed.antiporn.AdminReceiver
import com.drmohammed.antiporn.R
import com.drmohammed.antiporn.core.model.DevicePolicyBundle
import com.drmohammed.antiporn.core.policy.AppPolicyRegistry
import com.drmohammed.antiporn.curfew.CurfewAppResolver
import com.drmohammed.antiporn.curfew.CurfewConfig
import com.drmohammed.antiporn.curfew.CurfewEnforcer
import com.drmohammed.antiporn.curfew.ZeroTrustForegroundWatcher
import com.drmohammed.antiporn.data.local.db.HisnRoomDatabase
import com.drmohammed.antiporn.data.local.db.dao.PendingApprovalDao
import com.drmohammed.antiporn.data.local.db.entity.PendingApprovalEntity
import com.drmohammed.antiporn.notification.manager.AppNotificationManager
import com.drmohammed.antiporn.notification.model.NotificationActionType
import com.drmohammed.antiporn.notification.model.NotificationCategory
import com.drmohammed.antiporn.notification.registry.NotificationRegistry
import com.drmohammed.antiporn.shields.AppSuspensionShield
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
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
 * Coordinates immediate local suspension, cloud queue logging, and remote unfreezing.
 */
class ApprovalRepository(
    context: Context,
    private val approvalDao: PendingApprovalDao = HisnRoomDatabase.getInstance(context).pendingApprovalDao()
) {

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var approvalsListener: ListenerRegistration? = null

    @VisibleForTesting
    internal var policyProvider: () -> DevicePolicyBundle = {
        PolicyRepository.getInstance(appContext).getCurrentPolicy()
    }

    @VisibleForTesting
    internal var currentTimeMillisProvider: () -> Long = {
        com.drmohammed.antiporn.core.time.TrustedTimeProvider.currentTimeMillis(appContext)
    }

    @VisibleForTesting
    internal var isDeviceOwnerProvider: () -> Boolean = {
        com.drmohammed.antiporn.telemetry.SafeDpmGateway.isDeviceOwner(appContext)
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
            packageName
        }
    }

    @VisibleForTesting
    internal var isPackageInstalledProvider: (String) -> Boolean = { packageName ->
        try {
            appContext.packageManager.getPackageInfo(packageName, 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        } catch (e: Exception) {
            Log.w(TAG, "Error checking package info for $packageName", e)
            false
        }
    }

    @VisibleForTesting
    internal var isAppHiddenProvider: (String) -> Boolean = { packageName ->
        com.drmohammed.antiporn.telemetry.SafeDpmGateway.isApplicationHiddenBlocking(appContext, packageName).getOrNull() == true
    }

    @VisibleForTesting
    internal var setAppHiddenProvider: (String, Boolean) -> Boolean = { packageName, hidden ->
        com.drmohammed.antiporn.telemetry.SafeDpmGateway.setApplicationHiddenBlocking(appContext, packageName, hidden).getOrNull() ?: false
    }

    @VisibleForTesting
    internal var setPackagesSuspendedProvider: (Array<String>, Boolean) -> Boolean = { packages, suspended ->
        try {
            val dpmResult = com.drmohammed.antiporn.telemetry.SafeDpmGateway.setPackagesSuspendedBlocking(appContext, packages, suspended)
            val failed = dpmResult.getOrNull()
            dpmResult is com.drmohammed.antiporn.telemetry.DpmExecutionResult.Success &&
                (failed == null || packages.none { failed.contains(it) })
        } catch (e: Exception) {
            Log.w(TAG, "Exception calling setPackagesSuspended($suspended) for ${packages.joinToString()}", e)
            false
        }
    }

    @VisibleForTesting
    internal var suspendWithMessageProvider: (String, String) -> Boolean = { packageName, message ->
        try {
            com.drmohammed.antiporn.telemetry.SafeDpmGateway.setPackageSuspendedWithMessageBlocking(
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

    @VisibleForTesting
    internal var notificationDispatcher: (String, Boolean, Boolean, String?) -> Unit = { packageName, approved, isCurfewDeferred, decidedBy ->
        postApprovalNotification(packageName, approved, isCurfewDeferred, decidedBy)
    }

    @VisibleForTesting
    internal var cloudDecisionSyncer: (String) -> Unit = { packageName ->
        syncApprovalDecisionToCloud(packageName)
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

    val pendingApprovalsFlow: Flow<List<PendingApprovalEntity>> = approvalDao.getPendingApprovalsFlow()

    private fun resolveAppLabel(packageName: String): String = appLabelProvider(packageName)

    /**
     * Intercepts a newly installed package:
     * Freezes it in 0ms via DevicePolicyManager, records it locally, and asynchronously submits a cloud approval ticket.
     */
    fun freezeAndSubmitApproval(packageName: String) {
        if (systemCriticalChecker(packageName)) {
            Log.i(TAG, "Package $packageName is immune/self/system. Skipping freeze and approval submission.")
            return
        }

        scope.launch {
            freezeAndSubmitApprovalSync(packageName)
        }
    }

    /**
     * Immediately freezes a newly installed package in 0ms locally via DevicePolicyManager,
     * writes the record to SQLite, and synchronizes the active Zero-Trust foreground watcher.
     * Operates completely offline in < 5ms without any network dependency.
     */
    @Synchronized
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

        val appInfo = try { appContext.packageManager.getApplicationInfo(packageName, 0) } catch (e: Exception) { null }
        if (appInfo != null && ((appInfo.flags and ApplicationInfo.FLAG_SYSTEM != 0) || (appInfo.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP != 0))) {
            Log.i(TAG, "IRON GUARD: System package $packageName is immune from Zero-Trust freeze.")
            return false
        }

        if (!isDeviceOwnerProvider()) {
            Log.w(TAG, "Cannot freeze $packageName: Not Device/Profile Owner")
            return false
        }

        // 1. Instant 0ms suspension with Fail-Secure Fallback
        val freezeMessage = if (isHighRisk) {
            stringProvider(R.string.toast_high_risk_app_suspended)
        } else {
            stringProvider(R.string.toast_app_suspended_by_guardian)
        }
        val suspended = suspendWithMessageProvider(packageName, freezeMessage)

        // Self-healing: Zero-Trust architecture requires unapproved apps to be visible & suspended, NEVER hidden.
        // If an application was previously hidden by a legacy version, unhide it immediately so it appears on launcher as frozen.
        try {
            if (isAppHiddenProvider(packageName)) {
                setAppHiddenProvider(packageName, false)
                Log.i(TAG, "Self-healing: Unhid package $packageName during Zero-Trust freeze")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Self-healing unhide check failed for $packageName", e)
        }

        val finalStatus = PendingApprovalEntity.STATUS_PENDING
        if (suspended) {
            Log.w(TAG, "[FREEZE_SUCCESS] Instant 0ms freeze applied to package: $packageName (showToast=$showToast, isHighRisk=$isHighRisk)")
        } else {
            Log.w(TAG, "[FREEZE_WARNING] Device suspension reported unconfirmed for $packageName; proceeding with Zero-Trust pending approval without hiding.")
        }

        // Show alert toast on main thread ONLY when requested (avoids spamming toast during background reconciliation)
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

        val existing = approvalDao.getApprovalByPackageNameBlocking(packageName)
        if (existing?.isRejected == true) {
            Log.w(TAG, "Preserving existing REJECTED status for $packageName; skipping PENDING overwrite.")
            watcherSyncer()
            foregroundEvaluator(packageName)
            return true
        }

        // 2. Resolve human-readable application label
        val appName = resolveAppLabel(packageName)

        val approvalEntity = PendingApprovalEntity(
            packageName = packageName,
            appName = appName,
            requestedAt = currentTimeMillisProvider(),
            status = finalStatus,
            isCloudSynced = false
        )

        // 3. Save locally in SQLite
        approvalDao.savePendingApprovalBlocking(approvalEntity)

        // 3.1 Post in-app and system notification regarding the frozen package
        try {
            val notifTitle = if (isHighRisk) {
                appContext.getString(R.string.notification_high_risk_app_frozen_title)
            } else {
                appContext.getString(R.string.toast_app_suspended_by_guardian)
            }
            val notifMessage = if (isHighRisk) {
                appContext.getString(R.string.notification_high_risk_app_frozen_msg, appName)
            } else {
                appContext.getString(R.string.toast_app_suspended_by_guardian) + " ($appName)"
            }
            AppNotificationManager.getInstance(appContext).postEventNotification(
                category = NotificationCategory.APPROVALS,
                title = notifTitle,
                message = notifMessage,
                dedupKey = "zero_trust_freeze_$packageName",
                cooldownMs = 10 * 60 * 1000L,
                actionType = NotificationActionType.OPEN_SELF_APPROVALS,
                actionPayload = packageName,
                postSystemNotification = true,
                importance = 2
            )
        } catch (e: Exception) {
            Log.w(TAG, "Error posting freeze notification for $packageName", e)
        }

        // 4. Update Zero-Trust active foreground watcher immediately and trigger in-memory anti-race evaluation
        watcherSyncer()
        foregroundEvaluator(packageName)

        return true
    }

    suspend fun freezeAndSubmitApprovalSync(packageName: String): Boolean = withContext(Dispatchers.IO) {
        val localSuccess = freezeAndSubmitApprovalLocal(packageName)
        if (!localSuccess) return@withContext false

        val entity = approvalDao.getPendingApprovalsBlocking().find { it.packageName == packageName }
            ?: return@withContext false

        // ANR-protected cloud upload with 3500ms timeout
        val uploadSuccess = kotlinx.coroutines.withTimeoutOrNull(3500L) {
            uploadSingleApprovalSync(entity)
        } ?: false

        Log.i(TAG, "freezeAndSubmitApprovalSync completed for $packageName (uploaded=$uploadSuccess)")
        uploadSuccess
    }

    /**
     * Uploads all locally stored pending approvals that have not yet reached Firestore (e.g. created while offline).
     */
    suspend fun syncPendingApprovalsToCloud() = withContext(Dispatchers.IO) {
        try {
            val unsynced = approvalDao.getUnsyncedApprovalsBlocking()
            if (unsynced.isEmpty()) return@withContext
            Log.i(TAG, "Syncing ${unsynced.size} unsynced pending approvals to cloud")
            for (approval in unsynced) {
                uploadSingleApprovalSync(approval)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in syncPendingApprovalsToCloud", e)
        }
    }

    /**
     * Synchronously uploads a single approval ticket to `/policies/$uid/approvals/${approval.packageName}`.
     * Returns true if successful, false otherwise.
     */
    suspend fun uploadSingleApprovalSync(approval: PendingApprovalEntity): Boolean = withContext(Dispatchers.IO) {
        try {
            val expectedStatus = approval.status
            val expectedDecidedAt = approval.decidedAt

            // Guard: AUTO_APPROVED_UNLOCKED does not need to be uploaded to cloud
            if (approval.decidedBy == "AUTO_APPROVED_UNLOCKED") {
                approvalDao.markApprovalCloudSyncedIfMatchingBlocking(
                    packageName = approval.packageName,
                    expectedStatus = expectedStatus,
                    expectedDecidedAt = expectedDecidedAt,
                    isSynced = 1
                )
                Log.d(TAG, "Skipping cloud upload for AUTO_APPROVED_UNLOCKED: ${approval.packageName}")
                return@withContext true
            }

            val customUploader = firestoreUploader
            if (customUploader != null) {
                val uploaded = customUploader(approval)
                if (uploaded) {
                    approvalDao.markApprovalCloudSyncedIfMatchingBlocking(
                        packageName = approval.packageName,
                        expectedStatus = expectedStatus,
                        expectedDecidedAt = expectedDecidedAt,
                        isSynced = 1
                    )
                }
                return@withContext uploaded
            }

            val uid = FirebaseAuth.getInstance().currentUser?.uid
                ?: AccountabilityRepository.getInstance(appContext).getCachedUid()
            if (uid.isBlank()) {
                Log.w(TAG, "Cannot upload approval for ${approval.packageName}: UID is blank")
                return@withContext false
            }

            val firestore = FirebaseFirestore.getInstance()
            val docRef = firestore.collection("policies")
                .document(uid)
                .collection("approvals")
                .document(approval.packageName)

            val resolvedAppName = approval.appName.takeIf { it.isNotBlank() && it != approval.packageName }
                ?: resolveAppLabel(approval.packageName)
            val fullData = mutableMapOf<String, Any>(
                "packageName" to approval.packageName,
                "appName" to resolvedAppName,
                "wardUid" to uid,
                "status" to approval.status,
                "requestedAt" to approval.requestedAt
            )
            if (approval.status == PendingApprovalEntity.STATUS_PENDING) {
                approval.decidedBy?.takeIf { it.isNotBlank() }?.let { fullData["decidedBy"] = it }
                approval.decidedAt?.takeIf { it > 0 }?.let { fullData["decidedAt"] = it }
                try {
                    docRef.set(fullData, SetOptions.merge()).await()
                } catch (e: FirebaseFirestoreException) {
                    if (e.code == FirebaseFirestoreException.Code.PERMISSION_DENIED) {
                        reconcileWithCloudDocument(docRef, approval)
                        return@withContext false
                    }
                    throw e
                }
            } else {
                val decidedByValue = approval.decidedBy?.takeIf { it.isNotBlank() } ?: "SELF"
                val decidedAtValue = approval.decidedAt?.takeIf { it > 0 } ?: currentTimeMillisProvider()
                fullData["decidedBy"] = decidedByValue
                fullData["decidedAt"] = decidedAtValue

                val decisionUpdate = mapOf<String, Any>(
                    "status" to approval.status,
                    "decidedBy" to decidedByValue,
                    "decidedAt" to decidedAtValue
                )
                try {
                    docRef.update(decisionUpdate).await()
                } catch (e: FirebaseFirestoreException) {
                    when (e.code) {
                        FirebaseFirestoreException.Code.NOT_FOUND -> {
                            try {
                                docRef.set(fullData, SetOptions.merge()).await()
                            } catch (setEx: FirebaseFirestoreException) {
                                if (setEx.code == FirebaseFirestoreException.Code.PERMISSION_DENIED) {
                                    reconcileWithCloudDocument(docRef, approval)
                                    return@withContext false
                                }
                                throw setEx
                            }
                        }
                        FirebaseFirestoreException.Code.PERMISSION_DENIED -> {
                            reconcileWithCloudDocument(docRef, approval)
                            return@withContext false
                        }
                        else -> throw e
                    }
                }
            }

            approvalDao.markApprovalCloudSyncedIfMatchingBlocking(
                packageName = approval.packageName,
                expectedStatus = expectedStatus,
                expectedDecidedAt = expectedDecidedAt,
                isSynced = 1
            )
            Log.i(TAG, "Uploaded approval ticket to cloud for ${approval.packageName} under /policies/$uid/approvals")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error uploading approval ticket to Firestore for ${approval.packageName}", e)
            false
        }
    }

    private suspend fun reconcileWithCloudDocument(
        docRef: DocumentReference,
        localApproval: PendingApprovalEntity
    ) {
        val packageName = localApproval.packageName
        try {
            val snapshot = docRef.get().await()
            if (!snapshot.exists()) return
            val cloudStatus = snapshot.getString("status") ?: return
            val cloudDecidedBy = snapshot.getString("decidedBy")
            when (cloudStatus) {
                PendingApprovalEntity.STATUS_REJECTED -> {
                    Log.w(TAG, "Reconciling local state for $packageName to cloud REJECTED (decidedBy=$cloudDecidedBy) after PERMISSION_DENIED")
                    recordRejection(packageName, cloudDecidedBy, syncToCloud = false)
                }
                PendingApprovalEntity.STATUS_APPROVED -> {
                    val isGuardianDecision = !cloudDecidedBy.isNullOrBlank() &&
                        !cloudDecidedBy.equals("SELF", ignoreCase = true)
                    if (isGuardianDecision) {
                        Log.w(TAG, "Reconciling local state for $packageName to cloud guardian APPROVED (decidedBy=$cloudDecidedBy) after PERMISSION_DENIED")
                        unsuspendAndApprove(
                            packageName = packageName,
                            decidedBy = cloudDecidedBy,
                            syncToCloud = false,
                            forceInventorySync = false
                        )
                    } else if (localApproval.isApproved) {
                        Log.i(TAG, "Cloud already holds APPROVED ($cloudDecidedBy) for $packageName; marking local row synced")
                        approvalDao.markApprovalCloudSyncedIfMatchingBlocking(
                            packageName = packageName,
                            expectedStatus = localApproval.status,
                            expectedDecidedAt = localApproval.decidedAt,
                            isSynced = 1
                        )
                    }
                }
            }
        } catch (reconcileError: Exception) {
            Log.w(TAG, "Failed reconciling cloud document for $packageName after PERMISSION_DENIED: ${reconcileError.message}")
        }
    }

    suspend fun uploadSingleApproval(approval: PendingApprovalEntity): Boolean {
        return uploadSingleApprovalSync(approval)
    }

    /**
     * Unfreezes and approves a pending application.
     * Guaranteed Invariants:
     * 1. Permanently blacklisted applications (Telegram, TikTok, etc.) are NEVER unsuspended.
     * 2. If Night Curfew or Nap is currently active and the app is not whitelisted, the approval
     *    is recorded in the database, but suspension remains active until morning curfew wakeup.
     */
    fun unsuspendAndApprove(
        packageName: String,
        decidedBy: String? = null,
        syncToCloud: Boolean = true,
        forceInventorySync: Boolean = false
    ): ApprovalDecisionResult {
        // Under Unified Zero-Trust architecture, supervisor approval is unconditionally honored for all applications.

        val policy = policyProvider()
        if (policy.customBlacklistPackages.contains(packageName)) {
            Log.w(TAG, "Security Alert: Cannot unsuspend $packageName - currently in custom blacklist! Skipping unsuspend without poisoning approval record.")
            return ApprovalDecisionResult.BLOCKED_BLACKLISTED
        }

        val isSelf = decidedBy?.equals("SELF", ignoreCase = true) == true
        // Security Guard: Prevent ward self-approval from overriding explicit guardian REJECTED status or forbidden self-control
        if (isSelf) {
            val existing = approvalDao.getApprovalByPackageNameBlocking(packageName)
            if (existing?.status?.equals(PendingApprovalEntity.STATUS_REJECTED, ignoreCase = true) == true) {
                Log.w(TAG, "Security Alert: Ward attempted self-approval for $packageName which was REJECTED by guardian! Aborting self-approval.")
                return ApprovalDecisionResult.BLOCKED_REJECTED
            }
            if (syncToCloud) {
                val isSupervised = policy.isSupervised || !policy.guardianUid.isNullOrBlank()
                if (isSupervised && (!policy.isSelfControlAllowed || (!policy.selfControlNewAppsAllowed && !policy.selfControlAppsAllowed))) {
                    Log.w(TAG, "Security Alert: Ward attempted self-approval for $packageName while self-control for new/app approvals is forbidden by supervisor!")
                    return ApprovalDecisionResult.BLOCKED_SELF_CONTROL_FORBIDDEN
                }
            }
        }

        if (!isDeviceOwnerProvider()) return ApprovalDecisionResult.FAILED_NOT_DEVICE_OWNER

        val resolvedAppLabel = resolveAppLabel(packageName)
        val now = currentTimeMillisProvider()
        val targetSynced = if (syncToCloud) 0 else 1
        val isBlockActive = isBlockActiveProvider()
        val isWhitelisted = CurfewConfig.STATIC_WHITELIST.contains(packageName) ||
                immunePackagesProvider().contains(packageName) ||
                policy.customCurfewWhitelistPackages.contains(packageName)

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
            watcherSyncer()
            if (syncToCloud) {
                cloudDecisionSyncer(packageName)
            }
            notificationDispatcher(packageName, true, true, decidedBy)
            return ApprovalDecisionResult.APPROVED_CURFEW_DEFERRED
        }

        return try {
            // Unhide application if it was hidden via Fail-Secure fallback
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
                val isInstalled = isPackageInstalledProvider(packageName)

                if (!isInstalled) {
                    Log.i(TAG, "Package $packageName is uninstalled; marking pending approval as approved/resolved")
                    approvalDao.upsertApprovalStatusBlocking(
                        packageName = packageName,
                        appName = resolvedAppLabel,
                        status = PendingApprovalEntity.STATUS_APPROVED,
                        decidedBy = decidedBy,
                        decidedAt = now,
                        isSynced = targetSynced
                    )
                    watcherSyncer()
                    if (syncToCloud) {
                        cloudDecisionSyncer(packageName)
                    }
                    ApprovalDecisionResult.APPROVED
                } else {
                    Log.e(TAG, "Failed unfreezing package $packageName: DPM returned failure")
                    ApprovalDecisionResult.FAILED_DPM_ERROR
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
                watcherSyncer()
                if (syncToCloud) {
                    cloudDecisionSyncer(packageName)
                }
                if (forceInventorySync) {
                    try {
                        AppInventoryManager.getInstance(appContext).syncInventoryToCloudAsync(force = true)
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed triggering forced inventory sync after approval of $packageName", e)
                    }
                }
                notificationDispatcher(packageName, true, false, decidedBy)
                ApprovalDecisionResult.APPROVED
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed unfreezing package $packageName", e)
            ApprovalDecisionResult.FAILED_DPM_ERROR
        }
    }

    /**
     * Cleans up pending approval when a package is uninstalled or removed.
     */
    fun removePendingApproval(packageName: String) {
        try {
            val existing = approvalDao.getApprovalByPackageNameBlocking(packageName)
            if (existing?.isRejected == true) {
                Log.i(TAG, "Preserving REJECTED approval record for $packageName on uninstall to prevent uninstall-reinstall bypass")
                watcherSyncer()
                return
            }
            approvalDao.deletePendingApprovalBlocking(packageName)
            watcherSyncer()
            deleteApprovalFromCloud(packageName)
            Log.i(TAG, "Removed pending approval for package: $packageName")
        } catch (e: Exception) {
            Log.e(TAG, "Failed removing pending approval for package $packageName", e)
        }
    }

    /**
     * Records a rejection for a pending application in local database.
     */
    fun recordRejection(
        packageName: String,
        decidedBy: String? = null,
        syncToCloud: Boolean = true
    ) {
        try {
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
            watcherSyncer()
            if (syncToCloud) {
                cloudDecisionSyncer(packageName)
            }
            notificationDispatcher(packageName, false, false, decidedBy)
        } catch (e: Exception) {
            Log.e(TAG, "Failed recording rejection for package $packageName", e)
        }
    }

    /**
     * Records an approval for an application in local database (e.g. auto-approved when policy is unlocked).
     */
    fun recordApproval(packageName: String, appName: String, decidedBy: String = "AUTO_APPROVED_UNLOCKED") {
        try {
            val now = currentTimeMillisProvider()
            val entity = PendingApprovalEntity(
                packageName = packageName,
                appName = appName,
                requestedAt = now,
                status = PendingApprovalEntity.STATUS_APPROVED,
                decidedBy = decidedBy,
                decidedAt = now,
                isCloudSynced = true
            )
            approvalDao.savePendingApprovalBlocking(entity)
            Timber.tag(TAG).i("Recorded approval for $packageName decidedBy $decidedBy")
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed recording approval for $packageName")
        }
    }

    private fun postApprovalNotification(
        packageName: String,
        approved: Boolean,
        isCurfewDeferred: Boolean,
        decidedBy: String?
    ) {
        try {
            val appLabel = resolveAppLabel(packageName)

            val isSelf = decidedBy?.equals("SELF", ignoreCase = true) == true
            val guardianName = decidedBy?.takeUnless { it.isBlank() || it.equals("null", ignoreCase = true) }
                ?: appContext.getString(R.string.guardian_default_name)

            val title: String
            val desc: String
            val actionType: NotificationActionType

            if (approved) {
                title = appContext.getString(R.string.notif_approval_granted_title)
                if (isCurfewDeferred) {
                    desc = if (isSelf) {
                        appContext.getString(R.string.notif_self_qr_approval_deferred_curfew_desc, appLabel)
                    } else {
                        appContext.getString(R.string.notif_approval_deferred_curfew_desc, appLabel, guardianName)
                    }
                    actionType = NotificationActionType.OPEN_CURFEW
                } else {
                    desc = if (isSelf) {
                        appContext.getString(R.string.notif_self_qr_approval_granted_desc, appLabel)
                    } else {
                        appContext.getString(R.string.notif_approval_granted_desc, guardianName, appLabel)
                    }
                    actionType = NotificationActionType.LAUNCH_APP
                }
            } else {
                title = appContext.getString(R.string.notif_approval_rejected_title)
                desc = if (isSelf) {
                    appContext.getString(R.string.notif_self_qr_approval_rejected_desc, appLabel)
                } else {
                    appContext.getString(R.string.notif_approval_rejected_desc, guardianName, appLabel)
                }
                actionType = NotificationActionType.NONE
            }

            val dedupKey = if (approved) "approval_${packageName}_APPROVED" else "approval_${packageName}_REJECTED"

            AppNotificationManager.getInstance(appContext).postEventNotification(
                category = NotificationCategory.APPROVALS,
                title = title,
                message = desc,
                dedupKey = dedupKey,
                cooldownMs = 10 * 60 * 1000L,
                actionType = actionType,
                actionPayload = packageName,
                systemNotificationId = packageName.hashCode(),
                channelId = NotificationRegistry.CHANNEL_ID_PROTECTION_EVENTS,
                postSystemNotification = true,
                importance = 2
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error posting approval notification for $packageName", e)
        }
    }

    private fun syncApprovalDecisionToCloud(packageName: String) {
        scope.launch {
            try {
                val entity = approvalDao.getApprovalByPackageNameBlocking(packageName) ?: return@launch
                uploadSingleApprovalSync(entity)
            } catch (e: Exception) {
                Log.w(TAG, "Failed syncing approval decision for $packageName to cloud: ${e.message}")
            }
        }
    }

    private fun deleteApprovalFromCloud(packageName: String) {
        val myUid = FirebaseAuth.getInstance().currentUser?.uid
            ?: AccountabilityRepository.getInstance(appContext).getCachedUid()
        if (myUid.isBlank()) return
        scope.launch {
            try {
                FirebaseFirestore.getInstance().collection("policies")
                    .document(myUid)
                    .collection("approvals")
                    .document(packageName)
                    .delete()
                    .await()
                Log.i(TAG, "Deleted cloud approval document for $packageName ($myUid)")
            } catch (e: Exception) {
                Log.w(TAG, "Failed deleting cloud approval document for $packageName: ${e.message}")
            }
        }
    }

    /**
     * Starts listening to Firestore for real-time approvals and rejections for this ward device
     * under `/policies/$wardUid/approvals`.
     */
    fun startApprovalsSync(wardUid: String) {
        approvalsListener?.remove()
        try {
            val firestore = FirebaseFirestore.getInstance()
            approvalsListener = firestore.collection("policies")
                .document(wardUid)
                .collection("approvals")
                .addSnapshotListener { snapshots, error ->
                    if (error != null) {
                        Log.w(TAG, "Error listening for approval updates: ${error.message}")
                        return@addSnapshotListener
                    }
                    if (snapshots != null && !snapshots.isEmpty) {
                        scope.launch {
                            try {
                                val isBlockActive = CurfewEnforcer.isEffectiveBlockActive(appContext)
                                val policy = PolicyRepository.getInstance(appContext).getCurrentPolicy()
                                val immunePackages = CurfewAppResolver.resolveImmunePackages(appContext)
                                val isWhitelistedPkg = { pkg: String ->
                                    CurfewConfig.STATIC_WHITELIST.contains(pkg) ||
                                            immunePackages.contains(pkg) ||
                                            policy.customCurfewWhitelistPackages.contains(pkg)
                                }

                                val pendingMap = getLocalPendingApprovals().associateBy { it.packageName }
                                for (doc in snapshots.documents) {
                                    val packageName = doc.getString("packageName") ?: doc.id
                                    val status = doc.getString("status") ?: continue
                                    val decidedBy = doc.getString("decidedBy")
                                    val local = pendingMap[packageName]

                                    if (status == "APPROVED") {
                                        val isCurfewDeferred = isBlockActive && !isWhitelistedPkg(packageName)
                                        if (local != null && local.isApproved) {
                                            if (isCurfewDeferred) {
                                                // Already recorded as approved locally and legitimately deferred by curfew; skip!
                                                continue
                                            }
                                            val isPhysicallySuspended = com.drmohammed.antiporn.telemetry.SafeDpmGateway.isPackageSuspendedBlocking(appContext, packageName).getOrNull() == true
                                            if (!isPhysicallySuspended) {
                                                // Already approved locally and physically active; skip!
                                                continue
                                            }
                                        }

                                        Log.i(TAG, "Real-time approval received for $packageName from $decidedBy (localApproved=${local?.isApproved}, curfewDeferred=$isCurfewDeferred)")
                                        unsuspendAndApprove(
                                            packageName = packageName,
                                            decidedBy = decidedBy,
                                            syncToCloud = false, // Critical: originated from cloud, do not ping-pong back!
                                            forceInventorySync = false
                                        )
                                    } else if (status == "REJECTED") {
                                        if (local == null || !local.isRejected) {
                                            Log.i(TAG, "Real-time rejection received for $packageName from $decidedBy")
                                            recordRejection(packageName, decidedBy, syncToCloud = false)
                                        }
                                    }
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "Error handling approval snapshot update", e)
                            }
                        }
                    }
                }
            Log.i(TAG, "Started approvals sync for ward $wardUid on /policies/$wardUid/approvals")
            scope.launch {
                syncPendingApprovalsToCloud()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error starting approvals sync", e)
        }
    }

    /**
     * Safely detaches the Firestore approvals snapshot listener on teardown.
     */
    fun stopApprovalsSync() {
        approvalsListener?.remove()
        approvalsListener = null
        Log.i(TAG, "Stopped approvals sync listener")
    }

    /**
     * Submits an approval or rejection from a Guardian to a Ward.
     * Updates the approval subcollection document `/policies/$wardUid/approvals/$packageName`
     * and simultaneously bumps the parent policy document `/policies/$wardUid` with:
     * `version = FieldValue.increment(1)` and `"updatedAt" = System.currentTimeMillis()`.
     * This dual-channel update guarantees that the ward's active PolicyRepository listener
     * is immediately triggered and wakes up to reconcile the system.
     */
    suspend fun resolveWardApproval(
        wardUid: String,
        packageName: String,
        approve: Boolean,
        guardianName: String
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val firestore = FirebaseFirestore.getInstance()
            val newStatus = if (approve) "APPROVED" else "REJECTED"
            val now = System.currentTimeMillis()

            val batch = firestore.batch()

            // Channel 1: Update the approval subcollection document /policies/$wardUid/approvals/$packageName
            val approvalDocRef = firestore.collection("policies")
                .document(wardUid)
                .collection("approvals")
                .document(packageName)

            val approvalUpdates = mapOf(
                "status" to newStatus,
                "decidedBy" to guardianName,
                "decidedAt" to now
            )
            batch.set(approvalDocRef, approvalUpdates, SetOptions.merge())

            // Channel 2: Simultaneously bump parent policy document /policies/$wardUid
            val policyDocRef = firestore.collection("policies").document(wardUid)
            val policyUpdates = mapOf(
                "version" to FieldValue.increment(1),
                "updatedAt" to now
            )
            batch.set(policyDocRef, policyUpdates, SetOptions.merge())

            batch.commit().await()
            Log.i(TAG, "Resolved ward approval for $packageName: $newStatus with dual-channel policy bump (ward: $wardUid)")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed resolving ward approval for $packageName", e)
            false
        }
    }

    fun getLocalPendingApprovals(): List<PendingApprovalEntity> = approvalDao.getPendingApprovalsBlocking()

    fun getActualLocalPendingApprovals(): List<PendingApprovalEntity> = approvalDao.getActualPendingApprovalsBlocking()

    /**
     * Seeds all pre-existing installed applications as the Initial Approved Baseline.
     * Prevents existing applications from being frozen when AntiPorn is set up on an already-in-use device
     * or initialized via QR provisioning / WebADB.
     * Also automatically unfreezes any baseline applications that were accidentally suspended previously.
     */
    @Synchronized
    fun ensureInitialBaselineSeeded() {
        val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_BASELINE_SEEDED, false)) {
            return
        }

        Log.i(TAG, "🌱 [SEED_BASELINE] Establishing Initial Zero-Trust Baseline for pre-existing apps...")
        val pm = appContext.packageManager
        val isDo = com.drmohammed.antiporn.telemetry.SafeDpmGateway.isDeviceOwner(appContext)

        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            PackageManager.MATCH_UNINSTALLED_PACKAGES or PackageManager.MATCH_DISABLED_COMPONENTS
        } else {
            @Suppress("DEPRECATION")
            PackageManager.GET_UNINSTALLED_PACKAGES or PackageManager.GET_DISABLED_COMPONENTS
        }
        val installedPackages = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getInstalledPackages(PackageManager.PackageInfoFlags.of(flags.toLong())) ?: emptyList()
            } else {
                @Suppress("DEPRECATION")
                pm.getInstalledPackages(flags) ?: emptyList()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed retrieving installed packages for baseline seeding", e)
            emptyList()
        }

        val packagesToUnsuspend = mutableListOf<String>()
        val baselineEntities = mutableListOf<PendingApprovalEntity>()

        val policy = if (isDo) PolicyRepository.getInstance(appContext).getCurrentPolicy() else null
        val isBlockActive = if (isDo) CurfewEnforcer.isEffectiveBlockActive(appContext) else false
        val immunePackages = if (isDo) CurfewAppResolver.resolveImmunePackages(appContext) else emptySet()

        for (pkgInfo in installedPackages) {
            val pkg = pkgInfo.packageName ?: continue
            if (pkg == appContext.packageName) continue
            // If it is permanently blacklisted or high-risk, do not approve into baseline!
            if (AppSuspensionShield.isBlacklisted(pkg) || AppPolicyRegistry.isHighRisk(pkg)) continue

            val appInfo = pkgInfo.applicationInfo
            val appName = try {
                if (appInfo != null) pm.getApplicationLabel(appInfo).toString() else pkg
            } catch (e: Exception) {
                pkg
            }

            // Seed pre-existing app as APPROVED baseline
            val nowMs = com.drmohammed.antiporn.core.time.TrustedTimeProvider.currentTimeMillis(appContext)
            val baselineEntity = PendingApprovalEntity(
                packageName = pkg,
                appName = appName,
                requestedAt = nowMs,
                status = PendingApprovalEntity.STATUS_APPROVED,
                decidedBy = "INITIAL_DEVICE_BASELINE",
                decidedAt = nowMs,
                isCloudSynced = true // Do not flood Firestore
            )
            baselineEntities.add(baselineEntity)

            // If it was inadvertently suspended or hidden, queue it for unfreezing ONLY if not restricted by Curfew or Custom Blacklist
            if (isDo && policy != null) {
                val isWhitelisted = CurfewConfig.STATIC_WHITELIST.contains(pkg) || immunePackages.contains(pkg)

                if (policy.customBlacklistPackages.contains(pkg)) {
                    continue
                }
                if (isBlockActive && !isWhitelisted) {
                    continue
                }

                val isSuspended = com.drmohammed.antiporn.telemetry.SafeDpmGateway.isPackageSuspendedBlocking(appContext, pkg).getOrNull() == true
                val isHidden = com.drmohammed.antiporn.telemetry.SafeDpmGateway.isApplicationHiddenBlocking(appContext, pkg).getOrNull() == true
                if (isSuspended || isHidden) {
                    packagesToUnsuspend.add(pkg)
                }
            }
        }

        // Batch insert pre-existing baseline approvals in a single database transaction
        if (baselineEntities.isNotEmpty()) {
            approvalDao.savePendingApprovalsBlocking(baselineEntities)
        }

        // Unsuspend and unhide all pre-existing baseline packages
        if (isDo && packagesToUnsuspend.isNotEmpty()) {
            Log.w(TAG, "🌱 [UNFREEZE_BASELINE] Unfreezing ${packagesToUnsuspend.size} pre-existing baseline packages...")
            try {
                com.drmohammed.antiporn.telemetry.SafeDpmGateway.setPackagesSuspendedBlocking(appContext, packagesToUnsuspend.toTypedArray(), false)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to unsuspend baseline packages via DPM", e)
            }
            for (pkg in packagesToUnsuspend) {
                try {
                    com.drmohammed.antiporn.telemetry.SafeDpmGateway.setApplicationHiddenBlocking(appContext, pkg, false)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to unhide baseline package $pkg", e)
                }
            }
        }

        ZeroTrustForegroundWatcher.syncWatcherState(appContext)
        prefs.edit().putBoolean(KEY_BASELINE_SEEDED, true).apply()
        Log.i(TAG, "✅ [SEED_BASELINE] Initial Zero-Trust Baseline successfully established with ${installedPackages.size} packages.")
    }

    fun ensureInitialBaselineSeededAsync() {
        scope.launch {
            try {
                ensureInitialBaselineSeeded()
            } catch (e: Exception) {
                Log.e(TAG, "Error in ensureInitialBaselineSeededAsync", e)
            }
        }
    }

    /**
     * Self-Healing Reconciliation: scans installed applications to catch any newly installed apps
     * that escaped or bypassed the broadcast receiver due to OEM power management or install source.
     * Operates completely offline, skipping system criticals, immune apps, and pre-approved apps.
     */
    @Synchronized
    fun reconcileUnapprovedInstalledApps() {
        if (!com.drmohammed.antiporn.core.engine.ProtectionEngine.isDeviceOwner(appContext)) return
        val policy = PolicyRepository.getInstance(appContext).getCurrentPolicy()
        if (!policy.lockNewInstallsUntilApproved) return

        // 1. Establish initial baseline on first run to protect pre-existing apps
        ensureInitialBaselineSeeded()

        if (!com.drmohammed.antiporn.telemetry.SafeDpmGateway.isDeviceOwner(appContext)) return
        val pm = appContext.packageManager

        val matchFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            PackageManager.MATCH_UNINSTALLED_PACKAGES or PackageManager.MATCH_DISABLED_COMPONENTS
        } else {
            @Suppress("DEPRECATION")
            PackageManager.GET_UNINSTALLED_PACKAGES or PackageManager.GET_DISABLED_COMPONENTS
        }
        val installedPackages = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getInstalledPackages(PackageManager.PackageInfoFlags.of(matchFlags.toLong())) ?: emptyList()
            } else {
                @Suppress("DEPRECATION")
                pm.getInstalledPackages(matchFlags) ?: emptyList()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed retrieving installed packages for Zero-Trust reconciliation", e)
            emptyList()
        }

        val localApprovals = getLocalPendingApprovals()

        val approvedSet = localApprovals.filter { it.isApproved }.map { it.packageName }.toSet()
        val pendingSet = localApprovals.filter { it.isPending }.map { it.packageName }.toSet()
        val rejectedSet = localApprovals.filter { it.isRejected }.map { it.packageName }.toSet()

        var strayAppFrozen = false
        val doEnabledAt = getDeviceOwnerEnabledTimestamp()

        for (pkgInfo in installedPackages) {
            val pkg = pkgInfo.packageName ?: continue
            if (pkg == appContext.packageName) continue
            // Skip App Stores
            if (pkg == CurfewConfig.GOOGLE_PLAY_STORE_PACKAGE || CurfewConfig.KNOWN_APP_STORES.contains(pkg)) continue

            // Skip ROM System apps & updated system apps
            val appInfo = pkgInfo.applicationInfo
            val isSystemApp = appInfo != null && (
                (appInfo.flags and ApplicationInfo.FLAG_SYSTEM != 0) ||
                (appInfo.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP != 0)
            )
            if (isSystemApp) continue

            if (approvedSet.contains(pkg)) {
                // Universal Self-Healing: Approved apps must never be hidden
                try {
                    if (com.drmohammed.antiporn.telemetry.SafeDpmGateway.isApplicationHiddenBlocking(appContext, pkg).getOrNull() == true) {
                        com.drmohammed.antiporn.telemetry.SafeDpmGateway.setApplicationHiddenBlocking(appContext, pkg, false)
                        Log.i(TAG, "Universal Self-Healing: Unhid legacy hidden approved package $pkg")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Universal Self-Healing failed for approved $pkg", e)
                }
                continue
            }
            if (com.drmohammed.antiporn.curfew.CurfewAppResolver.isSystemCriticalOrImmune(appContext, pkg)) continue

            // Universal Self-Healing: Under Unified Zero-Trust, no non-rejected app should EVER be hidden!
            // If an app is hidden by a legacy failure fallback, immediately restore its visibility so it shows as frozen.
            if (!rejectedSet.contains(pkg)) {
                try {
                    if (com.drmohammed.antiporn.telemetry.SafeDpmGateway.isApplicationHiddenBlocking(appContext, pkg).getOrNull() == true) {
                        com.drmohammed.antiporn.telemetry.SafeDpmGateway.setApplicationHiddenBlocking(appContext, pkg, false)
                        Log.i(TAG, "Universal Self-Healing: Unhid legacy hidden package $pkg")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Universal Self-Healing failed for $pkg", e)
                }
            }

            // Auto-Adopt Whitelisted Apps: If the package is in policy's customCurfewWhitelistPackages,
            // it has been explicitly approved by the supervisor. Auto-adopt it into approved baseline without freezing.
            if (policy.customCurfewWhitelistPackages.contains(pkg)) {
                Log.i(TAG, "🌱 [AUTO_ADOPT_WHITELISTED] Package '$pkg' is in customCurfewWhitelistPackages. Auto-adopting into approved baseline.")
                val appName = try {
                    if (appInfo != null) pm.getApplicationLabel(appInfo).toString() else pkg
                } catch (e: Exception) {
                    pkg
                }
                val nowMs = com.drmohammed.antiporn.core.time.TrustedTimeProvider.currentTimeMillis(appContext)
                val baselineEntity = PendingApprovalEntity(
                    packageName = pkg,
                    appName = appName,
                    requestedAt = pkgInfo.firstInstallTime.takeIf { it > 0 } ?: nowMs,
                    status = PendingApprovalEntity.STATUS_APPROVED,
                    decidedBy = "GUARDIAN_POLICY_WHITELIST",
                    decidedAt = nowMs,
                    isCloudSynced = true
                )
                approvalDao.savePendingApprovalBlocking(baselineEntity)
                continue
            }

            // Auto-Adopt Pre-existing Apps: If the app was installed prior to or during Device Owner activation,
            // it is part of the user's pre-existing baseline. Auto-adopt it into approved baseline without freezing.
            val firstInstallTime = pkgInfo.firstInstallTime
            val isPreExisting = doEnabledAt > 0L && firstInstallTime > 0L && firstInstallTime <= (doEnabledAt + DO_ACTIVATION_GRACE_PERIOD_MS)
            if (isPreExisting && !AppPolicyRegistry.isHighRisk(pkg)) {
                Log.i(TAG, "🌱 [AUTO_ADOPT_PRE_EXISTING] Package '$pkg' was installed prior to Device Owner activation ($firstInstallTime <= $doEnabledAt). Auto-adopting into approved baseline.")
                val appName = try {
                    if (appInfo != null) pm.getApplicationLabel(appInfo).toString() else pkg
                } catch (e: Exception) {
                    pkg
                }
                val nowMs = com.drmohammed.antiporn.core.time.TrustedTimeProvider.currentTimeMillis(appContext)
                val baselineEntity = PendingApprovalEntity(
                    packageName = pkg,
                    appName = appName,
                    requestedAt = firstInstallTime,
                    status = PendingApprovalEntity.STATUS_APPROVED,
                    decidedBy = "INITIAL_DEVICE_BASELINE",
                    decidedAt = nowMs,
                    isCloudSynced = true
                )
                approvalDao.savePendingApprovalBlocking(baselineEntity)
                continue
            }

            // If it was already rejected by guardian, ensure it is suspended/hidden if active, but do NOT call freezeAndSubmitApprovalLocal or modify status to PENDING
            if (rejectedSet.contains(pkg)) {
                val isSuspended = com.drmohammed.antiporn.telemetry.SafeDpmGateway.isPackageSuspendedBlocking(appContext, pkg).getOrNull() == true
                val isHidden = com.drmohammed.antiporn.telemetry.SafeDpmGateway.isApplicationHiddenBlocking(appContext, pkg).getOrNull() == true
                if (!isSuspended && !isHidden) {
                    Log.w(TAG, "🚨 [RECONCILE_REJECTED_APP] Rejected package '$pkg' was active. Enforcing suspension/hide!")
                    val suspended = com.drmohammed.antiporn.telemetry.SafeDpmGateway.setPackageSuspendedWithMessageBlocking(
                        context = appContext,
                        packageName = pkg,
                        message = appContext.getString(R.string.toast_app_suspended_by_guardian)
                    ).getOrNull() == true
                    if (!suspended) {
                        try {
                            com.drmohammed.antiporn.telemetry.SafeDpmGateway.setApplicationHiddenBlocking(appContext, pkg, true)
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to hide rejected package $pkg", e)
                        }
                    }
                }
                continue
            }

            // If it's already recorded as pending, ensure suspension is enforced
            if (pendingSet.contains(pkg)) {
                val isSuspended = com.drmohammed.antiporn.telemetry.SafeDpmGateway.isPackageSuspendedBlocking(appContext, pkg).getOrNull() == true
                if (!isSuspended) {
                    val isHighRisk = com.drmohammed.antiporn.core.policy.AppPolicyRegistry.isHighRisk(pkg)
                    val msg = if (isHighRisk) {
                        appContext.getString(R.string.toast_high_risk_app_suspended)
                    } else {
                        appContext.getString(R.string.toast_app_suspended_by_guardian)
                    }
                    com.drmohammed.antiporn.telemetry.SafeDpmGateway.setPackageSuspendedWithMessageBlocking(
                        appContext,
                        pkg,
                        msg
                    )
                }
                continue
            }

            // Unapproved package detected that is NOT suspended/hidden! Enforce instant freeze!
            Log.w(TAG, "🚨 [RECONCILE_STRAY_APP] Found unapproved active package '$pkg' (escaped receiver). Freezing silently in background!")
            val isHighRisk = com.drmohammed.antiporn.core.policy.AppPolicyRegistry.isHighRisk(pkg)
            if (freezeAndSubmitApprovalLocal(pkg, showToast = false, isHighRisk = isHighRisk)) {
                strayAppFrozen = true
            }
        }

        if (strayAppFrozen) {
            scope.launch {
                try {
                    syncPendingApprovalsToCloud()
                } catch (e: Exception) {
                    Log.w(TAG, "Failed syncing reconciled approvals to cloud", e)
                }
            }
        }
    }

    fun reconcileUnapprovedInstalledAppsAsync() {
        scope.launch {
            try {
                reconcileUnapprovedInstalledApps()
            } catch (e: Exception) {
                Log.e(TAG, "Error in reconcileUnapprovedInstalledAppsAsync", e)
            }
        }
    }

    /**
     * Retrieves the timestamp when Device Owner was enabled, or initializes it if DO is currently active.
     */
    fun getDeviceOwnerEnabledTimestamp(): Long {
        val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        var ts = prefs.getLong(KEY_DEVICE_OWNER_ENABLED_AT, 0L)
        if (ts == 0L && com.drmohammed.antiporn.core.engine.ProtectionEngine.isDeviceOwner(appContext)) {
            ts = com.drmohammed.antiporn.core.time.TrustedTimeProvider.currentTimeMillis(appContext)
            prefs.edit().putLong(KEY_DEVICE_OWNER_ENABLED_AT, ts).apply()
            Log.i(TAG, "Recorded initial Device Owner enabled timestamp: $ts")
        }
        return ts
    }

    /**
     * Explicitly records or updates the timestamp when Device Owner was granted to this app.
     */
    fun recordDeviceOwnerEnabledTimestamp(timestamp: Long = com.drmohammed.antiporn.core.time.TrustedTimeProvider.currentTimeMillis(appContext)) {
        val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val existing = prefs.getLong(KEY_DEVICE_OWNER_ENABLED_AT, 0L)
        if (existing == 0L || timestamp > 0L) {
            prefs.edit().putLong(KEY_DEVICE_OWNER_ENABLED_AT, timestamp).apply()
            Log.i(TAG, "Device Owner enabled timestamp recorded: $timestamp")
        }
    }

    /**
     * Sequentially establishes or updates the Zero-Trust baseline and reconciles stray applications.
     * Eliminates race conditions by ensuring baseline is fully established before any unapproved app checks run.
     */
    @Synchronized
    fun syncZeroTrustBaselineAndReconcile() {
        if (!com.drmohammed.antiporn.core.engine.ProtectionEngine.isDeviceOwner(appContext)) return
        getDeviceOwnerEnabledTimestamp()
        ensureInitialBaselineSeeded()
        reconcileUnapprovedInstalledApps()
    }

    /**
     * Asynchronously executes [syncZeroTrustBaselineAndReconcile] in a single background coroutine.
     */
    fun syncZeroTrustBaselineAndReconcileAsync(onComplete: (() -> Unit)? = null) {
        scope.launch {
            try {
                syncZeroTrustBaselineAndReconcile()
            } catch (e: Exception) {
                Log.e(TAG, "Error in syncZeroTrustBaselineAndReconcileAsync", e)
            } finally {
                onComplete?.invoke()
            }
        }
    }


    companion object {
        private const val TAG = "AntiPorn_ApprovalRepo"
        private const val PREFS_NAME = "zero_trust_approval_prefs"
        private const val KEY_BASELINE_SEEDED = "is_zero_trust_baseline_seeded"
        const val KEY_DEVICE_OWNER_ENABLED_AT = "device_owner_enabled_at_timestamp"
        private const val DO_ACTIVATION_GRACE_PERIOD_MS = 15_000L

        @Volatile
        private var instance: ApprovalRepository? = null

        fun getInstance(context: Context): ApprovalRepository {
            return instance ?: synchronized(this) {
                instance ?: try {
                    org.koin.core.context.GlobalContext.getOrNull()?.get<ApprovalRepository>()
                } catch (t: Throwable) {
                    null
                } ?: ApprovalRepository(context.applicationContext).also {
                    instance = it
                }
            }
        }

        @androidx.annotation.VisibleForTesting
        fun setInstance(repo: ApprovalRepository?) {
            instance = repo
        }
    }
}
