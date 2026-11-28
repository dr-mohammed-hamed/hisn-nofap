package com.drmohammed.antiporn.curfew

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.UserManager
import com.drmohammed.antiporn.data.repository.ApprovalRepository
import com.drmohammed.antiporn.data.repository.PolicyRepository
import com.drmohammed.antiporn.pageshield.engine.PageShieldFreezeStore
import com.drmohammed.antiporn.shields.BrowserShield
import com.drmohammed.antiporn.telemetry.SafeDpmGateway
import com.drmohammed.antiporn.waqfa.engine.WaqfaCooldownStore
import timber.log.Timber

/**
 * Policy/approval snapshot and eligibility helpers supporting [CurfewLifecycleHandler].
 */
internal object CurfewLifecycleSupport {

    private const val TAG = "AntiPorn_CurfewSupport"

    data class PolicyApprovalSnapshot(
        val policyFailed: Boolean,
        val approvalsFailed: Boolean,
        val customBlacklist: Set<String>,
        val isZeroTrustActive: Boolean,
        val approvedPackages: Set<String>,
        val unapprovedPackages: Set<String>
    ) {
        val failed: Boolean get() = policyFailed || approvalsFailed
    }

    fun loadPolicyAndApprovalsFailSecure(context: Context, caller: String): PolicyApprovalSnapshot {
        var policyFailed = false
        val currentPolicy = try {
            PolicyRepository.getInstance(context).getCurrentPolicy()
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Error fetching policy during %s - failing secure", caller)
            policyFailed = true
            null
        }
        var approvalsFailed = false
        val localApprovals = try {
            ApprovalRepository.getInstance(context).getLocalPendingApprovals()
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Error fetching pending approvals during %s - failing secure", caller)
            approvalsFailed = true
            emptyList()
        }
        val approvedPackages = localApprovals.filter {
            CurfewLifecycleHandler.isApprovalEligibleForRelease(it.packageName, it.isApproved, it.decidedBy)
        }.map { it.packageName }.toSet()
        val unapprovedPackages = localApprovals.filter { !it.isApproved }.map { it.packageName }.toSet()
        return PolicyApprovalSnapshot(
            policyFailed = policyFailed,
            approvalsFailed = approvalsFailed,
            customBlacklist = currentPolicy?.customBlacklistPackages ?: emptySet(),
            isZeroTrustActive = currentPolicy?.lockNewInstallsUntilApproved ?: false,
            approvedPackages = approvedPackages,
            unapprovedPackages = unapprovedPackages
        )
    }

    fun isSafeUnfreezableSystemApp(
        pm: PackageManager,
        pkg: String,
        customBlacklist: Set<String> = emptySet()
    ): Boolean = try {
        val ai = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getApplicationInfo(pkg, PackageManager.ApplicationInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.getApplicationInfo(pkg, 0)
        }
        val isSystem = (ai.flags and ApplicationInfo.FLAG_SYSTEM != 0) ||
            (ai.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP != 0)
        val isStoreOrSearch = CurfewConfig.KNOWN_APP_STORES.contains(pkg) ||
            pkg == CurfewConfig.GOOGLE_PLAY_STORE_PACKAGE ||
            pkg == CurfewConfig.GOOGLE_SEARCH_PACKAGE ||
            CurfewConfig.CURFEW_SEARCH_APPS.contains(pkg)
        val isBrowser = BrowserShield.TARGET_BROWSER_PACKAGES.contains(pkg)
        isSystem && !isStoreOrSearch && !isBrowser && !customBlacklist.contains(pkg)
    } catch (_: Exception) {
        false
    }

    fun filterEligibleStores(
        context: Context,
        pm: PackageManager,
        storesAndSearch: Set<String>,
        policyState: PolicyApprovalSnapshot
    ): List<String> = storesAndSearch.filter { pkg ->
        when {
            !CurfewLifecycleHandler.isPackageInstalled(pm, pkg) -> false
            CurfewLifecycleHandler.isHighRiskOrBlacklistedForRelease(pkg, policyState.approvedPackages, policyState.customBlacklist) -> {
                Timber.tag(TAG).i("Preserving suspension of %s on release (custom-blacklisted)", pkg)
                false
            }
            else -> !policyState.unapprovedPackages.contains(pkg) &&
                !WaqfaCooldownStore.isPackageInCooldown(context, pkg) &&
                !PageShieldFreezeStore.isFrozen(context, pkg)
        }
    }

    fun isPackageEligibleForDaytimeRelease(
        context: Context,
        pm: PackageManager,
        pkg: String,
        policyState: PolicyApprovalSnapshot,
        immunePackages: Set<String>
    ): Boolean {
        if (!CurfewLifecycleHandler.isPackageInstalled(pm, pkg)) return false
        if (CurfewLifecycleHandler.isHighRiskOrBlacklistedForRelease(pkg, policyState.approvedPackages, policyState.customBlacklist)) return false
        if (policyState.unapprovedPackages.contains(pkg)) return false
        if (WaqfaCooldownStore.isPackageInCooldown(context, pkg)) return false
        if (PageShieldFreezeStore.isFrozen(context, pkg)) return false
        if (!policyState.isZeroTrustActive) return true

        val isApproved = policyState.approvedPackages.contains(pkg)
        val isImmuneOrStore = CurfewAppResolver.isSystemCriticalOrImmune(context, pkg) ||
            immunePackages.contains(pkg) ||
            pkg == CurfewConfig.GOOGLE_PLAY_STORE_PACKAGE ||
            pkg == CurfewConfig.GOOGLE_SEARCH_PACKAGE ||
            CurfewConfig.KNOWN_APP_STORES.contains(pkg)
        val isSystemOrUpdatedApp = try {
            val ai = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getApplicationInfo(pkg, PackageManager.ApplicationInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.getApplicationInfo(pkg, 0)
            }
            (ai.flags and ApplicationInfo.FLAG_SYSTEM != 0) ||
                (ai.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP != 0)
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Failed reading ApplicationInfo for %s during release check", pkg)
            false
        }
        return isApproved || isImmuneOrStore || isSystemOrUpdatedApp
    }

    fun isAppSafeToUnsuspend(
        context: Context,
        app: ApplicationInfo,
        policyState: PolicyApprovalSnapshot,
        immunePackages: Set<String>
    ): Boolean {
        val pkg = app.packageName
        if (CurfewLifecycleHandler.isHighRiskOrBlacklistedForRelease(pkg, policyState.approvedPackages, policyState.customBlacklist)) return false
        if (policyState.unapprovedPackages.contains(pkg)) return false
        if (WaqfaCooldownStore.isPackageInCooldown(context, pkg)) return false
        if (PageShieldFreezeStore.isFrozen(context, pkg)) return false
        if (!policyState.isZeroTrustActive) return true

        val isImmune = immunePackages.contains(pkg) ||
            pkg == CurfewConfig.GOOGLE_PLAY_STORE_PACKAGE ||
            pkg == CurfewConfig.GOOGLE_SEARCH_PACKAGE ||
            CurfewConfig.KNOWN_APP_STORES.contains(pkg)
        val isSystem = (app.flags and ApplicationInfo.FLAG_SYSTEM != 0) ||
            (app.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP != 0)
        return policyState.approvedPackages.contains(pkg) || isImmune || isSystem
    }

    fun clearLegacyInstallRestriction(context: Context) {
        try {
            if (SafeDpmGateway.hasUserRestriction(context, UserManager.DISALLOW_INSTALL_APPS)) {
                SafeDpmGateway.clearUserRestrictionBlocking(context, UserManager.DISALLOW_INSTALL_APPS)
            }
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Could not ensure DISALLOW_INSTALL_APPS restriction is cleared")
        }
    }

    fun unhideLegacyStores(context: Context, pm: PackageManager, storesAndSearch: Set<String>) {
        for (pkg in storesAndSearch) {
            try {
                if (CurfewLifecycleHandler.isPackageInstalled(pm, pkg, includeHidden = true) &&
                    SafeDpmGateway.isApplicationHiddenBlocking(context, pkg).getOrNull() == true
                ) {
                    SafeDpmGateway.setApplicationHiddenBlocking(context, pkg, false)
                }
            } catch (e: Exception) {
                Timber.tag(TAG).w(e, "Could not unhide package %s", pkg)
            }
        }
    }

    fun isVpnReconcileNeeded(
        instantKill: Boolean,
        requiredMode: CurfewBlackholeVpnService.VpnMode,
        isServiceOrTunnelActive: Boolean = CurfewBlackholeVpnService.isServiceOrTunnelActive(),
        currentMode: CurfewBlackholeVpnService.VpnMode = CurfewBlackholeVpnService.currentMode
    ): Boolean {
        if (instantKill || !isServiceOrTunnelActive) return false
        return when (requiredMode) {
            CurfewBlackholeVpnService.VpnMode.IDLE -> true
            CurfewBlackholeVpnService.VpnMode.DNS_FILTERING_ONLY ->
                currentMode == CurfewBlackholeVpnService.VpnMode.CURFEW_BLACKHOLE
            CurfewBlackholeVpnService.VpnMode.CURFEW_BLACKHOLE -> false
        }
    }

    fun isAlwaysOnUpdateNeeded(
        context: Context,
        instantKill: Boolean,
        requiredMode: CurfewBlackholeVpnService.VpnMode,
        alwaysOnPkg: String?,
        isLockdown: Boolean
    ): Boolean {
        if (instantKill) return false
        val targetPkg = CurfewVpnDnsBridge.resolveTargetAlwaysOnPackage(context, requiredMode)
        return when (requiredMode) {
            CurfewBlackholeVpnService.VpnMode.DNS_FILTERING_ONLY ->
                alwaysOnPkg != targetPkg || isLockdown
            else ->
                alwaysOnPkg == context.packageName || (alwaysOnPkg == null && isLockdown)
        }
    }
}
