package com.drmohammed.antiporn.ui.control.web

import com.drmohammed.antiporn.core.model.DevicePolicyBundle

enum class EngineMode {
    LIGHT_PRIVATE_DNS,
    LOCAL_VPN
}

enum class LocalVpnReason {
    FULL_SYSTEM_FILTER,
    AD_BLOCKING,
    BOTH,
    SYSTEM_FALLBACK
}

enum class AdBlockDnsHint {
    NONE,
    CONFIRMED_BY_DNS,
    POSSIBLE_CUSTOM_DNS
}

enum class DnsPreset {
    CLOUDFLARE,
    ADGUARD,
    CONTROLD,
    CUSTOM
}

/**
 * Immutable UI state for Web Filter, DNS, and Browsing Restrictions.
 */
data class WebFilterUiState(
    val isRemote: Boolean = false,
    val wardId: String = "",
    val initialPolicy: DevicePolicyBundle = DevicePolicyBundle.defaultPolicy(),
    val dnsHost: String = DevicePolicyBundle.CLOUDFLARE_FAMILY_DNS_HOST,
    val isBlockExternalVpn: Boolean = true,
    val isIncognitoBlocked: Boolean = true,
    val isTelegramWebBlocked: Boolean = true,
    val isYouTubeRestricted: Boolean = false,
    val isWhitelistOnlyMode: Boolean = false,
    val blockedUrls: Set<String> = emptySet(),
    val allowedUrls: Set<String> = emptySet(),
    val isFullSystemFiltering: Boolean = false,
    val isAdBlocking: Boolean = false,
    val isLocalVpnFallbackEnabled: Boolean = false,
    val isUnaffiliatedBlocked: Boolean = false,
    val canWriteSecureSettings: Boolean = false,
    val isSaving: Boolean = false,
    val validatedCustomHosts: Set<String> = emptySet()
) {

    val selectedDnsPreset: DnsPreset
        get() {
            val clean = dnsHost.trim().lowercase()
            return when (clean) {
                "",
                DevicePolicyBundle.CLOUDFLARE_FAMILY_DNS_HOST.lowercase() -> DnsPreset.CLOUDFLARE
                DevicePolicyBundle.ADGUARD_FAMILY_DNS_HOST.lowercase() -> DnsPreset.ADGUARD
                DevicePolicyBundle.CONTROLD_NO_ADS_PORN_DNS_HOST.lowercase(),
                DevicePolicyBundle.CONTROLD_FAMILY_DNS_HOST.lowercase() -> DnsPreset.CONTROLD
                else -> DnsPreset.CUSTOM
            }
        }

    val isLocalVpnNeeded: Boolean
        get() = isFullSystemFiltering || isAdBlocking

    val engineMode: EngineMode
        get() = if (isLocalVpnNeeded) EngineMode.LOCAL_VPN else EngineMode.LIGHT_PRIVATE_DNS

    val localVpnReason: LocalVpnReason?
        get() = when {
            isFullSystemFiltering && isAdBlocking -> LocalVpnReason.BOTH
            isFullSystemFiltering -> LocalVpnReason.FULL_SYSTEM_FILTER
            isAdBlocking -> LocalVpnReason.AD_BLOCKING
            else -> null
        }

    val adBlockDnsHint: AdBlockDnsHint
        get() = when (selectedDnsPreset) {
            DnsPreset.ADGUARD, DnsPreset.CONTROLD -> AdBlockDnsHint.CONFIRMED_BY_DNS
            DnsPreset.CUSTOM -> AdBlockDnsHint.POSSIBLE_CUSTOM_DNS
            DnsPreset.CLOUDFLARE -> AdBlockDnsHint.NONE
        }

    val showWhitelistFullSystemWarning: Boolean
        get() = isWhitelistOnlyMode && isFullSystemFiltering

    val showUnaffiliatedLocalWarning: Boolean
        get() = !isRemote && isLocalVpnNeeded && isUnaffiliatedBlocked && !canWriteSecureSettings

    fun hasUnsavedChanges(): Boolean {
        val cleanInputDns = dnsHost.trim().ifBlank { DevicePolicyBundle.CLOUDFLARE_FAMILY_DNS_HOST }
        val cleanInitialDns = initialPolicy.dnsHost.trim().ifBlank { DevicePolicyBundle.CLOUDFLARE_FAMILY_DNS_HOST }
        val initialWasWhitelist = initialPolicy.browserFilterMode == DevicePolicyBundle.BROWSER_FILTER_WHITELIST_ONLY
        return !cleanInputDns.equals(cleanInitialDns, ignoreCase = true) ||
            isIncognitoBlocked != initialPolicy.isIncognitoBlocked ||
            isTelegramWebBlocked != initialPolicy.isTelegramWebBlocked ||
            isYouTubeRestricted != initialPolicy.isYouTubeRestrictedModeEnabled ||
            isFullSystemFiltering != initialPolicy.isFullSystemFilteringEnabled ||
            isAdBlocking != initialPolicy.isAdBlockingEnabled ||
            isLocalVpnFallbackEnabled != initialPolicy.isLocalVpnFallbackEnabled ||
            isBlockExternalVpn != initialPolicy.isBlockExternalVpnEnabled ||
            isWhitelistOnlyMode != initialWasWhitelist ||
            blockedUrls != initialPolicy.customBlockedUrls ||
            allowedUrls != initialPolicy.customAllowedUrls
    }

    fun computeBrowserFilterMode(): Int = when {
        isWhitelistOnlyMode -> DevicePolicyBundle.BROWSER_FILTER_WHITELIST_ONLY
        isFullSystemFiltering || blockedUrls.isNotEmpty() || allowedUrls.isNotEmpty() ->
            DevicePolicyBundle.BROWSER_FILTER_BLOCKLIST
        else -> DevicePolicyBundle.BROWSER_FILTER_OFF
    }

    companion object {
        fun fromPolicy(
            isRemote: Boolean,
            wardId: String,
            policy: DevicePolicyBundle
        ): WebFilterUiState = WebFilterUiState(
            isRemote = isRemote,
            wardId = wardId,
            initialPolicy = policy,
            dnsHost = policy.dnsHost,
            isBlockExternalVpn = policy.isBlockExternalVpnEnabled,
            isIncognitoBlocked = policy.isIncognitoBlocked,
            isTelegramWebBlocked = policy.isTelegramWebBlocked,
            isYouTubeRestricted = policy.isYouTubeRestrictedModeEnabled,
            isWhitelistOnlyMode = policy.browserFilterMode == DevicePolicyBundle.BROWSER_FILTER_WHITELIST_ONLY,
            blockedUrls = policy.customBlockedUrls,
            allowedUrls = policy.customAllowedUrls,
            isFullSystemFiltering = policy.isFullSystemFilteringEnabled,
            isAdBlocking = policy.isAdBlockingEnabled,
            isLocalVpnFallbackEnabled = policy.isLocalVpnFallbackEnabled
        )
    }
}
