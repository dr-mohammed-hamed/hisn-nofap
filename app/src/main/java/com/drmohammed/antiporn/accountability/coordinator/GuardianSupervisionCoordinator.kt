package com.drmohammed.antiporn.accountability.coordinator

import android.content.Context
import android.util.Log
import androidx.annotation.VisibleForTesting
import com.drmohammed.antiporn.R
import com.drmohammed.antiporn.core.time.TrustedTimeProvider
import com.drmohammed.antiporn.data.local.db.entity.PartnerEntity
import com.drmohammed.antiporn.data.repository.AccountabilityRepository
import com.drmohammed.antiporn.data.repository.completeGuardianSideTeardownCleanup
import com.drmohammed.antiporn.data.repository.isPendingGuardianTeardown
import com.drmohammed.antiporn.notification.manager.AppNotificationManager
import com.drmohammed.antiporn.notification.model.NotificationActionType
import com.drmohammed.antiporn.notification.model.NotificationCategory
import com.drmohammed.antiporn.notification.registry.NotificationRegistry
import com.drmohammed.antiporn.notification.repository.NotificationRepository
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentChange
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.ListenerRegistration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.absoluteValue

/**
 * Centralized, lifecycle-aware real-time coordinator for guardian supervision.
 *
 * Responsibilities:
 * 1. Dynamically observes active supervised wards from [AccountabilityRepository].
 * 2. Maintains persistent Firestore snapshot listeners on:
 *    - `/policies/{wardUid}/approvals` (Zero-Trust app installation and unfreeze requests)
 *    - `/policies/{wardUid}` (24h emergency breakout, 48h DO teardown, A11y tampering, self-control changes)
 * 3. Dispatches high-priority system status bar notifications and in-app event history
 *    via [AppNotificationManager] so the guardian is instantly alerted on both the Android status
 *    bar and the in-app notification center bell icon.
 * 4. Automatically cancels obsolete system notifications and updates in-app records when
 *    requests are resolved (approved/rejected) or cancelled.
 */
class GuardianSupervisionCoordinator(
    context: Context,
    private val accountabilityRepository: AccountabilityRepository,
    private val notificationManager: AppNotificationManager,
    private val notificationRepository: NotificationRepository
) {

    @VisibleForTesting
    var firestoreProvider: () -> FirebaseFirestore = { FirebaseFirestore.getInstance() }

    @VisibleForTesting
    var coordinatorScope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val appContext = context.applicationContext

    private val listenersMutex = Mutex()
    private val approvalListeners = ConcurrentHashMap<String, ListenerRegistration>()
    private val policyListeners = ConcurrentHashMap<String, ListenerRegistration>()
    private val cleaningUpWards = ConcurrentHashMap.newKeySet<String>()
    private val wardSnapshotMutexes = ConcurrentHashMap<String, Mutex>()

    private var wardObservationJob: Job? = null

    @Volatile
    private var isSupervising = false

    /**
     * Starts listening to wards flow and attaches real-time snapshot listeners
     * for all currently active supervised wards.
     */
    fun startSupervising() {
        synchronized(this) {
            if (isSupervising) {
                Log.d(TAG, "GuardianSupervisionCoordinator is already active")
                return
            }
            isSupervising = true
        }
        Log.i(TAG, "Starting GuardianSupervisionCoordinator active observation")

        wardObservationJob?.cancel()
        wardObservationJob = coordinatorScope.launch {
            try {
                accountabilityRepository.wardsFlow.collectLatest { wards ->
                    reconcileWardListeners(wards)
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.e(TAG, "Error in wardsFlow observation loop", e)
            }
        }
    }

    /**
     * Stops all active Firestore listeners and halts supervision coordination.
     */
    fun stopSupervising() {
        synchronized(this) {
            isSupervising = false
        }
        wardObservationJob?.cancel()
        wardObservationJob = null

        coordinatorScope.launch {
            detachAllListeners()
            Log.i(TAG, "Stopped GuardianSupervisionCoordinator")
        }
    }

    /**
     * Synchronously checks and reconciles snapshot listeners for the given list of wards.
     */
    suspend fun reconcileWardListeners(wards: List<PartnerEntity>) = listenersMutex.withLock {
        if (!isSupervising) {
            Log.d(TAG, "Supervision is stopped; skipping reconciliation")
            return@withLock
        }
        val activeWards = wards.filter { it.status == PartnerEntity.STATUS_ACTIVE }
        val activeWardUids = mutableSetOf<String>()

        for (ward in activeWards) {
            val canonicalUid = accountabilityRepository.resolveActivePartnerUid(ward.id, ward.pairingCode)
            if (canonicalUid.isNotBlank()) {
                if (cleaningUpWards.contains(canonicalUid)) continue
                activeWardUids.add(canonicalUid)
                val displayName = ward.displayName.ifBlank {
                    appContext.getString(R.string.status_ward_badge)
                }
                attachWardApprovalListener(canonicalUid, displayName)
                attachWardPolicyListener(canonicalUid, displayName)
            }
        }

        // Prune listeners for wards that are no longer active or have been removed
        val approvalIterator = approvalListeners.entries.iterator()
        while (approvalIterator.hasNext()) {
            val entry = approvalIterator.next()
            if (!activeWardUids.contains(entry.key)) {
                Log.d(
                    TAG,
                    "Detaching approval listener for inactive ward: ${com.drmohammed.antiporn.telemetry.AiTelemetryReporter.maskIdentifier(entry.key)}"
                )
                entry.value.remove()
                approvalIterator.remove()
            }
        }

        val policyIterator = policyListeners.entries.iterator()
        while (policyIterator.hasNext()) {
            val entry = policyIterator.next()
            if (!activeWardUids.contains(entry.key)) {
                Log.d(
                    TAG,
                    "Detaching policy listener for inactive ward: ${com.drmohammed.antiporn.telemetry.AiTelemetryReporter.maskIdentifier(entry.key)}"
                )
                entry.value.remove()
                policyIterator.remove()
                wardSnapshotMutexes.remove(entry.key)
            }
        }
    }

    private fun attachWardApprovalListener(wardUid: String, wardName: String) {
        if (approvalListeners.containsKey(wardUid)) return
        val maskedUid = com.drmohammed.antiporn.telemetry.AiTelemetryReporter.maskIdentifier(wardUid)
        val maskedName = com.drmohammed.antiporn.telemetry.AiTelemetryReporter.maskIdentifier(wardName)

        try {
            val firestore = firestoreProvider()
            val registration = firestore.collection("policies").document(wardUid)
                .collection("approvals")
                .addSnapshotListener { snapshot, error ->
                    if (error != null) {
                        Log.w(TAG, "Error in approvals snapshot listener for ward $maskedUid: ${error.message}")
                        return@addSnapshotListener
                    }
                    if (snapshot == null) return@addSnapshotListener

                    coordinatorScope.launch {
                        handleApprovalsSnapshot(wardUid, wardName, snapshot)
                    }
                }
            approvalListeners[wardUid] = registration
            Log.i(TAG, "Attached approvals snapshot listener for ward $maskedUid ($maskedName)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to attach approvals listener for ward $maskedUid", e)
        }
    }

    private fun attachWardPolicyListener(wardUid: String, wardName: String) {
        if (policyListeners.containsKey(wardUid)) return
        val maskedUid = com.drmohammed.antiporn.telemetry.AiTelemetryReporter.maskIdentifier(wardUid)
        val maskedName = com.drmohammed.antiporn.telemetry.AiTelemetryReporter.maskIdentifier(wardName)

        try {
            val firestore = firestoreProvider()
            val registration = firestore.collection("policies").document(wardUid)
                .addSnapshotListener { snapshot, error ->
                    if (error != null) {
                        if (error.code == FirebaseFirestoreException.Code.PERMISSION_DENIED &&
                            FirebaseAuth.getInstance().currentUser != null &&
                            accountabilityRepository.isPendingGuardianTeardown(wardUid)
                        ) {
                            launchTeardownCleanupForWard(wardUid, wardName)
                            return@addSnapshotListener
                        }
                        Log.w(TAG, "Error in policy snapshot listener for ward $maskedUid: ${error.message}")
                        return@addSnapshotListener
                    }
                    if (snapshot == null || !snapshot.exists()) return@addSnapshotListener

                    coordinatorScope.launch {
                        val wardMutex = wardSnapshotMutexes.computeIfAbsent(wardUid) { Mutex() }
                        wardMutex.withLock {
                            handlePolicySnapshot(wardUid, wardName, snapshot)
                        }
                    }
                }
            policyListeners[wardUid] = registration
            Log.i(TAG, "Attached policy snapshot listener for ward $maskedUid ($maskedName)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to attach policy listener for ward $maskedUid", e)
        }
    }

    private fun launchTeardownCleanupForWard(wardUid: String, wardName: String) {
        if (!cleaningUpWards.add(wardUid)) return
        coordinatorScope.launch {
            try {
                executeTeardownCleanupForWard(wardUid, wardName)
            } finally {
                cleaningUpWards.remove(wardUid)
            }
        }
    }

    private suspend fun executeTeardownCleanupForWard(wardUid: String, wardName: String) {
        listenersMutex.withLock {
            policyListeners.remove(wardUid)?.remove()
            approvalListeners.remove(wardUid)?.remove()
            wardSnapshotMutexes.remove(wardUid)
        }
        accountabilityRepository.completeGuardianSideTeardownCleanup(wardUid, wardName)
    }

    /**
     * Processes changes in a ward's `/policies/{wardUid}/approvals` subcollection.
     */
    private suspend fun handleApprovalsSnapshot(
        wardUid: String,
        wardName: String,
        snapshot: com.google.firebase.firestore.QuerySnapshot
    ) {
        for (change in snapshot.documentChanges) {
            val doc = change.document
            val pkg = doc.getString("packageName") ?: doc.id
            if (pkg.isBlank()) continue

            val appName = doc.getString("appName") ?: pkg
            val status = doc.getString("status") ?: "PENDING"
            val dedupKey = "guardian_req_${wardUid}_$pkg"
            val systemNotifId = computeSystemNotificationId(wardUid, pkg)

            when (change.type) {
                DocumentChange.Type.ADDED,
                DocumentChange.Type.MODIFIED -> {
                    if (status.equals("PENDING", ignoreCase = true)) {
                        // 1. Post Android status bar notification and record in SQLite
                        val title = appContext.getString(R.string.notif_ward_approval_req_title)
                        val message = appContext.getString(R.string.notif_ward_approval_req_desc, "$appName ($wardName)")

                        notificationManager.postEventNotification(
                            category = NotificationCategory.APPROVALS,
                            title = title,
                            message = message,
                            dedupKey = dedupKey,
                            cooldownMs = 10 * 60 * 1000L,
                            actionType = NotificationActionType.OPEN_APPROVALS_HUB,
                            actionPayload = "$wardUid|$pkg",
                            systemNotificationId = systemNotifId,
                            channelId = NotificationRegistry.CHANNEL_ID_PROTECTION_EVENTS,
                            postSystemNotification = true,
                            importance = 2
                        )
                        Log.i(
                            TAG,
                            "Posted pending approval notification for $pkg (${com.drmohammed.antiporn.telemetry.AiTelemetryReporter.maskIdentifier(wardName)}) in status bar and in-app center"
                        )
                    } else {
                        // Request resolved (APPROVED or REJECTED)
                        // Cancel status bar alert
                        notificationManager.cancelSystemNotification(systemNotifId)

                        // Update in-app notification record in SQLite so history reflects resolution
                        updateInAppApprovalStatus(dedupKey, appName, wardName, status)
                    }
                }

                DocumentChange.Type.REMOVED -> {
                    // Application uninstalled by ward; dismiss active status bar notification
                    notificationManager.cancelSystemNotification(systemNotifId)
                    updateInAppApprovalStatus(dedupKey, appName, wardName, "REMOVED")
                }
            }
        }
    }

    /**
     * Updates existing InAppNotification record in Room SQLite when an approval is resolved or cancelled.
     */
    private suspend fun updateInAppApprovalStatus(
        dedupKey: String,
        appName: String,
        wardName: String,
        status: String
    ) {
        try {
            val existing = notificationRepository.getNotificationByDedupKey(dedupKey) ?: return
            val guardianName = accountabilityRepository.getMyDisplayName()
            val updatedTitle = if (status.equals("APPROVED", ignoreCase = true)) {
                appContext.getString(R.string.notif_approval_granted_title)
            } else if (status.equals("REJECTED", ignoreCase = true)) {
                appContext.getString(R.string.notif_approval_rejected_title)
            } else {
                "${existing.title} (${appContext.getString(R.string.tab_approvals_rejected)})"
            }

            val updatedMessage = if (status.equals("APPROVED", ignoreCase = true)) {
                appContext.getString(R.string.notif_approval_granted_desc, guardianName, "$appName ($wardName)")
            } else if (status.equals("REJECTED", ignoreCase = true)) {
                appContext.getString(R.string.notif_approval_rejected_desc, guardianName, "$appName ($wardName)")
            } else {
                existing.message
            }

            notificationRepository.saveNotification(
                existing.copy(
                    title = updatedTitle,
                    message = updatedMessage,
                    actionType = NotificationActionType.OPEN_APPROVALS_HUB
                ),
                cooldownMs = 0L
            )
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.w(TAG, "Failed updating in-app approval status for $dedupKey", e)
        }
    }

    /**
     * Processes changes in a ward's `/policies/{wardUid}` parent document.
     */
    private suspend fun handlePolicySnapshot(
        wardUid: String,
        wardName: String,
        snapshot: com.google.firebase.firestore.DocumentSnapshot
    ) {
        val now = TrustedTimeProvider.currentTimeMillis(appContext)

        // 1. Emergency Breakout Request (24h)
        val breakoutStatus = snapshot.getString("breakoutStatus")
        val breakoutUnlockAt = snapshot.getLong("breakoutUnlockAt") ?: 0L
        val breakoutNotifId = NotificationRegistry.EVENT_BREAKOUT_REQUEST_ID + ((wardUid.hashCode() and 0x7FFFFFFF) % 1000)

        if (breakoutStatus.equals("PENDING", ignoreCase = true) && breakoutUnlockAt > now) {
            val remainingMs = (breakoutUnlockAt - now).coerceAtLeast(0L)
            val hours = remainingMs / 3600000L
            val mins = (remainingMs % 3600000L) / 60000L
            val timeStr = String.format(Locale.US, "%02d:%02d", hours, mins)

            notificationManager.postEventNotification(
                category = NotificationCategory.GUARDIAN,
                title = appContext.getString(R.string.notif_breakout_alert_title),
                message = appContext.getString(R.string.notif_breakout_alert_desc, wardName, timeStr),
                dedupKey = "alert_breakout_${wardUid}_$breakoutUnlockAt",
                cooldownMs = 60 * 60 * 1000L,
                actionType = NotificationActionType.OPEN_GUARDIAN_PANEL,
                actionPayload = wardUid,
                systemNotificationId = breakoutNotifId,
                channelId = NotificationRegistry.CHANNEL_ID_PROTECTION_EVENTS,
                postSystemNotification = true,
                importance = 3
            )
        } else {
            notificationManager.cancelSystemNotification(breakoutNotifId)
        }

        // 2. Device Owner Deactivation Request (48h) vs Guardian Remote Teardown
        val deactivationStatus = snapshot.getString("deactivationStatus")
        val deactivationUnlockAt = snapshot.getLong("deactivationUnlockAt") ?: 0L
        val remoteTeardownAt = snapshot.getLong("remoteTeardownRequestedAt") ?: 0L
        val deactivationNotifId = NotificationRegistry.EVENT_GUARDIAN_REQUEST_ID + ((wardUid.hashCode() and 0x7FFFFFFF) % 1000)

        if (shouldHandleRemoteTeardownCompletion(deactivationStatus, accountabilityRepository.isPendingGuardianTeardown(wardUid))) {
            if (cleaningUpWards.add(wardUid)) {
                try {
                    executeTeardownCleanupForWard(wardUid, wardName)
                    notificationManager.cancelSystemNotification(deactivationNotifId)
                } finally {
                    cleaningUpWards.remove(wardUid)
                }
            } else {
                notificationManager.cancelSystemNotification(deactivationNotifId)
            }
        } else if (isPendingGuardianDeactivationAlert(deactivationStatus, remoteTeardownAt, deactivationUnlockAt, now)) {
            val remainingMs = (deactivationUnlockAt - now).coerceAtLeast(0L)
            val hours = remainingMs / 3600000L
            val mins = (remainingMs % 3600000L) / 60000L
            val timeStr = String.format(Locale.US, "%02d:%02d", hours, mins)

            notificationManager.postEventNotification(
                category = NotificationCategory.GUARDIAN,
                title = appContext.getString(R.string.toast_guardian_deactivation_alert),
                message = appContext.getString(R.string.guardian_deactivation_alert_msg, wardName, timeStr),
                dedupKey = "alert_deactivation_${wardUid}_$deactivationUnlockAt",
                cooldownMs = 60 * 60 * 1000L,
                actionType = NotificationActionType.OPEN_GUARDIAN_PANEL,
                actionPayload = wardUid,
                systemNotificationId = deactivationNotifId,
                channelId = NotificationRegistry.CHANNEL_ID_PROTECTION_EVENTS,
                postSystemNotification = true,
                importance = 3
            )
        } else {
            notificationManager.cancelSystemNotification(deactivationNotifId)
        }

        // 3. Accessibility Tamper Alert
        val wardA11yTamperAt = snapshot.getLong("wardA11yTamperAt") ?: 0L
        val a11yNotifId = NotificationRegistry.EVENT_SYSTEM_TAMPER_ID + ((wardUid.hashCode() and 0x7FFFFFFF) % 1000)

        if (wardA11yTamperAt > 0L) {
            notificationManager.postEventNotification(
                category = NotificationCategory.GUARDIAN,
                title = appContext.getString(R.string.guardian_alert_a11y_tamper_title),
                message = "$wardName: ${appContext.getString(R.string.guardian_alert_a11y_tamper_msg)}",
                dedupKey = "alert_a11y_${wardUid}_$wardA11yTamperAt",
                cooldownMs = 60 * 60 * 1000L,
                actionType = NotificationActionType.OPEN_GUARDIAN_PANEL,
                actionPayload = wardUid,
                systemNotificationId = a11yNotifId,
                channelId = NotificationRegistry.CHANNEL_ID_CRITICAL_ALERTS,
                postSystemNotification = true,
                importance = 3
            )
        } else {
            notificationManager.cancelSystemNotification(a11yNotifId)
        }

        // 4. Ward Self-Control Policy Modifications
        val snapshotJson = snapshot.getString("guardianPolicySnapshot")
        val lastSelfModifiedAt = snapshot.getLong("lastSelfModifiedAt") ?: 0L
        val selfModNotifId = NotificationRegistry.EVENT_SELF_CONTROL_ALERT_ID + ((wardUid.hashCode() and 0x7FFFFFFF) % 1000)

        if (!snapshotJson.isNullOrBlank() && lastSelfModifiedAt > 0L) {
            val sdf = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())
            val formattedTime = sdf.format(Date(lastSelfModifiedAt))

            notificationManager.postEventNotification(
                category = NotificationCategory.GUARDIAN,
                title = appContext.getString(R.string.guardian_self_control_alert_title),
                message = appContext.getString(R.string.guardian_self_control_alert_msg, wardName, formattedTime),
                dedupKey = "alert_self_mod_${wardUid}_$lastSelfModifiedAt",
                cooldownMs = 2 * 60 * 60 * 1000L,
                actionType = NotificationActionType.OPEN_GUARDIAN_PANEL,
                actionPayload = wardUid,
                systemNotificationId = selfModNotifId,
                channelId = NotificationRegistry.CHANNEL_ID_PROTECTION_EVENTS,
                postSystemNotification = true,
                importance = 2
            )
        } else {
            // Cancel obsolete self-control alert when dismissed or reverted
            notificationManager.cancelSystemNotification(selfModNotifId)
        }
    }

    private suspend fun detachAllListeners() = listenersMutex.withLock {
        for (reg in approvalListeners.values) {
            try {
                reg.remove()
            } catch (e: Exception) {
                Log.w(TAG, "Failed removing approval listener during detach", e)
            }
        }
        approvalListeners.clear()

        for (reg in policyListeners.values) {
            try {
                reg.remove()
            } catch (e: Exception) {
                Log.w(TAG, "Failed removing policy listener during detach", e)
            }
        }
        policyListeners.clear()
        wardSnapshotMutexes.clear()
    }

    companion object {
        private const val TAG = "GuardianCoordinator"

        /**
         * Computes a deterministic, collision-resistant, strictly non-negative system notification ID
         * for a given ward and package approval ticket.
         */
        fun computeSystemNotificationId(wardUid: String, packageName: String): Int {
            val baseId = NotificationRegistry.EVENT_WARD_REQUEST_ID
            val offset = ((wardUid + "_" + packageName).hashCode() and 0x7FFFFFFF) % 1000
            return baseId + offset
        }

        internal fun shouldHandleRemoteTeardownCompletion(
            deactivationStatus: String?,
            isPendingGuardianTeardown: Boolean
        ): Boolean {
            return deactivationStatus.equals("COMPLETED", ignoreCase = true) && isPendingGuardianTeardown
        }

        internal fun isPendingGuardianDeactivationAlert(
            deactivationStatus: String?,
            remoteTeardownRequestedAt: Long,
            deactivationUnlockAt: Long,
            nowMs: Long
        ): Boolean {
            return deactivationStatus.equals("PENDING", ignoreCase = true) &&
                remoteTeardownRequestedAt <= 0L &&
                deactivationUnlockAt > nowMs
        }

        @Volatile
        private var instance: GuardianSupervisionCoordinator? = null

        fun getInstance(context: Context): GuardianSupervisionCoordinator {
            return instance ?: synchronized(this) {
                instance ?: try {
                    org.koin.core.context.GlobalContext.getOrNull()?.get<GuardianSupervisionCoordinator>()
                } catch (t: Throwable) {
                    null
                } ?: GuardianSupervisionCoordinator(
                    context = context.applicationContext,
                    accountabilityRepository = AccountabilityRepository.getInstance(context.applicationContext),
                    notificationManager = AppNotificationManager.getInstance(context.applicationContext),
                    notificationRepository = NotificationRepository.getInstance(context.applicationContext)
                ).also { instance = it }
            }
        }

        @VisibleForTesting
        fun setInstance(coordinator: GuardianSupervisionCoordinator?) {
            instance = coordinator
        }
    }
}
