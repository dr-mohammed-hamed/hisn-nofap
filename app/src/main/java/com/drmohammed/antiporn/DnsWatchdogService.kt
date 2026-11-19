package com.drmohammed.antiporn

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.Process
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.drmohammed.antiporn.core.engine.ProtectionEngine
import com.drmohammed.antiporn.core.engine.ProtectionTransitionGuard
import com.drmohammed.antiporn.core.time.TrustedTimeProvider
import com.drmohammed.antiporn.curfew.CurfewBlackholeVpnService
import com.drmohammed.antiporn.curfew.CurfewConfig
import com.drmohammed.antiporn.curfew.CurfewEnforcer
import com.drmohammed.antiporn.curfew.CurfewVpnDnsBridge
import com.drmohammed.antiporn.curfew.DnsFastHealOutcome
import com.drmohammed.antiporn.services.NetworkWatchdog
import com.drmohammed.antiporn.shields.DnsShield
import com.drmohammed.antiporn.core.model.ShieldStatus
import com.drmohammed.antiporn.notification.manager.AppNotificationManager
import com.drmohammed.antiporn.notification.model.NotificationActionType
import com.drmohammed.antiporn.notification.model.NotificationCategory
import com.drmohammed.antiporn.notification.registry.NotificationRegistry
import com.drmohammed.antiporn.telemetry.BlackBoxRecorder
import timber.log.Timber
import java.util.TimeZone
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class DnsWatchdogService : Service() {

    companion object {
        private const val TAG = "AntiPorn_Watchdog"
        const val CHANNEL_ID = NotificationRegistry.CHANNEL_ID_WATCHDOG
        const val NOTIFICATION_ID = NotificationRegistry.FGS_WATCHDOG_ID
        private const val DEBOUNCE_DELAY_MS = 500L
        private const val SCREEN_DEBOUNCE_DELAY_MS = 500L
        // صمام أمان دوري مريح للبطارية (15 دقيقة):
        // التعدي على الـ DNS يُرصد لحظياً عبر dnsObserver (ContentObserver)،
        // وتثبيت التطبيقات يرصد فورياً عبر packageReceiver (ACTION_PACKAGE_ADDED)،
        // وفتح/قفل الشاشة يرصد لحظياً عبر screenReceiver،
        // وبالتالي فإن النبضة الدورية لا تحتاج لإيقاظ المعالج كل دقيقة.
        private const val HEARTBEAT_INTERVAL_MS = 15 * 60_000L // 15 دقيقة كصمام أمان دوري مريح للبطارية
        private const val CLOUD_HEARTBEAT_INTERVAL_MS = 12 * 3600 * 1000L
        private const val OTA_UPDATE_CHECK_INTERVAL_MS = 12 * 3600 * 1000L

        @Volatile
        var isRunning: Boolean = false
            private set

        fun startService(context: Context) {
            if (isRunning) {
                Timber.tag(TAG).d("DnsWatchdogService is already running, skipping startService")
                return
            }
            try {
                val intent = Intent(context, DnsWatchdogService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    ContextCompat.startForegroundService(context, intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Failed to start DnsWatchdogService")
            }
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val protectionCheckChannel = Channel<Unit>(Channel.CONFLATED)
    private val isSyncingApprovals = AtomicBoolean(false)
    private val needsApprovalResync = AtomicBoolean(false)
    @Volatile
    private var isDestroyed = false

    private val debounceHandler = Handler(Looper.getMainLooper())
    private val checkRunnable = Runnable {
        if (!isDestroyed) checkAndEnforceProtection()
    }

    private fun scheduleDebouncedCheck(delayMs: Long) {
        if (isDestroyed) return
        debounceHandler.removeCallbacks(checkRunnable)
        if (!isDestroyed) {
            debounceHandler.postDelayed(checkRunnable, delayMs)
        }
    }

    private val screenDebounceRunnable = Runnable {
        if (isDestroyed) return@Runnable
        Timber.tag(TAG).i("Layer 2 - Screen event debounced check: reconciling Zero-Trust and verifying shields")
        com.drmohammed.antiporn.data.repository.ApprovalRepository.getInstance(applicationContext).syncZeroTrustBaselineAndReconcileAsync()
        checkAndEnforceProtection()
    }

    private val lastCloudHeartbeatElapsed = AtomicLong(0L)
    private val lastOtaCheckElapsed = AtomicLong(0L)
    private val heartbeatHandler = Handler(Looper.getMainLooper())
    private val heartbeatRunnable = object : Runnable {
        override fun run() {
            if (isDestroyed) return
            Timber.tag(TAG).d("Layer 3 - Periodic heartbeat (15m): verifying shields")
            checkAndEnforceProtection()
            TrustedTimeProvider.syncNowAsync(applicationContext)

            val nowRealtime = android.os.SystemClock.elapsedRealtime()
            val lastCloud = lastCloudHeartbeatElapsed.get()
            if (nowRealtime - lastCloud >= CLOUD_HEARTBEAT_INTERVAL_MS || lastCloud == 0L) {
                serviceScope.launch {
                    try {
                        val res = com.drmohammed.antiporn.data.repository.AccountabilityRepository.getInstance(applicationContext).updateHeartbeat()
                        if (res.isSuccess) {
                            lastCloudHeartbeatElapsed.set(nowRealtime)
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Timber.tag(TAG).w(e, "Watchdog cloud heartbeat update error")
                    }
                }
            }

            val lastOta = lastOtaCheckElapsed.get()
            if (nowRealtime - lastOta >= OTA_UPDATE_CHECK_INTERVAL_MS || lastOta == 0L) {
                serviceScope.launch {
                    try {
                        com.drmohammed.antiporn.ota.repository.OtaRepository.getInstance(applicationContext)
                            .checkForUpdatesAndAutoInstall(applicationContext)
                        lastOtaCheckElapsed.set(android.os.SystemClock.elapsedRealtime())
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Timber.tag(TAG).w(e, "Watchdog periodic OTA update error")
                    }
                }
            }

            if (!isDestroyed) {
                heartbeatHandler.postDelayed(this, HEARTBEAT_INTERVAL_MS)
            }
        }
    }

    private var dnsObserverThread: HandlerThread? = null
    private var dnsObserver: ContentObserver? = null
    private val networkWatchdog = NetworkWatchdog()

    private var isPackageReceiverRegistered = false
    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent == null) return
            val action = intent.action ?: return
            val packageName = intent.data?.schemeSpecificPart?.takeIf { it.isNotBlank() } ?: return
            Timber.tag(TAG).w("[EVENT] [DYNAMIC_WATCHDOG_RECEIVER] onReceive: action=$action | package=$packageName")
            when (action) {
                Intent.ACTION_PACKAGE_ADDED -> {
                    val isReplacing = intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)
                    PackageChangeHandler.handlePackageAdded(
                        context = applicationContext,
                        packageName = packageName,
                        isReplacing = isReplacing,
                        source = "Dynamic_Watchdog"
                    )
                }
                Intent.ACTION_PACKAGE_REMOVED -> {
                    val isReplacing = intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)
                    if (isReplacing) {
                        Timber.tag(TAG).i("[DYNAMIC_WATCHDOG_RECEIVER] Package $packageName is being replaced/updated (EXTRA_REPLACING=true). Ignoring temporary removal.")
                        return
                    }
                    PackageChangeHandler.handlePackageRemoved(
                        context = applicationContext,
                        packageName = packageName,
                        isReplacing = isReplacing,
                        source = "Dynamic_Watchdog"
                    )
                }
            }
        }
    }

    private var isScreenReceiverRegistered = false
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent == null) return
            val action = intent.action
            if (action == Intent.ACTION_USER_PRESENT || action == Intent.ACTION_SCREEN_ON) {
                if (isDestroyed) return
                Timber.tag(TAG).d("Layer 2 - Screen event observed ($action). Debouncing scan...")
                debounceHandler.removeCallbacks(screenDebounceRunnable)
                if (!isDestroyed) {
                    debounceHandler.postDelayed(screenDebounceRunnable, SCREEN_DEBOUNCE_DELAY_MS)
                }
                com.drmohammed.antiporn.waqfa.engine.WaqfaForegroundWatcher.getInstance(applicationContext).onScreenStateChanged(true)
            } else if (action == Intent.ACTION_SCREEN_OFF) {
                com.drmohammed.antiporn.waqfa.engine.WaqfaForegroundWatcher.getInstance(applicationContext).onScreenStateChanged(false)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        Timber.tag(TAG).i("DnsWatchdogService created")
        startForegroundNotification()
        serviceScope.launch {
            for (trigger in protectionCheckChannel) {
                try {
                    checkAndEnforceProtectionInternal()
                } catch (e: Exception) {
                    Timber.tag(TAG).e(e, "Error during serialized protection enforcement check")
                }
            }
        }
        registerDnsObserver()
        registerNetworkWatchdog()
        registerPackageReceiver()
        registerScreenReceiver()
        startHeartbeat()
        checkAndEnforceProtection()
        com.drmohammed.antiporn.data.repository.ApprovalRepository.getInstance(applicationContext).syncZeroTrustBaselineAndReconcileAsync()
        com.drmohammed.antiporn.curfew.ZeroTrustForegroundWatcher.syncWatcherStateAsync(applicationContext)
        com.drmohammed.antiporn.waqfa.engine.WaqfaForegroundWatcher.getInstance(applicationContext).start()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        isRunning = true
        Timber.tag(TAG).i("DnsWatchdogService started")
        startForegroundNotification()
        com.drmohammed.antiporn.curfew.ZeroTrustForegroundWatcher.syncWatcherStateAsync(applicationContext)
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        isDestroyed = true
        isRunning = false
        super.onDestroy()
        Timber.tag(TAG).w("DnsWatchdogService destroyed")
        networkWatchdog.unregister()
        stopHeartbeat()
        unregisterDnsObserver()
        unregisterPackageReceiver()
        unregisterScreenReceiver()
        com.drmohammed.antiporn.waqfa.engine.WaqfaForegroundWatcher.getInstance(applicationContext).stop()
        protectionCheckChannel.close()
        serviceScope.cancel()
        debounceHandler.removeCallbacksAndMessages(null)
        heartbeatHandler.removeCallbacksAndMessages(null)
    }

    private fun startForegroundNotification() {
        try {
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            val channelTitle = getString(R.string.watchdog_notification_title)
            val channelDesc = getString(R.string.watchdog_notification_desc)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    channelTitle,
                    NotificationManager.IMPORTANCE_MIN
                ).apply {
                    description = channelDesc
                    setShowBadge(false)
                }
                notificationManager.createNotificationChannel(channel)
            }

            val notification = NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(channelTitle)
                .setSmallIcon(R.drawable.ic_shield_dns)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .build()

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed promoting DnsWatchdogService to foreground")
            stopSelf()
        }
    }

    private fun registerDnsObserver() {
        if (dnsObserver != null) return

        val observerThread = HandlerThread(
            "AntiPorn_DnsObserver",
            Process.THREAD_PRIORITY_URGENT_DISPLAY
        ).apply { start() }

        val observer = object : ContentObserver(Handler(observerThread.looper)) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                if (isDestroyed) return
                handleImmediateDnsChange(uri)
            }
        }

        try {
            val modeUri = Settings.Global.getUriFor("private_dns_mode")
            val specifierUri = Settings.Global.getUriFor("private_dns_specifier")

            if (modeUri == null && specifierUri == null) {
                Timber.tag(TAG).w("Settings.Global URIs for Private DNS unavailable; skipping observer registration")
                observerThread.quitSafely()
                return
            }

            if (modeUri != null) {
                contentResolver.registerContentObserver(modeUri, false, observer)
            }
            if (specifierUri != null) {
                contentResolver.registerContentObserver(specifierUri, false, observer)
            }
            dnsObserverThread = observerThread
            dnsObserver = observer
            Timber.tag(TAG).i("ContentObserver registered for DNS settings on dedicated HandlerThread")
        } catch (e: Exception) {
            try {
                contentResolver.unregisterContentObserver(observer)
            } catch (_: Exception) {
                // Best-effort cleanup if one URI registered before another failed
            }
            observerThread.quitSafely()
            dnsObserver = null
            dnsObserverThread = null
            Timber.tag(TAG).e(e, "Failed to register ContentObserver for DNS settings")
        }
    }

    private fun handleImmediateDnsChange(uri: Uri?) {
        if (!isRunning) return
        try {
            val appContext = applicationContext ?: this
            val result = CurfewVpnDnsBridge.tryFastHealGlobalPrivateDns(appContext)
            when (result.outcome) {
                DnsFastHealOutcome.ALREADY_COMPLIANT -> {
                    // 0 writes, 0 debounce: prevents infinite ContentObserver feedback loops
                    return
                }
                DnsFastHealOutcome.HEALED_DIRECT -> {
                    Timber.tag(TAG).i(
                        "DNS fast-path healed silently in ${result.latencyMs}ms " +
                            "(wasMode=${result.observedMode}, wasHost=${result.observedHost}, restored=${result.expectedHost}, uri=$uri)"
                    )
                    BlackBoxRecorder.record(
                        context = appContext,
                        tag = BlackBoxRecorder.TAG_SHIELD,
                        actionName = "DNS_FAST_PATH_HEALED",
                        isSuccess = true,
                        executionTimeMs = result.latencyMs,
                        metadata = "wasMode=${result.observedMode}, wasHost=${result.observedHost}, restored=${result.expectedHost}, uri=$uri"
                    )
                    ProtectionEngine.applyDnsShieldFastAsync(appContext)
                }
                DnsFastHealOutcome.PERMISSION_DENIED_NEEDS_DPM,
                DnsFastHealOutcome.FAILED_NEEDS_DPM -> {
                    Timber.tag(TAG).w(
                        "DNS fast-path direct write unavailable (${result.outcome}); escalating immediately to DPM fast-path (uri=$uri)"
                    )
                    BlackBoxRecorder.record(
                        context = appContext,
                        tag = BlackBoxRecorder.TAG_SHIELD,
                        actionName = "DNS_FAST_PATH_FALLBACK_DPM",
                        isSuccess = false,
                        executionTimeMs = result.latencyMs,
                        errorDetails = result.errorMessage,
                        metadata = "wasMode=${result.observedMode}, wasHost=${result.observedHost}, expected=${result.expectedHost}, outcome=${result.outcome}"
                    )
                    ProtectionEngine.applyDnsShieldFastAsync(appContext)
                }
                DnsFastHealOutcome.SKIPPED_ACTIVE_TRANSITION,
                DnsFastHealOutcome.SKIPPED_VPN_EXPECTED -> {
                    val delayMs = if (ProtectionTransitionGuard.isWithinSettleWindow()) 3500L else DEBOUNCE_DELAY_MS
                    Timber.tag(TAG).d("DNS change deferred (${result.outcome}, uri=$uri). Scheduling post-transition audit in ${delayMs}ms...")
                    debounceHandler.removeCallbacks(checkRunnable)
                    debounceHandler.postDelayed(checkRunnable, delayMs)
                }
                DnsFastHealOutcome.SKIPPED_UNSUPPORTED_SDK,
                DnsFastHealOutcome.SKIPPED_NOT_DEVICE_OWNER -> {
                    return
                }
            }
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Error handling immediate DNS change (uri=$uri)")
        }
    }

    private fun unregisterDnsObserver() {
        dnsObserver?.let {
            try {
                contentResolver.unregisterContentObserver(it)
                Timber.tag(TAG).i("ContentObserver unregistered")
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Failed to unregister ContentObserver")
            }
            dnsObserver = null
        }
        dnsObserverThread?.let { thread ->
            try {
                thread.quitSafely()
            } catch (e: Exception) {
                Timber.tag(TAG).w(e, "Failed to quit DNS observer HandlerThread")
            }
            dnsObserverThread = null
        }
    }

    private fun registerNetworkWatchdog() {
        // CurfewBlackholeVpnService owns PhysicalNetworkMonitor as single source of truth for VPN DNS/DoT flushes
        // so dual monitors do not trigger a +300ms trailing double-flush on fresh post-handoff sockets.
        networkWatchdog.register(
            context = this,
            onNetworkChanged = null
        ) {
            Timber.tag(TAG).i("Network became available! Debouncing protection check...")
            scheduleDebouncedCheck(1000L)
            TrustedTimeProvider.syncNowAsync(applicationContext)

            needsApprovalResync.set(true)
            if (isSyncingApprovals.compareAndSet(false, true)) {
                serviceScope.launch {
                    while (isActive) {
                        try {
                            while (isActive && needsApprovalResync.getAndSet(false)) {
                                com.drmohammed.antiporn.data.repository.ApprovalRepository.getInstance(applicationContext).syncPendingApprovalsToCloud()
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            Timber.tag(TAG).w("Failed syncing pending approvals on network recovery: ${e.message}")
                        } finally {
                            isSyncingApprovals.set(false)
                        }
                        if (!isActive || !(needsApprovalResync.get() && isSyncingApprovals.compareAndSet(false, true))) {
                            break
                        }
                    }
                }
            } else {
                Timber.tag(TAG).d("Approval cloud sync already in progress; marked pending resync trigger")
            }
        }
    }

    private fun registerPackageReceiver() {
        if (isPackageReceiverRegistered) return
        try {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_PACKAGE_ADDED)
                addAction(Intent.ACTION_PACKAGE_REMOVED)
                addDataScheme("package")
            }
            // CRITICAL: System broadcasts like ACTION_PACKAGE_ADDED require RECEIVER_EXPORTED.
            // ContextCompat requires RECEIVER_EXPORTED or RECEIVER_NOT_EXPORTED on all API levels.
            ContextCompat.registerReceiver(this, packageReceiver, filter, ContextCompat.RECEIVER_EXPORTED)
            isPackageReceiverRegistered = true
            Timber.tag(TAG).i("Layer 1 - Dynamic package BroadcastReceiver registered successfully (RECEIVER_EXPORTED)")
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to register dynamic package receiver")
        }
    }

    private fun unregisterPackageReceiver() {
        if (!isPackageReceiverRegistered) return
        try {
            unregisterReceiver(packageReceiver)
            isPackageReceiverRegistered = false
            Timber.tag(TAG).i("Layer 1 - Dynamic package BroadcastReceiver unregistered")
        } catch (e: Exception) {
            Timber.tag(TAG).w("Failed to unregister dynamic package receiver: ${e.message}")
        }
    }

    private fun registerScreenReceiver() {
        if (isScreenReceiverRegistered) return
        try {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_USER_PRESENT)
            }
            // Explicit NOT_EXPORTED required for targetSdk=36 / API 33+.
            ContextCompat.registerReceiver(this, screenReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
            isScreenReceiverRegistered = true
            Timber.tag(TAG).i("Layer 2 - Screen / User Present BroadcastReceiver registered successfully")
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to register screen receiver")
        }
    }

    private fun unregisterScreenReceiver() {
        if (!isScreenReceiverRegistered) return
        try {
            unregisterReceiver(screenReceiver)
            isScreenReceiverRegistered = false
            Timber.tag(TAG).i("Layer 2 - Screen / User Present BroadcastReceiver unregistered")
        } catch (e: Exception) {
            Timber.tag(TAG).w("Failed to unregister screen receiver: ${e.message}")
        }
    }

    private fun startHeartbeat() {
        heartbeatHandler.removeCallbacks(heartbeatRunnable)
        heartbeatHandler.postDelayed(heartbeatRunnable, HEARTBEAT_INTERVAL_MS)
        Timber.tag(TAG).i("Layer 3 - Periodic heartbeat scheduled (every ${HEARTBEAT_INTERVAL_MS / 1000}s)")
    }

    private fun stopHeartbeat() {
        heartbeatHandler.removeCallbacks(heartbeatRunnable)
        Timber.tag(TAG).i("Layer 3 - Periodic heartbeat cancelled")
    }

    private fun checkAndEnforceProtection() {
        protectionCheckChannel.trySend(Unit)
    }

    private fun checkAndEnforceProtectionInternal() {
        val appContext = applicationContext ?: this
        if (!ProtectionEngine.isDeviceOwner(appContext)) {
            Timber.tag(TAG).d("Not Device Owner, skipping watchdog enforcement")
            return
        }

        // 0. Settle window & teardown check
        if (com.drmohammed.antiporn.core.engine.ProtectionTransitionGuard.isWithinSettleWindow()) {
            Timber.tag(TAG).d("System is within legitimate transition or settle window. Postponing watchdog audit...")
            scheduleDebouncedCheck(3500L)
            return
        }

        // 0b. First-time baseline check: silently establish protection without false alarms
        if (!com.drmohammed.antiporn.core.engine.ProtectionTransitionGuard.hasCompletedInitialEnforcement(appContext)) {
            Timber.tag(TAG).i("Initial Device Owner protection baseline not yet established. Applying shields silently...")
            val results = ProtectionEngine.applyAllSync(appContext)
            if (results.any { it.success }) {
                com.drmohammed.antiporn.core.engine.ProtectionTransitionGuard.markInitialEnforcementCompleted(appContext)
            }
            return
        }

        // 0c. Live curfew block reconciliation: reconcile scheduled transitions before audit
        val shouldBlockBeActive = CurfewEnforcer.isEffectiveBlockActive(appContext)
        val wasBlockEnforced = CurfewConfig.isLastBlockEnforced(appContext)
        if (shouldBlockBeActive != wasBlockEnforced) {
            Timber.tag(TAG).i("Scheduled block state transition pending ($wasBlockEnforced -> $shouldBlockBeActive). Reconciling before audit.")
            CurfewEnforcer.syncBlockState(appContext)
            if (com.drmohammed.antiporn.core.engine.ProtectionTransitionGuard.isWithinSettleWindow()) {
                Timber.tag(TAG).d("Curfew block reconciliation triggered internal transition. Postponing watchdog audit...")
                scheduleDebouncedCheck(3500L)
                return
            }
        }

        val desyncReasons = mutableListOf<String>()

        // 1. Surgical inspection of all registered security shields
        val shieldStatuses = ProtectionEngine.evaluateSystemStatus(appContext)
        for ((shieldId, status) in shieldStatuses) {
            when (status) {
                is ShieldStatus.Active -> { /* In fully active and enforced state */ }
                is ShieldStatus.Unsupported -> {
                    // System/OS hardware limitation - not a tamper event and cannot be fixed by re-enforcing
                    Timber.tag(TAG).d("Shield [$shieldId] is unsupported on current OS API level (${status.minSdkRequired})")
                }
                is ShieldStatus.NeedsEnforcement -> {
                    desyncReasons.add("[$shieldId: ${status.reason}]")
                }
                is ShieldStatus.Disabled -> {
                    desyncReasons.add("[$shieldId: ${status.warning}]")
                }
            }
        }

        // 2. Direct Settings.Global verification for Private DNS (Android 10+ / API 29+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                val mode = Settings.Global.getString(appContext.contentResolver, "private_dns_mode")
                val specifier = Settings.Global.getString(appContext.contentResolver, "private_dns_specifier")
                val requiredMode = com.drmohammed.antiporn.curfew.CurfewBlackholeVpnService.evaluateRequiredMode(appContext)
                val isVpnConsentGranted = com.drmohammed.antiporn.curfew.CurfewVpnDnsBridge.isVpnConsentGranted(appContext)
                val isVpnActive = isVpnConsentGranted && requiredMode != com.drmohammed.antiporn.curfew.CurfewBlackholeVpnService.VpnMode.IDLE

                if (isVpnActive) {
                    // When VPN mode is required, Private DNS must be opportunistic (or off/unset);
                    // Android ignores any cached private_dns_specifier when mode != "hostname".
                    val isAppropriate = mode == "opportunistic" || mode == "off" || mode == null
                    if (!isAppropriate) {
                        val reason = "Settings.Global DNS mismatch: VPN required, expected mode='opportunistic' but found mode='$mode', specifier='$specifier'"
                        Timber.tag(TAG).w(reason)
                        desyncReasons.add("[DNS_GLOBAL: $reason]")
                    }
                    if (!com.drmohammed.antiporn.curfew.CurfewBlackholeVpnService.isTunnelHealthy(requiredMode)) {
                        Timber.tag(TAG).i("VPN service or DNS worker not healthy while required. Enqueuing silent sync.")
                        com.drmohammed.antiporn.curfew.CurfewBlackholeVpnService.syncPolicy(appContext)
                    }
                } else {
                    val expectedHost = DnsShield.getEffectiveDnsHost(appContext)
                    val isHostMatching = specifier?.equals(expectedHost, ignoreCase = true) == true
                    if (mode != "hostname" || !isHostMatching) {
                        val isPendingCurfewRelease = (mode == "opportunistic" || mode.isNullOrEmpty()) &&
                            specifier.isNullOrEmpty() &&
                            CurfewConfig.isDnsRestorePending(appContext)

                        if (isPendingCurfewRelease) {
                            val reason = "Settings.Global DNS pending post-curfew restoration (mode=$mode, expected=$expectedHost)"
                            Timber.tag(TAG).i(reason)
                            desyncReasons.add("[DNS_PENDING_RESTORE: $reason]")
                            com.drmohammed.antiporn.curfew.CurfewVpnDnsBridge.restoreDnsShieldOnCurfewRelease(appContext)
                        } else {
                            val reason = "Settings.Global DNS mismatch (mode=$mode, specifier=$specifier, expected=$expectedHost)"
                            Timber.tag(TAG).w(reason)
                            desyncReasons.add("[DNS_GLOBAL: $reason]")
                        }
                    } else if (CurfewConfig.isDnsRestorePending(appContext)) {
                        CurfewConfig.setDnsRestorePending(appContext, false)
                    }
                }
            } catch (e: Exception) {
                Timber.tag(TAG).d(e, "Could not read Settings.Global directly")
            }
        }

        // 3. Verify Target Timezone and Network Auto Time directly (only if time lock is active in policy)
        val isTimeLocked = try {
            com.drmohammed.antiporn.data.repository.PolicyRepository.getInstance(appContext)
                .getCurrentPolicy().isDateTimeLocked
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Failed reading dateTimeLocked policy")
            false
        }

        if (isTimeLocked) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    val targetTzId = CurfewConfig.getTargetTimeZoneId(appContext)
                    val targetTz = TimeZone.getTimeZone(targetTzId)
                    TimeZone.setDefault(null)
                    val currentTz = TimeZone.getDefault()
                    val trustedTime = TrustedTimeProvider.currentTimeMillis(appContext)
                    val isTargetTz = currentTz.id == targetTzId || currentTz.hasSameRules(targetTz) ||
                        currentTz.getOffset(trustedTime) == targetTz.getOffset(trustedTime)
                    if (!isTargetTz) {
                        val tzReason = "Timezone mismatch (current=${currentTz.id}, expected=$targetTzId)"
                        Timber.tag(TAG).w(tzReason)
                        desyncReasons.add("[TIMEZONE: $tzReason]")
                    }
                }

                // Check auto-time (network clock synchronization)
                val autoTime = Settings.Global.getInt(appContext.contentResolver, Settings.Global.AUTO_TIME, 1)
                if (autoTime != 1) {
                    val autoTimeReason = "Auto time disabled (autoTime=$autoTime)"
                    Timber.tag(TAG).w(autoTimeReason)
                    desyncReasons.add("[AUTO_TIME: $autoTimeReason]")
                }
            } catch (e: Exception) {
                Timber.tag(TAG).w(e, "Could not verify Timezone or Auto Time directly in watchdog")
            }
        }

        val needsEnforce = desyncReasons.isNotEmpty()
        if (needsEnforce) {
            val detailedReasons = desyncReasons.joinToString(" | ")
            Timber.tag(TAG).i("Tampering or desync detected in protection shields: $detailedReasons. Re-enforcing via ProtectionEngine...")

            // Forensic recording into Black Box Flight Recorder
            BlackBoxRecorder.record(
                context = appContext,
                tag = BlackBoxRecorder.TAG_SHIELD,
                actionName = "WATCHDOG_TAMPER_DETECTED",
                isSuccess = false,
                errorDetails = detailedReasons,
                metadata = "failedCount=${desyncReasons.size}"
            )

            // Evaluate transition safety BEFORE triggering applyAllAsync() so self-healing
            // does not race with ProtectionTransitionGuard and suppress a real tamper alert.
            // Intentional silent DNS policy: Private DNS self-healing is recorded in Black Box only
            // with zero user-facing notifications in the notification shade.
            val isDirectTamper = desyncReasons.any {
                it.startsWith("[TIMEZONE:") ||
                    it.startsWith("[AUTO_TIME:") ||
                    it.startsWith("[system_tamper:")
            }

            val isCurfewStateSynchronized = CurfewEnforcer.isEffectiveBlockActive(appContext) == CurfewConfig.isLastBlockEnforced(appContext)
            val isTransitionSafe = !com.drmohammed.antiporn.core.engine.ProtectionTransitionGuard.isWithinSettleWindow() &&
                !com.drmohammed.antiporn.core.engine.ProtectionTransitionGuard.isTeardownInProgress &&
                isCurfewStateSynchronized &&
                !CurfewConfig.isDnsRestorePending(appContext)

            // Silent self-healing
            ProtectionEngine.applyAllAsync(appContext)

            if (isDirectTamper && isTransitionSafe) {
                try {
                    AppNotificationManager.getInstance(appContext).postEventNotification(
                        category = NotificationCategory.SHIELDS,
                        title = appContext.getString(R.string.notif_tamper_alert_title),
                        message = appContext.getString(R.string.notif_tamper_alert_msg),
                        dedupKey = "tamper_alert_enforce",
                        cooldownMs = 15 * 60 * 1000L,
                        actionType = NotificationActionType.NONE,
                        systemNotificationId = NotificationRegistry.EVENT_SYSTEM_TAMPER_ID,
                        channelId = NotificationRegistry.CHANNEL_ID_PROTECTION_EVENTS,
                        postSystemNotification = true,
                        importance = 3
                    )
                } catch (e: Exception) {
                    Timber.tag(TAG).e(e, "Error posting tamper alert notification")
                }
            }
        }
        com.drmohammed.antiporn.data.repository.ApprovalRepository.getInstance(appContext).syncZeroTrustBaselineAndReconcileAsync()
    }
}
