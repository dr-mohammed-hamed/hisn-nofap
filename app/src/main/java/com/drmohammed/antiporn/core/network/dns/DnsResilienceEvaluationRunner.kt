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
import kotlinx.coroutines.CancellationException
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
    private const val LEVEL2_VALIDATION_POLL_MS = 500L
    private const val OS_QUIRK_RECHECK_GRACE_MS = 2_000L

    private data class StageAResult(
        val handled: Boolean,
        val attemptedLevel2Override: Boolean
    )

    internal interface HostCallbacks {
        fun currentEpoch(): Long
        fun getSnapshot(): DnsResilienceManager.ResilienceRuntimeSnapshot
        fun isInCooldown(netId: String, nowMs: Long): Boolean
        fun shouldSkipDueToExhaustedConsent(context: Context, netId: String, nowMs: Long): Boolean
        fun markProbeAttempt(netId: String, nowMs: Long)
        fun activateLevel2(
            context: Context,
            overrideHost: String,
            netId: String,
            transport: String
        )
        fun clearLevel2StateWithoutDpm(context: Context)
        fun activateLevel3(
            context: Context,
            netId: String,
            transport: String,
            triggerClass: DnsResilienceManager.FailoverTriggerClass
        )
        fun scheduleSameNetworkPrimaryRecovery(
            context: Context,
            network: Network,
            netId: String,
            targetPolicyHost: String,
            triggerReason: String
        )
        fun rollbackToPrimarySync(context: Context, netId: String, transport: String, reason: String)
        fun markExhaustedDotAndNotify(
            context: Context,
            netId: String,
            transport: String,
            triggerClass: DnsResilienceManager.FailoverTriggerClass,
            restorePrimaryDpm: Boolean
        )
        fun applyDnsShieldSafely(context: Context): Boolean
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

        val isDpmHostNotServingTrigger = triggerReason.startsWith("dpm_host_not_serving:")
        val now = DnsResilienceManager.getElapsedRealtime()
        if (host.shouldSkipDueToExhaustedConsent(context, netId, now)) {
            Timber.tag(TAG).d("Exhausted DoT awaiting consent for $netId ($resolvedTransport); skipping re-probe")
            return
        }
        if (!isDpmHostNotServingTrigger && host.isInCooldown(netId, now)) {
            Timber.tag(TAG).d("Resilience evaluation in cooldown for $netId ($resolvedTransport)")
            return
        }

        val isCurfewOrVpnActive = DnsResilienceManager.curfewOrVpnActiveChecker?.invoke(context) ?: try {
            CurfewEnforcer.isEffectiveBlockActive(context) ||
                CurfewBlackholeVpnService.isBlackoutActive(context) ||
                CurfewBlackholeVpnService.isTunnelHealthy(CurfewBlackholeVpnService.VpnMode.DNS_FILTERING_ONLY)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Timber.tag(TAG).w(e, "Failed checking Curfew/VPN pre-condition in resilience evaluation; fail-safe skipping")
            true
        }
        if (isCurfewOrVpnActive) {
            Timber.tag(TAG).d("Curfew or non-idle VPN is active; DnsResilienceManager will not intervene.")
            return
        }

        if (!isDpmHostNotServingTrigger && DnsResilienceManager.isNetworkValidated(cm, network)) {
            Timber.tag(TAG).d("Network $netId ($resolvedTransport) is already validated; skipping resilience intervention.")
            return
        }
        if (!DnsResilienceManager.isTargetNetworkPrimaryOrSolePhysical(context, network)) {
            Timber.tag(TAG).d("Ignoring unvalidated background network net=$netId ($resolvedTransport)")
            return
        }

        if (DnsResilienceNetworkRouter.consumeSameNetRecoveredAndBlockIfRepeated(netId)) {
            Timber.tag(TAG).w("Primary DoT failed again after same-network recovery on $netId; locking primaryBlockedOnNetIds")
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

        val primaryProbeResult = if (isCaptive) {
            DnsResilienceProbeClient.DotProbeResult(
                reachable = false,
                systemDnsResolved = false,
                usedBootstrapFallback = false
            )
        } else {
            DnsResilienceProbeClient.probeDotHostDetailed(
                network = network,
                host = targetPolicyHost,
                families = families,
                dotProbeOverride = DnsResilienceManager.dotProbeOverride
            )
        }
        val isPrimaryWorking = primaryProbeResult.reachable
        recordProbeTelemetry(context, netId, resolvedTransport, triggerReason, isCaptive, families, primaryProbeResult)

        if (isAbortedByStateOrDisconnect(host, entryEpoch, cm, network)) return
        if (!isDpmHostNotServingTrigger && DnsResilienceManager.isNetworkValidated(cm, network)) {
            Timber.tag(TAG).i("Primary DoT host $targetPolicyHost validated on $netId ($resolvedTransport)")
            return
        }

        val resolvedTriggerClass = when {
            isCaptive -> DnsResilienceManager.FailoverTriggerClass.CAPTIVE_PORTAL
            isPrimaryWorking -> {
                if (!isDpmHostNotServingTrigger) {
                    val delayFn: suspend (Long) -> Unit = DnsResilienceManager.delayOverride ?: { delay(it) }
                    delayFn(OS_QUIRK_RECHECK_GRACE_MS)
                    if (isAbortedByStateOrDisconnect(host, entryEpoch, cm, network)) return
                    if (DnsResilienceManager.isNetworkValidated(cm, network)) {
                        Timber.tag(TAG).i("Network $netId ($resolvedTransport) validated during OS quirk grace period; remaining on PRIMARY_STRICT_DOT")
                        return
                    }
                }
                Timber.tag(TAG).w("Primary DoT connected on Java, but OS validated=false ($resolvedTransport); trying Stage A alternate DoT before Level 3.")
                DnsResilienceManager.FailoverTriggerClass.OS_DOT_VALIDATION_QUIRK
            }
            else -> DnsResilienceManager.FailoverTriggerClass.PORT_853_BLOCKED
        }

        var attemptedLevel2Override = false
        if (!isCaptive) {
            val stageAResult = tryStageAAlternateDot(
                context = context,
                cm = cm,
                network = network,
                netId = netId,
                resolvedTransport = resolvedTransport,
                targetPolicyHost = targetPolicyHost,
                triggerReason = triggerReason,
                families = families,
                entryEpoch = entryEpoch,
                host = host
            )
            if (stageAResult.handled) return
            attemptedLevel2Override = stageAResult.attemptedLevel2Override
            if (isAbortedByStateOrDisconnect(host, entryEpoch, cm, network)) return
        }

        escalateToLevel3(
            context = context,
            netId = netId,
            resolvedTransport = resolvedTransport,
            triggerReason = triggerReason,
            resolvedTriggerClass = resolvedTriggerClass,
            targetPolicyHost = targetPolicyHost,
            attemptedLevel2Override = attemptedLevel2Override,
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
        triggerReason: String,
        families: LinkIpFamilies,
        entryEpoch: Long,
        host: HostCallbacks
    ): StageAResult {
        var totalProbeElapsedMs = 0L
        var attemptedLevel2Override = false
        val candidates = DnsResilienceProbeClient.ALTERNATE_FAMILY_PRESETS.filterNot {
            it.equals(targetPolicyHost, ignoreCase = true)
        }
        for (candidate in candidates) {
            if (isAbortedByStateOrDisconnect(host, entryEpoch, cm, network)) {
                return StageAResult(handled = true, attemptedLevel2Override = attemptedLevel2Override)
            }
            if (totalProbeElapsedMs >= STAGE_A_PROBE_BUDGET_MS) {
                Timber.tag(TAG).w("Stage A probe budget exceeded for $netId; escalating to Stage B")
                break
            }
            val probeStartMs = DnsResilienceManager.getElapsedRealtime()
            val candidateProbe = DnsResilienceProbeClient.probeDotHostDetailed(
                network = network,
                host = candidate,
                families = families,
                dotProbeOverride = DnsResilienceManager.dotProbeOverride
            )
            totalProbeElapsedMs += (DnsResilienceManager.getElapsedRealtime() - probeStartMs).coerceAtLeast(0L)
            if (candidateProbe.reachable) {
                if (isAbortedByStateOrDisconnect(host, entryEpoch, cm, network)) {
                    return StageAResult(handled = true, attemptedLevel2Override = attemptedLevel2Override)
                }
                Timber.tag(TAG).i("Alternate Family DoT $candidate works on $resolvedTransport $netId; activating Level 2")
                host.activateLevel2(context, candidate, netId, resolvedTransport)
                attemptedLevel2Override = true
                val applied = host.applyDnsShieldSafely(context)
                if (!applied) {
                    Timber.tag(TAG).w("Applying alternate DoT $candidate failed on $netId; trying next candidate")
                    continue
                }

                val delayFn = DnsResilienceManager.delayOverride
                if (delayFn != null) {
                    delayFn.invoke(LEVEL2_VALIDATION_GRACE_MS)
                } else {
                    val graceDeadlineMs = DnsResilienceManager.getElapsedRealtime() + LEVEL2_VALIDATION_GRACE_MS
                    while (DnsResilienceManager.getElapsedRealtime() < graceDeadlineMs) {
                        delay(LEVEL2_VALIDATION_POLL_MS)
                        if (isAbortedByStateOrDisconnect(host, entryEpoch, cm, network)) {
                            Timber.tag(TAG).i("Aborting post-grace escalation for $netId: state epoch changed or network disconnected")
                            return StageAResult(handled = true, attemptedLevel2Override = attemptedLevel2Override)
                        }
                        if (DnsResilienceManager.isNetworkValidated(cm, network)) {
                            break
                        }
                    }
                }

                if (isAbortedByStateOrDisconnect(host, entryEpoch, cm, network)) {
                    Timber.tag(TAG).i("Aborting post-grace escalation for $netId: state epoch changed or network disconnected")
                    return StageAResult(handled = true, attemptedLevel2Override = attemptedLevel2Override)
                }
                if (DnsResilienceManager.isNetworkValidated(cm, network)) {
                    Timber.tag(TAG).i("$resolvedTransport $netId validated under alternate DoT $candidate; staying at Level 2")
                    recordNetworkSafe(
                        context = context,
                        action = "DNS_FAILOVER_ALTERNATE_DOT",
                        isSuccess = true,
                        details = "net=$netId, transport=$resolvedTransport, primary=$targetPolicyHost, fallback=$candidate"
                    )
                    host.scheduleSameNetworkPrimaryRecovery(context, network, netId, targetPolicyHost, triggerReason)
                    return StageAResult(handled = true, attemptedLevel2Override = attemptedLevel2Override)
                }
                Timber.tag(TAG).w("$resolvedTransport $netId unvalidated after 6s under alternate DoT $candidate; trying next candidate")
            }
        }
        if (attemptedLevel2Override) {
            host.clearLevel2StateWithoutDpm(context)
        }
        return StageAResult(handled = false, attemptedLevel2Override = attemptedLevel2Override)
    }

    private fun escalateToLevel3(
        context: Context,
        netId: String,
        resolvedTransport: String,
        triggerReason: String,
        resolvedTriggerClass: DnsResilienceManager.FailoverTriggerClass,
        targetPolicyHost: String,
        attemptedLevel2Override: Boolean,
        entryEpoch: Long,
        cm: ConnectivityManager?,
        network: Network,
        host: HostCallbacks
    ) {
        if (isAbortedByStateOrDisconnect(host, entryEpoch, cm, network)) {
            if (attemptedLevel2Override && !ProtectionTransitionGuard.isTeardownInProgress) {
                host.applyDnsShieldSafely(context)
            }
            return
        }
        if (isExternalVpnCoexistenceActive(context, cm)) {
            Timber.tag(TAG).i("External VPN coexistence active on $resolvedTransport $netId; skipping Level 3 VPN escalation")
            if (attemptedLevel2Override) {
                host.applyDnsShieldSafely(context)
            }
            return
        }
        if (!DnsResilienceManager.isLocalVpnFallbackEnabled(context)) {
            Timber.tag(TAG).w("Local DNS VPN fallback is disabled; awaiting user consent on $resolvedTransport $netId ($resolvedTriggerClass)")
            host.markExhaustedDotAndNotify(
                context = context,
                netId = netId,
                transport = resolvedTransport,
                triggerClass = resolvedTriggerClass,
                restorePrimaryDpm = attemptedLevel2Override
            )
            recordNetworkSafe(
                context = context,
                action = "DNS_RESILIENCE_AWAITING_CONSENT",
                isSuccess = false,
                details = "net=$netId, transport=$resolvedTransport, triggerClass=$resolvedTriggerClass"
            )
            return
        }

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
            if (e is CancellationException) throw e
            Timber.tag(TAG).e(e, "BinaryDomainFilter initialization failed before Level 3 activation")
            false
        }
        if (!filterReady) {
            Timber.tag(TAG).e("BinaryDomainFilter failed to initialize; aborting Level 3 escalation (Invariant #1)")
            host.rollbackToPrimarySync(context, netId, resolvedTransport, "binary_filter_failed")
            return
        }

        if (isAbortedByStateOrDisconnect(host, entryEpoch, cm, network)) {
            if (attemptedLevel2Override && !ProtectionTransitionGuard.isTeardownInProgress) {
                host.applyDnsShieldSafely(context)
            }
            return
        }
        if (isExternalVpnCoexistenceActive(context, cm)) {
            Timber.tag(TAG).i("External VPN coexistence active on $resolvedTransport $netId; skipping Level 3 VPN escalation")
            if (attemptedLevel2Override) {
                host.applyDnsShieldSafely(context)
            }
            return
        }
        if (!DnsResilienceManager.isLocalVpnFallbackEnabled(context)) {
            Timber.tag(TAG).w("Local DNS VPN fallback toggled off before Level 3 activation on $netId; awaiting consent")
            host.markExhaustedDotAndNotify(
                context = context,
                netId = netId,
                transport = resolvedTransport,
                triggerClass = resolvedTriggerClass,
                restorePrimaryDpm = attemptedLevel2Override
            )
            recordNetworkSafe(
                context = context,
                action = "DNS_RESILIENCE_AWAITING_CONSENT",
                isSuccess = false,
                details = "net=$netId, transport=$resolvedTransport, triggerClass=$resolvedTriggerClass"
            )
            return
        }

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

        if (isAbortedByStateOrDisconnect(host, entryEpoch, cm, network)) return

        if (!result) {
            Timber.tag(TAG).e("Level 3 VPN activation failed; rolling back to PRIMARY_STRICT_DOT")
            host.rollbackToPrimarySync(context, netId, resolvedTransport, "level3_activation_failed")
        } else {
            Timber.tag(TAG).i("Level 3 Local VPN DNS + DoH 443 activated for $resolvedTransport $netId")
            if (resolvedTriggerClass != DnsResilienceManager.FailoverTriggerClass.OS_DOT_VALIDATION_QUIRK) {
                host.scheduleSameNetworkPrimaryRecovery(context, network, netId, targetPolicyHost, triggerReason)
            } else {
                Timber.tag(TAG).i("Skipping same-network recovery for OS_DOT_VALIDATION_QUIRK on $netId; will restore on network change")
            }
        }
    }

    private fun isExternalVpnCoexistenceActive(context: Context, cm: ConnectivityManager?): Boolean {
        val allowExternalVpn = runCatching {
            com.drmohammed.antiporn.data.repository.PolicyRepository.getInstance(context)
                .getCurrentPolicy().isBlockExternalVpnEnabled == false
        }.getOrDefault(false)
        if (!allowExternalVpn || CurfewBlackholeVpnService.isServiceOrTunnelActive()) return false
        return cm?.allNetworks?.any { net ->
            cm.getNetworkCapabilities(net)?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN) == true
        } == true
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
        probeResult: DnsResilienceProbeClient.DotProbeResult
    ) {
        recordNetworkSafe(
            context = context,
            action = "DNS_RESILIENCE_PROBE",
            isSuccess = true,
            details = "net=$netId, transport=$resolvedTransport, trigger=$triggerReason, captive=$isCaptive, tls443=true, v4=${families.hasIpv4}, v6=${families.hasIpv6}, primaryDot=${probeResult.reachable}, sysDns=${probeResult.systemDnsResolved}, bootstrap=${probeResult.usedBootstrapFallback}"
        )
    }

    internal fun resolvePolicyHost(context: Context): String {
        val fallback = DnsShield.DEFAULT_DNS_HOST
        return try {
            DnsResilienceManager.policyHostProvider?.invoke(context)
                ?: com.drmohammed.antiporn.data.repository.PolicyRepository.getInstance(context)
                    .getCurrentPolicy().dnsHost.takeIf { it.isNotBlank() }
                ?: fallback
        } catch (e: Exception) {
            if (e is CancellationException) throw e
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
