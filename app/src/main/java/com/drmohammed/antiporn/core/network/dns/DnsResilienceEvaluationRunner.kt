package com.drmohammed.antiporn.core.network.dns

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import com.drmohammed.antiporn.core.engine.ProtectionTransitionGuard
import com.drmohammed.antiporn.curfew.CurfewBlackholeVpnService
import com.drmohammed.antiporn.curfew.CurfewEnforcer
import com.drmohammed.antiporn.curfew.CurfewVpnDnsBridge
import com.drmohammed.antiporn.shields.DnsShield
import com.drmohammed.antiporn.telemetry.BlackBoxRecorder
import com.drmohammed.antiporn.telemetry.SafeDpmGateway
import kotlinx.coroutines.delay
import timber.log.Timber

/**
 * Executes the 3-tier transport-agnostic DNS resilience evaluation pipeline for [DnsResilienceManager].
 * Guards every state mutation against concurrent teardown, network loss, and epoch changes.
 */
internal object DnsResilienceEvaluationRunner {

    private const val TAG = "AntiPorn_DnsResEval"
    private const val PROBE_COOLDOWN_MS = 15_000L
    private const val STAGE_A_PROBE_BUDGET_MS = 8_000L
    private const val LEVEL2_VALIDATION_GRACE_MS = 6_000L

    internal interface HostCallbacks {
        fun currentEpoch(): Long
        fun getSnapshot(): DnsResilienceManager.ResilienceRuntimeSnapshot
        fun isInCooldown(netId: String, nowMs: Long): Boolean
        fun markProbeAttempt(netId: String, nowMs: Long)
        fun activateLevel2(
            context: Context,
            overrideHost: String,
            netId: String,
            transport: String
        )
        fun activateLevel3(
            context: Context,
            netId: String,
            transport: String,
            triggerClass: DnsResilienceManager.FailoverTriggerClass
        )
        fun rollbackToPrimarySync(context: Context, netId: String, transport: String, reason: String)
        fun applyDnsShieldSafely(context: Context)
    }

    suspend fun evaluate(
        context: Context,
        network: Network,
        transport: String,
        triggerReason: String,
        host: HostCallbacks
    ) {
        val entryEpoch = host.currentEpoch()
        DnsResilienceManager.ensureHydrated(context)
        val cm = DnsResilienceManager.getConnectivityManager(context)

        val liveTransport = DnsResilienceManager.resolvePhysicalTransportLabel(context, cm, network)
        if (liveTransport == null && DnsResilienceManager.physicalTransportProvider == null && cm != null) {
            Timber.tag(TAG).d("Target network already disconnected before evaluation; aborting")
            return
        }
        val resolvedTransport = liveTransport?.takeIf { it in DnsResilienceManager.SUPPORTED_TRANSPORTS }
            ?: transport
        if (resolvedTransport !in DnsResilienceManager.SUPPORTED_TRANSPORTS) {
            Timber.tag(TAG).d("Unsupported transport $resolvedTransport for resilience evaluation; skipping")
            return
        }

        val netId = DnsResilienceManager.resolveNetworkId(network)
        val initialSnap = host.getSnapshot()
        if (initialSnap.protectedNetworkId == netId &&
            initialSnap.mode == DnsResilienceManager.DnsResilienceMode.FALLBACK_LOCAL_VPN_DOH
        ) {
            Timber.tag(TAG).d("Network $netId ($resolvedTransport) is already protected in Level 3 Fallback; skipping")
            return
        }

        val now = DnsResilienceManager.getElapsedRealtime()
        if (host.isInCooldown(netId, now)) {
            Timber.tag(TAG).d("Resilience evaluation in cooldown for $netId ($resolvedTransport)")
            return
        }

        val isCurfewOrVpnActive = DnsResilienceManager.curfewOrVpnActiveChecker?.invoke(context) ?: try {
            CurfewBlackholeVpnService.evaluateRequiredMode(context) != CurfewBlackholeVpnService.VpnMode.IDLE ||
                CurfewEnforcer.isEffectiveBlockActive(context)
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Failed checking Curfew/VPN pre-condition in resilience evaluation")
            false
        }
        if (isCurfewOrVpnActive) {
            Timber.tag(TAG).d("Curfew or non-idle VPN is active; DnsResilienceManager will not intervene.")
            return
        }

        if (DnsResilienceManager.isNetworkValidated(cm, network)) {
            Timber.tag(TAG).d("Network $netId ($resolvedTransport) is already validated; skipping resilience intervention.")
            return
        }
        if (!DnsResilienceManager.isTargetNetworkPrimaryOrSolePhysical(context, network)) {
            Timber.tag(TAG).d("Ignoring unvalidated background network net=$netId ($resolvedTransport)")
            return
        }

        host.markProbeAttempt(netId, now)
        Timber.tag(TAG).w("Evaluating $resolvedTransport resilience for net=$netId (trigger=$triggerReason, activeMode=${initialSnap.mode})")

        val families = DnsResilienceProbeClient.detectLinkFamilies(cm, network)
        val isCaptive = DnsResilienceManager.isNetworkCaptivePortal(cm, network)
        val hasInternet = isCaptive || DnsResilienceProbeClient.verifyPhysicalInternetAndDohReachable(
            network = network,
            families = families,
            internetProbeOverride = DnsResilienceManager.internetProbeOverride
        )

        val targetPolicyHost = resolvePolicyHost(context)
        if (isAbortedByStateOrDisconnect(host, entryEpoch, cm, network)) return

        if (!hasInternet) {
            handleOfflineNetwork(context, netId, resolvedTransport, triggerReason, isCaptive, families, targetPolicyHost)
            return
        }

        val isPrimaryWorking = !isCaptive && DnsResilienceProbeClient.testDotHostOnNetwork(
            network = network,
            host = targetPolicyHost,
            families = families,
            dotProbeOverride = DnsResilienceManager.dotProbeOverride
        )
        recordProbeTelemetry(context, netId, resolvedTransport, triggerReason, isCaptive, families, isPrimaryWorking)

        if (isAbortedByStateOrDisconnect(host, entryEpoch, cm, network)) return
        if (DnsResilienceManager.isNetworkValidated(cm, network)) {
            Timber.tag(TAG).i("Primary DoT host $targetPolicyHost validated on $netId ($resolvedTransport)")
            return
        }

        var skipStageA = false
        val resolvedTriggerClass = when {
            isCaptive -> DnsResilienceManager.FailoverTriggerClass.CAPTIVE_PORTAL
            isPrimaryWorking -> {
                Timber.tag(TAG).w("Primary DoT connected on Java, but OS validated=false ($resolvedTransport); skipping Stage A to Level 3.")
                skipStageA = true
                DnsResilienceManager.FailoverTriggerClass.OS_DOT_VALIDATION_QUIRK
            }
            else -> DnsResilienceManager.FailoverTriggerClass.PORT_853_BLOCKED
        }

        if (!isCaptive && !skipStageA) {
            val handledInStageA = tryStageAAlternateDot(
                context = context,
                cm = cm,
                network = network,
                netId = netId,
                resolvedTransport = resolvedTransport,
                targetPolicyHost = targetPolicyHost,
                families = families,
                entryEpoch = entryEpoch,
                host = host
            )
            if (handledInStageA) return
            if (isAbortedByStateOrDisconnect(host, entryEpoch, cm, network)) return
        }

        escalateToLevel3(
            context = context,
            netId = netId,
            resolvedTransport = resolvedTransport,
            triggerReason = triggerReason,
            resolvedTriggerClass = resolvedTriggerClass,
            targetPolicyHost = targetPolicyHost,
            entryEpoch = entryEpoch,
            cm = cm,
            network = network,
            host = host
        )
    }

    private suspend fun tryStageAAlternateDot(
        context: Context,
        cm: ConnectivityManager?,
        network: Network,
        netId: String,
        resolvedTransport: String,
        targetPolicyHost: String,
        families: LinkIpFamilies,
        entryEpoch: Long,
        host: HostCallbacks
    ): Boolean {
        val stageADeadlineMs = DnsResilienceManager.getElapsedRealtime() + STAGE_A_PROBE_BUDGET_MS
        val candidates = DnsResilienceProbeClient.ALTERNATE_FAMILY_PRESETS.filterNot {
            it.equals(targetPolicyHost, ignoreCase = true)
        }
        for (candidate in candidates) {
            if (isAbortedByStateOrDisconnect(host, entryEpoch, cm, network)) return true
            if (DnsResilienceManager.getElapsedRealtime() >= stageADeadlineMs) {
                Timber.tag(TAG).w("Stage A probe budget exceeded for $netId; escalating to Stage B")
                break
            }
            if (DnsResilienceProbeClient.testDotHostOnNetwork(
                    network = network,
                    host = candidate,
                    families = families,
                    dotProbeOverride = DnsResilienceManager.dotProbeOverride
                )
            ) {
                if (isAbortedByStateOrDisconnect(host, entryEpoch, cm, network)) return true
                Timber.tag(TAG).i("Alternate Family DoT $candidate works on $resolvedTransport $netId; activating Level 2")
                host.activateLevel2(context, candidate, netId, resolvedTransport)
                recordNetworkSafe(
                    context = context,
                    action = "DNS_FAILOVER_ALTERNATE_DOT",
                    isSuccess = true,
                    details = "net=$netId, transport=$resolvedTransport, primary=$targetPolicyHost, fallback=$candidate"
                )
                host.applyDnsShieldSafely(context)

                val delayFn = DnsResilienceManager.delayOverride
                if (delayFn != null) delayFn.invoke(LEVEL2_VALIDATION_GRACE_MS) else delay(LEVEL2_VALIDATION_GRACE_MS)

                if (isAbortedByStateOrDisconnect(host, entryEpoch, cm, network)) {
                    Timber.tag(TAG).i("Aborting post-grace escalation for $netId: state epoch changed or network disconnected")
                    return true
                }
                if (DnsResilienceManager.isNetworkValidated(cm, network)) {
                    Timber.tag(TAG).i("$resolvedTransport $netId validated under alternate DoT $candidate; staying at Level 2")
                    return true
                }
                Timber.tag(TAG).w("$resolvedTransport $netId unvalidated after 6s under alternate DoT $candidate; escalating to Level 3")
                break
            }
        }
        return false
    }

    private fun escalateToLevel3(
        context: Context,
        netId: String,
        resolvedTransport: String,
        triggerReason: String,
        resolvedTriggerClass: DnsResilienceManager.FailoverTriggerClass,
        targetPolicyHost: String,
        entryEpoch: Long,
        cm: ConnectivityManager?,
        network: Network,
        host: HostCallbacks
    ) {
        if (isAbortedByStateOrDisconnect(host, entryEpoch, cm, network)) return
        Timber.tag(TAG).w("Escalating $resolvedTransport $netId ($resolvedTriggerClass) to Level 3 FALLBACK_LOCAL_VPN_DOH")

        val isDeviceOwner = DnsResilienceManager.isDeviceOwnerChecker?.invoke(context) ?: SafeDpmGateway.isDeviceOwner(context)
        if (!isDeviceOwner) {
            Timber.tag(TAG).e("Cannot activate Level 3 fallback: not Device Owner; restoring PRIMARY_STRICT_DOT")
            host.rollbackToPrimarySync(context, netId, resolvedTransport, "not_device_owner")
            return
        }

        val filterReady = try {
            DnsResilienceManager.binaryFilterInitOverride?.invoke(context) ?: BinaryDomainFilter.init(context)
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "BinaryDomainFilter initialization failed before Level 3 activation")
            false
        }
        if (!filterReady) {
            Timber.tag(TAG).e("BinaryDomainFilter failed to initialize; aborting Level 3 escalation (Invariant #1)")
            host.rollbackToPrimarySync(context, netId, resolvedTransport, "binary_filter_failed")
            return
        }

        if (isAbortedByStateOrDisconnect(host, entryEpoch, cm, network)) return
        host.activateLevel3(context, netId, resolvedTransport, resolvedTriggerClass)
        recordNetworkSafe(
            context = context,
            action = "DNS_FAILOVER_LOCAL_VPN_DOH",
            isSuccess = true,
            details = "net=$netId, transport=$resolvedTransport, triggerClass=$resolvedTriggerClass, reason=$triggerReason, dohPort=443"
        )

        val result = DnsResilienceManager.level3ActivationOverride?.invoke(context, targetPolicyHost)
            ?: ProtectionTransitionGuard.runInternalTransition {
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
            Timber.tag(TAG).e("Level 3 VPN activation failed; rolling back to PRIMARY_STRICT_DOT")
            host.rollbackToPrimarySync(context, netId, resolvedTransport, "level3_activation_failed")
        } else {
            Timber.tag(TAG).i("Level 3 Local VPN DNS + DoH 443 activated for $resolvedTransport $netId")
        }
    }

    private fun handleOfflineNetwork(
        context: Context,
        netId: String,
        resolvedTransport: String,
        triggerReason: String,
        isCaptive: Boolean,
        families: LinkIpFamilies,
        targetPolicyHost: String
    ) {
        Timber.tag(TAG).w("Network $netId ($resolvedTransport) has NO physical internet. Preserving PRIMARY_STRICT_DOT.")
        recordNetworkSafe(
            context = context,
            action = "DNS_RESILIENCE_PROBE",
            isSuccess = false,
            details = "net=$netId, transport=$resolvedTransport, trigger=$triggerReason, captive=$isCaptive, tls443=false, v4=${families.hasIpv4}, v6=${families.hasIpv6}"
        )
        val isDo = DnsResilienceManager.isDeviceOwnerChecker?.invoke(context) ?: SafeDpmGateway.isDeviceOwner(context)
        if (isDo) {
            CurfewVpnDnsBridge.preArmStandaloneDotFailClosed(context, targetPolicyHost)
        }
    }

    private fun recordProbeTelemetry(
        context: Context,
        netId: String,
        resolvedTransport: String,
        triggerReason: String,
        isCaptive: Boolean,
        families: LinkIpFamilies,
        isPrimaryWorking: Boolean
    ) {
        recordNetworkSafe(
            context = context,
            action = "DNS_RESILIENCE_PROBE",
            isSuccess = true,
            details = "net=$netId, transport=$resolvedTransport, trigger=$triggerReason, captive=$isCaptive, tls443=true, v4=${families.hasIpv4}, v6=${families.hasIpv6}, primaryDot=$isPrimaryWorking"
        )
    }

    private fun resolvePolicyHost(context: Context): String {
        val fallback = DnsShield.DEFAULT_DNS_HOST
        return try {
            DnsResilienceManager.policyHostProvider?.invoke(context)
                ?: com.drmohammed.antiporn.data.repository.PolicyRepository.getInstance(context)
                    .getCurrentPolicy().dnsHost.takeIf { it.isNotBlank() }
                ?: fallback
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Failed resolving target policy DNS host; using default")
            fallback
        }
    }

    private fun isAbortedByStateOrDisconnect(
        host: HostCallbacks,
        entryEpoch: Long,
        cm: ConnectivityManager?,
        network: Network
    ): Boolean {
        if (host.currentEpoch() != entryEpoch || ProtectionTransitionGuard.isTeardownInProgress) {
            return true
        }
        if (DnsResilienceManager.physicalTransportProvider == null && cm != null) {
            val stillConnected = try {
                cm.getNetworkCapabilities(network) != null
            } catch (_: Exception) {
                false
            }
            if (!stillConnected) return true
        }
        return false
    }

    private fun recordNetworkSafe(context: Context, action: String, isSuccess: Boolean, details: String) {
        try {
            BlackBoxRecorder.recordNetwork(context = context, action = action, isSuccess = isSuccess, details = details)
        } catch (e: Exception) {
            Timber.tag(TAG).d(e, "BlackBoxRecorder.recordNetwork skipped/failed for $action")
        }
    }

    internal const val DEFAULT_COOLDOWN_MS = PROBE_COOLDOWN_MS
}
