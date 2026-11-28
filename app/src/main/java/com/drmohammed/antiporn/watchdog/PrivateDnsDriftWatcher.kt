package com.drmohammed.antiporn.watchdog

import android.content.Context
import android.database.ContentObserver
import android.net.Network
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.provider.Settings
import com.drmohammed.antiporn.core.engine.ProtectionEngine
import com.drmohammed.antiporn.core.engine.ProtectionTransitionGuard
import com.drmohammed.antiporn.core.network.PhysicalNetworkResolver
import com.drmohammed.antiporn.core.network.dns.DnsResilienceManager
import com.drmohammed.antiporn.curfew.CurfewVpnDnsBridge
import com.drmohammed.antiporn.curfew.DnsFastHealOutcome
import com.drmohammed.antiporn.curfew.DnsFastHealResult
import com.drmohammed.antiporn.telemetry.BlackBoxRecorder
import timber.log.Timber

/**
 * Monitors `Settings.Global` Private DNS changes on a high-priority [HandlerThread],
 * performs 1-3ms fast-healing, and dispatches unvalidated external drift to [DnsResilienceManager].
 */
internal class PrivateDnsDriftWatcher(
    private val driftTracker: ProtectionWatchdogRuntime.ExternalDnsDriftTracker,
    private val isActiveGeneration: (Long) -> Boolean,
    private val scheduleDebouncedAudit: (Long) -> Unit
) {

    companion object {
        private const val TAG = "AntiPorn_DnsDriftWatch"
        private const val DEBOUNCE_DELAY_MS = 500L
    }

    private var dnsObserverThread: HandlerThread? = null
    private var dnsObserver: ContentObserver? = null

    fun register(appContext: Context, gen: Long) {
        if (dnsObserver != null) return

        val observerThread = HandlerThread(
            "AntiPorn_DnsObserver",
            Process.THREAD_PRIORITY_URGENT_DISPLAY
        ).apply { start() }

        val observer = object : ContentObserver(Handler(observerThread.looper)) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                if (!isActiveGeneration(gen)) return
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
            if (modeUri != null) appContext.contentResolver.registerContentObserver(modeUri, false, observer)
            if (specifierUri != null) appContext.contentResolver.registerContentObserver(specifierUri, false, observer)
            dnsObserverThread = observerThread
            dnsObserver = observer
            Timber.tag(TAG).i("ContentObserver registered for DNS settings on dedicated HandlerThread")
        } catch (e: Exception) {
            try {
                appContext.contentResolver.unregisterContentObserver(observer)
            } catch (cleanupErr: Exception) {
                Timber.tag(TAG).d(cleanupErr, "Error unregistering observer during cleanup")
            }
            observerThread.quitSafely()
            dnsObserver = null
            dnsObserverThread = null
            Timber.tag(TAG).e(e, "Failed to register ContentObserver for DNS settings")
        }
    }

    fun unregister(appContext: Context) {
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

    fun evaluateExternalDriftAndDispatch(
        result: DnsFastHealResult,
        activeNet: Network?,
        transport: String?,
        isValidated: Boolean,
        recordTelemetry: (actionName: String, isSuccess: Boolean, details: String) -> Unit,
        escalateUnvalidated: (Network, String, Int) -> Boolean,
        triggerDpmFastAsync: () -> Unit
    ) {
        if (activeNet != null &&
            !isValidated &&
            transport != null &&
            transport in DnsResilienceManager.SUPPORTED_TRANSPORTS
        ) {
            val driftCount60s = driftTracker.recordDrift()
            val escalated = escalateUnvalidated(activeNet, transport, driftCount60s)
            val netId = DnsResilienceManager.resolveNetworkId(activeNet)
            recordTelemetry(
                "PRIVATE_DNS_EXTERNAL_DRIFT",
                false,
                "wasMode=${result.observedMode}, wasHost=${result.observedHost}, transport=$transport, net=$netId, validated=false, driftCount60s=$driftCount60s, escalated=$escalated"
            )
            // On 1st drift with HEALED_DIRECT, fast-heal already restored Settings.Global;
            // on 1st drift needing DPM, wait only if fast-track is not overriding;
            // on 2nd+ drift within 60s, suppress competing blocking DPM.
            val shouldRunDpmFast = !escalated && result.outcome != DnsFastHealOutcome.HEALED_DIRECT
            if (shouldRunDpmFast) {
                triggerDpmFastAsync()
            }
        } else {
            triggerDpmFastAsync()
        }
    }

    private fun handleImmediateDnsChange(appContext: Context, uri: Uri?, gen: Long) {
        if (!isActiveGeneration(gen)) return
        try {
            val result = CurfewVpnDnsBridge.tryFastHealGlobalPrivateDns(appContext)
            when (result.outcome) {
                DnsFastHealOutcome.ALREADY_COMPLIANT -> return
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
                    dispatchExternalDrift(appContext, result)
                }
                DnsFastHealOutcome.PERMISSION_DENIED_NEEDS_DPM,
                DnsFastHealOutcome.FAILED_NEEDS_DPM -> {
                    Timber.tag(TAG).w(
                        "DNS fast-path direct write unavailable (${result.outcome}); escalating to DPM/resilience (uri=$uri)"
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
                    dispatchExternalDrift(appContext, result)
                }
                DnsFastHealOutcome.SKIPPED_ACTIVE_TRANSITION,
                DnsFastHealOutcome.SKIPPED_VPN_EXPECTED -> {
                    val delayMs = if (ProtectionTransitionGuard.isWithinSettleWindow()) 3500L else DEBOUNCE_DELAY_MS
                    Timber.tag(TAG).d("DNS change deferred (${result.outcome}, uri=$uri). Scheduling audit in ${delayMs}ms...")
                    scheduleDebouncedAudit(delayMs)
                }
                DnsFastHealOutcome.SKIPPED_UNSUPPORTED_SDK,
                DnsFastHealOutcome.SKIPPED_NOT_DEVICE_OWNER -> return
            }
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Error handling immediate DNS change (uri=$uri)")
        }
    }

    private fun dispatchExternalDrift(appContext: Context, result: DnsFastHealResult) {
        val state = PhysicalNetworkResolver.resolveActivePhysicalNetworkState(appContext)
        evaluateExternalDriftAndDispatch(
            result = result,
            activeNet = state.network,
            transport = state.transport,
            isValidated = state.isValidated,
            recordTelemetry = { action, ok, details ->
                BlackBoxRecorder.recordNetwork(appContext, action, isSuccess = ok, details = details)
            },
            escalateUnvalidated = { net, tr, count ->
                DnsResilienceManager.onExternalDnsResetWhileUnvalidated(appContext, net, tr, count)
            },
            triggerDpmFastAsync = { ProtectionEngine.applyDnsShieldFastAsync(appContext) }
        )
    }
}
