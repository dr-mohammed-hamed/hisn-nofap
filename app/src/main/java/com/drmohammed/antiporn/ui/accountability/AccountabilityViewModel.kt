package com.drmohammed.antiporn.ui.accountability

import android.app.Application
import android.util.Log
import androidx.annotation.VisibleForTesting
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.drmohammed.antiporn.cloud.auth.GoogleAuthManager
import com.drmohammed.antiporn.core.model.DevicePolicyBundle
import com.drmohammed.antiporn.core.time.TrustedTimeProvider
import com.drmohammed.antiporn.data.local.db.entity.PartnerEntity
import com.drmohammed.antiporn.data.repository.AccountabilityRepository
import com.drmohammed.antiporn.data.repository.ApprovalRepository
import com.drmohammed.antiporn.data.repository.PolicyRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

/**
 * ViewModel for AccountabilityFragment.
 * Manages partner synchronization, pairing credentials, emergency breakout flow, and approval counting.
 */
open class AccountabilityViewModel(
    application: Application,
    private val accountabilityRepo: AccountabilityRepository = AccountabilityRepository.getInstance(application),
    private val approvalRepo: ApprovalRepository? = ApprovalRepository.getInstance(application),
    private val googleAuthManager: GoogleAuthManager? = GoogleAuthManager.getInstance(application),
    private val policyRepository: PolicyRepository? = PolicyRepository.getInstance(application)
) : AndroidViewModel(application) {

    @VisibleForTesting
    internal var ioDispatcher: CoroutineDispatcher = Dispatchers.IO

    @VisibleForTesting
    internal var mainDispatcher: CoroutineDispatcher = Dispatchers.Main

    @VisibleForTesting
    internal var policyProvider: suspend () -> DevicePolicyBundle = {
        policyRepository?.getCurrentPolicy() ?: DevicePolicyBundle.defaultPolicy()
    }

    @VisibleForTesting
    internal var startFirestoreSyncAction: () -> Unit = {
        policyRepository?.startFirestoreSync()
    }

    @VisibleForTesting
    internal var rollbackCloudSyncAction: suspend (Application) -> Unit = {
        com.drmohammed.antiporn.cloud.CloudSyncManager.checkAndAutoRollbackIfUnsupervisedSuspend(it)
    }

    private val _uiState = MutableStateFlow(AccountabilityUiState())
    val uiState: StateFlow<AccountabilityUiState> = _uiState.asStateFlow()

    private val pairingSessionMutex = Mutex()
    private var loadPairingCodeJob: Job? = null
    private var breakoutCountdownJob: Job? = null
    private var pairingCountdownJob: Job? = null

    init {
        @Suppress("LeakingThis")
        if (shouldAutoObserveOnInit()) {
            observePolicyFlow()
            observeCloudSyncFlow()
            refreshAll()
        }
    }

    @VisibleForTesting
    protected open fun shouldAutoObserveOnInit(): Boolean = true

    private fun observeCloudSyncFlow() {
        viewModelScope.launch {
            com.drmohammed.antiporn.cloud.CloudSyncManager.isCloudSyncEnabledFlow.collect { enabled ->
                _uiState.update { it.copy(isCloudSyncEnabled = enabled) }
                if (enabled) {
                    loadPartners()
                    loadPendingApprovals()
                }
            }
        }
    }

    private fun observePolicyFlow() {
        val repo = policyRepository ?: return
        viewModelScope.launch(mainDispatcher) {
            repo.currentPolicyFlow.collect { policy ->
                _uiState.update { current ->
                    current.copy(
                        isSupervised = policy.isSupervised,
                        breakoutStatus = policy.breakoutStatus,
                        breakoutUnlockAt = policy.breakoutUnlockAt
                    )
                }

                if (policy.isSupervised) {
                    startBreakoutTicker(policy)
                } else {
                    stopBreakoutTicker()
                    loadPairingCode()
                }

                loadPartners()
                updateGoogleSecurityState()
            }
        }
    }

    fun refreshAll() {
        loadDisplayName()
        updateGoogleSecurityState()
        loadPairingCode()
        loadPartners()
        loadPendingApprovals()
    }

    fun loadDisplayName() {
        val name = accountabilityRepo.getMyDisplayName()
        _uiState.update { it.copy(myDisplayName = name) }
    }

    fun updateGoogleSecurityState() {
        val isLinked = googleAuthManager?.isLinkedWithGoogle() ?: false
        val email = googleAuthManager?.getLinkedEmail()
        _uiState.update { current ->
            current.copy(
                isGoogleLinked = isLinked,
                googleAccountEmail = email
            )
        }
    }

    fun loadPairingCode() {
        loadPairingCodeJob?.cancel()
        loadPairingCodeJob = viewModelScope.launch(ioDispatcher) {
            pairingSessionMutex.withLock {
                val app = getApplication<Application>()
                val isCloudEnabled = com.drmohammed.antiporn.cloud.CloudSyncManager.isCloudSyncEnabled(app)
                val activeSession = com.drmohammed.antiporn.cloud.CloudSyncManager.getActivePairingSession(app)

                if (activeSession != null) {
                    withContext(mainDispatcher) {
                        _uiState.update {
                            it.copy(
                                isCloudSyncEnabled = isCloudEnabled,
                                isTemporaryPairingActive = true,
                                myPairingCode = activeSession.code,
                                pairingExpiresAt = activeSession.expiresAt
                            )
                        }
                        startPairingTicker(activeSession.expiresAt)
                    }
                    return@withLock
                }

                // Zero-Cloud by Default: If cloud sync is not enabled, do not generate code in Firestore!
                if (!isCloudEnabled) {
                    withContext(mainDispatcher) {
                        _uiState.update {
                            it.copy(
                                isCloudSyncEnabled = false,
                                isTemporaryPairingActive = false,
                                myPairingCode = "",
                                pairingExpiresAt = 0L,
                                pairingRemainingMs = 0L
                            )
                        }
                    }
                    return@withLock
                }

                try {
                    val currentPolicy = policyProvider()
                    if (currentPolicy.isSupervised) {
                        withContext(mainDispatcher) {
                            _uiState.update {
                                it.copy(
                                    isCloudSyncEnabled = isCloudEnabled,
                                    isPairingCodeLocked = true,
                                    myPairingCode = "------"
                                )
                            }
                        }
                        return@withLock
                    }

                    // If user is a guardian with active wards, do not auto-generate a ward pairing code
                    val wards = accountabilityRepo.getWards()
                    if (wards.isNotEmpty()) {
                        withContext(mainDispatcher) {
                            _uiState.update {
                                it.copy(
                                    isCloudSyncEnabled = isCloudEnabled,
                                    isTemporaryPairingActive = false,
                                    myPairingCode = "",
                                    pairingExpiresAt = 0L,
                                    pairingRemainingMs = 0L
                                )
                            }
                        }
                        return@withLock
                    }

                    val code = accountabilityRepo.getOrCreateMyPairingCode()
                    val session = com.drmohammed.antiporn.cloud.CloudSyncManager.getActivePairingSession(app)
                    withContext(mainDispatcher) {
                        val isLocked = (code == AccountabilityRepository.CODE_LOCKED)
                        _uiState.update {
                            it.copy(
                                isCloudSyncEnabled = isCloudEnabled,
                                myPairingCode = code,
                                isPairingCodeLocked = isLocked,
                                isTemporaryPairingActive = session != null,
                                pairingExpiresAt = session?.expiresAt ?: 0L
                            )
                        }
                        if (session != null) {
                            startPairingTicker(session.expiresAt)
                        }
                    }
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    safeLogError("Failed loading pairing code", e)
                    withContext(mainDispatcher) {
                        _uiState.update {
                            it.copy(
                                isCloudSyncEnabled = isCloudEnabled,
                                myPairingCode = "------"
                            )
                        }
                    }
                }
            }
        }
    }

    fun startTemporaryPairingSession(onComplete: (Result<String>) -> Unit) {
        loadPairingCodeJob?.cancel()
        viewModelScope.launch(ioDispatcher) {
            pairingSessionMutex.withLock {
                val app = getApplication<Application>()
                try {
                    com.drmohammed.antiporn.cloud.CloudSyncManager.setCloudSyncEnabled(app, true)
                    startFirestoreSyncAction()
                    val code = accountabilityRepo.getOrCreateMyPairingCode()
                    val session = com.drmohammed.antiporn.cloud.CloudSyncManager.getActivePairingSession(app)
                    val expiresAt = session?.expiresAt ?: (TrustedTimeProvider.currentTimeMillis(app) + 15 * 60 * 1000L)
                    withContext(mainDispatcher) {
                        _uiState.update {
                            it.copy(
                                isCloudSyncEnabled = true,
                                isTemporaryPairingActive = true,
                                myPairingCode = code,
                                pairingExpiresAt = expiresAt
                            )
                        }
                        startPairingTicker(expiresAt)
                        onComplete(Result.success(code))
                    }
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    safeLogError("Failed starting temporary pairing session", e)
                    rollbackCloudSyncAction(app)
                    val cloudStillEnabled = com.drmohammed.antiporn.cloud.CloudSyncManager.isCloudSyncEnabled(app)
                    withContext(mainDispatcher) {
                        _uiState.update {
                            it.copy(
                                isTemporaryPairingActive = false,
                                myPairingCode = "",
                                pairingExpiresAt = 0L,
                                pairingRemainingMs = 0L,
                                isCloudSyncEnabled = cloudStillEnabled
                            )
                        }
                        onComplete(Result.failure(e))
                    }
                }
            }
        }
    }

    fun cancelTemporaryPairingSession(onComplete: (() -> Unit)? = null) {
        loadPairingCodeJob?.cancel()
        pairingCountdownJob?.cancel()
        pairingCountdownJob = null
        viewModelScope.launch(ioDispatcher) {
            pairingSessionMutex.withLock {
                accountabilityRepo.cancelMyPairingCode()
                val cloudStillEnabled = com.drmohammed.antiporn.cloud.CloudSyncManager.isCloudSyncEnabled(getApplication())
                withContext(mainDispatcher) {
                    _uiState.update {
                        it.copy(
                            isTemporaryPairingActive = false,
                            myPairingCode = "",
                            pairingExpiresAt = 0L,
                            pairingRemainingMs = 0L,
                            isCloudSyncEnabled = cloudStillEnabled
                        )
                    }
                    onComplete?.invoke()
                }
            }
        }
    }

    private fun startPairingTicker(expiresAt: Long) {
        pairingCountdownJob?.cancel()
        val app = getApplication<Application>()
        pairingCountdownJob = viewModelScope.launch(mainDispatcher) {
            while (isActive) {
                val remainingMs = (expiresAt - TrustedTimeProvider.currentTimeMillis(app)).coerceAtLeast(0L)
                _uiState.update { it.copy(pairingRemainingMs = remainingMs) }
                if (remainingMs <= 0L) {
                    cancelTemporaryPairingSession()
                    break
                }
                delay(1000L)
            }
        }
    }

    fun enableCloudAsGuardian() {
        val app = getApplication<Application>()
        com.drmohammed.antiporn.cloud.CloudSyncManager.setCloudSyncEnabled(app, true)
        loadPartners()
    }

    fun loadPartners() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val wards = accountabilityRepo.getWards()
                val guardians = accountabilityRepo.getGuardians()
                withContext(Dispatchers.Main) {
                    _uiState.update { current ->
                        current.copy(
                            wards = wards,
                            guardians = guardians
                        )
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed loading partner records", e)
            }
        }
    }

    fun loadPendingApprovals() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val activeWards = accountabilityRepo.getWards()
                    .filter { it.status == PartnerEntity.STATUS_ACTIVE }

                if (activeWards.isEmpty()) {
                    withContext(Dispatchers.Main) {
                        _uiState.update { it.copy(pendingApprovalsCount = 0) }
                    }
                    return@launch
                }

                val firestore = com.google.firebase.firestore.FirebaseFirestore.getInstance()
                val resolvedWardIds = activeWards.mapNotNull { ward ->
                    val resolved = accountabilityRepo.resolveActivePartnerUid(ward.id, ward.pairingCode)
                    resolved.takeIf { it.isNotBlank() }
                }.distinct()

                if (resolvedWardIds.isEmpty()) {
                    withContext(Dispatchers.Main) {
                        _uiState.update { it.copy(pendingApprovalsCount = 0) }
                    }
                    return@launch
                }

                val countDeferreds = resolvedWardIds.map { wardId ->
                    async {
                        try {
                            val snap = firestore.collection("policies").document(wardId)
                                .collection("approvals")
                                .whereEqualTo("status", "PENDING")
                                .get()
                                .await()
                            snap.size()
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed loading pending approvals count for ward $wardId", e)
                            0
                        }
                    }
                }

                val totalPending = countDeferreds.awaitAll().sum()

                withContext(Dispatchers.Main) {
                    _uiState.update { it.copy(pendingApprovalsCount = totalPending) }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed loading approvals", e)
                withContext(Dispatchers.Main) {
                    _uiState.update { it.copy(pendingApprovalsCount = 0) }
                }
            }
        }
    }

    private fun startBreakoutTicker(policy: DevicePolicyBundle) {
        breakoutCountdownJob?.cancel()
        val app = getApplication<Application>()

        if (policy.breakoutStatus == DevicePolicyBundle.BREAKOUT_STATUS_PENDING && policy.breakoutUnlockAt != null) {
            breakoutCountdownJob = viewModelScope.launch {
                while (isActive) {
                    val remainingMs = (policy.breakoutUnlockAt - TrustedTimeProvider.currentTimeMillis(app)).coerceAtLeast(0L)
                    _uiState.update { it.copy(breakoutRemainingMs = remainingMs) }
                    if (remainingMs <= 0L) break
                    delay(1000L)
                }
            }
        } else {
            _uiState.update { it.copy(breakoutRemainingMs = 0L) }
        }
    }

    private fun stopBreakoutTicker() {
        breakoutCountdownJob?.cancel()
        breakoutCountdownJob = null
        _uiState.update { it.copy(breakoutRemainingMs = 0L) }
    }

    fun requestEmergencyBreakout(onResult: (Result<Long>) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val result = accountabilityRepo.requestEmergencyBreakout()
            withContext(Dispatchers.Main) {
                onResult(result)
            }
        }
    }

    fun cancelEmergencyBreakout(onResult: (Result<Unit>) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val result = accountabilityRepo.cancelEmergencyBreakout()
            withContext(Dispatchers.Main) {
                onResult(result)
            }
        }
    }

    fun executeEmergencyBreakout(onResult: (Result<Unit>) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val result = accountabilityRepo.executeEmergencyBreakout()
            withContext(Dispatchers.Main) {
                loadPairingCode()
                loadPartners()
                onResult(result)
            }
        }
    }

    fun setMyDisplayName(name: String, onResult: ((Result<Unit>) -> Unit)? = null) {
        viewModelScope.launch(Dispatchers.IO) {
            val result = accountabilityRepo.setMyDisplayName(name)
            withContext(Dispatchers.Main) {
                if (result.isSuccess) {
                    _uiState.update { it.copy(myDisplayName = name) }
                }
                onResult?.invoke(result)
            }
        }
    }

    suspend fun fetchWardPolicy(wardUid: String): DevicePolicyBundle? {
        return accountabilityRepo.fetchWardPolicy(wardUid)
    }

    fun revokeSupervision(wardUid: String, onResult: (Result<Unit>) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val result = accountabilityRepo.revokeSupervision(wardUid)
            withContext(Dispatchers.Main) {
                if (result.isSuccess) {
                    loadPartners()
                }
                onResult(result)
            }
        }
    }

    fun pairWithCode(code: String, role: String, onResult: (Result<PartnerEntity>) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val result = accountabilityRepo.pairWithCode(code, role)
            withContext(Dispatchers.Main) {
                if (result.isSuccess) {
                    pairingCountdownJob?.cancel()
                    pairingCountdownJob = null
                    _uiState.update { it.copy(isTemporaryPairingActive = false, pairingRemainingMs = 0L) }
                    loadPartners()
                    loadPairingCode()
                }
                onResult(result)
            }
        }
    }

    fun renamePartner(partnerId: String, newName: String, onResult: (Result<Unit>) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val result = accountabilityRepo.renamePartner(partnerId, newName)
            withContext(Dispatchers.Main) {
                if (result.isSuccess) {
                    loadPartners()
                }
                onResult(result)
            }
        }
    }

    override fun onCleared() {
        stopBreakoutTicker()
        loadPairingCodeJob?.cancel()
        loadPairingCodeJob = null
        pairingCountdownJob?.cancel()
        pairingCountdownJob = null
        super.onCleared()
    }

    private fun safeLogError(message: String, throwable: Throwable? = null) {
        try {
            Log.e(TAG, message, throwable)
        } catch (_: Throwable) {
            // JVM unit test fallback when android.util.Log is not mocked
        }
    }

    companion object {
        private const val TAG = "AntiPorn_AccVM"
    }
}
