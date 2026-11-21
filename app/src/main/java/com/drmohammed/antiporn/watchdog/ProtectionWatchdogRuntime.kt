package com.drmohammed.antiporn.watchdog

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.drmohammed.antiporn.PackageChangeHandler
import com.drmohammed.antiporn.R
import com.drmohammed.antiporn.core.engine.ProtectionEngine
import com.drmohammed.antiporn.core.engine.ProtectionTransitionGuard
import com.drmohammed.antiporn.core.model.ShieldStatus
import com.drmohammed.antiporn.core.time.TrustedTimeProvider
import com.drmohammed.antiporn.curfew.CurfewBlackholeVpnService
import com.drmohammed.antiporn.curfew.CurfewConfig
import com.drmohammed.antiporn.curfew.CurfewEnforcer
import com.drmohammed.antiporn.curfew.CurfewVpnDnsBridge
import com.drmohammed.antiporn.curfew.DnsFastHealOutcome
import com.drmohammed.antiporn.curfew.ZeroTrustForegroundWatcher
import com.drmohammed.antiporn.data.repository.AccountabilityRepository
import com.drmohammed.antiporn.data.repository.ApprovalRepository
import com.drmohammed.antiporn.data.repository.PolicyRepository
import com.drmohammed.antiporn.notification.manager.AppNotificationManager
import com.drmohammed.antiporn.notification.model.NotificationActionType
import com.drmohammed.antiporn.notification.model.NotificationCategory
import com.drmohammed.antiporn.notification.registry.NotificationRegistry
import com.drmohammed.antiporn.services.NetworkWatchdog
import com.drmohammed.antiporn.shields.DnsShield
import com.drmohammed.antiporn.telemetry.BlackBoxRecorder
import com.drmohammed.antiporn.waqfa.engine.WaqfaForegroundWatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.TimeZone
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Thread-safe, idempotent, host-agnostic runtime controller for zero-trust protection watchdog observation.
 *
 * Decoupled from Android Service lifecycle so that the primary host (e.g. [HisnDeviceAdminService]),
 * fallback host (e.g. [DnsWatchdogService]), or test harness can attach and detach without
 * leaking listeners or double-registering system observers.
 *
 * Follows strict generation tracking and dynamic resource re-creation to ensure 100% re-attach safety
 * across unbind/rebind or teardown/re-provision lifecycles within the same process.
 */
object ProtectionWatchdogRuntime {

    private const val TAG = "AntiPorn_WatchdogRuntime"
    private const val DEBOUNCE_DELAY_MS = 500L
    private const val SCREEN_DEBOUNCE_DELAY_MS = 500L
    private const val HEARTBEAT_INTERVAL_MS = 15 * 60_000L // 15 minutes battery-friendly periodic safety net
    private const val CLOUD_HEARTBEAT_INTERVAL_MS = 24 * 3600 * 1000L
    private const val OTA_UPDATE_CHECK_INTERVAL_MS = 12 * 3600 * 1000L

    enum class WatchdogHost {
        NONE,
        DEVICE_ADMIN_SERVICE,
        LEGACY_BG_SERVICE,
        FGS_FALLBACK
    }

    private val lock = Any()

    @Volatile
    var isRunning: Boolean = false
        private set

    @Volatile
    var activeHost: WatchdogHost = WatchdogHost.NONE
        private set

    private var generationId: Long = 0L

    // Dynamic resources: re-created on startRuntimeLocked and cleaned up on stopRuntimeLocked
    private var runtimeScope: CoroutineScope? = null
    private var protectionCheckChannel: Channel<Unit>? = null

    private var appContextRef: Context? = null
    private var debounceHandler: Handler? = null
    private var heartbeatHandler: Handler? = null

    private var dnsObserverThread: HandlerThread? = null
    private var dnsObserver: ContentObserver? = null

    private val networkWatchdog = NetworkWatchdog()
    private var isNetworkRegistered = false

    private var isPackageReceiverRegistered = false
    private var packageReceiver: BroadcastReceiver? = null

    private var isScreenReceiverRegistered = false
    private var screenReceiver: BroadcastReceiver? = null

    private val isSyncingApprovals = AtomicBoolean(false)
    private val needsApprovalResync = AtomicBoolean(false)
    private val lastCloudHeartbeatElapsed = AtomicLong(0L)
    private val lastOtaCheckElapsed = AtomicLong(0L)

    /**
     * Attaches an active host to the watchdog runtime.
     * Idempotent: if already running under the same host, does nothing.
     * If running under fallback and [WatchdogHost.DEVICE_ADMIN_SERVICE] attaches, upgrades the host.
     */
    fun attach(context: Context, host: WatchdogHost) {
        synchronized(lock) {
            val appCtx = context.applicationContext
            if (isRunning) {
                if (activeHost == host) {
                    Timber.tag(TAG).d("Watchdog runtime already running on host $host; ignoring redundant attach.")
                    return
                }
                // Upgrade from fallback or legacy service to primary DeviceAdminService without restarting observers
                if (host == WatchdogHost.DEVICE_ADMIN_SERVICE) {
                    Timber.tag(TAG).i("Upgrading active watchdog host from $activeHost to $host.")
                    activeHost = host
                    return
                }
                // Do not downgrade an already attached DeviceAdminService to a fallback host
                if (activeHost == WatchdogHost.DEVICE_ADMIN_SERVICE && host == WatchdogHost.FGS_FALLBACK) {
                    Timber.tag(TAG).d("DeviceAdminService is already primary host; ignoring fallback attach request.")
                    return
                }
            }

            activeHost = host
            startRuntimeLocked(appCtx)
        }
    }

    /**
     * Detaches a host from the watchdog runtime.
     * ONLY tears down observers/receivers if [host] is the currently active host.
     * Prevents an old stopping service from killing a freshly attached primary host.
     */
    fun detach(host: WatchdogHost) {
        synchronized(lock) {
            if (activeHost != host) {
                Timber.tag(TAG).d("Ignoring detach request from $host because activeHost is $activeHost.")
                return
            }
            stopRuntimeLocked()
            activeHost = WatchdogHost.NONE
        }
    }

    /**
     * Unconditionally tears down all watchdog observers, receivers, and coroutines.
     * Used during device owner teardown or admin receiver disabling.
     */
    fun forceStop() {
        synchronized(lock) {
            stopRuntimeLocked()
            activeHost = WatchdogHost.NONE
        }
    }

    /**
     * Triggers a debounced or direct protection check from external callers.
     */
    fun scheduleCheck(delayMs: Long = 0L) {
        synchronized(lock) {
            if (!isRunning) return
            if (delayMs <= 0L) {
                protectionCheckChannel?.trySend(Unit)
            } else {
                scheduleDebouncedCheckLocked(delayMs)
            }
        }
    }

    private fun startRuntimeLocked(appContext: Context) {
        generationId++
        val currentGen = generationId
        appContextRef = appContext

        Timber.tag(TAG).i("Starting ProtectionWatchdogRuntime [generation=$currentGen, host=$activeHost]")

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        runtimeScope = scope

        val channel = Channel<Unit>(Channel.CONFLATED)
        protectionCheckChannel = channel

        val debouncer = Handler(Looper.getMainLooper())
        debounceHandler = debouncer

        val heartbeater = Handler(Looper.getMainLooper())
        heartbeatHandler = heartbeater

        // Serialized check loop
        scope.launch {
            for (trigger in channel) {
                if (!isActive || currentGen != generationId) break
                try {
                    checkAndEnforceProtectionInternal(appContext, currentGen)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Timber.tag(TAG).e(e, "Error during serialized protection enforcement check")
                }
            }
        }

        registerDnsObserver(appContext, currentGen)
        registerNetworkWatchdog(appContext, scope)
        registerPackageReceiver(appContext)
        registerScreenReceiver(appContext, currentGen)
        startHeartbeat(appContext, currentGen)

        isRunning = true

        // Initial reconciliation
        channel.trySend(Unit)
        ApprovalRepository.getInstance(appContext).syncZeroTrustBaselineAndReconcileAsync()
        ZeroTrustForegroundWatcher.syncWatcherStateAsync(appContext)
        try {
            WaqfaForegroundWatcher.getInstance(appContext).start()
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Error starting WaqfaForegroundWatcher")
        }
    }

    private fun stopRuntimeLocked() {
        generationId++
        isRunning = false
        Timber.tag(TAG).w("Stopping ProtectionWatchdogRuntime [generation=$generationId]")

        val appContext = appContextRef
        if (appContext != null) {
            unregisterNetworkWatchdog()
            unregisterDnsObserver(appContext)
            unregisterPackageReceiver(appContext)
            unregisterScreenReceiver(appContext)
            try {
                WaqfaForegroundWatcher.getInstance(appContext).stop()
            } catch (e: Exception) {
                Timber.tag(TAG).w(e, "Error stopping WaqfaForegroundWatcher")
            }
        }

        stopHeartbeat()
        debounceHandler?.removeCallbacksAndMessages(null)
        debounceHandler = null

        heartbeatHandler?.removeCallbacksAndMessages(null)
        heartbeatHandler = null

        try {
            protectionCheckChannel?.close()
        } catch (e: Exception) {
            Timber.tag(TAG).d(e, "Error closing protection check channel")
        }
        protectionCheckChannel = null

        runtimeScope?.cancel()
        runtimeScope = null

        appContextRef = null
    }

    private fun scheduleDebouncedCheckLocked(delayMs: Long) {
        val currentGen = generationId
        val handler = debounceHandler ?: return
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({
            synchronized(lock) {
                if (isRunning && currentGen == generationId) {
                    protectionCheckChannel?.trySend(Unit)
                }
            }
        }, delayMs)
    }

    private fun registerDnsObserver(appContext: Context, gen: Long) {
        if (dnsObserver != null) return

        val observerThread = HandlerThread(
            "AntiPorn_DnsObserver",
            Process.THREAD_PRIORITY_URGENT_DISPLAY
        ).apply { start() }

        val observer = object : ContentObserver(Handler(observerThread.looper)) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                synchronized(lock) {
                    if (!isRunning || gen != generationId) return
                }
                handleImmediateDnsChange(appContext, uri, gen)
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
                appContext.contentResolver.registerContentObserver(modeUri, false, observer)
            }
            if (specifierUri != null) {
                appContext.contentResolver.registerContentObserver(specifierUri, false, observer)
            }
            dnsObserverThread = observerThread
            dnsObserver = observer
            Timber.tag(TAG).i("ContentObserver registered for DNS settings on dedicated HandlerThread")
        } catch (e: Exception) {
            try {
                appContext.contentResolver.unregisterContentObserver(observer)
            } catch (e: Exception) {
                Timber.tag(TAG).d(e, "Error unregistering observer during cleanup")
            }
            observerThread.quitSafely()
            dnsObserver = null
            dnsObserverThread = null
            Timber.tag(TAG).e(e, "Failed to register ContentObserver for DNS settings")
        }
    }

    private fun unregisterDnsObserver(appContext: Context) {
        dnsObserver?.let {
            try {
                appContext.contentResolver.unregisterContentObserver(it)
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

    private fun handleImmediateDnsChange(appContext: Context, uri: Uri?, gen: Long) {
        synchronized(lock) {
            if (!isRunning || gen != generationId) return
        }
        try {
            val result = CurfewVpnDnsBridge.tryFastHealGlobalPrivateDns(appContext)
            when (result.outcome) {
                DnsFastHealOutcome.ALREADY_COMPLIANT -> {
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
                    synchronized(lock) {
                        scheduleDebouncedCheckLocked(delayMs)
                    }
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

    private fun registerNetworkWatchdog(appContext: Context, scope: CoroutineScope) {
        if (isNetworkRegistered) return
        networkWatchdog.register(
            context = appContext,
            onNetworkChanged = null,
            onDetailedNetworkTransition = { reason, network, transport ->
                com.drmohammed.antiporn.core.network.dns.DnsResilienceManager.onNetworkChanged(
                    appContext,
                    reason,
                    network,
                    transport
                )
            }
        ) {
            Timber.tag(TAG).i("Network became available! Debouncing protection check...")
            scheduleCheck(1000L)
            TrustedTimeProvider.syncNowAsync(appContext)

            needsApprovalResync.set(true)
            if (isSyncingApprovals.compareAndSet(false, true)) {
                scope.launch {
                    while (isActive) {
                        try {
                            while (isActive && needsApprovalResync.getAndSet(false)) {
                                ApprovalRepository.getInstance(appContext).syncPendingApprovalsToCloud()
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
        isNetworkRegistered = true
    }

    private fun unregisterNetworkWatchdog() {
        if (!isNetworkRegistered) return
        try {
            networkWatchdog.unregister()
            isNetworkRegistered = false
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Failed to unregister NetworkWatchdog")
        }
    }

    private fun registerPackageReceiver(appContext: Context) {
        if (isPackageReceiverRegistered) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent == null) return
                val action = intent.action ?: return
                val packageName = intent.data?.schemeSpecificPart?.takeIf { it.isNotBlank() } ?: return
                Timber.tag(TAG).w("[EVENT] [DYNAMIC_WATCHDOG_RECEIVER] onReceive: action=$action | package=$packageName")
                when (action) {
                    Intent.ACTION_PACKAGE_ADDED -> {
                        val isReplacing = intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)
                        PackageChangeHandler.handlePackageAdded(
                            context = appContext,
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
                            context = appContext,
                            packageName = packageName,
                            isReplacing = isReplacing,
                            source = "Dynamic_Watchdog"
                        )
                    }
                }
            }
        }

        try {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_PACKAGE_ADDED)
                addAction(Intent.ACTION_PACKAGE_REMOVED)
                addDataScheme("package")
            }
            ContextCompat.registerReceiver(appContext, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
            packageReceiver = receiver
            isPackageReceiverRegistered = true
            Timber.tag(TAG).i("Layer 1 - Dynamic package BroadcastReceiver registered successfully (RECEIVER_EXPORTED)")
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to register dynamic package receiver")
        }
    }

    private fun unregisterPackageReceiver(appContext: Context) {
        if (!isPackageReceiverRegistered) return
        packageReceiver?.let {
            try {
                appContext.unregisterReceiver(it)
                Timber.tag(TAG).i("Layer 1 - Dynamic package BroadcastReceiver unregistered")
            } catch (e: Exception) {
                Timber.tag(TAG).w("Failed to unregister dynamic package receiver: ${e.message}")
            }
            packageReceiver = null
        }
        isPackageReceiverRegistered = false
    }

    private fun registerScreenReceiver(appContext: Context, gen: Long) {
        if (isScreenReceiverRegistered) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent == null) return
                val action = intent.action
                if (action == Intent.ACTION_USER_PRESENT || action == Intent.ACTION_SCREEN_ON) {
                    synchronized(lock) {
                        if (!isRunning || gen != generationId) return
                    }
                    Timber.tag(TAG).d("Layer 2 - Screen event observed ($action). Debouncing scan...")
                    synchronized(lock) {
                        val handler = debounceHandler ?: return
                        handler.removeCallbacksAndMessages(null)
                        handler.postDelayed({
                            synchronized(lock) {
                                if (isRunning && gen == generationId) {
                                    ApprovalRepository.getInstance(appContext).syncZeroTrustBaselineAndReconcileAsync()
                                    protectionCheckChannel?.trySend(Unit)
                                }
                            }
                        }, SCREEN_DEBOUNCE_DELAY_MS)
                    }
                    try {
                        WaqfaForegroundWatcher.getInstance(appContext).onScreenStateChanged(true)
                    } catch (e: Exception) {
                        Timber.tag(TAG).w(e, "Error forwarding screen ON to WaqfaForegroundWatcher")
                    }
                } else if (action == Intent.ACTION_SCREEN_OFF) {
                    try {
                        WaqfaForegroundWatcher.getInstance(appContext).onScreenStateChanged(false)
                    } catch (e: Exception) {
                        Timber.tag(TAG).w(e, "Error forwarding screen OFF to WaqfaForegroundWatcher")
                    }
                }
            }
        }

        try {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_USER_PRESENT)
            }
            ContextCompat.registerReceiver(appContext, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
            screenReceiver = receiver
            isScreenReceiverRegistered = true
            Timber.tag(TAG).i("Layer 2 - Screen / User Present BroadcastReceiver registered successfully")
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to register screen receiver")
        }
    }

    private fun unregisterScreenReceiver(appContext: Context) {
        if (!isScreenReceiverRegistered) return
        screenReceiver?.let {
            try {
                appContext.unregisterReceiver(it)
                Timber.tag(TAG).i("Layer 2 - Screen / User Present BroadcastReceiver unregistered")
            } catch (e: Exception) {
                Timber.tag(TAG).w("Failed to unregister screen receiver: ${e.message}")
            }
            screenReceiver = null
        }
        isScreenReceiverRegistered = false
    }

    private fun startHeartbeat(appContext: Context, gen: Long) {
        val handler = heartbeatHandler ?: return
        val runnable = object : Runnable {
            override fun run() {
                synchronized(lock) {
                    if (!isRunning || gen != generationId) return
                }
                Timber.tag(TAG).d("Layer 3 - Periodic heartbeat (15m): verifying shields")
                protectionCheckChannel?.trySend(Unit)
                TrustedTimeProvider.syncNowAsync(appContext)

                val scope = runtimeScope
                if (scope != null) {
                    val nowRealtime = SystemClock.elapsedRealtime()
                    val lastCloud = lastCloudHeartbeatElapsed.get()
                    val isCloudEnabled = com.drmohammed.antiporn.cloud.CloudSyncManager.isCloudSyncEnabled(appContext)
                    if (isCloudEnabled) {
                        val isSupervised = com.drmohammed.antiporn.data.repository.PolicyRepository.getInstance(appContext).getCurrentPolicy().isSupervised
                        if (isSupervised && (nowRealtime - lastCloud >= CLOUD_HEARTBEAT_INTERVAL_MS || lastCloud == 0L)) {
                            scope.launch {
                                try {
                                    val res = AccountabilityRepository.getInstance(appContext).updateHeartbeat()
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
                    }

                    val lastOta = lastOtaCheckElapsed.get()
                    if (nowRealtime - lastOta >= OTA_UPDATE_CHECK_INTERVAL_MS || lastOta == 0L) {
                        scope.launch {
                            try {
                                com.drmohammed.antiporn.ota.repository.OtaRepository.getInstance(appContext)
                                    .checkForUpdatesAndAutoInstall(appContext)
                                lastOtaCheckElapsed.set(SystemClock.elapsedRealtime())
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                Timber.tag(TAG).w(e, "Watchdog periodic OTA update error")
                            }
                        }
                    }
                }

                synchronized(lock) {
                    if (isRunning && gen == generationId) {
                        heartbeatHandler?.postDelayed(this, HEARTBEAT_INTERVAL_MS)
                    }
                }
            }
        }

        handler.removeCallbacksAndMessages(null)
        handler.postDelayed(runnable, HEARTBEAT_INTERVAL_MS)
        Timber.tag(TAG).i("Layer 3 - Periodic heartbeat scheduled (every ${HEARTBEAT_INTERVAL_MS / 1000}s)")
    }

    private fun stopHeartbeat() {
        heartbeatHandler?.removeCallbacksAndMessages(null)
        Timber.tag(TAG).i("Layer 3 - Periodic heartbeat cancelled")
    }

    private fun checkAndEnforceProtectionInternal(appContext: Context, gen: Long) {
        if (!ProtectionEngine.isDeviceOwner(appContext)) {
            Timber.tag(TAG).d("Not Device Owner, skipping watchdog enforcement")
            return
        }

        // 0. Settle window & teardown check
        if (ProtectionTransitionGuard.isWithinSettleWindow()) {
            Timber.tag(TAG).d("System is within legitimate transition or settle window. Postponing watchdog audit...")
            scheduleCheck(3500L)
            return
        }

        // 0b. First-time baseline check: silently establish protection without false alarms
        if (!ProtectionTransitionGuard.hasCompletedInitialEnforcement(appContext)) {
            Timber.tag(TAG).i("Initial Device Owner protection baseline not yet established. Applying shields silently...")
            val results = ProtectionEngine.applyAllSync(appContext)
            if (results.any { it.success }) {
                ProtectionTransitionGuard.markInitialEnforcementCompleted(appContext)
            }
            return
        }

        // 0c. Live curfew block reconciliation: reconcile scheduled transitions before audit
        val shouldBlockBeActive = CurfewEnforcer.isEffectiveBlockActive(appContext)
        val wasBlockEnforced = CurfewConfig.isLastBlockEnforced(appContext)
        if (shouldBlockBeActive != wasBlockEnforced) {
            Timber.tag(TAG).i("Scheduled block state transition pending ($wasBlockEnforced -> $shouldBlockBeActive). Reconciling before audit.")
            CurfewEnforcer.syncBlockState(appContext)
            if (ProtectionTransitionGuard.isWithinSettleWindow()) {
                Timber.tag(TAG).d("Curfew block reconciliation triggered internal transition. Postponing watchdog audit...")
                scheduleCheck(3500L)
                return
            }
        }

        val desyncReasons = mutableListOf<String>()

        // 1. Surgical inspection of all registered security shields
        val shieldStatuses = ProtectionEngine.evaluateSystemStatus(appContext)
        for ((shieldId, status) in shieldStatuses) {
            when (status) {
                is ShieldStatus.Active -> { /* Fully active */ }
                is ShieldStatus.Unsupported -> {
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
                val requiredMode = CurfewBlackholeVpnService.evaluateRequiredMode(appContext)
                val isVpnConsentGranted = CurfewVpnDnsBridge.isVpnConsentGranted(appContext)
                val isVpnActive = isVpnConsentGranted && requiredMode != CurfewBlackholeVpnService.VpnMode.IDLE

                if (isVpnActive) {
                    val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                    val isExternalVpnRunning = cm?.allNetworks?.any { net ->
                        val caps = cm.getNetworkCapabilities(net)
                        caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
                    } == true && !CurfewBlackholeVpnService.isServiceOrTunnelActive()

                    val policy = try {
                        PolicyRepository.getInstance(appContext).getCurrentPolicy()
                    } catch (_: Exception) { null }
                    val allowExternalVpn = policy?.isBlockExternalVpnEnabled == false

                    if (allowExternalVpn && isExternalVpnRunning) {
                        Timber.tag(TAG).d("External VPN is active; yielding local VPN service and preserving DNS protection.")
                    } else {
                        val isAppropriate = mode == "opportunistic" || mode == "off" || mode == null
                        val isUnaffiliatedProtectedFallback = CurfewVpnDnsBridge.isPrivateDnsBlockedByUnaffiliatedUsers(appContext) &&
                            mode == "hostname" &&
                            !specifier.isNullOrBlank() &&
                            com.drmohammed.antiporn.core.network.DnsFilteringValidator.isKnownFamilyPreset(specifier)

                        if (!isAppropriate && !isUnaffiliatedProtectedFallback) {
                            val reason = "Settings.Global DNS mismatch: VPN required, expected mode='opportunistic' but found mode='$mode', specifier='$specifier'"
                            Timber.tag(TAG).w(reason)
                            desyncReasons.add("[DNS_GLOBAL: $reason]")
                        } else if (isUnaffiliatedProtectedFallback) {
                            Timber.tag(TAG).i("Local VPN active with verified fallback DoT (%s) due to unaffiliated users. Suppressing desync.", specifier)
                        }
                        if (!CurfewBlackholeVpnService.isTunnelHealthy(requiredMode)) {
                            Timber.tag(TAG).i("VPN service or DNS worker not healthy while required. Enqueuing silent sync.")
                            CurfewBlackholeVpnService.syncPolicy(appContext)
                        }
                    }
                } else {
                    val expectedHost = DnsShield.getEffectiveDnsHost(appContext)
                    val isHostMatching = specifier?.equals(expectedHost, ignoreCase = true) == true
                    if (mode != "hostname" || !isHostMatching) {
                        val isUnaffiliatedProtectedFallback = CurfewVpnDnsBridge.isPrivateDnsBlockedByUnaffiliatedUsers(appContext) &&
                            mode == "hostname" &&
                            !specifier.isNullOrBlank() &&
                            com.drmohammed.antiporn.core.network.DnsFilteringValidator.isKnownFamilyPreset(specifier)

                        val isPendingCurfewRelease = (mode == "opportunistic" || mode.isNullOrEmpty()) &&
                            specifier.isNullOrEmpty() &&
                            CurfewConfig.isDnsRestorePending(appContext)

                        if (isUnaffiliatedProtectedFallback) {
                            Timber.tag(TAG).i("Settings.Global DNS running verified fallback (%s) due to unaffiliated users. Skipping desync.", specifier)
                        } else if (isPendingCurfewRelease) {
                            val reason = "Settings.Global DNS pending post-curfew restoration (mode=$mode, expected=$expectedHost)"
                            Timber.tag(TAG).i(reason)
                            desyncReasons.add("[DNS_PENDING_RESTORE: $reason]")
                            CurfewVpnDnsBridge.restoreDnsShieldOnCurfewRelease(appContext)
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

        // 3. Verify Target Timezone and Network Auto Time directly
        val isTimeLocked = try {
            PolicyRepository.getInstance(appContext).getCurrentPolicy().isDateTimeLocked
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

            BlackBoxRecorder.record(
                context = appContext,
                tag = BlackBoxRecorder.TAG_SHIELD,
                actionName = "WATCHDOG_TAMPER_DETECTED",
                isSuccess = false,
                errorDetails = detailedReasons,
                metadata = "failedCount=${desyncReasons.size}"
            )

            val isDirectTamper = desyncReasons.any {
                it.startsWith("[TIMEZONE:") ||
                    it.startsWith("[AUTO_TIME:") ||
                    it.startsWith("[system_tamper:")
            }

            val isCurfewStateSynchronized = CurfewEnforcer.isEffectiveBlockActive(appContext) == CurfewConfig.isLastBlockEnforced(appContext)
            val isTransitionSafe = !ProtectionTransitionGuard.isWithinSettleWindow() &&
                !ProtectionTransitionGuard.isTeardownInProgress &&
                isCurfewStateSynchronized &&
                !CurfewConfig.isDnsRestorePending(appContext)

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

        ApprovalRepository.getInstance(appContext).syncZeroTrustBaselineAndReconcileAsync()
    }
}
