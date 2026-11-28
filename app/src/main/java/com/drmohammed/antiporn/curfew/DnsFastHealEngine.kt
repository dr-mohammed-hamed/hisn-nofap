package com.drmohammed.antiporn.curfew

import android.content.Context
import android.os.Build
import com.drmohammed.antiporn.core.engine.ProtectionTransitionGuard
import com.drmohammed.antiporn.shields.DnsShield
import com.drmohammed.antiporn.telemetry.SafeDpmGateway
import timber.log.Timber

/**
 * Zero-latency (~1-3ms) Settings.Global fast-heal and offline fail-closed pre-arm engine
 * for Private DNS protection.
 */
internal object DnsFastHealEngine {

    private const val TAG = "AntiPorn_DnsFastHeal"

    fun preArmStandaloneDotFailClosed(context: Context, targetHost: String): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || targetHost.isBlank()) return false
        if (!SafeDpmGateway.isDeviceOwner(context)) return false

        val globalMode = PrivateDnsSettingsBridge.readGlobalSetting(context, "private_dns_mode")
        val globalHost = PrivateDnsSettingsBridge.readGlobalSetting(context, "private_dns_specifier")
        val dpmMode = PrivateDnsSettingsBridge.readDpmPrivateDnsMode(context)
        val dpmHost = PrivateDnsSettingsBridge.readDpmPrivateDnsHost(context)
        val dpmAlreadyMatches = dpmMode == PrivateDnsSettingsBridge.privateDnsModeProviderHostname() &&
            dpmHost?.equals(targetHost, ignoreCase = true) == true

        if (globalMode == "hostname" && globalHost?.equals(targetHost, ignoreCase = true) == true) {
            CurfewConfig.setPendingDpmPrivateDnsSync(context, !dpmAlreadyMatches, if (dpmAlreadyMatches) null else targetHost)
            CurfewConfig.setDnsRestorePending(context, false)
            return true
        }

        ProtectionTransitionGuard.markInternalDnsMutation()
        val ok = try {
            PrivateDnsSettingsBridge.writePrivateDnsAtomic(context, "hostname", targetHost)
        } catch (e: SecurityException) {
            PrivateDnsSettingsBridge.directGlobalWriteDenied = true
            Timber.tag(TAG).w(e, "preArmStandaloneDotFailClosed blocked by SecurityException")
            false
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "preArmStandaloneDotFailClosed failed")
            false
        }
        if (ok) {
            PrivateDnsSettingsBridge.directGlobalWriteDenied = false
            CurfewConfig.setPendingDpmPrivateDnsSync(context, !dpmAlreadyMatches, if (dpmAlreadyMatches) null else targetHost)
            CurfewConfig.setDnsRestorePending(context, false)
            PrivateDnsSettingsBridge.lockDnsAndNetworkRestrictions(context)
        }
        return ok
    }

    fun tryFastHealGlobalPrivateDns(context: Context): DnsFastHealResult {
        return tryFastHealGlobalPrivateDnsInternal(
            sdkInt = Build.VERSION.SDK_INT,
            hasActiveTransitions = { ProtectionTransitionGuard.hasActiveTransitions() },
            isDeviceOwner = { SafeDpmGateway.isDeviceOwner(context) },
            isLocalVpnExpected = { CurfewVpnDnsBridge.isLocalVpnExpectedActive(context) },
            getExpectedHost = { DnsShield.getEffectiveDnsHost(context) },
            readGlobal = { key -> PrivateDnsSettingsBridge.readGlobalSetting(context, key) },
            markInternalMutation = { ProtectionTransitionGuard.markInternalDnsMutation() },
            writeGlobal = { key, value -> PrivateDnsSettingsBridge.writeGlobalSettingWithAtomicUnlock(context, key, value) },
            clearDnsRestorePending = {
                if (CurfewConfig.isDnsRestorePending(context)) {
                    CurfewConfig.setDnsRestorePending(context, false)
                }
            },
            writePrivateDnsPair = { mode, specifier -> PrivateDnsSettingsBridge.writePrivateDnsAtomic(context, mode, specifier) }
        )
    }

    fun tryFastHealGlobalPrivateDnsInternal(
        sdkInt: Int = Build.VERSION_CODES.Q,
        hasActiveTransitions: () -> Boolean,
        isDeviceOwner: () -> Boolean,
        isLocalVpnExpected: () -> Boolean,
        getExpectedHost: () -> String,
        readGlobal: (String) -> String?,
        markInternalMutation: () -> Unit,
        writeGlobal: (String, String) -> Boolean,
        clearDnsRestorePending: () -> Unit = {},
        writePrivateDnsPair: ((mode: String, specifier: String) -> Boolean)? = null
    ): DnsFastHealResult {
        if (sdkInt < Build.VERSION_CODES.Q) return DnsFastHealResult(outcome = DnsFastHealOutcome.SKIPPED_UNSUPPORTED_SDK)
        if (hasActiveTransitions()) return DnsFastHealResult(outcome = DnsFastHealOutcome.SKIPPED_ACTIVE_TRANSITION)
        if (!isDeviceOwner()) return DnsFastHealResult(outcome = DnsFastHealOutcome.SKIPPED_NOT_DEVICE_OWNER)
        if (isLocalVpnExpected()) return DnsFastHealResult(outcome = DnsFastHealOutcome.SKIPPED_VPN_EXPECTED)

        val expectedHost = getExpectedHost()
        if (expectedHost.isBlank()) {
            return DnsFastHealResult(
                outcome = DnsFastHealOutcome.FAILED_NEEDS_DPM,
                expectedHost = expectedHost,
                errorMessage = "Effective DNS host is blank"
            )
        }

        val globalMode = readGlobal("private_dns_mode")
        val globalHost = readGlobal("private_dns_specifier")
        if (globalMode == "hostname" && globalHost?.equals(expectedHost, ignoreCase = true) == true) {
            return DnsFastHealResult(
                outcome = DnsFastHealOutcome.ALREADY_COMPLIANT,
                observedMode = globalMode,
                observedHost = globalHost,
                expectedHost = expectedHost
            )
        }

        if (PrivateDnsSettingsBridge.directGlobalWriteDenied) {
            return DnsFastHealResult(
                outcome = DnsFastHealOutcome.PERMISSION_DENIED_NEEDS_DPM,
                observedMode = globalMode,
                observedHost = globalHost,
                expectedHost = expectedHost,
                errorMessage = "Direct Settings.Global write circuit breaker open"
            )
        }

        val startNanos = System.nanoTime()
        return try {
            markInternalMutation()
            val writeSucceeded = if (writePrivateDnsPair != null) {
                writePrivateDnsPair("hostname", expectedHost)
            } else {
                val hostWritten = writeGlobal("private_dns_specifier", expectedHost)
                val modeWritten = if (hostWritten) writeGlobal("private_dns_mode", "hostname") else false
                hostWritten && modeWritten
            }
            val latencyMs = ((System.nanoTime() - startNanos) / 1_000_000L).coerceAtLeast(0L)
            if (!writeSucceeded) {
                Timber.tag(TAG).w("Fast-heal Settings.Global.putString returned false (expectedHost=$expectedHost)")
                return DnsFastHealResult(
                    outcome = DnsFastHealOutcome.FAILED_NEEDS_DPM,
                    observedMode = globalMode,
                    observedHost = globalHost,
                    expectedHost = expectedHost,
                    latencyMs = latencyMs,
                    errorMessage = "Settings.Global.putString returned false"
                )
            }
            clearDnsRestorePending()
            Timber.tag(TAG).i("Fast-healed Private DNS via Settings.Global to $expectedHost in ${latencyMs}ms (was mode=$globalMode, host=$globalHost)")
            DnsFastHealResult(
                outcome = DnsFastHealOutcome.HEALED_DIRECT,
                observedMode = globalMode,
                observedHost = globalHost,
                expectedHost = expectedHost,
                latencyMs = latencyMs
            )
        } catch (e: SecurityException) {
            PrivateDnsSettingsBridge.directGlobalWriteDenied = true
            val latencyMs = ((System.nanoTime() - startNanos) / 1_000_000L).coerceAtLeast(0L)
            Timber.tag(TAG).w(e, "Direct Settings.Global private DNS write denied (SecurityException); tripping circuit breaker")
            DnsFastHealResult(
                outcome = DnsFastHealOutcome.PERMISSION_DENIED_NEEDS_DPM,
                observedMode = globalMode,
                observedHost = globalHost,
                expectedHost = expectedHost,
                latencyMs = latencyMs,
                errorMessage = e.message ?: e.javaClass.simpleName
            )
        } catch (e: Exception) {
            val latencyMs = ((System.nanoTime() - startNanos) / 1_000_000L).coerceAtLeast(0L)
            Timber.tag(TAG).w(e, "Direct Settings.Global private DNS fast-heal failed; delegating to DPM fast-path")
            DnsFastHealResult(
                outcome = DnsFastHealOutcome.FAILED_NEEDS_DPM,
                observedMode = globalMode,
                observedHost = globalHost,
                expectedHost = expectedHost,
                latencyMs = latencyMs,
                errorMessage = e.message ?: e.javaClass.simpleName
            )
        }
    }
}
