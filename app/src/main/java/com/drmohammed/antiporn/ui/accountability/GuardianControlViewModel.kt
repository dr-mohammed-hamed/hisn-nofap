package com.drmohammed.antiporn.ui.accountability

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.drmohammed.antiporn.R
import com.drmohammed.antiporn.core.model.DevicePolicyBundle
import com.drmohammed.antiporn.core.time.TrustedTimeProvider
import com.drmohammed.antiporn.telemetry.AiTelemetryReporter
import com.drmohammed.antiporn.curfew.CurfewConfig
import com.drmohammed.antiporn.data.local.db.HisnRoomDatabase
import com.drmohammed.antiporn.data.local.db.dao.PartnerDao
import com.drmohammed.antiporn.data.local.db.entity.PartnerEntity
import com.drmohammed.antiporn.data.repository.AccountabilityRepository
import com.drmohammed.antiporn.data.repository.PolicyRepository
import com.drmohammed.antiporn.data.repository.completeGuardianSideTeardownCleanup
import com.drmohammed.antiporn.data.repository.isPendingGuardianTeardown
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Source
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * Clean MVVM ViewModel for GuardianControlActivity.
 * Coordinates remote policy fetching, offline caching, DNS validation pre-flight probes,
 * partner renaming, and Firestore policy pushes.
 */
class GuardianControlViewModel(
    application: Application,
    private val policyRepository: PolicyRepository = PolicyRepository.getInstance(application),
    private val accountabilityRepository: AccountabilityRepository = AccountabilityRepository.getInstance(application),
    private val partnerDao: PartnerDao = HisnRoomDatabase.getInstance(application).partnerDao()
) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(GuardianControlUiState())
    val uiState: StateFlow<GuardianControlUiState> = _uiState.asStateFlow()
    private var loadWardJob: Job? = null
    private val listenerLock = Any()
    @Volatile
    private var policyListenerRegistration: ListenerRegistration? = null
    @Volatile
    private var isListenerDesired: Boolean = false

    private val _events = Channel<GuardianControlEvent>(Channel.BUFFERED)
    val events: Flow<GuardianControlEvent> = _events.receiveAsFlow()

    private var isInitialized = false

    fun initialize(wardId: String, wardName: String) {
        if (isInitialized) return
        isInitialized = true
        _uiState.update {
            it.copy(
                wardId = wardId,
                wardName = wardName
            )
        }
        loadWardPolicy()
    }

    fun loadWardPolicy() {
        val state = _uiState.value
        if (state.wardId.isBlank()) return

        loadWardJob?.cancel()
        _uiState.update { it.copy(isLoading = true) }

        loadWardJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val partner = partnerDao.getPartnerByIdAndRole(state.wardId, PartnerEntity.ROLE_WARD)
                val resolvedWardId = accountabilityRepository.resolveActivePartnerUid(state.wardId, partner?.pairingCode)
                val activeWardId = if (resolvedWardId.isNotBlank()) resolvedWardId else state.wardId
                val activePartner = if (activeWardId != state.wardId) {
                    partnerDao.getPartnerByIdAndRole(activeWardId, PartnerEntity.ROLE_WARD) ?: partner
                } else {
                    partner
                }

                val app = getApplication<Application>()
                val fallbackPlaceholder = app.getString(R.string.status_ward_badge)
                val resolvedWardName = if ((state.wardName.isBlank() || state.wardName == fallbackPlaceholder) && activePartner != null && activePartner.displayName.isNotBlank()) {
                    activePartner.displayName
                } else {
                    state.wardName
                }

                val maskedOldId = AiTelemetryReporter.maskIdentifier(state.wardId)
                val maskedActiveId = AiTelemetryReporter.maskIdentifier(activeWardId)
                val maskedName = AiTelemetryReporter.maskIdentifier(resolvedWardName)

                if (activeWardId != state.wardId || resolvedWardName != state.wardName) {
                    Log.i(TAG, "[UidMigration] GuardianControlViewModel switched wardId from $maskedOldId to $maskedActiveId (name: $maskedName)")
                    _uiState.update { it.copy(wardId = activeWardId, wardName = resolvedWardName) }
                }

                var isOffline = false
                var snapshot: com.google.firebase.firestore.DocumentSnapshot? = null
                val firestore = FirebaseFirestore.getInstance()

                try {
                    snapshot = firestore.collection("policies").document(activeWardId).get().await()
                } catch (netEx: Exception) {
                    if (netEx is CancellationException) throw netEx
                    if (netEx is FirebaseFirestoreException &&
                        netEx.code == FirebaseFirestoreException.Code.PERMISSION_DENIED &&
                        FirebaseAuth.getInstance().currentUser != null &&
                        (accountabilityRepository.isPendingGuardianTeardown(activeWardId) ||
                            partnerDao.getPartnerByIdAndRole(activeWardId, PartnerEntity.ROLE_WARD) == null)
                    ) {
                        val cleaned = accountabilityRepository.completeGuardianSideTeardownCleanup(activeWardId, resolvedWardName)
                        _uiState.update { it.copy(isLoading = false) }
                        _events.send(
                            if (cleaned) GuardianControlEvent.WardTeardownCompleted
                            else GuardianControlEvent.SupervisionEnded
                        )
                        return@launch
                    }
                    Log.w(TAG, "Network fetch failed for ward policy $maskedActiveId, attempting offline cache", netEx)
                    isOffline = true
                    try {
                        snapshot = firestore.collection("policies").document(activeWardId).get(Source.CACHE).await()
                    } catch (cacheEx: Exception) {
                        if (cacheEx is CancellationException) throw cacheEx
                        Log.w(TAG, "Cache fetch failed or empty for ward $maskedActiveId", cacheEx)
                    }
                }

                val snapshotData = snapshot?.data
                val isLivePolicy = (snapshot != null && snapshot.exists() && snapshotData != null)
                val policy = if (isLivePolicy) {
                    DevicePolicyBundle.fromMap(snapshotData)
                } else {
                    DevicePolicyBundle.defaultPolicy()
                }

                if (isLivePolicy &&
                    policy.deactivationStatus == DevicePolicyBundle.DEACTIVATION_STATUS_COMPLETED &&
                    (accountabilityRepository.isPendingGuardianTeardown(activeWardId) || !policy.isSupervised)
                ) {
                    val cleaned = accountabilityRepository.completeGuardianSideTeardownCleanup(activeWardId, resolvedWardName)
                    _uiState.update { it.copy(isLoading = false) }
                    _events.send(
                        if (cleaned) GuardianControlEvent.WardTeardownCompleted
                        else GuardianControlEvent.SupervisionEnded
                    )
                    return@launch
                }

                com.drmohammed.antiporn.telemetry.BlackBoxRecorder.recordCloudSync(
                    app,
                    "WARD_POLICY_FETCH",
                    isSuccess = isLivePolicy && !isOffline,
                    details = "wardId=$maskedActiveId, isLive=$isLivePolicy, offline=$isOffline, curfew=${policy.curfewStartHour}:${policy.curfewStartMinute}-${policy.curfewEndHour}:${policy.curfewEndMinute}, selfControl=${policy.isSelfControlAllowed}"
                )

                val telemetry = extractTelemetry(snapshot, policy)

                _uiState.update { current ->
                    val initial = policy.copy(
                        dnsHost = policy.dnsHost.trim(),
                        timeZoneId = policy.timeZoneId.ifBlank { CurfewConfig.DEFAULT_TIMEZONE_ID }
                    )

                    applyTelemetryToState(
                        current.copy(
                            wardId = activeWardId,
                            isLoading = false,
                            isOffline = isOffline,
                            currentPolicy = policy,
                            initialPolicySnapshot = if (isLivePolicy) initial else current.initialPolicySnapshot,
                            selectedStartHour = policy.curfewStartHour,
                            selectedStartMinute = policy.curfewStartMinute,
                            selectedEndHour = policy.curfewEndHour,
                            selectedEndMinute = policy.curfewEndMinute,
                            selectedWeekendStartHour = policy.weekendCurfewStartHour,
                            selectedWeekendStartMinute = policy.weekendCurfewStartMinute,
                            selectedWeekendEndHour = policy.weekendCurfewEndHour,
                            selectedWeekendEndMinute = policy.weekendCurfewEndMinute,
                            selectedTimeZoneId = policy.timeZoneId.ifBlank { CurfewConfig.DEFAULT_TIMEZONE_ID },
                            isCurfewEnabled = policy.isCurfewEnabled,
                            isWeekendCurfewEnabled = policy.isWeekendCurfewEnabled,
                            isSelfControlAllowed = policy.isSelfControlAllowed,
                            isVpnBlackholeEnabled = policy.isVpnBlackholeEnabled,
                            isInstantInternetKillEnabled = policy.isInstantInternetKillEnabled,
                            isIncognitoBlocked = policy.isIncognitoBlocked,
                            lockNewInstallsUntilApproved = policy.lockNewInstallsUntilApproved,
                            isDateTimeLocked = policy.isDateTimeLocked,
                            isYouTubeRestrictedModeEnabled = policy.isYouTubeRestrictedModeEnabled,
                            isTelegramWebBlocked = policy.isTelegramWebBlocked,
                            isScreenAiEnabledGlobally = policy.isScreenAiEnabledGlobally,
                            dnsHost = policy.dnsHost.trim()
                        ),
                        telemetry
                    )
                }
                synchronized(listenerLock) {
                    if (isListenerDesired) {
                        attachPolicyListenerLocked()
                    }
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Timber.tag(TAG).e(e, "Error loading ward policy for %s", AiTelemetryReporter.maskIdentifier(state.wardId))
                _uiState.update { it.copy(isLoading = false, isOffline = true) }
            }
        }
    }

    fun updateScreenAiEnabledImmediately(enabled: Boolean) {
        val state = _uiState.value
        val baseline = state.initialPolicySnapshot
        if (state.wardId.isBlank() || state.isSaving || state.isLoading) return
        if (baseline == null) {
            viewModelScope.launch {
                _events.send(GuardianControlEvent.ShowToastRes(R.string.toast_guardian_policy_save_failed))
            }
            return
        }
        val previousEnabled = state.isScreenAiEnabledGlobally
        _uiState.update {
            it.copy(
                isScreenAiEnabledGlobally = enabled,
                isSaving = true
            )
        }

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val context = getApplication<Application>()
                val myUid = accountabilityRepository.getCachedUid()
                val myName = accountabilityRepository.getMyDisplayName()
                val pushedPolicy = policyRepository.mergeAndPushRemotePolicyPatch(
                    wardUid = state.wardId,
                    fallbackPolicy = baseline,
                    guardianUidFallback = myUid,
                    guardianNameFallback = myName
                ) { latest ->
                    val now = TrustedTimeProvider.currentTimeMillis(context)
                    latest.copy(
                        isScreenAiEnabledGlobally = enabled,
                        screenAiSchemaVersion = DevicePolicyBundle.CURRENT_SCREEN_AI_SCHEMA_VERSION,
                        version = latest.version + 1,
                        updatedAt = now
                    )
                }
                withContext(Dispatchers.Main) {
                    if (pushedPolicy != null) {
                        _uiState.update {
                            it.copy(
                                currentPolicy = pushedPolicy,
                                initialPolicySnapshot = pushedPolicy.copy(),
                                isScreenAiEnabledGlobally = pushedPolicy.isScreenAiEnabledGlobally,
                                version = pushedPolicy.version
                            )
                        }
                        _events.send(GuardianControlEvent.ShowToastRes(R.string.toast_guardian_policy_saved))
                    } else {
                        _uiState.update {
                            it.copy(isScreenAiEnabledGlobally = previousEnabled)
                        }
                        _events.send(GuardianControlEvent.ShowToastRes(R.string.toast_guardian_policy_save_failed))
                    }
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.e(TAG, "Error updating Vision Guard AI state immediately", e)
                _uiState.update { it.copy(isScreenAiEnabledGlobally = previousEnabled) }
                _events.send(GuardianControlEvent.ShowToastRes(R.string.toast_guardian_policy_save_failed))
            } finally {
                _uiState.update { it.copy(isSaving = false) }
            }
        }
    }

    fun onPolicyReverted(reverted: DevicePolicyBundle) {
        _uiState.update {
            it.copy(
                currentPolicy = reverted,
                initialPolicySnapshot = reverted.copy(),
                selectedStartHour = reverted.curfewStartHour,
                selectedStartMinute = reverted.curfewStartMinute,
                selectedEndHour = reverted.curfewEndHour,
                selectedEndMinute = reverted.curfewEndMinute,
                selectedWeekendStartHour = reverted.weekendCurfewStartHour,
                selectedWeekendStartMinute = reverted.weekendCurfewStartMinute,
                selectedWeekendEndHour = reverted.weekendCurfewEndHour,
                selectedWeekendEndMinute = reverted.weekendCurfewEndMinute,
                selectedTimeZoneId = reverted.timeZoneId,
                isCurfewEnabled = reverted.isCurfewEnabled,
                isWeekendCurfewEnabled = reverted.isWeekendCurfewEnabled,
                isSelfControlAllowed = reverted.isSelfControlAllowed,
                isVpnBlackholeEnabled = reverted.isVpnBlackholeEnabled,
                isInstantInternetKillEnabled = reverted.isInstantInternetKillEnabled,
                isIncognitoBlocked = reverted.isIncognitoBlocked,
                lockNewInstallsUntilApproved = reverted.lockNewInstallsUntilApproved,
                isDateTimeLocked = reverted.isDateTimeLocked,
                isYouTubeRestrictedModeEnabled = reverted.isYouTubeRestrictedModeEnabled,
                isTelegramWebBlocked = reverted.isTelegramWebBlocked,
                isScreenAiEnabledGlobally = reverted.isScreenAiEnabledGlobally,
                dnsHost = reverted.dnsHost.trim()
            )
        }
    }

    fun onPolicyAcknowledged(acknowledged: DevicePolicyBundle) {
        _uiState.update {
            it.copy(
                currentPolicy = acknowledged,
                initialPolicySnapshot = acknowledged.copy(),
                isInstantInternetKillEnabled = acknowledged.isInstantInternetKillEnabled,
                isSelfControlAllowed = acknowledged.isSelfControlAllowed,
                isScreenAiEnabledGlobally = acknowledged.isScreenAiEnabledGlobally,
                version = acknowledged.version
            )
        }
    }

    fun applyUpdatedPolicyFromSubScreen(updated: DevicePolicyBundle) {
        onPolicyReverted(updated)
        _uiState.update { it.copy(version = updated.version) }
    }

    fun unlockWardActiveMode(target: String) {
        val state = _uiState.value
        val baseline = state.initialPolicySnapshot
        if (state.wardId.isBlank() || state.isSaving || state.isLoading || baseline == null) {
            if (baseline == null && state.wardId.isNotBlank()) {
                viewModelScope.launch {
                    _events.send(GuardianControlEvent.ShowToastRes(R.string.toast_guardian_policy_save_failed))
                }
            }
            return
        }

        _uiState.update { it.copy(isSaving = true) }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val context = getApplication<Application>()
                val myUid = accountabilityRepository.getCachedUid()
                val myName = accountabilityRepository.getMyDisplayName()
                val pushedPolicy = policyRepository.mergeAndPushRemotePolicyPatch(
                    wardUid = state.wardId,
                    fallbackPolicy = baseline,
                    guardianUidFallback = myUid,
                    guardianNameFallback = myName
                ) { latest ->
                    val now = TrustedTimeProvider.currentTimeMillis(context)
                    latest.copy(
                        remoteModeUnlockTarget = target,
                        remoteModeUnlockRequestedAt = now,
                        version = latest.version + 1,
                        updatedAt = now
                    )
                }
                withContext(Dispatchers.Main) {
                    if (pushedPolicy != null) {
                        _uiState.update {
                            it.copy(
                                currentPolicy = pushedPolicy,
                                initialPolicySnapshot = pushedPolicy.copy(),
                                version = pushedPolicy.version
                            )
                        }
                        _events.send(GuardianControlEvent.ShowToastRes(R.string.toast_guardian_remote_unlock_sent))
                    } else {
                        _events.send(GuardianControlEvent.ShowToastRes(R.string.toast_guardian_policy_save_failed))
                    }
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Timber.tag(TAG).e(e, "Error sending remote mode unlock target=%s", target)
                _events.send(GuardianControlEvent.ShowToastRes(R.string.toast_guardian_policy_save_failed))
            } finally {
                _uiState.update { it.copy(isSaving = false) }
            }
        }
    }

    fun renameWard(newName: String) {
        val state = _uiState.value
        if (newName.isBlank() || newName == state.wardName) return

        viewModelScope.launch(Dispatchers.IO) {
            val result = accountabilityRepository.renamePartner(state.wardId, newName, PartnerEntity.ROLE_WARD)
            result.onSuccess {
                _uiState.update { it.copy(wardName = newName) }
                _events.send(GuardianControlEvent.ShowToastRes(R.string.toast_name_saved))
            }.onFailure { e ->
                Log.e(TAG, "Failed to rename ward", e)
                val msg = e.localizedMessage
                if (!msg.isNullOrBlank()) {
                    _events.send(GuardianControlEvent.ShowToastString(msg, isLong = true))
                } else {
                    _events.send(GuardianControlEvent.ShowToastRes(R.string.toast_rename_failed, isLong = true))
                }
            }
        }
    }

    fun startPolicyListener() {
        synchronized(listenerLock) {
            isListenerDesired = true
            attachPolicyListenerLocked()
        }
    }

    private fun attachPolicyListenerLocked() {
        val state = _uiState.value
        val targetWardId = state.wardId.trim()
        if (targetWardId.isBlank()) return
        val maskedWardId = AiTelemetryReporter.maskIdentifier(targetWardId)
        synchronized(listenerLock) {
            if (!isListenerDesired) return
            policyListenerRegistration?.remove()
            policyListenerRegistration = null
            try {
                val firestore = FirebaseFirestore.getInstance()
                policyListenerRegistration = firestore.collection("policies").document(targetWardId)
                    .addSnapshotListener { snapshot, error ->
                        if (error != null) {
                            if (error.code == FirebaseFirestoreException.Code.PERMISSION_DENIED &&
                                FirebaseAuth.getInstance().currentUser != null
                            ) {
                                viewModelScope.launch(Dispatchers.IO) {
                                    val isPendingOrRemoved = accountabilityRepository.isPendingGuardianTeardown(targetWardId) ||
                                        partnerDao.getPartnerByIdAndRole(targetWardId, PartnerEntity.ROLE_WARD) == null
                                    if (isPendingOrRemoved) {
                                        stopPolicyListener()
                                        val cleaned = accountabilityRepository.completeGuardianSideTeardownCleanup(
                                            targetWardId,
                                            _uiState.value.wardName
                                        )
                                        _events.send(
                                            if (cleaned) GuardianControlEvent.WardTeardownCompleted
                                            else GuardianControlEvent.SupervisionEnded
                                        )
                                    } else {
                                        Timber.tag(TAG).w(error, "Error in real-time policy listener for ward %s", maskedWardId)
                                    }
                                }
                                return@addSnapshotListener
                            }
                            Timber.tag(TAG).w(error, "Error in real-time policy listener for ward %s", maskedWardId)
                            return@addSnapshotListener
                        }
                        if (snapshot == null || !snapshot.exists()) return@addSnapshotListener
                        val snapshotData = snapshot.data ?: return@addSnapshotListener
                        val policy = DevicePolicyBundle.fromMap(snapshotData)
                        if (policy.deactivationStatus == DevicePolicyBundle.DEACTIVATION_STATUS_COMPLETED &&
                            (accountabilityRepository.isPendingGuardianTeardown(targetWardId) || !policy.isSupervised)
                        ) {
                            stopPolicyListener()
                            viewModelScope.launch(Dispatchers.IO) {
                                val cleaned = accountabilityRepository.completeGuardianSideTeardownCleanup(
                                    targetWardId,
                                    _uiState.value.wardName
                                )
                                _events.send(
                                    if (cleaned) GuardianControlEvent.WardTeardownCompleted
                                    else GuardianControlEvent.SupervisionEnded
                                )
                            }
                            return@addSnapshotListener
                        }
                        val telemetry = extractTelemetry(snapshot, policy)

                        _uiState.update { current ->
                            val keepLocalEdits = current.hasUnsavedChanges || current.isSaving
                            if (!keepLocalEdits) {
                                val normalizedTz = policy.timeZoneId.ifBlank { CurfewConfig.DEFAULT_TIMEZONE_ID }
                                val normalizedDns = policy.dnsHost.trim()
                                val initial = policy.copy(
                                    dnsHost = normalizedDns,
                                    timeZoneId = normalizedTz
                                )
                                applyTelemetryToState(
                                    current.copy(
                                        currentPolicy = policy,
                                        initialPolicySnapshot = initial,
                                        selectedStartHour = policy.curfewStartHour,
                                        selectedStartMinute = policy.curfewStartMinute,
                                        selectedEndHour = policy.curfewEndHour,
                                        selectedEndMinute = policy.curfewEndMinute,
                                        selectedWeekendStartHour = policy.weekendCurfewStartHour,
                                        selectedWeekendStartMinute = policy.weekendCurfewStartMinute,
                                        selectedWeekendEndHour = policy.weekendCurfewEndHour,
                                        selectedWeekendEndMinute = policy.weekendCurfewEndMinute,
                                        selectedTimeZoneId = normalizedTz,
                                        isCurfewEnabled = policy.isCurfewEnabled,
                                        isWeekendCurfewEnabled = policy.isWeekendCurfewEnabled,
                                        isSelfControlAllowed = policy.isSelfControlAllowed,
                                        isVpnBlackholeEnabled = policy.isVpnBlackholeEnabled,
                                        isInstantInternetKillEnabled = policy.isInstantInternetKillEnabled,
                                        isIncognitoBlocked = policy.isIncognitoBlocked,
                                        lockNewInstallsUntilApproved = policy.lockNewInstallsUntilApproved,
                                        isDateTimeLocked = policy.isDateTimeLocked,
                                        isYouTubeRestrictedModeEnabled = policy.isYouTubeRestrictedModeEnabled,
                                        isTelegramWebBlocked = policy.isTelegramWebBlocked,
                                        isScreenAiEnabledGlobally = policy.isScreenAiEnabledGlobally,
                                        dnsHost = normalizedDns,
                                        isOffline = false
                                    ),
                                    telemetry
                                )
                            } else {
                                applyTelemetryToState(current.copy(isOffline = false), telemetry)
                            }
                        }
                    }
                Log.i(TAG, "Attached real-time policy listener for ward $maskedWardId")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to attach real-time policy listener for ward $maskedWardId", e)
            }
        }
    }

    fun stopPolicyListener() {
        synchronized(listenerLock) {
            isListenerDesired = false
            policyListenerRegistration?.remove()
            policyListenerRegistration = null
        }
    }

    override fun onCleared() {
        stopPolicyListener()
        super.onCleared()
    }

    private data class WardTelemetrySnapshot(
        val version: Long,
        val acknowledgedVersion: Long,
        val lastAcknowledgedAt: Long,
        val lastHeartbeat: Long,
        val wardHasAccessibilityService: Boolean?,
        val wardHasUsageStats: Boolean?,
        val wardCanWriteSecureSettings: Boolean?,
        val wardCapabilitiesUpdatedAt: Long,
        val wardA11yTamperAt: Long,
        val wardSdkInt: Int?,
        val wardActiveFortressId: String?,
        val wardActiveFortressName: String?,
        val wardActiveFortressIsEmergency: Boolean,
        val wardActiveFortressEndsAtWall: Long,
        val wardIsCurfewActiveNow: Boolean,
        val wardActiveModesUpdatedAt: Long
    )

    private fun extractTelemetry(
        snapshot: com.google.firebase.firestore.DocumentSnapshot?,
        policy: DevicePolicyBundle
    ): WardTelemetrySnapshot = WardTelemetrySnapshot(
        version = snapshot?.getLong("version") ?: policy.version,
        acknowledgedVersion = snapshot?.getLong("acknowledgedVersion") ?: 0L,
        lastAcknowledgedAt = snapshot?.getLong("lastAcknowledgedAt") ?: 0L,
        lastHeartbeat = snapshot?.getLong("lastHeartbeat") ?: 0L,
        wardHasAccessibilityService = snapshot?.getBoolean("wardHasAccessibilityService")
            ?: policy.wardHasAccessibilityService,
        wardHasUsageStats = snapshot?.getBoolean("wardHasUsageStats")
            ?: policy.wardHasUsageStats,
        wardCanWriteSecureSettings = snapshot?.getBoolean("wardCanWriteSecureSettings")
            ?: policy.wardCanWriteSecureSettings,
        wardCapabilitiesUpdatedAt = snapshot?.getLong("wardCapabilitiesUpdatedAt")
            ?: policy.wardCapabilitiesUpdatedAt,
        wardA11yTamperAt = snapshot?.getLong("wardA11yTamperAt")
            ?: policy.wardA11yTamperAt,
        wardSdkInt = snapshot?.getLong("wardSdkInt")?.toInt()
            ?: policy.wardSdkInt,
        wardActiveFortressId = snapshot?.getString("wardActiveFortressId")?.takeIf { it.isNotBlank() }
            ?: policy.wardActiveFortressId,
        wardActiveFortressName = snapshot?.getString("wardActiveFortressName")?.takeIf { it.isNotBlank() }
            ?: policy.wardActiveFortressName,
        wardActiveFortressIsEmergency = snapshot?.getBoolean("wardActiveFortressIsEmergency")
            ?: policy.wardActiveFortressIsEmergency,
        wardActiveFortressEndsAtWall = snapshot?.getLong("wardActiveFortressEndsAtWall")
            ?: policy.wardActiveFortressEndsAtWall,
        wardIsCurfewActiveNow = snapshot?.getBoolean("wardIsCurfewActiveNow")
            ?: policy.wardIsCurfewActiveNow,
        wardActiveModesUpdatedAt = snapshot?.getLong("wardActiveModesUpdatedAt")
            ?: policy.wardActiveModesUpdatedAt
    )

    private fun applyTelemetryToState(
        state: GuardianControlUiState,
        telemetry: WardTelemetrySnapshot
    ): GuardianControlUiState = state.copy(
        version = telemetry.version,
        acknowledgedVersion = telemetry.acknowledgedVersion,
        lastAcknowledgedAt = telemetry.lastAcknowledgedAt,
        lastHeartbeat = telemetry.lastHeartbeat,
        wardHasAccessibilityService = telemetry.wardHasAccessibilityService,
        wardHasUsageStats = telemetry.wardHasUsageStats,
        wardCanWriteSecureSettings = telemetry.wardCanWriteSecureSettings,
        wardCapabilitiesUpdatedAt = telemetry.wardCapabilitiesUpdatedAt,
        wardA11yTamperAt = telemetry.wardA11yTamperAt,
        wardSdkInt = telemetry.wardSdkInt,
        wardActiveFortressId = telemetry.wardActiveFortressId,
        wardActiveFortressName = telemetry.wardActiveFortressName,
        wardActiveFortressIsEmergency = telemetry.wardActiveFortressIsEmergency,
        wardActiveFortressEndsAtWall = telemetry.wardActiveFortressEndsAtWall,
        wardIsCurfewActiveNow = telemetry.wardIsCurfewActiveNow,
        wardActiveModesUpdatedAt = telemetry.wardActiveModesUpdatedAt
    )

    companion object {
        private const val TAG = "AntiPorn_GuardianVM"
    }
}
