package com.drmohammed.antiporn.curfew

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.annotation.VisibleForTesting
import com.drmohammed.antiporn.core.engine.ProtectionTransitionGuard
import com.drmohammed.antiporn.core.model.DevicePolicyBundle
import com.drmohammed.antiporn.core.network.dns.BinaryDomainFilter
import com.drmohammed.antiporn.core.network.dns.DnsPacketParser
import com.drmohammed.antiporn.core.network.dns.DnsTunnelWorker
import com.drmohammed.antiporn.core.network.dns.DnsUpstreamForwarder
import com.drmohammed.antiporn.curfew.notification.ProtectionNotificationCoordinator
import com.drmohammed.antiporn.data.repository.PolicyRepository
import com.drmohammed.antiporn.notification.registry.NotificationRegistry
import com.drmohammed.antiporn.shields.DnsShield
import com.drmohammed.antiporn.telemetry.BlackBoxRecorder
import com.drmohammed.antiporn.telemetry.SafeDpmGateway
import com.drmohammed.antiporn.watchdog.ProtectionWatchdogRuntime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Unified 24/7 Protection VPN Service coordinating daytime Local DNS Sinkhole
 * inspection and nighttime Curfew blackout via serialized off-main-thread execution.
 * Companion state and tunnel configuration are decomposed into [CurfewVpnStateCoordinator]
 * and [CurfewVpnTunnelHandler].
 */
class CurfewBlackholeVpnService : VpnService() {

    enum class VpnMode {
        IDLE,
        DNS_FILTERING_ONLY,
        CURFEW_BLACKHOLE
    }

    internal enum class StartForegroundAction {
        PROMOTE_FGS_ONLY,
        PROMOTE_THEN_DEMOTE,
        SKIP_SILENT_BOUND
    }

    internal sealed class VpnCommand {
        data class StartOrUpdate(val forceRebuild: Boolean = false) : VpnCommand()
        data class StopIfAllowed(val startId: Int = -1) : VpnCommand()
        data object ForceTeardown : VpnCommand()
    }

    private var vpnInterface: ParcelFileDescriptor? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val forwarderJob = SupervisorJob(serviceScope.coroutineContext[Job])
    private val forwarderScope = CoroutineScope(forwarderJob + Dispatchers.IO)
    internal val commandChannel = Channel<VpnCommand>(Channel.UNLIMITED)
    private val tunnelMutex = Mutex()
    internal var dnsWorkerJob: Job? = null
    private val failClosedArmedOnStop = AtomicBoolean(false)

    private var physicalNetworkMonitor: PhysicalNetworkMonitor? = null
    private val networkChangeCoalescer = NetworkChangeCoalescer(
        scope = serviceScope,
        clockMs = ::monotonicNowMs,
        coalesceMs = NetworkChangeCoalescer.DEFAULT_COALESCE_MS,
        isDestroyed = { isServiceDestroyed || isServiceRevoked },
        onFlush = { reason ->
            dnsForwarder?.onNetworkChanged()
            Timber.tag(TAG).i("Flushed DNS cache, DoT pool & circuit breaker on network change ($reason)")
        }
    )

    private val foregroundLock = Any()

    @Volatile
    internal var isForegroundActive: Boolean = false
        private set

    @Volatile
    internal var isServiceDestroyed: Boolean = false

    @Volatile
    internal var isServiceRevoked: Boolean = false

    @Volatile
    internal var lastStartId: Int = -1

    @Volatile
    private var currentMode: VpnMode = VpnMode.IDLE

    @Volatile
    private var lastBypassedPackages: Set<String>? = null

    private var dnsForwarder: DnsUpstreamForwarder? = null
    private var dnsTunnelWorker: DnsTunnelWorker? = null

    @Volatile
    private var activePolicy: DevicePolicyBundle = DevicePolicyBundle.defaultPolicy()

    @Volatile
    private var compiledBlockedDomainsCache: Set<String> = emptySet()

    internal fun createTunnelBuilder(): Builder = Builder()

    override fun onCreate() {
        super.onCreate()
        failClosedArmedOnStop.set(false)
        val appContext = applicationContext ?: this
        synchronized(CurfewVpnStateCoordinator.companionStateLock) {
            CurfewVpnStateCoordinator.activeService = WeakReference(this)
            CurfewVpnStateCoordinator.isStartPending = false
        }

        val forwarder = DnsUpstreamForwarder(vpnService = this)
        dnsForwarder = forwarder
        dnsTunnelWorker = DnsTunnelWorker(
            forwarder = forwarder,
            policyProvider = { activePolicy },
            customBlockedProvider = { compiledBlockedDomainsCache },
            isRunningProvider = { currentMode == VpnMode.DNS_FILTERING_ONLY }
        )
        physicalNetworkMonitor = PhysicalNetworkMonitor(
            appContext = appContext,
            isDestroyed = { isServiceDestroyed || isServiceRevoked },
            onNetworkChanged = ::notifyNetworkChanged
        ).also { it.register() }

        serviceScope.launch {
            try {
                BinaryDomainFilter.init(appContext)
            } catch (e: Exception) {
                Timber.tag(TAG).w(e, "BinaryDomainFilter pre-init failed")
            }
            for (cmd in commandChannel) {
                handleCommand(cmd)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val appContext = applicationContext ?: this
        synchronized(CurfewVpnStateCoordinator.companionStateLock) {
            CurfewVpnStateCoordinator.activeService = WeakReference(this)
            CurfewVpnStateCoordinator.isStartPending = false
        }
        lastStartId = startId
        val startedAsFgs = intent?.getBooleanExtra(EXTRA_STARTED_AS_FGS, false) == true
        val forceKeepFgs = intent?.getBooleanExtra(EXTRA_FORCE_KEEP_FGS, false) == true

        val action = intent?.action
        if (action == ACTION_FORCE_TEARDOWN || shouldAbortForPostTeardown(appContext)) {
            if (startedAsFgs) safeStartForegroundNotification()
            isServiceRevoked = true
            commandChannel.trySend(VpnCommand.ForceTeardown)
            return START_NOT_STICKY
        }
        if (action == ACTION_STOP) {
            if (startedAsFgs) safeStartForegroundNotification()
            commandChannel.trySend(VpnCommand.StopIfAllowed(startId = startId))
            return START_NOT_STICKY
        }

        val canRunSilently = !forceKeepFgs && CurfewVpnStateCoordinator.canRunDnsVpnSilently(appContext)
        when (resolveStartForegroundAction(startedAsFgs, canRunSilently)) {
            StartForegroundAction.PROMOTE_FGS_ONLY -> safeStartForegroundNotification()
            StartForegroundAction.PROMOTE_THEN_DEMOTE -> {
                safeStartForegroundNotification()
                if (isForegroundActive) {
                    demoteFromForegroundSilently(appContext)
                }
            }
            StartForegroundAction.SKIP_SILENT_BOUND -> Unit
        }

        isServiceRevoked = false
        physicalNetworkMonitor?.register()
        val forceRebuild = intent?.getBooleanExtra("force_rebuild", false) == true
        commandChannel.trySend(VpnCommand.StartOrUpdate(forceRebuild = forceRebuild))
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? {
        val appContext = applicationContext ?: this
        if (intent?.action == SERVICE_INTERFACE) {
            if (shouldAbortForPostTeardown(appContext)) {
                isServiceRevoked = true
                commandChannel.trySend(VpnCommand.ForceTeardown)
                return super.onBind(intent)
            }
            isServiceRevoked = false
            physicalNetworkMonitor?.register()
            synchronized(CurfewVpnStateCoordinator.companionStateLock) {
                CurfewVpnStateCoordinator.activeService = WeakReference(this)
                CurfewVpnStateCoordinator.isStartPending = false
            }
            Timber.tag(TAG).i("Always-On OS direct bind received; ensuring tunnel")
            if (!CurfewVpnStateCoordinator.canRunDnsVpnSilently(appContext)) {
                safeStartForegroundNotification()
            }
            commandChannel.trySend(VpnCommand.StartOrUpdate(forceRebuild = false))
        }
        return super.onBind(intent)
    }

    private fun shouldAbortForPostTeardown(appContext: Context): Boolean {
        if (ProtectionTransitionGuard.isTeardownInProgress) return true
        return !SafeDpmGateway.isDeviceOwner(appContext) &&
            PolicyRepository.getInstance(appContext).getCurrentPolicy().deactivationStatus == DevicePolicyBundle.DEACTIVATION_STATUS_COMPLETED &&
            CurfewVpnStateCoordinator.evaluateRequiredMode(appContext) == VpnMode.IDLE
    }

    private suspend fun handleCommand(cmd: VpnCommand) {
        val appContext = applicationContext ?: this
        if (ProtectionTransitionGuard.isTeardownInProgress) {
            Timber.tag(TAG).i("Force tearing down VPN tunnel (cmd=$cmd, teardownInProgress=true)")
            isServiceRevoked = true
            stopVpnAndSelf(appContext, lastStartId)
            return
        }
        val isCurfewBlackoutActive = CurfewVpnStateCoordinator.isBlackoutActive(appContext)

        when (cmd) {
            is VpnCommand.ForceTeardown -> {
                Timber.tag(TAG).i("Force tearing down VPN tunnel via ForceTeardown command")
                isServiceRevoked = true
                stopVpnAndSelf(appContext, lastStartId)
            }

            is VpnCommand.StopIfAllowed -> {
                if (isCurfewBlackoutActive) {
                    Timber.tag(TAG).w("Rejected ACTION_STOP because curfew blackout is actively enforced!")
                    establishTunnel(VpnMode.CURFEW_BLACKHOLE, forceRebuild = false)
                    syncNotificationSafely(appContext)
                    return
                }
                if (cmd.startId > 0 && lastStartId > cmd.startId) {
                    Timber.tag(TAG).i("Ignoring superseded ACTION_STOP(startId=${cmd.startId}, latest=$lastStartId)")
                    return
                }
                Timber.tag(TAG).i("Received ACTION_STOP. Tearing down VPN tunnel and stopping service.")
                stopVpnAndSelf(appContext, cmd.startId)
            }

            is VpnCommand.StartOrUpdate -> {
                refreshBlockedUrlsCache()
                val targetMode = CurfewVpnStateCoordinator.evaluateRequiredMode(appContext, fallbackOnFailure = currentMode)
                Timber.tag(TAG).i("Evaluating targetMode: $targetMode (currentMode=$currentMode)")
                if (targetMode == VpnMode.IDLE) {
                    stopVpnAndSelf(appContext, lastStartId)
                    return
                }
                establishTunnel(targetMode, cmd.forceRebuild)
                syncNotificationSafely(appContext)
            }
        }
    }

    private suspend fun establishTunnel(targetMode: VpnMode, forceRebuild: Boolean = false) {
        tunnelMutex.withLock {
            val appContext = applicationContext ?: this
            if (isServiceDestroyed || isServiceRevoked || ProtectionTransitionGuard.isTeardownInProgress) {
                Timber.tag(TAG).i("Aborting establishTunnel because service is destroyed, revoked, or tearing down")
                CurfewVpnStateCoordinator.completeTunnelWaiters(false)
                return@withLock
            }
            if (targetMode == VpnMode.IDLE) {
                stopVpnInternal(removeActiveRef = true)
                stopServiceForStartId(lastStartId)
                return@withLock
            }

            if (!forceRebuild && vpnInterface != null && currentMode == targetMode) {
                if (targetMode == VpnMode.CURFEW_BLACKHOLE) {
                    if (CurfewConfig.getVpnBypassedPackages(appContext) == lastBypassedPackages) {
                        CurfewVpnStateCoordinator.completeTunnelWaiters(true)
                        return@withLock
                    }
                } else if (dnsWorkerJob?.isActive == true) {
                    CurfewVpnStateCoordinator.completeTunnelWaiters(true)
                    return@withLock
                }
            }

            val oldVpnInterface = vpnInterface
            var pendingVpnInterface: ParcelFileDescriptor? = null
            try {
                val tunnelConfig = CurfewVpnTunnelHandler.prepareTunnelConfig(
                    service = this,
                    appContext = appContext,
                    targetMode = targetMode,
                    lastBypassedPackages = lastBypassedPackages
                )
                val newVpnInterface = try {
                    tunnelConfig.builder.establish()
                } catch (e: Exception) {
                    Timber.tag(TAG).e(e, "Failed to establish TUN interface for mode $targetMode")
                    null
                }
                pendingVpnInterface = newVpnInterface

                if (isServiceDestroyed || isServiceRevoked || ProtectionTransitionGuard.isTeardownInProgress) {
                    runCatching { pendingVpnInterface?.close() }
                    pendingVpnInterface = null
                    withContext(NonCancellable) { stopVpnInternal(removeActiveRef = true) }
                    CurfewVpnStateCoordinator.completeTunnelWaiters(false)
                    return@withLock
                }

                if (newVpnInterface != null) {
                    runCatching { oldVpnInterface?.close() }
                        .onFailure { Timber.tag(TAG).w(it, "Error closing previous TUN interface during handover") }
                    stopActiveIoLoop()

                    if (isServiceDestroyed || isServiceRevoked || ProtectionTransitionGuard.isTeardownInProgress) {
                        runCatching { pendingVpnInterface?.close() }
                        pendingVpnInterface = null
                        withContext(NonCancellable) { stopVpnInternal(removeActiveRef = true) }
                        CurfewVpnStateCoordinator.completeTunnelWaiters(false)
                        return@withLock
                    }

                    vpnInterface = newVpnInterface
                    pendingVpnInterface = null
                    currentMode = targetMode
                    lastBypassedPackages = tunnelConfig.appliedBypassedPackages

                    refreshBlockedUrlsCache()
                    if (targetMode == VpnMode.DNS_FILTERING_ONLY) {
                        startDnsFilteringLoop(newVpnInterface)
                    }

                    var tunnelActivated = false
                    synchronized(CurfewVpnStateCoordinator.companionStateLock) {
                        val activeRef = CurfewVpnStateCoordinator.activeService?.get()
                        if (!isServiceDestroyed && !isServiceRevoked && (activeRef === this || activeRef == null)) {
                            CurfewVpnStateCoordinator.activeService = WeakReference(this)
                            CurfewVpnStateCoordinator.currentMode = targetMode
                            CurfewVpnStateCoordinator.isRunning = true
                            CurfewVpnStateCoordinator.isStartPending = false
                            tunnelActivated = true
                        }
                    }
                    if (tunnelActivated) {
                        CurfewVpnStateCoordinator.completeTunnelWaiters(true)
                    } else if (synchronized(CurfewVpnStateCoordinator.companionStateLock) {
                            val activeRef = CurfewVpnStateCoordinator.activeService?.get()
                            activeRef == null || activeRef === this
                        }
                    ) {
                        CurfewVpnStateCoordinator.completeTunnelWaiters(false)
                    }
                    Timber.tag(TAG).i("Seamless Handover completed successfully to mode: $targetMode")
                    runCatching {
                        BlackBoxRecorder.record(
                            context = appContext,
                            tag = BlackBoxRecorder.TAG_NETWORK,
                            actionName = "vpnTunnelEstablished",
                            isSuccess = true,
                            metadata = "mode=$targetMode"
                        )
                    }.onFailure { Timber.tag(TAG).d(it, "BlackBox VPN record skipped") }
                } else {
                    CurfewVpnStateCoordinator.completeTunnelWaiters(oldVpnInterface != null && isRunning)
                    if (oldVpnInterface == null) {
                        stopVpnInternal(removeActiveRef = true)
                        stopServiceForStartId(lastStartId)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Error during tunnel establishment for mode $targetMode")
                CurfewVpnStateCoordinator.completeTunnelWaiters(
                    oldVpnInterface != null && isRunning && !isServiceDestroyed && !isServiceRevoked
                )
                if (isServiceDestroyed || isServiceRevoked || oldVpnInterface == null) {
                    withContext(NonCancellable) { stopVpnInternal(removeActiveRef = true) }
                    if (!isServiceDestroyed) {
                        stopServiceForStartId(lastStartId)
                    }
                }
            } finally {
                pendingVpnInterface?.let { runCatching { it.close() } }
                if ((isServiceDestroyed || isServiceRevoked) && vpnInterface != null) {
                    withContext(NonCancellable) { stopVpnInternal(removeActiveRef = true) }
                }
            }
        }
        if (isServiceDestroyed || isServiceRevoked) {
            withContext(NonCancellable) {
                tunnelMutex.withLock {
                    if (vpnInterface != null || CurfewVpnStateCoordinator.activeService?.get() === this@CurfewBlackholeVpnService) {
                        stopVpnInternal(removeActiveRef = true)
                    }
                }
            }
        }
    }

    private fun refreshBlockedUrlsCache() {
        val appContext = applicationContext ?: this
        try {
            val policy = PolicyRepository.getInstance(appContext).getCurrentPolicy()
            activePolicy = policy
            compiledBlockedDomainsCache = DnsPacketParser.compileEffectiveBlockedDomains(policy.customBlockedUrls)
            dnsForwarder?.setCustomDnsHost(policy.dnsHost)
            dnsForwarder?.clearResponseCache()
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Failed to refresh policy cache")
        }
    }

    private suspend fun stopActiveIoLoop() {
        val job = dnsWorkerJob
        dnsWorkerJob = null
        forwarderJob.cancelChildren()
        runCatching { dnsForwarder?.onNetworkChanged() }
        if (job != null) {
            try {
                job.cancelAndJoin()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.tag(TAG).w(e, "Error stopping DNS worker loop")
            }
        }
    }

    private fun startDnsFilteringLoop(pfd: ParcelFileDescriptor) {
        val worker = dnsTunnelWorker ?: return
        val job = worker.start(serviceScope, forwarderScope, pfd)
        dnsWorkerJob = job
        job.invokeOnCompletion { cause ->
            if (cause !is CancellationException &&
                !isServiceDestroyed &&
                !isServiceRevoked &&
                currentMode == VpnMode.DNS_FILTERING_ONLY &&
                vpnInterface === pfd
            ) {
                Timber.tag(TAG).e(cause, "DnsTunnelWorker exited unexpectedly; scheduling tunnel self-heal")
                commandChannel.trySend(VpnCommand.StartOrUpdate(forceRebuild = true))
            }
        }
    }

    private fun safeStartForegroundNotification() {
        synchronized(foregroundLock) {
            try {
                val notification = ProtectionNotificationCoordinator.buildProtectionNotification(this)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
                } else {
                    startForeground(NOTIFICATION_ID, notification)
                }
                isForegroundActive = true
            } catch (e: Exception) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                    e is android.app.ForegroundServiceStartNotAllowedException
                ) {
                    Timber.tag(TAG).w(e, "FGS promotion rejected by OS background restrictions")
                } else {
                    Timber.tag(TAG).e(e, "Failed promoting CurfewBlackholeVpnService to foreground")
                }
            }
        }
    }

    @Synchronized
    internal fun syncForegroundPresentation() {
        val appContext = applicationContext ?: this
        if (isServiceDestroyed || isServiceRevoked || ProtectionTransitionGuard.isTeardownInProgress) return
        if (currentMode == VpnMode.IDLE) return
        if (currentMode == VpnMode.DNS_FILTERING_ONLY && CurfewVpnStateCoordinator.canRunDnsVpnSilently(appContext)) {
            demoteFromForegroundSilently(appContext)
        } else {
            safeStartForegroundNotification()
        }
    }

    private fun demoteFromForegroundSilently(appContext: Context) {
        synchronized(foregroundLock) {
            try {
                if (isForegroundActive) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                        stopForeground(STOP_FOREGROUND_REMOVE)
                    } else {
                        @Suppress("DEPRECATION")
                        stopForeground(true)
                    }
                    isForegroundActive = false
                }
                val nm = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                nm?.cancel(NOTIFICATION_ID)
            } catch (e: Exception) {
                Timber.tag(TAG).w(e, "Failed demoting VPN service to silent bound mode")
            }
        }
    }

    private fun syncNotificationSafely(appContext: Context) {
        try {
            syncForegroundPresentation()
            ProtectionNotificationCoordinator.syncProtectionNotification(appContext)
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Error syncing protection notification from VPN service")
        }
    }

    private suspend fun stopVpnAndSelf(appContext: Context, expectedStartId: Int = -1) {
        if (expectedStartId > 0 && lastStartId > expectedStartId) {
            Timber.tag(TAG).i("Skipping superseded stopVpnAndSelf(expected=$expectedStartId, latest=$lastStartId)")
            return
        }
        val shouldStop: Boolean = tunnelMutex.withLock {
            if (expectedStartId > 0 && lastStartId > expectedStartId) {
                Timber.tag(TAG).i("Skipping stopVpnInternal inside lock due to newer startId=$lastStartId")
                false
            } else {
                stopVpnInternal(removeActiveRef = true)
                true
            }
        }
        if (!shouldStop) return
        syncNotificationSafely(appContext)
        val stopTargetId = if (expectedStartId > 0) expectedStartId else lastStartId
        stopServiceForStartId(stopTargetId)
    }

    private fun stopServiceForStartId(startId: Int) {
        if (startId > 0) stopSelfResult(startId) else stopSelf()
    }

    @Synchronized
    private fun stopVpnInternal(removeActiveRef: Boolean) {
        currentMode = VpnMode.IDLE
        lastBypassedPackages = null
        val ownedActive = synchronized(CurfewVpnStateCoordinator.companionStateLock) {
            val isOwner = CurfewVpnStateCoordinator.activeService?.get() === this
            if (isOwner) {
                CurfewVpnStateCoordinator.isRunning = false
                CurfewVpnStateCoordinator.isStartPending = false
                CurfewVpnStateCoordinator.currentMode = VpnMode.IDLE
                if (removeActiveRef) {
                    CurfewVpnStateCoordinator.activeService = null
                }
            }
            isOwner || CurfewVpnStateCoordinator.activeService == null
        }
        if (ownedActive) {
            CurfewVpnStateCoordinator.completeTunnelWaiters(false)
        }

        dnsWorkerJob?.cancel()
        dnsWorkerJob = null
        dnsTunnelWorker?.closeActiveStreams()
        forwarderJob.cancelChildren()
        if (!isServiceDestroyed) {
            runCatching { dnsForwarder?.onNetworkChanged() }
        }
        try {
            vpnInterface?.close()
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Error closing VPN interface descriptor")
        } finally {
            vpnInterface = null
            CurfewVpnStateCoordinator.completeTunnelStoppedWaiters(true)
        }

        synchronized(foregroundLock) {
            try {
                stopForeground(STOP_FOREGROUND_REMOVE)
                isForegroundActive = false
            } catch (e: Exception) {
                Timber.tag(TAG).w(e, "Error stopping foreground notification")
            }
        }
    }

    override fun onRevoke() {
        Timber.tag(TAG).w("Protection VPN revoked by system or user")
        isServiceRevoked = true
        val appContext = applicationContext ?: this
        ensureFailClosedOnUnexpectedStop(appContext, wasRevoked = true)
        physicalNetworkMonitor?.unregister()
        CurfewVpnStateCoordinator.completeTunnelWaiters(false)
        if (tunnelMutex.tryLock()) {
            try {
                stopVpnInternal(removeActiveRef = true)
            } finally {
                tunnelMutex.unlock()
            }
        }
        stopServiceForStartId(lastStartId)
        super.onRevoke()
    }

    private fun monotonicNowMs(): Long = try {
        SystemClock.elapsedRealtime()
    } catch (_: Throwable) {
        System.nanoTime() / 1_000_000L
    }

    internal fun notifyNetworkChanged(reason: String) {
        networkChangeCoalescer.notify(reason)
    }

    @VisibleForTesting
    internal fun ensureFailClosedOnUnexpectedStop(appContext: Context, wasRevoked: Boolean) {
        if (ProtectionTransitionGuard.hasActiveTransitions()) {
            if (wasRevoked) {
                ProtectionWatchdogRuntime.scheduleCheck(500L)
            }
            return
        }
        if (!failClosedArmedOnStop.compareAndSet(false, true)) {
            return
        }
        appFailClosedScope.launch {
            try {
                if (ProtectionTransitionGuard.hasActiveTransitions() || !SafeDpmGateway.isDeviceOwner(appContext)) {
                    return@launch
                }
                if (CurfewVpnStateCoordinator.evaluateRequiredMode(appContext) != VpnMode.IDLE) {
                    if (wasRevoked || !CurfewVpnDnsBridge.isVpnConsentGranted(appContext)) {
                        ProtectionTransitionGuard.runInternalTransition {
                            if (ProtectionTransitionGuard.isTeardownInProgress ||
                                CurfewVpnStateCoordinator.isTunnelHealthy() ||
                                (CurfewVpnStateCoordinator.isServiceOrTunnelActive() &&
                                    CurfewVpnDnsBridge.isVpnConsentGranted(appContext))
                            ) {
                                return@runInternalTransition
                            }
                            CurfewVpnDnsBridge.preArmStandaloneDotFailClosed(
                                appContext,
                                DnsShield.getEffectiveDnsHost(appContext)
                            )
                        }
                    }
                    ProtectionWatchdogRuntime.scheduleCheck(500L)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.tag(TAG).w(e, "Error pre-arming Standalone DoT fail-closed on unexpected stop")
            }
        }
    }

    override fun onDestroy() {
        isServiceDestroyed = true
        val appContext = applicationContext ?: this
        if (currentMode != VpnMode.IDLE && !ProtectionTransitionGuard.isTeardownInProgress) {
            ensureFailClosedOnUnexpectedStop(appContext, wasRevoked = isServiceRevoked)
        }
        physicalNetworkMonitor?.unregister()
        physicalNetworkMonitor = null
        val ownsActiveState = synchronized(CurfewVpnStateCoordinator.companionStateLock) {
            CurfewVpnStateCoordinator.activeService == null || CurfewVpnStateCoordinator.activeService?.get() === this
        }
        if (ownsActiveState) {
            CurfewVpnStateCoordinator.completeTunnelWaiters(false)
        }
        super.onDestroy()
        Timber.tag(TAG).i("CurfewBlackholeVpnService destroyed")
        commandChannel.close()
        try {
            if (tunnelMutex.tryLock()) {
                try {
                    stopVpnInternal(removeActiveRef = true)
                } finally {
                    tunnelMutex.unlock()
                }
            }
        } finally {
            runCatching { dnsForwarder?.clearCache() }
                .onFailure { Timber.tag(TAG).w(it, "Error clearing DNS forwarder cache/sockets on destroy") }
            serviceScope.cancel()
            val shouldCompleteStopped = synchronized(CurfewVpnStateCoordinator.companionStateLock) {
                val isOwner = CurfewVpnStateCoordinator.activeService?.get() === this
                if (isOwner) {
                    CurfewVpnStateCoordinator.isRunning = false
                    CurfewVpnStateCoordinator.isStartPending = false
                    CurfewVpnStateCoordinator.currentMode = VpnMode.IDLE
                    CurfewVpnStateCoordinator.activeService = null
                }
                isOwner || CurfewVpnStateCoordinator.activeService == null
            }
            if (shouldCompleteStopped || ownsActiveState) {
                CurfewVpnStateCoordinator.completeTunnelStoppedWaiters(true)
            }
        }
    }

    companion object {
        private const val TAG = "AntiPorn_UnifiedVpn"
        private val appFailClosedScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        const val CHANNEL_ID = NotificationRegistry.CHANNEL_ID_PROTECTION_VPN
        const val NOTIFICATION_ID = NotificationRegistry.FGS_CURFEW_VPN_ID

        const val ACTION_START = "com.drmohammed.antiporn.curfew.ACTION_START_VPN"
        const val ACTION_STOP = "com.drmohammed.antiporn.curfew.ACTION_STOP_VPN"
        const val ACTION_FORCE_TEARDOWN = "com.drmohammed.antiporn.curfew.ACTION_FORCE_TEARDOWN"
        const val EXTRA_STARTED_AS_FGS = "extra_started_as_fgs"
        const val EXTRA_FORCE_KEEP_FGS = "extra_force_keep_fgs"

        internal fun resolveStartForegroundAction(
            startedAsFgs: Boolean,
            canRunSilently: Boolean
        ): StartForegroundAction = when {
            startedAsFgs && canRunSilently -> StartForegroundAction.PROMOTE_THEN_DEMOTE
            startedAsFgs || !canRunSilently -> StartForegroundAction.PROMOTE_FGS_ONLY
            else -> StartForegroundAction.SKIP_SILENT_BOUND
        }

        val isRunning: Boolean
            get() = CurfewVpnStateCoordinator.isRunning

        val currentMode: VpnMode
            get() = CurfewVpnStateCoordinator.currentMode

        fun onNetworkChanged(reason: String = "external") =
            CurfewVpnStateCoordinator.onNetworkChanged(reason)

        fun awaitTunnelReadyBlocking(expectedMode: VpnMode? = null, timeoutMs: Long = 1_500L): Boolean =
            CurfewVpnStateCoordinator.awaitTunnelReadyBlocking(expectedMode, timeoutMs)

        fun isTunnelHealthy(expectedMode: VpnMode? = null): Boolean =
            CurfewVpnStateCoordinator.isTunnelHealthy(expectedMode)

        fun isServiceOrTunnelActive(): Boolean =
            CurfewVpnStateCoordinator.isServiceOrTunnelActive()

        fun getLiveStateSummary(): String =
            CurfewVpnStateCoordinator.getLiveStateSummary()

        fun evaluateRequiredMode(context: Context, fallbackOnFailure: VpnMode = currentMode): VpnMode =
            CurfewVpnStateCoordinator.evaluateRequiredMode(context, fallbackOnFailure)

        fun syncPolicy(context: Context) =
            CurfewVpnStateCoordinator.syncPolicy(context)

        fun forceReleaseVpn(context: Context) {
            forceReleaseVpnAndAwait(context, 1000L)
        }

        fun forceReleaseVpnAndAwait(context: Context, timeoutMs: Long = 2000L): Boolean =
            CurfewVpnStateCoordinator.forceReleaseVpnAndAwait(context, timeoutMs)

        fun start(context: Context) =
            CurfewVpnStateCoordinator.start(context)

        fun forceStopForTeardown(context: Context) =
            CurfewVpnStateCoordinator.forceStopForTeardown(context)

        fun stop(context: Context) =
            CurfewVpnStateCoordinator.stop(context)
    }
}
