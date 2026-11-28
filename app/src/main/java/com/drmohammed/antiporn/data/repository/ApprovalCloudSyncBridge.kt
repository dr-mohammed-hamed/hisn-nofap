package com.drmohammed.antiporn.data.repository

import android.content.Context
import com.drmohammed.antiporn.core.model.DevicePolicyBundle
import com.drmohammed.antiporn.core.util.SafeAndroidLog as Log
import com.drmohammed.antiporn.curfew.CurfewAppResolver
import com.drmohammed.antiporn.curfew.CurfewEnforcer
import com.drmohammed.antiporn.data.local.db.dao.PendingApprovalDao
import com.drmohammed.antiporn.data.local.db.entity.PendingApprovalEntity
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicLong

/**
 * Bridges Firestore approval ticket synchronization, real-time snapshot handling,
 * and Guardian remote resolution for [ApprovalRepository].
 */
internal class ApprovalCloudSyncBridge(
    private val appContext: Context,
    private val approvalDao: PendingApprovalDao,
    private val scope: CoroutineScope,
    private val policyProvider: () -> DevicePolicyBundle,
    private val currentTimeMillisProvider: () -> Long,
    private val appLabelResolver: (String) -> String,
    private val firestoreUploaderProvider: () -> (suspend (PendingApprovalEntity) -> Boolean)?,
    private val onUnsuspendAndApprove: (packageName: String, decidedBy: String?, syncToCloud: Boolean, forceInventorySync: Boolean) -> ApprovalDecisionResult,
    private val onRecordRejection: (packageName: String, decidedBy: String?, syncToCloud: Boolean) -> Unit
) {

    private var approvalsListener: ListenerRegistration? = null
    private val cloudUploadMutex = Mutex()
    private val snapshotMutex = Mutex()
    private val listenerGeneration = AtomicLong(0L)

    fun syncApprovalDecisionAsync(packageName: String) {
        scope.launch {
            try {
                val entity = approvalDao.getApprovalByPackageNameBlocking(packageName) ?: return@launch
                uploadSingleApprovalSync(entity)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.w(TAG, "Failed syncing approval decision for $packageName to cloud: ${e.message}")
            }
        }
    }

    fun deleteApprovalFromCloudAsync(packageName: String) {
        val myUid = resolveUid()
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
                if (e is CancellationException) throw e
                Log.w(TAG, "Failed deleting cloud approval document for $packageName: ${e.message}")
            }
        }
    }

    suspend fun syncPendingApprovalsToCloud() = withContext(Dispatchers.IO) {
        cloudUploadMutex.withLock {
            try {
                val unsynced = approvalDao.getUnsyncedApprovalsBlocking()
                if (unsynced.isEmpty()) return@withLock
                Log.i(TAG, "Syncing ${unsynced.size} unsynced pending approvals to cloud")
                for (approval in unsynced) {
                    uploadSingleApprovalSync(approval)
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.e(TAG, "Error in syncPendingApprovalsToCloud", e)
            }
        }
    }

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

            val customUploader = firestoreUploaderProvider()
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

            val uid = resolveUid()
            if (uid.isBlank()) {
                Log.w(TAG, "Cannot upload approval for ${approval.packageName}: UID is blank")
                return@withContext false
            }

            val docRef = FirebaseFirestore.getInstance()
                .collection("policies")
                .document(uid)
                .collection("approvals")
                .document(approval.packageName)

            val resolvedAppName = approval.appName.takeIf { it.isNotBlank() && it != approval.packageName }
                ?: appLabelResolver(approval.packageName)
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
            if (e is CancellationException) throw e
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
                    // Firestore only denies the write when the authoritative server rules forbid it
                    // (e.g., Guardian rejected/blacklisted or revoked self-control). Reconcile strictly.
                    Log.w(TAG, "Reconciling local state for $packageName to cloud REJECTED (decidedBy=$cloudDecidedBy) after PERMISSION_DENIED")
                    onRecordRejection(packageName, cloudDecidedBy, false)
                }
                PendingApprovalEntity.STATUS_APPROVED -> {
                    val isGuardianDecision = !cloudDecidedBy.isNullOrBlank() &&
                        !cloudDecidedBy.equals("SELF", ignoreCase = true)
                    if (isGuardianDecision) {
                        Log.w(TAG, "Reconciling local state for $packageName to cloud guardian APPROVED (decidedBy=$cloudDecidedBy) after PERMISSION_DENIED")
                        onUnsuspendAndApprove(packageName, cloudDecidedBy, false, false)
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
            if (reconcileError is CancellationException) throw reconcileError
            Log.w(TAG, "Failed reconciling cloud document for $packageName after PERMISSION_DENIED: ${reconcileError.message}")
        }
    }

    fun startApprovalsSync(wardUid: String) {
        val gen = listenerGeneration.incrementAndGet()
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
                            snapshotMutex.withLock {
                                processSnapshotsLocked(gen, snapshots.documents)
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

    private fun processSnapshotsLocked(
        gen: Long,
        documents: List<com.google.firebase.firestore.DocumentSnapshot>
    ) {
        if (gen != listenerGeneration.get()) return
        try {
            val isBlockActive = CurfewEnforcer.isEffectiveBlockActive(appContext)
            val policy = policyProvider()
            val effectiveAllowed = if (isBlockActive) {
                CurfewAppResolver.resolveImmunePackages(appContext)
            } else {
                emptySet()
            }

            val pendingMap = approvalDao.getPendingApprovalsBlocking().associateBy { it.packageName }
            for (doc in documents) {
                if (gen != listenerGeneration.get()) return
                val packageName = doc.getString("packageName") ?: doc.id
                val status = doc.getString("status") ?: continue
                val decidedBy = doc.getString("decidedBy")
                val cloudDecidedAt = doc.getLong("decidedAt") ?: 0L
                val local = pendingMap[packageName]

                if (status == PendingApprovalEntity.STATUS_APPROVED) {
                    val isNewerPendingLocalSelfRejection = local != null &&
                        !local.isCloudSynced &&
                        local.isRejected &&
                        local.decidedBy.equals("SELF", ignoreCase = true) &&
                        (local.decidedAt ?: 0L) > cloudDecidedAt
                    if (isNewerPendingLocalSelfRejection) {
                        Log.i(TAG, "Skipping stale cloud APPROVED snapshot for $packageName (unsynced local SELF rejection is newer)")
                        continue
                    }

                    val isCurfewDeferred = isBlockActive && !effectiveAllowed.contains(packageName)
                    if (local != null && local.isApproved) {
                        if (isCurfewDeferred) continue
                        val isPhysicallySuspended = com.drmohammed.antiporn.telemetry.SafeDpmGateway
                            .isPackageSuspendedBlocking(appContext, packageName)
                            .getOrNull() == true
                        if (!isPhysicallySuspended) continue
                    }

                    Log.i(TAG, "Real-time approval received for $packageName from $decidedBy (localApproved=${local?.isApproved}, curfewDeferred=$isCurfewDeferred)")
                    onUnsuspendAndApprove(packageName, decidedBy, false, false)
                } else if (status == PendingApprovalEntity.STATUS_REJECTED) {
                    val isCloudSelfRejection = decidedBy.equals("SELF", ignoreCase = true)
                    val hasSelfApprovalAccess = !policy.isSupervised ||
                        policy.guardianUid.isNullOrBlank() ||
                        (policy.isSelfControlAllowed && (policy.selfControlAppsAllowed || policy.selfControlNewAppsAllowed))
                    val isNewerPendingLocalSelfApproval = local != null &&
                        !local.isCloudSynced &&
                        local.isApproved &&
                        local.decidedBy.equals("SELF", ignoreCase = true) &&
                        isCloudSelfRejection &&
                        hasSelfApprovalAccess &&
                        (local.decidedAt ?: 0L) > cloudDecidedAt
                    if (isNewerPendingLocalSelfApproval) {
                        Log.i(TAG, "Skipping stale cloud REJECTED snapshot for $packageName (unsynced local SELF approval is newer)")
                    } else if (local == null || !local.isRejected) {
                        Log.i(TAG, "Real-time rejection received for $packageName from $decidedBy")
                        onRecordRejection(packageName, decidedBy, false)
                    }
                }
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.e(TAG, "Error handling approval snapshot update", e)
        }
    }

    fun stopApprovalsSync() {
        listenerGeneration.incrementAndGet()
        approvalsListener?.remove()
        approvalsListener = null
        Log.i(TAG, "Stopped approvals sync listener")
    }

    suspend fun resolveWardApproval(
        wardUid: String,
        packageName: String,
        approve: Boolean,
        guardianName: String
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val firestore = FirebaseFirestore.getInstance()
            val newStatus = if (approve) PendingApprovalEntity.STATUS_APPROVED else PendingApprovalEntity.STATUS_REJECTED
            val now = currentTimeMillisProvider()
            val batch = firestore.batch()

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
            if (e is CancellationException) throw e
            Log.e(TAG, "Failed resolving ward approval for $packageName", e)
            false
        }
    }

    private fun resolveUid(): String =
        FirebaseAuth.getInstance().currentUser?.uid
            ?: AccountabilityRepository.getInstance(appContext).getCachedUid()

    companion object {
        private const val TAG = "AntiPorn_ApprovalSync"
    }
}
