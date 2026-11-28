package com.drmohammed.antiporn.curfew

import android.content.Context
import android.os.Build
import android.os.SystemClock
import androidx.annotation.RequiresApi
import com.drmohammed.antiporn.core.engine.ProtectionTransitionGuard
import com.drmohammed.antiporn.core.engine.SecondaryProfileDetector
import com.drmohammed.antiporn.core.network.PhysicalNetworkResolver
import com.drmohammed.antiporn.core.network.dns.DnsResilienceManager
import com.drmohammed.antiporn.shields.DnsShield
import com.drmohammed.antiporn.telemetry.BlackBoxRecorder
import com.drmohammed.antiporn.telemetry.DpmExecutionResult
import com.drmohammed.antiporn.telemetry.SafeDpmGateway
import timber.log.Timber
import java.util.concurrent.atomic.AtomicLong

/**
 * Enforces standalone Strict Private DNS (DoT) via DevicePolicyManager with atomic
 * Settings.Global fallback, offline fail-closed pre-arming, post-DPM verification,
 * and unaffiliated user reprobe gating.
 */
internal object StandaloneDotEnforcer {

    private const val TAG = "AntiPorn_StandaloneDot"
    private const val UNAFFILIATED_REPROBE_INTERVAL_MS = 5 * 60 * 1_000L

    private val lastUnaffiliatedDpmRetryElapsedMs = AtomicLong(0L)

    fun resetUnaffiliatedRetryForTest() {
        lastUnaffiliatedDpmRetryElapsedMs.set(0L)
    }

    private fun safeElapsedRealtimeMs(): Long = try {
        SystemClock.elapsedRealtime()
    } catch (_: RuntimeException) {
        System.nanoTime() / 1_000_000L
    }

    fun shouldAllowUnaffiliatedDpmReprobe(
        countReliable: Boolean,
        nowMs: Long,
        lastRetryMs: Long,
        intervalMs: Long = UNAFFILIATED_REPROBE_INTERVAL_MS
    ): Boolean = !countReliable && (lastRetryMs == 0L || (nowMs - lastRetryMs) >= intervalMs)

    fun preArmStandaloneDotFailClosed(
        context: Context,
        host: String = DnsShield.getEffectiveDnsHost(context)
    ): Boolean = DnsFastHealEngine.preArmStandaloneDotFailClosed(context, host)

    internal fun shouldSkipDpmForExhaustedNetwork(
        suppressResilienceNotify: Boolean,
        isExhaustedOnNetwork: Boolean
    ): Boolean = !suppressResilienceNotify && isExhaustedOnNetwork

    internal fun shouldNotifyResilienceOnHostNotServing(
        dnsSetResult: Int,
        isOffline: Boolean,
        suppressResilienceNotify: Boolean,
        isEvaluationInProgress: Boolean
    ): Boolean = dnsSetResult == CurfewVpnDnsBridge.COMPAT_PRIVATE_DNS_SET_ERROR_HOST_NOT_SERVING &&
        !isOffline &&
        !suppressResilienceNotify &&
        !isEvaluationInProgress

    @RequiresApi(Build.VERSION_CODES.Q)
    fun applyStandaloneDot(
        context: Context,
        targetHost: String,
        suppressResilienceNotify: Boolean = false
    ): Pair<Int, Boolean> {
        if (shouldSkipDpmForExhaustedNetwork(suppressResilienceNotify, DnsResilienceManager.isExhaustedDotOnNetwork(context))) {
            Timber.tag(TAG).d("Skipping DPM Private DNS call for $targetHost: DoT already exhausted on current network")
            return CurfewVpnDnsBridge.COMPAT_PRIVATE_DNS_SET_ERROR_HOST_NOT_SERVING to false
        }

        val currentMode = PrivateDnsSettingsBridge.readDpmPrivateDnsMode(context)
        val currentHost = PrivateDnsSettingsBridge.readDpmPrivateDnsHost(context)
        val globalMode = PrivateDnsSettingsBridge.readGlobalSetting(context, "private_dns_mode")
        val globalHost = PrivateDnsSettingsBridge.readGlobalSetting(context, "private_dns_specifier")
        val isOffline = PhysicalNetworkResolver.isDevicePhysicallyOffline(context)

        val (dnsSetResult, usedFallback) = applyStandaloneDotInternal(
            targetHost = targetHost,
            currentDpmMode = currentMode,
            currentDpmHost = currentHost,
            globalMode = globalMode,
            globalHost = globalHost,
            isDeviceOwner = SafeDpmGateway.isDeviceOwner(context),
            markInternalMutation = { ProtectionTransitionGuard.markInternalDnsMutation() },
            writeGlobal = { key, value -> PrivateDnsSettingsBridge.writeGlobalSettingWithAtomicUnlock(context, key, value) },
            setDpmPrivateDns = { host -> invokeDpmSetGlobalPrivateDns(context, host) },
            clearDnsRestorePending = {
                if (CurfewConfig.isDnsRestorePending(context)) CurfewConfig.setDnsRestorePending(context, false)
            },
            writePrivateDnsPair = { mode, specifier -> PrivateDnsSettingsBridge.writePrivateDnsAtomic(context, mode, specifier) },
            isDeviceOffline = isOffline,
            markPendingDpmSync = { pending -> CurfewConfig.setPendingDpmPrivateDnsSync(context, pending, targetHost) },
            readGlobalAfterDpm = {
                PrivateDnsSettingsBridge.readGlobalSetting(context, "private_dns_mode") to
                    PrivateDnsSettingsBridge.readGlobalSetting(context, "private_dns_specifier")
            }
        )

        if (dnsSetResult != CurfewVpnDnsBridge.COMPAT_PRIVATE_DNS_SET_NO_ERROR &&
            !PrivateDnsSettingsBridge.isPrivateDnsBlockedByUnaffiliatedUsers(context)
        ) {
            BlackBoxRecorder.recordShield(
                context = context,
                shieldId = DnsShield.id,
                isSuccess = false,
                details = "setGlobalPrivateDns returned error code=$dnsSetResult host=$targetHost offline=$isOffline usedFallback=$usedFallback"
            )
        }
        val evalInProgress = DnsResilienceManager.isEvaluationInProgress()
        if (shouldNotifyResilienceOnHostNotServing(dnsSetResult, isOffline, suppressResilienceNotify, evalInProgress)) {
            Timber.tag(TAG).w("applyStandaloneDot: host $targetHost NOT serving while online; notifying DnsResilienceManager")
            DnsResilienceManager.onDpmHostNotServing(context, targetHost)
        } else if (dnsSetResult == CurfewVpnDnsBridge.COMPAT_PRIVATE_DNS_SET_ERROR_HOST_NOT_SERVING && !isOffline) {
            Timber.tag(TAG).d(
                "applyStandaloneDot: skipping DnsResilienceManager notify (suppress=%b, evalInProgress=%b)",
                suppressResilienceNotify,
                evalInProgress
            )
        }
        return dnsSetResult to usedFallback
    }

    private fun invokeDpmSetGlobalPrivateDns(context: Context, host: String): Pair<Boolean, Int> {
        val unaffiliatedAlreadyBlocked = PrivateDnsSettingsBridge.isPrivateDnsBlockedByUnaffiliatedUsers(context)
        val countResult = SecondaryProfileDetector.countSecondaryUsersAndProfiles(context)
        val nowMs = safeElapsedRealtimeMs()
        val shouldRetryDpm = !unaffiliatedAlreadyBlocked || shouldAllowUnaffiliatedDpmReprobe(
            countReliable = countResult.reliable,
            nowMs = nowMs,
            lastRetryMs = lastUnaffiliatedDpmRetryElapsedMs.get()
        )
        if (!shouldRetryDpm) {
            Timber.tag(TAG).d("Skipping redundant DPM call in applyStandaloneDot: unaffiliated users present")
            return false to CurfewVpnDnsBridge.COMPAT_PRIVATE_DNS_SET_ERROR_FAILURE_SETTING
        }
        if (unaffiliatedAlreadyBlocked) {
            lastUnaffiliatedDpmRetryElapsedMs.set(nowMs)
            Timber.tag(TAG).d("Re-probing DPM setGlobalPrivateDns because secondary user count is unreliable")
        }
        val dpmRes = SafeDpmGateway.setGlobalPrivateDnsBlocking(context, host, BlackBoxRecorder.TAG_SHIELD)
        if (PrivateDnsSettingsBridge.isUnaffiliatedUsersException(dpmRes)) {
            PrivateDnsSettingsBridge.updatePrivateDnsBlockedByUnaffiliatedUsers(context, true)
            BlackBoxRecorder.recordShield(context, DnsShield.id, false, "setGlobalPrivateDnsBlocking blocked: unaffiliated users host=$host")
        } else if (dpmRes.isSuccess) {
            lastUnaffiliatedDpmRetryElapsedMs.set(0L)
            PrivateDnsSettingsBridge.updatePrivateDnsBlockedByUnaffiliatedUsers(context, false)
        }
        val resultCode = when (dpmRes) {
            is DpmExecutionResult.Success -> dpmRes.data
            is DpmExecutionResult.Failure -> CurfewVpnDnsBridge.COMPAT_PRIVATE_DNS_SET_ERROR_FAILURE_SETTING
        }
        return dpmRes.isSuccess to resultCode
    }

    fun applyStandaloneDotInternal(
        targetHost: String,
        currentDpmMode: Int,
        currentDpmHost: String?,
        globalMode: String?,
        globalHost: String?,
        isDeviceOwner: Boolean,
        markInternalMutation: () -> Unit,
        writeGlobal: (String, String) -> Boolean,
        setDpmPrivateDns: (String) -> Pair<Boolean, Int>,
        clearDnsRestorePending: () -> Unit = {},
        writePrivateDnsPair: ((mode: String, specifier: String) -> Boolean)? = null,
        isDeviceOffline: Boolean = false,
        markPendingDpmSync: (Boolean) -> Unit = {},
        readGlobalAfterDpm: (() -> Pair<String?, String?>)? = null
    ): Pair<Int, Boolean> {
        markInternalMutation()
        var dnsSetResult = CurfewVpnDnsBridge.COMPAT_PRIVATE_DNS_SET_NO_ERROR
        var usedFallback = false

        val dpmMatches = currentDpmMode == PrivateDnsSettingsBridge.privateDnsModeProviderHostname() &&
            currentDpmHost?.equals(targetHost, ignoreCase = true) == true
        val globalMatches = globalMode == "hostname" && globalHost?.equals(targetHost, ignoreCase = true) == true

        fun writePair(mode: String, spec: String): Boolean = if (writePrivateDnsPair != null) {
            writePrivateDnsPair(mode, spec)
        } else {
            val h = writeGlobal("private_dns_specifier", spec)
            val m = if (h) writeGlobal("private_dns_mode", mode) else false
            h && m
        }

        if (!dpmMatches || !globalMatches) {
            var dpmSucceeded = dpmMatches
            if (isDeviceOwner) {
                if (!dpmMatches) {
                    val (dpmOk, resultCode) = setDpmPrivateDns(targetHost)
                    dnsSetResult = resultCode
                    Timber.tag(TAG).i("setGlobalPrivateDnsBlocking to $targetHost, result: $dnsSetResult (ok=$dpmOk, offline=$isDeviceOffline)")
                    dpmSucceeded = dpmOk && dnsSetResult == CurfewVpnDnsBridge.COMPAT_PRIVATE_DNS_SET_NO_ERROR
                }
                if (dpmSucceeded) markPendingDpmSync(false)

                val globalMatchesAfterDpm = if (dpmSucceeded && readGlobalAfterDpm != null) {
                    val (postMode, postHost) = readGlobalAfterDpm()
                    postMode == "hostname" && postHost?.equals(targetHost, ignoreCase = true) == true
                } else {
                    globalMatches
                }

                if (dnsSetResult == CurfewVpnDnsBridge.COMPAT_PRIVATE_DNS_SET_ERROR_HOST_NOT_SERVING) {
                    if (isDeviceOffline && targetHost.isNotBlank()) {
                        val preArmedOk = if (globalMatchesAfterDpm) true else try {
                            writePair("hostname", targetHost).also { ok ->
                                if (ok) {
                                    PrivateDnsSettingsBridge.directGlobalWriteDenied = false
                                    Timber.tag(TAG).i("Pre-armed Private DNS Settings.Global offline to $targetHost pending DPM sync")
                                }
                            }
                        } catch (e: SecurityException) {
                            PrivateDnsSettingsBridge.directGlobalWriteDenied = true
                            Timber.tag(TAG).w(e, "SecurityException pre-arming Settings.Global offline")
                            false
                        } catch (e: Exception) {
                            Timber.tag(TAG).w(e, "Could not pre-arm Settings.Global offline")
                            false
                        }
                        if (preArmedOk) {
                            markPendingDpmSync(true)
                            usedFallback = true
                        }
                    } else {
                        // CRITICAL INVARIANT: Online host is NOT serving on port 853; NEVER write to Settings.Global!
                        usedFallback = false
                    }
                } else if (!globalMatchesAfterDpm && (dpmSucceeded || dnsSetResult == CurfewVpnDnsBridge.COMPAT_PRIVATE_DNS_SET_ERROR_FAILURE_SETTING)) {
                    try {
                        if (writePair("hostname", targetHost)) {
                            Timber.tag(TAG).i("Enforced private DNS via Settings.Global for $targetHost (dpmSucceeded=$dpmSucceeded)")
                            PrivateDnsSettingsBridge.directGlobalWriteDenied = false
                            if (!dpmSucceeded) usedFallback = true
                        } else {
                            Timber.tag(TAG).w("Settings.Global.putString returned false for private DNS ($targetHost)")
                        }
                    } catch (e: SecurityException) {
                        PrivateDnsSettingsBridge.directGlobalWriteDenied = true
                        Timber.tag(TAG).w(e, "SecurityException writing Settings.Global; circuit breaker tripped")
                    } catch (e: Exception) {
                        Timber.tag(TAG).w(e, "Could not write Settings.Global for private DNS")
                    }
                } else if (!dpmSucceeded && dnsSetResult == CurfewVpnDnsBridge.COMPAT_PRIVATE_DNS_SET_ERROR_FAILURE_SETTING && globalMatchesAfterDpm) {
                    // Settings.Global was already armed to targetHost when DPM returned OEM error 2
                    usedFallback = true
                } else if (!dpmSucceeded && !globalMatchesAfterDpm && dnsSetResult == CurfewVpnDnsBridge.COMPAT_PRIVATE_DNS_SET_NO_ERROR) {
                    dnsSetResult = CurfewVpnDnsBridge.COMPAT_PRIVATE_DNS_SET_ERROR_FAILURE_SETTING
                }
            } else {
                if (!globalMatches && !PrivateDnsSettingsBridge.directGlobalWriteDenied) {
                    try {
                        if (writePair("hostname", targetHost)) {
                            Timber.tag(TAG).i("Enforced private DNS via Settings.Global (non-DO) to $targetHost")
                            usedFallback = true
                        }
                    } catch (e: SecurityException) {
                        PrivateDnsSettingsBridge.directGlobalWriteDenied = true
                        Timber.tag(TAG).w(e, "SecurityException writing Settings.Global (non-DO)")
                    } catch (e: Exception) {
                        Timber.tag(TAG).w(e, "Could not write Settings.Global (non-DO)")
                    }
                }
                dnsSetResult = CurfewVpnDnsBridge.COMPAT_PRIVATE_DNS_SET_ERROR_FAILURE_SETTING
            }
        } else {
            markPendingDpmSync(false)
            Timber.tag(TAG).d("Private DNS already set to $targetHost via both DPM and Settings.Global")
        }

        val isSuccess = (dnsSetResult == CurfewVpnDnsBridge.COMPAT_PRIVATE_DNS_SET_NO_ERROR && !usedFallback) ||
            (usedFallback && (dnsSetResult != CurfewVpnDnsBridge.COMPAT_PRIVATE_DNS_SET_ERROR_HOST_NOT_SERVING || isDeviceOffline)) ||
            (dpmMatches && globalMatches)
        if (isSuccess) clearDnsRestorePending()
        return dnsSetResult to usedFallback
    }
}
