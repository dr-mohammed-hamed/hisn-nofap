package com.drmohammed.antiporn.ui.accountability

import com.drmohammed.antiporn.core.model.DevicePolicyBundle
import com.drmohammed.antiporn.curfew.CurfewConfig

/**
 * Immutable UI State representing the state of the Guardian Control panel.
 */
data class GuardianControlUiState(
    val wardId: String = "",
    val wardName: String = "",
    val isLoading: Boolean = true,
    val isSaving: Boolean = false,
    val isOffline: Boolean = false,
    val currentPolicy: DevicePolicyBundle = DevicePolicyBundle.defaultPolicy(),
    val initialPolicySnapshot: DevicePolicyBundle? = null,
    val selectedStartHour: Int = CurfewConfig.START_HOUR,
    val selectedStartMinute: Int = CurfewConfig.START_MINUTE,
    val selectedEndHour: Int = CurfewConfig.END_HOUR,
    val selectedEndMinute: Int = CurfewConfig.END_MINUTE,
    val selectedWeekendStartHour: Int = CurfewConfig.START_HOUR,
    val selectedWeekendStartMinute: Int = CurfewConfig.START_MINUTE,
    val selectedWeekendEndHour: Int = CurfewConfig.END_HOUR,
    val selectedWeekendEndMinute: Int = CurfewConfig.END_MINUTE,
    val selectedTimeZoneId: String = CurfewConfig.getDefaultFallbackTimeZone(),
    val isCurfewEnabled: Boolean = false,
    val isWeekendCurfewEnabled: Boolean = false,
    val isSelfControlAllowed: Boolean = true,
    val isVpnBlackholeEnabled: Boolean = CurfewConfig.DEFAULT_VPN_BLACKHOLE_ENABLED,
    val isInstantInternetKillEnabled: Boolean = false,
    val isIncognitoBlocked: Boolean = true,
    val lockNewInstallsUntilApproved: Boolean = false,
    val isDateTimeLocked: Boolean = false,
    val isYouTubeRestrictedModeEnabled: Boolean = false,
    val isTelegramWebBlocked: Boolean = true,
    val isScreenAiEnabledGlobally: Boolean = false,
    val dnsHost: String = DevicePolicyBundle.CLOUDFLARE_FAMILY_DNS_HOST,
    val version: Long = 0L,
    val acknowledgedVersion: Long = 0L,
    val lastAcknowledgedAt: Long = 0L,
    val lastHeartbeat: Long = 0L,
    val wardHasAccessibilityService: Boolean? = null,
    val wardHasUsageStats: Boolean? = null,
    val wardCanWriteSecureSettings: Boolean? = null,
    val wardCapabilitiesUpdatedAt: Long = 0L,
    val wardA11yTamperAt: Long = 0L,
    val wardSdkInt: Int? = null,
    val errorMessage: String? = null
) {
    val hasUnsavedChanges: Boolean
        get() {
            val initial = initialPolicySnapshot ?: return false
            return selectedStartHour != initial.curfewStartHour ||
                    selectedStartMinute != initial.curfewStartMinute ||
                    selectedEndHour != initial.curfewEndHour ||
                    selectedEndMinute != initial.curfewEndMinute ||
                    selectedWeekendStartHour != initial.weekendCurfewStartHour ||
                    selectedWeekendStartMinute != initial.weekendCurfewStartMinute ||
                    selectedWeekendEndHour != initial.weekendCurfewEndHour ||
                    selectedWeekendEndMinute != initial.weekendCurfewEndMinute ||
                    selectedTimeZoneId != initial.timeZoneId ||
                    isCurfewEnabled != initial.isCurfewEnabled ||
                    isWeekendCurfewEnabled != initial.isWeekendCurfewEnabled ||
                    isSelfControlAllowed != initial.isSelfControlAllowed ||
                    isVpnBlackholeEnabled != initial.isVpnBlackholeEnabled ||
                    isInstantInternetKillEnabled != initial.isInstantInternetKillEnabled ||
                    isIncognitoBlocked != initial.isIncognitoBlocked ||
                    lockNewInstallsUntilApproved != initial.lockNewInstallsUntilApproved ||
                    isDateTimeLocked != initial.isDateTimeLocked ||
                    isYouTubeRestrictedModeEnabled != initial.isYouTubeRestrictedModeEnabled ||
                    isTelegramWebBlocked != initial.isTelegramWebBlocked ||
                    isScreenAiEnabledGlobally != initial.isScreenAiEnabledGlobally ||
                    dnsHost.trim() != initial.dnsHost.trim() ||
                    currentPolicy.customBlockedUrls != initial.customBlockedUrls ||
                    currentPolicy.customAllowedUrls != initial.customAllowedUrls ||
                    currentPolicy.browserFilterMode != initial.browserFilterMode ||
                    currentPolicy.isFullSystemFilteringEnabled != initial.isFullSystemFilteringEnabled ||
                    currentPolicy.isAdBlockingEnabled != initial.isAdBlockingEnabled ||
                    currentPolicy.waqfaConfig != initial.waqfaConfig ||
                    currentPolicy.customAiScanPackages != initial.customAiScanPackages
        }

    val isSaveEnabled: Boolean
        get() = hasUnsavedChanges && !isSaving && !isLoading
}

/**
 * Single-shot UI events emitted from GuardianControlViewModel.
 */
sealed interface GuardianControlEvent {
    data class ShowToastRes(val resId: Int, val isLong: Boolean = false) : GuardianControlEvent
    data class ShowToastString(val message: String, val isLong: Boolean = false) : GuardianControlEvent
    data class DnsValidationError(val titleRes: Int, val message: String) : GuardianControlEvent
    object SaveSuccess : GuardianControlEvent
}
