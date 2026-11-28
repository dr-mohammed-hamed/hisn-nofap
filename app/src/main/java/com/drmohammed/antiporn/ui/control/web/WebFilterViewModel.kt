package com.drmohammed.antiporn.ui.control.web

import android.app.Application
import android.content.Context
import android.os.Bundle
import androidx.annotation.VisibleForTesting
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.drmohammed.antiporn.R
import com.drmohammed.antiporn.accessibility.control.AccessibilitySelfActivator
import com.drmohammed.antiporn.core.model.DevicePolicyBundle
import com.drmohammed.antiporn.core.network.DnsFilteringValidator
import com.drmohammed.antiporn.curfew.CurfewVpnDnsBridge
import com.drmohammed.antiporn.curfew.WebFilterCapabilityResult
import com.drmohammed.antiporn.data.repository.AccountabilityRepository
import com.drmohammed.antiporn.data.repository.PolicyRepository
import com.drmohammed.antiporn.ui.control.gate.SelfControlAccessGateRegistry
import com.drmohammed.antiporn.ui.control.gate.SelfControlSection
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

sealed class WebFilterUiEvent {
    data class SaveSuccess(val policyJson: String) : WebFilterUiEvent()
    data class SaveFailed(val isRemote: Boolean) : WebFilterUiEvent()
    data class ValidationErrorDns(val title: String?, val message: String) : WebFilterUiEvent()
    data object InvalidDnsHostFormat : WebFilterUiEvent()
    data class CapabilityBlocked(val capability: WebFilterCapabilityResult) : WebFilterUiEvent()
    data object EmptyWhitelistError : WebFilterUiEvent()
}

/**
 * ViewModel managing draft state, DNS validation, capability checks, and persistence
 * for [WebFilterSettingsActivity].
 */
class WebFilterViewModel(
    application: Application,
    private val policyRepository: PolicyRepository,
    private val accountabilityRepository: AccountabilityRepository
) : AndroidViewModel(application) {

    @VisibleForTesting
    internal var ioDispatcher: CoroutineDispatcher = Dispatchers.IO

    @VisibleForTesting
    internal var validateDnsHost: (String) -> DnsFilteringValidator.ValidationResult =
        { DnsFilteringValidator.validateDnsHost(it) }

    @VisibleForTesting
    internal var isKnownFamilyPreset: (String) -> Boolean =
        { DnsFilteringValidator.isKnownFamilyPreset(it) }

    @VisibleForTesting
    internal var evaluateCapability: (Context, String, String, Boolean) -> WebFilterCapabilityResult =
        { ctx, initial, requested, wantsVpn ->
            CurfewVpnDnsBridge.evaluateWebFilterCapability(ctx, initial, requested, wantsVpn)
        }

    @VisibleForTesting
    internal var canAccessSelfControl: (Context) -> Boolean = { ctx ->
        SelfControlAccessGateRegistry.getActiveGate().canAccess(ctx) &&
            SelfControlAccessGateRegistry.isSectionPermitted(ctx, SelfControlSection.WEB_FILTER)
    }

    @VisibleForTesting
    internal var isUnaffiliatedBlockedProvider: (Context) -> Boolean =
        { CurfewVpnDnsBridge.isPrivateDnsBlockedByUnaffiliatedUsers(it) }

    @VisibleForTesting
    internal var canWriteSecureSettingsProvider: (Context) -> Boolean =
        { AccessibilitySelfActivator.canWriteSecureSettings(it) }

    @VisibleForTesting
    internal var coroutineScopeOverride: CoroutineScope? = null

    @VisibleForTesting
    internal var selfPolicyPatchSaver: (suspend ((DevicePolicyBundle) -> DevicePolicyBundle) -> DevicePolicyBundle?)? = null

    private val workScope: CoroutineScope
        get() = coroutineScopeOverride ?: viewModelScope

    private val _uiState = MutableStateFlow(WebFilterUiState())
    val uiState: StateFlow<WebFilterUiState> = _uiState.asStateFlow()

    private val _events = Channel<WebFilterUiEvent>(Channel.BUFFERED)
    val events: Flow<WebFilterUiEvent> = _events.receiveAsFlow()

    private var isInitialized = false
    private var isRestoredFromSavedState = false

    fun initialize(isRemote: Boolean, wardId: String, initialPolicy: DevicePolicyBundle): Boolean {
        if (isInitialized) return false
        isInitialized = true
        _uiState.value = WebFilterUiState.fromPolicy(
            isRemote = isRemote,
            wardId = wardId,
            policy = initialPolicy
        )
        return true
    }

    @VisibleForTesting
    internal interface SavedStateAccessor {
        fun getString(key: String, defaultValue: String): String
        fun getBoolean(key: String, defaultValue: Boolean): Boolean
        fun getStringArrayList(key: String): ArrayList<String>?
        fun putString(key: String, value: String)
        fun putBoolean(key: String, value: Boolean)
        fun putStringArrayList(key: String, value: ArrayList<String>)
    }

    private class BundleSavedStateAccessor(private val bundle: Bundle) : SavedStateAccessor {
        override fun getString(key: String, defaultValue: String): String = bundle.getString(key, defaultValue)
        override fun getBoolean(key: String, defaultValue: Boolean): Boolean = bundle.getBoolean(key, defaultValue)
        override fun getStringArrayList(key: String): ArrayList<String>? = bundle.getStringArrayList(key)
        override fun putString(key: String, value: String) = bundle.putString(key, value)
        override fun putBoolean(key: String, value: Boolean) = bundle.putBoolean(key, value)
        override fun putStringArrayList(key: String, value: ArrayList<String>) = bundle.putStringArrayList(key, value)
    }

    fun restoreFromSavedState(state: Bundle) {
        restoreFromSavedState(BundleSavedStateAccessor(state))
    }

    @VisibleForTesting
    internal fun restoreFromSavedState(state: SavedStateAccessor) {
        if (isRestoredFromSavedState) return
        isRestoredFromSavedState = true
        _uiState.update { current ->
            val restoredBlocked = state.getStringArrayList(STATE_BLOCKED_URLS)?.toSet() ?: current.blockedUrls
            val restoredAllowed = state.getStringArrayList(STATE_ALLOWED_URLS)?.toSet() ?: current.allowedUrls
            val restoredValidated = state.getStringArrayList(STATE_VALIDATED_HOSTS)?.toSet() ?: current.validatedCustomHosts
            current.copy(
                dnsHost = state.getString(STATE_DNS_HOST, current.dnsHost),
                isIncognitoBlocked = state.getBoolean(STATE_INCOGNITO_BLOCKED, current.isIncognitoBlocked),
                isTelegramWebBlocked = state.getBoolean(STATE_TELEGRAM_WEB_BLOCKED, current.isTelegramWebBlocked),
                isYouTubeRestricted = state.getBoolean(STATE_YOUTUBE_RESTRICTED, current.isYouTubeRestricted),
                isFullSystemFiltering = state.getBoolean(STATE_FULL_SYSTEM_FILTERING, current.isFullSystemFiltering),
                isAdBlocking = state.getBoolean(STATE_AD_BLOCKING, current.isAdBlocking),
                isLocalVpnFallbackEnabled = state.getBoolean(STATE_LOCAL_VPN_FALLBACK, current.isLocalVpnFallbackEnabled),
                isBlockExternalVpn = state.getBoolean(STATE_BLOCK_EXTERNAL_VPN, current.isBlockExternalVpn),
                isWhitelistOnlyMode = state.getBoolean(STATE_WHITELIST_ONLY, current.isWhitelistOnlyMode),
                blockedUrls = restoredBlocked,
                allowedUrls = restoredAllowed,
                validatedCustomHosts = restoredValidated
            )
        }
    }

    fun writeToSavedState(outState: Bundle) {
        writeToSavedState(BundleSavedStateAccessor(outState))
    }

    @VisibleForTesting
    internal fun writeToSavedState(outState: SavedStateAccessor) {
        val state = _uiState.value
        outState.putString(STATE_DNS_HOST, state.dnsHost)
        outState.putBoolean(STATE_INCOGNITO_BLOCKED, state.isIncognitoBlocked)
        outState.putBoolean(STATE_TELEGRAM_WEB_BLOCKED, state.isTelegramWebBlocked)
        outState.putBoolean(STATE_YOUTUBE_RESTRICTED, state.isYouTubeRestricted)
        outState.putBoolean(STATE_FULL_SYSTEM_FILTERING, state.isFullSystemFiltering)
        outState.putBoolean(STATE_AD_BLOCKING, state.isAdBlocking)
        outState.putBoolean(STATE_LOCAL_VPN_FALLBACK, state.isLocalVpnFallbackEnabled)
        outState.putBoolean(STATE_BLOCK_EXTERNAL_VPN, state.isBlockExternalVpn)
        outState.putBoolean(STATE_WHITELIST_ONLY, state.isWhitelistOnlyMode)
        outState.putStringArrayList(STATE_BLOCKED_URLS, ArrayList(state.blockedUrls))
        outState.putStringArrayList(STATE_ALLOWED_URLS, ArrayList(state.allowedUrls))
        outState.putStringArrayList(STATE_VALIDATED_HOSTS, ArrayList(state.validatedCustomHosts))
    }

    fun updateDnsHost(host: String, validated: Boolean = false) {
        _uiState.update { current ->
            val trimmed = host.trim()
            val updatedValidated = if (validated && trimmed.isNotBlank()) {
                current.validatedCustomHosts + trimmed.lowercase()
            } else {
                current.validatedCustomHosts
            }
            current.copy(
                dnsHost = trimmed,
                validatedCustomHosts = updatedValidated
            )
        }
    }

    fun updateWebsiteRules(blocked: Set<String>, allowed: Set<String>) {
        _uiState.update { it.copy(blockedUrls = blocked.toSet(), allowedUrls = allowed.toSet()) }
    }

    fun setBlockExternalVpn(enabled: Boolean) {
        _uiState.update { it.copy(isBlockExternalVpn = enabled) }
    }

    fun setIncognitoBlocked(enabled: Boolean) {
        _uiState.update { it.copy(isIncognitoBlocked = enabled) }
    }

    fun setTelegramWebBlocked(enabled: Boolean) {
        _uiState.update { it.copy(isTelegramWebBlocked = enabled) }
    }

    fun setYouTubeRestricted(enabled: Boolean) {
        _uiState.update { it.copy(isYouTubeRestricted = enabled) }
    }

    fun setWhitelistOnlyMode(enabled: Boolean) {
        _uiState.update { it.copy(isWhitelistOnlyMode = enabled) }
    }

    fun setFullSystemFiltering(enabled: Boolean) {
        _uiState.update { it.copy(isFullSystemFiltering = enabled) }
    }

    fun setAdBlocking(enabled: Boolean) {
        _uiState.update { it.copy(isAdBlocking = enabled) }
    }

    fun setLocalVpnFallbackEnabled(enabled: Boolean) {
        _uiState.update { it.copy(isLocalVpnFallbackEnabled = enabled) }
    }

    fun refreshUnaffiliatedStatusAsync() {
        if (_uiState.value.isRemote) return
        val appCtx = getApplication<Application>()
        workScope.launch(ioDispatcher) {
            try {
                val blocked = isUnaffiliatedBlockedProvider(appCtx)
                val canWrite = canWriteSecureSettingsProvider(appCtx)
                _uiState.update {
                    it.copy(
                        isUnaffiliatedBlocked = blocked,
                        canWriteSecureSettings = canWrite
                    )
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Timber.w(e, "Failed refreshing unaffiliated status")
            }
        }
    }

    fun saveChanges() {
        val state = _uiState.value
        if (state.isSaving || !state.hasUnsavedChanges()) return

        if (state.isWhitelistOnlyMode && state.allowedUrls.isEmpty()) {
            _events.trySend(WebFilterUiEvent.EmptyWhitelistError)
            return
        }

        val dnsToSave = state.dnsHost.trim().ifBlank { DevicePolicyBundle.CLOUDFLARE_FAMILY_DNS_HOST }
        if (!dnsToSave.matches(DNS_HOST_REGEX)) {
            _events.trySend(WebFilterUiEvent.InvalidDnsHostFormat)
            return
        }

        val appCtx = getApplication<Application>()
        if (!state.isRemote && !canAccessSelfControl(appCtx)) {
            _events.trySend(WebFilterUiEvent.SaveFailed(isRemote = false))
            return
        }

        _uiState.update { it.copy(isSaving = true) }

        workScope.launch(ioDispatcher) {
            try {
                val isUnchanged = dnsToSave.equals(state.initialPolicy.dnsHost, ignoreCase = true)
                val isKnownPreset = isKnownFamilyPreset(dnsToSave)
                val isAlreadyValidated = state.validatedCustomHosts.contains(dnsToSave.lowercase())
                val validationResult = if (isUnchanged || isKnownPreset || isAlreadyValidated) {
                    DnsFilteringValidator.ValidationResult.Valid
                } else {
                    validateDnsHost(dnsToSave)
                }

                val probeFailure = mapProbeFailure(appCtx, validationResult)
                if (probeFailure != null) {
                    _uiState.update { it.copy(isSaving = false) }
                    _events.send(WebFilterUiEvent.ValidationErrorDns(probeFailure.first, probeFailure.second))
                    return@launch
                }

                val filterMode = state.computeBrowserFilterMode()
                if (!state.isRemote) {
                    val wantsLocalVpnFiltering =
                        (state.isFullSystemFiltering && filterMode != DevicePolicyBundle.BROWSER_FILTER_OFF) ||
                            state.isAdBlocking
                    val capability = evaluateCapability(
                        appCtx,
                        state.initialPolicy.dnsHost,
                        dnsToSave,
                        wantsLocalVpnFiltering
                    )
                    if (capability !is WebFilterCapabilityResult.Allowed) {
                        _uiState.update { it.copy(isSaving = false) }
                        _events.send(WebFilterUiEvent.CapabilityBlocked(capability))
                        return@launch
                    }
                }

                val pushedPolicy = withContext(ioDispatcher + NonCancellable) {
                    persistPolicyUpdate(state, dnsToSave, filterMode)
                }

                if (pushedPolicy != null) {
                    _uiState.update { it.copy(isSaving = false, initialPolicy = pushedPolicy) }
                    _events.send(WebFilterUiEvent.SaveSuccess(pushedPolicy.toJson()))
                } else {
                    _uiState.update { it.copy(isSaving = false) }
                    _events.send(WebFilterUiEvent.SaveFailed(state.isRemote))
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Timber.e(e, "Error saving web filter settings")
                _uiState.update { it.copy(isSaving = false) }
                _events.send(WebFilterUiEvent.SaveFailed(state.isRemote))
            }
        }
    }

    private suspend fun persistPolicyUpdate(
        state: WebFilterUiState,
        dnsToSave: String,
        filterMode: Int
    ): DevicePolicyBundle? {
        return if (state.isRemote) {
            policyRepository.mergeAndPushRemotePolicyPatch(
                wardUid = state.wardId,
                fallbackPolicy = state.initialPolicy,
                guardianUidFallback = accountabilityRepository.getCachedUid(),
                guardianNameFallback = accountabilityRepository.getMyDisplayName()
            ) { basePolicy ->
                applyWebFields(basePolicy, state, dnsToSave, filterMode)
            }
        } else {
            val patch: (DevicePolicyBundle) -> DevicePolicyBundle = { basePolicy ->
                applyWebFields(basePolicy, state, dnsToSave, filterMode)
            }
            selfPolicyPatchSaver?.invoke(patch) ?: policyRepository.mergeAndSaveSelfPolicyPatch(transform = patch)
        }
    }

    private fun applyWebFields(
        basePolicy: DevicePolicyBundle,
        state: WebFilterUiState,
        dnsToSave: String,
        filterMode: Int
    ): DevicePolicyBundle = basePolicy.copy(
        dnsHost = dnsToSave,
        isIncognitoBlocked = state.isIncognitoBlocked,
        isTelegramWebBlocked = state.isTelegramWebBlocked,
        isYouTubeRestrictedModeEnabled = state.isYouTubeRestricted,
        isFullSystemFilteringEnabled = state.isFullSystemFiltering,
        isAdBlockingEnabled = state.isAdBlocking,
        isLocalVpnFallbackEnabled = if (!state.isRemote &&
            state.isLocalVpnFallbackEnabled == state.initialPolicy.isLocalVpnFallbackEnabled
        ) {
            basePolicy.isLocalVpnFallbackEnabled
        } else {
            state.isLocalVpnFallbackEnabled
        },
        isBlockExternalVpnEnabled = state.isBlockExternalVpn,
        customBlockedUrls = state.blockedUrls,
        customAllowedUrls = state.allowedUrls,
        browserFilterMode = filterMode
    )

    private fun mapProbeFailure(
        appCtx: Application,
        validationResult: DnsFilteringValidator.ValidationResult
    ): Pair<String?, String>? = when (validationResult) {
        is DnsFilteringValidator.ValidationResult.Valid -> null
        is DnsFilteringValidator.ValidationResult.UnfilteredPorn ->
            appCtx.getString(R.string.dns_probe_error_not_blocking) to appCtx.getString(
                R.string.dns_probe_error_not_blocking_detail,
                validationResult.domain,
                validationResult.resolvedIp
            )
        is DnsFilteringValidator.ValidationResult.Unreachable ->
            appCtx.getString(R.string.dns_probe_error_unreachable) to appCtx.getString(
                R.string.dns_probe_error_unreachable_detail,
                validationResult.reason
            )
        is DnsFilteringValidator.ValidationResult.ResolverBroken ->
            appCtx.getString(R.string.dns_probe_error_broken) to appCtx.getString(
                R.string.dns_probe_error_broken_detail,
                validationResult.reason
            )
    }

    companion object {
        internal val DNS_HOST_REGEX =
            Regex("^(?=.{1,253}$)(?:[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?\\.)+[a-zA-Z]{2,63}$")
        private const val STATE_DNS_HOST = "vm_state_dns_host"
        private const val STATE_INCOGNITO_BLOCKED = "vm_state_incognito_blocked"
        private const val STATE_TELEGRAM_WEB_BLOCKED = "vm_state_telegram_web_blocked"
        private const val STATE_YOUTUBE_RESTRICTED = "vm_state_youtube_restricted"
        private const val STATE_FULL_SYSTEM_FILTERING = "vm_state_full_system_filtering"
        private const val STATE_AD_BLOCKING = "vm_state_ad_blocking"
        private const val STATE_LOCAL_VPN_FALLBACK = "vm_state_local_vpn_fallback"
        private const val STATE_BLOCK_EXTERNAL_VPN = "vm_state_block_external_vpn"
        private const val STATE_WHITELIST_ONLY = "vm_state_whitelist_only"
        private const val STATE_BLOCKED_URLS = "vm_state_blocked_urls"
        private const val STATE_ALLOWED_URLS = "vm_state_allowed_urls"
        private const val STATE_VALIDATED_HOSTS = "vm_state_validated_hosts"
    }
}
