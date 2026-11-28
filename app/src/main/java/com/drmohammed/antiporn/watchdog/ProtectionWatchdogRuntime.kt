package com.drmohammed.antiporn.watchdog

import android.content.Context
import android.net.Network
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.annotation.VisibleForTesting
import com.drmohammed.antiporn.core.network.dns.DnsResilienceManager
import com.drmohammed.antiporn.curfew.DnsFastHealResult
import com.drmohammed.antiporn.curfew.ZeroTrustForegroundWatcher
import com.drmohammed.antiporn.data.repository.ApprovalRepository
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

/**
 * Thread-safe, idempotent, host-agnostic runtime controller for zero-trust protection watchdog observation.
 * Coordinates [PrivateDnsDriftWatcher], [WatchdogSystemReceivers], and [ProtectionAuditEngine].
 */
object ProtectionWatchdogRuntime {

    private const val TAG = "AntiPorn_WatchdogRuntime"

    enum class WatchdogHost {
        NONE,
        DEVICE_ADMIN_SERVICE,
        LEGACY_BG_SERVICE,
        FGS_FALLBACK
    }

    @VisibleForTesting
    internal class ExternalDnsDriftTracker(
        private val windowMs: Long = 60_000L,
        private val maxEntries: Int = 16,
        private val clockMs: () -> Long = {
            try {
                SystemClock.elapsedRealtime()
            } catch (_: RuntimeException) {
                System.currentTimeMillis()
            }
        }
    ) {
        private val driftLock = Any()
        private val timestamps = ArrayDeque<Long>()

        fun recordDrift(nowMs: Long = clockMs()): Int = synchronized(driftLock) {
            val cutoff = nowMs - windowMs
            while (timestamps.isNotEmpty() && timestamps.first() < cutoff) {
                timestamps.removeFirst()
            }
            timestamps.addLast(nowMs)
            while (timestamps.size > maxEntries) {
                timestamps.removeFirst()
            }
            timestamps.size
        }

        fun reset() = synchronized(driftLock) {
            timestamps.clear()
        }
    }

    private val lock = Any()

    @Volatile
    var isRunning: Boolean = false
        private set

    @Volatile
    var activeHost: WatchdogHost = WatchdogHost.NONE
        private set

    private var generationId: Long = 0L
    private var runtimeScope: CoroutineScope? = null
    private var protectionCheckChannel: Channel<Unit>? = null
    private var appContextRef: Context? = null
    private var debounceHandler: Handler? = null
    private var heartbeatHandler: Handler? = null

    internal val externalDnsDriftTracker = ExternalDnsDriftTracker()

    private val driftWatcher = PrivateDnsDriftWatcher(
        driftTracker = externalDnsDriftTracker,
        isActiveGeneration = { gen -> synchronized(lock) { isRunning && gen == generationId } },
        scheduleDebouncedAudit = { delayMs ->
            synchronized(lock) {
                if (isRunning) scheduleDebouncedCheckLocked(delayMs)
            }
        }
    )

    private val systemReceivers = WatchdogSystemReceivers(
        driftTracker = externalDnsDriftTracker,
        isActiveGeneration = { gen -> synchronized(lock) { isRunning && gen == generationId } },
        scheduleCheck = { delayMs -> scheduleCheck(delayMs) },
        enqueueImmediateCheck = { protectionCheckChannel?.trySend(Unit) },
        postDebouncedLocked = { delayMs, block ->
            synchronized(lock) {
                val handler = debounceHandler ?: return@synchronized
                handler.removeCallbacksAndMessages(null)
                handler.postDelayed(block, delayMs)
            }
        }
    )

    fun attach(context: Context, host: WatchdogHost) {
        synchronized(lock) {
            val appCtx = context.applicationContext
            if (isRunning) {
                if (activeHost == host) {
                    Timber.tag(TAG).d("Watchdog runtime already running on host $host; ignoring redundant attach.")
                    return
                }
                if (host == WatchdogHost.DEVICE_ADMIN_SERVICE) {
                    Timber.tag(TAG).i("Upgrading active watchdog host from $activeHost to $host.")
                    activeHost = host
                    return
                }
                if (activeHost == WatchdogHost.DEVICE_ADMIN_SERVICE && host == WatchdogHost.FGS_FALLBACK) {
                    Timber.tag(TAG).d("DeviceAdminService is already primary host; ignoring fallback attach request.")
                    return
                }
            }
            activeHost = host
            startRuntimeLocked(appCtx)
        }
    }

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

    fun forceStop() {
        synchronized(lock) {
            stopRuntimeLocked()
            activeHost = WatchdogHost.NONE
        }
    }

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

    @VisibleForTesting
    internal fun evaluateExternalDriftAndDispatch(
        result: DnsFastHealResult,
        activeNet: Network?,
        transport: String?,
        isValidated: Boolean,
        recordTelemetry: (actionName: String, isSuccess: Boolean, details: String) -> Unit,
        escalateUnvalidated: (Network, String, Int) -> Boolean,
        triggerDpmFastAsync: () -> Unit
    ) {
        driftWatcher.evaluateExternalDriftAndDispatch(
            result = result,
            activeNet = activeNet,
            transport = transport,
            isValidated = isValidated,
            recordTelemetry = recordTelemetry,
            escalateUnvalidated = escalateUnvalidated,
            triggerDpmFastAsync = triggerDpmFastAsync
        )
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
        debounceHandler = Handler(Looper.getMainLooper())
        val heartbeater = Handler(Looper.getMainLooper())
        heartbeatHandler = heartbeater

        scope.launch {
            try {
                DnsResilienceManager.reconcilePersistedStateOnStartup(appContext)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.tag(TAG).w(e, "Error reconciling persisted DNS resilience state on startup")
            }
            for (trigger in channel) {
                if (!isActive || currentGen != generationId) break
                try {
                    ProtectionAuditEngine.runAudit(appContext) { delayMs -> scheduleCheck(delayMs) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Timber.tag(TAG).e(e, "Error during serialized protection enforcement check")
                }
            }
        }

        driftWatcher.register(appContext, currentGen)
        systemReceivers.registerAll(appContext, scope, heartbeater, currentGen)
        isRunning = true

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
            systemReceivers.unregisterAll(appContext, heartbeatHandler)
            driftWatcher.unregister(appContext)
            try {
                WaqfaForegroundWatcher.getInstance(appContext).stop()
            } catch (e: Exception) {
                Timber.tag(TAG).w(e, "Error stopping WaqfaForegroundWatcher")
            }
        } else {
            heartbeatHandler?.removeCallbacksAndMessages(null)
        }

        externalDnsDriftTracker.reset()
        debounceHandler?.removeCallbacksAndMessages(null)
        debounceHandler = null
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
}
