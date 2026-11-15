package com.drmohammed.antiporn.pageshield.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.drmohammed.antiporn.R
import com.drmohammed.antiporn.core.model.DevicePolicyBundle
import com.drmohammed.antiporn.data.repository.PolicyRepository
import com.drmohammed.antiporn.pageshield.engine.PageShieldAccessibilityHook
import com.drmohammed.antiporn.pageshield.model.AppPageShieldPolicy
import com.drmohammed.antiporn.pageshield.model.PageShieldConfig
import com.drmohammed.antiporn.pageshield.model.PageShieldReaction
import com.drmohammed.antiporn.pageshield.registry.PageShieldRegistry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber

data class PageShieldSettingsUiState(
    val config: PageShieldConfig = PageShieldConfig(),
    val isRemote: Boolean = false,
    val wardId: String? = null,
    val isLoading: Boolean = false,
    val isSaved: Boolean = false,
    val errorMessage: String? = null,
    val isAppExpanded: Boolean = true,
    val freezeDurationMinutes: Int = AppPageShieldPolicy.DEFAULT_FREEZE_MINUTES
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
        val ytPolicy = config.appPolicies[PageShieldRegistry.PACKAGE_YOUTUBE]
            ?: AppPageShieldPolicy(packageName = PageShieldRegistry.PACKAGE_YOUTUBE)

        _uiState.update {
            it.copy(
                config = config,
                isRemote = isRemote,
                wardId = wardId,
                freezeDurationMinutes = ytPolicy.freezeDurationMinutes
            )
        }
    }

    fun setMasterEnabled(enabled: Boolean) {
        _uiState.update {
            it.copy(config = it.config.copy(isEnabled = enabled))
        }
    }

    fun toggleAppExpanded() {
        _uiState.update { it.copy(isAppExpanded = !it.isAppExpanded) }
    }

    fun setPageEnabled(packageName: String, pageId: String, enabled: Boolean) {
        val currentPolicies = _uiState.value.config.appPolicies.toMutableMap()
        val currentAppPolicy = currentPolicies[packageName] ?: AppPageShieldPolicy(packageName = packageName)

        val updatedPages = if (enabled) {
            currentAppPolicy.enabledPageIds + pageId
        } else {
            currentAppPolicy.enabledPageIds - pageId
        }

        val updatedPolicy = currentAppPolicy.copy(enabledPageIds = updatedPages)
        currentPolicies[packageName] = updatedPolicy

        _uiState.update {
            it.copy(config = it.config.copy(appPolicies = currentPolicies))
        }
    }

    fun setReaction(packageName: String, reaction: PageShieldReaction) {
        val currentPolicies = _uiState.value.config.appPolicies.toMutableMap()
        val currentAppPolicy = currentPolicies[packageName] ?: AppPageShieldPolicy(packageName = packageName)
        val updatedPolicy = currentAppPolicy.copy(reaction = reaction)
        currentPolicies[packageName] = updatedPolicy

        _uiState.update {
            it.copy(config = it.config.copy(appPolicies = currentPolicies))
        }
    }

    fun setFreezeDurationMinutes(packageName: String, minutes: Int) {
        val coerced = minutes.coerceIn(AppPageShieldPolicy.MIN_FREEZE_MINUTES, AppPageShieldPolicy.MAX_FREEZE_MINUTES)
        val currentPolicies = _uiState.value.config.appPolicies.toMutableMap()
        val currentAppPolicy = currentPolicies[packageName] ?: AppPageShieldPolicy(packageName = packageName)
        val updatedPolicy = currentAppPolicy.copy(freezeDurationMinutes = coerced)
        currentPolicies[packageName] = updatedPolicy

        _uiState.update {
            it.copy(
                config = it.config.copy(appPolicies = currentPolicies),
                freezeDurationMinutes = coerced
            )
        }
    }

    fun saveSettings(onComplete: (Boolean, DevicePolicyBundle?) -> Unit) {
        if (_uiState.value.isLoading) return
        val state = _uiState.value
        _uiState.update { it.copy(isLoading = true, errorMessage = null) }

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
                    onComplete(success, updated)
                } else {
                    val current = policyRepository.getCurrentPolicy()
                    val updatedPolicy = current.copy(pageShieldConfig = state.config)
                    val success = policyRepository.saveAndEnforceSelfPolicy(updatedPolicy)

                    if (success) {
                        PageShieldAccessibilityHook.getInstance().updateConfig(state.config)
                        _uiState.update { it.copy(isLoading = false, isSaved = true) }
                    } else {
                        val failedMsg = getApplication<Application>().getString(R.string.page_shield_save_failed_toast)
                        _uiState.update { it.copy(isLoading = false, errorMessage = failedMsg) }
                    }
                    onComplete(success, updatedPolicy)
                }
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Error saving PageShield settings")
                _uiState.update { it.copy(isLoading = false, errorMessage = e.message) }
                onComplete(false, null)
            }
        }
    }

    companion object {
        private const val TAG = "AntiPorn_PageShieldVM"
    }
}
