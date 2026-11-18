package com.drmohammed.antiporn.pageshield.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.drmohammed.antiporn.R
import com.drmohammed.antiporn.accessibility.HisnAccessibilityService
import com.drmohammed.antiporn.accessibility.control.AccessibilityPolicyCoordinator
import com.drmohammed.antiporn.accessibility.control.AccessibilityReconcileResult
import com.drmohammed.antiporn.accessibility.control.AccessibilitySelfActivator
import com.drmohammed.antiporn.core.model.DevicePolicyBundle
import com.drmohammed.antiporn.data.repository.PolicyRepository
import com.drmohammed.antiporn.pageshield.engine.PageShieldAccessibilityHook
import com.drmohammed.antiporn.pageshield.model.AppPageShieldPolicy
import com.drmohammed.antiporn.pageshield.model.PageShieldConfig
import com.drmohammed.antiporn.pageshield.model.PageShieldReaction
import com.drmohammed.antiporn.pageshield.registry.PageShieldRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

data class PageShieldSettingsUiState(
    val config: PageShieldConfig = PageShieldConfig(),
    val isRemote: Boolean = false,
    val wardId: String? = null,
    val isLoading: Boolean = false,
    val isSaved: Boolean = false,
    val errorMessage: String? = null,
    val expandedPackages: Set<String> = emptySet(),
    val isAccessibilityActive: Boolean = false,
    val canWriteSecureSettings: Boolean = false,
    val wardHasAccessibilityService: Boolean? = null,
    val wardCanWriteSecureSettings: Boolean? = null,
    val lastReconcileResult: AccessibilityReconcileResult? = null
)

class PageShieldSettingsViewModel(
    application: Application,
    private val policyRepository: PolicyRepository = PolicyRepository.getInstance(application)
) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(PageShieldSettingsUiState())
    val uiState: StateFlow<PageShieldSettingsUiState> = _uiState.asStateFlow()

    private var initialPolicy: DevicePolicyBundle? = null

    fun initialize(isRemote: Boolean, wardId: String?, policyJson: String?) {
        val policy = if (!policyJson.isNullOrBlank()) {
            try {
                DevicePolicyBundle.fromJson(policyJson)
            } catch (e: Exception) {
                Timber.tag(TAG).w(e, "Failed parsing remote policyJson for PageShield; using local")
                policyRepository.getCurrentPolicy()
            }
        } else {
            policyRepository.getCurrentPolicy()
        }

        initialPolicy = policy
        val config = policy.pageShieldConfig

        // Ensure default policies exist for all registered apps in the UI state
        val mergedPolicies = config.appPolicies.toMutableMap()
        for (app in PageShieldRegistry.registeredApps) {
            if (!mergedPolicies.containsKey(app.packageName)) {
                mergedPolicies[app.packageName] = AppPageShieldPolicy(packageName = app.packageName)
            }
        }
        val preparedConfig = config.copy(appPolicies = mergedPolicies)

        // Expand all registered apps by default so the user sees all available protections
        val allPackages = PageShieldRegistry.registeredApps.map { it.packageName }.toSet()

        _uiState.update {
            it.copy(
                config = preparedConfig,
                isRemote = isRemote,
                wardId = wardId,
                expandedPackages = allPackages,
                wardHasAccessibilityService = policy.wardHasAccessibilityService,
                wardCanWriteSecureSettings = policy.wardCanWriteSecureSettings
            )
        }

        if (!isRemote) {
            refreshEngineStatus()
        }
    }

    fun refreshEngineStatus() {
        if (_uiState.value.isRemote) return
        val app = getApplication<Application>()
        val isActive = HisnAccessibilityService.instance != null ||
            AccessibilitySelfActivator.isServiceEnabledInSettings(app)
        val canWrite = AccessibilitySelfActivator.canWriteSecureSettings(app)

        _uiState.update {
            it.copy(
                isAccessibilityActive = isActive,
                canWriteSecureSettings = canWrite
            )
        }
    }

    fun setMasterEnabled(enabled: Boolean) {
        _uiState.update {
            it.copy(config = it.config.copy(isEnabled = enabled))
        }
    }

    fun toggleAppExpanded(packageName: String) {
        _uiState.update { current ->
            val updated = if (current.expandedPackages.contains(packageName)) {
                current.expandedPackages - packageName
            } else {
                current.expandedPackages + packageName
            }
            current.copy(expandedPackages = updated)
        }
    }

    fun setPageEnabled(packageName: String, pageId: String, enabled: Boolean) {
        _uiState.update { current ->
            val currentPolicies = current.config.appPolicies.toMutableMap()
            val currentAppPolicy = currentPolicies[packageName] ?: AppPageShieldPolicy(packageName = packageName)

            val updatedPages = if (enabled) {
                currentAppPolicy.enabledPageIds + pageId
            } else {
                currentAppPolicy.enabledPageIds - pageId
            }

            currentPolicies[packageName] = currentAppPolicy.copy(enabledPageIds = updatedPages)
            current.copy(config = current.config.copy(appPolicies = currentPolicies))
        }
    }

    fun setReaction(packageName: String, reaction: PageShieldReaction) {
        _uiState.update { current ->
            val currentPolicies = current.config.appPolicies.toMutableMap()
            val currentAppPolicy = currentPolicies[packageName] ?: AppPageShieldPolicy(packageName = packageName)
            currentPolicies[packageName] = currentAppPolicy.copy(reaction = reaction)
            current.copy(config = current.config.copy(appPolicies = currentPolicies))
        }
    }

    fun setFreezeDurationMinutes(packageName: String, minutes: Int) {
        val coerced = minutes.coerceIn(AppPageShieldPolicy.MIN_FREEZE_MINUTES, AppPageShieldPolicy.MAX_FREEZE_MINUTES)
        _uiState.update { current ->
            val currentPolicies = current.config.appPolicies.toMutableMap()
            val currentAppPolicy = currentPolicies[packageName] ?: AppPageShieldPolicy(packageName = packageName)
            currentPolicies[packageName] = currentAppPolicy.copy(freezeDurationMinutes = coerced)
            current.copy(config = current.config.copy(appPolicies = currentPolicies))
        }
    }

    /**
     * واجهة متوافقة مع الإصدارات السابقة لحفظ الإعدادات.
     */
    fun saveSettings(onComplete: (Boolean, DevicePolicyBundle?) -> Unit) {
        saveSettings { success, updatedPolicy, _ ->
            onComplete(success, updatedPolicy)
        }
    }

    fun saveSettings(onComplete: (Boolean, DevicePolicyBundle?, AccessibilityReconcileResult?) -> Unit) {
        if (_uiState.value.isLoading) {
            onComplete(false, null, null)
            return
        }
        val state = _uiState.value
        _uiState.update { it.copy(isLoading = true, errorMessage = null, lastReconcileResult = null) }

        viewModelScope.launch {
            try {
                if (state.isRemote) {
                    val base = initialPolicy ?: policyRepository.getCurrentPolicy()
                    val updated = base.copy(pageShieldConfig = state.config)
                    val ward = state.wardId
                    val success = if (!ward.isNullOrBlank()) {
                        policyRepository.pushPolicyToWard(ward, updated)
                    } else {
                        true
                    }

                    _uiState.update { it.copy(isLoading = false, isSaved = success) }
                    onComplete(success, updated, null)
                } else {
                    val app = getApplication<Application>()
                    val wasA11yActiveBeforeSave = HisnAccessibilityService.instance != null ||
                        withContext(Dispatchers.IO) {
                            AccessibilitySelfActivator.isServiceEnabledInSettings(app)
                        }

                    val current = policyRepository.getCurrentPolicy()
                    val updatedPolicy = current.copy(pageShieldConfig = state.config)
                    val success = policyRepository.saveAndEnforceSelfPolicy(updatedPolicy)

                    var reconcileResult: AccessibilityReconcileResult? = null
                    if (success) {
                        PageShieldAccessibilityHook.getInstance().updateConfig(state.config)
                        val rawReconcile = withContext(Dispatchers.IO) {
                            AccessibilityPolicyCoordinator.reconcile(app, updatedPolicy)
                        }
                        reconcileResult = if (!wasA11yActiveBeforeSave && rawReconcile is AccessibilityReconcileResult.AlreadyActive) {
                            AccessibilityReconcileResult.EnabledSilently
                        } else {
                            rawReconcile
                        }
                        refreshEngineStatus()
                        _uiState.update {
                            it.copy(
                                isLoading = false,
                                isSaved = true,
                                lastReconcileResult = reconcileResult
                            )
                        }
                    } else {
                        val failedMsg = app.getString(R.string.page_shield_save_failed_toast)
                        _uiState.update { it.copy(isLoading = false, errorMessage = failedMsg) }
                    }
                    onComplete(success, updatedPolicy, reconcileResult)
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Timber.tag(TAG).e(e, "Error saving PageShield settings")
                _uiState.update { it.copy(isLoading = false, errorMessage = e.message) }
                onComplete(false, null, null)
            }
        }
    }

    companion object {
        private const val TAG = "AntiPorn_PageShieldVM"
    }
}
