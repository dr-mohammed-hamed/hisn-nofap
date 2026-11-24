package com.drmohammed.antiporn.watchdog

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.drmohammed.antiporn.PackageChangeHandler
import com.drmohammed.antiporn.cloud.CloudSyncManager
import com.drmohammed.antiporn.core.network.dns.DnsResilienceManager
import com.drmohammed.antiporn.core.time.TrustedTimeProvider
import com.drmohammed.antiporn.curfew.CurfewConfig
import com.drmohammed.antiporn.data.repository.AccountabilityRepository
import com.drmohammed.antiporn.data.repository.ApprovalRepository
import com.drmohammed.antiporn.data.repository.PolicyRepository
import com.drmohammed.antiporn.ota.repository.OtaRepository
import com.drmohammed.antiporn.services.NetworkWatchdog
import com.drmohammed.antiporn.waqfa.engine.WaqfaForegroundWatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Manages [NetworkWatchdog], dynamic package/screen receivers, and the 15-minute
 * periodic heartbeat for [ProtectionWatchdogRuntime].
 */
internal class WatchdogSystemReceivers(
    private val driftTracker: ProtectionWatchdogRuntime.ExternalDnsDriftTracker,
    private val isActiveGeneration: (Long) -> Boolean,
    private val scheduleCheck: (Long) -> Unit,
    private val enqueueImmediateCheck: () -> Unit,
    private val postDebouncedLocked: (Long, () -> Unit) -> Unit
) {

    companion object {
        private const val TAG = "AntiPorn_WatchReceivers"
        private const val SCREEN_DEBOUNCE_DELAY_MS = 500L
        private const val HEARTBEAT_INTERVAL_MS = 15 * 60_000L
        private const val CLOUD_HEARTBEAT_INTERVAL_MS = 24 * 3600 * 1000L
        private const val OTA_UPDATE_CHECK_INTERVAL_MS = 12 * 3600 * 1000L
    }

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

    fun registerAll(
        appContext: Context,
        scope: CoroutineScope,
        heartbeatHandler: Handler,
        gen: Long
    ) {
        registerNetworkWatchdog(appContext, scope)
        registerPackageReceiver(appContext)
        registerScreenReceiver(appContext, gen)
        startHeartbeat(appContext, scope, heartbeatHandler, gen)
    }

    fun unregisterAll(appContext: Context, heartbeatHandler: Handler?) {
        unregisterNetworkWatchdog()
        unregisterPackageReceiver(appContext)
        unregisterScreenReceiver(appContext)
        heartbeatHandler?.removeCallbacksAndMessages(null)
        Timber.tag(TAG).i("Layer 3 - Periodic heartbeat cancelled")
    }

    private fun registerNetworkWatchdog(appContext: Context, scope: CoroutineScope) {
        if (isNetworkRegistered) return
        networkWatchdog.register(
            context = appContext,
            onNetworkChanged = null,
            onDetailedNetworkTransition = { reason, network, transport ->
                if (reason.startsWith("net_validated")) {
                    driftTracker.reset()
                }
                DnsResilienceManager.onNetworkChanged(appContext, reason, network, transport)
            }
        ) {
            val isPendingDpmSync = CurfewConfig.isPendingDpmPrivateDnsSync(appContext)
            val delayMs = if (isPendingDpmSync) 250L else 1000L
            Timber.tag(TAG).i("Network became available! Debouncing protection check (${delayMs}ms, pendingDpmSync=$isPendingDpmSync)...")
            scheduleCheck(delayMs)
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
                        PackageChangeHandler.handlePackageAdded(appContext, packageName, isReplacing, "Dynamic_Watchdog")
                    }
                    Intent.ACTION_PACKAGE_REMOVED -> {
                        val isReplacing = intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)
                        if (isReplacing) {
                            Timber.tag(TAG).i("[DYNAMIC_WATCHDOG_RECEIVER] Package $packageName is being replaced; ignoring removal.")
                            return
                        }
                        PackageChangeHandler.handlePackageRemoved(appContext, packageName, isReplacing, "Dynamic_Watchdog")
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
            Timber.tag(TAG).i("Layer 1 - Dynamic package BroadcastReceiver registered (RECEIVER_EXPORTED)")
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
                val action = intent?.action ?: return
                if (action == Intent.ACTION_USER_PRESENT || action == Intent.ACTION_SCREEN_ON) {
                    if (!isActiveGeneration(gen)) return
                    Timber.tag(TAG).d("Layer 2 - Screen event observed ($action). Debouncing scan...")
                    postDebouncedLocked(SCREEN_DEBOUNCE_DELAY_MS) {
                        if (isActiveGeneration(gen)) {
                            ApprovalRepository.getInstance(appContext).syncZeroTrustBaselineAndReconcileAsync()
                            enqueueImmediateCheck()
                        }
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
            Timber.tag(TAG).i("Layer 2 - Screen / User Present BroadcastReceiver registered")
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

    private fun startHeartbeat(
        appContext: Context,
        scope: CoroutineScope,
        handler: Handler,
        gen: Long
    ) {
        val runnable = object : Runnable {
            override fun run() {
                if (!isActiveGeneration(gen)) return
                Timber.tag(TAG).d("Layer 3 - Periodic heartbeat (15m): verifying shields")
                enqueueImmediateCheck()
                TrustedTimeProvider.syncNowAsync(appContext)

                val nowRealtime = SystemClock.elapsedRealtime()
                val lastCloud = lastCloudHeartbeatElapsed.get()
                if (CloudSyncManager.isCloudSyncEnabled(appContext)) {
                    val isSupervised = PolicyRepository.getInstance(appContext).getCurrentPolicy().isSupervised
                    if (isSupervised && (nowRealtime - lastCloud >= CLOUD_HEARTBEAT_INTERVAL_MS || lastCloud == 0L)) {
                        scope.launch {
                            try {
                                if (AccountabilityRepository.getInstance(appContext).updateHeartbeat().isSuccess) {
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
                            OtaRepository.getInstance(appContext).checkForUpdatesAndAutoInstall(appContext)
                            lastOtaCheckElapsed.set(SystemClock.elapsedRealtime())
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            Timber.tag(TAG).w(e, "Watchdog periodic OTA update error")
                        }
                    }
                }

                if (isActiveGeneration(gen)) {
                    handler.postDelayed(this, HEARTBEAT_INTERVAL_MS)
                }
            }
        }
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed(runnable, HEARTBEAT_INTERVAL_MS)
        Timber.tag(TAG).i("Layer 3 - Periodic heartbeat scheduled (every ${HEARTBEAT_INTERVAL_MS / 1000}s)")
    }
}
