package com.drmohammed.antiporn.ui.control

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.drmohammed.antiporn.core.model.DevicePolicyBundle
import com.drmohammed.antiporn.curfew.CurfewEnforcer
import com.drmohammed.antiporn.curfew.CurfewScheduler
import com.drmohammed.antiporn.data.repository.ApprovalRepository
import com.drmohammed.antiporn.data.repository.PolicyRepository
import com.drmohammed.antiporn.fortress.engine.EffectiveRestrictionResolver
import com.drmohammed.antiporn.fortress.engine.FortressSessionController
import com.drmohammed.antiporn.ui.control.gate.SelfControlAccessGateRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * ViewModel for SelfControlFragment policy editor, gate access, and active-mode unlocking.
 */
class SelfControlViewModel(
    application: Application,
    private val policyRepository: PolicyRepository = PolicyRepository.getInstance(application),
    private val approvalRepository: ApprovalRepository = ApprovalRepository.getInstance(application)
) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(
        SelfControlUiState(
            policy = policyRepository.getCurrentPolicy(),
            initialSnapshot = policyRepository.getCurrentPolicy()
        )
    )
    val uiState: StateFlow<SelfControlUiState> = _uiState.asStateFlow()

    init {
        checkGateAndRefresh()
        observePolicyFlow()
        observeActiveFortressFlow()
    }

    private fun observePolicyFlow() {
        viewModelScope.launch {
            policyRepository.currentPolicyFlow.collect { freshPolicy ->
                val app = getApplication<Application>()
                val gate = SelfControlAccessGateRegistry.getActiveGate()
                val isLocked = !gate.canAccess(app)

                _uiState.update { current ->
                    val newInitial = current.initialSnapshot ?: freshPolicy
                    val hasChanges = current.hasUnsavedChanges && (current.policy != newInitial)
                    val activePolicy = if (hasChanges) current.policy else freshPolicy

                    current.copy(
                        policy = activePolicy,
                        initialSnapshot = if (hasChanges) current.initialSnapshot else freshPolicy,
                        isLocked = isLocked,
                        hasUnsavedChanges = hasChanges
                    )
                }
                refreshActiveModesSnapshot()
            }
        }
    }

    private fun observeActiveFortressFlow() {
        viewModelScope.launch {
            FortressSessionController.activeSessionFlow.collect {
                refreshActiveModesSnapshot()
            }
        }
    }

    fun refreshActiveModesSnapshot() {
        val app = getApplication<Application>()
        viewModelScope.launch(Dispatchers.IO) {
            val policy = policyRepository.getCurrentPolicy()
            val snapshot = EffectiveRestrictionResolver.getActiveModesSnapshot(
                context = app,
                policy = policy
            )
            _uiState.update { it.copy(activeModesSnapshot = snapshot) }
        }
    }

    fun checkGateAndRefresh() {
        val app = getApplication<Application>()
        val gate = SelfControlAccessGateRegistry.getActiveGate()
        val isLocked = !gate.canAccess(app)

        _uiState.update { it.copy(isLocked = isLocked) }

        if (!isLocked) {
            loadCurrentPolicy()
            loadPendingApprovals()
            refreshActiveModesSnapshot()
        }
    }

    fun loadCurrentPolicy() {
        viewModelScope.launch(Dispatchers.IO) {
            val policy = policyRepository.getCurrentPolicy()
            val snapshot = EffectiveRestrictionResolver.getActiveModesSnapshot(
                context = getApplication(),
                policy = policy
            )
            withContext(Dispatchers.Main) {
                _uiState.update { current ->
                    current.copy(
                        policy = policy,
                        initialSnapshot = policy,
                        activeModesSnapshot = snapshot,
                        hasUnsavedChanges = false
                    )
                }
            }
        }
    }

    fun updateDraftPolicy(newDraft: DevicePolicyBundle) {
        _uiState.update { current ->
            val hasChanges = current.initialSnapshot?.let { snapshot ->
                newDraft != snapshot
            } ?: false

            current.copy(
                policy = newDraft,
                hasUnsavedChanges = hasChanges
            )
        }
    }

    fun savePolicy(
        policyToSave: DevicePolicyBundle,
        onComplete: ((Result<Unit>) -> Unit)? = null
    ) {
        _uiState.update { it.copy(isSaving = true) }

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val success = policyRepository.saveAndEnforceSelfPolicy(policyToSave)

                withContext(Dispatchers.Main) {
                    if (success) {
                        _uiState.update { current ->
                            current.copy(
                                policy = policyToSave,
                                initialSnapshot = policyToSave,
                                isSaving = false,
                                hasUnsavedChanges = false
                            )
                        }
                        onComplete?.invoke(Result.success(Unit))
                    } else {
                        _uiState.update { it.copy(isSaving = false) }
                        onComplete?.invoke(Result.failure(Exception("Failed to enforce policy")))
                    }
                }
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Failed to persist policy bundle")
                withContext(Dispatchers.Main) {
                    _uiState.update { it.copy(isSaving = false, errorMessage = e.message) }
                    onComplete?.invoke(Result.failure(e))
                }
            }
        }
    }

    fun unlockNightCurfewDirect(onResult: (Boolean) -> Unit) {
        executeModeUnlock(isFortress = false, onResult = onResult) { app ->
            CurfewEnforcer.unlockCurfewForCurrentWindowDirect(app)
        }
    }

    fun unlockNightCurfewWithQr(
        rawScannedToken: String,
        isEmergencyBypassCompleted: Boolean,
        onResult: (Boolean) -> Unit
    ) {
        executeModeUnlock(isFortress = false, onResult = onResult) { app ->
            CurfewEnforcer.unlockCurfewForCurrentWindowWithQr(
                context = app,
                rawScannedToken = rawScannedToken,
                isEmergencyBypassCompleted = isEmergencyBypassCompleted
            )
        }
    }

    fun unlockActiveFortressDirect(onResult: (Boolean) -> Unit) {
        executeModeUnlock(isFortress = true, onResult = onResult) { app ->
            FortressSessionController.unlockActiveFortressDirectWhenAllowed(app)
        }
    }

    fun unlockActiveFortressWithQr(
        rawScannedToken: String,
        isEmergencyBypassCompleted: Boolean,
        onResult: (Boolean) -> Unit
    ) {
        executeModeUnlock(isFortress = true, onResult = onResult) { app ->
            if (isEmergencyBypassCompleted) {
                FortressSessionController.unlockActiveFortressViaEmergencyBypass(app)
            } else {
                FortressSessionController.unlockActiveFortressWithQrToken(
                    context = app,
                    rawScannedToken = rawScannedToken,
                    isEmergencyBypassCompleted = false
                )
            }
        }
    }

    private fun executeModeUnlock(
        isFortress: Boolean,
        onResult: (Boolean) -> Unit,
        action: (Application) -> Boolean
    ) = viewModelScope.launch(Dispatchers.IO) {
        val app = getApplication<Application>()
        val unlocked = try {
            action(app).also { ok ->
                if (ok) {
                    if (isFortress) {
                        CurfewScheduler.cancelFortressEndAlarm(app)
                    }
                    CurfewEnforcer.syncBlockState(app)
                    CurfewScheduler.scheduleNextAlarms(app)
                }
            }
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed unlocking active mode (isFortress=%b)", isFortress)
            false
        }
        val snapshot = EffectiveRestrictionResolver.getActiveModesSnapshot(
            context = app,
            policy = policyRepository.getCurrentPolicy()
        )
        withContext(Dispatchers.Main) {
            _uiState.update { it.copy(activeModesSnapshot = snapshot) }
            onResult(unlocked)
        }
    }

    fun loadPendingApprovals() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val pending = approvalRepository.getActualLocalPendingApprovals()
                withContext(Dispatchers.Main) {
                    _uiState.update { it.copy(pendingApprovalsCount = pending.size) }
                }
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Error fetching pending approvals")
            }
        }
    }

    fun setQrLoading(isLoading: Boolean) {
        _uiState.update { it.copy(isQrLoading = isLoading) }
    }

    companion object {
        private const val TAG = "AntiPorn_SelfCtrlVM"
    }
}
