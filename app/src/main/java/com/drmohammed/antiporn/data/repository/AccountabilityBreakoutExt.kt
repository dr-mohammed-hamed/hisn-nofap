package com.drmohammed.antiporn.data.repository

import android.content.Context
import android.util.Log
import com.drmohammed.antiporn.R
import com.drmohammed.antiporn.accessibility.HisnAccessibilityService
import com.drmohammed.antiporn.accessibility.control.AccessibilitySelfActivator
import com.drmohammed.antiporn.core.model.DevicePolicyBundle
import com.drmohammed.antiporn.core.time.TrustedTimeProvider
import com.drmohammed.antiporn.curfew.CurfewForegroundWatcher
import com.drmohammed.antiporn.telemetry.AiTelemetryReporter
import com.google.firebase.firestore.SetOptions
import com.google.firebase.firestore.Source
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

private const val TAG = "AntiPorn_AccBreakout"

/**
 * Collects the ward device's current runtime capability telemetry fields for Firestore sync.
 * Auto-clears [wardA11yTamperAt] back to 0L once Accessibility is active again.
 */
internal fun collectWardCapabilityFields(
    appContext: Context,
    now: Long,
    recordTamper: Boolean = false
): Map<String, Any> {
    val hasA11y = HisnAccessibilityService.instance != null ||
        AccessibilitySelfActivator.isServiceEnabledInSettings(appContext)
    val hasUsageStats = CurfewForegroundWatcher.hasUsageStatsPermission(appContext)
    val canWriteSecure = AccessibilitySelfActivator.canWriteSecureSettings(appContext)

    val fields = mutableMapOf<String, Any>(
        "wardHasAccessibilityService" to hasA11y,
        "wardHasUsageStats" to hasUsageStats,
        "wardCanWriteSecureSettings" to canWriteSecure,
        "wardCapabilitiesUpdatedAt" to now,
        "wardSdkInt" to android.os.Build.VERSION.SDK_INT
    )
    if (recordTamper) {
        fields["wardA11yTamperAt"] = now
    } else if (hasA11y) {
        fields["wardA11yTamperAt"] = 0L
    }
    return fields
}

/**
 * Updates the device's heartbeat timestamp and runtime capability telemetry in Firestore /policies/{myUid}.
 * Lightweight operation invoked periodically or on app foreground.
 */
internal suspend fun AccountabilityRepository.updateHeartbeatInternal(): Result<Unit> = withContext(Dispatchers.IO) {
    if (!isSupervised() || !com.drmohammed.antiporn.cloud.CloudSyncManager.isCloudSyncEnabled(appContext)) {
        Log.d(TAG, "Skipping heartbeat: device is not an active supervised ward or cloud sync is disabled")
        return@withContext Result.success(Unit)
    }
    capabilitiesSyncMutex.withLock {
        try {
            val myUid = getOrCreateCurrentUid()
            val now = TrustedTimeProvider.currentTimeMillis(appContext)
            val payload = HashMap<String, Any>(8).apply {
                put("lastHeartbeat", now)
                putAll(collectWardCapabilityFields(appContext, now, recordTamper = false))
            }
            firestore.collection("policies").document(myUid)
                .set(payload, SetOptions.merge())
                .await()
            Log.d(TAG, "Heartbeat and ward capabilities updated successfully at $now")
            Result.success(Unit)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.w(TAG, "Non-critical heartbeat update failed: ${e.message}")
            Result.failure(e)
        }
    }
}

/**
 * Syncs the ward device's capability flags (Accessibility, Usage Stats, WRITE_SECURE_SETTINGS)
 * and optional tamper timestamp to Firestore /policies/{myUid} using merge semantics.
 */
internal suspend fun AccountabilityRepository.syncWardCapabilitiesInternal(
    recordTamper: Boolean = false
): Result<Unit> = withContext(Dispatchers.IO) {
    capabilitiesSyncMutex.withLock {
        try {
            val myUid = getOrCreateCurrentUid()
            if (myUid.startsWith("dev_") && auth.currentUser == null) {
                Log.d(TAG, "Skipping ward capabilities sync: device not authenticated")
                return@withLock Result.success(Unit)
            }
            val now = TrustedTimeProvider.currentTimeMillis(appContext)
            val payload = collectWardCapabilityFields(appContext, now, recordTamper = recordTamper)
            firestore.collection("policies").document(myUid)
                .set(payload, SetOptions.merge())
                .await()
            Log.d(TAG, "Ward capabilities synced successfully at $now (recordTamper=$recordTamper)")
            Result.success(Unit)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.w(TAG, "Non-critical ward capabilities sync failed: ${e.message}")
            Result.failure(e)
        }
    }
}

/**
 * Initiates a 24-hour Emergency Breakout request for a supervised ward device.
 * All protection policies remain 100% active during the 24-hour grace period.
 */
internal suspend fun AccountabilityRepository.requestEmergencyBreakoutInternal(): Result<Long> = withContext(Dispatchers.IO) {
    try {
        val myUid = getOrCreateCurrentUid()
        val docRef = firestore.collection("policies").document(myUid)
        val snapshot = docRef.get(Source.SERVER).await()
        if (!snapshot.exists()) {
            return@withContext Result.failure(IllegalStateException("Policy document does not exist"))
        }

        val currentStatus = snapshot.getString("breakoutStatus")
        if (currentStatus == DevicePolicyBundle.BREAKOUT_STATUS_PENDING) {
            return@withContext Result.failure(IllegalStateException(appContext.getString(R.string.error_breakout_already_pending)))
        }

        TrustedTimeProvider.initialize(appContext)
        if (!TrustedTimeProvider.isTrusted()) {
            TrustedTimeProvider.syncNow(appContext)
        }
        if (!TrustedTimeProvider.isTrusted()) {
            return@withContext Result.failure(
                IllegalStateException(appContext.getString(R.string.error_trusted_time_required))
            )
        }

        val now = TrustedTimeProvider.currentTimeMillis(appContext)
        val unlockAt = now + DevicePolicyBundle.BREAKOUT_GRACE_PERIOD_MS
        val updates = mapOf(
            "breakoutStatus" to DevicePolicyBundle.BREAKOUT_STATUS_PENDING,
            "breakoutRequestedAt" to now,
            "breakoutUnlockAt" to unlockAt,
            "updatedAt" to now
        )
        docRef.update(updates).await()
        Log.i(TAG, "Emergency breakout requested. Unlock time set to $unlockAt")
        Result.success(unlockAt)
    } catch (e: Exception) {
        if (e is CancellationException) throw e
        Log.e(TAG, "Error requesting emergency breakout", e)
        Result.failure(e)
    }
}

/**
 * Cancels an active emergency breakout request by the ward.
 */
internal suspend fun AccountabilityRepository.cancelEmergencyBreakoutInternal(): Result<Unit> = withContext(Dispatchers.IO) {
    try {
        val myUid = getOrCreateCurrentUid()
        val docRef = firestore.collection("policies").document(myUid)
        val updates = mapOf(
            "breakoutStatus" to DevicePolicyBundle.BREAKOUT_STATUS_CANCELLED,
            "breakoutRequestedAt" to null,
            "breakoutUnlockAt" to null,
            "updatedAt" to TrustedTimeProvider.currentTimeMillis(appContext)
        )
        docRef.update(updates).await()
        Log.i(TAG, "Emergency breakout request cancelled by ward")
        Result.success(Unit)
    } catch (e: Exception) {
        if (e is CancellationException) throw e
        Log.e(TAG, "Error cancelling emergency breakout", e)
        Result.failure(e)
    }
}

/**
 * Rejects an emergency breakout request by the Guardian.
 */
internal suspend fun AccountabilityRepository.rejectEmergencyBreakoutInternal(wardUid: String): Result<Unit> = withContext(Dispatchers.IO) {
    val maskedWard = AiTelemetryReporter.maskIdentifier(wardUid)
    try {
        val docRef = firestore.collection("policies").document(wardUid)
        val updates = mapOf(
            "breakoutStatus" to DevicePolicyBundle.BREAKOUT_STATUS_REJECTED,
            "breakoutRequestedAt" to null,
            "breakoutUnlockAt" to null,
            "updatedAt" to TrustedTimeProvider.currentTimeMillis(appContext)
        )
        docRef.update(updates).await()
        Log.i(TAG, "Guardian rejected emergency breakout for ward $maskedWard")
        Result.success(Unit)
    } catch (e: Exception) {
        if (e is CancellationException) throw e
        Log.e(TAG, "Error rejecting emergency breakout for ward $maskedWard", e)
        Result.failure(e)
    }
}

/**
 * Executes the final emergency breakout release after 24 hours have elapsed.
 */
internal suspend fun AccountabilityRepository.executeEmergencyBreakoutInternal(): Result<Unit> = withContext(Dispatchers.IO) {
    try {
        val myUid = getOrCreateCurrentUid()
        val docRef = firestore.collection("policies").document(myUid)
        val snapshot = docRef.get(Source.SERVER).await()
        if (!snapshot.exists()) {
            return@withContext Result.failure(IllegalStateException("Policy document does not exist"))
        }

        val status = snapshot.getString("breakoutStatus")
        val unlockAt = snapshot.getLong("breakoutUnlockAt") ?: Long.MAX_VALUE

        if (!TrustedTimeProvider.isTrusted()) {
            TrustedTimeProvider.syncNow(appContext, force = true)
        }
        if (!TrustedTimeProvider.isTrusted()) {
            return@withContext Result.failure(IllegalStateException(appContext.getString(R.string.error_trusted_time_required)))
        }

        val now = TrustedTimeProvider.currentTimeMillis(appContext)
        if (status != DevicePolicyBundle.BREAKOUT_STATUS_PENDING || unlockAt == Long.MAX_VALUE || now < unlockAt) {
            return@withContext Result.failure(IllegalStateException(appContext.getString(R.string.error_breakout_not_ready)))
        }

        val currentVersion = snapshot.getLong("version") ?: 1L
        val existingPolicy = snapshot.data?.let { DevicePolicyBundle.fromMap(it) }
            ?: PolicyRepository.getInstance(appContext).getCurrentPolicy()
        val safeDefault = AccountabilityRepository.buildPostSupervisionPolicy(
            existingPolicy = existingPolicy,
            nextVersion = currentVersion + 1,
            now = now,
            breakoutStatus = DevicePolicyBundle.BREAKOUT_STATUS_COMPLETED
        )
        docRef.set(safeDefault.toMap(), SetOptions.merge()).await()
        PolicyRepository.getInstance(appContext).updateLocalPolicyAndEnforce(safeDefault)
        onSupervisionRevokedLocally()
        Log.i(TAG, "Successfully executed emergency breakout after 24 hours")
        Result.success(Unit)
    } catch (e: Exception) {
        if (e is CancellationException) throw e
        Log.e(TAG, "Error executing emergency breakout", e)
        Result.failure(e)
    }
}

/**
 * Reads the live policy of a ward to inspect heartbeat and breakout status.
 */
internal suspend fun AccountabilityRepository.fetchWardPolicyInternal(wardUid: String): DevicePolicyBundle? = withContext(Dispatchers.IO) {
    val maskedWard = AiTelemetryReporter.maskIdentifier(wardUid)
    try {
        val snapshot = firestore.collection("policies").document(wardUid).get().await()
        val data = snapshot.data
        if (snapshot.exists() && data != null) {
            DevicePolicyBundle.fromMap(data)
        } else null
    } catch (e: Exception) {
        if (e is CancellationException) throw e
        Log.w(TAG, "Failed fetching ward policy for $maskedWard: ${e.message}")
        null
    }
}
