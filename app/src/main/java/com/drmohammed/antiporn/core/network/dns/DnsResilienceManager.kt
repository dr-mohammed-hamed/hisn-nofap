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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

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
    private const val DOT_PROBE_TIMEOUT_MS = 2_000

    private val ALTERNATE_FAMILY_PRESETS = listOf(
        DevicePolicyBundle.ADGUARD_FAMILY_DNS_HOST,
        DevicePolicyBundle.CLOUDFLARE_FAMILY_DNS_HOST,
        DevicePolicyBundle.CONTROLD_NO_ADS_PORN_DNS_HOST,
        "family-filter-dns.cleanbrowsing.org"
    )

    private val PRESET_BOOTSTRAP_IPV4 = mapOf(
        "family.cloudflare-dns.com" to "1.1.1.3",
        "family.adguard-dns.com" to "94.140.14.15",
        "family-filter-dns.cleanbrowsing.org" to "185.228.168.168",
        "family.controld.com" to "76.76.2.3"
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

    private val stateLock = Any()
    @Volatile
    private var activeMode: DnsResilienceMode = DnsResilienceMode.PRIMARY_STRICT_DOT
    @Volatile
    private var overrideDotHost: String? = null
    @Volatile
    private var protectedWifiNetworkId: String? = null
    @Volatile
    private var lastProbeElapsedMs: Long = 0L

    fun getActiveMode(): DnsResilienceMode = activeMode

    fun isLocalVpnFallbackActive(): Boolean =
        activeMode == DnsResilienceMode.FALLBACK_LOCAL_VPN_DOH

    fun getEffectiveRuntimeDnsHost(policyHost: String): String {
        return if (activeMode == DnsResilienceMode.FALLBACK_STRICT_DOT && !overrideDotHost.isNullOrBlank()) {
            overrideDotHost!!
        } else {
            policyHost
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
            activeMode = DnsResilienceMode.PRIMARY_STRICT_DOT
            overrideDotHost = null
            protectedWifiNetworkId = null
            lastProbeElapsedMs = 0L
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
            evaluationMutex.withLock {
                evaluateWifiUnvalidatedOrDotErrorInternal(context, wifiNetwork, triggerReason)
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

    private suspend fun evaluateWifiUnvalidatedOrDotErrorInternal(
        context: Context,
        wifiNetwork: Network,
        triggerReason: String
    ) {
        val netId = resolveNetworkId(wifiNetwork)
        val currentProtId = protectedWifiNetworkId
        if (currentProtId == netId && activeMode == DnsResilienceMode.FALLBACK_LOCAL_VPN_DOH) {
            Timber.tag(TAG).d("Wi-Fi network $netId is already protected in Level 3 Fallback; skipping duplicate evaluation")
            return
        }

        val now = getElapsedRealtime()
        if (now - lastProbeElapsedMs < PROBE_COOLDOWN_MS && currentProtId == netId) {
            Timber.tag(TAG).d("Resilience evaluation in cooldown for $netId")
            return
        }
        lastProbeElapsedMs = now

        Timber.tag(TAG).w("Evaluating Wi-Fi resilience for net=$netId (trigger=$triggerReason, activeMode=$activeMode)")

        // Pre-condition: Strictly do NOT intervene during Curfew Blackhole or active non-idle VPN!
        val isCurfewOrVpnActive = curfewOrVpnActiveChecker?.invoke(context) ?: try {
            CurfewBlackholeVpnService.evaluateRequiredMode(context) != CurfewBlackholeVpnService.VpnMode.IDLE ||
            com.drmohammed.antiporn.curfew.CurfewEnforcer.isEffectiveBlockActive(context)
        } catch (_: Exception) {
            false
        }
        if (isCurfewOrVpnActive) {
            Timber.tag(TAG).d("Curfew or non-idle VPN is active; DnsResilienceManager will not intervene.")
            return
        }

        // 1. Fresh NetworkCapabilities check (Avoid stale cache on healthy devices)
        val cm = getConnectivityManager(context)
        val initialValidated = isWifiValidatedChecker?.invoke(wifiNetwork)
            ?: (cm?.getNetworkCapabilities(wifiNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true)
        if (initialValidated) {
            Timber.tag(TAG).d("Wi-Fi network $netId is already validated; skipping resilience intervention.")
            return
        }

        // Anti-Spoofing Internet Verification Probe (Strictly bound to wifiNetwork)
        val isCaptive = isWifiCaptivePortalChecker?.invoke(wifiNetwork)
            ?: (cm?.getNetworkCapabilities(wifiNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL) == true)
        val hasInternet = isCaptive || checkWifiHasPhysicalInternet(wifiNetwork)

        if (!hasInternet) {
            Timber.tag(TAG).w("Wi-Fi network $netId has NO physical internet (router offline / ISP outage). Preserving PRIMARY_STRICT_DOT.")
            return
        }

        val policyHost = DnsShield.DEFAULT_DNS_HOST
        val targetPolicyHost = try {
            policyHostProvider?.invoke(context)
                ?: com.drmohammed.antiporn.data.repository.PolicyRepository.getInstance(context)
                    .getCurrentPolicy().dnsHost.takeIf { it.isNotBlank() } ?: policyHost
        } catch (_: Exception) {
            policyHost
        }

        // 2. Test primary DoT host on wifiNetwork
        val isPrimaryWorking = !isCaptive && testDotHostOnNetwork(wifiNetwork, targetPolicyHost)

        // Re-read fresh capabilities after network probes
        val postPrimaryValidated = isWifiValidatedChecker?.invoke(wifiNetwork)
            ?: (cm?.getNetworkCapabilities(wifiNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true)
        if (postPrimaryValidated) {
            Timber.tag(TAG).i("Primary DoT host $targetPolicyHost is verified working and validated on $netId")
            return
        }

        var skipStageA = false
        if (isPrimaryWorking) {
            // Java DoT connects to targetPolicyHost, but Android 17 / Nothing OS reports validated=false!
            // Alternate DoT hosts will also fail in the OS resolver; escalate directly to Level 3.
            Timber.tag(TAG).w("Primary DoT host connected on Java, but OS reports validated=false on Android 17 / Nothing OS! Skipping Stage A and escalating directly to Level 3.")
            skipStageA = true
        }

        // 3. Stage A: Try Alternate Family DoT Presets (Level 2) if not captive and not Nothing OS quirk
        if (!isCaptive && !skipStageA) {
            val candidateHosts = ALTERNATE_FAMILY_PRESETS.filterNot { it.equals(targetPolicyHost, ignoreCase = true) }
            for (candidate in candidateHosts) {
                if (testDotHostOnNetwork(wifiNetwork, candidate)) {
                    Timber.tag(TAG).i("Alternate Family DoT host $candidate works on Wi-Fi $netId! Activating Level 2 FALLBACK_STRICT_DOT")
                    synchronized(stateLock) {
                        activeMode = DnsResilienceMode.FALLBACK_STRICT_DOT
                        overrideDotHost = candidate
                        protectedWifiNetworkId = netId
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
                    val postFallbackValidated = isWifiValidatedChecker?.invoke(wifiNetwork)
                        ?: (cm?.getNetworkCapabilities(wifiNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true)
                    if (postFallbackValidated) {
                        Timber.tag(TAG).i("Wi-Fi $netId successfully validated under alternate DoT $candidate; remaining at Level 2")
                        return
                    } else {
                        Timber.tag(TAG).w("Wi-Fi $netId remains unvalidated after 6s under alternate DoT $candidate; escalating to Level 3")
                        break // Stop trying alternate DoT; proceed directly to Level 3
                    }
                }
            }
        }

        // 4. Stage B: Level 3 Escalation - Port 853 Blocked or Android 17 OS Quirk (FALLBACK_LOCAL_VPN_DOH)
        Timber.tag(TAG).w("All DoT 853 failed or Wi-Fi unvalidated on $netId; escalating to Level 3 (FALLBACK_LOCAL_VPN_DOH + DoH 443)")
        val isDeviceOwner = isDeviceOwnerChecker?.invoke(context) ?: SafeDpmGateway.isDeviceOwner(context)
        if (!isDeviceOwner) {
            Timber.tag(TAG).e("Cannot activate Level 3 fallback: Application is not Device Owner; restoring PRIMARY_STRICT_DOT")
            synchronized(stateLock) {
                activeMode = DnsResilienceMode.PRIMARY_STRICT_DOT
                overrideDotHost = null
                protectedWifiNetworkId = null
            }
            applyDnsShieldSafely(context)
            return
        }

        // INVARIANT #1: BinaryDomainFilter MUST initialize successfully before opportunistic downgrade
        val filterReady = try {
            binaryFilterInitOverride?.invoke(context) ?: BinaryDomainFilter.init(context)
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "BinaryDomainFilter initialization threw exception before Level 3 activation")
            false
        }
        if (!filterReady) {
            Timber.tag(TAG).e("BinaryDomainFilter failed to initialize; strictly aborting Level 3 escalation to prevent porn leak (Invariant #1)")
            synchronized(stateLock) {
                activeMode = DnsResilienceMode.PRIMARY_STRICT_DOT
                overrideDotHost = null
                protectedWifiNetworkId = null
            }
            applyDnsShieldSafely(context)
            return
        }

        synchronized(stateLock) {
            activeMode = DnsResilienceMode.FALLBACK_LOCAL_VPN_DOH
            overrideDotHost = null
            protectedWifiNetworkId = netId
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

        if (!result) {
            Timber.tag(TAG).e("Failed to activate Level 3 VPN fallback safely; rolling back to PRIMARY_STRICT_DOT (Fail-Secure)")
            synchronized(stateLock) {
                activeMode = DnsResilienceMode.PRIMARY_STRICT_DOT
                protectedWifiNetworkId = null
            }
            applyDnsShieldSafely(context)
        } else {
            Timber.tag(TAG).i("Level 3 Local VPN DNS + DoH 443 activated successfully for Wi-Fi $netId")
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
        Timber.tag(TAG).d("onNetworkChanged: transition=$transitionType, net=$netId, transport=$transport, activeMode=$activeMode")

        // Mandatory Invariant 1 (Anti-Flapping):
        // Never tear down or flap when Cellular disconnects or when the protected Wi-Fi becomes validated
        if (activeMode != DnsResilienceMode.PRIMARY_STRICT_DOT) {
            val protectedNet = protectedWifiNetworkId
            if (transport == "CELLULAR" && transitionType.startsWith("net_lost")) {
                Timber.tag(TAG).d("Cellular disconnected while Wi-Fi is protected; preserving active resilience mode")
                return
            }
            if (netId == protectedNet && transitionType.startsWith("net_validated")) {
                Timber.tag(TAG).d("Protected Wi-Fi $netId is now validated under resilience mode; preserving protection")
                return
            }
            if (netId != null && netId == protectedNet && transitionType.startsWith("net_lost")) {
                Timber.tag(TAG).i("Protected Wi-Fi $netId was disconnected! Restoring PRIMARY_STRICT_DOT")
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
                    kotlinx.coroutines.delay(10_000L) // 10-second debounce window
                    val cm = getConnectivityManager(context)
                    val caps = cm?.getNetworkCapabilities(network)
                    if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                        !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                    ) {
                        evaluateWifiUnvalidatedOrDotError(context, network, "wifi_unvalidated_timeout_10s")
                    }
                }
                pendingEvaluationJobs[key] = job
            }
        }
    }

    private fun restorePrimaryStrictDot(context: Context) {
        synchronized(stateLock) {
            activeMode = DnsResilienceMode.PRIMARY_STRICT_DOT
            overrideDotHost = null
            protectedWifiNetworkId = null
        }
        resilienceScope.launch {
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
    private fun testDotHostOnNetwork(wifiNetwork: Network, host: String): Boolean {
        dotProbeOverride?.let { return it(wifiNetwork, host) }
        var rawSocket: Socket? = null
        var sslSocket: SSLSocket? = null
        return try {
            val resolvedAddr = try {
                wifiNetwork.getAllByName(host).firstOrNull { it is java.net.Inet4Address }
                    ?: wifiNetwork.getByName(host)
            } catch (e: Exception) {
                PRESET_BOOTSTRAP_IPV4[host.lowercase().trim()]?.let { ipStr ->
                    try {
                        java.net.InetAddress.getByName(ipStr)
                    } catch (_: Exception) { null }
                } ?: run {
                    Timber.tag(TAG).d("Failed resolving $host on wifiNetwork: ${e.message}")
                    return false
                }
            }

            rawSocket = wifiNetwork.socketFactory.createSocket()
            rawSocket.soTimeout = DOT_PROBE_TIMEOUT_MS
            rawSocket.connect(InetSocketAddress(resolvedAddr, 853), DOT_PROBE_TIMEOUT_MS)

            val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
            val wrapped = factory.createSocket(rawSocket, host, 853, true) as SSLSocket
            sslSocket = wrapped
            val sslParams = wrapped.sslParameters
            sslParams.serverNames = listOf(SNIHostName(host))
            sslParams.endpointIdentificationAlgorithm = "HTTPS"
            wrapped.sslParameters = sslParams
            wrapped.soTimeout = DOT_PROBE_TIMEOUT_MS
            wrapped.startHandshake()

            val session = wrapped.session
            val hostnameOk = HttpsURLConnection.getDefaultHostnameVerifier().verify(host, session)
            hostnameOk
        } catch (e: Exception) {
            Timber.tag(TAG).d("DoT probe failed for $host on network: ${e.message}")
            false
        } finally {
            try {
                sslSocket?.close()
            } catch (_: Exception) {}
            try {
                rawSocket?.close()
            } catch (_: Exception) {}
        }
    }
}
