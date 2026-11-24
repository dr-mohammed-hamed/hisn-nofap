package com.drmohammed.antiporn.core.network.dns

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap

/**
 * Handles physical network transition routing, multi-network default guards,
 * 10-second unvalidated debounce jobs, and external Private DNS drift fast-tracking
 * for [DnsResilienceManager].
 */
internal class DnsResilienceNetworkRouter(
    private val scope: CoroutineScope,
    private val cooldownMs: Long,
    private val driftFastTrackDelayMs: Long
) {

    companion object {
        private const val TAG = "AntiPorn_DnsNetRouter"
        private const val UNVALIDATED_DEBOUNCE_MS = 10_000L
    }

    private val pendingJobsLock = Any()
    private val pendingEvaluationJobs = ConcurrentHashMap<String, Job>()

    @Volatile
    private var lastImmediateDriftEscalatedNetId: String? = null

    fun cancelAll() {
        synchronized(pendingJobsLock) {
            pendingEvaluationJobs.values.forEach { it.cancel() }
            pendingEvaluationJobs.clear()
        }
        lastImmediateDriftEscalatedNetId = null
    }

    fun cancelForNetwork(netId: String) {
        synchronized(pendingJobsLock) {
            pendingEvaluationJobs.remove(netId)?.cancel()
        }
    }

    fun isTargetNetworkPrimaryOrSolePhysical(context: Context, targetNetwork: Network): Boolean {
        val cm = DnsResilienceManager.getConnectivityManager(context)
        val targetId = DnsResilienceManager.resolveNetworkId(targetNetwork)
        val activeNet = try {
            DnsResilienceManager.activeNetworkProvider?.invoke(context) ?: cm?.activeNetwork
        } catch (_: Exception) {
            null
        }
        if (activeNet != null) {
            val activeTransport = DnsResilienceManager.resolvePhysicalTransportLabel(context, cm, activeNet)
            if (activeTransport != null && activeTransport != "VPN") {
                if (DnsResilienceManager.resolveNetworkId(activeNet) == targetId) return true
                if (DnsResilienceManager.isNetworkValidated(cm, activeNet)) return false
            }
        }
        val physicalNets = DnsResilienceManager.getAllPhysicalNetworks(context, cm)
        return physicalNets.none { other ->
            DnsResilienceManager.resolveNetworkId(other) != targetId &&
                DnsResilienceManager.isNetworkValidated(cm, other)
        }
    }

    fun onDpmHostNotServing(
        context: Context,
        failedHost: String,
        lastProbeElapsedMs: Long,
        lastProbedNetworkId: String?
    ) {
        val cm = DnsResilienceManager.getConnectivityManager(context)
        val activeNet = try {
            DnsResilienceManager.activeNetworkProvider?.invoke(context) ?: cm?.activeNetwork
        } catch (_: Exception) {
            null
        }
        val targetNet = if (activeNet != null &&
            DnsResilienceManager.resolvePhysicalTransportLabel(context, cm, activeNet) in DnsResilienceManager.SUPPORTED_TRANSPORTS
        ) {
            activeNet
        } else {
            DnsResilienceManager.getAllPhysicalNetworks(context, cm).firstOrNull { net ->
                DnsResilienceManager.resolvePhysicalTransportLabel(context, cm, net) in DnsResilienceManager.SUPPORTED_TRANSPORTS
            }
        } ?: return

        val netId = DnsResilienceManager.resolveNetworkId(targetNet)
        val now = DnsResilienceManager.getElapsedRealtime()
        if ((now - lastProbeElapsedMs) < cooldownMs && lastProbedNetworkId == netId) {
            Timber.tag(TAG).d("onDpmHostNotServing debounced for $failedHost on $netId")
            return
        }
        val transport = DnsResilienceManager.resolvePhysicalTransportLabel(context, cm, targetNet) ?: return
        if (transport in DnsResilienceManager.SUPPORTED_TRANSPORTS &&
            isTargetNetworkPrimaryOrSolePhysical(context, targetNet)
        ) {
            DnsResilienceManager.evaluateNetworkUnvalidatedOrDotError(
                context,
                targetNet,
                transport,
                "dpm_host_not_serving:$failedHost"
            )
        }
    }

    fun onExternalDnsResetWhileUnvalidated(
        context: Context,
        network: Network,
        transport: String,
        driftCount60s: Int,
        resetCooldownForNewNet: () -> Unit
    ): Boolean {
        if (transport !in DnsResilienceManager.SUPPORTED_TRANSPORTS) return false
        val snap = DnsResilienceManager.getRuntimeSnapshot()
        if (snap.mode != DnsResilienceManager.DnsResilienceMode.PRIMARY_STRICT_DOT || snap.isRestoringPrimary) {
            return false
        }
        val cm = DnsResilienceManager.getConnectivityManager(context)
        if (DnsResilienceManager.isNetworkValidated(cm, network) ||
            !isTargetNetworkPrimaryOrSolePhysical(context, network)
        ) {
            return false
        }

        val key = DnsResilienceManager.resolveNetworkId(network)
        return if (driftCount60s <= 1) {
            schedulePendingEvaluation(key, driftFastTrackDelayMs) {
                val freshCm = DnsResilienceManager.getConnectivityManager(context)
                val freshTransport = DnsResilienceManager.resolvePhysicalTransportLabel(context, freshCm, network)
                    ?: if (DnsResilienceManager.physicalTransportProvider == null && freshCm != null) return@schedulePendingEvaluation else transport
                if (freshTransport in DnsResilienceManager.SUPPORTED_TRANSPORTS &&
                    !DnsResilienceManager.isNetworkValidated(freshCm, network) &&
                    isTargetNetworkPrimaryOrSolePhysical(context, network)
                ) {
                    DnsResilienceManager.evaluateNetworkUnvalidatedOrDotError(
                        context,
                        network,
                        freshTransport,
                        "external_dns_drift_fasttrack_1500ms"
                    )
                }
            }
            false
        } else {
            cancelForNetwork(key)
            if (lastImmediateDriftEscalatedNetId != key) {
                resetCooldownForNewNet()
                lastImmediateDriftEscalatedNetId = key
            }
            DnsResilienceManager.evaluateNetworkUnvalidatedOrDotError(
                context,
                network,
                transport,
                "external_dns_drift_unvalidated:count=$driftCount60s"
            )
            true
        }
    }

    fun onNetworkChanged(
        context: Context,
        transitionType: String,
        network: Network?,
        transport: String?,
        restorePrimary: (String) -> Unit
    ) {
        val netId = network?.let(DnsResilienceManager::resolveNetworkId)
        val snap = DnsResilienceManager.getRuntimeSnapshot()
        Timber.tag(TAG).d(
            "onNetworkChanged: transition=$transitionType, net=$netId, transport=$transport, mode=${snap.mode}, prot=${snap.protectedNetworkId}"
        )

        if (snap.mode != DnsResilienceManager.DnsResilienceMode.PRIMARY_STRICT_DOT) {
            val protectedNet = snap.protectedNetworkId
            val protTransport = snap.protectedTransport

            if (netId != null && netId == protectedNet && transitionType.startsWith("net_validated")) {
                return
            }
            if (netId != null && netId == protectedNet && transitionType.startsWith("net_lost")) {
                restorePrimary("protected_net_lost:${protTransport ?: transport}:$netId")
                return
            }
            if (network != null &&
                netId != protectedNet &&
                (transport == "WIFI" || transport == "ETHERNET") &&
                !transitionType.startsWith("net_lost") &&
                (protTransport == "CELLULAR" || protTransport == "WIFI")
            ) {
                val cm = DnsResilienceManager.getConnectivityManager(context)
                val isValidatedEvent = transitionType.startsWith("net_validated")
                val isActiveDefault = try {
                    val activeNet = DnsResilienceManager.activeNetworkProvider?.invoke(context) ?: cm?.activeNetwork
                    activeNet != null && DnsResilienceManager.resolveNetworkId(activeNet) == netId &&
                        DnsResilienceManager.resolvePhysicalTransportLabel(context, cm, activeNet) == transport
                } catch (_: Exception) {
                    false
                }
                if (isValidatedEvent || (protTransport == "CELLULAR" && isActiveDefault)) {
                    Timber.tag(TAG).i("Preferred physical network $transport ($netId) active/validated; restoring PRIMARY_STRICT_DOT")
                    restorePrimary("preferred_transport_active:$transport:$netId")
                    return
                }
            }
            if (protTransport == "WIFI" && transport == "CELLULAR") {
                if (netId != null && transitionType.startsWith("net_lost")) cancelForNetwork(netId)
                return
            }
        }

        if (transport == null || transport !in DnsResilienceManager.SUPPORTED_TRANSPORTS || network == null) return
        val key = netId ?: network.toString()

        if (transitionType.startsWith("net_validated") || transitionType.startsWith("net_lost")) {
            cancelForNetwork(key)
            if (transitionType.startsWith("net_lost") && lastImmediateDriftEscalatedNetId == key) {
                lastImmediateDriftEscalatedNetId = null
            }
            return
        }
        if (transitionType.startsWith("net_captive_portal")) {
            cancelForNetwork(key)
            if (isTargetNetworkPrimaryOrSolePhysical(context, network)) {
                DnsResilienceManager.evaluateNetworkUnvalidatedOrDotError(
                    context,
                    network,
                    transport,
                    "${transport.lowercase()}_captive_portal_immediate"
                )
            }
            return
        }
        if (transitionType.startsWith("net_unvalidated") || transitionType.startsWith("net_available")) {
            if (!isTargetNetworkPrimaryOrSolePhysical(context, network)) return
            schedulePendingEvaluation(key, UNVALIDATED_DEBOUNCE_MS) {
                val cm = DnsResilienceManager.getConnectivityManager(context)
                val currentTransport = DnsResilienceManager.resolvePhysicalTransportLabel(context, cm, network)
                    ?: if (DnsResilienceManager.physicalTransportProvider == null && cm != null) return@schedulePendingEvaluation else transport
                if (currentTransport in DnsResilienceManager.SUPPORTED_TRANSPORTS &&
                    !DnsResilienceManager.isNetworkValidated(cm, network) &&
                    isTargetNetworkPrimaryOrSolePhysical(context, network)
                ) {
                    DnsResilienceManager.evaluateNetworkUnvalidatedOrDotError(
                        context,
                        network,
                        currentTransport,
                        "${currentTransport.lowercase()}_unvalidated_timeout_10s"
                    )
                }
            }
        }
    }

    private fun schedulePendingEvaluation(key: String, delayMs: Long, block: suspend () -> Unit) {
        synchronized(pendingJobsLock) {
            pendingEvaluationJobs.remove(key)?.cancel()
            val job = scope.launch {
                val override = DnsResilienceManager.delayOverride
                if (override != null) override.invoke(delayMs) else delay(delayMs)
                block()
            }
            pendingEvaluationJobs[key] = job
            job.invokeOnCompletion { pendingEvaluationJobs.remove(key, job) }
        }
    }

    fun getElapsedRealtime(): Long = try {
        android.os.SystemClock.elapsedRealtime()
    } catch (_: RuntimeException) {
        System.currentTimeMillis()
    }

    fun getConnectivityManager(context: Context): ConnectivityManager? = try {
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
    } catch (_: RuntimeException) {
        null
    }

    fun resolvePhysicalTransportLabel(context: Context, cm: ConnectivityManager?, network: Network): String? {
        DnsResilienceManager.physicalTransportProvider?.invoke(context, network)?.let { return it }
        val caps = try {
            cm?.getNetworkCapabilities(network)
        } catch (e: Exception) {
            Timber.tag(TAG).d(e, "Failed reading NetworkCapabilities for transport label")
            null
        } ?: return null
        return com.drmohammed.antiporn.core.network.PhysicalNetworkResolver.resolveTransportLabel(caps)
    }

    fun getAllPhysicalNetworks(context: Context, cm: ConnectivityManager?): List<Network> {
        DnsResilienceManager.allPhysicalNetworksProvider?.invoke(context)?.let { return it }
        if (cm == null) return emptyList()
        return com.drmohammed.antiporn.core.network.PhysicalNetworkResolver.getAllPhysicalInternetNetworks(cm)
    }

    fun isNetworkValidated(cm: ConnectivityManager?, network: Network): Boolean {
        DnsResilienceManager.isNetworkValidatedChecker?.let { return it(network) }
        DnsResilienceManager.isWifiValidatedChecker?.let { return it(network) }
        return try {
            cm?.getNetworkCapabilities(network)?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
        } catch (e: Exception) {
            Timber.tag(TAG).d(e, "Failed checking NET_CAPABILITY_VALIDATED")
            false
        }
    }

    fun isNetworkCaptivePortal(cm: ConnectivityManager?, network: Network): Boolean {
        DnsResilienceManager.isNetworkCaptivePortalChecker?.let { return it(network) }
        DnsResilienceManager.isWifiCaptivePortalChecker?.let { return it(network) }
        return try {
            cm?.getNetworkCapabilities(network)?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL) == true
        } catch (e: Exception) {
            Timber.tag(TAG).d(e, "Failed checking NET_CAPABILITY_CAPTIVE_PORTAL")
            false
        }
    }
}
