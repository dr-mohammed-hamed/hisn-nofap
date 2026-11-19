package com.drmohammed.antiporn.watchdog

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.drmohammed.antiporn.DnsWatchdogService
import com.drmohammed.antiporn.HisnDeviceAdminService
import com.drmohammed.antiporn.core.engine.ProtectionTransitionGuard
import com.drmohammed.antiporn.telemetry.SafeDpmGateway
import com.drmohammed.antiporn.telemetry.BlackBoxRecorder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Central coordinator for watchdog lifecycle, fail-safe OEM binding verification, and fallback recovery.
 *
 * Guarantees zero-notification [HisnDeviceAdminService] operation as primary on Android 8+ (API 26+),
 * quiet background operation on Android 7 (API 24-25), and maintains an emergency FGS fallback
 * only for non-standard/defective OEM ROMs.
 */
object WatchdogOrchestrator {

    private const val TAG = "AntiPorn_WatchdogOrchestrator"

    private const val GRACE_PERIOD_MS = 10_000L
    private const val POLL_INTERVAL_MS = 500L
    private const val POST_NUDGE_WAIT_MS = 5_000L
    private const val NUDGE_COOLDOWN_MS = 30 * 60_000L // At most once every 30 minutes

    private val isCheckingFallback = AtomicBoolean(false)
    private val lastNudgeTimestamp = AtomicLong(0L)

    private val orchestratorJob = SupervisorJob()
    private val orchestratorScope = CoroutineScope(orchestratorJob + Dispatchers.Default)

    /**
     * Non-blocking entry point to ensure watchdog protection is active across the system.
     * Safe to invoke from [MainActivity.onResume], [ProtectionEngine.applyAllSync],
     * and broadcast receivers within strict [android.content.BroadcastReceiver.goAsync] timeouts.
     */
    fun ensureActive(context: Context) {
        val appContext = context.applicationContext

        if (ProtectionTransitionGuard.isTeardownInProgress) {
            Timber.tag(TAG).d("Teardown in progress. Bypassing ensureActive.")
            return
        }

        if (!SafeDpmGateway.isDeviceOwner(appContext)) {
            Timber.tag(TAG).d("Device Owner not active. Bypassing ensureActive.")
            return
        }

        // ==========================================
        // 1. Android 7.0–7.1 (API 24–25): Legacy Background Service (Zero Notification)
        // ==========================================
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            if (!ProtectionWatchdogRuntime.isRunning) {
                try {
                    val intent = Intent(appContext, DnsWatchdogService::class.java)
                    appContext.startService(intent)
                } catch (e: Exception) {
                    Timber.tag(TAG).e(e, "Failed starting legacy background DnsWatchdogService on API < 26")
                }
            }
            return
        }

        // ==========================================
        // 2. Android 8.0+ (API 26+): DeviceAdminService
        // ==========================================
        // Constraint 5: Ensure component is not in a stuck disabled state
        ensureAdminServiceComponentEnabled(appContext)

        if (HisnDeviceAdminService.isBound) {
            ProtectionWatchdogRuntime.attach(
                context = appContext,
                host = ProtectionWatchdogRuntime.WatchdogHost.DEVICE_ADMIN_SERVICE
            )
            if (DnsWatchdogService.isRunning) {
                try {
                    appContext.stopService(Intent(appContext, DnsWatchdogService::class.java))
                } catch (e: Exception) {
                    Timber.tag(TAG).w(e, "Error stopping lingering DnsWatchdogService while bound")
                }
            }
            return
        }

        // DPMS binds asynchronously after boot/provisioning/update.
        // Verify in an asynchronous non-blocking coroutine before deciding on fallback.
        if (!isCheckingFallback.compareAndSet(false, true)) {
            Timber.tag(TAG).d("Fallback verification already in flight; skipping concurrent request.")
            return
        }

        orchestratorScope.launch {
            try {
                // Phase A: Grace period polling (10s, 500ms intervals)
                val graceDeadline = SystemClock.elapsedRealtime() + GRACE_PERIOD_MS
                while (SystemClock.elapsedRealtime() < graceDeadline) {
                    if (HisnDeviceAdminService.isBound) {
                        Timber.tag(TAG).i("HisnDeviceAdminService bound successfully during grace window.")
                        return@launch
                    }
                    if (ProtectionTransitionGuard.isTeardownInProgress || !SafeDpmGateway.isDeviceOwner(appContext)) {
                        Timber.tag(TAG).d("Teardown initiated or not Device Owner during watchdog grace period; aborting.")
                        return@launch
                    }
                    delay(POLL_INTERVAL_MS)
                }

                if (HisnDeviceAdminService.isBound || ProtectionTransitionGuard.isTeardownInProgress || !SafeDpmGateway.isDeviceOwner(appContext)) return@launch

                // Phase B: Component Toggle Nudge (if cooldown permits)
                val now = SystemClock.elapsedRealtime()
                val lastNudge = lastNudgeTimestamp.get()
                if (now - lastNudge >= NUDGE_COOLDOWN_MS || lastNudge == 0L) {
                    lastNudgeTimestamp.set(now)
                    Timber.tag(TAG).w("HisnDeviceAdminService not bound after grace. Performing component toggle nudge...")
                    withContext(NonCancellable) {
                        performComponentNudge(appContext)
                    }

                    // Wait post-nudge grace window
                    val postNudgeDeadline = SystemClock.elapsedRealtime() + POST_NUDGE_WAIT_MS
                    while (SystemClock.elapsedRealtime() < postNudgeDeadline) {
                        if (HisnDeviceAdminService.isBound) {
                            Timber.tag(TAG).i("HisnDeviceAdminService bound successfully after component nudge.")
                            return@launch
                        }
                        if (ProtectionTransitionGuard.isTeardownInProgress || !SafeDpmGateway.isDeviceOwner(appContext)) return@launch
                        delay(POLL_INTERVAL_MS)
                    }
                }

                // Phase C: Emergency FGS Fallback (Defective OEM ROM recovery)
                if (!HisnDeviceAdminService.isBound &&
                    !ProtectionTransitionGuard.isTeardownInProgress &&
                    SafeDpmGateway.isDeviceOwner(appContext)
                ) {
                    Timber.tag(TAG).w("HisnDeviceAdminService binding failed completely. Activating emergency FGS fallback.")
                    triggerFgsFallback(appContext)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Error during watchdog fallback verification sequence")
            } finally {
                isCheckingFallback.set(false)
            }
        }
    }

    /**
     * Unconditionally halts all watchdog observation and services.
     * Invoked during [com.drmohammed.antiporn.core.engine.DeviceOwnerTeardownManager.executeTeardown]
     * or [com.drmohammed.antiporn.AdminReceiver.onDisabled].
     */
    fun stopAll(context: Context) {
        val appContext = context.applicationContext
        Timber.tag(TAG).w("Halting all watchdog operations and services")

        ProtectionWatchdogRuntime.forceStop()
        isCheckingFallback.set(false)

        try {
            appContext.stopService(Intent(appContext, DnsWatchdogService::class.java))
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Error stopping DnsWatchdogService during stopAll")
        }
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.O)
    private fun ensureAdminServiceComponentEnabled(appContext: Context) {
        try {
            val component = ComponentName(appContext.packageName, "com.drmohammed.antiporn.HisnDeviceAdminService")
            val pm = appContext.packageManager
            val currentState = pm.getComponentEnabledSetting(component)
            if (currentState == PackageManager.COMPONENT_ENABLED_STATE_DISABLED) {
                Timber.tag(TAG).w("Restoring disabled HisnDeviceAdminService component to ENABLED")
                pm.setComponentEnabledSetting(
                    component,
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                    PackageManager.DONT_KILL_APP
                )
            }
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Failed verifying HisnDeviceAdminService component enabled setting")
        }
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.O)
    private suspend fun performComponentNudge(appContext: Context) {
        try {
            val component = ComponentName(appContext.packageName, "com.drmohammed.antiporn.HisnDeviceAdminService")
            val pm = appContext.packageManager
            pm.setComponentEnabledSetting(
                component,
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP
            )
            delay(400L)
            pm.setComponentEnabledSetting(
                component,
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP
            )
            Timber.tag(TAG).d("Component nudge toggled successfully for HisnDeviceAdminService")
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Error executing component nudge for HisnDeviceAdminService")
        }
    }

    private fun triggerFgsFallback(appContext: Context) {
        try {
            val intent = Intent(appContext, DnsWatchdogService::class.java).apply {
                action = DnsWatchdogService.ACTION_EMERGENCY_FALLBACK
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ContextCompat.startForegroundService(appContext, intent)
            } else {
                appContext.startService(intent)
            }
            BlackBoxRecorder.record(
                context = appContext,
                tag = BlackBoxRecorder.TAG_SHIELD,
                actionName = "WATCHDOG_FGS_FALLBACK_ACTIVATED",
                isSuccess = true,
                metadata = "reason=device_admin_unbound"
            )
        } catch (e: Exception) {
            // Handles ForegroundServiceStartNotAllowedException on Android 12+ (API 31+) safely
            Timber.tag(TAG).e(e, "Failed to start emergency FGS fallback service")
            BlackBoxRecorder.record(
                context = appContext,
                tag = BlackBoxRecorder.TAG_SHIELD,
                actionName = "WATCHDOG_FGS_FALLBACK_FAILED",
                isSuccess = false,
                errorDetails = e.message
            )
        }
    }
}
