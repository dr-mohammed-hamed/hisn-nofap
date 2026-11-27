package com.drmohammed.antiporn.curfew

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.drmohammed.antiporn.core.engine.ProtectionTransitionGuard
import com.drmohammed.antiporn.curfew.notification.ProtectionNotificationCoordinator
import com.drmohammed.antiporn.fortress.engine.EffectiveRestrictionResolver
import com.drmohammed.antiporn.fortress.model.EffectiveRestrictionProfile
import com.drmohammed.antiporn.telemetry.BlackBoxRecorder
import com.drmohammed.antiporn.telemetry.SafeDpmGateway
import timber.log.Timber

/**
 * Executes DevicePolicyManager package suspension, differential mode-overlap transitions,
 * daytime release, newly-installed interception, and stuck-app recovery.
 */
internal object CurfewLifecycleHandler {

    private const val TAG = "AntiPorn_CurfewLifecycle"
    private val unfreezableSystemPackages = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    fun isPackageInstalled(
        pm: PackageManager,
        packageName: String,
        includeHidden: Boolean = false
    ): Boolean {
        if (packageName.isBlank()) return false
        return try {
            val flagBits = if (includeHidden) PackageManager.MATCH_UNINSTALLED_PACKAGES else 0
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(flagBits.toLong()))
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(packageName, flagBits)
            }
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Unexpected error checking package installation for %s", packageName)
            false
        }
    }

    fun enforceActivation(
        context: Context,
        effectiveProfile: EffectiveRestrictionProfile = EffectiveRestrictionResolver.resolve(context)
    ): Boolean = ProtectionTransitionGuard.runInternalTransition {
        var overallSuccess = true
        val instantKill = effectiveProfile.isInstantInternetKillActive
        val vpnEnabled = effectiveProfile.shouldCutInternet

        val alwaysOnPkg = SafeDpmGateway.getAlwaysOnVpnPackageBlocking(context).getOrNull()
        val isLockdown = SafeDpmGateway.isAlwaysOnVpnLockdownEnabledBlocking(context).getOrNull() == true
        if (vpnEnabled) {
            val (_, vpnDpmOk) = CurfewVpnDnsBridge.ensureBlackholeVpnActive(context, alwaysOnPkg, isLockdown)
            if (!vpnDpmOk) overallSuccess = false
        } else {
            val requiredMode = CurfewBlackholeVpnService.evaluateRequiredMode(context)
            if (!CurfewVpnDnsBridge.reconcileNonBlackholeVpnAndDns(context, requiredMode, alwaysOnPkg, isLockdown)) {
                overallSuccess = false
            }
        }

        syncNotificationSafely(context, "enforceActivation")

        CurfewAppResolver.invalidateCache()
        val targets = CurfewAppResolver.getCurfewTargetPackages(context, effectiveProfile)
        val policySnapshot = CurfewLifecycleSupport.loadPolicyAndApprovalsFailSecure(context, "suspendCurfewApps")
        val pm = context.packageManager

        // Differential unsuspend: release tracked packages no longer targeted under the active effective profile
        if (!policySnapshot.failed) {
            val previousTracked = CurfewConfig.getTrackedSuspendedPackages(context)
            val storesAndSearch = CurfewConfig.OEM_APP_STORES + CurfewConfig.CURFEW_SEARCH_APPS
            val immunePackages = CurfewAppResolver.resolveImmunePackages(context, effectiveProfile)
            val diffCandidates = previousTracked.filter { pkg ->
                !targets.contains(pkg) &&
                    !storesAndSearch.contains(pkg) &&
                    CurfewLifecycleSupport.isPackageEligibleForDaytimeRelease(context, pm, pkg, policySnapshot, immunePackages) &&
                    SafeDpmGateway.isPackageSuspendedBlocking(context, pkg).getOrNull() == true
            }
            if (diffCandidates.isNotEmpty()) {
                val (diffOk, failedDiff) = suspendPackagesChecked(context, diffCandidates, suspend = false, label = "differential release")
                if (!diffOk) overallSuccess = false
                val releasedDiff = diffCandidates.filterNot { it in failedDiff }
                CurfewConfig.removeTrackedSuspendedPackages(context, releasedDiff)
            }
        }

        Timber.tag(TAG).i("Enforcing block ON for %d targeted packages", targets.size)
        val storesBatchToSuspend = (CurfewConfig.OEM_APP_STORES + CurfewConfig.CURFEW_SEARCH_APPS).filter { pkg ->
            isPackageInstalled(pm, pkg) && SafeDpmGateway.isPackageSuspendedBlocking(context, pkg).getOrNull() != true
        }
        val (storesOk, _) = suspendPackagesChecked(context, storesBatchToSuspend, suspend = true, label = "store/search")
        if (!storesOk) overallSuccess = false

        val installedTargets = targets.filter { isPackageInstalled(pm, it) && !unfreezableSystemPackages.contains(it) }
        val toSuspend = installedTargets.filter { pkg ->
            SafeDpmGateway.isPackageSuspendedBlocking(context, pkg).getOrNull() != true
        }
        CurfewConfig.setLastBlockEnforced(context, true)
        CurfewConfig.addTrackedSuspendedPackages(context, installedTargets)
        val (appsOk, failedApps) = suspendPackagesChecked(context, toSuspend, suspend = true, label = "curfew target")
        var unmitigatedFailure = false
        for (failed in failedApps) {
            if (CurfewLifecycleSupport.isSafeUnfreezableSystemApp(pm, failed, policySnapshot.customBlacklist)) {
                unfreezableSystemPackages.add(failed)
                Timber.tag(TAG).w("Package %s is unfreezable system/OEM app; cached", failed)
            } else {
                unmitigatedFailure = true
            }
        }
        if (failedApps.isNotEmpty()) {
            CurfewConfig.removeTrackedSuspendedPackages(context, failedApps)
        }
        if (!appsOk && unmitigatedFailure) overallSuccess = false
        val newlySuspendedApps = toSuspend.filterNot { it in failedApps }
        val activeTrackedApps = installedTargets.filterNot { it in failedApps }

        if (!ensureSafeImmuneUnsuspended(context, pm, effectiveProfile)) {
            overallSuccess = false
        }

        CurfewLifecycleSupport.clearLegacyInstallRestriction(context)
        CurfewConfig.setDnsRestorePending(context, false)
        CurfewConfig.setLastAppliedEffectiveSignature(
            context,
            if (overallSuccess) effectiveProfile.signature else ""
        )

        val failedDetails = if (failedApps.isNotEmpty()) ", failedPkgs=${failedApps.joinToString()}" else ""
        BlackBoxRecorder.recordCurfew(
            context,
            "CURFEW_ACTIVATED",
            isSuccess = overallSuccess,
            details = "targets=${targets.size}, suspended=${newlySuspendedApps.size}, tracked=${activeTrackedApps.size}, failed=${failedApps.size}$failedDetails, vpnEnabled=$vpnEnabled, instantKill=$instantKill, fortress=${effectiveProfile.activeFortress?.fortressId ?: "none"}"
        )
        overallSuccess
    }

    fun enforceRelease(context: Context): Boolean = ProtectionTransitionGuard.runInternalTransition {
        var overallSuccess = true
        val instantKill = CurfewConfig.isInstantInternetKillEnabled(context)
        val requiredMode = CurfewBlackholeVpnService.evaluateRequiredMode(context)
        val alwaysOnPkg = SafeDpmGateway.getAlwaysOnVpnPackageBlocking(context).getOrNull()
        val isLockdown = SafeDpmGateway.isAlwaysOnVpnLockdownEnabledBlocking(context).getOrNull() == true

        if (instantKill) {
            val (vpnChanged, vpnOk) = CurfewVpnDnsBridge.ensureBlackholeVpnActive(context, alwaysOnPkg, isLockdown)
            if (!vpnOk) overallSuccess = false
            if (vpnChanged) syncNotificationSafely(context, "enforceRelease InstantKill")
        }

        val isVpnTeardownNeeded = CurfewLifecycleSupport.isVpnReconcileNeeded(instantKill, requiredMode)
        val isAlwaysOnConfigured = CurfewLifecycleSupport.isAlwaysOnUpdateNeeded(context, instantKill, requiredMode, alwaysOnPkg, isLockdown)
        val wasBlockEnforced = CurfewConfig.isLastBlockEnforced(context)
        val recordedCurfewPackages = CurfewConfig.getTrackedSuspendedPackages(context)

        val policyState = CurfewLifecycleSupport.loadPolicyAndApprovalsFailSecure(context, "curfew release")
        if (policyState.failed) {
            if (CurfewEnforcer.isVpnTeardownAllowedOnReleaseFailSecure(
                    fetchPolicyFailed = policyState.policyFailed,
                    instantKill = instantKill,
                    isVpnTeardownNeeded = isVpnTeardownNeeded,
                    isAlwaysOnConfigured = isAlwaysOnConfigured
                )
            ) {
                CurfewVpnDnsBridge.reconcileNonBlackholeVpnAndDns(context, requiredMode, alwaysOnPkg, isLockdown)
            }
            Timber.tag(TAG).w("Fail-secure active: preserving suspended packages because approvals or policy could not be verified")
            return@runInternalTransition false
        }

        val pm = context.packageManager
        val immunePackages = CurfewAppResolver.resolveImmunePackages(context)
        val storesAndSearch = CurfewConfig.OEM_APP_STORES + CurfewConfig.CURFEW_SEARCH_APPS
        val installedStoresAndSearch = CurfewLifecycleSupport.filterEligibleStores(context, pm, storesAndSearch, policyState)
        val installedStoresSet = installedStoresAndSearch.toSet()

        val curfewTargets = if (wasBlockEnforced || recordedCurfewPackages.isNotEmpty()) {
            CurfewAppResolver.getCurfewTargetPackages(context)
        } else {
            emptySet()
        }
        val eligibleAppCandidates = (recordedCurfewPackages + immunePackages + policyState.approvedPackages + curfewTargets)
            .filterNot { installedStoresSet.contains(it) }
            .filter { CurfewLifecycleSupport.isPackageEligibleForDaytimeRelease(context, pm, it, policyState, immunePackages) }

        val currentlySuspendedInstalled = (installedStoresAndSearch + eligibleAppCandidates).toSet()
            .filterTo(mutableSetOf()) { pkg ->
                SafeDpmGateway.isPackageSuspendedBlocking(context, pkg).getOrNull() == true
            }
        val storesBatchToUnsuspend = installedStoresAndSearch.filter { it in currentlySuspendedInstalled }
        val appsBatchToUnsuspend = eligibleAppCandidates.filter { it in currentlySuspendedInstalled }

        if (!CurfewEnforcer.shouldExecuteReleaseCleanup(
                isVpnTeardownNeeded = isVpnTeardownNeeded,
                isAlwaysOnConfigured = isAlwaysOnConfigured,
                wasBlockEnforced = wasBlockEnforced,
                hasTrackedSuspendedPackages = recordedCurfewPackages.isNotEmpty(),
                hasLingeringSuspendedPackages = currentlySuspendedInstalled.isNotEmpty()
            )
        ) {
            CurfewConfig.setLastAppliedEffectiveSignature(context, "")
            syncNotificationSafely(context, "enforceRelease idle")
            return@runInternalTransition true
        }

        Timber.tag(TAG).i("Enforcing block OFF: Releasing daytime apps and stores")
        var dnsRestoreSuccess = true
        if (!instantKill) {
            if (!CurfewVpnDnsBridge.reconcileNonBlackholeVpnAndDns(context, requiredMode, alwaysOnPkg, isLockdown)) {
                overallSuccess = false
                dnsRestoreSuccess = false
            }
        }
        CurfewConfig.setDnsRestorePending(context, !dnsRestoreSuccess)

        CurfewLifecycleSupport.unhideLegacyStores(context, pm, storesAndSearch)
        CurfewLifecycleSupport.clearLegacyInstallRestriction(context)

        val (storesOk, failedStores) = suspendPackagesChecked(context, storesBatchToUnsuspend, suspend = false, label = "store/search release")
        val (appsOk, failedApps) = suspendPackagesChecked(context, appsBatchToUnsuspend, suspend = false, label = "curfew app release")
        if (!storesOk || !appsOk) overallSuccess = false

        val packagesToKeepTracked = failedApps.filter { it in recordedCurfewPackages }.toSet()
        val packagesToUntrack = recordedCurfewPackages.filterNot { it in packagesToKeepTracked }
        if (packagesToUntrack.isNotEmpty()) {
            CurfewConfig.removeTrackedSuspendedPackages(context, packagesToUntrack)
        }
        val stillHasPendingRelease = failedStores.isNotEmpty() || packagesToKeepTracked.isNotEmpty()
        CurfewConfig.setLastBlockEnforced(context, stillHasPendingRelease)
        CurfewConfig.setLastAppliedEffectiveSignature(context, "")

        BlackBoxRecorder.recordCurfew(
            context,
            "CURFEW_RELEASED",
            isSuccess = overallSuccess,
            details = "released=${appsBatchToUnsuspend.size - failedApps.size}, failedApps=${failedApps.size}, failedStores=${failedStores.size}, dnsRestoreOk=$dnsRestoreSuccess"
        )
        syncNotificationSafely(context, "enforceRelease")
        overallSuccess
    }

    fun handlePackageAdded(context: Context, packageName: String) {
        val appContext = context.applicationContext ?: context
        if (!CurfewEnforcer.isEffectiveBlockActive(appContext)) return

        val immune = CurfewAppResolver.resolveImmunePackages(appContext)
        if (immune.contains(packageName) ||
            packageName == CurfewConfig.GOOGLE_PLAY_STORE_PACKAGE ||
            packageName == CurfewConfig.GOOGLE_SEARCH_PACKAGE
        ) {
            Timber.tag(TAG).i("Newly installed package %s is immune/Play/Search; skipping suspension", packageName)
            return
        }
        if (!SafeDpmGateway.isDeviceOwner(appContext)) return

        try {
            val res = SafeDpmGateway.setPackagesSuspendedBlocking(appContext, arrayOf(packageName), true)
            val failed = res.getOrNull()?.toSet() ?: emptySet()
            if (res.isSuccess && !failed.contains(packageName)) {
                CurfewConfig.addTrackedSuspendedPackages(appContext, listOf(packageName))
                Timber.tag(TAG).i("Newly installed package %s immediately suspended", packageName)
            } else {
                Timber.tag(TAG).w("Failed suspending newly installed package %s (isSuccess=%b)", packageName, res.isSuccess)
            }
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Error suspending newly installed package %s", packageName)
        }
    }

    fun repairStuckSuspendedApps(context: Context): Int {
        val appContext = context.applicationContext ?: context
        if (!SafeDpmGateway.isDeviceOwner(appContext)) return 0
        val pm = appContext.packageManager ?: return 0
        val isCurfewRunning = CurfewEnforcer.isEffectiveBlockActive(appContext)
        val policyState = CurfewLifecycleSupport.loadPolicyAndApprovalsFailSecure(appContext, "stuck app repair")
        if (policyState.failed) return 0

        val immunePackages = CurfewAppResolver.resolveImmunePackages(appContext)
        val installed = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.getInstalledApplications(0)
            }
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Failed querying installed applications during stuck app repair")
            emptyList()
        }

        val stuckToUnsuspend = installed.mapNotNull { app ->
            val pkg = app.packageName
            if (pkg == appContext.packageName) return@mapNotNull null
            if (isCurfewRunning && !immunePackages.contains(pkg)) return@mapNotNull null
            if (!CurfewLifecycleSupport.isAppSafeToUnsuspend(appContext, app, policyState, immunePackages)) return@mapNotNull null
            if (SafeDpmGateway.isPackageSuspendedBlocking(appContext, pkg).getOrNull() == true) pkg else null
        }
        if (stuckToUnsuspend.isEmpty()) return 0

        val (ok, failed) = suspendPackagesChecked(appContext, stuckToUnsuspend, suspend = false, label = "stuck app repair")
        val repairedCount = stuckToUnsuspend.count { it !in failed }
        if (!ok) Timber.tag(TAG).w("[REPAIR] Completed with failures: repaired=$repairedCount, failed=$failed")
        else Timber.tag(TAG).i("[REPAIR] Successfully unsuspended $repairedCount stuck apps")
        return repairedCount
    }

    private fun ensureSafeImmuneUnsuspended(
        context: Context,
        pm: PackageManager,
        effectiveProfile: EffectiveRestrictionProfile
    ): Boolean {
        val policyState = CurfewLifecycleSupport.loadPolicyAndApprovalsFailSecure(context, "curfew activation")
        val immunePackages = CurfewAppResolver.resolveImmunePackages(context, effectiveProfile)
        val immuneToUnsuspend = if (policyState.failed) {
            CurfewAppResolver.resolveCoreSystemCriticalPackages(context).filter { pkg ->
                isPackageInstalled(pm, pkg) && SafeDpmGateway.isPackageSuspendedBlocking(context, pkg).getOrNull() == true
            }
        } else {
            immunePackages.filter { pkg ->
                isPackageInstalled(pm, pkg) &&
                    !policyState.customBlacklist.contains(pkg) &&
                    !policyState.unapprovedPackages.contains(pkg) &&
                    SafeDpmGateway.isPackageSuspendedBlocking(context, pkg).getOrNull() == true
            }
        }
        if (immuneToUnsuspend.isEmpty()) return true
        val (ok, _) = suspendPackagesChecked(context, immuneToUnsuspend, suspend = false, label = "immune activation restore")
        return ok
    }

    private fun suspendPackagesChecked(
        context: Context,
        packages: List<String>,
        suspend: Boolean,
        label: String
    ): Pair<Boolean, Set<String>> {
        if (packages.isEmpty()) return true to emptySet()
        return try {
            val res = SafeDpmGateway.setPackagesSuspendedBlocking(context, packages.toTypedArray(), suspend)
            if (!res.isSuccess) {
                Timber.tag(TAG).e("DPM batch (%s, suspend=%b) failed for %d packages", label, suspend, packages.size)
                false to packages.toSet()
            } else {
                val failed = res.getOrNull()?.toSet() ?: emptySet()
                val ok = failed.isEmpty()
                if (!ok) Timber.tag(TAG).w("DPM batch (%s, suspend=%b) partial failure: %s", label, suspend, failed)
                ok to failed
            }
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Error executing DPM batch (%s, suspend=%b)", label, suspend)
            false to packages.toSet()
        }
    }

    private fun syncNotificationSafely(context: Context, caller: String) {
        try {
            ProtectionNotificationCoordinator.syncProtectionNotification(context)
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Error synchronizing protection notification in %s", caller)
        }
    }

    @Suppress("UNUSED_PARAMETER")
    internal fun isApprovalEligibleForRelease(
        packageName: String,
        isApproved: Boolean,
        decidedBy: String?
    ): Boolean = isApproved

    @Suppress("UNUSED_PARAMETER")
    internal fun isHighRiskOrBlacklistedForRelease(
        pkg: String,
        approvedPackages: Set<String>,
        customBlacklist: Set<String>
    ): Boolean = customBlacklist.contains(pkg)
}
