package com.drmohammed.antiporn.core.model

import android.util.Log
import com.drmohammed.antiporn.fortress.model.FortressesConfig
import com.drmohammed.antiporn.waqfa.model.WaqfaConfig
import org.json.JSONArray
import org.json.JSONObject

/**
 * Unified enterprise policy bundle controlling all protection parameters
 * configured by the user's accountability partner/guardian.
 */
data class DevicePolicyBundle(
    val curfewStartHour: Int = com.drmohammed.antiporn.curfew.CurfewConfig.START_HOUR, val curfewStartMinute: Int = com.drmohammed.antiporn.curfew.CurfewConfig.START_MINUTE,
    val curfewEndHour: Int = com.drmohammed.antiporn.curfew.CurfewConfig.END_HOUR, val curfewEndMinute: Int = com.drmohammed.antiporn.curfew.CurfewConfig.END_MINUTE,
    val isCurfewEnabled: Boolean = false,
    val isInstantInternetKillEnabled: Boolean = false,
    val isWeekendCurfewEnabled: Boolean = false,
    val weekendCurfewStartHour: Int = com.drmohammed.antiporn.curfew.CurfewConfig.START_HOUR, val weekendCurfewStartMinute: Int = com.drmohammed.antiporn.curfew.CurfewConfig.START_MINUTE,
    val weekendCurfewEndHour: Int = com.drmohammed.antiporn.curfew.CurfewConfig.END_HOUR, val weekendCurfewEndMinute: Int = com.drmohammed.antiporn.curfew.CurfewConfig.END_MINUTE,
    val isVpnBlackholeEnabled: Boolean = com.drmohammed.antiporn.curfew.CurfewConfig.DEFAULT_VPN_BLACKHOLE_ENABLED,
    val isIncognitoBlocked: Boolean = true,
    val lockNewInstallsUntilApproved: Boolean = false,
    val isScreenAiEnabledGlobally: Boolean = false,
    val screenAiSchemaVersion: Int = CURRENT_SCREEN_AI_SCHEMA_VERSION,
    val customBlacklistPackages: Set<String> = emptySet(),
    val customCurfewWhitelistPackages: Set<String> = emptySet(),
    val customAiScanPackages: Set<String> = emptySet(),
    val excludedAiScanPackages: Set<String> = emptySet(),
    val curfewVpnBypassPackages: Set<String> = emptySet(),
    val protectedAppPackages: Set<String> = emptySet(),
    val browserFilterMode: Int = BROWSER_FILTER_OFF,
    val isFullSystemFilteringEnabled: Boolean = false,
    val customBlockedUrls: Set<String> = emptySet(),
    val customAllowedUrls: Set<String> = emptySet(),
    val dnsHost: String = CLOUDFLARE_FAMILY_DNS_HOST,
    val isYouTubeRestrictedModeEnabled: Boolean = false,
    val isAdBlockingEnabled: Boolean = false,
    val isLocalVpnFallbackEnabled: Boolean = false,
    val isTelegramWebBlocked: Boolean = true,
    val isBlockExternalVpnEnabled: Boolean = true,
    val guardianUid: String? = null,
    val guardianName: String? = null,
    val isSupervised: Boolean = false,
    val isSelfControlAllowed: Boolean = true,
    val selfControlCurfewAllowed: Boolean = true,
    val selfControlWebAllowed: Boolean = true,
    val selfControlAppsAllowed: Boolean = true,
    val selfControlWaqfaAllowed: Boolean = true,
    val selfControlNewAppsAllowed: Boolean = true,
    val isDateTimeLocked: Boolean = false,
    val isClearAppDataAllowed: Boolean = true,
    val timeZoneId: String = com.drmohammed.antiporn.curfew.CurfewConfig.getDefaultFallbackTimeZone(),
    val lastHeartbeat: Long = 0L,
    val breakoutStatus: String? = null,
    val breakoutRequestedAt: Long? = null,
    val breakoutUnlockAt: Long? = null,
    val deactivationStatus: String? = null,
    val deactivationRequestedAt: Long? = null,
    val deactivationUnlockAt: Long? = null,
    val deactivationHasQr: Boolean = false,
    val guardianPolicySnapshot: String? = null,
    val lastSelfModifiedAt: Long? = null,
    val waqfaConfig: WaqfaConfig = WaqfaConfig(),
    val pageShieldConfig: com.drmohammed.antiporn.pageshield.model.PageShieldConfig = com.drmohammed.antiporn.pageshield.model.PageShieldConfig(),
    val selfControlPageShieldAllowed: Boolean = true,
    val version: Long = 1L,
    val updatedAt: Long = 0L,
    val remoteSelfControlResetRequestedAt: Long? = null,
    val remoteTeardownRequestedAt: Long? = null,
    // Ward read-only capability telemetry (NEVER written by Guardian toMap() pushes)
    val wardHasAccessibilityService: Boolean? = null,
    val wardHasUsageStats: Boolean? = null,
    val wardCanWriteSecureSettings: Boolean? = null,
    val wardCapabilitiesUpdatedAt: Long = 0L,
    val wardA11yTamperAt: Long = 0L,
    val wardSdkInt: Int? = null,
    // Fortresses & Remote Mode Unlock policy fields
    val fortressesConfig: FortressesConfig = FortressesConfig.default(),
    val selfControlFortressesAllowed: Boolean = true,
    val selfControlFortressUnlockAllowed: Boolean = true,
    val selfControlOtherSettingsAllowed: Boolean = true,
    val remoteModeUnlockTarget: String? = null,
    val remoteModeUnlockRequestedAt: Long? = null,
    // Ward active-mode read-only telemetry (NEVER written by Guardian toMap() pushes)
    val wardActiveFortressId: String? = null,
    val wardActiveFortressName: String? = null,
    val wardActiveFortressIsEmergency: Boolean = false,
    val wardActiveFortressEndsAtWall: Long = 0L,
    val wardIsCurfewActiveNow: Boolean = false,
    val wardActiveModesUpdatedAt: Long = 0L
) {

    fun hasAllSelfControlPermissionsEnabled(): Boolean =
        isSelfControlAllowed &&
            selfControlCurfewAllowed &&
            selfControlWebAllowed &&
            selfControlAppsAllowed &&
            selfControlWaqfaAllowed &&
            selfControlNewAppsAllowed &&
            selfControlPageShieldAllowed &&
            selfControlFortressesAllowed &&
            selfControlFortressUnlockAllowed &&
            selfControlOtherSettingsAllowed

    fun withAllSelfControlPermissionsEnabled(): DevicePolicyBundle = copy(
        isSelfControlAllowed = true,
        selfControlCurfewAllowed = true,
        selfControlWebAllowed = true,
        selfControlAppsAllowed = true,
        selfControlWaqfaAllowed = true,
        selfControlNewAppsAllowed = true,
        selfControlPageShieldAllowed = true,
        selfControlFortressesAllowed = true,
        selfControlFortressUnlockAllowed = true,
        selfControlOtherSettingsAllowed = true
    )

    fun toMap(): Map<String, Any?> {
        val map = mutableMapOf<String, Any?>(
            "curfewStartHour" to curfewStartHour,
            "curfewStartMinute" to curfewStartMinute,
            "curfewEndHour" to curfewEndHour,
            "curfewEndMinute" to curfewEndMinute,
            "isCurfewEnabled" to isCurfewEnabled,
            "isInstantInternetKillEnabled" to isInstantInternetKillEnabled,
            "isWeekendCurfewEnabled" to isWeekendCurfewEnabled,
            "weekendCurfewStartHour" to weekendCurfewStartHour,
            "weekendCurfewStartMinute" to weekendCurfewStartMinute,
            "weekendCurfewEndHour" to weekendCurfewEndHour,
            "weekendCurfewEndMinute" to weekendCurfewEndMinute,
            "isVpnBlackholeEnabled" to isVpnBlackholeEnabled,
            "isIncognitoBlocked" to isIncognitoBlocked,
            "lockNewInstallsUntilApproved" to lockNewInstallsUntilApproved,
            "isScreenAiEnabledGlobally" to isScreenAiEnabledGlobally,
            "screenAiSchemaVersion" to screenAiSchemaVersion,
            "customBlacklistPackages" to customBlacklistPackages.toList(),
            "customCurfewWhitelistPackages" to customCurfewWhitelistPackages.toList(),
            "customAiScanPackages" to customAiScanPackages.toList(),
            "excludedAiScanPackages" to excludedAiScanPackages.toList(),
            "curfewVpnBypassPackages" to curfewVpnBypassPackages.toList(),
            "protectedAppPackages" to protectedAppPackages.toList(),
            "browserFilterMode" to browserFilterMode,
            "isFullSystemFilteringEnabled" to isFullSystemFilteringEnabled,
            "customBlockedUrls" to customBlockedUrls.toList(),
            "customAllowedUrls" to customAllowedUrls.toList(),
            "dnsHost" to dnsHost,
            "isYouTubeRestrictedModeEnabled" to isYouTubeRestrictedModeEnabled,
            "isAdBlockingEnabled" to isAdBlockingEnabled,
            "isLocalVpnFallbackEnabled" to isLocalVpnFallbackEnabled,
            "isTelegramWebBlocked" to isTelegramWebBlocked,
            "isBlockExternalVpnEnabled" to isBlockExternalVpnEnabled,
            "isSupervised" to isSupervised,
            "isSelfControlAllowed" to isSelfControlAllowed,
            "selfControlCurfewAllowed" to selfControlCurfewAllowed,
            "selfControlWebAllowed" to selfControlWebAllowed,
            "selfControlAppsAllowed" to selfControlAppsAllowed,
            "selfControlWaqfaAllowed" to selfControlWaqfaAllowed,
            "selfControlNewAppsAllowed" to selfControlNewAppsAllowed,
            "isDateTimeLocked" to isDateTimeLocked,
            "isClearAppDataAllowed" to isClearAppDataAllowed,
            "timeZoneId" to timeZoneId,
            "lastHeartbeat" to lastHeartbeat,
            "breakoutStatus" to breakoutStatus,
            "breakoutRequestedAt" to breakoutRequestedAt,
            "breakoutUnlockAt" to breakoutUnlockAt,
            "deactivationStatus" to deactivationStatus,
            "deactivationRequestedAt" to deactivationRequestedAt,
            "deactivationUnlockAt" to deactivationUnlockAt,
            "deactivationHasQr" to deactivationHasQr,
            "guardianPolicySnapshot" to guardianPolicySnapshot,
            "lastSelfModifiedAt" to lastSelfModifiedAt,
            "waqfaConfig" to waqfaConfig.toMap(),
            "pageShieldConfig" to pageShieldConfig.toMap(),
            "selfControlPageShieldAllowed" to selfControlPageShieldAllowed,
            "version" to version,
            "updatedAt" to updatedAt,
            "remoteSelfControlResetRequestedAt" to remoteSelfControlResetRequestedAt,
            "remoteTeardownRequestedAt" to remoteTeardownRequestedAt,
            "fortressesConfig" to fortressesConfig.toMap(),
            "selfControlFortressesAllowed" to selfControlFortressesAllowed,
            "selfControlFortressUnlockAllowed" to selfControlFortressUnlockAllowed,
            "selfControlOtherSettingsAllowed" to selfControlOtherSettingsAllowed,
            "remoteModeUnlockTarget" to remoteModeUnlockTarget,
            "remoteModeUnlockRequestedAt" to remoteModeUnlockRequestedAt
        )
        map["guardianUid"] = guardianUid
        map["guardianName"] = guardianName
        return map
    }

    fun toJson(): String {
        val json = JSONObject()
        json.put("curfewStartHour", curfewStartHour); json.put("curfewStartMinute", curfewStartMinute)
        json.put("curfewEndHour", curfewEndHour); json.put("curfewEndMinute", curfewEndMinute)
        json.put("isCurfewEnabled", isCurfewEnabled)
        json.put("isInstantInternetKillEnabled", isInstantInternetKillEnabled)
        json.put("isWeekendCurfewEnabled", isWeekendCurfewEnabled)
        json.put("weekendCurfewStartHour", weekendCurfewStartHour); json.put("weekendCurfewStartMinute", weekendCurfewStartMinute)
        json.put("weekendCurfewEndHour", weekendCurfewEndHour); json.put("weekendCurfewEndMinute", weekendCurfewEndMinute)
        json.put("isVpnBlackholeEnabled", isVpnBlackholeEnabled)
        json.put("isIncognitoBlocked", isIncognitoBlocked)
        json.put("lockNewInstallsUntilApproved", lockNewInstallsUntilApproved)

        val blacklistArr = JSONArray().apply { customBlacklistPackages.forEach { put(it) } }
        json.put("customBlacklistPackages", blacklistArr)
        json.put("isScreenAiEnabledGlobally", isScreenAiEnabledGlobally)
        json.put("screenAiSchemaVersion", screenAiSchemaVersion)

        val whitelistArr = JSONArray().apply { customCurfewWhitelistPackages.forEach { put(it) } }
        json.put("customCurfewWhitelistPackages", whitelistArr)

        val aiScanArr = JSONArray().apply { customAiScanPackages.forEach { put(it) } }
        json.put("customAiScanPackages", aiScanArr)

        val excludedAiScanArr = JSONArray().apply { excludedAiScanPackages.forEach { put(it) } }
        json.put("excludedAiScanPackages", excludedAiScanArr)

        val vpnBypassArr = JSONArray().apply { curfewVpnBypassPackages.forEach { put(it) } }
        json.put("curfewVpnBypassPackages", vpnBypassArr)

        val protectedArr = JSONArray().apply { protectedAppPackages.forEach { put(it) } }
        json.put("protectedAppPackages", protectedArr)

        json.put("browserFilterMode", browserFilterMode)
        json.put("isFullSystemFilteringEnabled", isFullSystemFilteringEnabled)

        val blockedUrlsArr = JSONArray().apply { customBlockedUrls.forEach { put(it) } }
        json.put("customBlockedUrls", blockedUrlsArr)

        val allowedUrlsArr = JSONArray().apply { customAllowedUrls.forEach { put(it) } }
        json.put("customAllowedUrls", allowedUrlsArr)

        json.put("dnsHost", dnsHost)
        json.put("isYouTubeRestrictedModeEnabled", isYouTubeRestrictedModeEnabled)
        json.put("isAdBlockingEnabled", isAdBlockingEnabled)
        json.put("isLocalVpnFallbackEnabled", isLocalVpnFallbackEnabled)
        json.put("isTelegramWebBlocked", isTelegramWebBlocked)
        json.put("isBlockExternalVpnEnabled", isBlockExternalVpnEnabled)
        if (guardianUid != null) json.put("guardianUid", guardianUid)
        if (guardianName != null) json.put("guardianName", guardianName)
        json.put("isSupervised", isSupervised)
        json.put("isSelfControlAllowed", isSelfControlAllowed)
        json.put("selfControlCurfewAllowed", selfControlCurfewAllowed)
        json.put("selfControlWebAllowed", selfControlWebAllowed)
        json.put("selfControlAppsAllowed", selfControlAppsAllowed)
        json.put("selfControlWaqfaAllowed", selfControlWaqfaAllowed)
        json.put("selfControlNewAppsAllowed", selfControlNewAppsAllowed)
        json.put("isDateTimeLocked", isDateTimeLocked)
        json.put("isClearAppDataAllowed", isClearAppDataAllowed)
        json.put("timeZoneId", timeZoneId)
        json.put("lastHeartbeat", lastHeartbeat)
        if (breakoutStatus != null) json.put("breakoutStatus", breakoutStatus)
        if (breakoutRequestedAt != null) json.put("breakoutRequestedAt", breakoutRequestedAt)
        if (breakoutUnlockAt != null) json.put("breakoutUnlockAt", breakoutUnlockAt)
        if (deactivationStatus != null) json.put("deactivationStatus", deactivationStatus)
        if (deactivationRequestedAt != null) json.put("deactivationRequestedAt", deactivationRequestedAt)
        if (deactivationUnlockAt != null) json.put("deactivationUnlockAt", deactivationUnlockAt)
        json.put("deactivationHasQr", deactivationHasQr)
        if (guardianPolicySnapshot != null) json.put("guardianPolicySnapshot", guardianPolicySnapshot)
        if (lastSelfModifiedAt != null) json.put("lastSelfModifiedAt", lastSelfModifiedAt)
        if (remoteSelfControlResetRequestedAt != null) json.put("remoteSelfControlResetRequestedAt", remoteSelfControlResetRequestedAt)
        if (remoteTeardownRequestedAt != null) json.put("remoteTeardownRequestedAt", remoteTeardownRequestedAt)
        json.put("waqfaConfig", waqfaConfig.toJson())
        json.put("pageShieldConfig", pageShieldConfig.toJson())
        json.put("selfControlPageShieldAllowed", selfControlPageShieldAllowed)
        json.put("version", version)
        json.put("updatedAt", updatedAt)
        if (wardHasAccessibilityService != null) json.put("wardHasAccessibilityService", wardHasAccessibilityService)
        if (wardHasUsageStats != null) json.put("wardHasUsageStats", wardHasUsageStats)
        if (wardCanWriteSecureSettings != null) json.put("wardCanWriteSecureSettings", wardCanWriteSecureSettings)
        json.put("wardCapabilitiesUpdatedAt", wardCapabilitiesUpdatedAt)
        json.put("wardA11yTamperAt", wardA11yTamperAt)
        if (wardSdkInt != null) json.put("wardSdkInt", wardSdkInt)
        json.put("fortressesConfig", fortressesConfig.toJson())
        json.put("selfControlFortressesAllowed", selfControlFortressesAllowed)
        json.put("selfControlFortressUnlockAllowed", selfControlFortressUnlockAllowed)
        json.put("selfControlOtherSettingsAllowed", selfControlOtherSettingsAllowed)
        if (remoteModeUnlockTarget != null) json.put("remoteModeUnlockTarget", remoteModeUnlockTarget)
        if (remoteModeUnlockRequestedAt != null) json.put("remoteModeUnlockRequestedAt", remoteModeUnlockRequestedAt)
        if (wardActiveFortressId != null) json.put("wardActiveFortressId", wardActiveFortressId)
        if (wardActiveFortressName != null) json.put("wardActiveFortressName", wardActiveFortressName)
        json.put("wardActiveFortressIsEmergency", wardActiveFortressIsEmergency)
        json.put("wardActiveFortressEndsAtWall", wardActiveFortressEndsAtWall)
        json.put("wardIsCurfewActiveNow", wardIsCurfewActiveNow)
        json.put("wardActiveModesUpdatedAt", wardActiveModesUpdatedAt)
        return json.toString()
    }

    companion object {
        private const val TAG = "AntiPorn_PolicyBundle"
        const val CURRENT_SCREEN_AI_SCHEMA_VERSION = 2
        const val BROWSER_FILTER_OFF = 0
        const val BROWSER_FILTER_BLOCKLIST = 1
        const val BROWSER_FILTER_BLOCKLIST_ONLY = 1
        const val BROWSER_FILTER_WHITELIST_ONLY = 2

        const val CONTROLD_FAMILY_DNS_HOST = "no-ads-drugs-malware-porn-gambling-dating-typo.freedns.controld.com"
        const val CONTROLD_NO_ADS_PORN_DNS_HOST = "no-ads-porn.freedns.controld.com"
        const val ADGUARD_FAMILY_DNS_HOST = "family.adguard-dns.com"
        const val CLOUDFLARE_FAMILY_DNS_HOST = "family.cloudflare-dns.com"

        const val BREAKOUT_STATUS_PENDING = "PENDING"
        const val BREAKOUT_STATUS_REJECTED = "REJECTED"
        const val BREAKOUT_STATUS_CANCELLED = "CANCELLED"
        const val BREAKOUT_STATUS_COMPLETED = "COMPLETED"
        const val BREAKOUT_GRACE_PERIOD_MS = 24L * 60L * 60L * 1000L // 24 Hours

        const val DEACTIVATION_STATUS_PENDING = "PENDING"
        const val DEACTIVATION_STATUS_REJECTED = "REJECTED"
        const val DEACTIVATION_STATUS_CANCELLED = "CANCELLED"
        const val DEACTIVATION_STATUS_COMPLETED = "COMPLETED"
        const val DEACTIVATION_DEFAULT_DELAY_MS = 48L * 60L * 60L * 1000L // 48 Hours
        const val DEACTIVATION_QR_DELAY_MS = 24L * 60L * 60L * 1000L // 24 Hours

        const val REMOTE_MODE_UNLOCK_CURFEW = "CURFEW"
        const val REMOTE_MODE_UNLOCK_FORTRESS = "FORTRESS"
        const val REMOTE_MODE_UNLOCK_ALL = "ALL"

        fun defaultPolicy(): DevicePolicyBundle = DevicePolicyBundle(
            browserFilterMode = BROWSER_FILTER_OFF,
            isFullSystemFilteringEnabled = false,
            customBlockedUrls = emptySet(),
            customAllowedUrls = emptySet(),
            customCurfewWhitelistPackages = com.drmohammed.antiporn.curfew.CurfewConfig.STATIC_WHITELIST,
            dnsHost = CLOUDFLARE_FAMILY_DNS_HOST,
            isYouTubeRestrictedModeEnabled = false,
            isAdBlockingEnabled = false,
            isLocalVpnFallbackEnabled = false,
            isTelegramWebBlocked = true,
            isBlockExternalVpnEnabled = true,
            lockNewInstallsUntilApproved = false,
            guardianUid = null,
            guardianName = null,
            isSupervised = false,
            isSelfControlAllowed = true,
            isDateTimeLocked = false,
            isClearAppDataAllowed = true,
            timeZoneId = com.drmohammed.antiporn.curfew.CurfewConfig.getDefaultFallbackTimeZone()
        )

        fun reconcileLegacyV1(policy: DevicePolicyBundle): DevicePolicyBundle {
            return if (policy.timeZoneId.isBlank()) {
                policy.copy(timeZoneId = com.drmohammed.antiporn.curfew.CurfewConfig.DEFAULT_TIMEZONE_ID)
            } else {
                policy
            }
        }

        @Suppress("UNCHECKED_CAST")
        fun fromMap(map: Map<String, Any?>): DevicePolicyBundle {
            val startHour = ((map["curfewStartHour"] as? Number)?.toInt() ?: com.drmohammed.antiporn.curfew.CurfewConfig.START_HOUR).coerceIn(0, 23)
            val startMin = ((map["curfewStartMinute"] as? Number)?.toInt() ?: com.drmohammed.antiporn.curfew.CurfewConfig.START_MINUTE).coerceIn(0, 59)
            val endHour = ((map["curfewEndHour"] as? Number)?.toInt() ?: com.drmohammed.antiporn.curfew.CurfewConfig.END_HOUR).coerceIn(0, 23)
            val endMin = ((map["curfewEndMinute"] as? Number)?.toInt() ?: com.drmohammed.antiporn.curfew.CurfewConfig.END_MINUTE).coerceIn(0, 59)
            val isCurfew = (map["isCurfewEnabled"] as? Boolean) ?: false
            val isInstantKill = (map["isInstantInternetKillEnabled"] as? Boolean) ?: false
            val isWeekendCurfew = (map["isWeekendCurfewEnabled"] as? Boolean) ?: false
            val wkStartHour = ((map["weekendCurfewStartHour"] as? Number)?.toInt() ?: com.drmohammed.antiporn.curfew.CurfewConfig.START_HOUR).coerceIn(0, 23)
            val wkStartMin = ((map["weekendCurfewStartMinute"] as? Number)?.toInt() ?: com.drmohammed.antiporn.curfew.CurfewConfig.START_MINUTE).coerceIn(0, 59)
            val wkEndHour = ((map["weekendCurfewEndHour"] as? Number)?.toInt() ?: com.drmohammed.antiporn.curfew.CurfewConfig.END_HOUR).coerceIn(0, 23)
            val wkEndMin = ((map["weekendCurfewEndMinute"] as? Number)?.toInt() ?: com.drmohammed.antiporn.curfew.CurfewConfig.END_MINUTE).coerceIn(0, 59)
            val vpn = (map["isVpnBlackholeEnabled"] as? Boolean) ?: com.drmohammed.antiporn.curfew.CurfewConfig.DEFAULT_VPN_BLACKHOLE_ENABLED
            val incognito = (map["isIncognitoBlocked"] as? Boolean) ?: true
            val lockNew = (map["lockNewInstallsUntilApproved"] as? Boolean) ?: false

            val blacklist = (map["customBlacklistPackages"] as? List<*>)?.filterIsInstance<String>()?.toSet() ?: emptySet()
            val whitelist = if (!map.containsKey("customCurfewWhitelistPackages")) {
                com.drmohammed.antiporn.curfew.CurfewConfig.STATIC_WHITELIST
            } else {
                (map["customCurfewWhitelistPackages"] as? List<*>)?.filterIsInstance<String>()?.toSet() ?: emptySet()
            }
            val aiScan = (map["customAiScanPackages"] as? List<*>)?.filterIsInstance<String>()?.toSet() ?: emptySet()
            val excludedAiScan = (map["excludedAiScanPackages"] as? List<*>)?.filterIsInstance<String>()?.toSet() ?: emptySet()
            val vpnBypass = (map["curfewVpnBypassPackages"] as? List<*>)?.filterIsInstance<String>()?.toSet() ?: emptySet()
            val protectedApps = (map["protectedAppPackages"] as? List<*>)?.filterIsInstance<String>()?.toSet() ?: emptySet()

            val browserFilterMode = ((map["browserFilterMode"] as? Number)?.toInt()
                ?: (map["browserFilterMode"] as? String)?.toIntOrNull()
                ?: BROWSER_FILTER_OFF).coerceIn(0, 2)
            val isFullSystemFilteringEnabled = (map["isFullSystemFilteringEnabled"] as? Boolean) ?: false
            val rawScreenAiSchemaVersion = (map["screenAiSchemaVersion"] as? Number)?.toInt() ?: 1
            val isScreenAiEnabledGlobally: Boolean
            val screenAiSchemaVersion: Int
            if (rawScreenAiSchemaVersion < CURRENT_SCREEN_AI_SCHEMA_VERSION) {
                isScreenAiEnabledGlobally = false
                screenAiSchemaVersion = CURRENT_SCREEN_AI_SCHEMA_VERSION
            } else {
                isScreenAiEnabledGlobally = (map["isScreenAiEnabledGlobally"] as? Boolean) ?: false
                screenAiSchemaVersion = rawScreenAiSchemaVersion
            }
            val customBlockedUrls = (map["customBlockedUrls"] as? List<*>)?.filterIsInstance<String>()?.filter { it.isNotBlank() }?.toSet() ?: emptySet()
            val customAllowedUrls = (map["customAllowedUrls"] as? List<*>)?.filterIsInstance<String>()?.filter { it.isNotBlank() }?.toSet() ?: emptySet()

            val dns = (map["dnsHost"] as? String)?.takeIf { it.isNotBlank() } ?: CLOUDFLARE_FAMILY_DNS_HOST
            val ytRestricted = (map["isYouTubeRestrictedModeEnabled"] as? Boolean) ?: false
            val adBlocking = (map["isAdBlockingEnabled"] as? Boolean) ?: false
            val localVpnFallback = (map["isLocalVpnFallbackEnabled"] as? Boolean) ?: false
            val tgBlocked = (map["isTelegramWebBlocked"] as? Boolean) ?: true
            val blockExternalVpn = (map["isBlockExternalVpnEnabled"] as? Boolean) ?: true

            val gUid = (map["guardianUid"] as? String)?.takeIf { it.isNotBlank() }
            val gName = (map["guardianName"] as? String)?.takeIf { it.isNotBlank() }
            val supervised = (map["isSupervised"] as? Boolean) ?: (!gUid.isNullOrBlank())
            val isSelfControlAllowed = (map["isSelfControlAllowed"] as? Boolean) ?: true
            val selfControlCurfewAllowed = (map["selfControlCurfewAllowed"] as? Boolean) ?: true
            val selfControlWebAllowed = (map["selfControlWebAllowed"] as? Boolean) ?: true
            val selfControlAppsAllowed = (map["selfControlAppsAllowed"] as? Boolean) ?: true
            val selfControlWaqfaAllowed = (map["selfControlWaqfaAllowed"] as? Boolean) ?: true
            val selfControlNewAppsAllowed = (map["selfControlNewAppsAllowed"] as? Boolean) ?: true
            val isDateTimeLocked = (map["isDateTimeLocked"] as? Boolean) ?: false
            val isClearAppDataAllowed = (map["isClearAppDataAllowed"] as? Boolean) ?: true
            val timeZoneId = (map["timeZoneId"] as? String)?.takeIf { it.isNotBlank() } ?: com.drmohammed.antiporn.curfew.CurfewConfig.DEFAULT_TIMEZONE_ID
            val lastHeartbeat = (map["lastHeartbeat"] as? Number)?.toLong() ?: 0L
            val breakoutStatus = (map["breakoutStatus"] as? String)?.takeIf { it.isNotBlank() }
            val breakoutRequestedAt = (map["breakoutRequestedAt"] as? Number)?.toLong()
            val breakoutUnlockAt = (map["breakoutUnlockAt"] as? Number)?.toLong()
            val deactivationStatus = (map["deactivationStatus"] as? String)?.takeIf { it.isNotBlank() }
            val deactivationRequestedAt = (map["deactivationRequestedAt"] as? Number)?.toLong()
            val deactivationUnlockAt = (map["deactivationUnlockAt"] as? Number)?.toLong()
            val deactivationHasQr = (map["deactivationHasQr"] as? Boolean) ?: false
            val guardianPolicySnapshot = (map["guardianPolicySnapshot"] as? String)?.takeIf { it.isNotBlank() }
            val lastSelfModifiedAt = (map["lastSelfModifiedAt"] as? Number)?.toLong()
            val remoteResetAt = (map["remoteSelfControlResetRequestedAt"] as? Number)?.toLong()
            val remoteTeardownAt = (map["remoteTeardownRequestedAt"] as? Number)?.toLong()
            val waqfa = WaqfaConfig.fromMap(map["waqfaConfig"])
            val pageShield = com.drmohammed.antiporn.pageshield.model.PageShieldConfig.fromMap(map["pageShieldConfig"])
            val selfControlPageShieldAllowed = (map["selfControlPageShieldAllowed"] as? Boolean) ?: true

            val version = (map["version"] as? Number)?.toLong() ?: 1L
            val updatedAt = (map["updatedAt"] as? Number)?.toLong() ?: 0L
            val wardHasAccessibilityService = map["wardHasAccessibilityService"] as? Boolean
            val wardHasUsageStats = map["wardHasUsageStats"] as? Boolean
            val wardCanWriteSecureSettings = map["wardCanWriteSecureSettings"] as? Boolean
            val wardCapabilitiesUpdatedAt = (map["wardCapabilitiesUpdatedAt"] as? Number)?.toLong() ?: 0L
            val wardA11yTamperAt = (map["wardA11yTamperAt"] as? Number)?.toLong() ?: 0L
            val wardSdkInt = (map["wardSdkInt"] as? Number)?.toInt()

            val fortressesConfig = FortressesConfig.fromMap(map["fortressesConfig"])
            val selfControlFortressesAllowed = (map["selfControlFortressesAllowed"] as? Boolean) ?: true
            val selfControlFortressUnlockAllowed = (map["selfControlFortressUnlockAllowed"] as? Boolean) ?: true
            val selfControlOtherSettingsAllowed = (map["selfControlOtherSettingsAllowed"] as? Boolean) ?: true
            val remoteModeUnlockTarget = (map["remoteModeUnlockTarget"] as? String)?.takeIf { it.isNotBlank() }
            val remoteModeUnlockRequestedAt = (map["remoteModeUnlockRequestedAt"] as? Number)?.toLong()
            val wardActiveFortressId = (map["wardActiveFortressId"] as? String)?.takeIf { it.isNotBlank() }
            val wardActiveFortressName = (map["wardActiveFortressName"] as? String)?.takeIf { it.isNotBlank() }
            val wardActiveFortressIsEmergency = (map["wardActiveFortressIsEmergency"] as? Boolean) ?: false
            val wardActiveFortressEndsAtWall = (map["wardActiveFortressEndsAtWall"] as? Number)?.toLong() ?: 0L
            val wardIsCurfewActiveNow = (map["wardIsCurfewActiveNow"] as? Boolean) ?: false
            val wardActiveModesUpdatedAt = (map["wardActiveModesUpdatedAt"] as? Number)?.toLong() ?: 0L

            return DevicePolicyBundle(
                curfewStartHour = startHour, curfewStartMinute = startMin,
                curfewEndHour = endHour, curfewEndMinute = endMin,
                isCurfewEnabled = isCurfew,
                isInstantInternetKillEnabled = isInstantKill,
                isWeekendCurfewEnabled = isWeekendCurfew,
                weekendCurfewStartHour = wkStartHour, weekendCurfewStartMinute = wkStartMin,
                weekendCurfewEndHour = wkEndHour, weekendCurfewEndMinute = wkEndMin,
                isVpnBlackholeEnabled = vpn, isIncognitoBlocked = incognito,
                lockNewInstallsUntilApproved = lockNew,
                isScreenAiEnabledGlobally = isScreenAiEnabledGlobally,
                screenAiSchemaVersion = screenAiSchemaVersion,
                customBlacklistPackages = blacklist,
                customCurfewWhitelistPackages = whitelist,
                customAiScanPackages = aiScan,
                excludedAiScanPackages = excludedAiScan,
                curfewVpnBypassPackages = vpnBypass,
                protectedAppPackages = protectedApps,
                browserFilterMode = browserFilterMode,
                isFullSystemFilteringEnabled = isFullSystemFilteringEnabled,
                customBlockedUrls = customBlockedUrls,
                customAllowedUrls = customAllowedUrls,
                dnsHost = dns,
                isYouTubeRestrictedModeEnabled = ytRestricted,
                isAdBlockingEnabled = adBlocking,
                isLocalVpnFallbackEnabled = localVpnFallback,
                isTelegramWebBlocked = tgBlocked,
                isBlockExternalVpnEnabled = blockExternalVpn,
                guardianUid = gUid, guardianName = gName,
                isSupervised = supervised,
                isSelfControlAllowed = isSelfControlAllowed,
                selfControlCurfewAllowed = selfControlCurfewAllowed,
                selfControlWebAllowed = selfControlWebAllowed,
                selfControlAppsAllowed = selfControlAppsAllowed,
                selfControlWaqfaAllowed = selfControlWaqfaAllowed,
                selfControlNewAppsAllowed = selfControlNewAppsAllowed,
                isDateTimeLocked = isDateTimeLocked,
                isClearAppDataAllowed = isClearAppDataAllowed,
                timeZoneId = timeZoneId, lastHeartbeat = lastHeartbeat,
                breakoutStatus = breakoutStatus,
                breakoutRequestedAt = breakoutRequestedAt, breakoutUnlockAt = breakoutUnlockAt,
                deactivationStatus = deactivationStatus,
                deactivationRequestedAt = deactivationRequestedAt, deactivationUnlockAt = deactivationUnlockAt,
                deactivationHasQr = deactivationHasQr,
                guardianPolicySnapshot = guardianPolicySnapshot,
                lastSelfModifiedAt = lastSelfModifiedAt,
                waqfaConfig = waqfa,
                pageShieldConfig = pageShield,
                selfControlPageShieldAllowed = selfControlPageShieldAllowed,
                version = version, updatedAt = updatedAt,
                remoteSelfControlResetRequestedAt = remoteResetAt,
                remoteTeardownRequestedAt = remoteTeardownAt,
                wardHasAccessibilityService = wardHasAccessibilityService,
                wardHasUsageStats = wardHasUsageStats,
                wardCanWriteSecureSettings = wardCanWriteSecureSettings,
                wardCapabilitiesUpdatedAt = wardCapabilitiesUpdatedAt,
                wardA11yTamperAt = wardA11yTamperAt,
                wardSdkInt = wardSdkInt,
                fortressesConfig = fortressesConfig,
                selfControlFortressesAllowed = selfControlFortressesAllowed,
                selfControlFortressUnlockAllowed = selfControlFortressUnlockAllowed,
                selfControlOtherSettingsAllowed = selfControlOtherSettingsAllowed,
                remoteModeUnlockTarget = remoteModeUnlockTarget,
                remoteModeUnlockRequestedAt = remoteModeUnlockRequestedAt,
                wardActiveFortressId = wardActiveFortressId,
                wardActiveFortressName = wardActiveFortressName,
                wardActiveFortressIsEmergency = wardActiveFortressIsEmergency,
                wardActiveFortressEndsAtWall = wardActiveFortressEndsAtWall,
                wardIsCurfewActiveNow = wardIsCurfewActiveNow,
                wardActiveModesUpdatedAt = wardActiveModesUpdatedAt
            )
        }

        private fun JSONObject.optNonBlankStringOrNull(key: String): String? =
            if (has(key) && !isNull(key)) optString(key, "").takeIf { it.isNotBlank() } else null

        private fun org.json.JSONArray?.toStringSet(): Set<String> {
            if (this == null) return emptySet()
            val set = mutableSetOf<String>()
            for (i in 0 until length()) {
                val s = optString(i)
                if (!s.isNullOrBlank() && s != "null") set.add(s)
            }
            return set
        }

        fun fromJson(jsonStr: String): DevicePolicyBundle = fromJsonOrNull(jsonStr) ?: defaultPolicy()

        fun fromJsonOrNull(jsonStr: String): DevicePolicyBundle? {
            if (jsonStr.isBlank()) return null
            return try {
                val json = JSONObject(jsonStr)
                val blacklist = json.optJSONArray("customBlacklistPackages").toStringSet()
                val whitelist = if (json.has("customCurfewWhitelistPackages")) {
                    json.optJSONArray("customCurfewWhitelistPackages").toStringSet()
                } else {
                    com.drmohammed.antiporn.curfew.CurfewConfig.STATIC_WHITELIST
                }
                val aiScan = json.optJSONArray("customAiScanPackages").toStringSet()
                val excludedAiScan = json.optJSONArray("excludedAiScanPackages").toStringSet()
                val vpnBypass = json.optJSONArray("curfewVpnBypassPackages").toStringSet()
                val protectedApps = json.optJSONArray("protectedAppPackages").toStringSet()
                val browserFilterMode = json.optInt("browserFilterMode", BROWSER_FILTER_OFF).coerceIn(0, 2)
                val isFullSystemFilteringEnabled = json.optBoolean("isFullSystemFilteringEnabled", false)
                val rawScreenAiSchemaVersion = if (json.has("screenAiSchemaVersion") && !json.isNull("screenAiSchemaVersion")) {
                    json.optInt("screenAiSchemaVersion", 1)
                } else {
                    1
                }
                val isScreenAiEnabledGlobally: Boolean
                val screenAiSchemaVersion: Int
                if (rawScreenAiSchemaVersion < CURRENT_SCREEN_AI_SCHEMA_VERSION) {
                    isScreenAiEnabledGlobally = false
                    screenAiSchemaVersion = CURRENT_SCREEN_AI_SCHEMA_VERSION
                } else {
                    isScreenAiEnabledGlobally = json.optBoolean("isScreenAiEnabledGlobally", false)
                    screenAiSchemaVersion = rawScreenAiSchemaVersion
                }
                val customBlockedUrls = json.optJSONArray("customBlockedUrls").toStringSet()
                val customAllowedUrls = json.optJSONArray("customAllowedUrls").toStringSet()

                val dns = json.optNonBlankStringOrNull("dnsHost") ?: CLOUDFLARE_FAMILY_DNS_HOST
                val ytRestricted = json.optBoolean("isYouTubeRestrictedModeEnabled", false)
                val adBlocking = json.optBoolean("isAdBlockingEnabled", false)
                val localVpnFallback = json.optBoolean("isLocalVpnFallbackEnabled", false)
                val tgBlocked = json.optBoolean("isTelegramWebBlocked", true)
                val blockExternalVpn = json.optBoolean("isBlockExternalVpnEnabled", true)
                val gUid = json.optNonBlankStringOrNull("guardianUid")
                val gName = json.optNonBlankStringOrNull("guardianName")
                val supervised = json.optBoolean("isSupervised", !gUid.isNullOrBlank())
                val isSelfControlAllowed = json.optBoolean("isSelfControlAllowed", true)
                val selfControlCurfewAllowed = json.optBoolean("selfControlCurfewAllowed", true)
                val selfControlWebAllowed = json.optBoolean("selfControlWebAllowed", true)
                val selfControlAppsAllowed = json.optBoolean("selfControlAppsAllowed", true)
                val selfControlWaqfaAllowed = json.optBoolean("selfControlWaqfaAllowed", true)
                val selfControlNewAppsAllowed = json.optBoolean("selfControlNewAppsAllowed", true)
                val isDateTimeLocked = json.optBoolean("isDateTimeLocked", false)
                val isClearAppDataAllowed = json.optBoolean("isClearAppDataAllowed", true)
                val timeZoneId = json.optNonBlankStringOrNull("timeZoneId") ?: com.drmohammed.antiporn.curfew.CurfewConfig.DEFAULT_TIMEZONE_ID
                val lastHeartbeat = json.optLong("lastHeartbeat", 0L)
                val breakoutStatus = json.optNonBlankStringOrNull("breakoutStatus")
                val breakoutRequestedAt = if (json.has("breakoutRequestedAt") && !json.isNull("breakoutRequestedAt")) json.optLong("breakoutRequestedAt") else null
                val breakoutUnlockAt = if (json.has("breakoutUnlockAt") && !json.isNull("breakoutUnlockAt")) json.optLong("breakoutUnlockAt") else null
                val deactivationStatus = json.optNonBlankStringOrNull("deactivationStatus")
                val deactivationRequestedAt = if (json.has("deactivationRequestedAt") && !json.isNull("deactivationRequestedAt")) json.optLong("deactivationRequestedAt") else null
                val deactivationUnlockAt = if (json.has("deactivationUnlockAt") && !json.isNull("deactivationUnlockAt")) json.optLong("deactivationUnlockAt") else null
                val deactivationHasQr = json.optBoolean("deactivationHasQr", false)
                val guardianPolicySnapshot = json.optNonBlankStringOrNull("guardianPolicySnapshot")
                val lastSelfModifiedAt = if (json.has("lastSelfModifiedAt") && !json.isNull("lastSelfModifiedAt")) json.optLong("lastSelfModifiedAt") else null
                val remoteResetAt = if (json.has("remoteSelfControlResetRequestedAt") && !json.isNull("remoteSelfControlResetRequestedAt")) json.optLong("remoteSelfControlResetRequestedAt") else null
                val remoteTeardownAt = if (json.has("remoteTeardownRequestedAt") && !json.isNull("remoteTeardownRequestedAt")) json.optLong("remoteTeardownRequestedAt") else null
                val waqfa = WaqfaConfig.fromJson(json.optJSONObject("waqfaConfig"))
                val pageShield = com.drmohammed.antiporn.pageshield.model.PageShieldConfig.fromJson(json.optJSONObject("pageShieldConfig"))
                val selfControlPageShieldAllowed = json.optBoolean("selfControlPageShieldAllowed", true)
                val wardHasAccessibilityService = if (json.has("wardHasAccessibilityService") && !json.isNull("wardHasAccessibilityService")) {
                    json.optBoolean("wardHasAccessibilityService")
                } else {
                    null
                }
                val wardHasUsageStats = if (json.has("wardHasUsageStats") && !json.isNull("wardHasUsageStats")) {
                    json.optBoolean("wardHasUsageStats")
                } else {
                    null
                }
                val wardCanWriteSecureSettings = if (json.has("wardCanWriteSecureSettings") && !json.isNull("wardCanWriteSecureSettings")) {
                    json.optBoolean("wardCanWriteSecureSettings")
                } else {
                    null
                }
                val wardCapabilitiesUpdatedAt = json.optLong("wardCapabilitiesUpdatedAt", 0L)
                val wardA11yTamperAt = json.optLong("wardA11yTamperAt", 0L)
                val wardSdkInt = if (json.has("wardSdkInt") && !json.isNull("wardSdkInt")) {
                    json.optInt("wardSdkInt")
                } else {
                    null
                }
                val fortressesConfig = FortressesConfig.fromJson(json.optJSONObject("fortressesConfig"))
                val selfControlFortressesAllowed = json.optBoolean("selfControlFortressesAllowed", true)
                val selfControlFortressUnlockAllowed = json.optBoolean("selfControlFortressUnlockAllowed", true)
                val selfControlOtherSettingsAllowed = json.optBoolean("selfControlOtherSettingsAllowed", true)
                val remoteModeUnlockTarget = json.optNonBlankStringOrNull("remoteModeUnlockTarget")
                val remoteModeUnlockRequestedAt = if (json.has("remoteModeUnlockRequestedAt") && !json.isNull("remoteModeUnlockRequestedAt")) {
                    json.optLong("remoteModeUnlockRequestedAt")
                } else {
                    null
                }
                val wardActiveFortressId = json.optNonBlankStringOrNull("wardActiveFortressId")
                val wardActiveFortressName = json.optNonBlankStringOrNull("wardActiveFortressName")
                val wardActiveFortressIsEmergency = json.optBoolean("wardActiveFortressIsEmergency", false)
                val wardActiveFortressEndsAtWall = json.optLong("wardActiveFortressEndsAtWall", 0L)
                val wardIsCurfewActiveNow = json.optBoolean("wardIsCurfewActiveNow", false)
                val wardActiveModesUpdatedAt = json.optLong("wardActiveModesUpdatedAt", 0L)

                DevicePolicyBundle(
                    curfewStartHour = json.optInt("curfewStartHour", com.drmohammed.antiporn.curfew.CurfewConfig.START_HOUR).coerceIn(0, 23),
                    curfewStartMinute = json.optInt("curfewStartMinute", com.drmohammed.antiporn.curfew.CurfewConfig.START_MINUTE).coerceIn(0, 59),
                    curfewEndHour = json.optInt("curfewEndHour", com.drmohammed.antiporn.curfew.CurfewConfig.END_HOUR).coerceIn(0, 23),
                    curfewEndMinute = json.optInt("curfewEndMinute", com.drmohammed.antiporn.curfew.CurfewConfig.END_MINUTE).coerceIn(0, 59),
                    isCurfewEnabled = json.optBoolean("isCurfewEnabled", false),
                    isInstantInternetKillEnabled = json.optBoolean("isInstantInternetKillEnabled", false),
                    isWeekendCurfewEnabled = json.optBoolean("isWeekendCurfewEnabled", false),
                    weekendCurfewStartHour = json.optInt("weekendCurfewStartHour", com.drmohammed.antiporn.curfew.CurfewConfig.START_HOUR).coerceIn(0, 23),
                    weekendCurfewStartMinute = json.optInt("weekendCurfewStartMinute", com.drmohammed.antiporn.curfew.CurfewConfig.START_MINUTE).coerceIn(0, 59),
                    weekendCurfewEndHour = json.optInt("weekendCurfewEndHour", com.drmohammed.antiporn.curfew.CurfewConfig.END_HOUR).coerceIn(0, 23),
                    weekendCurfewEndMinute = json.optInt("weekendCurfewEndMinute", com.drmohammed.antiporn.curfew.CurfewConfig.END_MINUTE).coerceIn(0, 59),
                    isVpnBlackholeEnabled = json.optBoolean("isVpnBlackholeEnabled", com.drmohammed.antiporn.curfew.CurfewConfig.DEFAULT_VPN_BLACKHOLE_ENABLED),
                    isIncognitoBlocked = json.optBoolean("isIncognitoBlocked", true),
                    lockNewInstallsUntilApproved = json.optBoolean("lockNewInstallsUntilApproved", false),
                    isScreenAiEnabledGlobally = isScreenAiEnabledGlobally,
                    screenAiSchemaVersion = screenAiSchemaVersion,
                    customBlacklistPackages = blacklist,
                    customCurfewWhitelistPackages = whitelist,
                    customAiScanPackages = aiScan,
                    excludedAiScanPackages = excludedAiScan,
                    curfewVpnBypassPackages = vpnBypass,
                    protectedAppPackages = protectedApps,
                    browserFilterMode = browserFilterMode,
                    isFullSystemFilteringEnabled = isFullSystemFilteringEnabled,
                    customBlockedUrls = customBlockedUrls,
                    customAllowedUrls = customAllowedUrls,
                    dnsHost = dns,
                    isYouTubeRestrictedModeEnabled = ytRestricted,
                    isAdBlockingEnabled = adBlocking,
                    isLocalVpnFallbackEnabled = localVpnFallback,
                    isTelegramWebBlocked = tgBlocked,
                    isBlockExternalVpnEnabled = blockExternalVpn,
                    guardianUid = gUid, guardianName = gName,
                    isSupervised = supervised,
                    isSelfControlAllowed = isSelfControlAllowed,
                    selfControlCurfewAllowed = selfControlCurfewAllowed,
                    selfControlWebAllowed = selfControlWebAllowed,
                    selfControlAppsAllowed = selfControlAppsAllowed,
                    selfControlWaqfaAllowed = selfControlWaqfaAllowed,
                    selfControlNewAppsAllowed = selfControlNewAppsAllowed,
                    isDateTimeLocked = isDateTimeLocked,
                    isClearAppDataAllowed = isClearAppDataAllowed,
                    timeZoneId = timeZoneId, lastHeartbeat = lastHeartbeat,
                    breakoutStatus = breakoutStatus,
                    breakoutRequestedAt = breakoutRequestedAt, breakoutUnlockAt = breakoutUnlockAt,
                    deactivationStatus = deactivationStatus,
                    deactivationRequestedAt = deactivationRequestedAt, deactivationUnlockAt = deactivationUnlockAt,
                    deactivationHasQr = deactivationHasQr,
                    guardianPolicySnapshot = guardianPolicySnapshot,
                    lastSelfModifiedAt = lastSelfModifiedAt,
                    waqfaConfig = waqfa,
                    pageShieldConfig = pageShield,
                    selfControlPageShieldAllowed = selfControlPageShieldAllowed,
                    version = json.optLong("version", 1L),
                    updatedAt = json.optLong("updatedAt", 0L),
                    remoteSelfControlResetRequestedAt = remoteResetAt,
                    remoteTeardownRequestedAt = remoteTeardownAt,
                    wardHasAccessibilityService = wardHasAccessibilityService,
                    wardHasUsageStats = wardHasUsageStats,
                    wardCanWriteSecureSettings = wardCanWriteSecureSettings,
                    wardCapabilitiesUpdatedAt = wardCapabilitiesUpdatedAt,
                    wardA11yTamperAt = wardA11yTamperAt,
                    wardSdkInt = wardSdkInt,
                    fortressesConfig = fortressesConfig,
                    selfControlFortressesAllowed = selfControlFortressesAllowed,
                    selfControlFortressUnlockAllowed = selfControlFortressUnlockAllowed,
                    selfControlOtherSettingsAllowed = selfControlOtherSettingsAllowed,
                    remoteModeUnlockTarget = remoteModeUnlockTarget,
                    remoteModeUnlockRequestedAt = remoteModeUnlockRequestedAt,
                    wardActiveFortressId = wardActiveFortressId,
                    wardActiveFortressName = wardActiveFortressName,
                    wardActiveFortressIsEmergency = wardActiveFortressIsEmergency,
                    wardActiveFortressEndsAtWall = wardActiveFortressEndsAtWall,
                    wardIsCurfewActiveNow = wardIsCurfewActiveNow,
                    wardActiveModesUpdatedAt = wardActiveModesUpdatedAt
                )
            } catch (e: Exception) {
                try {
                    Log.w(TAG, "Error parsing DevicePolicyBundle from JSON: ${e.message}")
                } catch (_: Throwable) {
                    /* safe fallback for unit tests */
                }
                null
            }
        }
    }
}
