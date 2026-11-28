package com.drmohammed.antiporn.waqfa.ui.settings

import android.app.Application
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.drmohammed.antiporn.accessibility.HisnAccessibilityService
import com.drmohammed.antiporn.accessibility.bus.ForegroundAppEventBus
import com.drmohammed.antiporn.accessibility.control.AccessibilityPolicyCoordinator
import com.drmohammed.antiporn.accessibility.control.AccessibilityReconcileResult
import com.drmohammed.antiporn.accessibility.control.AccessibilitySelfActivator
import com.drmohammed.antiporn.core.model.DevicePolicyBundle
import com.drmohammed.antiporn.curfew.CurfewForegroundWatcher
import com.drmohammed.antiporn.data.repository.PolicyRepository
import com.drmohammed.antiporn.waqfa.engine.WaqfaSessionManager
import com.drmohammed.antiporn.waqfa.model.WaqfaConfig
import com.drmohammed.antiporn.waqfa.model.WaqfaTimeoutReactionType
import kotlinx.coroutines.CancellationException
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

sealed class WaqfaSettingsEvent {
    data class SaveSucceeded(
        val updatedPolicy: DevicePolicyBundle?,
        val reconcileResult: AccessibilityReconcileResult?,
        val pendingUsageAccessPrompt: Boolean,
        val isRemote: Boolean
    ) : WaqfaSettingsEvent()

    data object SaveFailed : WaqfaSettingsEvent()
}

enum class WaqfaEngineType {
    INSTANT_0MS,
    USAGE_STATS_FALLBACK,
    DORMANT
}

data class WaqfaSettingsUiState(
    val config: WaqfaConfig = WaqfaConfig(),
    val isRemote: Boolean = false,
    val wardId: String? = null,
    val isLoading: Boolean = false,
    val isSaved: Boolean = false,
    val errorMessage: String? = null,
    val engineType: WaqfaEngineType = WaqfaEngineType.DORMANT,
    val showPermissionDialog: Boolean = false,
    val isAutoUpgradedByOtherFeature: Boolean = false,
    val hasUsageStatsPermission: Boolean = false,
    val isAccessibilityActive: Boolean = false,
    val wardHasAccessibilityService: Boolean? = null,
    val wardHasUsageStats: Boolean? = null,
    val wardCanWriteSecureSettings: Boolean? = null,
    val pendingManualActivation: AccessibilityReconcileResult.NeedsManualActivation? = null,
    val pendingUsageAccessPrompt: Boolean = false
)

class WaqfaSettingsViewModel(
    application: Application,
    private val policyRepository: PolicyRepository = PolicyRepository.getInstance(application),
    private val sessionManager: WaqfaSessionManager = WaqfaSessionManager.getInstance(application)
) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(WaqfaSettingsUiState())
    val uiState: StateFlow<WaqfaSettingsUiState> = _uiState.asStateFlow()

    private val _events = Channel<WaqfaSettingsEvent>(Channel.BUFFERED)
    val events: Flow<WaqfaSettingsEvent> = _events.receiveAsFlow()

    private var initialPolicy: DevicePolicyBundle? = null
    private var isInitialized = false

    fun initialize(isRemote: Boolean, wardId: String?, policyJson: String?) {
        if (isInitialized) {
            refreshEngineStatus()
            return
        }
        isInitialized = true
        val policy = if (!policyJson.isNullOrBlank()) {
            try {
                DevicePolicyBundle.fromJson(policyJson)
            } catch (e: Exception) {
                Timber.tag(TAG).w(e, "Failed parsing remote policyJson for Waqfa, falling back to local policy")
                policyRepository.getCurrentPolicy()
            }
        } else {
            policyRepository.getCurrentPolicy()
        }

        initialPolicy = policy
        val autoUpgraded = computeAutoUpgradedByOtherFeature(policy)

        _uiState.update {
            it.copy(
                config = policy.waqfaConfig,
                isRemote = isRemote,
                wardId = wardId,
                isAutoUpgradedByOtherFeature = autoUpgraded,
                wardHasAccessibilityService = policy.wardHasAccessibilityService,
                wardHasUsageStats = policy.wardHasUsageStats,
                wardCanWriteSecureSettings = policy.wardCanWriteSecureSettings
            )
        }

        refreshEngineStatus()
    }

    fun refreshEngineStatus() {
        val currentState = _uiState.value
        if (currentState.isRemote) {
            val remoteEngine = when {
                currentState.wardHasAccessibilityService == true -> WaqfaEngineType.INSTANT_0MS
                currentState.wardHasUsageStats == true -> WaqfaEngineType.USAGE_STATS_FALLBACK
                else -> WaqfaEngineType.DORMANT
            }
            _uiState.update { it.copy(engineType = remoteEngine) }
            return
        }

        val app = getApplication<Application>()
        val latestPolicy = try {
            policyRepository.getCurrentPolicy()
        } catch (_: Throwable) {
            initialPolicy
        }
        val autoUpgraded = computeAutoUpgradedByOtherFeature(latestPolicy)

        val a11yActive = HisnAccessibilityService.instance != null ||
            ForegroundAppEventBus.getInstance(app).isAccessibilityActive.value ||
            AccessibilitySelfActivator.isServiceEnabledInSettings(app)

        val hasUsage = CurfewForegroundWatcher.hasUsageStatsPermission(app)
        val useInstant = currentState.config.useInstantAccessibilityMode

        val engine = when {
            autoUpgraded && a11yActive -> WaqfaEngineType.INSTANT_0MS
            useInstant -> if (a11yActive) WaqfaEngineType.INSTANT_0MS else WaqfaEngineType.DORMANT
            else -> when {
                a11yActive -> WaqfaEngineType.INSTANT_0MS
                hasUsage -> WaqfaEngineType.USAGE_STATS_FALLBACK
                else -> WaqfaEngineType.DORMANT
            }
        }

        _uiState.update {
            it.copy(
                engineType = engine,
                isAutoUpgradedByOtherFeature = autoUpgraded,
                hasUsageStatsPermission = hasUsage,
                isAccessibilityActive = a11yActive
            )
        }
    }

    fun setWaqfaEnabled(enabled: Boolean) {
        _uiState.update { it.copy(config = it.config.copy(isEnabled = enabled)) }
        refreshEngineStatus()
    }

    fun setUseInstantAccessibilityMode(enabled: Boolean) {
        _uiState.update { it.copy(config = it.config.copy(useInstantAccessibilityMode = enabled)) }
        refreshEngineStatus()
    }

    fun dismissPermissionDialog() {
        _uiState.update { it.copy(showPermissionDialog = false) }
    }

    fun clearPendingPrompts() {
        _uiState.update {
            it.copy(
                pendingManualActivation = null,
                pendingUsageAccessPrompt = false
            )
        }
    }

    fun setTargetPackages(packages: Set<String>) {
        _uiState.update { it.copy(config = it.config.copy(targetPackages = packages)) }
    }

    fun setReasonRequired(required: Boolean) {
        _uiState.update { it.copy(config = it.config.copy(isReasonRequired = required)) }
    }

    fun setTimeoutReactionType(type: WaqfaTimeoutReactionType) {
        _uiState.update { it.copy(config = it.config.copy(timeoutReactionType = type)) }
    }

    fun setCooldownMinutes(minutes: Int) {
        val valid = minutes.coerceAtLeast(1)
        _uiState.update { it.copy(config = it.config.copy(cooldownMinutes = valid)) }
    }

    fun setAllowOpenSession(allow: Boolean) {
        _uiState.update { it.copy(config = it.config.copy(allowOpenSession = allow)) }
    }

    fun setAllowCustomDuration(allow: Boolean) {
        _uiState.update { it.copy(config = it.config.copy(allowCustomDuration = allow)) }
    }

    fun addQuickDuration(minutes: Int) {
        val valid = minutes.coerceIn(1, 180)
        val currentList = _uiState.value.config.quickDurationsMinutes.toMutableList()
        if (!currentList.contains(valid)) {
            currentList.add(valid)
            currentList.sort()
            _uiState.update { it.copy(config = it.config.copy(quickDurationsMinutes = currentList)) }
        }
    }

    fun removeQuickDuration(minutes: Int): Boolean {
        val currentList = _uiState.value.config.quickDurationsMinutes.toMutableList()
        if (currentList.size <= 1) {
            return false // Cannot remove the last remaining duration
        }
        val removed = currentList.remove(minutes)
        if (removed) {
            _uiState.update { it.copy(config = it.config.copy(quickDurationsMinutes = currentList)) }
        }
        return removed
    }

    /**
     * واجهة متوافقة مع الإصدارات السابقة لحفظ الإعدادات.
     */
    fun saveSettings(onComplete: (Boolean, DevicePolicyBundle?) -> Unit) {
        saveSettings { success, updatedPolicy, _ ->
            onComplete(success, updatedPolicy)
        }
    }

    fun saveSettings(onComplete: ((Boolean, DevicePolicyBundle?, AccessibilityReconcileResult?) -> Unit)? = null) {
        if (_uiState.value.isLoading) {
            onComplete?.invoke(false, null, null)
            return
        }
        val state = _uiState.value
        _uiState.update {
            it.copy(
                isLoading = true,
                errorMessage = null,
                pendingManualActivation = null,
                pendingUsageAccessPrompt = false
            )
        }

        viewModelScope.launch {
            try {
                if (state.isRemote) {
                    val (success, updated) = withContext(Dispatchers.IO + NonCancellable) {
                        val base = initialPolicy ?: policyRepository.getCurrentPolicy()
                        val updatedPolicy = base.copy(waqfaConfig = state.config)
                        val ward = state.wardId
                        val pushed = if (!ward.isNullOrBlank()) {
                            policyRepository.pushPolicyToWard(ward, updatedPolicy)
                        } else {
                            true
                        }
                        pushed to updatedPolicy
                    }

                    _uiState.update { it.copy(isLoading = false, isSaved = success) }
                    if (success) {
                        _events.send(
                            WaqfaSettingsEvent.SaveSucceeded(
                                updatedPolicy = updated,
                                reconcileResult = null,
                                pendingUsageAccessPrompt = false,
                                isRemote = true
                            )
                        )
                    } else {
                        _events.send(WaqfaSettingsEvent.SaveFailed)
                    }
                    onComplete?.invoke(success, updated, null)
                } else {
                    val app = getApplication<Application>()
                    val resultTriple = withContext(Dispatchers.IO + NonCancellable) {
                        val wasA11yActiveBeforeSave = HisnAccessibilityService.instance != null ||
                            AccessibilitySelfActivator.isServiceEnabledInSettings(app)

                        val current = policyRepository.getCurrentPolicy()
                        val updatedPolicy = current.copy(waqfaConfig = state.config)
                        val success = policyRepository.saveAndEnforceSelfPolicy(updatedPolicy)
                        var reconcileResult: AccessibilityReconcileResult? = null
                        if (success) {
                            sessionManager.updateConfig(state.config)
                            val rawReconcile = AccessibilityPolicyCoordinator.reconcile(app, updatedPolicy)
                            reconcileResult = if (!wasA11yActiveBeforeSave && rawReconcile is AccessibilityReconcileResult.AlreadyActive) {
                                AccessibilityReconcileResult.EnabledSilently
                            } else {
                                rawReconcile
                            }
                        }
                        Triple(success, updatedPolicy, reconcileResult)
                    }

                    val (success, updatedPolicy, reconcileResult) = resultTriple
                    if (success) {
                        refreshEngineStatus()

                        val autoUpgraded = computeAutoUpgradedByOtherFeature(updatedPolicy)
                        val hasUsage = CurfewForegroundWatcher.hasUsageStatsPermission(app)
                        val isA11yNowActive = _uiState.value.isAccessibilityActive
                        val needsUsagePrompt = !updatedPolicy.waqfaConfig.useInstantAccessibilityMode &&
                            updatedPolicy.waqfaConfig.isEnabled &&
                            updatedPolicy.waqfaConfig.targetPackages.isNotEmpty() &&
                            !autoUpgraded &&
                            !hasUsage &&
                            !isA11yNowActive
                        val manualActivation = reconcileResult as? AccessibilityReconcileResult.NeedsManualActivation

                        _uiState.update {
                            it.copy(
                                isLoading = false,
                                isSaved = true,
                                pendingManualActivation = manualActivation,
                                pendingUsageAccessPrompt = needsUsagePrompt
                            )
                        }
                        _events.send(
                            WaqfaSettingsEvent.SaveSucceeded(
                                updatedPolicy = updatedPolicy,
                                reconcileResult = reconcileResult,
                                pendingUsageAccessPrompt = needsUsagePrompt,
                                isRemote = false
                            )
                        )
                    } else {
                        val failedMsg = app.getString(com.drmohammed.antiporn.R.string.waqfa_settings_save_failed_toast)
                        _uiState.update { it.copy(isLoading = false, errorMessage = failedMsg) }
                        _events.send(WaqfaSettingsEvent.SaveFailed)
                    }
                    onComplete?.invoke(success, updatedPolicy, reconcileResult)
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Timber.tag(TAG).e(e, "Error saving waqfa settings")
                _uiState.update { it.copy(isLoading = false, errorMessage = e.message) }
                _events.send(WaqfaSettingsEvent.SaveFailed)
                onComplete?.invoke(false, null, null)
            }
        }
    }

    private fun computeAutoUpgradedByOtherFeature(policy: DevicePolicyBundle?): Boolean {
        if (policy == null) return false
        val pageShieldActive = policy.pageShieldConfig.isEnabled &&
            policy.pageShieldConfig.getTargetPackages().isNotEmpty()
        val visionAiApi34Active = policy.isScreenAiEnabledGlobally &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE
        return pageShieldActive || visionAiApi34Active
    }

    companion object {
        private const val TAG = "AntiPorn_WaqfaSettingsVM"
    }
}
