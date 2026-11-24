package com.drmohammed.antiporn.curfew

import android.app.admin.DevicePolicyManager
import android.os.Build
import androidx.annotation.VisibleForTesting

/**
 * Enforcer for Standalone Private DNS (DoT) via DPM and Settings.Global atomic unlock.
 */
object StandaloneDotEnforcer {

    private const val COMPAT_PRIVATE_DNS_SET_NO_ERROR = 0
    private const val COMPAT_PRIVATE_DNS_SET_ERROR_HOST_NOT_SERVING = 1
    private const val COMPAT_PRIVATE_DNS_SET_ERROR_FAILURE_SETTING = 2

    fun preArmStandaloneDotFailClosed(
        context: android.content.Context,
        host: String = com.drmohammed.antiporn.shields.DnsShield.getEffectiveDnsHost(context)
    ): Boolean = CurfewVpnDnsBridge.preArmStandaloneDotFailClosed(context, host)

    @VisibleForTesting
    internal fun applyStandaloneDotInternal(
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
        readGlobalAfterDpm: (() -> Pair<String?, String?>)? = null,
        isDirectGlobalWriteDenied: () -> Boolean = { false },
        setDirectGlobalWriteDenied: (Boolean) -> Unit = {},
        dpmModeHostname: Int = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            DevicePolicyManager.PRIVATE_DNS_MODE_PROVIDER_HOSTNAME
        } else {
            3
        },
        compatNoError: Int = COMPAT_PRIVATE_DNS_SET_NO_ERROR,
        compatHostNotServing: Int = COMPAT_PRIVATE_DNS_SET_ERROR_HOST_NOT_SERVING,
        compatFailureSetting: Int = COMPAT_PRIVATE_DNS_SET_ERROR_FAILURE_SETTING,
        logI: (String) -> Unit = {},
        logD: (String) -> Unit = {},
        logW: (String, Throwable?) -> Unit = { _, _ -> }
    ): Pair<Int, Boolean> {
        markInternalMutation()
        var dnsSetResult = compatNoError
        var usedFallback = false

        val dpmMatches = currentDpmMode == dpmModeHostname &&
            currentDpmHost?.equals(targetHost, ignoreCase = true) == true
        val globalMatches = globalMode == "hostname" &&
            globalHost?.equals(targetHost, ignoreCase = true) == true

        if (!dpmMatches || !globalMatches) {
            var dpmSucceeded = dpmMatches

            if (isDeviceOwner) {
                if (!dpmMatches) {
                    val (dpmOk, resultCode) = setDpmPrivateDns(targetHost)
                    dnsSetResult = resultCode
                    logI("setGlobalPrivateDnsBlocking to $targetHost, result: $dnsSetResult (ok=$dpmOk)")
                    dpmSucceeded = dpmOk && dnsSetResult == compatNoError
                }

                val globalMatchesAfterDpm = if (dpmSucceeded && readGlobalAfterDpm != null) {
                    val (postMode, postHost) = readGlobalAfterDpm()
                    postMode == "hostname" && postHost?.equals(targetHost, ignoreCase = true) == true
                } else {
                    globalMatches
                }

                if (dnsSetResult == compatHostNotServing) {
                    // CRITICAL INVARIANT: Host is NOT serving on port 853; NEVER write to Settings.Global!
                    usedFallback = false
                } else if (!globalMatchesAfterDpm && (dpmSucceeded || dnsSetResult == compatFailureSetting)) {
                    val canAttemptWrite = !isDirectGlobalWriteDenied() || isDeviceOwner
                    if (canAttemptWrite) {
                        try {
                            val globalWriteSucceeded = if (writePrivateDnsPair != null) {
                                writePrivateDnsPair("hostname", targetHost)
                            } else {
                                val hostOk = writeGlobal("private_dns_specifier", targetHost)
                                val modeOk = if (hostOk) writeGlobal("private_dns_mode", "hostname") else false
                                hostOk && modeOk
                            }
                            if (globalWriteSucceeded) {
                                logI("Enforced private DNS via Settings.Global for $targetHost (dpmSucceeded=$dpmSucceeded)")
                                setDirectGlobalWriteDenied(false)
                                if (!dpmSucceeded) {
                                    usedFallback = true
                                }
                            } else {
                                logW("Settings.Global.putString returned false for private DNS ($targetHost)", null)
                            }
                        } catch (e: SecurityException) {
                            setDirectGlobalWriteDenied(true)
                            logW("SecurityException writing Settings.Global for private DNS; circuit breaker tripped", e)
                        } catch (e: Exception) {
                            logW("Could not write Settings.Global for private DNS", e)
                        }
                    } else {
                        logD("Skipping Settings.Global write in applyStandaloneDot (circuit breaker active)")
                    }
                } else if (!dpmSucceeded && dnsSetResult == compatFailureSetting && globalMatchesAfterDpm) {
                    // Settings.Global was already armed to targetHost when DPM returned OEM error 2
                    usedFallback = true
                } else if (!dpmSucceeded && !globalMatchesAfterDpm && dnsSetResult == compatNoError) {
                    dnsSetResult = compatFailureSetting
                }
            } else {
                if (!globalMatches) {
                    if (!isDirectGlobalWriteDenied()) {
                        try {
                            val globalWriteSucceeded = if (writePrivateDnsPair != null) {
                                writePrivateDnsPair("hostname", targetHost)
                            } else {
                                val hostOk = writeGlobal("private_dns_specifier", targetHost)
                                val modeOk = if (hostOk) writeGlobal("private_dns_mode", "hostname") else false
                                hostOk && modeOk
                            }
                            if (globalWriteSucceeded) {
                                logI("Enforced private DNS via Settings.Global directly (non-DO) to $targetHost")
                                usedFallback = true
                            }
                        } catch (e: SecurityException) {
                            setDirectGlobalWriteDenied(true)
                            logW("SecurityException writing Settings.Global (non-DO)", e)
                        } catch (e: Exception) {
                            logW("Could not write Settings.Global (non-DO)", e)
                        }
                    }
                }
                dnsSetResult = compatFailureSetting
            }
        } else {
            logD("Private DNS already set to $targetHost via both DPM and Settings.Global, skipping redundant write")
        }

        val isSuccess = (dnsSetResult == compatNoError && !usedFallback) ||
            (usedFallback && dnsSetResult != compatHostNotServing) ||
            (dpmMatches && globalMatches)
        if (isSuccess) {
            clearDnsRestorePending()
        }
        return dnsSetResult to usedFallback
    }
}
