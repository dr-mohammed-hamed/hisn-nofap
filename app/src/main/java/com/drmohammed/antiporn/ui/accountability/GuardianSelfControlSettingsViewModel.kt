package com.drmohammed.antiporn.ui.accountability

import android.app.Application
import android.os.Bundle
import androidx.annotation.VisibleForTesting
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.drmohammed.antiporn.R
import com.drmohammed.antiporn.core.model.DevicePolicyBundle
import com.drmohammed.antiporn.core.time.TrustedTimeProvider
import com.drmohammed.antiporn.data.repository.PolicyRepository
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.util.concurrent.atomic.AtomicInteger

data class GuardianSelfControlUiState(
    val wardId: String = "",
    val currentPolicy: DevicePolicyBundle = DevicePolicyBundle.defaultPolicy(),
    val isPushing: Boolean = false
)

sealed class GuardianSelfControlEvent {
    data class ShowToastRes(val resId: Int) : GuardianSelfControlEvent()
    data object RevertSwitchesToPolicy : GuardianSelfControlEvent()
}

/**
 * ViewModel managing supervisor-configured granular self-control permissions
 * and remote QR reset requests for a ward, surviving rotation and serializing pushes.
 */
class GuardianSelfControlSettingsViewModel(
    application: Application,
    private val policyRepository: PolicyRepository
) : AndroidViewModel(application) {

    @VisibleForTesting
    internal var ioDispatcher: CoroutineDispatcher = Dispatchers.IO

    @VisibleForTesting
    internal var coroutineScopeOverride: CoroutineScope? = null

    @VisibleForTesting
    internal var pushPolicyAction: suspend (String, DevicePolicyBundle) -> Boolean = { wardId, bundle ->
        policyRepository.pushPolicyToWard(wardId, bundle)
    }

    @VisibleForTesting
    internal var nowProvider: () -> Long = {
        TrustedTimeProvider.currentTimeMillis(getApplication())
    }

    private val workScope: CoroutineScope
        get() = coroutineScopeOverride ?: viewModelScope

    private val _uiState = MutableStateFlow(GuardianSelfControlUiState())
    val uiState: StateFlow<GuardianSelfControlUiState> = _uiState.asStateFlow()

    private val _events = Channel<GuardianSelfControlEvent>(Channel.BUFFERED)
    val events: Flow<GuardianSelfControlEvent> = _events.receiveAsFlow()

    private val pushMutex = Mutex()
    private val pendingPushCount = AtomicInteger(0)
    private var isInitialized = false
    private var isRestoredFromSavedState = false

    fun initialize(wardId: String, initialPolicy: DevicePolicyBundle): Boolean {
        if (isInitialized) return false
        isInitialized = true
        _uiState.value = GuardianSelfControlUiState(
            wardId = wardId,
            currentPolicy = initialPolicy,
            isPushing = false
        )
        return true
    }

    fun restoreFromSavedState(state: Bundle) {
        if (isRestoredFromSavedState) return
        isRestoredFromSavedState = true
        restorePolicyState(
            savedWardId = state.getString(STATE_WARD_ID),
            savedPolicyJson = state.getString(STATE_POLICY_JSON)
        )
    }

    @VisibleForTesting
    internal fun restorePolicyState(savedWardId: String?, savedPolicyJson: String?) {
        val parsed = if (!savedPolicyJson.isNullOrBlank()) {
            DevicePolicyBundle.fromJsonOrNull(savedPolicyJson)
        } else {
            null
        }
        _uiState.update { current ->
            val restoredPolicy = if (parsed != null && parsed.version >= current.currentPolicy.version) {
                parsed
            } else {
                current.currentPolicy
            }
            current.copy(
                wardId = savedWardId?.takeIf { it.isNotBlank() } ?: current.wardId,
                currentPolicy = restoredPolicy
            )
        }
    }

    fun writeToSavedState(outState: Bundle) {
        val state = _uiState.value
        outState.putString(STATE_WARD_ID, state.wardId)
        outState.putString(STATE_POLICY_JSON, state.currentPolicy.toJson())
    }

    fun pushPolicyChange(updater: (DevicePolicyBundle) -> DevicePolicyBundle) {
        val currentWardId = _uiState.value.wardId
        if (currentWardId.isBlank()) return

        pendingPushCount.incrementAndGet()
        _uiState.update { it.copy(isPushing = true) }

        workScope.launch(ioDispatcher) {
            try {
                pushMutex.withLock {
                    val basePolicy = _uiState.value.currentPolicy
                    val updated = updater(basePolicy).copy(
                        version = basePolicy.version + 1,
                        updatedAt = nowProvider()
                    )
                    val success = withContext(NonCancellable) {
                        pushPolicyAction(currentWardId, updated)
                    }
                    if (success) {
                        _uiState.update { it.copy(currentPolicy = updated) }
                        _events.send(GuardianSelfControlEvent.ShowToastRes(R.string.toast_guardian_permissions_saved))
                    } else {
                        _events.send(GuardianSelfControlEvent.RevertSwitchesToPolicy)
                        _events.send(GuardianSelfControlEvent.ShowToastRes(R.string.toast_guardian_policy_save_failed))
                    }
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Timber.tag(TAG).e(e, "Failed pushing self-control policy change")
                _events.send(GuardianSelfControlEvent.RevertSwitchesToPolicy)
                _events.send(GuardianSelfControlEvent.ShowToastRes(R.string.toast_guardian_policy_save_failed))
            } finally {
                val remaining = pendingPushCount.decrementAndGet().coerceAtLeast(0)
                _uiState.update { it.copy(isPushing = remaining > 0) }
            }
        }
    }

    fun executeRemoteQrReset() {
        val currentWardId = _uiState.value.wardId
        if (currentWardId.isBlank()) return

        pendingPushCount.incrementAndGet()
        _uiState.update { it.copy(isPushing = true) }

        workScope.launch(ioDispatcher) {
            try {
                pushMutex.withLock {
                    val basePolicy = _uiState.value.currentPolicy
                    val now = nowProvider()
                    val updated = basePolicy.copy(
                        remoteSelfControlResetRequestedAt = now,
                        version = basePolicy.version + 1,
                        updatedAt = now
                    )
                    val success = withContext(NonCancellable) {
                        pushPolicyAction(currentWardId, updated)
                    }
                    if (success) {
                        _uiState.update { it.copy(currentPolicy = updated) }
                        _events.send(GuardianSelfControlEvent.ShowToastRes(R.string.toast_reset_ward_qr_success))
                    } else {
                        _events.send(GuardianSelfControlEvent.ShowToastRes(R.string.toast_reset_ward_qr_failed))
                    }
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Timber.tag(TAG).e(e, "Failed executing remote QR reset")
                _events.send(GuardianSelfControlEvent.ShowToastRes(R.string.toast_reset_ward_qr_failed))
            } finally {
                val remaining = pendingPushCount.decrementAndGet().coerceAtLeast(0)
                _uiState.update { it.copy(isPushing = remaining > 0) }
            }
        }
    }

    companion object {
        private const val TAG = "GuardianSelfControlVM"
        private const val STATE_WARD_ID = "vm_state_ward_id"
        private const val STATE_POLICY_JSON = "vm_state_policy_json"
    }
}
