package com.drmohammed.antiporn.core.engine

import android.app.admin.DevicePolicyManager
import android.content.Context
import androidx.annotation.VisibleForTesting
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkRequest
import com.drmohammed.antiporn.R
import com.drmohammed.antiporn.accessibility.control.AccessibilityPolicyCoordinator
import com.drmohammed.antiporn.cloud.CloudSyncManager
import com.drmohammed.antiporn.core.model.DevicePolicyBundle
import com.drmohammed.antiporn.core.time.TrustedTimeProvider
import com.drmohammed.antiporn.curfew.CurfewEnforcer
import com.drmohammed.antiporn.data.repository.AccountabilityRepository
import com.drmohammed.antiporn.data.repository.ApprovalRepository
import com.drmohammed.antiporn.data.repository.PolicyRepository
import com.drmohammed.antiporn.telemetry.AiTelemetryReporter
import com.drmohammed.antiporn.telemetry.BlackBoxRecorder
import com.drmohammed.antiporn.telemetry.SafeDpmGateway
import com.drmohammed.antiporn.work.CloudSyncWorker
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.Source
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.util.concurrent.TimeUnit

/**
 * محرك التفكيك الآمن لصلاحيات مالك الجهاز (Device Owner Teardown Manager).
 * يضمن التفكيك الكامل والصارم لكافة القيود والتطبيقات المعلقة والمخفية والمنبهات وإسقاط الصلاحية
 * قبل مسح الإشراف والسياسة المحلية لتفادي وقوع هاتف المستخدم في فخ "القيود العالقة" (Orphaned Restrictions).
 */
object DeviceOwnerTeardownManager {

    private const val TAG = "AntiPorn_DOTeardown"
    private const val CLOUD_ACK_RETRY_WORK_NAME = "hisn_cloud_teardown_ack_retry"
    private val teardownMutex = Mutex()

    /**
     * تنفيذ التفكيك الشامل لصلاحية مالك الجهاز بعد انقضاء المهلة المحلية/الرقابية (48 أو 24 ساعة).
     */
    suspend fun executeTeardown(context: Context): Result<Unit> =
        executeTeardownInternal(context = context, guardianRequestedAt = null)

    /**
     * تنفيذ التفكيك الفوري المفوّض من المشرف بعد التحقق من خادم Firestore.
     */
    suspend fun executeGuardianAuthorizedTeardown(
        context: Context,
        expectedRequestedAt: Long
    ): Result<Unit> = executeTeardownInternal(
        context = context,
        guardianRequestedAt = expectedRequestedAt
    )

    private suspend fun executeTeardownInternal(
        context: Context,
        guardianRequestedAt: Long?
    ): Result<Unit> = withContext(Dispatchers.IO) {
        teardownMutex.withLock {
            val appContext = context.applicationContext ?: context
            val dpm = appContext.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
                ?: return@withLock Result.failure(IllegalStateException("DevicePolicyManager not found"))
            val admin = SafeDpmGateway.getAdminComponent(appContext)
            val policyRepo = PolicyRepository.getInstance(appContext)
            val currentPolicy = policyRepo.getCurrentPolicy()

            if (!TrustedTimeProvider.isTrusted()) {
                TrustedTimeProvider.syncNow(appContext)
            }
            if (!TrustedTimeProvider.isTrusted()) {
                Timber.tag(TAG).w("Teardown rejected: trusted network time unavailable")
                return@withLock Result.failure(
                    IllegalStateException(appContext.getString(R.string.error_trusted_time_required))
                )
            }
            val now = TrustedTimeProvider.currentTimeMillis(appContext)

            val gateFailure = verifyTeardownAuthorization(
                appContext = appContext,
                currentPolicy = currentPolicy,
                now = now,
                guardianRequestedAt = guardianRequestedAt
            )
            if (gateFailure != null) {
                return@withLock Result.failure(gateFailure)
            }

            ProtectionTransitionGuard.isTeardownInProgress = true

            try {
                Timber.tag(TAG).w(
                    "[TEARDOWN] Starting complete safe unwinding (guardianAuthorized=%b)...",
                    guardianRequestedAt != null
                )

                // 0. انتظار انتهاء أي محرك أو حارس نشط قبل تفكيك القيود
                policyRepo.awaitEnforcementIdleForTeardown()
                ProtectionEngine.awaitIdleForTeardown()
                CurfewEnforcer.awaitIdleForTeardown()
                AccessibilityPolicyCoordinator.awaitIdleForTeardown()

                val isDo = dpm.isDeviceOwnerApp(appContext.packageName)
                val isPo = dpm.isProfileOwnerApp(appContext.packageName)
                val isOwner = isDo || isPo

                // 1. إلغاء Always-On VPN أولاً ثم إيقاف خدمة الـ VPN وجميع المحركات والمنبهات
                DeviceOwnerUnwindHelper.clearAlwaysOnVpnAndStopService(appContext, dpm, admin, isOwner)
                DeviceOwnerUnwindHelper.stopBackgroundEnginesAndAlarms(appContext)

                // 2. تفكيك كافة الحزم والقيود وإعدادات النظام إذا كانت الصلاحية نشطة
                if (isOwner) {
                    DeviceOwnerUnwindHelper.unwindAllManagedPackages(appContext, dpm, admin, isDo)
                    DeviceOwnerUnwindHelper.clearAllUserRestrictions(dpm, admin)
                    DeviceOwnerUnwindHelper.restorePrivateDnsAndTimeAndSystemSettings(
                        appContext = appContext,
                        dpm = dpm,
                        admin = admin,
                        isDeviceOwner = isDo
                    )
                } else {
                    Timber.tag(TAG).w("Device is not Device/Profile Owner; cleaning supervision & local state only.")
                }

                // 3. إسقاط صلاحية مالك الجهاز/الملف الشخصي والتحقق الفعلي قبل مسح الإشراف أو السياسة المحلية
                try {
                    if (dpm.isDeviceOwnerApp(appContext.packageName)) {
                        @Suppress("DEPRECATION")
                        dpm.clearDeviceOwnerApp(appContext.packageName)
                        Timber.tag(TAG).i("[TEARDOWN_SUCCESS] clearDeviceOwnerApp invoked")
                    } else if (dpm.isProfileOwnerApp(appContext.packageName)) {
                        @Suppress("DEPRECATION")
                        dpm.clearProfileOwner(admin)
                        Timber.tag(TAG).i("[TEARDOWN_SUCCESS] clearProfileOwner invoked")
                    }
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    val msg = "Failed invoking ownership removal: ${e.message}"
                    Timber.tag(TAG).e(e, "[TEARDOWN_OWNERSHIP_CALL_FAILED] %s", msg)
                    BlackBoxRecorder.recordDpm(
                        context = appContext,
                        action = "DEVICE_OWNER_TEARDOWN_FAILED",
                        latencyMs = 0L,
                        isSuccess = false,
                        details = msg,
                        error = e.message
                    )
                    return@withLock Result.failure(e)
                }

                try {
                    if (dpm.isAdminActive(admin)) {
                        dpm.removeActiveAdmin(admin)
                        Timber.tag(TAG).i("removeActiveAdmin completed successfully")
                    }
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    Timber.tag(TAG).w(e, "Could not removeActiveAdmin: %s", e.message)
                }

                val stillOwner = dpm.isDeviceOwnerApp(appContext.packageName) ||
                    dpm.isProfileOwnerApp(appContext.packageName)
                if (stillOwner) {
                    val msg = "Device Owner privilege is still active after clearDeviceOwnerApp"
                    Timber.tag(TAG).e("[TEARDOWN_VERIFY_FAILED] %s", msg)
                    BlackBoxRecorder.recordDpm(
                        context = appContext,
                        action = "DEVICE_OWNER_TEARDOWN_FAILED",
                        latencyMs = 0L,
                        isSuccess = false,
                        details = msg,
                        error = msg
                    )
                    return@withLock Result.failure(IllegalStateException(msg))
                }

                // 4. بعد إسقاط الملكية بنجاح: مسح خط الأساس وتصفير السياسة المحلية وتنظيف الإشراف وإبلاغ السحابة
                ProtectionTransitionGuard.resetInitialEnforcement(appContext)
                try {
                    val cleanPolicy = buildPostTeardownLocalPolicy(currentPolicy, now)
                    policyRepo.updateLocalPolicyWithoutEnforcingAndFlush(cleanPolicy)
                    Timber.tag(TAG).i("Local policy reset and flushed to clean unsupervised defaults without DPM enforcement")
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    Timber.tag(TAG).w(e, "Failed resetting local policy during teardown")
                }

                DeviceOwnerUnwindHelper.clearPostOwnershipLocalData(appContext)

                val cloudAcked = pushDeactivationCompletedToCloud(appContext, currentPolicy, now)
                if (!cloudAcked && currentPolicy.isSupervised && !currentPolicy.guardianUid.isNullOrBlank()) {
                    val myUid = AccountabilityRepository.getInstance(appContext).getOrCreateCurrentUid()
                    CloudSyncManager.setPendingCloudTeardownAck(appContext, myUid, now)
                    enqueueCloudTeardownAckRetryWork(appContext)
                }

                try {
                    ApprovalRepository.getInstance(appContext).stopApprovalsSync()
                    AccountabilityRepository.getInstance(appContext).onSupervisionRevokedLocally()
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    Timber.tag(TAG).w(e, "Failed cleaning supervision state during teardown")
                }

                CloudSyncManager.checkAndAutoRollbackIfUnsupervisedSuspend(appContext)

                BlackBoxRecorder.recordDpm(
                    context = appContext,
                    action = "DEVICE_OWNER_TEARDOWN_COMPLETED",
                    latencyMs = 0L,
                    isSuccess = true,
                    details = "Device Owner successfully deactivated and all restrictions unwound (guardianAuthorized=${guardianRequestedAt != null}, cloudAcked=$cloudAcked)"
                )
                Result.success(Unit)
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                Timber.tag(TAG).e(t, "[TEARDOWN_FATAL] Failed to execute Device Owner teardown cleanly")
                BlackBoxRecorder.recordDpm(
                    context = appContext,
                    action = "DEVICE_OWNER_TEARDOWN_FAILED",
                    latencyMs = 0L,
                    isSuccess = false,
                    details = "Error: ${t.message}",
                    error = t.message
                )
                Result.failure(t)
            } finally {
                ProtectionTransitionGuard.isTeardownInProgress = false
            }
        }
    }

    /**
     * يرسل تأكيد إزالة الصلاحية المؤجلة إلى Firestore إذا تعذر إرساله لحظة التفكيك بسبب انقطاع الشبكة.
     */
    suspend fun flushPendingCloudTeardownAckIfNeeded(context: Context): Boolean = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext ?: context
        val pending = CloudSyncManager.getPendingCloudTeardownAck(appContext) ?: return@withContext false
        val (_, recordedAt) = pending
        val uid = AccountabilityRepository.getInstance(appContext).getOrCreateCurrentUid()
        if (FirebaseAuth.getInstance().currentUser == null || uid.startsWith("dev_")) {
            return@withContext false
        }
        val acked = attemptCloudCompletionWrite(uid, recordedAt)
        if (acked) {
            CloudSyncManager.clearPendingCloudTeardownAck(appContext)
            CloudSyncManager.checkAndAutoRollbackIfUnsupervisedSuspend(appContext)
        }
        acked
    }

    private fun enqueueCloudTeardownAckRetryWork(appContext: Context) {
        try {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            val request = OneTimeWorkRequestBuilder<CloudSyncWorker>()
                .setConstraints(constraints)
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    WorkRequest.MIN_BACKOFF_MILLIS,
                    TimeUnit.MILLISECONDS
                )
                .build()
            WorkManager.getInstance(appContext).enqueueUniqueWork(
                CLOUD_ACK_RETRY_WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                request
            )
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Failed scheduling cloud teardown ack retry worker")
        }
    }

    private suspend fun verifyTeardownAuthorization(
        appContext: Context,
        currentPolicy: DevicePolicyBundle,
        now: Long,
        guardianRequestedAt: Long?
    ): IllegalStateException? {
        if (guardianRequestedAt != null) {
            if (guardianRequestedAt <= 0L) {
                return IllegalStateException("Invalid guardian teardown timestamp")
            }
            if (!currentPolicy.isSupervised || currentPolicy.guardianUid.isNullOrBlank()) {
                return IllegalStateException("Guardian teardown requires active supervision")
            }
            return try {
                val myUid = AccountabilityRepository.getInstance(appContext).getOrCreateCurrentUid()
                if (FirebaseAuth.getInstance().currentUser == null || myUid.startsWith("dev_")) {
                    return IllegalStateException("Firebase authentication required to verify guardian teardown")
                }
                val snapshot = FirebaseFirestore.getInstance()
                    .collection("policies")
                    .document(myUid)
                    .get(Source.SERVER)
                    .await()
                val remoteStatus = snapshot.getString("deactivationStatus")
                val remoteUnlockAt = snapshot.getLong("deactivationUnlockAt") ?: Long.MAX_VALUE
                val remoteRequestedAt = snapshot.getLong("remoteTeardownRequestedAt") ?: 0L
                val isValid = validateGuardianTeardownServerState(
                    remoteStatus = remoteStatus,
                    remoteUnlockAt = remoteUnlockAt,
                    remoteTeardownRequestedAt = remoteRequestedAt,
                    expectedRequestedAt = guardianRequestedAt,
                    nowMs = now
                )
                if (!isValid) {
                    Timber.tag(TAG).w(
                        "Guardian teardown rejected by server check: status=%s, unlockAt=%d, reqAt=%d, expected=%d, now=%d",
                        remoteStatus,
                        remoteUnlockAt,
                        remoteRequestedAt,
                        guardianRequestedAt,
                        now
                    )
                    IllegalStateException("Server guardian teardown verification failed")
                } else {
                    null
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Timber.tag(TAG).e(e, "Guardian teardown aborted: server verification failed")
                IllegalStateException("Failed to verify guardian teardown with server: ${e.message}", e)
            }
        }

        val unlockAt = currentPolicy.deactivationUnlockAt ?: Long.MAX_VALUE
        if (currentPolicy.deactivationStatus != DevicePolicyBundle.DEACTIVATION_STATUS_PENDING) {
            Timber.tag(TAG).w(
                "Teardown rejected: deactivation status is not PENDING (current: %s)",
                currentPolicy.deactivationStatus
            )
            return IllegalStateException("Device owner deactivation is not pending")
        }
        if (now < unlockAt) {
            Timber.tag(TAG).w("Teardown rejected: deactivation cooldown not elapsed (now: %d, unlockAt: %d)", now, unlockAt)
            return IllegalStateException("Deactivation delay has not elapsed yet")
        }
        if (currentPolicy.isSupervised && !currentPolicy.guardianUid.isNullOrBlank()) {
            try {
                val myUid = AccountabilityRepository.getInstance(appContext).getOrCreateCurrentUid()
                if (FirebaseAuth.getInstance().currentUser == null || myUid.startsWith("dev_")) {
                    return IllegalStateException("Firebase authentication required to verify deactivation")
                }
                val snapshot = FirebaseFirestore.getInstance()
                    .collection("policies")
                    .document(myUid)
                    .get(Source.SERVER)
                    .await()
                val remoteStatus = snapshot.getString("deactivationStatus")
                val remoteUnlockAt = snapshot.getLong("deactivationUnlockAt") ?: Long.MAX_VALUE
                if (remoteStatus != DevicePolicyBundle.DEACTIVATION_STATUS_PENDING || now < remoteUnlockAt) {
                    Timber.tag(TAG).w(
                        "Teardown rejected by server policy check: remoteStatus=%s, remoteUnlockAt=%d, now=%d",
                        remoteStatus,
                        remoteUnlockAt,
                        now
                    )
                    return IllegalStateException("Server deactivation verification failed")
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Timber.tag(TAG).e(e, "Teardown aborted: failed to verify server timestamp with Firestore")
                return IllegalStateException("Failed to verify deactivation with guardian server: ${e.message}", e)
            }
        }
        return null
    }

    private suspend fun pushDeactivationCompletedToCloud(
        appContext: Context,
        currentPolicy: DevicePolicyBundle,
        now: Long
    ): Boolean {
        if (!currentPolicy.isSupervised || currentPolicy.guardianUid.isNullOrBlank()) {
            return true
        }
        val myUid = AccountabilityRepository.getInstance(appContext).getOrCreateCurrentUid()
        if (FirebaseAuth.getInstance().currentUser == null || myUid.startsWith("dev_")) {
            Timber.tag(TAG).w("Cannot push deactivation completion to Firestore without Firebase auth")
            return false
        }
        val retryDelaysMs = longArrayOf(0L, 600L, 1500L)
        for (delayMs in retryDelaysMs) {
            if (delayMs > 0L) delay(delayMs)
            if (attemptCloudCompletionWrite(myUid, now)) {
                CloudSyncManager.clearPendingCloudTeardownAck(appContext)
                return true
            }
        }
        return false
    }

    private suspend fun attemptCloudCompletionWrite(myUid: String, now: Long): Boolean {
        val docRef = FirebaseFirestore.getInstance().collection("policies").document(myUid)
        val updates = mapOf(
            "deactivationStatus" to DevicePolicyBundle.DEACTIVATION_STATUS_COMPLETED,
            "deactivationRequestedAt" to null,
            "deactivationUnlockAt" to null,
            "deactivationHasQr" to false,
            "remoteTeardownRequestedAt" to null,
            "guardianUid" to null,
            "guardianName" to null,
            "isSupervised" to false,
            "isInstantInternetKillEnabled" to false,
            "updatedAt" to now
        )
        return try {
            docRef.update(updates).await()
            Timber.tag(TAG).i(
                "Deactivation completed pushed to Firestore for uid: %s",
                AiTelemetryReporter.maskIdentifier(myUid)
            )
            true
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            if (e is FirebaseFirestoreException && e.code == FirebaseFirestoreException.Code.PERMISSION_DENIED) {
                try {
                    val serverDoc = docRef.get(Source.SERVER).await()
                    val status = serverDoc.getString("deactivationStatus")
                    val guardian = serverDoc.getString("guardianUid")
                    val isSupervised = serverDoc.getBoolean("isSupervised") ?: (!guardian.isNullOrBlank())
                    if (!serverDoc.exists() || !isSupervised || guardian.isNullOrBlank() ||
                        status == DevicePolicyBundle.DEACTIVATION_STATUS_COMPLETED ||
                        status == DevicePolicyBundle.DEACTIVATION_STATUS_CANCELLED
                    ) {
                        Timber.tag(TAG).i(
                            "Cloud policy already unsupervised/resolved (status=%s) during completion ack",
                            status
                        )
                        return true
                    }
                } catch (readEx: Exception) {
                    if (readEx is CancellationException) throw readEx
                }
            }
            Timber.tag(TAG).w(e, "Failed to push deactivation completed to Firestore: %s", e.message)
            false
        }
    }

    @VisibleForTesting
    internal fun validateGuardianTeardownServerState(
        remoteStatus: String?,
        remoteUnlockAt: Long,
        remoteTeardownRequestedAt: Long,
        expectedRequestedAt: Long,
        nowMs: Long
    ): Boolean {
        if (expectedRequestedAt <= 0L) return false
        if (remoteStatus != DevicePolicyBundle.DEACTIVATION_STATUS_PENDING) return false
        if (remoteTeardownRequestedAt != expectedRequestedAt) return false
        if (remoteUnlockAt <= 0L || nowMs < remoteUnlockAt) return false
        return true
    }

    @VisibleForTesting
    internal fun buildPostTeardownLocalPolicy(
        currentPolicy: DevicePolicyBundle,
        now: Long
    ): DevicePolicyBundle {
        return DevicePolicyBundle.defaultPolicy().copy(
            deactivationStatus = DevicePolicyBundle.DEACTIVATION_STATUS_COMPLETED,
            deactivationRequestedAt = null,
            deactivationUnlockAt = null,
            deactivationHasQr = false,
            remoteTeardownRequestedAt = null,
            guardianUid = null,
            guardianName = null,
            isSupervised = false,
            version = maxOf(currentPolicy.version + 1, 2L),
            updatedAt = now
        )
    }
}
