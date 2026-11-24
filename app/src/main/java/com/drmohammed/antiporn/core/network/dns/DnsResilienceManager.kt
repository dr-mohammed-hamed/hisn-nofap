package com.drmohammed.antiporn.core.network.dns

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import androidx.annotation.VisibleForTesting
import com.drmohammed.antiporn.curfew.CurfewVpnDnsBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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
        override fun isInCooldown(netId: String, nowMs: Long): Boolean =
            (nowMs - lastProbeElapsedMs) < PROBE_COOLDOWN_MS &&
                (protectedNetworkId == netId || lastProbedNetworkId == netId)
        override fun markProbeAttempt(netId: String, nowMs: Long) {
            lastProbeElapsedMs = nowMs
            lastProbedNetworkId = netId
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
        override fun rollbackToPrimarySync(context: Context, netId: String, transport: String, reason: String) {
            this@DnsResilienceManager.rollbackToPrimarySync(context, netId, transport, reason)
        }
        override fun applyDnsShieldSafely(context: Context) {
            this@DnsResilienceManager.applyDnsShieldSafely(context)
        }
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
            activeMode = mode
            overrideDotHost = overrideHost
            protectedNetworkId = netId
            protectedTransport = transport
            failoverTriggerClass = triggerClass
            isRestoringPrimary = restoringPrimary
            isHydrated = true
        }
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
            if (context != null) DnsResilienceStore.clearPersistedState(context)
        }
    }

    fun onPolicyDnsHostChanged(context: Context) {
        resetStateSync(context)
        resilienceScope.launch {
            try {
                CurfewVpnDnsBridge.reconcileNonBlackholeVpnAndDns(context)
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
        networkRouter.onDpmHostNotServing(context, failedHost, lastProbeElapsedMs, lastProbedNetworkId)
    }

    fun onExternalDnsResetWhileUnvalidated(context: Context, network: Network, transport: String, driftCount60s: Int): Boolean {
        ensureHydrated(context)
        return networkRouter.onExternalDnsResetWhileUnvalidated(context, network, transport, driftCount60s) {
            lastProbeElapsedMs = -PROBE_COOLDOWN_MS
        }
    }

    fun evaluateNetworkUnvalidatedOrDotError(context: Context, network: Network, transport: String, triggerReason: String) {
        resilienceScope.launch {
            evaluationMutex.withLock {
                DnsResilienceEvaluationRunner.evaluate(context, network, transport, triggerReason, evalCallbacks)
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

    fun onNetworkChanged(context: Context, transitionType: String, network: Network?, transport: String?) {
        ensureHydrated(context)
        networkRouter.onNetworkChanged(context, transitionType, network, transport) { reason ->
            restorePrimaryStrictDot(context, reason)
        }
    }

    private fun beginRestorePrimaryTransition(context: Context, reason: String): Boolean {
        val prevMode: DnsResilienceMode; val prevNet: String?; val prevTransport: String?
        synchronized(stateLock) {
            if (activeMode == DnsResilienceMode.PRIMARY_STRICT_DOT && !isRestoringPrimary) return false
            stateEpoch.incrementAndGet()
            prevMode = activeMode; prevNet = protectedNetworkId; prevTransport = protectedTransport
            isRestoringPrimary = true; activeMode = DnsResilienceMode.PRIMARY_STRICT_DOT
            overrideDotHost = null; protectedNetworkId = null; protectedTransport = null; failoverTriggerClass = null
            if (prevNet != null) networkRouter.cancelForNetwork(prevNet)
        }
        DnsResilienceStore.clearPersistedState(context)
        DnsResilienceStore.recordNetworkSafe(context, "DNS_RESILIENCE_RESTORE", true, "reason=$reason, prevMode=$prevMode, prevNet=$prevNet, prevTransport=$prevTransport")
        return true
    }

    private fun restorePrimaryStrictDot(context: Context, reason: String = "net_lost") {
        if (!beginRestorePrimaryTransition(context, reason)) return
        resilienceScope.launch { evaluationMutex.withLock { restorePrimaryStrictDotLockedAsyncBody(context, reason) } }
    }

    private fun restorePrimaryStrictDotLockedAsyncBody(context: Context, reason: String) {
        DnsResilienceStore.executePrimaryRestore(context, reason) { synchronized(stateLock) { isRestoringPrimary = false } }
    }

    private fun rollbackToPrimarySync(context: Context, netId: String, transport: String, reason: String) {
        synchronized(stateLock) {
            stateEpoch.incrementAndGet()
            activeMode = DnsResilienceMode.PRIMARY_STRICT_DOT
            overrideDotHost = null; protectedNetworkId = null; protectedTransport = null; failoverTriggerClass = null; isRestoringPrimary = false
            DnsResilienceStore.clearPersistedState(context)
        }
        DnsResilienceStore.recordNetworkSafe(context, "DNS_FAILOVER_ROLLBACK", false, "net=$netId, transport=$transport, reason=$reason")
        applyDnsShieldSafely(context)
    }

    private fun applyDnsShieldSafely(context: Context) = DnsResilienceStore.applyDnsShieldSafely(context, applyDnsShieldOverride)

    @VisibleForTesting internal fun resolveBootstrapCandidates(host: String, families: LinkIpFamilies): List<InetAddress> = DnsResilienceProbeClient.resolveBootstrapCandidates(host, families)
    @VisibleForTesting internal fun verifyTls443OnSocket(rawSocket: Socket, sniHost: String): Boolean = DnsResilienceProbeClient.verifyTls443OnSocket(rawSocket, sniHost)
    @VisibleForTesting internal fun exchangeRfc7858ProbeOnSocket(socket: Socket): Boolean = DnsResilienceProbeClient.exchangeRfc7858ProbeOnSocket(socket)
}
