package com.drmohammed.antiporn.ui.shields

import android.content.Context
import android.os.Build
import android.os.UserManager
import com.drmohammed.antiporn.R
import com.drmohammed.antiporn.core.engine.ProtectionEngine
import com.drmohammed.antiporn.core.model.DevicePolicyBundle
import com.drmohammed.antiporn.core.model.ShieldStatus
import com.drmohammed.antiporn.core.network.DnsFilteringValidator
import com.drmohammed.antiporn.curfew.CurfewConfig
import com.drmohammed.antiporn.data.repository.PolicyRepository
import com.drmohammed.antiporn.shields.AppSuspensionShield
import com.drmohammed.antiporn.shields.BrowserShield
import com.drmohammed.antiporn.shields.CurfewShield
import com.drmohammed.antiporn.shields.DnsShield
import com.drmohammed.antiporn.shields.VpnShield
import java.util.TimeZone

/**
 * Strict ground-truth evaluator for currently active security shields.
 * Only returns shields whose security invariants are 100% active and enforced.
 */
object ActiveShieldsEvaluator {

    /**
     * Evaluates and returns all active protection shields.
     * Does NOT return disabled, partially-configured, or pending shields.
     *
     * @param precomputedStatuses Optional pre-evaluated shield map to avoid redundant DPM queries.
     */
    @JvmOverloads
    fun evaluateActiveShields(
        context: Context,
        precomputedStatuses: Map<String, ShieldStatus>? = null
    ): List<ActiveShieldInfo> {
        val appContext = context.applicationContext ?: context
        val isDeviceOwner = ProtectionEngine.isDeviceOwner(appContext)
        if (!isDeviceOwner) {
            return emptyList()
        }

        val badgeText = context.getString(R.string.shield_active_badge)
        val activeList = mutableListOf<ActiveShieldInfo>()
        val systemStatuses = precomputedStatuses ?: ProtectionEngine.evaluateSystemStatus(appContext)
        val policy = try {
            PolicyRepository.getInstance(appContext).getCurrentPolicy()
        } catch (e: Exception) {
            android.util.Log.w("ActiveShieldsEvaluator", "Failed reading current policy for ActiveShieldsEvaluator", e)
            null
        }

        // 1. Full Privilege Shield (Device Owner)
        activeList.add(
            ActiveShieldInfo(
                id = "device_owner",
                title = context.getString(R.string.shield_active_do_title),
                description = context.getString(R.string.shield_active_do_desc),
                iconRes = R.drawable.ic_shield_device_owner,
                badgeText = badgeText
            )
        )

        // 2. AntiPorn DNS Shield
        val isDnsActive = systemStatuses[DnsShield.id] is ShieldStatus.Active
        val effectiveHost = DnsShield.getEffectiveDnsHost(appContext)
        val isLocalVpnFiltering = com.drmohammed.antiporn.curfew.CurfewBlackholeVpnService.isServiceOrTunnelActive() &&
            com.drmohammed.antiporn.curfew.CurfewBlackholeVpnService.currentMode == com.drmohammed.antiporn.curfew.CurfewBlackholeVpnService.VpnMode.DNS_FILTERING_ONLY
        val actualOsDnsHost = if (!isLocalVpnFiltering && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                val gMode = android.provider.Settings.Global.getString(appContext.contentResolver, "private_dns_mode")
                val gSpec = android.provider.Settings.Global.getString(appContext.contentResolver, "private_dns_specifier")
                if (gMode == "hostname" && !gSpec.isNullOrBlank()) gSpec else null
            } catch (_: Exception) {
                null
            }
        } else {
            null
        }
        val displayedHost = actualOsDnsHost ?: effectiveHost
        val isAdultBlockingDns = isHostAdultFiltering(displayedHost) ||
            (policy?.dnsHost?.isNotBlank() == true && displayedHost.equals(policy.dnsHost.trim(), ignoreCase = true))
        if (isDnsActive && (isAdultBlockingDns || isLocalVpnFiltering)) {
            val desc = if (isLocalVpnFiltering) {
                if (com.drmohammed.antiporn.core.network.dns.DnsResilienceManager.isLocalVpnFallbackActive()) {
                    context.getString(R.string.shield_dns_resilience_doh_active)
                } else {
                    context.getString(R.string.shield_dns_local_filter_active)
                }
            } else {
                context.getString(R.string.shield_active_dns_desc, displayedHost)
            }
            activeList.add(
                ActiveShieldInfo(
                    id = "dns",
                    title = context.getString(R.string.shield_active_dns_title),
                    description = desc,
                    iconRes = R.drawable.ic_shield_dns,
                    badgeText = badgeText
                )
            )
        }

        // 3. Night Curfew Shield (الحصن الليلي)
        if (shouldIncludeCurfewShield(policy, systemStatuses[CurfewShield.id])) {
            activeList.add(
                ActiveShieldInfo(
                    id = "curfew",
                    title = context.getString(R.string.shield_active_curfew_title),
                    description = context.getString(R.string.shield_active_curfew_desc),
                    iconRes = R.drawable.ic_shield_curfew,
                    badgeText = badgeText
                )
            )
        }

        // 4. Browser Incognito & SafeSearch Shield
        val isIncognitoBlockedPolicy = policy?.isIncognitoBlocked ?: false
        val isBrowserShieldActive = systemStatuses[BrowserShield.id] is ShieldStatus.Active
        if (isIncognitoBlockedPolicy && isBrowserShieldActive) {
            activeList.add(
                ActiveShieldInfo(
                    id = "browser",
                    title = context.getString(R.string.shield_active_browser_title),
                    description = context.getString(R.string.shield_active_browser_desc),
                    iconRes = R.drawable.ic_shield_incognito,
                    badgeText = badgeText
                )
            )
        }

        // 5. Prevent Time Tampering Shield (منع تغيير الوقت)
        val isDateTimeLockedPolicy = policy?.isDateTimeLocked ?: false
        if (isDateTimeLockedPolicy && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val isDateTimeRestricted = com.drmohammed.antiporn.telemetry.SafeDpmGateway.hasUserRestriction(appContext, UserManager.DISALLOW_CONFIG_DATE_TIME)

            val targetTzId = CurfewConfig.getTargetTimeZoneId(appContext)
            val targetTz = TimeZone.getTimeZone(targetTzId)
            val currentTz = TimeZone.getDefault()
            val trustedTime = com.drmohammed.antiporn.core.time.TrustedTimeProvider.currentTimeMillis(appContext)
            val isTargetTz = currentTz.id == targetTzId || currentTz.hasSameRules(targetTz) ||
                currentTz.getOffset(trustedTime) == targetTz.getOffset(trustedTime)

            if (isDateTimeRestricted && isTargetTz) {
                activeList.add(
                    ActiveShieldInfo(
                        id = "time_tamper",
                        title = context.getString(R.string.shield_active_time_title),
                        description = context.getString(R.string.shield_active_time_desc),
                        iconRes = R.drawable.ic_shield_time,
                        badgeText = badgeText
                    )
                )
            }
        }

        // 6. Freeze New Apps Shield (Zero-Trust)
        val lockNewInstalls = policy?.lockNewInstallsUntilApproved ?: false
        val isAppSuspensionActive = systemStatuses[AppSuspensionShield.id] is ShieldStatus.Active
        if (lockNewInstalls && isAppSuspensionActive) {
            activeList.add(
                ActiveShieldInfo(
                    id = "freeze_new_apps",
                    title = context.getString(R.string.shield_active_freeze_title),
                    description = context.getString(R.string.shield_active_freeze_desc),
                    iconRes = R.drawable.ic_shield_freeze,
                    badgeText = badgeText
                )
            )
        }

        // 7. VPN Restriction Shield
        val isVpnBlocked = systemStatuses[VpnShield.id] is ShieldStatus.Active
        if (isVpnBlocked) {
            activeList.add(
                ActiveShieldInfo(
                    id = "vpn",
                    title = context.getString(R.string.shield_active_vpn_title),
                    description = context.getString(R.string.shield_active_vpn_desc),
                    iconRes = R.drawable.ic_shield_vpn,
                    badgeText = badgeText
                )
            )
        }

        // 8. Page Shield (درع الصفحات)
        val isA11yActive = com.drmohammed.antiporn.accessibility.HisnAccessibilityService.instance != null
        if (shouldIncludePageShield(policy, isA11yActive)) {
            val count = policy?.pageShieldConfig?.getTargetPackages()?.size ?: 0
            val desc = context.getString(R.string.page_shield_active_desc, count)
            activeList.add(
                ActiveShieldInfo(
                    id = "page_shield",
                    title = context.getString(R.string.page_shield_title),
                    description = desc,
                    iconRes = R.drawable.ic_shield_page,
                    badgeText = badgeText
                )
            )
        }

        return activeList
    }

    internal fun shouldIncludeCurfewShield(
        policy: DevicePolicyBundle?,
        curfewStatus: ShieldStatus?
    ): Boolean = (policy?.isCurfewEnabled ?: false) && curfewStatus is ShieldStatus.Active

    internal fun shouldIncludePageShield(
        policy: DevicePolicyBundle?,
        isA11yActive: Boolean
    ): Boolean = policy?.pageShieldConfig?.isEnabled == true &&
        policy.pageShieldConfig.getTargetPackages().isNotEmpty() &&
        isA11yActive

    /**
     * Determines whether the given host is an exact known adult-filtering DNS preset.
     */
    fun isHostAdultFiltering(host: String): Boolean {
        val clean = host.trim().lowercase()
        return DnsFilteringValidator.isKnownFamilyPreset(clean) ||
                clean == "adult-filter-dns.cleanbrowsing.org"
    }
}
