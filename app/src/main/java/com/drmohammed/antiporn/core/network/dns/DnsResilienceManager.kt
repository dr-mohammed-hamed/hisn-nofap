package com.drmohammed.antiporn.core.network.dns

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import androidx.annotation.VisibleForTesting
import com.drmohammed.antiporn.core.engine.ProtectionTransitionGuard
import com.drmohammed.antiporn.curfew.CurfewVpnDnsBridge
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber
import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicLong

/**
 * Intelligent 3-tier Transport-Agnostic DNS Resilience Engine (Wi-Fi, Cellular, Ethernet).
 * Delegates persistence to [DnsResilienceStore], probes to [DnsResilienceProbeClient],
 * routing/debouncing to [DnsResilienceNetworkRouter], and evaluation to [DnsResilienceEvaluationRunner].
 */
object DnsResilienceManager {

    private const val TAG = "AntiPorn_DnsResilience"
    private const val PROBE_COOLDOWN_MS = 15_000L
    private const val EXTERNAL_DRIFT_FAST_TRACK_DELAY_MS = 1_500L

    enum class DnsResilienceMode {
        PRIMARY_STRICT_DOT,
        FALLBACK_STRICT_DOT,
        FALLBACK_LOCAL_VPN_DOH
    }

    enum class FailoverTriggerClass {
        PORT_853_BLOCKED,
        OS_DOT_VALIDATION_QUIRK,
        CAPTIVE_PORTAL
    }

    data class ResilienceRuntimeSnapshot(
        val mode: DnsResilienceMode,
        val overrideDotHost: String?,
        val protectedNetworkId: String?,
        val protectedTransport: String?,
        val triggerClass: FailoverTriggerClass?,
        val isRestoringPrimary: Boolean
    )

    internal val SUPPORTED_TRANSPORTS = setOf("WIFI", "CELLULAR", "ETHERNET")

    private val resilienceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val evaluationMutex = Mutex()
    private val networkRouter = DnsResilienceNetworkRouter(
        scope = resilienceScope,
        cooldownMs = PROBE_COOLDOWN_MS,
        driftFastTrackDelayMs = EXTERNAL_DRIFT_FAST_TRACK_DELAY_MS
    )

    @VisibleForTesting internal var curfewOrVpnActiveChecker: ((Context) -> Boolean)? = null
    @VisibleForTesting internal var isWifiValidatedChecker: ((Network) -> Boolean)? = null
    @VisibleForTesting internal var isWifiCaptivePortalChecker: ((Network) -> Boolean)? = null
    @VisibleForTesting internal var internetProbeOverride: ((Network) -> Boolean)? = null
    @VisibleForTesting internal var dotProbeOverride: ((Network, String) -> Boolean)? = null
    @VisibleForTesting internal var delayOverride: (suspend (Long) -> Unit)? = null
    @VisibleForTesting internal var isDeviceOwnerChecker: ((Context) -> Boolean)? = null
    @VisibleForTesting internal var binaryFilterInitOverride: ((Context) -> Boolean)? = null
    @VisibleForTesting internal var level3ActivationOverride: ((Context, String) -> Boolean)? = null
    @VisibleForTesting internal var applyDnsShieldOverride: ((Context) -> Unit)? = null
    @VisibleForTesting internal var policyHostProvider: ((Context) -> String)? = null
    @VisibleForTesting internal var isNetworkValidatedChecker: ((Network) -> Boolean)? = null
    @VisibleForTesting internal var isNetworkCaptivePortalChecker: ((Network) -> Boolean)? = null
    @VisibleForTesting internal var activeNetworkProvider: ((Context) -> Network?)? = null
    @VisibleForTesting internal var physicalTransportProvider: ((Context, Network) -> String?)? = null
    @VisibleForTesting internal var allPhysicalNetworksProvider: ((Context) -> List<Network>)? = null
    @VisibleForTesting internal var localVpnFallbackEnabledChecker: ((Context) -> Boolean)? = null
    @VisibleForTesting internal var consentNotifierOverride: ((Context, String) -> Unit)? = null
    @VisibleForTesting internal var dotProbeDetailedOverride: ((Network, String, Boolean) -> DnsResilienceProbeClient.DotProbeResult)? = null

    @VisibleForTesting
    internal var recoveryDelayOverride: (suspend (Long) -> Unit)?
        get() = DnsResilienceNetworkRouter.recoveryDelayOverride
        set(value) {
            DnsResilienceNetworkRouter.recoveryDelayOverride = value
        }

    private val stateLock = Any()
    private val stateEpoch = AtomicLong(0L)

    @Volatile private var activeMode: DnsResilienceMode = DnsResilienceMode.PRIMARY_STRICT_DOT
    @Volatile private var overrideDotHost: String? = null
    @Volatile private var protectedNetworkId: String? = null
    @Volatile private var protectedTransport: String? = null
    @Volatile private var failoverTriggerClass: FailoverTriggerClass? = null
    @Volatile private var isRestoringPrimary: Boolean = false
    @Volatile private var isHydrated: Boolean = false
    @Volatile private var lastProbeElapsedMs: Long = -PROBE_COOLDOWN_MS
    @Volatile private var lastProbedNetworkId: String? = null

    private val evalCallbacks = object : DnsResilienceEvaluationRunner.HostCallbacks {
        override fun currentEpoch(): Long = stateEpoch.get()
        override fun getSnapshot(): ResilienceRuntimeSnapshot = getRuntimeSnapshot()
        override fun isInCooldown(netId: String, nowMs: Long): Boolean = synchronized(stateLock) {
            (nowMs - lastProbeElapsedMs) < PROBE_COOLDOWN_MS &&
                (protectedNetworkId == netId || lastProbedNetworkId == netId)
        }
        override fun shouldSkipDueToExhaustedConsent(context: Context, netId: String, nowMs: Long): Boolean =
            this@DnsResilienceManager.shouldSkipEvaluationDueToExhaustedConsent(netId, nowMs, context)
        override fun markProbeAttempt(netId: String, nowMs: Long) {
            synchronized(stateLock) {
                lastProbeElapsedMs = nowMs
                lastProbedNetworkId = netId
            }
        }
        override fun activateLevel2(context: Context, overrideHost: String, netId: String, transport: String) {
            val snap = synchronized(stateLock) {
                activeMode = DnsResilienceMode.FALLBACK_STRICT_DOT
                overrideDotHost = overrideHost
                protectedNetworkId = netId
                protectedTransport = transport
                failoverTriggerClass = FailoverTriggerClass.PORT_853_BLOCKED
                isRestoringPrimary = false
                isHydrated = true
                ResilienceRuntimeSnapshot(activeMode, overrideDotHost, protectedNetworkId, protectedTransport, failoverTriggerClass, false)
            }
            DnsResilienceStore.persistSnapshot(context, snap)
        }
        override fun clearLevel2StateWithoutDpm(context: Context) {
            synchronized(stateLock) {
                activeMode = DnsResilienceMode.PRIMARY_STRICT_DOT
                overrideDotHost = null
                protectedNetworkId = null
                protectedTransport = null
                failoverTriggerClass = null
                isRestoringPrimary = false
                DnsResilienceNetworkRouter.cancelRecoveryJobs()
            }
            DnsResilienceStore.clearPersistedState(context)
        }
        override fun activateLevel3(context: Context, netId: String, transport: String, triggerClass: FailoverTriggerClass) {
            val snap = synchronized(stateLock) {
                activeMode = DnsResilienceMode.FALLBACK_LOCAL_VPN_DOH
                overrideDotHost = null
                protectedNetworkId = netId
                protectedTransport = transport
                failoverTriggerClass = triggerClass
                isRestoringPrimary = false
                isHydrated = true
                ResilienceRuntimeSnapshot(activeMode, overrideDotHost, protectedNetworkId, protectedTransport, failoverTriggerClass, false)
            }
            DnsResilienceStore.persistSnapshot(context, snap)
        }
        override fun scheduleSameNetworkPrimaryRecovery(
            context: Context,
            network: Network,
            netId: String,
            targetPolicyHost: String,
            triggerReason: String
        ) {
            this@DnsResilienceManager.scheduleSameNetworkPrimaryRecovery(context, network, netId, targetPolicyHost, triggerReason)
        }
        override fun rollbackToPrimarySync(context: Context, netId: String, transport: String, reason: String) {
            this@DnsResilienceManager.rollbackToPrimarySync(context, netId, transport, reason)
        }
        override fun markExhaustedDotAndNotify(
            context: Context,
            netId: String,
            transport: String,
            triggerClass: FailoverTriggerClass,
            restorePrimaryDpm: Boolean
        ) {
            this@DnsResilienceManager.markExhaustedDotAndNotify(context, netId, restorePrimaryDpm)
        }
        override fun applyDnsShieldSafely(context: Context): Boolean =
            this@DnsResilienceManager.applyDnsShieldSafely(context)
    }

    fun getActiveMode(): DnsResilienceMode = activeMode
    fun isLocalVpnFallbackActive(): Boolean = activeMode == DnsResilienceMode.FALLBACK_LOCAL_VPN_DOH && !isRestoringPrimary
    fun isRestoringPrimaryNow(): Boolean = isRestoringPrimary
    fun isDohFastPathOrTeardownActive(): Boolean = activeMode == DnsResilienceMode.FALLBACK_LOCAL_VPN_DOH || isRestoringPrimary
    fun isEvaluationOrDohFallbackActive(): Boolean =
        evaluationMutex.isLocked || activeMode == DnsResilienceMode.FALLBACK_LOCAL_VPN_DOH || isRestoringPrimary
    fun isEvaluationOrFallbackActive(): Boolean =
        evaluationMutex.isLocked || activeMode != DnsResilienceMode.PRIMARY_STRICT_DOT || isRestoringPrimary

    fun getEffectiveRuntimeDnsHost(policyHost: String): String {
        val host = overrideDotHost
        return if (activeMode == DnsResilienceMode.FALLBACK_STRICT_DOT && !host.isNullOrBlank()) host else policyHost
    }

    fun getProtectedNetworkId(): String? = protectedNetworkId
    fun getProtectedTransport(): String? = protectedTransport
    fun getFailoverTriggerClass(): FailoverTriggerClass? = failoverTriggerClass
    @Deprecated("Use getProtectedNetworkId()", ReplaceWith("getProtectedNetworkId()"))
    fun getProtectedWifiNetworkId(): String? = protectedNetworkId

    fun getRuntimeSnapshot(): ResilienceRuntimeSnapshot = synchronized(stateLock) {
        ResilienceRuntimeSnapshot(activeMode, overrideDotHost, protectedNetworkId, protectedTransport, failoverTriggerClass, isRestoringPrimary)
    }

    @VisibleForTesting
    internal fun setStateForTest(
        mode: DnsResilienceMode,
        overrideHost: String? = null,
        netId: String? = null,
        transport: String? = null,
        triggerClass: FailoverTriggerClass? = null,
        restoringPrimary: Boolean = false
    ) {
        synchronized(stateLock) {
            stateEpoch.incrementAndGet()
            activeMode = mode
            overrideDotHost = overrideHost
            protectedNetworkId = netId
            protectedTransport = transport
            failoverTriggerClass = triggerClass
            isRestoringPrimary = restoringPrimary
            isHydrated = true
        }
    }

    @VisibleForTesting
    internal fun setFallbackStateForTest(
        mode: DnsResilienceMode,
        netId: String?,
        isCaptivePortal: Boolean = false
    ) {
        setStateForTest(
            mode = mode,
            netId = netId,
            transport = "WIFI",
            triggerClass = if (isCaptivePortal) FailoverTriggerClass.CAPTIVE_PORTAL else FailoverTriggerClass.PORT_853_BLOCKED
        )
    }

    internal fun resolveNetworkId(network: Network): String = try {
        network.toString()
    } catch (_: RuntimeException) {
        "net@${System.identityHashCode(network)}"
    }

    fun ensureHydrated(context: Context) {
        if (isHydrated) return
        val loaded = DnsResilienceStore.loadSnapshot(context)
        synchronized(stateLock) {
            if (!isHydrated) {
                if (loaded != null && activeMode == DnsResilienceMode.PRIMARY_STRICT_DOT && protectedNetworkId == null) {
                    activeMode = loaded.mode
                    overrideDotHost = loaded.overrideDotHost
                    protectedNetworkId = loaded.protectedNetworkId
                    protectedTransport = loaded.protectedTransport
                    failoverTriggerClass = loaded.triggerClass
                }
                isHydrated = true
            }
        }
    }

    suspend fun reconcilePersistedStateOnStartup(context: Context) {
        ensureHydrated(context)
        if (getRuntimeSnapshot().mode == DnsResilienceMode.PRIMARY_STRICT_DOT) return
        evaluationMutex.withLock {
            val current = getRuntimeSnapshot()
            if (current.mode == DnsResilienceMode.PRIMARY_STRICT_DOT) return@withLock
            DnsResilienceStore.reconcileStartup(context, current) { reason ->
                if (beginRestorePrimaryTransition(context, reason)) {
                    restorePrimaryStrictDotLockedAsyncBody(context, reason)
                }
            }
        }
    }

    fun resetStateSync(context: Context? = null) {
        synchronized(stateLock) {
            Timber.tag(TAG).i("Resetting resilience state to PRIMARY_STRICT_DOT synchronously")
            stateEpoch.incrementAndGet()
            activeMode = DnsResilienceMode.PRIMARY_STRICT_DOT
            overrideDotHost = null
            protectedNetworkId = null
            protectedTransport = null
            failoverTriggerClass = null
            isRestoringPrimary = false
            isHydrated = true
            lastProbeElapsedMs = -PROBE_COOLDOWN_MS
            lastProbedNetworkId = null
            networkRouter.cancelAll()
            curfewOrVpnActiveChecker = null
            isWifiValidatedChecker = null
            isWifiCaptivePortalChecker = null
            isNetworkValidatedChecker = null
            isNetworkCaptivePortalChecker = null
            activeNetworkProvider = null
            physicalTransportProvider = null
            allPhysicalNetworksProvider = null
            internetProbeOverride = null
            dotProbeOverride = null
            delayOverride = null
            isDeviceOwnerChecker = null
            binaryFilterInitOverride = null
            level3ActivationOverride = null
            applyDnsShieldOverride = null
            policyHostProvider = null
            localVpnFallbackEnabledChecker = null
            consentNotifierOverride = null
            dotProbeDetailedOverride = null
            if (context != null) {
                DnsResilienceStore.clearPersistedState(context)
                DnsFallbackConsentNotifier.cancelNotification(context)
            }
        }
    }

    fun onPolicyDnsHostChanged(context: Context) {
        resetStateSync(context)
        resilienceScope.launch {
            try {
                CurfewVpnDnsBridge.reconcileNonBlackholeVpnAndDns(context)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.tag(TAG).w(e, "Error reconciling VPN after policy host change")
            }
        }
    }

    internal fun getElapsedRealtime(): Long = networkRouter.getElapsedRealtime()

    internal fun getConnectivityManager(context: Context): ConnectivityManager? =
        networkRouter.getConnectivityManager(context)

    internal fun resolvePhysicalTransportLabel(context: Context, cm: ConnectivityManager?, network: Network): String? =
        networkRouter.resolvePhysicalTransportLabel(context, cm, network)

    internal fun getAllPhysicalNetworks(context: Context, cm: ConnectivityManager?): List<Network> =
        networkRouter.getAllPhysicalNetworks(context, cm)

    internal fun isNetworkValidated(cm: ConnectivityManager?, network: Network): Boolean =
        networkRouter.isNetworkValidated(cm, network)

    internal fun isNetworkCaptivePortal(cm: ConnectivityManager?, network: Network): Boolean =
        networkRouter.isNetworkCaptivePortal(cm, network)

    internal fun isTargetNetworkPrimaryOrSolePhysical(context: Context, targetNetwork: Network): Boolean =
        networkRouter.isTargetNetworkPrimaryOrSolePhysical(context, targetNetwork)

    fun onDpmHostNotServing(context: Context, failedHost: String) {
        ensureHydrated(context)
        val (probeElapsedMs, probedNetId) = synchronized(stateLock) {
            lastProbeElapsedMs to lastProbedNetworkId
        }
        networkRouter.onDpmHostNotServing(context, failedHost, probeElapsedMs, probedNetId)
    }

    fun onExternalDnsResetWhileUnvalidated(context: Context, network: Network, transport: String, driftCount60s: Int): Boolean {
        ensureHydrated(context)
        return networkRouter.onExternalDnsResetWhileUnvalidated(context, network, transport, driftCount60s) {
            synchronized(stateLock) {
                lastProbeElapsedMs = -PROBE_COOLDOWN_MS
            }
        }
    }

    fun isEvaluationInProgress(): Boolean = evaluationMutex.isLocked

    internal fun isEvaluationMutexLocked(): Boolean = evaluationMutex.isLocked

    internal fun isLocalVpnFallbackEnabled(context: Context): Boolean {
        localVpnFallbackEnabledChecker?.let { return it(context) }
        return try {
            com.drmohammed.antiporn.data.repository.PolicyRepository.getInstance(context)
                .getCurrentPolicy().isLocalVpnFallbackEnabled
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Timber.tag(TAG).d(e, "Failed reading isLocalVpnFallbackEnabled from PolicyRepository")
            false
        }
    }

    private fun resolveActivePhysicalNetworkSafe(
        context: Context,
        cm: ConnectivityManager? = getConnectivityManager(context)
    ): Network? {
        val activeNet = try {
            activeNetworkProvider?.invoke(context) ?: cm?.activeNetwork
        } catch (e: Exception) {
            Timber.tag(TAG).d(e, "Failed reading activeNetwork in resolveActivePhysicalNetworkSafe")
            null
        }
        if (activeNet != null && resolvePhysicalTransportLabel(context, cm, activeNet) in SUPPORTED_TRANSPORTS) {
            return activeNet
        }
        return getAllPhysicalNetworks(context, cm).firstOrNull { net ->
            resolvePhysicalTransportLabel(context, cm, net) in SUPPORTED_TRANSPORTS
        }
    }

    private fun resolveActiveNetworkIdSafe(context: Context): String? =
        resolveActivePhysicalNetworkSafe(context)?.let { resolveNetworkId(it) }

    fun isExhaustedDotOnNetwork(
        context: Context,
        netId: String? = resolveActiveNetworkIdSafe(context)
    ): Boolean {
        val targetNetId = netId ?: return false
        return DnsResilienceNetworkRouter.isExhaustedDot(targetNetId, getElapsedRealtime())
    }

    fun shouldSkipEvaluationDueToExhaustedConsent(
        netId: String,
        nowMs: Long,
        context: Context? = null
    ): Boolean {
        if (!DnsResilienceNetworkRouter.isExhaustedDot(netId, nowMs)) return false
        val fallbackEnabled = if (context != null) isLocalVpnFallbackEnabled(context) else false
        return !fallbackEnabled
    }

    fun triggerImmediateEvaluationForActiveNetwork(context: Context) {
        ensureHydrated(context)
        val cm = getConnectivityManager(context)
        val targetNet = resolveActivePhysicalNetworkSafe(context, cm) ?: run {
            DnsFallbackConsentNotifier.cancelNotification(context)
            return
        }

        val netId = resolveNetworkId(targetNet)
        val transport = resolvePhysicalTransportLabel(context, cm, targetNet) ?: return
        synchronized(stateLock) {
            lastProbeElapsedMs = -PROBE_COOLDOWN_MS
        }
        DnsResilienceNetworkRouter.clearConsentAndExhaustedForNetwork(netId)
        DnsFallbackConsentNotifier.cancelNotification(context)
        evaluateNetworkUnvalidatedOrDotError(
            context = context,
            network = targetNet,
            transport = transport,
            triggerReason = "manual_fallback_consent_approved"
        )
    }

    fun onExternalVpnCoexistenceRevoked(context: Context) {
        ensureHydrated(context)
        if (activeMode != DnsResilienceMode.FALLBACK_LOCAL_VPN_DOH) return
        if (beginRestorePrimaryTransition(context, "external_vpn_coexistence_revoked")) {
            synchronized(stateLock) {
                isRestoringPrimary = false
            }
        }
    }

    fun evaluateNetworkUnvalidatedOrDotError(context: Context, network: Network, transport: String, triggerReason: String) {
        resilienceScope.launch {
            try {
                evaluationMutex.withLock {
                    DnsResilienceEvaluationRunner.evaluate(context, network, transport, triggerReason, evalCallbacks)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.tag(TAG).w(e, "Unhandled error evaluating $transport DNS resilience")
            }
        }
    }

    @Deprecated("Use evaluateNetworkUnvalidatedOrDotError", ReplaceWith("evaluateNetworkUnvalidatedOrDotError(context, wifiNetwork, \"WIFI\", triggerReason)"))
    fun evaluateWifiUnvalidatedOrDotError(context: Context, wifiNetwork: Network, triggerReason: String) {
        evaluateNetworkUnvalidatedOrDotError(context, wifiNetwork, "WIFI", triggerReason)
    }

    @VisibleForTesting
    internal suspend fun evaluateNetworkUnvalidatedOrDotErrorDirect(
        context: Context,
        network: Network,
        transport: String = "WIFI",
        triggerReason: String
    ) {
        evaluationMutex.withLock {
            DnsResilienceEvaluationRunner.evaluate(context, network, transport, triggerReason, evalCallbacks)
        }
    }

    @VisibleForTesting
    internal suspend fun evaluateWifiUnvalidatedOrDotErrorDirect(context: Context, wifiNetwork: Network, triggerReason: String) {
        evaluateNetworkUnvalidatedOrDotErrorDirect(context, wifiNetwork, "WIFI", triggerReason)
    }

    @VisibleForTesting
    internal fun scheduleSameNetworkPrimaryRecovery(
        context: Context,
        network: Network,
        netId: String,
        targetPolicyHost: String,
        triggerReason: String
    ) {
        synchronized(stateLock) {
            if (activeMode == DnsResilienceMode.PRIMARY_STRICT_DOT || protectedNetworkId != netId) {
                return
            }
            val currentAttempts = DnsResilienceNetworkRouter.recoveryAttemptCountByNet.getOrDefault(netId, 0)
            if (currentAttempts >= DnsResilienceNetworkRouter.MAX_SAME_NET_RECOVERY_ATTEMPTS) {
                Timber.tag(TAG).d("Maximum same-network recovery attempts (${DnsResilienceNetworkRouter.MAX_SAME_NET_RECOVERY_ATTEMPTS}) reached for $netId")
                return
            }
            if (triggerReason.contains("captive", ignoreCase = true) ||
                failoverTriggerClass == FailoverTriggerClass.CAPTIVE_PORTAL
            ) {
                Timber.tag(TAG).d("Skipping scheduled recovery for captive portal network $netId; will recover on net_validated")
                return
            }
            if (activeMode == DnsResilienceMode.FALLBACK_LOCAL_VPN_DOH &&
                failoverTriggerClass == FailoverTriggerClass.OS_DOT_VALIDATION_QUIRK
            ) {
                Timber.tag(TAG).d("Skipping same-network recovery for OS_DOT_VALIDATION_QUIRK on $netId; will restore on network change")
                return
            }
            if (DnsResilienceNetworkRouter.isPrimaryBlockedOnNetwork(netId)) {
                Timber.tag(TAG).d("Skipping same-network recovery for $netId: primary DoT previously failed after recovery")
                return
            }

            val delayMs = if (currentAttempts < DnsResilienceNetworkRouter.SAME_NET_RECOVERY_DELAYS_MS.size) {
                DnsResilienceNetworkRouter.SAME_NET_RECOVERY_DELAYS_MS[currentAttempts]
            } else {
                DnsResilienceNetworkRouter.SAME_NET_RECOVERY_DELAYS_MS.last()
            }

            val scheduledEpoch = stateEpoch.get()
            DnsResilienceNetworkRouter.activeRecoveryJob?.cancel()
            lateinit var launchedJob: Job
            launchedJob = resilienceScope.launch {
                try {
                    val delayFn: suspend (Long) -> Unit = DnsResilienceNetworkRouter.recoveryDelayOverride ?: { delay(it) }
                    delayFn(delayMs)

                    val stillValid = synchronized(stateLock) {
                        stateEpoch.get() == scheduledEpoch &&
                            DnsResilienceNetworkRouter.activeRecoveryJob === launchedJob &&
                            activeMode != DnsResilienceMode.PRIMARY_STRICT_DOT &&
                            protectedNetworkId == netId
                    }
                    if (!stillValid || ProtectionTransitionGuard.hasActiveTransitions()) {
                        return@launch
                    }

                    val linkFamilies = DnsResilienceProbeClient.detectLinkFamilies(getConnectivityManager(context), network)
                    val probeResult = dotProbeDetailedOverride?.invoke(network, targetPolicyHost, true)
                        ?: DnsResilienceProbeClient.probeDotHostDetailed(
                            network = network,
                            host = targetPolicyHost,
                            families = linkFamilies,
                            requireSystemDnsResolution = true,
                            dotProbeOverride = dotProbeOverride
                        )
                    val restored = probeResult.reachable && probeResult.systemDnsResolved

                    val newCount = synchronized(stateLock) {
                        if (stateEpoch.get() != scheduledEpoch ||
                            DnsResilienceNetworkRouter.activeRecoveryJob !== launchedJob ||
                            protectedNetworkId != netId
                        ) {
                            return@launch
                        }
                        DnsResilienceNetworkRouter.recoveryAttemptCountByNet.compute(netId) { _, count -> (count ?: 0) + 1 } ?: 1
                    }

                    if (restored) {
                        Timber.tag(TAG).i("Primary DoT host $targetPolicyHost is now working on $netId! Restoring PRIMARY_STRICT_DOT")
                        DnsResilienceNetworkRouter.markSameNetRecovered(netId)
                        restorePrimaryStrictDot(context, "same_network_primary_recovery:$netId")
                    } else {
                        Timber.tag(TAG).w("Same-network recovery probe failed (attempt $newCount/${DnsResilienceNetworkRouter.MAX_SAME_NET_RECOVERY_ATTEMPTS}) on $netId")
                        if (newCount < DnsResilienceNetworkRouter.MAX_SAME_NET_RECOVERY_ATTEMPTS) {
                            scheduleSameNetworkPrimaryRecovery(context, network, netId, targetPolicyHost, triggerReason)
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Timber.tag(TAG).w(e, "Unhandled error during same-network recovery for $netId")
                }
            }
            DnsResilienceNetworkRouter.activeRecoveryJob = launchedJob
        }
    }

    private fun scheduleCaptivePortalPrimaryRecovery(context: Context, network: Network, netId: String) {
        Timber.tag(TAG).i("Protected captive portal network $netId validated; checking if primary DoT is restored")
        synchronized(stateLock) {
            val expectedEpoch = stateEpoch.get()
            DnsResilienceNetworkRouter.activeCaptivePortalRecoveryJob?.cancel()
            lateinit var portalJob: Job
            portalJob = resilienceScope.launch {
                try {
                    val delayFn: suspend (Long) -> Unit = DnsResilienceNetworkRouter.recoveryDelayOverride ?: { delay(it) }
                    delayFn(DnsResilienceNetworkRouter.CAPTIVE_PORTAL_RECOVERY_DELAY_MS)
                    val stillProtected = synchronized(stateLock) {
                        stateEpoch.get() == expectedEpoch &&
                            DnsResilienceNetworkRouter.activeCaptivePortalRecoveryJob === portalJob &&
                            activeMode != DnsResilienceMode.PRIMARY_STRICT_DOT &&
                            protectedNetworkId == netId
                    }
                    if (stillProtected && !ProtectionTransitionGuard.hasActiveTransitions()) {
                        val targetPolicyHost = DnsResilienceEvaluationRunner.resolvePolicyHost(context)
                        val linkFamilies = DnsResilienceProbeClient.detectLinkFamilies(getConnectivityManager(context), network)
                        if (DnsResilienceProbeClient.testDotHostOnNetwork(
                                network = network,
                                host = targetPolicyHost,
                                families = linkFamilies,
                                dotProbeOverride = dotProbeOverride
                            )
                        ) {
                            val canRestore = synchronized(stateLock) {
                                stateEpoch.get() == expectedEpoch &&
                                    DnsResilienceNetworkRouter.activeCaptivePortalRecoveryJob === portalJob &&
                                    protectedNetworkId == netId
                            }
                            if (canRestore) {
                                Timber.tag(TAG).i("Primary DoT works after captive portal login on $netId! Restoring PRIMARY_STRICT_DOT")
                                restorePrimaryStrictDot(context, "captive_portal_validated_recovery:$netId")
                            }
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Timber.tag(TAG).w(e, "Unhandled error during captive portal DoT recovery on $netId")
                }
            }
            DnsResilienceNetworkRouter.activeCaptivePortalRecoveryJob = portalJob
        }
    }

    fun onNetworkChanged(context: Context, transitionType: String, network: Network?, transport: String?) {
        ensureHydrated(context)
        networkRouter.onNetworkChanged(
            context = context,
            transitionType = transitionType,
            network = network,
            transport = transport,
            onCaptivePortalValidated = { validatedNet, netId ->
                scheduleCaptivePortalPrimaryRecovery(context, validatedNet, netId)
            },
            restorePrimary = { reason ->
                restorePrimaryStrictDot(context, reason)
            }
        )
    }

    private fun beginRestorePrimaryTransition(context: Context, reason: String): Boolean {
        val prevMode: DnsResilienceMode
        val prevNet: String?
        val prevTransport: String?
        synchronized(stateLock) {
            if (activeMode == DnsResilienceMode.PRIMARY_STRICT_DOT && !isRestoringPrimary) return false
            stateEpoch.incrementAndGet()
            prevMode = activeMode
            prevNet = protectedNetworkId
            prevTransport = protectedTransport
            isRestoringPrimary = true
            activeMode = DnsResilienceMode.PRIMARY_STRICT_DOT
            overrideDotHost = null
            protectedNetworkId = null
            protectedTransport = null
            failoverTriggerClass = null
            if (prevNet != null) networkRouter.cancelForNetwork(prevNet)
            DnsResilienceNetworkRouter.cancelRecoveryJobs()
        }
        DnsResilienceStore.clearPersistedState(context)
        DnsResilienceStore.recordNetworkSafe(context, "DNS_RESILIENCE_RESTORE", true, "reason=$reason, prevMode=$prevMode, prevNet=$prevNet, prevTransport=$prevTransport")
        return true
    }

    @VisibleForTesting
    internal fun restorePrimaryStrictDot(context: Context, reason: String = "net_lost") {
        if (!beginRestorePrimaryTransition(context, reason)) return
        resilienceScope.launch {
            try {
                evaluationMutex.withLock { restorePrimaryStrictDotLockedAsyncBody(context, reason) }
            } catch (e: CancellationException) {
                synchronized(stateLock) { isRestoringPrimary = false }
                throw e
            } catch (e: Exception) {
                synchronized(stateLock) { isRestoringPrimary = false }
                Timber.tag(TAG).w(e, "Unhandled error restoring PRIMARY_STRICT_DOT ($reason)")
            }
        }
    }

    private fun restorePrimaryStrictDotLockedAsyncBody(context: Context, reason: String) {
        DnsResilienceStore.executePrimaryRestore(context, reason) { synchronized(stateLock) { isRestoringPrimary = false } }
    }

    private fun rollbackToPrimarySync(context: Context, netId: String, transport: String, reason: String) {
        synchronized(stateLock) {
            stateEpoch.incrementAndGet()
            activeMode = DnsResilienceMode.PRIMARY_STRICT_DOT
            overrideDotHost = null
            protectedNetworkId = null
            protectedTransport = null
            failoverTriggerClass = null
            isRestoringPrimary = false
            DnsResilienceNetworkRouter.cancelRecoveryJobs()
            DnsResilienceStore.clearPersistedState(context)
        }
        DnsResilienceStore.recordNetworkSafe(context, "DNS_FAILOVER_ROLLBACK", false, "net=$netId, transport=$transport, reason=$reason")
        applyDnsShieldSafely(context)
    }

    private fun markExhaustedDotAndNotify(context: Context, netId: String, restorePrimaryDpm: Boolean) {
        val now = getElapsedRealtime()
        DnsResilienceNetworkRouter.markExhaustedDot(netId, now)
        synchronized(stateLock) {
            stateEpoch.incrementAndGet()
            activeMode = DnsResilienceMode.PRIMARY_STRICT_DOT
            overrideDotHost = null
            protectedNetworkId = null
            protectedTransport = null
            failoverTriggerClass = null
            isRestoringPrimary = false
            DnsResilienceNetworkRouter.cancelRecoveryJobs()
        }
        DnsResilienceStore.clearPersistedState(context)
        if (restorePrimaryDpm) {
            applyDnsShieldSafely(context)
        }
        if (DnsResilienceNetworkRouter.markConsentPromptedIfNew(netId)) {
            val override = consentNotifierOverride
            if (override != null) {
                override.invoke(context, netId)
            } else {
                DnsFallbackConsentNotifier.notifyOnceForNetwork(context, netId)
            }
        }
    }

    private fun applyDnsShieldSafely(context: Context): Boolean =
        DnsResilienceStore.applyDnsShieldSafely(context, applyDnsShieldOverride)

    @VisibleForTesting internal fun resolveBootstrapCandidates(host: String, families: LinkIpFamilies): List<InetAddress> = DnsResilienceProbeClient.resolveBootstrapAddresses(host, families)
    @VisibleForTesting internal fun verifyTls443OnSocket(rawSocket: Socket, sniHost: String): Boolean = DnsResilienceProbeClient.verifyTls443OnSocket(rawSocket, sniHost)
    @VisibleForTesting internal fun exchangeRfc7858ProbeOnSocket(socket: Socket): Boolean = DnsResilienceProbeClient.exchangeRfc7858ProbeOnSocket(socket)
}
