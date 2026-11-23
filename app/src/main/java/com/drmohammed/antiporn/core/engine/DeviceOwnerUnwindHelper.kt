package com.drmohammed.antiporn.core.engine

import android.Manifest
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.UserManager
import android.provider.Settings
import androidx.work.WorkManager
import com.drmohammed.antiporn.accessibility.control.AccessibilitySelfActivator
import com.drmohammed.antiporn.ai.overlay.ScreenBlurOverlayManager
import com.drmohammed.antiporn.ai.service.ScreenAiProjectionService
import com.drmohammed.antiporn.banking.BankingCloakManager
import com.drmohammed.antiporn.core.network.dns.DnsResilienceManager
import com.drmohammed.antiporn.core.policy.AppPolicyRegistry
import com.drmohammed.antiporn.curfew.CurfewBlackholeVpnService
import com.drmohammed.antiporn.curfew.CurfewConfig
import com.drmohammed.antiporn.curfew.CurfewEnforcer
import com.drmohammed.antiporn.curfew.CurfewScheduler
import com.drmohammed.antiporn.curfew.NapController
import com.drmohammed.antiporn.curfew.ZeroTrustForegroundWatcher
import com.drmohammed.antiporn.data.local.db.HisnRoomDatabase
import com.drmohammed.antiporn.data.repository.PolicyRepository
import com.drmohammed.antiporn.pageshield.engine.PageShieldReactionExecutor
import com.drmohammed.antiporn.shields.SystemTamperShield
import com.drmohammed.antiporn.waqfa.data.WaqfaRepository
import com.drmohammed.antiporn.waqfa.engine.WaqfaAlarmScheduler
import com.drmohammed.antiporn.waqfa.engine.WaqfaCooldownStore
import com.drmohammed.antiporn.waqfa.engine.WaqfaNotificationHelper
import com.drmohammed.antiporn.waqfa.engine.WaqfaSessionManager
import com.drmohammed.antiporn.waqfa.model.WaqfaSession
import com.drmohammed.antiporn.watchdog.WatchdogOrchestrator
import com.drmohammed.antiporn.work.CloudSyncWorker
import com.drmohammed.antiporn.work.WatchdogHealthWorker
import kotlinx.coroutines.CancellationException
import timber.log.Timber

/**
 * Isolated helper executing each cleanup step of Device Owner teardown inside
 * fault-tolerant boundaries so a single OEM quirk cannot abort ownership removal.
 */
internal object DeviceOwnerUnwindHelper {

    private const val TAG = "AntiPorn_DOUnwind"

    private suspend fun runStepSafely(stepName: String, block: suspend () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            Timber.tag(TAG).w(t, "Teardown step [%s] encountered non-fatal error: %s", stepName, t.message)
        }
    }

    /**
     * Clears DPM Always-On VPN lockdown BEFORE stopping the VPN service so the OS
     * does not keep holding a bound connection or re-establish the tunnel.
     */
    suspend fun clearAlwaysOnVpnAndStopService(
        appContext: Context,
        dpm: DevicePolicyManager,
        admin: ComponentName,
        isOwner: Boolean
    ) {
        if (isOwner) {
            runStepSafely("clearAlwaysOnVpnPackage") {
                dpm.setAlwaysOnVpnPackage(admin, null, false)
                Timber.tag(TAG).i("AlwaysOnVpnPackage cleared before stopping VPN service")
            }
        }
        runStepSafely("forceStopVpnService") {
            CurfewBlackholeVpnService.forceStopForTeardown(appContext)
            Timber.tag(TAG).i("CurfewBlackholeVpnService force-stopped during teardown")
        }
    }

    /**
     * Halts all background engines, floating overlays, scheduled alarms, transient flags,
     * and periodic WorkManager jobs so no protection loop can re-trigger during teardown.
     */
    suspend fun stopBackgroundEnginesAndAlarms(appContext: Context) {
        runStepSafely("stopWatchdog") {
            WatchdogOrchestrator.stopAll(appContext)
        }
        runStepSafely("stopScreenAiAndOverlays") {
            appContext.stopService(Intent(appContext, ScreenAiProjectionService::class.java))
            ScreenBlurOverlayManager.dismissAllOverlaysForBanking()
        }
        runStepSafely("cancelCurfewAndNapAndResilience") {
            CurfewScheduler.cancelCurfewAlarms(appContext)
            CurfewScheduler.cancelCurfewSnoozeAlarm(appContext)
            CurfewScheduler.cancelNapEndAlarm(appContext)
            CurfewEnforcer.clearSnooze(appContext)
            NapController.clearNapForTeardown(appContext)
            CurfewConfig.clearTransientFlagsForTeardown(appContext)
            DnsResilienceManager.resetStateSync()
        }
        runStepSafely("teardownWaqfa") {
            val waqfaRepo = WaqfaRepository.getInstance(appContext)
            val activeSessions = waqfaRepo.getAllActiveSessionsBlocking()
            for (session in activeSessions) {
                WaqfaAlarmScheduler.cancelSessionTimeoutAlarm(appContext, session.packageName)
                WaqfaAlarmScheduler.cancelPreTimeoutWarningAlarm(appContext, session.packageName)
            }
            WaqfaSessionManager.getInstance(appContext).cancelActiveSession(WaqfaSession.ACTION_USER_EARLY_EXIT)
            waqfaRepo.endAllActiveSessions(WaqfaSession.ACTION_USER_EARLY_EXIT)
            WaqfaNotificationHelper.cancelOngoingSessionNotification(appContext)
            WaqfaNotificationHelper.cancelPreTimeoutWarningNotification(appContext)

            val frozenPackages = WaqfaCooldownStore.getAllFrozenPackages(appContext)
            for (frozen in frozenPackages) {
                WaqfaAlarmScheduler.cancelCooldownEndAlarm(appContext, frozen.packageName)
                WaqfaCooldownStore.clearCooldown(appContext, frozen.packageName)
            }
        }
        runStepSafely("teardownPageShield") {
            PageShieldReactionExecutor.teardown(appContext)
        }
        runStepSafely("clearBankingCloak") {
            BankingCloakManager.clearForTeardown(appContext)
        }
        runStepSafely("cancelWorkManagerJobs") {
            val wm = WorkManager.getInstance(appContext)
            wm.cancelUniqueWork(WatchdogHealthWorker.WORK_NAME)
            wm.cancelUniqueWork(CloudSyncWorker.WORK_NAME)
        }
    }

    /**
     * Clears local approval requests and watcher state ONLY after Device/Profile Owner
     * privileges have been verified as removed.
     */
    suspend fun clearPostOwnershipLocalData(appContext: Context) {
        runStepSafely("clearPendingApprovals") {
            HisnRoomDatabase.getInstance(appContext).pendingApprovalDao().clearAllPendingApprovalsBlocking()
            ZeroTrustForegroundWatcher.syncWatcherState(appContext)
        }
    }

    /**
     * Scans all installed/uninstalled packages in one pass to unhide, unsuspend,
     * unblock uninstall, and clear managed application restrictions without leaving orphans.
     */
    suspend fun unwindAllManagedPackages(
        appContext: Context,
        dpm: DevicePolicyManager,
        admin: ComponentName,
        isDeviceOwner: Boolean
    ) {
        val pm = appContext.packageManager
        val rawFlags = PackageManager.MATCH_UNINSTALLED_PACKAGES or PackageManager.MATCH_DISABLED_COMPONENTS
        val installedPackages = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getInstalledPackages(PackageManager.PackageInfoFlags.of(rawFlags.toLong()))
            } else {
                @Suppress("DEPRECATION")
                pm.getInstalledPackages(rawFlags)
            }?.orEmpty() ?: emptyList()
        } catch (t: Throwable) {
            Timber.tag(TAG).w(t, "Failed enumerating packages during teardown")
            emptyList()
        }

        for (pkgInfo in installedPackages) {
            val pkg = pkgInfo.packageName ?: continue
            if (pkg == appContext.packageName) continue

            runStepSafely("unhide:$pkg") {
                if (dpm.isApplicationHidden(admin, pkg)) {
                    dpm.setApplicationHidden(admin, pkg, false)
                }
            }
            runStepSafely("unsuspend:$pkg") {
                if (dpm.isPackageSuspended(admin, pkg)) {
                    dpm.setPackagesSuspended(admin, arrayOf(pkg), false)
                }
            }
            runStepSafely("unblockUninstall:$pkg") {
                if (dpm.isUninstallBlocked(admin, pkg)) {
                    dpm.setUninstallBlocked(admin, pkg, false)
                }
            }
            runStepSafely("clearAppRestrictions:$pkg") {
                val existing: Bundle? = dpm.getApplicationRestrictions(admin, pkg)
                if (existing != null && !existing.isEmpty) {
                    dpm.setApplicationRestrictions(admin, pkg, Bundle())
                }
            }
        }

        // Static browser & protected package fallbacks in case a package wasn't returned above
        val extraBrowsers = (AppPolicyRegistry.getTargetBrowserPackages() + listOf(
            "org.chromium.chrome",
            "com.opera.gx"
        )).distinct()
        for (browserPkg in extraBrowsers) {
            runStepSafely("clearBrowserRestrictions:$browserPkg") {
                val existing: Bundle? = dpm.getApplicationRestrictions(admin, browserPkg)
                if (existing == null || !existing.isEmpty) {
                    dpm.setApplicationRestrictions(admin, browserPkg, Bundle())
                }
            }
        }

        runStepSafely("unblockProtectedFromPolicy") {
            val protectedPkgs = PolicyRepository.getInstance(appContext).getCurrentPolicy().protectedAppPackages
            for (pkg in protectedPkgs) {
                if (dpm.isUninstallBlocked(admin, pkg)) {
                    dpm.setUninstallBlocked(admin, pkg, false)
                }
            }
        }

        runStepSafely("unblockSelfUninstallAndData") {
            if (dpm.isUninstallBlocked(admin, appContext.packageName)) {
                dpm.setUninstallBlocked(admin, appContext.packageName, false)
            }
            if (isDeviceOwner && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val method = dpm.javaClass.getMethod(
                    "setClearApplicationUserDataBlocked",
                    ComponentName::class.java,
                    String::class.java,
                    Boolean::class.javaPrimitiveType
                )
                method.invoke(dpm, admin, appContext.packageName, false)
            }
        }
    }

    /**
     * Dynamically clears every restriction currently active in [DevicePolicyManager.getUserRestrictions]
     * plus the full static fallback list to guarantee zero orphaned user restrictions.
     */
    suspend fun clearAllUserRestrictions(
        dpm: DevicePolicyManager,
        admin: ComponentName
    ) {
        val dynamicKeys = try {
            val bundle: Bundle? = dpm.getUserRestrictions(admin)
            bundle?.keySet()?.filter { bundle.getBoolean(it, false) }.orEmpty()
        } catch (t: Throwable) {
            Timber.tag(TAG).w(t, "Failed reading dynamic user restrictions")
            emptyList()
        }

        @Suppress("DEPRECATION")
        val staticFallback = mutableListOf(
            UserManager.DISALLOW_SAFE_BOOT,
            UserManager.DISALLOW_FACTORY_RESET,
            UserManager.DISALLOW_ADD_USER,
            UserManager.DISALLOW_APPS_CONTROL,
            UserManager.DISALLOW_INSTALL_APPS,
            UserManager.DISALLOW_UNINSTALL_APPS,
            UserManager.DISALLOW_MODIFY_ACCOUNTS,
            UserManager.DISALLOW_NETWORK_RESET,
            UserManager.DISALLOW_CONFIG_VPN
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            @Suppress("DEPRECATION")
            staticFallback.add(UserManager.DISALLOW_ADD_MANAGED_PROFILE)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            staticFallback.add(UserManager.DISALLOW_USER_SWITCH)
            staticFallback.add(UserManager.DISALLOW_CONFIG_DATE_TIME)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            staticFallback.add(UserManager.DISALLOW_CONFIG_PRIVATE_DNS)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            staticFallback.add(SystemTamperShield.DISALLOW_ADD_CLONE_PROFILE)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            staticFallback.add(UserManager.DISALLOW_ADD_PRIVATE_PROFILE)
        }

        val allRestrictions = (dynamicKeys + staticFallback).distinct()
        for (restriction in allRestrictions) {
            runStepSafely("clearRestriction:$restriction") {
                dpm.clearUserRestriction(admin, restriction)
            }
        }
    }

    /**
     * Restores Private DNS, automatic time/timezone (strictly guarded for API 30+ vs legacy),
     * user switcher settings, DPM permission states, and cleanly disables Accessibility.
     * MUST be called AFTER [clearAllUserRestrictions].
     */
    suspend fun restorePrivateDnsAndTimeAndSystemSettings(
        appContext: Context,
        dpm: DevicePolicyManager,
        admin: ComponentName,
        isDeviceOwner: Boolean
    ) {
        if (isDeviceOwner && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runStepSafely("restoreDpmPrivateDns") {
                val res = dpm.setGlobalPrivateDnsModeOpportunistic(admin)
                Timber.tag(TAG).i("DPM Private DNS restored to Opportunistic (result=%d)", res)
            }
        }
        if (isDeviceOwner && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            runStepSafely("restoreGlobalPrivateDns") {
                Settings.Global.putString(appContext.contentResolver, "private_dns_mode", "opportunistic")
                Settings.Global.putString(appContext.contentResolver, "private_dns_specifier", "")
            }
        }

        if (isDeviceOwner) {
            runStepSafely("restoreUserSwitcher") {
                Settings.Global.putInt(appContext.contentResolver, "user_switcher_enabled", 1)
                Settings.Global.putInt(appContext.contentResolver, "guest_user_enabled", 1)
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                runStepSafely("restoreDpmAutoTimeApi30") {
                    dpm.setAutoTimeZoneEnabled(admin, true)
                    dpm.setAutoTimeEnabled(admin, true)
                }
            } else {
                runStepSafely("restoreDpmAutoTimeLegacy") {
                    @Suppress("DEPRECATION")
                    dpm.setAutoTimeRequired(admin, false)
                }
            }

            runStepSafely("restoreDpmGlobalTimeSettings") {
                dpm.setGlobalSetting(admin, Settings.Global.AUTO_TIME, "1")
                dpm.setGlobalSetting(admin, Settings.Global.AUTO_TIME_ZONE, "1")
            }
            runStepSafely("restoreContentResolverTimeSettings") {
                Settings.Global.putInt(appContext.contentResolver, Settings.Global.AUTO_TIME, 1)
                Settings.Global.putInt(appContext.contentResolver, Settings.Global.AUTO_TIME_ZONE, 1)
            }
        }

        runStepSafely("clearPermittedAccessibilityServices") {
            if (dpm.getPermittedAccessibilityServices(admin) != null) {
                dpm.setPermittedAccessibilityServices(admin, null)
            }
        }

        runStepSafely("resetDpmPermissionGrantStates") {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                dpm.setPermissionGrantState(
                    admin,
                    appContext.packageName,
                    Manifest.permission.POST_NOTIFICATIONS,
                    DevicePolicyManager.PERMISSION_GRANT_STATE_DEFAULT
                )
            }
            dpm.setPermissionGrantState(
                admin,
                appContext.packageName,
                Manifest.permission.SYSTEM_ALERT_WINDOW,
                DevicePolicyManager.PERMISSION_GRANT_STATE_DEFAULT
            )
        }

        runStepSafely("disableAccessibilityServiceCleanly") {
            AccessibilitySelfActivator.disableCleanly(appContext, isTemporaryCloak = false)
        }
    }
}
