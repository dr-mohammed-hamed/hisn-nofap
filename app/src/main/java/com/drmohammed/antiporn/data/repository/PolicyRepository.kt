package com.drmohammed.antiporn.data.repository

import android.content.Context
import androidx.annotation.VisibleForTesting
import androidx.core.content.edit
import timber.log.Timber
import com.drmohammed.antiporn.accessibility.control.AccessibilityPolicyCoordinator
import com.drmohammed.antiporn.cloud.CloudSyncManager
import com.drmohammed.antiporn.core.engine.DeviceOwnerTeardownManager
import com.drmohammed.antiporn.core.model.DevicePolicyBundle
import com.drmohammed.antiporn.core.policy.AppPolicyRegistry
import com.drmohammed.antiporn.core.time.TrustedTimeProvider
import com.drmohammed.antiporn.curfew.CurfewAppResolver
import com.drmohammed.antiporn.curfew.CurfewConfig
import com.drmohammed.antiporn.curfew.CurfewEnforcer
import com.drmohammed.antiporn.curfew.LegacyCurfewWhitelistMigrator
import com.drmohammed.antiporn.data.local.db.HisnRoomDatabase
import com.drmohammed.antiporn.data.local.db.dao.DevicePolicyDao
import com.drmohammed.antiporn.data.local.db.dao.PartnerDao
import com.drmohammed.antiporn.data.local.db.entity.DevicePolicyEntity
import com.drmohammed.antiporn.data.local.db.entity.PartnerEntity
import com.drmohammed.antiporn.pageshield.engine.PageShieldAccessibilityHook
import com.drmohammed.antiporn.telemetry.AiTelemetryReporter
import com.drmohammed.antiporn.telemetry.BlackBoxRecorder
import com.drmohammed.antiporn.ui.control.gate.SelfControlAccessGateRegistry
import com.drmohammed.antiporn.waqfa.engine.WaqfaSessionManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicLong

/**
 * Unified Single Source of Truth (SSOT) repository for device protection policies.
 * Delegates Firestore bidirectional sync to [PolicyCloudSyncBridge] and remote teardown
 * execution to [RemoteTeardownHandler].
 */
class PolicyRepository(
    context: Context,
    private val policyDao: DevicePolicyDao = HisnRoomDatabase.getInstance(context).devicePolicyDao(),
    private val partnerDao: PartnerDao = HisnRoomDatabase.getInstance(context).partnerDao()
) {

    private val appContext = context.applicationContext ?: context
    private val repositoryScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val policyStateLock = Any()

    private val _currentPolicyFlow: MutableStateFlow<DevicePolicyBundle>
    val currentPolicyFlow: StateFlow<DevicePolicyBundle>

    private val policyPersistMutex = Mutex()
    private val persistSequence = AtomicLong(0L)
    private val roomSaveChannel = Channel<Pair<Long, DevicePolicyBundle>>(Channel.UNLIMITED)
    private val enforcementHandler = PolicyEnforcementHandler(appContext, repositoryScope)
    private val remoteTeardownHandler = RemoteTeardownHandler(appContext)
    private val cloudSyncBridge = PolicyCloudSyncBridge(
        appContext = appContext,
        partnerDao = partnerDao,
        repositoryScope = repositoryScope,
        currentPolicyProvider = { _currentPolicyFlow.value },
        remoteTeardownHandler = remoteTeardownHandler,
        onApplyCloudPolicy = { policy, forceOverride ->
            updateLocalPolicyAndEnforce(policy, forceSovereignOverride = forceOverride)
        }
    )

    @Volatile
    var isInitialPolicyLoaded: Boolean = false
        private set

    private val initialPolicyReady = CompletableDeferred<Unit>()

    val isInitialReconciliationComplete: Boolean
        get() = initialPolicyReady.isCompleted

    init {
        val cachedPolicy = loadFastPolicyFromCache()
        val hasCachedPolicy = cachedPolicy != null
        isInitialPolicyLoaded = hasCachedPolicy
        val initialPolicy = cachedPolicy ?: DevicePolicyBundle.defaultPolicy()
        _currentPolicyFlow = MutableStateFlow(initialPolicy)
        currentPolicyFlow = _currentPolicyFlow.asStateFlow()
        PageShieldAccessibilityHook.getInstance().updateConfig(initialPolicy.pageShieldConfig)
        SelfControlAccessGateRegistry.isSelfControlAllowedWhenSupervised = initialPolicy.isSelfControlAllowed

        repositoryScope.launch {
            for ((seq, policyToSave) in roomSaveChannel) {
                persistPolicyBundle(seq, policyToSave)
            }
        }

        repositoryScope.launch {
            var didMigrateLegacyWhitelist = false
            var effectivePolicyAfterReconcile = initialPolicy
            try {
                val (reconciled, needsSave, migratedLegacy) = loadAndReconcilePolicy(initialPolicy, hasCachedPolicy)
                didMigrateLegacyWhitelist = migratedLegacy
                val adopted = synchronized(policyStateLock) {
                    val current = _currentPolicyFlow.value
                    val noConcurrentUpdate = current == initialPolicy
                    val shouldAdoptInitialRoomLoad = !hasCachedPolicy && noConcurrentUpdate
                    val isNewerOrEqualReconciled = reconciled != current &&
                        (shouldAdoptInitialRoomLoad ||
                            reconciled.version > current.version ||
                            (reconciled.version == current.version &&
                                (reconciled.updatedAt > current.updatedAt ||
                                    (noConcurrentUpdate && reconciled.updatedAt >= current.updatedAt))))
                    val didAdopt = if (isNewerOrEqualReconciled) {
                        _currentPolicyFlow.value = reconciled
                        PageShieldAccessibilityHook.getInstance().updateConfig(reconciled.pageShieldConfig)
                        SelfControlAccessGateRegistry.isSelfControlAllowedWhenSupervised = reconciled.isSelfControlAllowed
                        if (needsSave || !hasCachedPolicy) {
                            schedulePolicyPersistenceLocked(reconciled)
                        }
                        true
                    } else if (!hasCachedPolicy && reconciled == current) {
                        schedulePolicyPersistenceLocked(current)
                        true
                    } else {
                        false
                    }
                    isInitialPolicyLoaded = true
                    didAdopt
                }
                val effectivePolicy = _currentPolicyFlow.value
                effectivePolicyAfterReconcile = effectivePolicy
                if (adopted && (needsSave || !hasCachedPolicy)) {
                    saveFastPolicyCache(effectivePolicy, syncCommit = true)
                }
                if (adopted || effectivePolicy != initialPolicy) {
                    runCatching { PageShieldAccessibilityHook.getInstance().updateConfig(effectivePolicy.pageShieldConfig) }
                        .onFailure { Timber.tag(TAG).w(it, "Failed updating PageShield config after cold-start Room reconcile") }
                    runCatching { WaqfaSessionManager.getInstance(appContext).updateConfig(effectivePolicy.waqfaConfig) }
                        .onFailure { Timber.tag(TAG).w(it, "Failed updating Waqfa config after cold-start Room reconcile") }
                    runCatching { AccessibilityPolicyCoordinator.reconcile(appContext, effectivePolicy) }
                        .onFailure { Timber.tag(TAG).w(it, "Failed reconciling accessibility after cold-start Room reconcile") }
                }
                if (adopted) {
                    remoteTeardownHandler.checkAndApplyRemoteSelfControlReset(reconciled)
                    RemoteModeUnlockHandler.checkAndApplyRemoteModeUnlock(appContext, reconciled)
                }
            } finally {
                isInitialPolicyLoaded = true
                initialPolicyReady.complete(Unit)
            }

            if (didMigrateLegacyWhitelist) {
                runCatching {
                    CurfewAppResolver.invalidateCache()
                    if (CurfewEnforcer.isEffectiveBlockActive(appContext)) {
                        CurfewEnforcer.syncBlockState(appContext)
                    }
                }.onFailure {
                    Timber.tag(TAG).w(it, "Failed syncing curfew state after legacy whitelist migration")
                }

                if (CloudSyncManager.isCloudSyncEnabled(appContext) &&
                    (!effectivePolicyAfterReconcile.isSupervised ||
                        (effectivePolicyAfterReconcile.isSelfControlAllowed && effectivePolicyAfterReconcile.selfControlCurfewAllowed))
                ) {
                    cloudSyncBridge.syncPolicyToCloudAsync(
                        AccountabilityRepository.getInstance(appContext).getCachedUid(),
                        effectivePolicyAfterReconcile
                    )
                }
            }

            try {
                if (CloudSyncManager.isCloudSyncEnabled(appContext)) {
                    startFirestoreSync()
                } else {
                    safeLogInfo("Cloud sync is disabled; skipping startup Firestore sync (pure offline mode)")
                }
            } catch (t: Throwable) {
                safeLogWarn("Skipped startup cloud sync initialization: ${t.message}")
            }
        }
    }

    private fun safeLogInfo(message: String) {
        Timber.tag(TAG).i("%s", message)
    }

    private fun safeLogWarn(message: String) {
        Timber.tag(TAG).w("%s", message)
    }

    private fun loadFastPolicyFromCache(): DevicePolicyBundle? {
        try {
            val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val cachedJson = prefs.getString(KEY_CACHED_POLICY_JSON, null)
            if (!cachedJson.isNullOrBlank()) {
                val parsed = DevicePolicyBundle.fromJsonOrNull(cachedJson)
                if (parsed != null) {
                    Timber.tag(TAG).d("Fast synchronous policy loaded from SharedPreferences cache (version=%d)", parsed.version)
                    return parsed
                }
                Timber.tag(TAG).w("Corrupted fast policy cache JSON; awaiting asynchronous Room reconcile")
            }
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Failed reading fast policy cache from SharedPreferences")
        }
        return null
    }

    private fun saveFastPolicyCache(bundle: DevicePolicyBundle, syncCommit: Boolean = false) {
        try {
            val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val shouldCommit = syncCommit || android.os.Looper.myLooper() != android.os.Looper.getMainLooper()
            prefs.edit(commit = shouldCommit) {
                putString(KEY_CACHED_POLICY_JSON, bundle.toJson())
            }
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Failed saving policy to fast SharedPreferences cache")
        }
    }

    private fun schedulePolicyPersistenceLocked(bundle: DevicePolicyBundle) {
        val seq = persistSequence.incrementAndGet()
        val sendResult = roomSaveChannel.trySend(seq to bundle)
        if (!sendResult.isSuccess) {
            Timber.tag(TAG).w("Policy persistence channel unavailable; writing asynchronously on IO")
            repositoryScope.launch {
                persistPolicyBundle(seq, bundle)
            }
        }
    }

    private suspend fun persistPolicyBundle(seq: Long, bundle: DevicePolicyBundle) {
        policyPersistMutex.withLock {
            if (seq < persistSequence.get()) {
                Timber.tag(TAG).d("Skipping superseded disk persistence for policy v%d (seq=%d < %d)", bundle.version, seq, persistSequence.get())
                return@withLock
            }
            saveFastPolicyCache(bundle, syncCommit = true)
            try {
                policyDao.saveDevicePolicyBlocking(DevicePolicyEntity.fromBundle(bundle))
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Timber.tag(TAG).e(e, "Failed persisting device policy v%d to Room", bundle.version)
            }
        }
    }

    private data class ReconcileResult(
        val policy: DevicePolicyBundle,
        val needsSave: Boolean,
        val didMigrateLegacyWhitelist: Boolean
    )

    private suspend fun loadAndReconcilePolicy(
        initialPolicy: DevicePolicyBundle,
        hasCachedPolicy: Boolean
    ): ReconcileResult {
        return try {
            val roomBundle = policyDao.getDevicePolicyBlocking()?.toBundle()
            val hasPersistedPolicy = roomBundle != null || hasCachedPolicy
            val loaded = roomBundle ?: initialPolicy
            val hasGuardians = try {
                partnerDao.getPartnersByRoleBlocking(PartnerEntity.ROLE_GUARDIAN).isNotEmpty()
            } catch (e: Exception) {
                Timber.tag(TAG).w(e, "Failed checking guardians in SQLite during cold boot reconcile")
                false
            }
            if (loaded.isSupervised && !loaded.guardianUid.isNullOrBlank() && !hasGuardians) {
                try {
                    val guardian = PartnerEntity(
                        id = loaded.guardianUid,
                        pairingCode = AccountabilityRepository.CODE_LOCKED,
                        displayName = loaded.guardianName ?: appContext.getString(com.drmohammed.antiporn.R.string.guardian_default_name),
                        role = PartnerEntity.ROLE_GUARDIAN,
                        status = PartnerEntity.STATUS_ACTIVE,
                        createdAt = loaded.updatedAt,
                        lastSyncAt = loaded.updatedAt
                    )
                    partnerDao.savePartnerBlocking(guardian)
                    Timber.tag(TAG).i("Reconciled missing guardian in SQLite on cold boot: %s", AiTelemetryReporter.maskIdentifier(loaded.guardianName))
                } catch (e: Exception) {
                    Timber.tag(TAG).e(e, "Failed reconciling missing guardian in SQLite")
                }
            }

            var workingPolicy = loaded
            var needsSave = false
            if (!hasGuardians && workingPolicy.version == 1L && workingPolicy.isVpnBlackholeEnabled != CurfewConfig.DEFAULT_VPN_BLACKHOLE_ENABLED) {
                workingPolicy = workingPolicy.copy(isVpnBlackholeEnabled = CurfewConfig.DEFAULT_VPN_BLACKHOLE_ENABLED)
                needsSave = true
            }
            if (!hasGuardians &&
                !workingPolicy.isSupervised &&
                workingPolicy.guardianUid.isNullOrBlank() &&
                !workingPolicy.hasAllSelfControlPermissionsEnabled()
            ) {
                workingPolicy = workingPolicy.withAllSelfControlPermissionsEnabled()
                needsSave = true
            }
            var didMigrateLegacyWhitelist = false
            val migration = LegacyCurfewWhitelistMigrator.migrateIfNeeded(appContext, workingPolicy, hasPersistedPolicy)
            if (migration.addedPackages.isNotEmpty()) {
                workingPolicy = migration.policy
                needsSave = true
                didMigrateLegacyWhitelist = true
            }
            if (needsSave) {
                val reconciled = workingPolicy.copy(
                    updatedAt = maxOf(TrustedTimeProvider.currentTimeMillis(appContext), workingPolicy.updatedAt)
                )
                Timber.tag(TAG).i("Prepared standalone device policy reconciliation on cold boot (version=%d)", reconciled.version)
                ReconcileResult(reconciled, needsSave = true, didMigrateLegacyWhitelist = didMigrateLegacyWhitelist)
            } else {
                ReconcileResult(loaded, needsSave = false, didMigrateLegacyWhitelist = false)
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Timber.tag(TAG).e(e, "Error in loadAndReconcilePolicy")
            ReconcileResult(initialPolicy, needsSave = false, didMigrateLegacyWhitelist = false)
        }
    }

    suspend fun awaitInitialPolicy(): DevicePolicyBundle {
        initialPolicyReady.await()
        return _currentPolicyFlow.value
    }

    fun getCurrentPolicy(): DevicePolicyBundle = _currentPolicyFlow.value

    fun startFirestoreSync() = cloudSyncBridge.startFirestoreSync()

    fun stopFirestoreSync() = cloudSyncBridge.stopFirestoreSync()

    fun updateLocalPolicyWithoutEnforcing(bundle: DevicePolicyBundle) {
        synchronized(policyStateLock) {
            _currentPolicyFlow.value = bundle
            SelfControlAccessGateRegistry.isSelfControlAllowedWhenSupervised = bundle.isSelfControlAllowed
            schedulePolicyPersistenceLocked(bundle)
        }
    }

    /**
     * Updates policy locally without re-enforcing DPM shields and synchronously flushes
     * both FastCache and Room before returning, preventing auto-rollback races during teardown.
     */
    suspend fun updateLocalPolicyWithoutEnforcingAndFlush(bundle: DevicePolicyBundle) {
        val seq = synchronized(policyStateLock) {
            _currentPolicyFlow.value = bundle
            SelfControlAccessGateRegistry.isSelfControlAllowedWhenSupervised = bundle.isSelfControlAllowed
            persistSequence.incrementAndGet()
        }
        withContext(Dispatchers.IO) {
            persistPolicyBundle(seq, bundle)
        }
    }

    fun updateLocalPolicyAndEnforce(bundle: DevicePolicyBundle, forceSovereignOverride: Boolean = false): Boolean {
        var shouldTeardown = false
        val currentBeforeUpdate = synchronized(policyStateLock) {
            val current = _currentPolicyFlow.value
            Timber.tag(TAG).i(
                "[SelfControlDiag] updateLocalPolicyAndEnforce: v%d, isSupervised=%b, isSelfControlAllowed=%b, forceOverride=%b",
                bundle.version,
                bundle.isSupervised,
                bundle.isSelfControlAllowed,
                forceSovereignOverride
            )
            val isBreakoutStatusChange = bundle.breakoutStatus != current.breakoutStatus || bundle.deactivationStatus != current.deactivationStatus
            if (!forceSovereignOverride &&
                (bundle.version < current.version || (!isBreakoutStatusChange && bundle.version == current.version && bundle.updatedAt < current.updatedAt))
            ) {
                Timber.tag(TAG).w(
                    "Monotonic guard: rejected stale/out-of-order policy v%d@%d (current is v%d@%d)",
                    bundle.version,
                    bundle.updatedAt,
                    current.version,
                    current.updatedAt
                )
                return@synchronized null to current
            }
            _currentPolicyFlow.value = bundle
            SelfControlAccessGateRegistry.isSelfControlAllowedWhenSupervised = bundle.isSelfControlAllowed
            schedulePolicyPersistenceLocked(bundle)
            val lastProcessedTeardown = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getLong(KEY_LAST_PROCESSED_REMOTE_TEARDOWN_AT, 0L)
            shouldTeardown = shouldExecuteGuardianTeardown(bundle, lastProcessedTeardown)
            current to current
        }

        val previous = currentBeforeUpdate.first
        if (previous == null) {
            val current = currentBeforeUpdate.second
            BlackBoxRecorder.recordCloudSync(
                appContext,
                "POLICY_REJECTED_STALE",
                isSuccess = false,
                error = "Monotonic guard reject: incoming v${bundle.version}@${bundle.updatedAt} < current v${current.version}@${current.updatedAt}"
            )
            return false
        }
        if (shouldTeardown) {
            repositoryScope.launch {
                checkAndExecutePendingGuardianTeardown(bundle)
            }
        } else {
            RemoteModeUnlockHandler.checkAndApplyRemoteModeUnlock(appContext, bundle)
            enforcementHandler.enqueueApply(bundle, previous)
        }
        recordAndHandleSupervisionChange(bundle, previous)
        remoteTeardownHandler.checkAndApplyRemoteSelfControlReset(bundle)
        return true
    }

    private fun recordAndHandleSupervisionChange(bundle: DevicePolicyBundle, previous: DevicePolicyBundle) {
        BlackBoxRecorder.recordCloudSync(
            appContext,
            "POLICY_ENFORCED_LOCALLY",
            isSuccess = true,
            details = "v${bundle.version}, curfew=${bundle.isCurfewEnabled}(${bundle.curfewStartHour}:${bundle.curfewStartMinute}-${bundle.curfewEndHour}:${bundle.curfewEndMinute}), weekend=${bundle.isWeekendCurfewEnabled}(${bundle.weekendCurfewStartHour}:${bundle.weekendCurfewStartMinute}), vpn=${bundle.isVpnBlackholeEnabled}, dns=${bundle.dnsHost}, supervised=${bundle.isSupervised}, wlCount=${bundle.customCurfewWhitelistPackages.size}, vpnBypassCount=${bundle.curfewVpnBypassPackages.size}"
        )

        if (previous.isSupervised && !bundle.isSupervised) {
            Timber.tag(TAG).i("Supervision revocation detected in updateLocalPolicyAndEnforce! Restoring standalone mode...")
            repositoryScope.launch {
                try {
                    AccountabilityRepository.getInstance(appContext).onSupervisionRevokedLocally()
                    ApprovalRepository.getInstance(appContext).stopApprovalsSync()
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    Timber.tag(TAG).e(e, "Error cleaning up supervision state on revocation")
                }
            }
        }
    }

    @Deprecated("Legacy convenience method")
    fun updateSelfControlAllowed(allowed: Boolean) {
        val current = _currentPolicyFlow.value
        if (current.isSelfControlAllowed == allowed) return
        val updated = current.copy(
            isSelfControlAllowed = allowed,
            updatedAt = TrustedTimeProvider.currentTimeMillis(appContext)
        )
        updateLocalPolicyAndEnforce(updated)
    }

    fun applyPolicyToSystem(bundle: DevicePolicyBundle, previousPolicy: DevicePolicyBundle = _currentPolicyFlow.value) {
        enforcementHandler.enqueueApply(bundle, previousPolicy)
    }

    suspend fun pushPolicyToWard(wardUid: String, bundle: DevicePolicyBundle): Boolean =
        cloudSyncBridge.pushPolicyToWard(wardUid, bundle)

    suspend fun mergeAndPushRemotePolicyPatch(
        wardUid: String,
        fallbackPolicy: DevicePolicyBundle,
        guardianUidFallback: String?,
        guardianNameFallback: String?,
        transform: (DevicePolicyBundle) -> DevicePolicyBundle
    ): DevicePolicyBundle? = cloudSyncBridge.mergeAndPushRemotePolicyPatch(
        wardUid = wardUid,
        fallbackPolicy = fallbackPolicy,
        guardianUidFallback = guardianUidFallback,
        guardianNameFallback = guardianNameFallback,
        transform = transform
    )

    suspend fun saveAndEnforceSelfPolicy(bundle: DevicePolicyBundle): Boolean =
        mergeAndSaveSelfPolicyPatch { bundle } != null

    suspend fun mergeAndSaveSelfPolicyPatch(
        awaitCloudSync: Boolean = false,
        transform: (DevicePolicyBundle) -> DevicePolicyBundle
    ): DevicePolicyBundle? = withContext(Dispatchers.IO) {
        try {
            initialPolicyReady.await()
            val repoSupervised = try {
                AccountabilityRepository.getInstance(appContext).isSupervised()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Timber.tag(TAG).w(e, "Failed to query accountability supervision in mergeAndSaveSelfPolicyPatch")
                false
            }
            val acceptedPair = synchronized(policyStateLock) {
                val current = _currentPolicyFlow.value
                val bundle = transform(current)
                val isSupervised = current.isSupervised || bundle.isSupervised || repoSupervised
                Timber.tag(TAG).i(
                    "[SelfControlDiag] saveAndEnforceSelfPolicy called: isSupervised=%b, current.isSelfControlAllowed=%b, bundle.isSelfControlAllowed=%b",
                    isSupervised,
                    current.isSelfControlAllowed,
                    bundle.isSelfControlAllowed
                )

                if (isSupervised && (!current.isSelfControlAllowed || !bundle.isSelfControlAllowed)) {
                    null
                } else {
                    var sanitized = bundle.copy(
                        guardianUid = current.guardianUid,
                        guardianName = current.guardianName,
                        isSupervised = isSupervised,
                        isSelfControlAllowed = current.isSelfControlAllowed,
                        selfControlCurfewAllowed = current.selfControlCurfewAllowed,
                        selfControlWebAllowed = current.selfControlWebAllowed,
                        selfControlAppsAllowed = current.selfControlAppsAllowed,
                        selfControlWaqfaAllowed = current.selfControlWaqfaAllowed,
                        selfControlPageShieldAllowed = current.selfControlPageShieldAllowed,
                        selfControlNewAppsAllowed = current.selfControlNewAppsAllowed,
                        selfControlFortressesAllowed = current.selfControlFortressesAllowed,
                        selfControlFortressUnlockAllowed = current.selfControlFortressUnlockAllowed,
                        selfControlOtherSettingsAllowed = current.selfControlOtherSettingsAllowed,
                        remoteModeUnlockTarget = current.remoteModeUnlockTarget,
                        remoteModeUnlockRequestedAt = current.remoteModeUnlockRequestedAt
                    )
                    if (isSupervised) {
                        sanitized = sanitizeSupervisedSelfPolicy(sanitized, current)
                    }

                    val now = TrustedTimeProvider.currentTimeMillis(appContext)
                    val newPolicy = sanitized.copy(
                        fortressesConfig = sanitized.fortressesConfig.normalized(),
                        guardianPolicySnapshot = if (isSupervised) (current.guardianPolicySnapshot ?: current.toJson()) else null,
                        lastSelfModifiedAt = if (isSupervised) now else null,
                        version = current.version + 1,
                        updatedAt = now
                    )
                    _currentPolicyFlow.value = newPolicy
                    SelfControlAccessGateRegistry.isSelfControlAllowedWhenSupervised = newPolicy.isSelfControlAllowed
                    schedulePolicyPersistenceLocked(newPolicy)
                    enforcementHandler.enqueueApply(newPolicy, current)
                    current to newPolicy
                }
            }

            if (acceptedPair == null) {
                Timber.tag(TAG).w("[SelfControlDiag] saveAndEnforceSelfPolicy REJECTED: self-control is disabled by supervisor!")
                BlackBoxRecorder.recordGate(
                    appContext,
                    "SELF_CONTROL_SAVE_REJECTED",
                    isSuccess = false,
                    error = "Access denied: self-control is disabled by supervisor"
                )
                return@withContext null
            }

            val (previous, updated) = acceptedPair
            recordAndHandleSupervisionChange(updated, previous)
            BlackBoxRecorder.recordGate(
                appContext,
                "SELF_CONTROL_POLICY_SAVED",
                isSuccess = true,
                details = "v${updated.version}, curfew=${updated.isCurfewEnabled}(${updated.curfewStartHour}:${updated.curfewStartMinute}), wlCount=${updated.customCurfewWhitelistPackages.size}, vpnBypassCount=${updated.curfewVpnBypassPackages.size}"
            )
            val myUid = AccountabilityRepository.getInstance(appContext).getCachedUid()
            val syncJob = cloudSyncBridge.syncPolicyToCloudAsync(myUid, updated)
            if (awaitCloudSync) {
                syncJob?.join()
            }
            updated
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Timber.tag(TAG).e(e, "Error in mergeAndSaveSelfPolicyPatch")
            null
        }
    }

    /**
     * Approves local VPN DNS fallback on this device without weakening any existing
     * restriction, allowing supervised wards to grant 1-tap resilience consent offline.
     */
    suspend fun approveLocalVpnFallbackLocally(): DevicePolicyBundle? = withContext(Dispatchers.IO) {
        try {
            initialPolicyReady.await()
            var alreadyEnabled: DevicePolicyBundle? = null
            val acceptedPair = synchronized(policyStateLock) {
                val current = _currentPolicyFlow.value
                if (current.isLocalVpnFallbackEnabled) {
                    alreadyEnabled = current
                    null
                } else {
                    val newPolicy = current.copy(
                        isLocalVpnFallbackEnabled = true,
                        version = current.version + 1,
                        updatedAt = TrustedTimeProvider.currentTimeMillis(appContext)
                    )
                    _currentPolicyFlow.value = newPolicy
                    schedulePolicyPersistenceLocked(newPolicy)
                    enforcementHandler.enqueueApply(newPolicy, current)
                    current to newPolicy
                }
            }
            if (alreadyEnabled != null) return@withContext alreadyEnabled

            val (_, updated) = acceptedPair ?: return@withContext null
            BlackBoxRecorder.recordGate(
                appContext,
                "LOCAL_VPN_FALLBACK_APPROVED",
                isSuccess = true,
                details = "v${updated.version}, supervised=${updated.isSupervised}"
            )
            val myUid = AccountabilityRepository.getInstance(appContext).getCachedUid()
            cloudSyncBridge.syncPolicyToCloudAsync(myUid, updated)
            updated
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Timber.tag(TAG).e(e, "Error in approveLocalVpnFallbackLocally")
            null
        }
    }

    @VisibleForTesting
    internal fun sanitizeSupervisedSelfPolicy(
        bundle: DevicePolicyBundle,
        current: DevicePolicyBundle
    ): DevicePolicyBundle {
        var sanitized = bundle.copy(
            screenAiSchemaVersion = maxOf(
                current.screenAiSchemaVersion,
                DevicePolicyBundle.CURRENT_SCREEN_AI_SCHEMA_VERSION
            ),
            breakoutStatus = current.breakoutStatus,
            breakoutRequestedAt = current.breakoutRequestedAt,
            breakoutUnlockAt = current.breakoutUnlockAt,
            deactivationStatus = current.deactivationStatus,
            deactivationRequestedAt = current.deactivationRequestedAt,
            deactivationUnlockAt = current.deactivationUnlockAt,
            deactivationHasQr = current.deactivationHasQr,
            remoteSelfControlResetRequestedAt = current.remoteSelfControlResetRequestedAt,
            remoteTeardownRequestedAt = current.remoteTeardownRequestedAt,
            remoteModeUnlockTarget = current.remoteModeUnlockTarget,
            remoteModeUnlockRequestedAt = current.remoteModeUnlockRequestedAt
        )
        if (!current.selfControlOtherSettingsAllowed) {
            sanitized = sanitized.copy(
                isDateTimeLocked = current.isDateTimeLocked,
                isClearAppDataAllowed = current.isClearAppDataAllowed,
                timeZoneId = current.timeZoneId,
                isInstantInternetKillEnabled = current.isInstantInternetKillEnabled
            )
        }
        if (!current.selfControlCurfewAllowed) {
            sanitized = sanitized.copy(
                curfewStartHour = current.curfewStartHour,
                curfewStartMinute = current.curfewStartMinute,
                curfewEndHour = current.curfewEndHour,
                curfewEndMinute = current.curfewEndMinute,
                isCurfewEnabled = current.isCurfewEnabled,
                isInstantInternetKillEnabled = current.isInstantInternetKillEnabled,
                isWeekendCurfewEnabled = current.isWeekendCurfewEnabled,
                weekendCurfewStartHour = current.weekendCurfewStartHour,
                weekendCurfewStartMinute = current.weekendCurfewStartMinute,
                weekendCurfewEndHour = current.weekendCurfewEndHour,
                weekendCurfewEndMinute = current.weekendCurfewEndMinute,
                isVpnBlackholeEnabled = current.isVpnBlackholeEnabled,
                customCurfewWhitelistPackages = current.customCurfewWhitelistPackages,
                curfewVpnBypassPackages = current.curfewVpnBypassPackages
            )
        }
        if (!current.selfControlWebAllowed) {
            sanitized = sanitized.copy(
                browserFilterMode = current.browserFilterMode,
                isFullSystemFilteringEnabled = current.isFullSystemFilteringEnabled,
                customBlockedUrls = current.customBlockedUrls,
                customAllowedUrls = current.customAllowedUrls,
                dnsHost = current.dnsHost,
                isYouTubeRestrictedModeEnabled = current.isYouTubeRestrictedModeEnabled,
                isAdBlockingEnabled = current.isAdBlockingEnabled,
                isLocalVpnFallbackEnabled = current.isLocalVpnFallbackEnabled,
                isTelegramWebBlocked = current.isTelegramWebBlocked,
                isIncognitoBlocked = current.isIncognitoBlocked,
                isBlockExternalVpnEnabled = current.isBlockExternalVpnEnabled
            )
        }
        if (!current.selfControlAppsAllowed) {
            sanitized = sanitized.copy(
                isScreenAiEnabledGlobally = current.isScreenAiEnabledGlobally,
                customBlacklistPackages = current.customBlacklistPackages,
                customAiScanPackages = current.customAiScanPackages,
                excludedAiScanPackages = current.excludedAiScanPackages,
                protectedAppPackages = current.protectedAppPackages
            )
        }
        if (!current.selfControlWaqfaAllowed) {
            sanitized = sanitized.copy(waqfaConfig = current.waqfaConfig)
        }
        if (!current.selfControlPageShieldAllowed) {
            sanitized = sanitized.copy(pageShieldConfig = current.pageShieldConfig)
        }
        if (!current.selfControlNewAppsAllowed) {
            sanitized = sanitized.copy(lockNewInstallsUntilApproved = current.lockNewInstallsUntilApproved)
        }
        if (!current.selfControlFortressesAllowed) {
            sanitized = sanitized.copy(fortressesConfig = current.fortressesConfig)
        }
        return sanitized
    }

    suspend fun addAppToCurfewWhitelist(packageName: String): Boolean = withContext(Dispatchers.IO) {
        if (packageName.isBlank()) return@withContext false
        try {
            initialPolicyReady.await()
            var alreadyWhitelisted = false
            val acceptedPair = synchronized(policyStateLock) {
                val current = _currentPolicyFlow.value
                if (current.customCurfewWhitelistPackages.contains(packageName)) {
                    alreadyWhitelisted = true
                    null
                } else if (AppPolicyRegistry.isRestrictedBrowser(packageName) ||
                    CurfewConfig.KNOWN_APP_STORES.contains(packageName) ||
                    CurfewConfig.CURFEW_SEARCH_APPS.contains(packageName) ||
                    current.customBlacklistPackages.contains(packageName)
                ) {
                    null
                } else {
                    val newPolicy = current.copy(
                        customCurfewWhitelistPackages = current.customCurfewWhitelistPackages + packageName,
                        version = current.version + 1,
                        updatedAt = TrustedTimeProvider.currentTimeMillis(appContext)
                    )
                    _currentPolicyFlow.value = newPolicy
                    SelfControlAccessGateRegistry.isSelfControlAllowedWhenSupervised = newPolicy.isSelfControlAllowed
                    schedulePolicyPersistenceLocked(newPolicy)
                    enforcementHandler.enqueueApply(newPolicy, current)
                    current to newPolicy
                }
            }

            if (alreadyWhitelisted) return@withContext true
            if (acceptedPair == null) {
                Timber.tag(TAG).w("Refusing to add restricted or blacklisted package to curfew whitelist: %s", packageName)
                return@withContext false
            }

            val (previous, updated) = acceptedPair
            recordAndHandleSupervisionChange(updated, previous)
            val myUid = AccountabilityRepository.getInstance(appContext).getCachedUid()
            cloudSyncBridge.syncPolicyToCloudAsync(myUid, updated)
            Timber.tag(TAG).i("Auto-whitelisted new Islamic/productive app in curfew: %s", packageName)
            true
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Timber.tag(TAG).e(e, "Failed auto-whitelisting app %s in curfew", packageName)
            false
        }
    }

    internal suspend fun awaitEnforcementIdleForTeardown() = enforcementHandler.awaitIdle()

    @VisibleForTesting
    internal fun shouldExecuteGuardianTeardown(
        cloudPolicy: DevicePolicyBundle,
        lastProcessedRequestedAt: Long
    ): Boolean = Companion.shouldExecuteGuardianTeardown(cloudPolicy, lastProcessedRequestedAt)

    internal suspend fun checkAndExecutePendingGuardianTeardown(cloudPolicy: DevicePolicyBundle): Boolean =
        remoteTeardownHandler.checkAndExecutePendingGuardianTeardown(cloudPolicy)

    internal suspend fun retryPendingGuardianTeardownIfNeeded(): Boolean =
        remoteTeardownHandler.retryPendingGuardianTeardownIfNeeded(_currentPolicyFlow.value)

    suspend fun requestDeviceOwnerDeactivation(hasQr: Boolean): Result<Long> =
        AccountabilityRepository.getInstance(appContext).requestDeviceOwnerDeactivation(hasQr)

    suspend fun cancelDeviceOwnerDeactivation(): Result<Unit> =
        AccountabilityRepository.getInstance(appContext).cancelDeviceOwnerDeactivation()

    suspend fun executeDeviceOwnerTeardown(): Result<Unit> =
        DeviceOwnerTeardownManager.executeTeardown(appContext)

    companion object {
        private const val TAG = "AntiPorn_PolicyRepo"
        const val PREFS_NAME = "hisn_policy_fast_cache"
        const val KEY_CACHED_POLICY_JSON = "cached_policy_json"
        const val KEY_LAST_PROCESSED_REMOTE_RESET_AT = "last_processed_remote_reset_at"
        const val KEY_LAST_PROCESSED_REMOTE_TEARDOWN_AT = "last_processed_remote_teardown_at"
        const val KEY_LAST_PROCESSED_REMOTE_MODE_UNLOCK_AT = "last_processed_remote_mode_unlock_at"

        @VisibleForTesting
        internal fun shouldExecuteGuardianTeardown(
            cloudPolicy: DevicePolicyBundle,
            lastProcessedRequestedAt: Long
        ): Boolean = RemoteTeardownHandler.shouldExecuteGuardianTeardown(cloudPolicy, lastProcessedRequestedAt)

        @Volatile
        private var instance: PolicyRepository? = null

        fun getInstance(context: Context): PolicyRepository {
            return instance ?: synchronized(this) {
                instance ?: (try {
                    org.koin.core.context.GlobalContext.getOrNull()?.get<PolicyRepository>()
                } catch (_: Throwable) {
                    null
                } ?: PolicyRepository(context.applicationContext)).also {
                    instance = it
                }
            }
        }

        @VisibleForTesting
        fun setInstance(repo: PolicyRepository?) {
            instance = repo
        }
    }
}
