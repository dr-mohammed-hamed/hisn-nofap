package com.drmohammed.antiporn.provisioning.ui

import android.app.Application
import androidx.annotation.VisibleForTesting
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.drmohammed.antiporn.provisioning.engine.BlockingSecondaryUser
import com.drmohammed.antiporn.provisioning.engine.DeviceOwnerPromoter
import com.drmohammed.antiporn.provisioning.engine.OemPermissionType
import com.drmohammed.antiporn.provisioning.engine.PromotionResult
import com.drmohammed.antiporn.provisioning.service.AdbPairingNotificationService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

data class ProvisioningWizardUiState(
    val discoveredPairingPort: Int = 0,
    val discoveredConnectPort: Int = 0,
    val isPromoting: Boolean = false,
    val isCleaningSecondaryUsers: Boolean = false,
    val pendingBlockingUsersJson: String? = null
)

sealed class ProvisioningWizardEvent {
    data object PromotionSucceeded : ProvisioningWizardEvent()
    data class SecondaryUsersBlocking(val users: List<BlockingSecondaryUser>) : ProvisioningWizardEvent()
    data class OemPermissionRequired(val oemType: OemPermissionType) : ProvisioningWizardEvent()
    data class AccountsBlocking(val payload: BlockingAccountsPayload) : ProvisioningWizardEvent()
    data class PromotionFailed(val message: String) : ProvisioningWizardEvent()
    data class UnexpectedError(val className: String, val message: String?) : ProvisioningWizardEvent()
}

/**
 * Holds wireless ADB discovery ports, pending blocking-user state, and promotion execution
 * in [viewModelScope] so configuration changes never cancel an active Device Owner promotion.
 */
class ProvisioningWizardViewModel(
    application: Application
) : AndroidViewModel(application) {

    @VisibleForTesting
    internal var ioDispatcher: CoroutineDispatcher = Dispatchers.IO

    @VisibleForTesting
    internal var coroutineScopeOverride: CoroutineScope? = null

    private val workScope: CoroutineScope
        get() = coroutineScopeOverride ?: viewModelScope

    @VisibleForTesting
    internal var pairAndPromoteAction: suspend (Int, Int, String) -> PromotionResult =
        { pairingPort, connectPort, code ->
            DeviceOwnerPromoter.pairAndPromote(getApplication(), pairingPort, connectPort, code)
        }

    @VisibleForTesting
    internal var removeSecondaryUsersAndPromoteAction: suspend (Int, Set<Int>) -> PromotionResult =
        { connectPort, approvedUserIds ->
            DeviceOwnerPromoter.removeSecondaryUsersAndPromote(
                context = getApplication(),
                connectPort = connectPort,
                approvedUserIds = approvedUserIds,
                explicitUserConsentConfirmed = true
            )
        }

    @VisibleForTesting
    internal var promoteIfAlreadyPairedAction: suspend (Int) -> PromotionResult = { connectPort ->
        DeviceOwnerPromoter.promoteIfAlreadyPaired(getApplication(), connectPort)
    }

    @VisibleForTesting
    internal var isPromotionActiveCheck: () -> Boolean = { DeviceOwnerPromoter.isPromotionActive }

    private val _uiState = MutableStateFlow(ProvisioningWizardUiState())
    val uiState: StateFlow<ProvisioningWizardUiState> = _uiState.asStateFlow()

    private val _events = Channel<ProvisioningWizardEvent>(Channel.BUFFERED)
    val events: Flow<ProvisioningWizardEvent> = _events.receiveAsFlow()

    fun updateDiscoveredPorts(pairingPort: Int, connectPort: Int) {
        _uiState.update { current ->
            current.copy(
                discoveredPairingPort = pairingPort,
                discoveredConnectPort = if (connectPort > 0) connectPort else current.discoveredConnectPort
            )
        }
    }

    fun updateConnectPort(connectPort: Int) {
        if (connectPort <= 0) return
        _uiState.update { it.copy(discoveredConnectPort = connectPort) }
    }

    fun setPendingBlockingUsersJson(json: String?) {
        _uiState.update { it.copy(pendingBlockingUsersJson = json) }
    }

    fun onExternalBroadcastFailure(blockingUsers: List<BlockingSecondaryUser>, errorMessage: String?) {
        val state = _uiState.value
        if (blockingUsers.isEmpty() && state.isPromoting && isPromotionActiveCheck()) {
            Timber.tag(TAG).i("Ignoring concurrent failure broadcast while local promotion is active")
            return
        }
        _uiState.update { it.copy(isPromoting = false, isCleaningSecondaryUsers = false) }
        workScope.launch {
            if (blockingUsers.isNotEmpty()) {
                _uiState.update { it.copy(pendingBlockingUsersJson = BlockingSecondaryUser.listToJson(blockingUsers)) }
                _events.send(ProvisioningWizardEvent.SecondaryUsersBlocking(blockingUsers))
            } else {
                _events.send(ProvisioningWizardEvent.PromotionFailed(errorMessage.orEmpty()))
            }
        }
    }

    /**
     * main: handles external failure broadcasts that carry an OEM-permission or account-blocking
     * outcome. The decision stays in the ViewModel (rotation-safe); the Activity only renders the
     * corresponding dialog.
     */
    fun onExternalBroadcastFailureWithOutcome(
        blockingUsers: List<BlockingSecondaryUser>,
        oemType: OemPermissionType?,
        accountsPayload: BlockingAccountsPayload?,
        errorMessage: String?
    ) {
        val state = _uiState.value
        val hasOutcome = blockingUsers.isNotEmpty() || oemType != null || accountsPayload != null
        if (!hasOutcome && state.isPromoting && isPromotionActiveCheck()) {
            Timber.tag(TAG).i("Ignoring concurrent failure broadcast while local promotion is active")
            return
        }
        _uiState.update { it.copy(isPromoting = false, isCleaningSecondaryUsers = false) }
        workScope.launch {
            when {
                blockingUsers.isNotEmpty() -> {
                    _uiState.update {
                        it.copy(pendingBlockingUsersJson = BlockingSecondaryUser.listToJson(blockingUsers))
                    }
                    _events.send(ProvisioningWizardEvent.SecondaryUsersBlocking(blockingUsers))
                }
                oemType != null -> _events.send(ProvisioningWizardEvent.OemPermissionRequired(oemType))
                accountsPayload != null -> _events.send(ProvisioningWizardEvent.AccountsBlocking(accountsPayload))
                else -> _events.send(ProvisioningWizardEvent.PromotionFailed(errorMessage.orEmpty()))
            }
        }
    }

    /**
     * main: retry a promotion on a device that is already paired, routed through the ViewModel so
     * an active promotion survives rotation.
     */
    fun retryAlreadyPaired() {
        if (_uiState.value.isPromoting || isPromotionActiveCheck()) return
        val connectPort = _uiState.value.discoveredConnectPort.takeIf { it > 0 }
            ?: AdbPairingNotificationService.capturedConnectPort
        _uiState.update { it.copy(isPromoting = true, isCleaningSecondaryUsers = false) }
        workScope.launch {
            try {
                val result = withContext(ioDispatcher + NonCancellable) {
                    promoteIfAlreadyPairedAction(connectPort)
                }
                handlePromotionResult(result)
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                Timber.tag(TAG).e(t, "Unexpected error during already-paired retry")
                _events.send(ProvisioningWizardEvent.UnexpectedError(t.javaClass.simpleName, t.message))
            } finally {
                _uiState.update { it.copy(isPromoting = false, isCleaningSecondaryUsers = false) }
            }
        }
    }

    fun startPairingAndPromotion(code: String) {
        val state = _uiState.value
        if (state.isPromoting || isPromotionActiveCheck()) return

        _uiState.update { it.copy(isPromoting = true, isCleaningSecondaryUsers = false) }
        val pairingPort = state.discoveredPairingPort
        val connectPort = state.discoveredConnectPort

        workScope.launch {
            try {
                val result = withContext(ioDispatcher + NonCancellable) {
                    pairAndPromoteAction(pairingPort, connectPort, code)
                }
                handlePromotionResult(result)
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                Timber.tag(TAG).e(t, "Unexpected error in wizard activation")
                _events.send(ProvisioningWizardEvent.UnexpectedError(t.javaClass.simpleName, t.message))
            } finally {
                _uiState.update { it.copy(isPromoting = false, isCleaningSecondaryUsers = false) }
            }
        }
    }

    fun startRemoveSecondaryUsersAndPromote(approvedUserIds: Set<Int>) {
        val state = _uiState.value
        if (state.isPromoting || isPromotionActiveCheck()) return

        _uiState.update {
            it.copy(
                isPromoting = true,
                isCleaningSecondaryUsers = true,
                pendingBlockingUsersJson = null
            )
        }
        val effectiveConnectPort = state.discoveredConnectPort.takeIf { it > 0 }
            ?: AdbPairingNotificationService.capturedConnectPort

        workScope.launch {
            try {
                val result = withContext(ioDispatcher + NonCancellable) {
                    removeSecondaryUsersAndPromoteAction(effectiveConnectPort, approvedUserIds)
                }
                handlePromotionResult(result)
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                Timber.tag(TAG).e(t, "Unexpected error during secondary user removal and activation")
                _events.send(ProvisioningWizardEvent.UnexpectedError(t.javaClass.simpleName, t.message))
            } finally {
                _uiState.update { it.copy(isPromoting = false, isCleaningSecondaryUsers = false) }
            }
        }
    }

    private suspend fun handlePromotionResult(result: PromotionResult) {
        when (result) {
            is PromotionResult.Success -> {
                _uiState.update { it.copy(pendingBlockingUsersJson = null) }
                _events.send(ProvisioningWizardEvent.PromotionSucceeded)
            }
            is PromotionResult.SecondaryUsersBlocking -> {
                _uiState.update {
                    it.copy(pendingBlockingUsersJson = BlockingSecondaryUser.listToJson(result.users))
                }
                _events.send(ProvisioningWizardEvent.SecondaryUsersBlocking(result.users))
            }
            is PromotionResult.OemPermissionRequired -> {
                _events.send(ProvisioningWizardEvent.OemPermissionRequired(result.oemType))
            }
            is PromotionResult.AccountsBlocking -> {
                _events.send(
                    ProvisioningWizardEvent.AccountsBlocking(
                        BlockingAccountsPayload(result.accountLabels, result.accountTypes, result.message)
                    )
                )
            }
            is PromotionResult.Failure -> {
                _events.send(ProvisioningWizardEvent.PromotionFailed(result.message))
            }
        }
    }

    companion object {
        private const val TAG = "AntiPorn_WizardVM"
    }
}
