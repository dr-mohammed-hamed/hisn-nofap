package com.drmohammed.antiporn.curfew

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Looper
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import com.drmohammed.antiporn.core.engine.ProtectionTransitionGuard
import com.drmohammed.antiporn.core.model.DevicePolicyBundle
import com.drmohammed.antiporn.core.network.dns.DnsResilienceManager
import com.drmohammed.antiporn.curfew.CurfewBlackholeVpnService.VpnCommand
import com.drmohammed.antiporn.curfew.CurfewBlackholeVpnService.VpnMode
import com.drmohammed.antiporn.curfew.notification.ProtectionNotificationCoordinator
import com.drmohammed.antiporn.data.repository.PolicyRepository
import com.drmohammed.antiporn.telemetry.SafeDpmGateway
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import java.lang.ref.WeakReference

/**
 * Coordinates runtime companion state, tunnel readiness/teardown synchronization waiters,
 * policy mode evaluation, and lifecycle commands for [CurfewBlackholeVpnService].
 */
internal object CurfewVpnStateCoordinator {

    private const val TAG = "AntiPorn_UnifiedVpn"
    internal val companionStateLock = Any()
    private val presentationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    internal var activeService: WeakReference<CurfewBlackholeVpnService>? = null

    @Volatile
    internal var isStartPending: Boolean = false

    @Volatile
    var isRunning: Boolean = false

    @Volatile
    var currentMode: VpnMode = VpnMode.IDLE

    @Volatile
    private var tunnelReadyDeferred: CompletableDeferred<Boolean>? = null

    @Volatile
    private var tunnelStoppedDeferred: CompletableDeferred<Boolean>? = null

    fun onNetworkChanged(reason: String = "external") {
        val service = synchronized(companionStateLock) { activeService?.get() }
        if (service != null && !service.isServiceDestroyed) {
            service.notifyNetworkChanged(reason)
        }
    }

    internal fun prepareTunnelWaiter(): CompletableDeferred<Boolean> = synchronized(companionStateLock) {
        tunnelReadyDeferred?.takeIf { !it.isCompleted }
            ?: CompletableDeferred<Boolean>().also { tunnelReadyDeferred = it }
    }

    internal fun completeTunnelWaiters(ready: Boolean) {
        val deferred = synchronized(companionStateLock) {
            tunnelReadyDeferred.also { tunnelReadyDeferred = null }
        }
        deferred?.complete(ready)
    }

    internal fun prepareTunnelStoppedWaiter(): CompletableDeferred<Boolean> = synchronized(companionStateLock) {
        tunnelStoppedDeferred?.takeIf { !it.isCompleted }
            ?: CompletableDeferred<Boolean>().also { tunnelStoppedDeferred = it }
    }

    internal fun completeTunnelStoppedWaiters(stopped: Boolean) {
        val deferred = synchronized(companionStateLock) {
            tunnelStoppedDeferred.also { tunnelStoppedDeferred = null }
        }
        deferred?.complete(stopped)
    }

    fun awaitTunnelReadyBlocking(expectedMode: VpnMode? = null, timeoutMs: Long = 1_500L): Boolean {
        fun isModeSatisfiedLocked(): Boolean =
            isRunning && (expectedMode == null || currentMode == expectedMode)

        if (Looper.myLooper() == Looper.getMainLooper()) {
            return synchronized(companionStateLock) { isModeSatisfiedLocked() }
        }
        val deadlineNs = System.nanoTime() + timeoutMs.coerceAtLeast(0L) * 1_000_000L
        try {
            while (true) {
                val deferred = synchronized(companionStateLock) {
                    if (isModeSatisfiedLocked()) return true
                    if (!isStartPending && activeService?.get() == null) return false
                    tunnelReadyDeferred?.takeIf { !it.isCompleted }
                        ?: CompletableDeferred<Boolean>().also { tunnelReadyDeferred = it }
                }
                val remainingMs = (deadlineNs - System.nanoTime()) / 1_000_000L
                if (remainingMs <= 0L) {
                    return synchronized(companionStateLock) { isModeSatisfiedLocked() }
                }
                val awaited = runBlocking {
                    withTimeoutOrNull(remainingMs) { deferred.await() }
                }
                val satisfiedNow = synchronized(companionStateLock) { isModeSatisfiedLocked() }
                if (satisfiedNow) return true
                if (awaited == null) return false
                if (!awaited) {
                    val stillPending = synchronized(companionStateLock) {
                        isStartPending || (tunnelReadyDeferred?.isCompleted == false)
                    }
                    if (!stillPending) return false
                }
            }
        } catch (e: Exception) {
            if (e is InterruptedException) {
                Thread.currentThread().interrupt()
            }
            Timber.tag(TAG).w(e, "Interrupted/failed awaiting VPN tunnel readiness")
            return synchronized(companionStateLock) { isModeSatisfiedLocked() }
        }
    }

    fun isTunnelHealthy(expectedMode: VpnMode? = null): Boolean = synchronized(companionStateLock) {
        val svc = activeService?.get() ?: return false
        if (!isRunning || svc.isServiceDestroyed || svc.isServiceRevoked) return false
        if (expectedMode != null && currentMode != expectedMode) return false
        currentMode != VpnMode.DNS_FILTERING_ONLY || svc.dnsWorkerJob?.isActive == true
    }

    fun isServiceOrTunnelActive(): Boolean = synchronized(companionStateLock) {
        isRunning || isStartPending || activeService?.get() != null
    }

    @SuppressLint("NewApi") // Guarded by sdkInt < O at runtime while keeping sdkInt injectable for JVM unit tests
    fun canRunDnsVpnSilently(context: Context, sdkInt: Int = Build.VERSION.SDK_INT): Boolean {
        if (sdkInt < Build.VERSION_CODES.O) return false
        if (ProtectionTransitionGuard.isTeardownInProgress) return false
        if (!SafeDpmGateway.isDeviceOwner(context)) return false
        if (!isDeviceAdminServiceBoundApi26()) return false
        return ProtectionNotificationCoordinator.resolveCurrentState(context) is
            ProtectionNotificationCoordinator.ProtectionState.DnsFilteringActive
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun isDeviceAdminServiceBoundApi26(): Boolean =
        com.drmohammed.antiporn.HisnDeviceAdminService.isBound

    fun syncForegroundPresentation() {
        val service = synchronized(companionStateLock) { activeService?.get() }
        if (service != null && !service.isServiceDestroyed) {
            presentationScope.launch {
                if (!service.isServiceDestroyed) {
                    service.syncForegroundPresentation()
                }
            }
        }
    }

    fun getLiveStateSummary(): String = synchronized(companionStateLock) {
        if (!isRunning && !isStartPending && activeService?.get() == null) "INACTIVE" else "ACTIVE (${currentMode.name})"
    }

    internal fun isBlackoutActive(context: Context): Boolean =
        CurfewConfig.isInstantInternetKillEnabled(context) ||
            (CurfewEnforcer.isEffectiveBlockActive(context) && CurfewConfig.isVpnBlackholeEnabled(context))

    fun evaluateRequiredMode(context: Context, fallbackOnFailure: VpnMode = currentMode): VpnMode {
        if (isBlackoutActive(context)) {
            return VpnMode.CURFEW_BLACKHOLE
        }
        val isDnsFilterRequired = try {
            if (CurfewVpnDnsBridge.hasUnclearableHostilePrivateDns(context)) {
                false
            } else {
                DnsResilienceManager.ensureHydrated(context)
                val policy = PolicyRepository.getInstance(context).getCurrentPolicy()
                (policy.isFullSystemFilteringEnabled && policy.browserFilterMode != DevicePolicyBundle.BROWSER_FILTER_OFF) ||
                    policy.isAdBlockingEnabled ||
                    DnsResilienceManager.isLocalVpnFallbackActive() ||
                    CurfewVpnDnsBridge.isDoTLeakingAndUnaffiliatedBlocked(context)
            }
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Failed reading policy for VPN mode evaluation; preserving safe fallback=$fallbackOnFailure")
            return if (fallbackOnFailure != VpnMode.IDLE) fallbackOnFailure else VpnMode.IDLE
        }
        return if (isDnsFilterRequired) VpnMode.DNS_FILTERING_ONLY else VpnMode.IDLE
    }

    fun syncPolicy(context: Context) {
        if (ProtectionTransitionGuard.isTeardownInProgress) {
            Timber.tag(TAG).d("Skipping syncPolicy because teardown is in progress")
            return
        }
        val appContext = context.applicationContext ?: context
        try {
            val targetMode = evaluateRequiredMode(appContext)
            val runningService = synchronized(companionStateLock) {
                val service = activeService?.get()
                if (service != null && !service.isServiceDestroyed && targetMode != VpnMode.IDLE &&
                    (!isRunning || currentMode != targetMode)
                ) {
                    prepareTunnelWaiter()
                }
                service
            }
            if (runningService != null && !runningService.isServiceDestroyed) {
                val sent = runningService.commandChannel.trySend(VpnCommand.StartOrUpdate(forceRebuild = false))
                if (!sent.isSuccess && targetMode != VpnMode.IDLE) {
                    start(appContext)
                }
            } else if (targetMode != VpnMode.IDLE) {
                start(appContext)
            } else if (isServiceOrTunnelActive()) {
                stop(appContext)
            }
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Error syncing policy in CurfewBlackholeVpnService")
        }
    }

    fun forceReleaseVpnAndAwait(context: Context, timeoutMs: Long = 2000L): Boolean {
        if (!isServiceOrTunnelActive()) {
            Timber.tag(TAG).d("VPN already inactive; skipping forceReleaseVpnAndAwait")
            return true
        }
        Timber.tag(TAG).i("Force releasing VPN interface and awaiting tunnel teardown (timeout=${timeoutMs}ms)")
        val waiter = synchronized(companionStateLock) {
            prepareTunnelStoppedWaiter()
        }
        stop(context)
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return !isServiceOrTunnelActive()
        }
        return try {
            runBlocking {
                withTimeoutOrNull(timeoutMs) { waiter.await() } ?: !isServiceOrTunnelActive()
            }
        } catch (e: Exception) {
            if (e is InterruptedException) {
                Thread.currentThread().interrupt()
            }
            Timber.tag(TAG).w(e, "Interrupted/failed awaiting VPN tunnel release")
            !isServiceOrTunnelActive()
        }
    }

    fun start(context: Context) {
        if (ProtectionTransitionGuard.isTeardownInProgress) {
            Timber.tag(TAG).d("Skipping VPN start because Device Owner teardown is in progress")
            return
        }
        val appContext = context.applicationContext ?: context
        try {
            synchronized(companionStateLock) {
                isStartPending = true
                prepareTunnelWaiter()
            }
            val canRunSilently = canRunDnsVpnSilently(appContext)
            val intent = Intent(appContext, CurfewBlackholeVpnService::class.java).apply {
                action = CurfewBlackholeVpnService.ACTION_START
                putExtra(CurfewBlackholeVpnService.EXTRA_STARTED_AS_FGS, !canRunSilently)
            }
            if (canRunSilently) {
                try {
                    appContext.startService(intent)
                } catch (e: IllegalStateException) {
                    Timber.tag(TAG).w(e, "Silent startService rejected by OS/ROM; falling back to startForegroundService")
                    intent.putExtra(CurfewBlackholeVpnService.EXTRA_STARTED_AS_FGS, true)
                    intent.putExtra(CurfewBlackholeVpnService.EXTRA_FORCE_KEEP_FGS, true)
                    ContextCompat.startForegroundService(appContext, intent)
                }
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ContextCompat.startForegroundService(appContext, intent)
            } else {
                appContext.startService(intent)
            }
        } catch (e: Exception) {
            synchronized(companionStateLock) {
                isStartPending = false
            }
            completeTunnelWaiters(false)
            Timber.tag(TAG).e(e, "Failed to start CurfewBlackholeVpnService")
        }
    }

    fun forceStopForTeardown(context: Context) {
        val appContext = context.applicationContext ?: context
        val waiter = synchronized(companionStateLock) {
            isStartPending = false
            val service = activeService?.get()
            if (service != null && !service.isServiceDestroyed) prepareTunnelStoppedWaiter() else null
        }
        val runningService = synchronized(companionStateLock) {
            activeService?.get()
        }
        completeTunnelWaiters(false)
        if (runningService != null && !runningService.isServiceDestroyed) {
            runningService.isServiceRevoked = true
            runningService.commandChannel.trySend(VpnCommand.ForceTeardown)
        }
        try {
            appContext.stopService(Intent(appContext, CurfewBlackholeVpnService::class.java))
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Failed stopService during forceStopForTeardown")
        } finally {
            if (runningService == null || runningService.isServiceDestroyed) {
                synchronized(companionStateLock) {
                    activeService = null
                    isRunning = false
                    currentMode = VpnMode.IDLE
                }
                completeTunnelStoppedWaiters(true)
            }
        }
        if (waiter != null && Looper.myLooper() != Looper.getMainLooper()) {
            try {
                runBlocking {
                    withTimeoutOrNull(2000L) { waiter.await() }
                }
            } catch (e: Exception) {
                if (e is InterruptedException) {
                    Thread.currentThread().interrupt()
                }
                Timber.tag(TAG).w(e, "Interrupted awaiting VPN stop during teardown")
            }
        }
    }

    fun stop(context: Context) {
        val appContext = context.applicationContext ?: context
        if (isBlackoutActive(appContext)) {
            Timber.tag(TAG).w("Rejected CurfewBlackholeVpnService.stop() because curfew blackout is actively enforced")
            return
        }
        val runningService = synchronized(companionStateLock) {
            isStartPending = false
            val service = activeService?.get()
            if (service == null || service.isServiceDestroyed) {
                activeService = null
                isRunning = false
                currentMode = VpnMode.IDLE
            }
            service
        }
        completeTunnelWaiters(false)
        if (runningService == null || runningService.isServiceDestroyed) {
            try {
                appContext.stopService(Intent(appContext, CurfewBlackholeVpnService::class.java))
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Failed to stop CurfewBlackholeVpnService")
            } finally {
                completeTunnelStoppedWaiters(true)
            }
            return
        }
        val targetStartId = runningService.lastStartId
        val sent = try {
            runningService.commandChannel.trySend(VpnCommand.StopIfAllowed(startId = targetStartId)).isSuccess
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Error signaling active VPN service stop")
            false
        }
        if (!sent) {
            Timber.tag(TAG).w("Failed enqueuing StopIfAllowed; falling back to stopService")
            synchronized(companionStateLock) {
                if (activeService?.get() === runningService) {
                    activeService = null
                    isRunning = false
                    currentMode = VpnMode.IDLE
                }
            }
            try {
                appContext.stopService(Intent(appContext, CurfewBlackholeVpnService::class.java))
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Failed to stop CurfewBlackholeVpnService")
            } finally {
                completeTunnelStoppedWaiters(true)
            }
        }
    }
}
