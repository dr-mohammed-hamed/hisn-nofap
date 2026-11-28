package com.drmohammed.antiporn

import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.work.Configuration
import com.drmohammed.antiporn.core.time.TrustedTimeProvider
import com.drmohammed.antiporn.curfew.ZeroTrustForegroundWatcher
import com.drmohammed.antiporn.data.local.db.HisnRoomDatabase
import com.drmohammed.antiporn.data.repository.PolicyRepository
import com.drmohammed.antiporn.di.appModule
import com.drmohammed.antiporn.notification.registry.NotificationRegistry
import com.drmohammed.antiporn.ota.repository.OtaRepository
import com.drmohammed.antiporn.provisioning.engine.ProvisioningFreezeRecoveryStore
import com.drmohammed.antiporn.provisioning.service.AdbPairingNotificationFactory
import com.drmohammed.antiporn.provisioning.ui.ProvisioningWizardActivity
import com.drmohammed.antiporn.telemetry.GlobalCrashHandler
import com.drmohammed.antiporn.work.CloudSyncWorker
import com.drmohammed.antiporn.work.OtaUpdateWorker
import com.drmohammed.antiporn.work.WatchdogHealthWorker
import com.google.android.material.color.DynamicColors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.androidx.workmanager.factory.KoinWorkerFactory
import org.koin.androidx.workmanager.koin.workManagerFactory
import org.koin.core.context.GlobalContext
import org.koin.core.context.startKoin
import org.koin.core.logger.Level
import timber.log.Timber

/**
 * Custom Application class for AntiPorn.
 * Configures Koin, WorkManager on-demand initialization via [Configuration.Provider],
 * trusted time synchronization, and lifecycle security shields.
 */
class AntiPornApplication : Application(), Configuration.Provider {

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(KoinWorkerFactory())
            .build()

    override fun onCreate() {
        super.onCreate()

        // Plant Timber tree for structured logging in debug builds
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }

        // Initialize Koin Dependency Injection
        if (GlobalContext.getOrNull() == null) {
            startKoin {
                androidLogger(Level.ERROR)
                androidContext(this@AntiPornApplication)
                workManagerFactory()
                modules(appModule)
            }
        }

        // 1. Install Global Crash Handler immediately before any subsystem initializes
        GlobalCrashHandler.install(this)

        if (DynamicColors.isDynamicColorAvailable()) {
            DynamicColors.applyToActivitiesIfAvailable(this)
        }

        // Initialize tamper-proof Trusted Time subsystem on cold boot
        TrustedTimeProvider.initialize(this)
        TrustedTimeProvider.onTimeDriftCorrected = { driftMs ->
            Timber.i("Trusted time drift of ${driftMs}ms corrected; rescheduling alarms and verifying curfew/block state")
            com.drmohammed.antiporn.curfew.CurfewScheduler.scheduleNextAlarms(this)
            com.drmohammed.antiporn.fortress.engine.FortressSessionController.migrateLegacyNapSessionIfNeeded(this)
            if (com.drmohammed.antiporn.fortress.engine.FortressSessionController.isFortressActive(this) &&
                com.drmohammed.antiporn.fortress.engine.FortressSessionController.remainingMs(this) > 0L
            ) {
                com.drmohammed.antiporn.curfew.CurfewScheduler.scheduleFortressEndAlarm(
                    this,
                    com.drmohammed.antiporn.fortress.engine.FortressSessionController.getEndWall(this)
                )
            } else {
                com.drmohammed.antiporn.curfew.CurfewScheduler.cancelFortressEndAlarm(this)
            }
            if (com.drmohammed.antiporn.curfew.CurfewEnforcer.isSnoozeActive(this)) {
                com.drmohammed.antiporn.curfew.CurfewScheduler.scheduleCurfewSnoozeAlarm(
                    this,
                    com.drmohammed.antiporn.curfew.CurfewEnforcer.getSnoozeEndWall(this)
                )
            } else {
                com.drmohammed.antiporn.curfew.CurfewScheduler.cancelCurfewSnoozeAlarm(this)
            }
            com.drmohammed.antiporn.curfew.CurfewEnforcer.syncBlockStateAsync(this)
        }
        TrustedTimeProvider.syncNowAsync(this)

        // Wire Accessibility capability/tamper cloud sync hook before initializing repositories
        com.drmohammed.antiporn.accessibility.control.AccessibilityPolicyCoordinator.onCapabilitiesChangedCallback = { ctx, recordTamper ->
            com.drmohammed.antiporn.data.repository.AccountabilityRepository.getInstance(ctx)
                .syncWardCapabilitiesAsync(recordTamper)
        }

        // Execute safe, zero-disruption migration check for existing users and fresh installs
        com.drmohammed.antiporn.cloud.CloudSyncManager.performSafeMigrationCheck(this, applicationScope)

        // Initialize PolicyRepository on cold boot so local policies load into memory immediately
        PolicyRepository.getInstance(this)

        // Hydrate persisted DNS resilience state synchronously BEFORE any Always-On VPN bind/receiver can race
        com.drmohammed.antiporn.core.network.dns.DnsResilienceManager.ensureHydrated(this)

        // Initialize central Notification Channels
        com.drmohammed.antiporn.notification.manager.AppNotificationManager.getInstance(this).createNotificationChannels()

        // Start Guardian supervision coordinator only if cloud sync is actively enabled
        if (com.drmohammed.antiporn.cloud.CloudSyncManager.isCloudSyncEnabled(this)) {
            com.drmohammed.antiporn.accountability.coordinator.GuardianSupervisionCoordinator.getInstance(this).startSupervising()
        }

        // Register Accessibility Core modules and seed initial policy
        com.drmohammed.antiporn.accessibility.core.AccessibilityCoreBootstrap.ensureInitialized(this)

        // Synchronize Zero-Trust active foreground watcher state on startup
        ZeroTrustForegroundWatcher.syncWatcherState(this)

        // Self-healing watchdog: check for stuck user_setup_complete state on cold boot
        try {
            val setupComplete = android.provider.Settings.Secure.getInt(contentResolver, "user_setup_complete", 1)
            val provisioned = android.provider.Settings.Global.getInt(contentResolver, android.provider.Settings.Global.DEVICE_PROVISIONED, 1)
            if (setupComplete == 0 && provisioned == 1) {
                Timber.w("AntiPorn Boot Watchdog: Detected stuck user_setup_complete=0 on provisioned device.")
                try {
                    android.provider.Settings.Secure.putInt(contentResolver, "user_setup_complete", 1)
                } catch (t: Throwable) {
                    Timber.w(t, "Setup state watchdog: Failed to restore user_setup_complete")
                }
            }
        } catch (e: Exception) {
            Timber.w(e, "Setup state watchdog check failed")
        }

        // Schedule periodic background workers with network constraints and ExistingPeriodicWorkPolicy.KEEP
        OtaUpdateWorker.schedule(this)
        CloudSyncWorker.schedule(this)
        WatchdogHealthWorker.schedule(this)

        // Prune diagnostic events, in-app notifications, and obsolete OTA APKs in background (non-blocking)
        applicationScope.launch {
            try {
                // Purge obsolete OTA update APKs and temp downloads from cacheDir
                try {
                    val purgedCount = OtaRepository.getInstance(this@AntiPornApplication).purgeOtaCache(this@AntiPornApplication)
                    if (purgedCount > 0) {
                        Timber.i("Cleaned up $purgedCount obsolete OTA package(s) on application launch")
                    }
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    Timber.w(e, "Failed to purge OTA cache on application launch")
                }

                val db = HisnRoomDatabase.getInstance(this@AntiPornApplication)
                val cutoff = TrustedTimeProvider.currentTimeMillis(this@AntiPornApplication) - (30L * 24 * 60 * 60 * 1000)
                val pruned = db.diagnosticEventDao().pruneEventsOlderThan(cutoff)
                val capped = db.diagnosticEventDao().enforceMaxEventsCap(5000)
                val prunedNotifs = try {
                    db.inAppNotificationDao().pruneOldNotifications(maxKeep = 200, olderThanMs = cutoff)
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    Timber.w(e, "Failed to prune old in-app notifications")
                    0
                }
                if (pruned > 0 || capped > 0 || prunedNotifs > 0) {
                    Timber.i("Cleaned up database: diagPruned=$pruned, diagCapped=$capped, notifsPruned=$prunedNotifs")
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Timber.w(e, "Failed to prune diagnostic events / notifications")
            }

            checkPendingFreezeRecovery()
        }
    }

    private fun checkPendingFreezeRecovery() {
        try {
            if (com.drmohammed.antiporn.provisioning.engine.DeviceOwnerPromoter.isPromotionActive) return
            val stranded = ProvisioningFreezeRecoveryStore.findStillDisabledTrackedPackages(this)
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            if (stranded.isEmpty()) {
                nm?.cancel(NotificationRegistry.EVENT_FREEZE_RECOVERY_ID)
                return
            }
            val canNotify = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(
                    this,
                    android.Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED
            if (!canNotify) return

            val wizardIntent = Intent(this, ProvisioningWizardActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(ProvisioningWizardActivity.EXTRA_SHOW_RECOVERY_DIALOG, true)
            }
            val notification = AdbPairingNotificationFactory.buildFreezeRecoveryNotification(
                context = this,
                affectedCount = stranded.size,
                wizardIntent = wizardIntent,
                optionsBundle = null
            )
            nm?.notify(NotificationRegistry.EVENT_FREEZE_RECOVERY_ID, notification)
        } catch (e: Exception) {
            Timber.w(e, "Failed checking freeze recovery state on application launch")
        }
    }
}
