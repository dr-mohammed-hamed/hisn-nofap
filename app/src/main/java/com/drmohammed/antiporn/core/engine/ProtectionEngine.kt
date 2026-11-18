package com.drmohammed.antiporn.core.engine

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.util.Log
import com.drmohammed.antiporn.AdminReceiver
import com.drmohammed.antiporn.DnsWatchdogService
import com.drmohammed.antiporn.R
import com.drmohammed.antiporn.core.contract.ProtectionShield
import com.drmohammed.antiporn.core.model.ShieldResult
import com.drmohammed.antiporn.core.model.ShieldStatus
import com.drmohammed.antiporn.shields.AppSuspensionShield
import com.drmohammed.antiporn.shields.BrowserShield
import com.drmohammed.antiporn.shields.CurfewShield
import com.drmohammed.antiporn.shields.DnsShield
import com.drmohammed.antiporn.shields.SystemTamperShield
import com.drmohammed.antiporn.shields.VpnShield
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Central orchestrator engine coordinating all pluggable protection shields.
 * Manages background execution, thread-safe callback dispatch, and system status evaluation.
 */
object ProtectionEngine {

    private const val TAG = "AntiPorn_Engine"

    val shields: List<ProtectionShield> = listOf(
        SystemTamperShield,
        VpnShield,
        CurfewShield,
        DnsShield,
        BrowserShield,
        AppSuspensionShield
    )

    private val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val pendingCallbacks = CopyOnWriteArrayList<(List<ShieldResult>) -> Unit>()
    private val isEnforcing = AtomicBoolean(false)
    @Volatile
    private var needsRerun: Boolean = false
    private val pendingDnsCallbacks = CopyOnWriteArrayList<(ShieldResult) -> Unit>()
    private val isDnsFastEnforcing = AtomicBoolean(false)
    @Volatile
    private var needsDnsFastRerun: Boolean = false
    private val lastRecordedShieldFailures = java.util.concurrent.ConcurrentHashMap<String, String>()

    fun getAdminComponent(context: Context): ComponentName {
        return ComponentName(context, AdminReceiver::class.java)
    }

    /**
     * Suspending version: Applies all registered security shields asynchronously on [Dispatchers.IO].
     */
    suspend fun applyAll(context: Context): List<ShieldResult> = withContext(Dispatchers.IO) {
        applyAllSync(context.applicationContext ?: context)
    }

    /**
     * Applies all registered security shields asynchronously on a background coroutine.
     * Prevents UI freezing and drops no callbacks by safely queuing concurrent requests.
     */
    fun applyAllAsync(context: Context, onComplete: ((List<ShieldResult>) -> Unit)? = null) {
        synchronized(pendingCallbacks) {
            if (onComplete != null) {
                pendingCallbacks.add(onComplete)
            }

            if (!isEnforcing.compareAndSet(false, true)) {
                needsRerun = true
                Log.i(TAG, "Enforcement in progress, queued callback and scheduled rerun for completion notification")
                return
            }
            needsRerun = false
        }

        val appContext = context.applicationContext ?: context
        engineScope.launch {
            while (true) {
                val results = try {
                    applyAllSync(appContext)
                } catch (t: Throwable) {
                    Log.e(TAG, "Fatal error executing applyAllSync", t)
                    listOf(
                        ShieldResult(
                            shieldId = "engine",
                            success = false,
                            message = appContext.getString(R.string.toast_error_applying, t.localizedMessage ?: "Unknown")
                        )
                    )
                }

                val callbacksToNotify: List<(List<ShieldResult>) -> Unit>? = synchronized(pendingCallbacks) {
                    if (needsRerun) {
                        needsRerun = false
                        null
                    } else {
                        val copy = pendingCallbacks.toList()
                        pendingCallbacks.clear()
                        isEnforcing.set(false)
                        copy
                    }
                }

                if (callbacksToNotify != null) {
                    withContext(Dispatchers.Main) {
                        for (cb in callbacksToNotify) {
                            try {
                                cb.invoke(results)
                            } catch (e: Exception) {
                                Log.e(TAG, "Error invoking completion callback", e)
                            }
                        }
                    }
                    break
                }
            }
        }
    }

    /**
     * Enforces ONLY DnsShield asynchronously on a dedicated background fast-path
     * without waiting behind the other 5 shields (SystemTamper, VPN, Curfew, Browser, AppSuspension).
     * Conflates rapid concurrent requests cleanly via AtomicBoolean + rerun flag.
     */
    fun applyDnsShieldFastAsync(context: Context, onComplete: ((ShieldResult) -> Unit)? = null) {
        synchronized(pendingDnsCallbacks) {
            if (onComplete != null) {
                pendingDnsCallbacks.add(onComplete)
            }

            if (!isDnsFastEnforcing.compareAndSet(false, true)) {
                needsDnsFastRerun = true
                Log.d(TAG, "DNS fast-path enforcement in progress, conflated request and scheduled rerun")
                return
            }
            needsDnsFastRerun = false
        }

        val appContext = context.applicationContext ?: context
        engineScope.launch {
            var released = false
            try {
                while (true) {
                    val result = try {
                        applyDnsShieldFastSync(appContext)
                    } catch (t: Throwable) {
                        if (t is kotlinx.coroutines.CancellationException) throw t
                        Log.e(TAG, "Fatal error executing applyDnsShieldFastSync", t)
                        ShieldResult(
                            shieldId = DnsShield.id,
                            success = false,
                            message = appContext.getString(R.string.toast_error_applying, t.localizedMessage ?: "Unknown")
                        )
                    }

                    val callbacksToNotify: List<(ShieldResult) -> Unit>? = synchronized(pendingDnsCallbacks) {
                        if (needsDnsFastRerun) {
                            needsDnsFastRerun = false
                            null
                        } else {
                            val copy = pendingDnsCallbacks.toList()
                            pendingDnsCallbacks.clear()
                            isDnsFastEnforcing.set(false)
                            released = true
                            copy
                        }
                    }

                    if (callbacksToNotify != null) {
                        if (callbacksToNotify.isNotEmpty()) {
                            withContext(Dispatchers.Main) {
                                for (cb in callbacksToNotify) {
                                    try {
                                        cb.invoke(result)
                                    } catch (e: Exception) {
                                        Log.e(TAG, "Error invoking DNS fast-path completion callback", e)
                                    }
                                }
                            }
                        }
                        break
                    }
                }
            } finally {
                if (!released) {
                    synchronized(pendingDnsCallbacks) {
                        if (!released) {
                            pendingDnsCallbacks.clear()
                            needsDnsFastRerun = false
                            isDnsFastEnforcing.set(false)
                        }
                    }
                }
            }
        }
    }

    /**
     * Synchronously enforces ONLY [DnsShield] for standalone DoT reinforcement.
     * Deliberately does NOT hold a [ProtectionTransitionGuard.runInternalTransition] token during
     * potentially blocking (5-8s) DPM TLS probes, so repeated tamper attempts still get healed in
     * ~1-3ms by `tryFastHealGlobalPrivateDns`. `applyStandaloneDot` calls `markInternalDnsMutation()`
     * to keep the post-mutation settle window intact.
     * MUST be called from a background thread.
     */
    private fun applyDnsShieldFastSync(context: Context): ShieldResult {
        val appContext = context.applicationContext ?: context
        if (ProtectionTransitionGuard.isTeardownInProgress) {
            Log.d(TAG, "Skipping applyDnsShieldFastSync because teardown is in progress")
            return ShieldResult(
                shieldId = DnsShield.id,
                success = false,
                message = appContext.getString(R.string.card_dns_inactive)
            )
        }
        if (ProtectionTransitionGuard.hasActiveTransitions()) {
            Log.d(TAG, "Skipping applyDnsShieldFastSync because an internal transition is already active")
            return ShieldResult(
                shieldId = DnsShield.id,
                success = false,
                message = appContext.getString(R.string.card_dns_inactive)
            )
        }
        if (com.drmohammed.antiporn.curfew.CurfewVpnDnsBridge.isLocalVpnExpectedActive(appContext)) {
            Log.d(TAG, "Skipping applyDnsShieldFastSync because local VPN mode is expected active")
            return ShieldResult(
                shieldId = DnsShield.id,
                success = true,
                message = appContext.getString(R.string.shield_dns_local_filter_active)
            )
        }
        if (!isDeviceOwner(appContext)) {
            Log.w(TAG, "App is not Device/Profile Owner. Cannot run DNS fast-path enforcement.")
            return ShieldResult(
                shieldId = DnsShield.id,
                success = false,
                message = appContext.getString(R.string.toast_not_device_owner)
            )
        }

        val dpmResult = com.drmohammed.antiporn.telemetry.SafeDpmGateway.executeRawBlocking(
            context = appContext,
            tag = com.drmohammed.antiporn.telemetry.BlackBoxRecorder.TAG_SHIELD,
            actionName = "applyDnsShieldFast",
            requireDeviceOwner = true
        ) { dpm, admin ->
            DnsShield.apply(appContext, dpm, admin)
        }

        return dpmResult.getOrNull() ?: ShieldResult(
            shieldId = DnsShield.id,
            success = false,
            message = appContext.getString(R.string.toast_dpm_missing)
        )
    }

    /**
     * Synchronously applies all shields in sequence.
     * MUST be called from a background thread.
     */
    fun applyAllSync(context: Context): List<ShieldResult> {
        return ProtectionTransitionGuard.runInternalTransition {
            val appContext = context.applicationContext ?: context
            if (!isDeviceOwner(appContext)) {
                Log.w(TAG, "App is not Device/Profile Owner. Cannot apply shields.")
                return@runInternalTransition listOf(
                    ShieldResult(
                        shieldId = "engine",
                        success = false,
                        message = appContext.getString(R.string.toast_not_device_owner)
                    )
                )
            }

            val dpmResult = com.drmohammed.antiporn.telemetry.SafeDpmGateway.executeRawBlocking(
                context = appContext,
                tag = com.drmohammed.antiporn.telemetry.BlackBoxRecorder.TAG_SHIELD,
                actionName = "applyAllShieldsSync",
                requireDeviceOwner = true
            ) { dpm, admin ->
                val results = mutableListOf<ShieldResult>()
                for (shield in shields) {
                    try {
                        val res = shield.apply(appContext, dpm, admin)
                        results.add(res)
                        Log.d(TAG, "Applied shield [${shield.id}]: success=${res.success}")
                    } catch (e: Exception) {
                        Log.e(TAG, "Exception applying shield [${shield.id}]", e)
                        results.add(
                            ShieldResult(
                                shieldId = shield.id,
                                success = false,
                                message = appContext.getString(R.string.toast_error_applying, e.localizedMessage ?: "Unknown")
                            )
                        )
                    }
                }
                results
            }

            val results = dpmResult.getOrNull() ?: listOf(
                ShieldResult(
                    shieldId = "engine",
                    success = false,
                    message = appContext.getString(R.string.toast_dpm_missing)
                )
            )

            // If critical shields applied successfully, mark initial baseline as established
            val criticalResults = results.filter { it.shieldId in listOf("system_tamper", "vpn", "dns") }
            val criticalShieldsSuccess = criticalResults.isNotEmpty() && criticalResults.all { it.success }
            if (criticalShieldsSuccess || (results.isNotEmpty() && results.all { it.success })) {
                ProtectionTransitionGuard.markInitialEnforcementCompleted(appContext)
            }

            if (!DnsWatchdogService.isRunning && !ProtectionTransitionGuard.isTeardownInProgress) {
                try {
                    DnsWatchdogService.startService(appContext)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to start watchdog service", e)
                }
            }

            results
        }
    }

    /**
     * Evaluates the active status of every registered shield.
     */
    fun evaluateSystemStatus(context: Context): Map<String, ShieldStatus> {
        val appContext = context.applicationContext ?: context
        if (!isDeviceOwner(appContext)) {
            return shields.associate { it.id to ShieldStatus.Disabled(appContext.getString(R.string.card_device_owner_inactive)) }
        }

        val dpmResult = com.drmohammed.antiporn.telemetry.SafeDpmGateway.executeRawBlocking(
            context = appContext,
            tag = com.drmohammed.antiporn.telemetry.BlackBoxRecorder.TAG_SHIELD,
            actionName = "evaluateSystemStatus",
            requireDeviceOwner = true,
            recordSuccess = false
        ) { dpm, admin ->
            val statusMap = mutableMapOf<String, ShieldStatus>()
            for (shield in shields) {
                val status = try {
                    shield.checkStatus(appContext, dpm, admin)
                } catch (e: Exception) {
                    Log.e(TAG, "Error checking status for shield [${shield.id}]", e)
                    ShieldStatus.NeedsEnforcement(appContext.getString(R.string.card_device_owner_inactive))
                }
                statusMap[shield.id] = status

                if (status !is ShieldStatus.Active && status !is ShieldStatus.Unsupported) {
                    val failureKey = when (status) {
                        is ShieldStatus.NeedsEnforcement -> "NEEDS_ENFORCEMENT: ${status.reason}"
                        is ShieldStatus.Disabled -> "DISABLED: ${status.warning}"
                        else -> "INACTIVE"
                    }
                    if (lastRecordedShieldFailures.put(shield.id, failureKey) != failureKey) {
                        com.drmohammed.antiporn.telemetry.BlackBoxRecorder.recordShield(
                            context = appContext,
                            shieldId = shield.id,
                            isSuccess = false,
                            details = failureKey
                        )
                    }
                } else if (status is ShieldStatus.Active) {
                    val previousFailure = lastRecordedShieldFailures.remove(shield.id)
                    if (previousFailure != null) {
                        com.drmohammed.antiporn.telemetry.BlackBoxRecorder.recordShield(
                            context = appContext,
                            shieldId = shield.id,
                            isSuccess = true,
                            details = "RECOVERED"
                        )
                    }
                }
            }
            statusMap
        }

        val statusMap = dpmResult.getOrNull()
        if (statusMap != null) {
            val allHealthy = statusMap.isNotEmpty() && statusMap.values.all {
                it is ShieldStatus.Active || it is ShieldStatus.Unsupported
            }
            if (allHealthy) {
                com.drmohammed.antiporn.telemetry.BlackBoxRecorder.markHealthyShieldCheck(appContext)
            }
            return statusMap
        }
        return shields.associate { it.id to ShieldStatus.Disabled(appContext.getString(R.string.toast_dpm_missing)) }
    }

    /**
     * Checks if the app is currently the active Device Owner or Profile Owner.
     */
    fun isDeviceOwner(context: Context): Boolean {
        return com.drmohammed.antiporn.telemetry.SafeDpmGateway.isDeviceOwner(context)
    }

    /**
     * Determines whether all shields are actively protecting the device.
     */
    fun isSystemFullyProtected(context: Context): Boolean {
        if (!isDeviceOwner(context)) return false
        val statuses = evaluateSystemStatus(context)
        return statuses.values.all { it is ShieldStatus.Active || it is ShieldStatus.Unsupported }
    }
}
