package com.drmohammed.antiporn.core.network.dns

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.SystemClock
import androidx.annotation.VisibleForTesting
import com.drmohammed.antiporn.core.engine.ProtectionTransitionGuard
import com.drmohammed.antiporn.core.model.DevicePolicyBundle
import com.drmohammed.antiporn.curfew.CurfewBlackholeVpnService
import com.drmohammed.antiporn.curfew.CurfewVpnDnsBridge
import com.drmohammed.antiporn.shields.DnsShield
import com.drmohammed.antiporn.telemetry.BlackBoxRecorder
import com.drmohammed.antiporn.telemetry.SafeDpmGateway
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicLong

/**
 * Intelligent 3-tier Wi-Fi & DNS Resilience Engine.
 * Dynamically protects user connectivity while maintaining 100% strict pornography blocking.
 *
 * Tier 1: Default OS Strict DoT (Port 853) - Zero battery consumption.
 * Tier 2: Automatic switch to verified working family DoT host on the active Wi-Fi.
 * Tier 3: Comprehensive hardening via Local DNS VPN (VpnMode.DNS_FILTERING_ONLY) + DoH (Port 443).
 */
object DnsResilienceManager {

    private const val TAG = "AntiPorn_DnsResilience"

    enum class DnsResilienceMode {
        PRIMARY_STRICT_DOT,
        FALLBACK_STRICT_DOT,
        FALLBACK_LOCAL_VPN_DOH
    }

    private const val PROBE_COOLDOWN_MS = 15_000L
    private const val ANTI_SPOOF_TIMEOUT_MS = 1_500
    private const val OS_QUIRK_RECHECK_GRACE_MS = 2_000L

    private val ALTERNATE_FAMILY_PRESETS = listOf(
        DevicePolicyBundle.ADGUARD_FAMILY_DNS_HOST,
        DevicePolicyBundle.CLOUDFLARE_FAMILY_DNS_HOST,
        DevicePolicyBundle.CONTROLD_NO_ADS_PORN_DNS_HOST,
        "family-filter-dns.cleanbrowsing.org"
    )

    private val resilienceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val evaluationMutex = Mutex()
    private val pendingEvaluationJobs = java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.Job>()

    @VisibleForTesting
    internal var curfewOrVpnActiveChecker: ((Context) -> Boolean)? = null

    @VisibleForTesting
    internal var isWifiValidatedChecker: ((Network) -> Boolean)? = null

    @VisibleForTesting
    internal var isWifiCaptivePortalChecker: ((Network) -> Boolean)? = null

    @VisibleForTesting
    internal var internetProbeOverride: ((Network) -> Boolean)? = null

    @VisibleForTesting
    internal var dotProbeOverride: ((Network, String) -> Boolean)? = null

    @VisibleForTesting
    internal var delayOverride: (suspend (Long) -> Unit)? = null

    @VisibleForTesting
    internal var isDeviceOwnerChecker: ((Context) -> Boolean)? = null

    @VisibleForTesting
    internal var binaryFilterInitOverride: ((Context) -> Boolean)? = null

    @VisibleForTesting
    internal var level3ActivationOverride: ((Context, String) -> Boolean)? = null

    @VisibleForTesting
    internal var applyDnsShieldOverride: ((Context) -> Unit)? = null

    @VisibleForTesting
    internal var policyHostProvider: ((Context) -> String)? = null

    @VisibleForTesting
    internal var recoveryDelayOverride: (suspend (Long) -> Unit)?
        get() = DnsResilienceNetworkRouter.recoveryDelayOverride
        set(value) {
            DnsResilienceNetworkRouter.recoveryDelayOverride = value
        }

    private val stateLock = Any()
    @Volatile
    private var activeMode: DnsResilienceMode = DnsResilienceMode.PRIMARY_STRICT_DOT
    @Volatile
    private var overrideDotHost: String? = null
    @Volatile
    private var protectedWifiNetworkId: String? = null
    @Volatile
    private var isProtectedNetCaptivePortal: Boolean = false
    @Volatile
    private var lastProbeElapsedMs: Long = 0L
    private val stateEpoch = AtomicLong(0L)

    fun getActiveMode(): DnsResilienceMode = activeMode

    fun isLocalVpnFallbackActive(): Boolean =
        activeMode == DnsResilienceMode.FALLBACK_LOCAL_VPN_DOH

    fun getEffectiveRuntimeDnsHost(policyHost: String): String {
        val currentOverride = overrideDotHost
        return if (activeMode == DnsResilienceMode.FALLBACK_STRICT_DOT && !currentOverride.isNullOrBlank()) {
            currentOverride
        } else {
            policyHost
        }
    }

    @VisibleForTesting
    internal fun setFallbackStateForTest(
        mode: DnsResilienceMode,
        netId: String?,
        isCaptivePortal: Boolean = false
    ) {
        synchronized(stateLock) {
            stateEpoch.incrementAndGet()
            activeMode = mode
            protectedWifiNetworkId = netId
            isProtectedNetCaptivePortal = isCaptivePortal
        }
    }

    fun getProtectedWifiNetworkId(): String? = protectedWifiNetworkId

    internal fun resolveNetworkId(network: Network): String {
        return try {
            network.toString()
        } catch (_: RuntimeException) {
            "net@${System.identityHashCode(network)}"
        }
    }

    /**
     * Synchronously resets runtime resilience state to PRIMARY_STRICT_DOT.
     * Prevents race conditions when policy changes before VPN policy sync.
     */
    fun resetStateSync() {
        synchronized(stateLock) {
            Timber.tag(TAG).i("Resetting resilience state to PRIMARY_STRICT_DOT synchronously")
            stateEpoch.incrementAndGet()
            activeMode = DnsResilienceMode.PRIMARY_STRICT_DOT
            overrideDotHost = null
            protectedWifiNetworkId = null
            isProtectedNetCaptivePortal = false
            lastProbeElapsedMs = 0L
            DnsResilienceNetworkRouter.reset()
            pendingEvaluationJobs.values.forEach { it.cancel() }
            pendingEvaluationJobs.clear()
            curfewOrVpnActiveChecker = null
            isWifiValidatedChecker = null
            isWifiCaptivePortalChecker = null
            internetProbeOverride = null
            dotProbeOverride = null
            delayOverride = null
            isDeviceOwnerChecker = null
            binaryFilterInitOverride = null
            level3ActivationOverride = null
            applyDnsShieldOverride = null
            policyHostProvider = null
        }
    }

    /**
     * Resets runtime resilience state when policy DNS host is updated by supervisor/user.
     */
    fun onPolicyDnsHostChanged(context: Context) {
        resetStateSync()
        resilienceScope.launch {
            try {
                CurfewVpnDnsBridge.reconcileNonBlackholeVpnAndDns(context)
            } catch (e: Exception) {
                Timber.tag(TAG).w(e, "Error reconciling VPN after policy host change")
            }
        }
    }

    private fun getElapsedRealtime(): Long {
        return try {
            SystemClock.elapsedRealtime()
        } catch (_: RuntimeException) {
            System.currentTimeMillis()
        }
    }

    private fun getConnectivityManager(context: Context): ConnectivityManager? {
        return try {
            context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        } catch (_: RuntimeException) {
            null
        }
    }

    /**
     * Called when DPM returns error code 1 (PRIVATE_DNS_SET_ERROR_HOST_NOT_SERVING)
     * during standalone DoT application.
     */
    fun onDpmHostNotServing(context: Context, failedHost: String) {
        val now = getElapsedRealtime()
        if (now - lastProbeElapsedMs < PROBE_COOLDOWN_MS) {
            Timber.tag(TAG).d("onDpmHostNotServing debounced for $failedHost (${now - lastProbeElapsedMs}ms since last probe)")
            return
        }
        val cm = getConnectivityManager(context) ?: return
        val activeNet = cm.activeNetwork ?: return
        val caps = cm.getNetworkCapabilities(activeNet) ?: return
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
            evaluateWifiUnvalidatedOrDotError(context, activeNet, "dpm_host_not_serving:$failedHost")
        }
    }

    /**
     * Evaluates whether an unvalidated Wi-Fi network requires Level 2 or Level 3 DNS resilience.
     */
    fun evaluateWifiUnvalidatedOrDotError(
        context: Context,
        wifiNetwork: Network,
        triggerReason: String
    ) {
        resilienceScope.launch {
            try {
                evaluationMutex.withLock {
                    evaluateWifiUnvalidatedOrDotErrorInternal(context, wifiNetwork, triggerReason)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.tag(TAG).w(e, "Unhandled error evaluating Wi-Fi DNS resilience")
            }
        }
    }

    @VisibleForTesting
    internal suspend fun evaluateWifiUnvalidatedOrDotErrorDirect(
        context: Context,
        wifiNetwork: Network,
        triggerReason: String
    ) {
        evaluationMutex.withLock {
            evaluateWifiUnvalidatedOrDotErrorInternal(context, wifiNetwork, triggerReason)
        }
    }

    private fun resolveTargetPolicyHost(context: Context): String {
        val policyHost = DnsShield.DEFAULT_DNS_HOST
        return try {
            policyHostProvider?.invoke(context)
                ?: com.drmohammed.antiporn.data.repository.PolicyRepository.getInstance(context)
                    .getCurrentPolicy().dnsHost.takeIf { it.isNotBlank() } ?: policyHost
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Timber.tag(TAG).w(e, "Falling back to default DNS host while reading policy")
            policyHost
        }
    }

    private fun getLinkIpFamiliesForNetwork(cm: ConnectivityManager?, network: Network): LinkIpFamilies {
        return try {
            cm?.getLinkProperties(network).toLinkIpFamilies()
        } catch (_: Throwable) {
            LinkIpFamilies()
        }
    }

    private suspend fun evaluateWifiUnvalidatedOrDotErrorInternal(
        context: Context,
        wifiNetwork: Network,
        triggerReason: String
    ) {
        val startEpoch = stateEpoch.get()
        val netId = resolveNetworkId(wifiNetwork)
        val (currentModeSnapshot, currentProtId) = synchronized(stateLock) {
            activeMode to protectedWifiNetworkId
        }
        if (currentProtId == netId && currentModeSnapshot == DnsResilienceMode.FALLBACK_LOCAL_VPN_DOH) {
            Timber.tag(TAG).d("Wi-Fi network $netId is already protected in Level 3 Fallback; skipping duplicate evaluation")
            return
        }

        val isDpmHostNotServingTrigger = triggerReason.startsWith("dpm_host_not_serving:")

        val now = getElapsedRealtime()
        if (now - lastProbeElapsedMs < PROBE_COOLDOWN_MS && currentProtId == netId && !isDpmHostNotServingTrigger) {
            Timber.tag(TAG).d("Resilience evaluation in cooldown for $netId")
            return
        }
        lastProbeElapsedMs = now

        Timber.tag(TAG).w("Evaluating Wi-Fi resilience for net=$netId (trigger=$triggerReason, activeMode=$currentModeSnapshot)")

        // Pre-condition: Strictly do NOT intervene during Curfew Blackhole or active non-idle VPN!
        val isCurfewOrVpnActive = curfewOrVpnActiveChecker?.invoke(context) ?: try {
            CurfewBlackholeVpnService.evaluateRequiredMode(context) != CurfewBlackholeVpnService.VpnMode.IDLE ||
                com.drmohammed.antiporn.curfew.CurfewEnforcer.isEffectiveBlockActive(context)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Timber.tag(TAG).w(e, "Failed checking curfew/VPN state; skipping resilience intervention fail-safe")
            true
        }
        if (isCurfewOrVpnActive) {
            Timber.tag(TAG).d("Curfew or non-idle VPN is active; DnsResilienceManager will not intervene.")
            return
        }

        // 1. Fresh NetworkCapabilities check (Avoid stale cache on healthy devices)
        val cm = getConnectivityManager(context)
        val safeCaps: (Network) -> NetworkCapabilities? = { net ->
            try {
                cm?.getNetworkCapabilities(net)
            } catch (e: Exception) {
                Timber.tag(TAG).d("Failed querying NetworkCapabilities for $netId: ${e.message}")
                null
            }
        }
        val initialValidated = isWifiValidatedChecker?.invoke(wifiNetwork)
            ?: (safeCaps(wifiNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true)
        if (!isDpmHostNotServingTrigger && initialValidated) {
            Timber.tag(TAG).d("Wi-Fi network $netId is already validated; skipping resilience intervention.")
            return
        }

        // Anti-Spoofing Internet Verification Probe (Strictly bound to wifiNetwork)
        val isCaptive = isWifiCaptivePortalChecker?.invoke(wifiNetwork)
            ?: (safeCaps(wifiNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL) == true)
        val hasInternet = isCaptive || checkWifiHasPhysicalInternet(wifiNetwork)

        if (!hasInternet) {
            Timber.tag(TAG).w("Wi-Fi network $netId has NO physical internet (router offline / ISP outage). Preserving PRIMARY_STRICT_DOT.")
            return
        }

        val markCaptivePortal = isCaptive || triggerReason.contains("captive", ignoreCase = true)
        val targetPolicyHost = resolveTargetPolicyHost(context)
        val linkFamilies = getLinkIpFamiliesForNetwork(cm, wifiNetwork)

        // 2. Test primary DoT host on wifiNetwork
        val isPrimaryWorking = !isCaptive && testDotHostOnNetwork(wifiNetwork, targetPolicyHost, linkFamilies)

        // Re-read fresh capabilities after network probes
        val postPrimaryValidated = isWifiValidatedChecker?.invoke(wifiNetwork)
            ?: (safeCaps(wifiNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true)
        if (!isDpmHostNotServingTrigger && postPrimaryValidated) {
            Timber.tag(TAG).i("Primary DoT host $targetPolicyHost is verified working and validated on $netId")
            return
        }

        var skipStageA = false
        if (isPrimaryWorking) {
            if (!isDpmHostNotServingTrigger) {
                // Short verification grace period (OS_QUIRK_RECHECK_GRACE_MS)
                val delayFn: suspend (Long) -> Unit = delayOverride ?: { kotlinx.coroutines.delay(it) }
                delayFn(OS_QUIRK_RECHECK_GRACE_MS)
                if (stateEpoch.get() != startEpoch) return
                val quirkValidated = isWifiValidatedChecker?.invoke(wifiNetwork)
                    ?: (safeCaps(wifiNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true)
                if (quirkValidated) {
                    Timber.tag(TAG).i("Network $netId validated during grace period; remaining on PRIMARY_STRICT_DOT")
                    return
                }
                skipStageA = true
            } else {
                skipStageA = true
            }
        }

        // 3. Stage A: Try Alternate Family DoT Presets (Level 2) if not captive and not Nothing OS quirk
        if (!isCaptive && !skipStageA) {
            val candidateHosts = ALTERNATE_FAMILY_PRESETS.filterNot { it.equals(targetPolicyHost, ignoreCase = true) }
            for (candidate in candidateHosts) {
                if (stateEpoch.get() != startEpoch) return
                if (testDotHostOnNetwork(wifiNetwork, candidate, linkFamilies)) {
                    Timber.tag(TAG).i("Alternate Family DoT host $candidate works on Wi-Fi $netId! Activating Level 2 FALLBACK_STRICT_DOT")
                    synchronized(stateLock) {
                        if (stateEpoch.get() != startEpoch) return
                        activeMode = DnsResilienceMode.FALLBACK_STRICT_DOT
                        overrideDotHost = candidate
                        protectedWifiNetworkId = netId
                        isProtectedNetCaptivePortal = markCaptivePortal
                    }
                    BlackBoxRecorder.recordNetwork(
                        context = context,
                        action = "DNS_FAILOVER_ALTERNATE_DOT",
                        isSuccess = true,
                        details = "net=$netId, primary=$targetPolicyHost, fallback=$candidate"
                    )
                    applyDnsShieldSafely(context)

                    // CRITICAL: Wait 6-second grace period to verify whether Wi-Fi becomes validated under fallback DoT!
                    if (delayOverride != null) {
                        delayOverride?.invoke(6_000L)
                    } else {
                        kotlinx.coroutines.delay(6_000L)
                    }
                    if (stateEpoch.get() != startEpoch) return
                    val postFallbackValidated = isWifiValidatedChecker?.invoke(wifiNetwork)
                        ?: (safeCaps(wifiNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true)
                    if (postFallbackValidated) {
                        Timber.tag(TAG).i("Wi-Fi $netId successfully validated under alternate DoT $candidate; remaining at Level 2")
                        scheduleSameNetworkPrimaryRecovery(context, wifiNetwork, netId, targetPolicyHost, triggerReason)
                        return
                    } else {
                        Timber.tag(TAG).w("Wi-Fi $netId remains unvalidated after 6s under alternate DoT $candidate; escalating to Level 3")
                        break // Stop trying alternate DoT; proceed directly to Level 3
                    }
                }
            }
        }

        if (stateEpoch.get() != startEpoch) return

        // 4. Stage B: Level 3 Escalation - Port 853 Blocked or Android 17 OS Quirk (FALLBACK_LOCAL_VPN_DOH)
        Timber.tag(TAG).w("All DoT 853 failed or Wi-Fi unvalidated on $netId; escalating to Level 3 (FALLBACK_LOCAL_VPN_DOH + DoH 443)")
        val isDeviceOwner = isDeviceOwnerChecker?.invoke(context) ?: SafeDpmGateway.isDeviceOwner(context)
        if (!isDeviceOwner) {
            Timber.tag(TAG).e("Cannot activate Level 3 fallback: Application is not Device Owner; restoring PRIMARY_STRICT_DOT")
            synchronized(stateLock) {
                if (stateEpoch.get() != startEpoch) return
                activeMode = DnsResilienceMode.PRIMARY_STRICT_DOT
                overrideDotHost = null
                protectedWifiNetworkId = null
                isProtectedNetCaptivePortal = false
            }
            applyDnsShieldSafely(context)
            return
        }

        // INVARIANT #1: BinaryDomainFilter MUST initialize successfully before opportunistic downgrade
        val filterReady = try {
            binaryFilterInitOverride?.invoke(context) ?: BinaryDomainFilter.init(context)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Timber.tag(TAG).e(e, "BinaryDomainFilter initialization threw exception before Level 3 activation")
            false
        }
        if (!filterReady) {
            Timber.tag(TAG).e("BinaryDomainFilter failed to initialize; strictly aborting Level 3 escalation to prevent porn leak (Invariant #1)")
            synchronized(stateLock) {
                if (stateEpoch.get() != startEpoch) return
                activeMode = DnsResilienceMode.PRIMARY_STRICT_DOT
                overrideDotHost = null
                protectedWifiNetworkId = null
                isProtectedNetCaptivePortal = false
            }
            applyDnsShieldSafely(context)
            return
        }

        synchronized(stateLock) {
            if (stateEpoch.get() != startEpoch) return
            activeMode = DnsResilienceMode.FALLBACK_LOCAL_VPN_DOH
            overrideDotHost = null
            protectedWifiNetworkId = netId
            isProtectedNetCaptivePortal = markCaptivePortal
        }

        BlackBoxRecorder.recordNetwork(
            context = context,
            action = "DNS_FAILOVER_LOCAL_VPN_DOH",
            isSuccess = true,
            details = "net=$netId, reason=$triggerReason, dohPort=443"
        )

        val result = level3ActivationOverride?.invoke(context, targetPolicyHost) ?: ProtectionTransitionGuard.runInternalTransition {
            val dpmRes = SafeDpmGateway.executeRawBlocking(
                context = context,
                tag = BlackBoxRecorder.TAG_SHIELD,
                actionName = "activateLevel3DnsResilience"
            ) { dpm, admin ->
                CurfewVpnDnsBridge.applyDnsShieldPolicy(
                    context = context,
                    dpm = dpm,
                    admin = admin,
                    shieldId = DnsShield.id,
                    targetHost = targetPolicyHost
                )
            }
            dpmRes.getOrNull()?.success == true
        }

        if (stateEpoch.get() != startEpoch) return

        if (!result) {
            Timber.tag(TAG).e("Failed to activate Level 3 VPN fallback safely; rolling back to PRIMARY_STRICT_DOT (Fail-Secure)")
            synchronized(stateLock) {
                if (stateEpoch.get() != startEpoch) return
                activeMode = DnsResilienceMode.PRIMARY_STRICT_DOT
                protectedWifiNetworkId = null
                isProtectedNetCaptivePortal = false
            }
            applyDnsShieldSafely(context)
        } else {
            Timber.tag(TAG).i("Level 3 Local VPN DNS + DoH 443 activated successfully for Wi-Fi $netId")
            scheduleSameNetworkPrimaryRecovery(context, wifiNetwork, netId, targetPolicyHost, triggerReason)
        }
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
            if (activeMode == DnsResilienceMode.PRIMARY_STRICT_DOT || protectedWifiNetworkId != netId) {
                return
            }
            val currentAttempts = DnsResilienceNetworkRouter.recoveryAttemptCountByNet.getOrDefault(netId, 0)
            if (currentAttempts >= DnsResilienceNetworkRouter.MAX_SAME_NET_RECOVERY_ATTEMPTS) {
                Timber.tag(TAG).d("Maximum same-network recovery attempts (${DnsResilienceNetworkRouter.MAX_SAME_NET_RECOVERY_ATTEMPTS}) reached for $netId")
                return
            }

            if (triggerReason.contains("captive", ignoreCase = true) || isProtectedNetCaptivePortal) {
                Timber.tag(TAG).d("Skipping scheduled recovery for captive portal network $netId; will recover on net_validated")
                return
            }

            val delayMs = if (currentAttempts < DnsResilienceNetworkRouter.SAME_NET_RECOVERY_DELAYS_MS.size) {
                DnsResilienceNetworkRouter.SAME_NET_RECOVERY_DELAYS_MS[currentAttempts]
            } else {
                DnsResilienceNetworkRouter.SAME_NET_RECOVERY_DELAYS_MS.last()
            }

            val scheduledEpoch = stateEpoch.get()
            DnsResilienceNetworkRouter.activeRecoveryJob?.cancel()
            lateinit var launchedJob: kotlinx.coroutines.Job
            launchedJob = resilienceScope.launch {
                try {
                    val delayFn: suspend (Long) -> Unit = DnsResilienceNetworkRouter.recoveryDelayOverride ?: { kotlinx.coroutines.delay(it) }
                    delayFn(delayMs)

                    val stillValid = synchronized(stateLock) {
                        stateEpoch.get() == scheduledEpoch &&
                            DnsResilienceNetworkRouter.activeRecoveryJob === launchedJob &&
                            activeMode != DnsResilienceMode.PRIMARY_STRICT_DOT &&
                            protectedWifiNetworkId == netId
                    }
                    if (!stillValid || ProtectionTransitionGuard.hasActiveTransitions()) {
                        return@launch
                    }

                    val linkFamilies = getLinkIpFamiliesForNetwork(getConnectivityManager(context), network)
                    val restored = testDotHostOnNetwork(network, targetPolicyHost, linkFamilies)

                    val newCount = synchronized(stateLock) {
                        if (stateEpoch.get() != scheduledEpoch ||
                            DnsResilienceNetworkRouter.activeRecoveryJob !== launchedJob ||
                            protectedWifiNetworkId != netId
                        ) {
                            return@launch
                        }
                        DnsResilienceNetworkRouter.recoveryAttemptCountByNet.compute(netId) { _, count -> (count ?: 0) + 1 } ?: 1
                    }

                    if (restored) {
                        Timber.tag(TAG).i("Primary DoT host $targetPolicyHost is now working on $netId! Restoring PRIMARY_STRICT_DOT")
                        restorePrimaryStrictDot(context)
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

    /**
     * Handles network capability and transport transitions cleanly without flapping.
     */
    fun onNetworkChanged(
        context: Context,
        transitionType: String,
        network: Network?,
        transport: String?
    ) {
        val netId = if (network != null) resolveNetworkId(network) else null
        val (currentMode, protectedNet, wasCaptivePortal) = synchronized(stateLock) {
            Triple(activeMode, protectedWifiNetworkId, isProtectedNetCaptivePortal)
        }
        Timber.tag(TAG).d("onNetworkChanged: transition=$transitionType, net=$netId, transport=$transport, activeMode=$currentMode")

        // Mandatory Invariant 1 (Anti-Flapping):
        // Never tear down or flap when Cellular disconnects or when the protected Wi-Fi becomes validated
        if (currentMode != DnsResilienceMode.PRIMARY_STRICT_DOT) {
            if (transport == "CELLULAR" && transitionType.startsWith("net_lost")) {
                Timber.tag(TAG).d("Cellular disconnected while Wi-Fi is protected; preserving active resilience mode")
                return
            }
            if (netId == protectedNet && transitionType.startsWith("net_validated")) {
                if (wasCaptivePortal && network != null) {
                    Timber.tag(TAG).i("Protected captive portal Wi-Fi $netId validated; checking if primary DoT is restored")
                    synchronized(stateLock) {
                        val expectedEpoch = stateEpoch.get()
                        DnsResilienceNetworkRouter.activeCaptivePortalRecoveryJob?.cancel()
                        lateinit var portalJob: kotlinx.coroutines.Job
                        portalJob = resilienceScope.launch {
                            try {
                                val delayFn: suspend (Long) -> Unit = DnsResilienceNetworkRouter.recoveryDelayOverride ?: { kotlinx.coroutines.delay(it) }
                                delayFn(DnsResilienceNetworkRouter.CAPTIVE_PORTAL_RECOVERY_DELAY_MS)
                                val stillProtected = synchronized(stateLock) {
                                    stateEpoch.get() == expectedEpoch &&
                                        DnsResilienceNetworkRouter.activeCaptivePortalRecoveryJob === portalJob &&
                                        activeMode != DnsResilienceMode.PRIMARY_STRICT_DOT &&
                                        protectedWifiNetworkId == netId
                                }
                                if (stillProtected && !ProtectionTransitionGuard.hasActiveTransitions()) {
                                    val targetPolicyHost = resolveTargetPolicyHost(context)
                                    val linkFamilies = getLinkIpFamiliesForNetwork(getConnectivityManager(context), network)
                                    if (testDotHostOnNetwork(network, targetPolicyHost, linkFamilies)) {
                                        val canRestore = synchronized(stateLock) {
                                            stateEpoch.get() == expectedEpoch &&
                                                DnsResilienceNetworkRouter.activeCaptivePortalRecoveryJob === portalJob &&
                                                protectedWifiNetworkId == netId
                                        }
                                        if (canRestore) {
                                            Timber.tag(TAG).i("Primary DoT works after captive portal login on $netId! Restoring PRIMARY_STRICT_DOT")
                                            restorePrimaryStrictDot(context)
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
                    return
                }
                Timber.tag(TAG).d("Protected Wi-Fi $netId is now validated under resilience mode; preserving protection")
                return
            }
            if (netId != null && netId == protectedNet && transitionType.startsWith("net_lost")) {
                Timber.tag(TAG).i("Protected Wi-Fi $netId was disconnected! Restoring PRIMARY_STRICT_DOT")
                pendingEvaluationJobs.remove(netId)?.cancel()
                DnsResilienceNetworkRouter.onNetworkLost(netId)
                restorePrimaryStrictDot(context)
                return
            }
        }

        // If Wi-Fi validated or lost, cancel any pending 10s evaluation job immediately
        if (transport == "WIFI" && network != null) {
            val key = netId ?: network.toString()
            if (transitionType.startsWith("net_validated") || transitionType.startsWith("net_lost")) {
                pendingEvaluationJobs.remove(key)?.cancel()
            }
        }

        // Wi-Fi Captive Portal -> Immediate evaluation (no 10s wait!)
        if (transport == "WIFI" && network != null && transitionType.startsWith("net_captive_portal")) {
            val key = netId ?: network.toString()
            pendingEvaluationJobs.remove(key)?.cancel()
            evaluateWifiUnvalidatedOrDotError(context, network, "wifi_captive_portal_immediate")
            return
        }

        // If a new Wi-Fi network arrives and is unvalidated, evaluate it with 10-second debounce
        if (transport == "WIFI" && network != null) {
            if (transitionType.startsWith("net_unvalidated") || transitionType.startsWith("net_available")) {
                val key = netId ?: network.toString()
                pendingEvaluationJobs[key]?.cancel()
                val job = resilienceScope.launch {
                    try {
                        kotlinx.coroutines.delay(10_000L) // 10-second debounce window
                        val cm = getConnectivityManager(context)
                        val caps = try {
                            cm?.getNetworkCapabilities(network)
                        } catch (_: Exception) {
                            null
                        }
                        if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                            !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                        ) {
                            evaluateWifiUnvalidatedOrDotError(context, network, "wifi_unvalidated_timeout_10s")
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Timber.tag(TAG).w(e, "Error in 10s Wi-Fi unvalidated evaluation job")
                    }
                }
                pendingEvaluationJobs[key] = job
            }
        }
    }

    @VisibleForTesting
    internal fun restorePrimaryStrictDot(context: Context) {
        synchronized(stateLock) {
            stateEpoch.incrementAndGet()
            activeMode = DnsResilienceMode.PRIMARY_STRICT_DOT
            overrideDotHost = null
            protectedWifiNetworkId = null
            isProtectedNetCaptivePortal = false
            DnsResilienceNetworkRouter.activeRecoveryJob?.cancel()
            DnsResilienceNetworkRouter.activeRecoveryJob = null
            DnsResilienceNetworkRouter.activeCaptivePortalRecoveryJob?.cancel()
            DnsResilienceNetworkRouter.activeCaptivePortalRecoveryJob = null
        }
        resilienceScope.launch {
            try {
                evaluationMutex.withLock {
                    ProtectionTransitionGuard.runInternalTransition {
                        try {
                            CurfewVpnDnsBridge.reconcileNonBlackholeVpnAndDns(context)
                            Timber.tag(TAG).i("Cleanly restored PRIMARY_STRICT_DOT after leaving restricted Wi-Fi")
                        } catch (e: Exception) {
                            Timber.tag(TAG).w(e, "Error restoring PRIMARY_STRICT_DOT")
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.tag(TAG).w(e, "Unhandled error restoring PRIMARY_STRICT_DOT")
            }
        }
    }

    private fun applyDnsShieldSafely(context: Context) {
        applyDnsShieldOverride?.invoke(context)?.let { return }
        try {
            SafeDpmGateway.executeRawBlocking(
                context = context,
                tag = BlackBoxRecorder.TAG_SHIELD,
                actionName = "resilienceDnsShieldApply"
            ) { dpm, admin ->
                DnsShield.apply(context, dpm, admin)
            }
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Failed executing DnsShield.apply in resilience update")
        }
    }

    /**
     * Network-bound Anti-Spoof Internet Verification.
     * Checks if the Wi-Fi network has general outbound internet connectivity via TCP Port 443.
     */
    private fun checkWifiHasPhysicalInternet(wifiNetwork: Network): Boolean {
        internetProbeOverride?.let { return it(wifiNetwork) }
        val testEndpoints = listOf(
            InetSocketAddress("1.1.1.3", 443),
            InetSocketAddress("94.140.14.15", 443)
        )
        for (endpoint in testEndpoints) {
            var socket: Socket? = null
            try {
                socket = wifiNetwork.socketFactory.createSocket()
                socket.soTimeout = ANTI_SPOOF_TIMEOUT_MS
                socket.connect(endpoint, ANTI_SPOOF_TIMEOUT_MS)
                Timber.tag(TAG).d("Anti-Spoof probe succeeded: Wi-Fi has physical internet via ${endpoint.hostString}:443")
                return true
            } catch (_: Exception) {
                // Try next endpoint
            } finally {
                try {
                    socket?.close()
                } catch (_: Exception) {}
            }
        }
        return false
    }

    /**
     * Network-bound TLS DoT Connectivity Probe (Port 853).
     */
    private fun testDotHostOnNetwork(
        wifiNetwork: Network,
        host: String,
        families: LinkIpFamilies = LinkIpFamilies(hasIpv4 = true, hasIpv6 = false)
    ): Boolean {
        return DnsResilienceProbeClient.testDotHostOnNetwork(
            network = wifiNetwork,
            host = host,
            families = families,
            dotProbeOverride = dotProbeOverride
        )
    }
}
