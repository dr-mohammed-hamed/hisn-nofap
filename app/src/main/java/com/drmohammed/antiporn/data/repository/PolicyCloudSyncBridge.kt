package com.drmohammed.antiporn.data.repository

import android.content.Context
import android.util.Log
import com.drmohammed.antiporn.R
import com.drmohammed.antiporn.cloud.CloudSyncManager
import com.drmohammed.antiporn.cloud.fcm.AntiPornFirebaseMessagingService
import com.drmohammed.antiporn.core.engine.ProtectionTransitionGuard
import com.drmohammed.antiporn.core.model.DevicePolicyBundle
import com.drmohammed.antiporn.core.time.TrustedTimeProvider
import com.drmohammed.antiporn.data.local.db.dao.PartnerDao
import com.drmohammed.antiporn.data.local.db.entity.PartnerEntity
import com.drmohammed.antiporn.telemetry.AiTelemetryReporter
import com.drmohammed.antiporn.telemetry.BlackBoxRecorder
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import timber.log.Timber
import java.util.concurrent.atomic.AtomicLong

/**
 * Bridges bidirectional Firestore synchronization, monotonic snapshot reception,
 * and remote guardian policy patching for [PolicyRepository].
 */
internal class PolicyCloudSyncBridge(
    private val appContext: Context,
    private val partnerDao: PartnerDao,
    private val repositoryScope: CoroutineScope,
    private val currentPolicyProvider: () -> DevicePolicyBundle,
    private val remoteTeardownHandler: RemoteTeardownHandler,
    private val onApplyCloudPolicy: (policy: DevicePolicyBundle, forceSovereignOverride: Boolean) -> Boolean,
    private val isCloudSyncEnabledProvider: () -> Boolean = { CloudSyncManager.isCloudSyncEnabled(appContext) }
) {
    private var firestoreListener: ListenerRegistration? = null
    private val firestoreSyncMutex = Mutex()
    private val snapshotProcessingMutex = Mutex()
    private val listenerGeneration = AtomicLong(0L)

    @Volatile
    private var lastSyncedCloudVersion: Long = 0L

    @Volatile
    private var lastUnchangedLoggedSignature: String? = null

    fun startFirestoreSync() {
        if (!isCloudSyncEnabledProvider()) {
            Log.i(TAG, "Cloud sync is disabled; aborting startFirestoreSync (pure offline mode)")
            return
        }
        try {
            val auth = FirebaseAuth.getInstance()
            val user = auth.currentUser
            if (user == null) {
                auth.signInAnonymously()
                    .addOnSuccessListener { result ->
                        result.user?.uid?.let { uid ->
                            AccountabilityRepository.getInstance(appContext).persistUid(uid)
                            AntiPornFirebaseMessagingService.syncPersistedToken(appContext, uid)
                            attachPolicyListener(uid)
                            ApprovalRepository.getInstance(appContext).startApprovalsSync(uid)
                            repositoryScope.launch {
                                AccountabilityRepository.getInstance(appContext).syncPartnersFromCloud()
                                AccountabilityRepository.getInstance(appContext).checkAndSelfHealSupervisionStatus()
                            }
                        }
                    }
                    .addOnFailureListener { e ->
                        Log.w(TAG, "Failed anonymous auth for policy sync, falling back to persistent device UID: ${e.message}")
                        val fallbackUid = AccountabilityRepository.getInstance(appContext).getCachedUid()
                        attachPolicyListener(fallbackUid)
                        ApprovalRepository.getInstance(appContext).startApprovalsSync(fallbackUid)
                        repositoryScope.launch {
                            AccountabilityRepository.getInstance(appContext).checkAndSelfHealSupervisionStatus()
                        }
                    }
            } else {
                AccountabilityRepository.getInstance(appContext).persistUid(user.uid)
                AntiPornFirebaseMessagingService.syncPersistedToken(appContext, user.uid)
                attachPolicyListener(user.uid)
                ApprovalRepository.getInstance(appContext).startApprovalsSync(user.uid)
                repositoryScope.launch {
                    AccountabilityRepository.getInstance(appContext).syncPartnersFromCloud()
                    AccountabilityRepository.getInstance(appContext).checkAndSelfHealSupervisionStatus()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error initiating Firestore sync; continuing with offline local SQLite policy", e)
        }
    }

    fun stopFirestoreSync() {
        try {
            listenerGeneration.incrementAndGet()
            firestoreListener?.remove()
            firestoreListener = null
            lastUnchangedLoggedSignature = null
            ApprovalRepository.getInstance(appContext).stopApprovalsSync()
            Log.i(TAG, "Successfully stopped Firestore policy and approval sync listeners")
        } catch (e: Exception) {
            Log.w(TAG, "Error while stopping Firestore sync listeners: ${e.message}")
        }
    }

    private fun attachPolicyListener(uid: String) {
        val gen = listenerGeneration.incrementAndGet()
        firestoreListener?.remove()
        repositoryScope.launch {
            try {
                AccountabilityRepository.getInstance(appContext).updateHeartbeat()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.w(TAG, "Heartbeat error: ${e.message}")
            }
        }
        try {
            val firestore = FirebaseFirestore.getInstance()
            val maskedUid = AiTelemetryReporter.maskIdentifier(uid)
            Log.i(TAG, "[SelfControlDiag] attachPolicyListener: Attaching Firestore listener to /policies/$maskedUid")
            firestoreListener = firestore.collection("policies").document(uid)
                .addSnapshotListener { snapshot, error ->
                    if (error != null) {
                        Log.e(TAG, "[SelfControlDiag] Firestore Snapshot ERROR on /policies/$maskedUid: ${error.message} (code=${error.code})")
                        return@addSnapshotListener
                    }
                    val data = snapshot?.takeIf { it.exists() }?.data ?: return@addSnapshotListener
                    repositoryScope.launch {
                        snapshotProcessingMutex.withLock {
                            processSnapshotLocked(uid, maskedUid, gen, data)
                        }
                    }
                }
        } catch (e: Exception) {
            Log.e(TAG, "Error attaching Firestore policy listener", e)
        }
    }

    private suspend fun processSnapshotLocked(
        uid: String,
        maskedUid: String,
        gen: Long,
        data: Map<String, Any>
    ) {
        try {
            if (gen != listenerGeneration.get() ||
                ProtectionTransitionGuard.isTeardownInProgress ||
                !isCloudSyncEnabledProvider()
            ) {
                Timber.tag(TAG).d("Skipping queued snapshot (gen=%d, current=%d)", gen, listenerGeneration.get())
                return
            }

            val cloudPolicy = DevicePolicyBundle.fromMap(data)
            if (remoteTeardownHandler.checkAndExecutePendingGuardianTeardown(cloudPolicy)) {
                return
            }
            if (gen != listenerGeneration.get() ||
                ProtectionTransitionGuard.isTeardownInProgress ||
                !isCloudSyncEnabledProvider()
            ) {
                return
            }

            val current = currentPolicyProvider()
            // Preserve offline 1-tap local VPN fallback approval until cloud reflects a newer/equal version
            val preservedOfflineLocalFallback = current.isLocalVpnFallbackEnabled &&
                !cloudPolicy.isLocalVpnFallbackEnabled &&
                cloudPolicy.version < current.version
            val candidateCloudPolicy = if (preservedOfflineLocalFallback) {
                cloudPolicy.copy(isLocalVpnFallbackEnabled = true)
            } else {
                cloudPolicy
            }
            val isFromGuardian = candidateCloudPolicy.isSupervised && !candidateCloudPolicy.guardianUid.isNullOrBlank()
            val supervisoryChanged = isFromGuardian && hasSupervisoryDiff(candidateCloudPolicy, current)
            val statusChanged = candidateCloudPolicy.breakoutStatus != current.breakoutStatus ||
                candidateCloudPolicy.deactivationStatus != current.deactivationStatus
            val supervisionChanged = current.isSupervised != candidateCloudPolicy.isSupervised
            val isTimeNewer = candidateCloudPolicy.updatedAt > current.updatedAt
            val isVersionNewer = candidateCloudPolicy.version > current.version

            val maskedGuardianUid = AiTelemetryReporter.maskIdentifier(candidateCloudPolicy.guardianUid)
            Timber.tag(TAG).i(
                "[SelfControlDiag] Cloud policy received on ward /policies/%s: cloudPolicy(v%d, supervised=%b, allowed=%b, guardian='%s') vs local(v%d, supervised=%b, allowed=%b), isVersionNewer=%b, isTimeNewer=%b, supervisoryChanged=%b, statusChanged=%b",
                maskedUid,
                candidateCloudPolicy.version,
                candidateCloudPolicy.isSupervised,
                candidateCloudPolicy.isSelfControlAllowed,
                maskedGuardianUid,
                current.version,
                current.isSupervised,
                current.isSelfControlAllowed,
                isVersionNewer,
                isTimeNewer,
                supervisoryChanged,
                statusChanged
            )

            val isUnchanged = !(isVersionNewer || isTimeNewer || supervisoryChanged || statusChanged || supervisionChanged)
            if (isUnchanged) {
                if (preservedOfflineLocalFallback) {
                    syncPolicyToCloudAsync(uid, current)
                }
                val sig = "${candidateCloudPolicy.version}:${candidateCloudPolicy.updatedAt}:${candidateCloudPolicy.hashCode()}:${current.version}"
                if (lastUnchangedLoggedSignature == sig) {
                    return
                }
                lastUnchangedLoggedSignature = sig
            } else {
                lastUnchangedLoggedSignature = null
            }

            BlackBoxRecorder.recordCloudSync(
                appContext,
                "POLICY_RECEIVED",
                isSuccess = true,
                details = "uid=$maskedUid, v${candidateCloudPolicy.version}, isSupervised=${candidateCloudPolicy.isSupervised}, curfew=${candidateCloudPolicy.isCurfewEnabled}(${candidateCloudPolicy.curfewStartHour}:${candidateCloudPolicy.curfewStartMinute}), isVersionNewer=$isVersionNewer, isTimeNewer=$isTimeNewer"
            )

            if (isUnchanged) {
                return
            }

            val isTransitionToSupervised = !current.isSupervised && candidateCloudPolicy.isSupervised
            val effectiveCloudPolicy = reconcileTransitionRules(current, candidateCloudPolicy, isTransitionToSupervised)
            val forceOverride = isFromGuardian && (supervisoryChanged || statusChanged || supervisionChanged)
            val enforcedPolicy = if (forceOverride) {
                effectiveCloudPolicy.copy(version = maxOf(effectiveCloudPolicy.version, current.version + 1))
            } else {
                effectiveCloudPolicy
            }

            Timber.tag(TAG).i(
                "[SelfControlDiag] Sovereign cloud policy ACCEPTED! Enforcing update on device... (v%d, isSelfControlAllowed=%b, forceOverride=%b)",
                enforcedPolicy.version,
                enforcedPolicy.isSelfControlAllowed,
                forceOverride
            )
            val adopted = onApplyCloudPolicy(enforcedPolicy, forceOverride)
            val adoptedPolicy = currentPolicyProvider()
            if (adopted &&
                adoptedPolicy.version == enforcedPolicy.version &&
                adoptedPolicy.isSupervised &&
                !adoptedPolicy.guardianUid.isNullOrBlank()
            ) {
                saveDetectedGuardianPartner(adoptedPolicy, maskedGuardianUid)
            }

            if (adopted && isFromGuardian) {
                acknowledgeCloudPolicyAsync(uid, maskedUid, candidateCloudPolicy, enforcedPolicy, isTransitionToSupervised, effectiveCloudPolicy != candidateCloudPolicy || preservedOfflineLocalFallback)
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Timber.tag(TAG).e(e, "Error handling policy update from cloud")
        }
    }

    private fun hasSupervisoryDiff(cloudPolicy: DevicePolicyBundle, current: DevicePolicyBundle): Boolean {
        return cloudPolicy.isSelfControlAllowed != current.isSelfControlAllowed ||
            cloudPolicy.selfControlCurfewAllowed != current.selfControlCurfewAllowed ||
            cloudPolicy.selfControlWebAllowed != current.selfControlWebAllowed ||
            cloudPolicy.selfControlAppsAllowed != current.selfControlAppsAllowed ||
            cloudPolicy.selfControlWaqfaAllowed != current.selfControlWaqfaAllowed ||
            cloudPolicy.selfControlNewAppsAllowed != current.selfControlNewAppsAllowed ||
            cloudPolicy.isCurfewEnabled != current.isCurfewEnabled ||
            cloudPolicy.curfewStartHour != current.curfewStartHour ||
            cloudPolicy.curfewStartMinute != current.curfewStartMinute ||
            cloudPolicy.curfewEndHour != current.curfewEndHour ||
            cloudPolicy.curfewEndMinute != current.curfewEndMinute ||
            cloudPolicy.isWeekendCurfewEnabled != current.isWeekendCurfewEnabled ||
            cloudPolicy.weekendCurfewStartHour != current.weekendCurfewStartHour ||
            cloudPolicy.weekendCurfewStartMinute != current.weekendCurfewStartMinute ||
            cloudPolicy.weekendCurfewEndHour != current.weekendCurfewEndHour ||
            cloudPolicy.weekendCurfewEndMinute != current.weekendCurfewEndMinute ||
            cloudPolicy.isVpnBlackholeEnabled != current.isVpnBlackholeEnabled ||
            cloudPolicy.isInstantInternetKillEnabled != current.isInstantInternetKillEnabled ||
            cloudPolicy.isIncognitoBlocked != current.isIncognitoBlocked ||
            cloudPolicy.lockNewInstallsUntilApproved != current.lockNewInstallsUntilApproved ||
            cloudPolicy.isScreenAiEnabledGlobally != current.isScreenAiEnabledGlobally ||
            cloudPolicy.customBlacklistPackages != current.customBlacklistPackages ||
            cloudPolicy.customCurfewWhitelistPackages != current.customCurfewWhitelistPackages ||
            cloudPolicy.customAiScanPackages != current.customAiScanPackages ||
            cloudPolicy.excludedAiScanPackages != current.excludedAiScanPackages ||
            cloudPolicy.curfewVpnBypassPackages != current.curfewVpnBypassPackages ||
            cloudPolicy.protectedAppPackages != current.protectedAppPackages ||
            cloudPolicy.isDateTimeLocked != current.isDateTimeLocked ||
            cloudPolicy.isClearAppDataAllowed != current.isClearAppDataAllowed ||
            cloudPolicy.timeZoneId != current.timeZoneId ||
            cloudPolicy.dnsHost != current.dnsHost ||
            cloudPolicy.browserFilterMode != current.browserFilterMode ||
            cloudPolicy.isFullSystemFilteringEnabled != current.isFullSystemFilteringEnabled ||
            cloudPolicy.isYouTubeRestrictedModeEnabled != current.isYouTubeRestrictedModeEnabled ||
            cloudPolicy.isAdBlockingEnabled != current.isAdBlockingEnabled ||
            cloudPolicy.isLocalVpnFallbackEnabled != current.isLocalVpnFallbackEnabled ||
            cloudPolicy.isTelegramWebBlocked != current.isTelegramWebBlocked ||
            cloudPolicy.isBlockExternalVpnEnabled != current.isBlockExternalVpnEnabled ||
            cloudPolicy.customBlockedUrls != current.customBlockedUrls ||
            cloudPolicy.customAllowedUrls != current.customAllowedUrls ||
            cloudPolicy.guardianUid != current.guardianUid ||
            cloudPolicy.waqfaConfig != current.waqfaConfig ||
            cloudPolicy.pageShieldConfig != current.pageShieldConfig ||
            cloudPolicy.selfControlPageShieldAllowed != current.selfControlPageShieldAllowed ||
            cloudPolicy.selfControlFortressesAllowed != current.selfControlFortressesAllowed ||
            cloudPolicy.selfControlFortressUnlockAllowed != current.selfControlFortressUnlockAllowed ||
            cloudPolicy.selfControlOtherSettingsAllowed != current.selfControlOtherSettingsAllowed ||
            cloudPolicy.fortressesConfig != current.fortressesConfig ||
            cloudPolicy.remoteModeUnlockTarget != current.remoteModeUnlockTarget ||
            cloudPolicy.remoteModeUnlockRequestedAt != current.remoteModeUnlockRequestedAt ||
            cloudPolicy.remoteSelfControlResetRequestedAt != current.remoteSelfControlResetRequestedAt ||
            cloudPolicy.remoteTeardownRequestedAt != current.remoteTeardownRequestedAt
    }

    private fun reconcileTransitionRules(
        current: DevicePolicyBundle,
        cloudPolicy: DevicePolicyBundle,
        isTransitionToSupervised: Boolean
    ): DevicePolicyBundle {
        if (!isTransitionToSupervised) return cloudPolicy
        val preserveBlocked = cloudPolicy.customBlockedUrls.isEmpty() && current.customBlockedUrls.isNotEmpty()
        val preserveAllowed = cloudPolicy.customAllowedUrls.isEmpty() && current.customAllowedUrls.isNotEmpty()
        if (!preserveBlocked && !preserveAllowed) return cloudPolicy

        val restoredBlocked = if (preserveBlocked) current.customBlockedUrls else cloudPolicy.customBlockedUrls
        val restoredAllowed = if (preserveAllowed) current.customAllowedUrls else cloudPolicy.customAllowedUrls
        val restoredFilterMode = if (cloudPolicy.browserFilterMode == DevicePolicyBundle.BROWSER_FILTER_OFF && current.browserFilterMode != DevicePolicyBundle.BROWSER_FILTER_OFF) {
            current.browserFilterMode
        } else if (cloudPolicy.browserFilterMode == DevicePolicyBundle.BROWSER_FILTER_OFF && (restoredBlocked.isNotEmpty() || restoredAllowed.isNotEmpty())) {
            DevicePolicyBundle.BROWSER_FILTER_BLOCKLIST
        } else {
            cloudPolicy.browserFilterMode
        }
        Timber.tag(TAG).i("[PairingSync] Reconciled initial supervised policy: preserving %d blocked URLs and %d allowed URLs (mode=%d)", restoredBlocked.size, restoredAllowed.size, restoredFilterMode)
        return cloudPolicy.copy(
            customBlockedUrls = restoredBlocked,
            customAllowedUrls = restoredAllowed,
            browserFilterMode = restoredFilterMode
        )
    }

    private fun safeLog(priority: Int, message: String, throwable: Throwable? = null) {
        try {
            when (priority) {
                Log.DEBUG -> Log.d(TAG, message, throwable)
                Log.INFO -> Log.i(TAG, message, throwable)
                Log.WARN -> Log.w(TAG, message, throwable)
                else -> Log.e(TAG, message, throwable)
            }
        } catch (_: Throwable) {
            // JVM unit test fallback when android.util.Log is not mocked
        }
    }

    private fun saveDetectedGuardianPartner(adoptedPolicy: DevicePolicyBundle, maskedGuardianUid: String) {
        val guardianUid = adoptedPolicy.guardianUid ?: return
        safeLog(
            Log.INFO,
            "Guardian detected from cloud snapshot: ${AiTelemetryReporter.maskIdentifier(adoptedPolicy.guardianName)} ($maskedGuardianUid)"
        )
        partnerDao.getPartnersByRoleBlocking(PartnerEntity.ROLE_GUARDIAN)
            .filter { it.id != guardianUid }
            .forEach { partnerDao.deletePartnerByIdAndRoleBlocking(it.id, PartnerEntity.ROLE_GUARDIAN) }
        val existingGuardian = partnerDao.getPartnerByIdAndRoleBlocking(guardianUid, PartnerEntity.ROLE_GUARDIAN)
        val now = TrustedTimeProvider.currentTimeMillis(appContext)
        val displayName = existingGuardian?.displayName?.takeIf { it.isNotBlank() }
            ?: adoptedPolicy.guardianName?.takeIf { it.isNotBlank() }
            ?: runCatching { appContext.getString(R.string.guardian_default_name) }.getOrDefault("Guardian")
        val partner = PartnerEntity(
            id = guardianUid,
            pairingCode = AccountabilityRepository.CODE_LOCKED,
            displayName = displayName,
            role = PartnerEntity.ROLE_GUARDIAN,
            status = PartnerEntity.STATUS_ACTIVE,
            createdAt = existingGuardian?.createdAt ?: now,
            lastSyncAt = now
        )
        partnerDao.savePartnerBlocking(partner)
    }

    private fun acknowledgeCloudPolicyAsync(
        uid: String,
        maskedUid: String,
        cloudPolicy: DevicePolicyBundle,
        enforcedPolicy: DevicePolicyBundle,
        isTransitionToSupervised: Boolean,
        wasReconciled: Boolean
    ) {
        repositoryScope.launch {
            try {
                FirebaseFirestore.getInstance().collection("policies").document(uid).update(
                    mapOf(
                        "acknowledgedVersion" to cloudPolicy.version,
                        "lastAcknowledgedAt" to TrustedTimeProvider.currentTimeMillis(appContext)
                    )
                ).await()
                safeLog(Log.INFO, "[SelfControlDiag] Acknowledged policy v${cloudPolicy.version} back to Firestore /policies/$maskedUid")
                BlackBoxRecorder.recordCloudSync(
                    appContext,
                    "POLICY_ACKNOWLEDGED",
                    isSuccess = true,
                    details = "acknowledgedVersion=${cloudPolicy.version} on /policies/$maskedUid"
                )
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                safeLog(Log.WARN, "Failed writing policy acknowledgment: ${e.message}")
            }
            if (isTransitionToSupervised && wasReconciled) {
                try {
                    syncPolicyToCloudAsync(uid, enforcedPolicy)
                } catch (syncEx: Exception) {
                    if (syncEx is CancellationException) throw syncEx
                    Timber.tag(TAG).w(syncEx, "Initial transition cloud policy sync deferred: %s", syncEx.message)
                }
            }
        }
    }

    suspend fun pushPolicyToWard(wardUid: String, bundle: DevicePolicyBundle): Boolean {
        if (wardUid.isBlank()) {
            Timber.tag(TAG).e("Refusing pushPolicyToWard: wardUid is blank")
            return false
        }
        val maskedWardUid = AiTelemetryReporter.maskIdentifier(wardUid)
        val firestore = FirebaseFirestore.getInstance()
        val snap = try {
            firestore.collection("policies").document(wardUid).get().await()
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.w(TAG, "Failed reading current cloud policy version for wardUid=$maskedWardUid", e)
            null
        }
        val cloudVersion = snap?.getLong("version") ?: 0L
        val targetVersion = if (bundle.version > cloudVersion) bundle.version else cloudVersion + 1L
        val policyToPush = bundle.copy(
            version = targetVersion,
            updatedAt = TrustedTimeProvider.currentTimeMillis(appContext)
        )
        Log.i(TAG, "[SelfControlDiag] pushPolicyToWard: writing to Firestore /policies/$maskedWardUid: isSelfControlAllowed=${policyToPush.isSelfControlAllowed}, version=$targetVersion")
        BlackBoxRecorder.recordCloudSync(
            appContext,
            "PUSH_TO_WARD_INITIATED",
            isSuccess = true,
            details = "wardUid=$maskedWardUid, version=$targetVersion, cloudVersion=$cloudVersion, curfew=${policyToPush.isCurfewEnabled}(${policyToPush.curfewStartHour}:${policyToPush.curfewStartMinute})"
        )
        return try {
            firestore.collection("policies").document(wardUid)
                .set(policyToPush.toMap(), SetOptions.merge())
                .await()
            Log.i(TAG, "[SelfControlDiag] pushPolicyToWard SUCCESS for wardUid=$maskedWardUid (isSelfControlAllowed=${policyToPush.isSelfControlAllowed}, v$targetVersion)")
            BlackBoxRecorder.recordCloudSync(
                appContext,
                "PUSH_TO_WARD_SUCCESS",
                isSuccess = true,
                details = "wardUid=$maskedWardUid, version=$targetVersion"
            )
            true
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.e(TAG, "[SelfControlDiag] pushPolicyToWard FAILED for wardUid=$maskedWardUid: ${e.message}", e)
            BlackBoxRecorder.recordCloudSync(
                appContext,
                "PUSH_TO_WARD_FAILED",
                isSuccess = false,
                error = e.message,
                details = "wardUid=$maskedWardUid, version=$targetVersion"
            )
            false
        }
    }

    suspend fun mergeAndPushRemotePolicyPatch(
        wardUid: String,
        fallbackPolicy: DevicePolicyBundle,
        guardianUidFallback: String?,
        guardianNameFallback: String?,
        transform: (DevicePolicyBundle) -> DevicePolicyBundle
    ): DevicePolicyBundle? {
        if (wardUid.isBlank()) {
            Timber.tag(TAG).e("Refusing remote policy patch push: wardUid is blank")
            return null
        }
        val latestCloudPolicy = try {
            val snap = FirebaseFirestore.getInstance()
                .collection("policies")
                .document(wardUid)
                .get()
                .await()
            val data = snap?.data
            if (snap != null && snap.exists() && data != null) {
                DevicePolicyBundle.fromMap(data)
            } else {
                null
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Timber.tag(TAG).w(e, "Failed fetching latest cloud policy before remote patch push")
            null
        }
        if (latestCloudPolicy == null && !fallbackPolicy.isSupervised) {
            Timber.tag(TAG).e("Aborting remote patch push: latest cloud doc unavailable and fallbackPolicy is unsupervised")
            return null
        }
        val basePolicy = latestCloudPolicy ?: fallbackPolicy
        val resolvedGuardianUid = basePolicy.guardianUid?.takeIf { it.isNotBlank() }
            ?: fallbackPolicy.guardianUid?.takeIf { it.isNotBlank() }
            ?: guardianUidFallback?.takeIf { it.isNotBlank() }
        val resolvedGuardianName = basePolicy.guardianName?.takeIf { it.isNotBlank() }
            ?: fallbackPolicy.guardianName?.takeIf { it.isNotBlank() }
            ?: guardianNameFallback?.takeIf { it.isNotBlank() }
        val updatedPolicy = transform(basePolicy).copy(
            isSupervised = true,
            guardianUid = resolvedGuardianUid,
            guardianName = resolvedGuardianName,
            guardianPolicySnapshot = null,
            lastSelfModifiedAt = null
        )
        return if (pushPolicyToWard(wardUid, updatedPolicy)) updatedPolicy else null
    }

    internal fun currentListenerGeneration(): Long = listenerGeneration.get()

    internal fun advanceListenerGenerationForTesting(): Long = listenerGeneration.incrementAndGet()

    internal suspend fun processSnapshotForTesting(
        uid: String,
        gen: Long,
        data: Map<String, Any>
    ) {
        snapshotProcessingMutex.withLock {
            processSnapshotLocked(uid, AiTelemetryReporter.maskIdentifier(uid), gen, data)
        }
    }

    fun syncPolicyToCloudAsync(uid: String, bundle: DevicePolicyBundle): kotlinx.coroutines.Job? {
        if (uid.isBlank()) return null
        if (!isCloudSyncEnabledProvider()) {
            Timber.tag(TAG).d("Cloud sync is disabled; skipping syncPolicyToCloudAsync (offline mode)")
            return null
        }
        val maskedUid = AiTelemetryReporter.maskIdentifier(uid)
        return repositoryScope.launch {
            firestoreSyncMutex.withLock {
                try {
                    if (bundle.version >= lastSyncedCloudVersion) {
                        FirebaseFirestore.getInstance().collection("policies").document(uid)
                            .set(bundle.toMap(), SetOptions.merge())
                            .await()
                        lastSyncedCloudVersion = bundle.version
                        Timber.tag(TAG).i("Successfully synced policy v${bundle.version} to cloud ($maskedUid)")
                    } else {
                        Timber.tag(TAG).d("Skipping cloud sync for stale policy v${bundle.version} (already at v$lastSyncedCloudVersion)")
                    }
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    Timber.tag(TAG).w("Policy cloud sync deferred (offline or error): ${e.message}")
                }
            }
        }
    }

    companion object {
        private const val TAG = "AntiPorn_PolicyRepo"
    }
}
