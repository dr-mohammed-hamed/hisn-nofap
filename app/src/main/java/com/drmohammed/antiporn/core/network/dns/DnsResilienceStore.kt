package com.drmohammed.antiporn.core.network.dns

import android.content.Context
import com.drmohammed.antiporn.core.engine.ProtectionTransitionGuard
import com.drmohammed.antiporn.curfew.CurfewBlackholeVpnService
import com.drmohammed.antiporn.curfew.CurfewVpnDnsBridge
import com.drmohammed.antiporn.shields.DnsShield
import com.drmohammed.antiporn.telemetry.BlackBoxRecorder
import com.drmohammed.antiporn.telemetry.SafeDpmGateway
import timber.log.Timber

/**
 * Handles synchronous SharedPreferences persistence and startup hydration for [DnsResilienceManager].
 */
internal object DnsResilienceStore {

    private const val TAG = "AntiPorn_DnsResStore"
    private const val PREFS_NAME = "antiporn_dns_resilience_prefs"
    private const val KEY_ACTIVE_MODE = "active_mode"
    private const val KEY_OVERRIDE_DOT_HOST = "override_dot_host"
    private const val KEY_PROTECTED_NETWORK_ID = "protected_network_id"
    private const val KEY_PROTECTED_TRANSPORT = "protected_transport"
    private const val KEY_FAILOVER_TRIGGER_CLASS = "failover_trigger_class"

    fun loadSnapshot(context: Context): DnsResilienceManager.ResilienceRuntimeSnapshot? {
        return try {
            val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val modeStr = prefs.getString(KEY_ACTIVE_MODE, null)
            val parsedMode = modeStr?.let {
                runCatching { DnsResilienceManager.DnsResilienceMode.valueOf(it) }.getOrNull()
            } ?: DnsResilienceManager.DnsResilienceMode.PRIMARY_STRICT_DOT
            val loadedOverride = prefs.getString(KEY_OVERRIDE_DOT_HOST, null)?.takeIf { it.isNotBlank() }
            val loadedNetId = prefs.getString(KEY_PROTECTED_NETWORK_ID, null)?.takeIf { it.isNotBlank() }
            val loadedTransport = prefs.getString(KEY_PROTECTED_TRANSPORT, null)?.takeIf { it.isNotBlank() }
            val triggerStr = prefs.getString(KEY_FAILOVER_TRIGGER_CLASS, null)
            val parsedTrigger = triggerStr?.let {
                runCatching { DnsResilienceManager.FailoverTriggerClass.valueOf(it) }.getOrNull()
            }
            DnsResilienceManager.ResilienceRuntimeSnapshot(
                mode = parsedMode,
                overrideDotHost = loadedOverride,
                protectedNetworkId = loadedNetId,
                protectedTransport = loadedTransport,
                triggerClass = parsedTrigger,
                isRestoringPrimary = false
            )
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Failed to load persisted DNS resilience snapshot")
            null
        }
    }

    fun persistSnapshot(
        context: Context,
        snapshot: DnsResilienceManager.ResilienceRuntimeSnapshot
    ) {
        try {
            val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val editor = prefs.edit()
            if (snapshot.mode == DnsResilienceManager.DnsResilienceMode.PRIMARY_STRICT_DOT) {
                editor.clear()
            } else {
                editor.putString(KEY_ACTIVE_MODE, snapshot.mode.name)
                if (snapshot.overrideDotHost != null) {
                    editor.putString(KEY_OVERRIDE_DOT_HOST, snapshot.overrideDotHost)
                } else {
                    editor.remove(KEY_OVERRIDE_DOT_HOST)
                }
                if (snapshot.protectedNetworkId != null) {
                    editor.putString(KEY_PROTECTED_NETWORK_ID, snapshot.protectedNetworkId)
                } else {
                    editor.remove(KEY_PROTECTED_NETWORK_ID)
                }
                if (snapshot.protectedTransport != null) {
                    editor.putString(KEY_PROTECTED_TRANSPORT, snapshot.protectedTransport)
                } else {
                    editor.remove(KEY_PROTECTED_TRANSPORT)
                }
                if (snapshot.triggerClass != null) {
                    editor.putString(KEY_FAILOVER_TRIGGER_CLASS, snapshot.triggerClass.name)
                } else {
                    editor.remove(KEY_FAILOVER_TRIGGER_CLASS)
                }
            }
            if (!editor.commit()) {
                Timber.tag(TAG).w("SharedPreferences commit() returned false in persistSnapshot")
            }
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Failed to persist DNS resilience snapshot")
        }
    }

    fun clearPersistedState(context: Context) {
        try {
            val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            if (!prefs.edit().clear().commit()) {
                Timber.tag(TAG).w("SharedPreferences commit() returned false in clearPersistedState")
            }
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Failed to clear persisted DNS resilience state")
        }
    }

    fun reconcileStartup(
        context: Context,
        current: DnsResilienceManager.ResilienceRuntimeSnapshot,
        beginAndRestore: (String) -> Unit
    ) {
        val cm = DnsResilienceManager.getConnectivityManager(context)
        val targetId = current.protectedNetworkId
        val stillExists = targetId != null &&
            DnsResilienceManager.getAllPhysicalNetworks(context, cm).any {
                DnsResilienceManager.resolveNetworkId(it) == targetId
            }
        if (stillExists) {
            Timber.tag(TAG).i("Startup reconciliation preserved ${current.mode} on net=$targetId")
            if (current.mode == DnsResilienceManager.DnsResilienceMode.FALLBACK_LOCAL_VPN_DOH &&
                !CurfewBlackholeVpnService.isTunnelHealthy(CurfewBlackholeVpnService.VpnMode.DNS_FILTERING_ONLY)
            ) {
                ProtectionTransitionGuard.runInternalTransition {
                    CurfewVpnDnsBridge.reconcileNonBlackholeVpnAndDns(context)
                }
            }
        } else {
            Timber.tag(TAG).i("Startup reconciliation: protected net=$targetId gone; restoring PRIMARY_STRICT_DOT")
            beginAndRestore("startup_protected_net_gone")
        }
    }

    fun executePrimaryRestore(context: Context, reason: String, onFinally: () -> Unit) {
        ProtectionTransitionGuard.runInternalTransition {
            try {
                CurfewVpnDnsBridge.reconcileNonBlackholeVpnAndDns(context)
                Timber.tag(TAG).i("Cleanly restored PRIMARY_STRICT_DOT ($reason)")
            } catch (e: Exception) {
                Timber.tag(TAG).w(e, "Error restoring PRIMARY_STRICT_DOT ($reason)")
            } finally {
                onFinally()
            }
        }
    }

    fun applyDnsShieldSafely(context: Context, override: ((Context) -> Unit)?) {
        override?.invoke(context)?.let { return }
        try {
            SafeDpmGateway.executeRawBlocking(context, BlackBoxRecorder.TAG_SHIELD, "resilienceDnsShieldApply") { dpm, admin ->
                DnsShield.apply(context, dpm, admin)
            }
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Failed executing DnsShield.apply in resilience update")
        }
    }

    fun recordNetworkSafe(context: Context, action: String, isSuccess: Boolean, details: String) {
        try {
            BlackBoxRecorder.recordNetwork(context, action, isSuccess = isSuccess, details = details)
        } catch (e: Exception) {
            Timber.tag(TAG).d(e, "BlackBoxRecorder.recordNetwork skipped for $action")
        }
    }
}
