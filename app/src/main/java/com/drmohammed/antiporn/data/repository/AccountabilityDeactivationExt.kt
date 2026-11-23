package com.drmohammed.antiporn.data.repository

import androidx.core.content.edit
import com.drmohammed.antiporn.R
import com.drmohammed.antiporn.cloud.CloudSyncManager
import com.drmohammed.antiporn.core.model.DevicePolicyBundle
import com.drmohammed.antiporn.core.time.TrustedTimeProvider
import com.drmohammed.antiporn.notification.manager.AppNotificationManager
import com.drmohammed.antiporn.notification.model.NotificationActionType
import com.drmohammed.antiporn.notification.model.NotificationCategory
import com.drmohammed.antiporn.notification.registry.NotificationRegistry
import com.drmohammed.antiporn.telemetry.AiTelemetryReporter
import com.drmohammed.antiporn.telemetry.BlackBoxRecorder
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import timber.log.Timber

private const val TAG = "AntiPorn_AccDeact"
private const val KEY_PENDING_GUARDIAN_TEARDOWN_PREFIX = "pending_guardian_teardown_"
private const val KEY_PENDING_GUARDIAN_TEARDOWN_GROUP_PREFIX = "pending_guardian_teardown_group_"
private const val KEY_TOMBSTONED_TEARDOWN_WARDS = "tombstoned_teardown_wards"
private val guardianTeardownCleanupMutex = Mutex()

/**
 * Thrown when cancelling a pending remote teardown detects that the ward already
 * completed Device Owner removal and supervision cleanup ran.
 */
class GuardianTeardownAlreadyCompletedException(message: String) : IllegalStateException(message)

/**
 * Requests Device Owner deactivation with 48h default delay, or 24h if QR code was verified.
 */
suspend fun AccountabilityRepository.requestDeviceOwnerDeactivation(hasQr: Boolean): Result<Long> = withContext(Dispatchers.IO) {
    try {
        TrustedTimeProvider.initialize(appContext)
        if (!TrustedTimeProvider.isTrusted()) {
            TrustedTimeProvider.syncNow(appContext, force = true)
        }
        if (!TrustedTimeProvider.isTrusted()) {
            return@withContext Result.failure(
                IllegalStateException(appContext.getString(R.string.error_trusted_time_required))
            )
        }
        val now = TrustedTimeProvider.currentTimeMillis(appContext)
        val delayMs = if (hasQr) DevicePolicyBundle.DEACTIVATION_QR_DELAY_MS else DevicePolicyBundle.DEACTIVATION_DEFAULT_DELAY_MS
        val unlockAt = now + delayMs

        val policyRepo = PolicyRepository.getInstance(appContext)
        val currentPolicy = policyRepo.getCurrentPolicy()
        if (currentPolicy.isSupervised && !currentPolicy.guardianUid.isNullOrBlank()) {
            val myUid = getOrCreateCurrentUid()
            val docRef = firestore.collection("policies").document(myUid)
            val updates = mapOf(
                "deactivationStatus" to DevicePolicyBundle.DEACTIVATION_STATUS_PENDING,
                "deactivationRequestedAt" to now,
                "deactivationUnlockAt" to unlockAt,
                "deactivationHasQr" to hasQr,
                "updatedAt" to now
            )
            docRef.update(updates).await()
            Timber.tag(TAG).i("Device Owner deactivation requested on cloud. Unlock at $unlockAt (hasQr=$hasQr)")
        }

        val updatedLocal = currentPolicy.copy(
            deactivationStatus = DevicePolicyBundle.DEACTIVATION_STATUS_PENDING,
            deactivationRequestedAt = now,
            deactivationUnlockAt = unlockAt,
            deactivationHasQr = hasQr,
            updatedAt = now
        )
        policyRepo.updateLocalPolicyWithoutEnforcing(updatedLocal)

        Result.success(unlockAt)
    } catch (e: Exception) {
        if (e is CancellationException) throw e
        Timber.tag(TAG).e(e, "Error requesting device owner deactivation")
        Result.failure(e)
    }
}

/**
 * Cancels an active Device Owner deactivation request by the user.
 */
suspend fun AccountabilityRepository.cancelDeviceOwnerDeactivation(): Result<Unit> = withContext(Dispatchers.IO) {
    try {
        val now = TrustedTimeProvider.currentTimeMillis(appContext)
        val policyRepo = PolicyRepository.getInstance(appContext)
        val currentPolicy = policyRepo.getCurrentPolicy()
        if (currentPolicy.isSupervised && !currentPolicy.guardianUid.isNullOrBlank()) {
            val myUid = getOrCreateCurrentUid()
            val docRef = firestore.collection("policies").document(myUid)
            val updates = mapOf(
                "deactivationStatus" to DevicePolicyBundle.DEACTIVATION_STATUS_CANCELLED,
                "deactivationRequestedAt" to null,
                "deactivationUnlockAt" to null,
                "deactivationHasQr" to false,
                "updatedAt" to now
            )
            docRef.update(updates).await()
            Timber.tag(TAG).i("Device Owner deactivation cancelled on cloud by ward")
        }

        val updatedLocal = currentPolicy.copy(
            deactivationStatus = DevicePolicyBundle.DEACTIVATION_STATUS_CANCELLED,
            deactivationRequestedAt = null,
            deactivationUnlockAt = null,
            deactivationHasQr = false,
            updatedAt = now
        )
        policyRepo.updateLocalPolicyWithoutEnforcing(updatedLocal)

        Result.success(Unit)
    } catch (e: Exception) {
        if (e is CancellationException) throw e
        Timber.tag(TAG).e(e, "Error cancelling device owner deactivation")
        Result.failure(e)
    }
}

/**
 * Rejects an active Device Owner deactivation request by the Guardian.
 */
suspend fun AccountabilityRepository.rejectDeviceOwnerDeactivation(wardUid: String): Result<Unit> = withContext(Dispatchers.IO) {
    if (wardUid.isBlank()) {
        return@withContext Result.failure(IllegalArgumentException("wardUid is blank"))
    }
    val maskedWard = AiTelemetryReporter.maskIdentifier(wardUid)
    try {
        val docRef = firestore.collection("policies").document(wardUid)
        val updates = mapOf(
            "deactivationStatus" to DevicePolicyBundle.DEACTIVATION_STATUS_REJECTED,
            "deactivationRequestedAt" to null,
            "deactivationUnlockAt" to null,
            "deactivationHasQr" to false,
            "updatedAt" to TrustedTimeProvider.currentTimeMillis(appContext)
        )
        docRef.update(updates).await()
        Timber.tag(TAG).i("Guardian rejected device owner deactivation for ward $maskedWard")
        Result.success(Unit)
    } catch (e: Exception) {
        if (e is CancellationException) throw e
        Timber.tag(TAG).e(e, "Error rejecting device owner deactivation for ward $maskedWard")
        Result.failure(e)
    }
}

/**
 * Checks whether the Guardian has a pending remote Device Owner removal command for [wardUid].
 */
fun AccountabilityRepository.isPendingGuardianTeardown(wardUid: String): Boolean {
    if (wardUid.isBlank()) return false
    return prefs.getLong(KEY_PENDING_GUARDIAN_TEARDOWN_PREFIX + wardUid, 0L) > 0L
}

/**
 * Clears the local pending remote teardown marker (and any stored alias group) for [wardUid].
 */
fun AccountabilityRepository.clearPendingGuardianTeardownMarker(wardUid: String) {
    if (wardUid.isBlank()) return
    val targets = expandPendingTeardownGroup(setOf(wardUid))
    updatePendingTeardownMarkers(targets, timestamp = null)
}

internal fun AccountabilityRepository.getTombstonedTeardownWards(): Set<String> {
    return prefs.getStringSet(KEY_TOMBSTONED_TEARDOWN_WARDS, emptySet())?.toSet() ?: emptySet()
}

internal fun AccountabilityRepository.removeTombstonedTeardownWards(uids: Collection<String>) {
    if (uids.isEmpty()) return
    val current = getTombstonedTeardownWards().toMutableSet()
    if (current.removeAll(uids.toSet())) {
        prefs.edit(commit = true) {
            if (current.isEmpty()) remove(KEY_TOMBSTONED_TEARDOWN_WARDS)
            else putStringSet(KEY_TOMBSTONED_TEARDOWN_WARDS, current)
        }
    }
}

private fun AccountabilityRepository.addTombstonedTeardownWards(uids: Collection<String>) {
    if (uids.isEmpty()) return
    val current = getTombstonedTeardownWards().toMutableSet()
    if (current.addAll(uids)) {
        prefs.edit(commit = true) {
            putStringSet(KEY_TOMBSTONED_TEARDOWN_WARDS, current)
        }
    }
}

private fun AccountabilityRepository.expandPendingTeardownGroup(seeds: Set<String>): Set<String> {
    val expanded = linkedSetOf<String>()
    for (uid in seeds) {
        if (uid.isBlank()) continue
        expanded.add(uid)
        val groupCsv = prefs.getString(KEY_PENDING_GUARDIAN_TEARDOWN_GROUP_PREFIX + uid, null)
        if (!groupCsv.isNullOrBlank()) {
            groupCsv.split(',').map { it.trim() }.filter { it.isNotBlank() }.forEach { expanded.add(it) }
        }
    }
    return expanded
}

private fun AccountabilityRepository.updatePendingTeardownMarkers(
    targets: Set<String>,
    timestamp: Long?,
    commit: Boolean = false
) {
    val groupCsv = targets.filter { it.isNotBlank() }.joinToString(",")
    prefs.edit(commit = commit) {
        for (uid in targets) {
            if (uid.isBlank()) continue
            val key = KEY_PENDING_GUARDIAN_TEARDOWN_PREFIX + uid
            val groupKey = KEY_PENDING_GUARDIAN_TEARDOWN_GROUP_PREFIX + uid
            if (timestamp != null) {
                putLong(key, timestamp)
                if (groupCsv.isNotBlank()) putString(groupKey, groupCsv)
            } else {
                remove(key)
                remove(groupKey)
            }
        }
    }
}

/**
 * Dispatches an authoritative Guardian command to immediately strip Device Owner
 * permissions on the supervised ward's phone without waiting 48 hours.
 * Simultaneously sets `deactivationUnlockAt` in the past for backward compatibility.
 */
suspend fun AccountabilityRepository.requestGuardianImmediateTeardown(wardUid: String): Result<Long> =
    withContext(Dispatchers.IO) {
        if (wardUid.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("wardUid is blank"))
        }
        guardianTeardownCleanupMutex.withLock {
            var committedTargets: Set<String> = emptySet()
            var canonicalUpdated = false
            try {
                TrustedTimeProvider.initialize(appContext)
                if (!TrustedTimeProvider.isTrusted()) {
                    TrustedTimeProvider.syncNow(appContext, force = true)
                }
                if (!TrustedTimeProvider.isTrusted()) {
                    return@withLock Result.failure(
                        IllegalStateException(appContext.getString(R.string.error_trusted_time_required))
                    )
                }

                val now = TrustedTimeProvider.currentTimeMillis(appContext)
                val unlockAt = maxOf(1L, now - 60_000L)
                val partner = partnerDao.getPartnerByIdBlocking(wardUid)
                val resolvedActiveUid = resolveActivePartnerUid(wardUid, partner?.pairingCode)
                val canonicalUid = resolvedActiveUid.ifBlank { wardUid }
                val targetUids = expandPendingTeardownGroup(
                    linkedSetOf(wardUid).apply { if (resolvedActiveUid.isNotBlank()) add(resolvedActiveUid) }
                )

                removeTombstonedTeardownWards(targetUids)
                // Persist pending markers synchronously before Firestore write so ultra-fast ward cleanup cannot race ahead
                updatePendingTeardownMarkers(targetUids, timestamp = now, commit = true)
                committedTargets = targetUids

                var lastError: Throwable? = null
                for (targetUid in targetUids) {
                    try {
                        val docRef = firestore.collection("policies").document(targetUid)
                        val snap = docRef.get().await()
                        val currentVersion = snap.getLong("version") ?: 1L
                        val updates = mapOf(
                            "deactivationStatus" to DevicePolicyBundle.DEACTIVATION_STATUS_PENDING,
                            "deactivationRequestedAt" to now,
                            "deactivationUnlockAt" to unlockAt,
                            "deactivationHasQr" to false,
                            "remoteTeardownRequestedAt" to now,
                            "isInstantInternetKillEnabled" to false,
                            "version" to (currentVersion + 1L),
                            "updatedAt" to now
                        )
                        docRef.update(updates).await()
                        if (targetUid == canonicalUid) {
                            canonicalUpdated = true
                        }
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        lastError = e
                        Timber.tag(TAG).w(e, "Warning updating remote teardown for UID %s", AiTelemetryReporter.maskIdentifier(targetUid))
                    }
                }

                if (!canonicalUpdated) {
                    updatePendingTeardownMarkers(committedTargets, timestamp = null, commit = true)
                    return@withLock Result.failure(lastError ?: IllegalStateException("Failed dispatching remote teardown command"))
                }

                val maskedTargets = targetUids.joinToString(",") { AiTelemetryReporter.maskIdentifier(it) }
                BlackBoxRecorder.recordPairing(appContext, "GUARDIAN_IMMEDIATE_TEARDOWN_SENT", isSuccess = true, details = "targets=$maskedTargets, requestedAt=$now")
                Timber.tag(TAG).i("Dispatched guardian immediate teardown for targets=%s", maskedTargets)
                Result.success(now)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                if (!canonicalUpdated && committedTargets.isNotEmpty()) {
                    updatePendingTeardownMarkers(committedTargets, timestamp = null, commit = true)
                }
                Timber.tag(TAG).e(e, "Error dispatching guardian immediate teardown")
                Result.failure(e)
            }
        }
    }

/**
 * Cancels a previously queued Guardian immediate teardown command while the ward is still offline.
 */
suspend fun AccountabilityRepository.cancelGuardianImmediateTeardown(wardUid: String): Result<Unit> =
    withContext(Dispatchers.IO) {
        if (wardUid.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("wardUid is blank"))
        }
        guardianTeardownCleanupMutex.withLock {
            try {
                val now = TrustedTimeProvider.currentTimeMillis(appContext)
                val partner = partnerDao.getPartnerByIdBlocking(wardUid)
                val resolvedActiveUid = resolveActivePartnerUid(wardUid, partner?.pairingCode)
                val canonicalUid = resolvedActiveUid.ifBlank { wardUid }
                val targetUids = expandPendingTeardownGroup(
                    linkedSetOf(wardUid).apply { if (resolvedActiveUid.isNotBlank()) add(resolvedActiveUid) }
                )

                var canonicalCancelled = false
                var permissionDeniedError: FirebaseFirestoreException? = null
                var lastError: Throwable? = null
                for (targetUid in targetUids) {
                    try {
                        val docRef = firestore.collection("policies").document(targetUid)
                        val snap = docRef.get().await()
                        val currentVersion = snap.getLong("version") ?: 1L
                        val updates = mapOf(
                            "deactivationStatus" to DevicePolicyBundle.DEACTIVATION_STATUS_CANCELLED,
                            "deactivationRequestedAt" to null,
                            "deactivationUnlockAt" to null,
                            "deactivationHasQr" to false,
                            "remoteTeardownRequestedAt" to null,
                            "version" to (currentVersion + 1L),
                            "updatedAt" to now
                        )
                        docRef.update(updates).await()
                        if (targetUid == canonicalUid) {
                            canonicalCancelled = true
                        }
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        if (e is FirebaseFirestoreException && e.code == FirebaseFirestoreException.Code.PERMISSION_DENIED) {
                            permissionDeniedError = e
                        }
                        lastError = e
                        Timber.tag(TAG).w(
                            e,
                            "Warning cancelling remote teardown for UID %s",
                            AiTelemetryReporter.maskIdentifier(targetUid)
                        )
                    }
                }

                if (!canonicalCancelled) {
                    if (permissionDeniedError != null && targetUids.any { isPendingGuardianTeardown(it) }) {
                        completeGuardianSideTeardownCleanupLocked(wardUid, partner?.displayName.orEmpty())
                        return@withLock Result.failure(
                            GuardianTeardownAlreadyCompletedException(
                                appContext.getString(R.string.toast_guardian_remote_teardown_completed)
                            )
                        )
                    }
                    return@withLock Result.failure(
                        lastError ?: IllegalStateException("Failed cancelling remote teardown command")
                    )
                }

                updatePendingTeardownMarkers(targetUids, timestamp = null, commit = true)

                BlackBoxRecorder.recordPairing(
                    appContext,
                    "GUARDIAN_IMMEDIATE_TEARDOWN_CANCELLED",
                    isSuccess = true,
                    details = "ward=${AiTelemetryReporter.maskIdentifier(wardUid)}"
                )
                Result.success(Unit)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Timber.tag(TAG).e(e, "Error cancelling guardian immediate teardown")
                Result.failure(e)
            }
        }
    }

/**
 * Idempotently cleans up the Guardian's local/cloud partner record and posts a confirmation
 * notification once the ward's phone executes the immediate teardown and drops `guardianUid`.
 */
suspend fun AccountabilityRepository.completeGuardianSideTeardownCleanup(
    wardUid: String,
    wardName: String
): Boolean = withContext(Dispatchers.IO) {
    if (wardUid.isBlank()) return@withContext false
    guardianTeardownCleanupMutex.withLock {
        completeGuardianSideTeardownCleanupLocked(wardUid, wardName)
    }
}

private suspend fun AccountabilityRepository.completeGuardianSideTeardownCleanupLocked(
    wardUid: String,
    wardName: String
): Boolean {
    val partner = partnerDao.getPartnerByIdBlocking(wardUid)
    val resolvedActiveUid = resolveActivePartnerUid(wardUid, partner?.pairingCode)
    val targetUids = expandPendingTeardownGroup(
        linkedSetOf(wardUid).apply { if (resolvedActiveUid.isNotBlank()) add(resolvedActiveUid) }
    )
    val hasPendingMarker = targetUids.any { isPendingGuardianTeardown(it) }
    if (!hasPendingMarker) {
        return false
    }

    updatePendingTeardownMarkers(targetUids, timestamp = null, commit = true)

    val myUid = getOrCreateCurrentUid()
    val notifMgr = AppNotificationManager.getInstance(appContext)
    val failedCloudDeletes = mutableListOf<String>()
    for (targetUid in targetUids) {
        try {
            firestore.collection("users").document(myUid)
                .collection("partners").document(targetUid)
                .delete()
                .await()
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            failedCloudDeletes.add(targetUid)
            Timber.tag(TAG).w("Partner cloud delete warning on teardown completion: %s", e.message)
        }
        try {
            partnerDao.deletePartnerBlocking(targetUid)
        } catch (e: Exception) {
            Timber.tag(TAG).w("Partner local delete warning on teardown completion: %s", e.message)
        }
        val deactNotifId = NotificationRegistry.EVENT_GUARDIAN_REQUEST_ID + ((targetUid.hashCode() and 0x7FFFFFFF) % 1000)
        val breakoutNotifId = NotificationRegistry.EVENT_BREAKOUT_REQUEST_ID + ((targetUid.hashCode() and 0x7FFFFFFF) % 1000)
        notifMgr.cancelSystemNotification(deactNotifId)
        notifMgr.cancelSystemNotification(breakoutNotifId)
    }
    if (failedCloudDeletes.isNotEmpty()) {
        addTombstonedTeardownWards(failedCloudDeletes)
    }

    val resolvedName = wardName.ifBlank {
        partner?.displayName?.takeIf { it.isNotBlank() } ?: appContext.getString(R.string.status_ward_badge)
    }
    val dedupMinute = TrustedTimeProvider.currentTimeMillis(appContext) / 60_000L
    try {
        notifMgr.postEventNotification(
            category = NotificationCategory.GUARDIAN,
            title = appContext.getString(R.string.notif_guardian_remote_teardown_completed_title),
            message = appContext.getString(R.string.notif_guardian_remote_teardown_completed_msg, resolvedName),
            dedupKey = "guardian_teardown_done_${wardUid}_$dedupMinute",
            cooldownMs = 0L,
            actionType = NotificationActionType.NONE,
            actionPayload = null,
            systemNotificationId = NotificationRegistry.EVENT_GUARDIAN_TEARDOWN_COMPLETED_ID +
                ((wardUid.hashCode() and 0x7FFFFFFF) % 500),
            channelId = NotificationRegistry.CHANNEL_ID_PROTECTION_EVENTS,
            postSystemNotification = true,
            importance = 2
        )
    } catch (e: Exception) {
        Timber.tag(TAG).w(e, "Failed posting guardian teardown completion notification")
    }

    BlackBoxRecorder.recordPairing(
        appContext,
        "GUARDIAN_TEARDOWN_CONFIRMED",
        isSuccess = true,
        details = "ward=${AiTelemetryReporter.maskIdentifier(wardUid)}"
    )
    CloudSyncManager.checkAndAutoRollbackIfUnsupervised(appContext)
    return true
}
