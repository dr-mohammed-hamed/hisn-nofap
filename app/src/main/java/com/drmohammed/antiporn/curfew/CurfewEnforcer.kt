package com.drmohammed.antiporn.curfew

import android.content.Context
import android.content.pm.PackageManager
import com.drmohammed.antiporn.core.engine.ProtectionTransitionGuard
import com.drmohammed.antiporn.core.model.DevicePolicyBundle
import com.drmohammed.antiporn.core.time.TrustedTimeProvider
import com.drmohammed.antiporn.data.repository.WardActiveModesTelemetrySyncer
import com.drmohammed.antiporn.fortress.engine.CurfewWindowOverrideStore
import com.drmohammed.antiporn.fortress.engine.EffectiveRestrictionResolver
import com.drmohammed.antiporn.fortress.engine.FortressSessionController
import com.drmohammed.antiporn.telemetry.BlackBoxRecorder
import com.drmohammed.antiporn.telemetry.SafeDpmGateway
import com.drmohammed.antiporn.ui.control.gate.SelfControlAccessGateRegistry
import com.drmohammed.antiporn.ui.control.gate.SelfControlSection
import com.drmohammed.antiporn.ui.control.qr.SelfControlQrManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.util.Calendar

/**
 * Coordinates night curfew & fortress schedule evaluation, snooze/window-override invariants,
 * and synchronized entry points to [CurfewLifecycleHandler].
 */
object CurfewEnforcer {

    private const val TAG = "AntiPorn_CurfewEnforcer"
    private val enforcerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun isScheduleWindowActive(
        calendar: Calendar,
        startHour: Int,
        startMinute: Int,
        endHour: Int,
        endMinute: Int,
        isWeekendCurfewEnabled: Boolean,
        weekendStartHour: Int,
        weekendStartMinute: Int,
        weekendEndHour: Int,
        weekendEndMinute: Int
    ): Boolean = CurfewScheduleExt.isScheduleWindowActive(
        calendar = calendar,
        startHour = startHour,
        startMinute = startMinute,
        endHour = endHour,
        endMinute = endMinute,
        isWeekendCurfewEnabled = isWeekendCurfewEnabled,
        weekendStartHour = weekendStartHour,
        weekendStartMinute = weekendStartMinute,
        weekendEndHour = weekendEndHour,
        weekendEndMinute = weekendEndMinute
    )

    fun isCurfewActive(calendar: Calendar = CurfewConfig.getCurfewCalendar()): Boolean =
        isScheduleWindowActive(
            calendar = calendar,
            startHour = CurfewConfig.START_HOUR,
            startMinute = CurfewConfig.START_MINUTE,
            endHour = CurfewConfig.END_HOUR,
            endMinute = CurfewConfig.END_MINUTE,
            isWeekendCurfewEnabled = false,
            weekendStartHour = CurfewConfig.START_HOUR,
            weekendStartMinute = CurfewConfig.START_MINUTE,
            weekendEndHour = CurfewConfig.END_HOUR,
            weekendEndMinute = CurfewConfig.END_MINUTE
        )

    fun isCurfewActive(
        context: Context,
        calendar: Calendar = CurfewConfig.getCurfewCalendar(context),
        useFailSecureEnabled: Boolean = false
    ): Boolean {
        val enabled = if (useFailSecureEnabled) {
            CurfewConfig.isCurfewEnabledFailSecure(context)
        } else {
            CurfewConfig.isCurfewEnabled(context)
        }
        if (!enabled) return false
        return isScheduleWindowActive(
            calendar = calendar,
            startHour = CurfewConfig.getCurfewStartHour(context),
            startMinute = CurfewConfig.getCurfewStartMinute(context),
            endHour = CurfewConfig.getCurfewEndHour(context),
            endMinute = CurfewConfig.getCurfewEndMinute(context),
            isWeekendCurfewEnabled = CurfewConfig.isWeekendCurfewEnabled(context),
            weekendStartHour = CurfewConfig.getWeekendCurfewStartHour(context),
            weekendStartMinute = CurfewConfig.getWeekendCurfewStartMinute(context),
            weekendEndHour = CurfewConfig.getWeekendCurfewEndHour(context),
            weekendEndMinute = CurfewConfig.getWeekendCurfewEndMinute(context)
        )
    }

    fun isCurfewFeatureEnabled(context: Context): Boolean = CurfewConfig.isCurfewEnabled(context)

    @Deprecated(
        message = "DevicePolicyBundle.isCurfewEnabled is the single source of truth; update via PolicyRepository."
    )
    fun setCurfewFeatureEnabled(context: Context, enabled: Boolean) {
        Timber.tag(TAG).w("Ignoring deprecated setCurfewFeatureEnabled(%b); PolicyRepository is authoritative", enabled)
        syncBlockState(context)
    }

    fun isManualTestActive(context: Context): Boolean =
        context.getSharedPreferences(CurfewConfig.PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(CurfewConfig.KEY_MANUAL_TEST_ACTIVE, false)

    @Synchronized
    fun setManualTestActive(context: Context, active: Boolean): Boolean {
        if (active) {
            CurfewWindowOverrideStore.clearOverride(context)
        }
        context.getSharedPreferences(CurfewConfig.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(CurfewConfig.KEY_MANUAL_TEST_ACTIVE, active).commit()
        return syncBlockState(context)
    }

    fun setManualTestActiveAsync(context: Context, active: Boolean, onComplete: ((Boolean) -> Unit)? = null) {
        val appContext = context.applicationContext ?: context
        enforcerScope.launch {
            var success = false
            try {
                success = setManualTestActive(appContext, active)
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Error executing setManualTestActiveAsync")
            } finally {
                if (onComplete != null) {
                    withContext(Dispatchers.Main) { onComplete.invoke(success) }
                }
            }
        }
    }

    fun isSnoozeActive(context: Context): Boolean {
        val snoozeEnd = getSnoozeEndWall(context)
        return TrustedTimeProvider.currentTimeMillis(context) < snoozeEnd
    }

    fun getSnoozeEndWall(context: Context): Long =
        context.getSharedPreferences(CurfewConfig.PREFS_NAME, Context.MODE_PRIVATE)
            .getLong(CurfewConfig.KEY_CURFEW_SNOOZE_END_WALL, 0L)

    @Synchronized
    fun clearSnooze(context: Context) {
        context.getSharedPreferences(CurfewConfig.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().remove(CurfewConfig.KEY_CURFEW_SNOOZE_END_WALL).commit()
        Timber.tag(TAG).i("Curfew snooze cleared")
    }

    /**
     * Requests a temporary curfew snooze. Rejected whenever a Fortress session is active
     * because Fortresses never yield to curfew snooze.
     */
    @Synchronized
    fun snoozeCurfew(context: Context, minutes: Int = 15): Boolean {
        if (FortressSessionController.isFortressActive(context)) {
            Timber.tag(TAG).w("Curfew snooze rejected: Fortress session is currently active")
            return false
        }
        val safeMinutes = minutes.coerceIn(1, 60)
        val snoozeEnd = TrustedTimeProvider.currentTimeMillis(context) + (safeMinutes * 60 * 1000L)
        context.getSharedPreferences(CurfewConfig.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putLong(CurfewConfig.KEY_CURFEW_SNOOZE_END_WALL, snoozeEnd).commit()
        CurfewScheduler.scheduleCurfewSnoozeAlarm(context, snoozeEnd)
        val released = enforce(context, false)
        Timber.tag(TAG).i("Curfew snoozed for $safeMinutes minutes until $snoozeEnd (released=$released)")
        return released
    }

    fun isEffectiveCurfewActive(context: Context): Boolean {
        if (!isCurfewFeatureEnabled(context)) return false
        if (isSnoozeActive(context)) return false
        if (isManualTestActive(context)) return true
        if (CurfewWindowOverrideStore.isWindowOverridden(context)) return false
        return isCurfewActive(context)
    }

    /**
     * Effective block = active Night Curfew OR active Fortress session.
     */
    fun isEffectiveBlockActive(context: Context): Boolean =
        isEffectiveCurfewActive(context) || FortressSessionController.isFortressActive(context)

    /**
     * Unlocks the current Night Curfew window directly when QR lock is disabled and
     * supervision policy permits [SelfControlSection.FORTRESS_UNLOCK].
     */
    @Synchronized
    fun unlockCurfewForCurrentWindowDirect(context: Context): Boolean {
        val appContext = context.applicationContext ?: context
        if (SelfControlQrManager.getInstance(appContext).isQrLockEnabled()) {
            BlackBoxRecorder.recordGate(appContext, "CURFEW_UNLOCK_REJECTED_QR_REQUIRED", isSuccess = false, error = "QR lock is enabled")
            return false
        }
        if (!SelfControlAccessGateRegistry.isSectionPermitted(appContext, SelfControlSection.FORTRESS_UNLOCK)) {
            BlackBoxRecorder.recordGate(appContext, "CURFEW_UNLOCK_DENIED_BY_GUARDIAN", isSuccess = false, error = "FORTRESS_UNLOCK not permitted")
            return false
        }
        applyCurfewWindowOverride(appContext)
        BlackBoxRecorder.recordGate(appContext, "CURFEW_WINDOW_UNLOCKED_DIRECT", isSuccess = true)
        return true
    }

    /**
     * Unlocks the current Night Curfew window via verified QR token or completed emergency bypass,
     * subject to supervision permission [SelfControlSection.FORTRESS_UNLOCK].
     */
    @Synchronized
    fun unlockCurfewForCurrentWindowWithQr(
        context: Context,
        rawScannedToken: String,
        isEmergencyBypassCompleted: Boolean = false
    ): Boolean {
        val appContext = context.applicationContext ?: context
        if (!SelfControlAccessGateRegistry.isSectionPermitted(appContext, SelfControlSection.FORTRESS_UNLOCK)) {
            BlackBoxRecorder.recordGate(appContext, "CURFEW_QR_UNLOCK_DENIED_BY_GUARDIAN", isSuccess = false, error = "FORTRESS_UNLOCK not permitted")
            return false
        }
        val qrManager = SelfControlQrManager.getInstance(appContext)
        if (isEmergencyBypassCompleted) {
            if (qrManager.isQrLockEnabled()) {
                Timber.tag(TAG).w("Rejected curfew emergency bypass unlock: QR lock is still enabled")
                return false
            }
            applyCurfewWindowOverride(appContext)
            BlackBoxRecorder.recordGate(appContext, "CURFEW_WINDOW_UNLOCKED_QR_EMERGENCY_BYPASS", isSuccess = true)
            return true
        }
        if (!qrManager.isQrLockEnabled() || !qrManager.verifyToken(rawScannedToken)) {
            BlackBoxRecorder.recordGate(appContext, "CURFEW_QR_UNLOCK_INVALID_TOKEN", isSuccess = false, error = "QR token verification failed")
            return false
        }
        applyCurfewWindowOverride(appContext)
        BlackBoxRecorder.recordGate(appContext, "CURFEW_WINDOW_UNLOCKED_QR", isSuccess = true)
        return true
    }

    @Synchronized
    internal fun applyCurfewWindowOverride(
        context: Context,
        policy: DevicePolicyBundle? = null,
        syncAndNotify: Boolean = true
    ) {
        val appContext = context.applicationContext ?: context
        val snapshot = EffectiveRestrictionResolver.getActiveModesSnapshot(appContext, policy = policy)
        if (snapshot.nightCurfewWindowEndWallMs > 0L) {
            CurfewWindowOverrideStore.setOverrideUntil(appContext, snapshot.nightCurfewWindowEndWallMs)
        }
        if (isManualTestActive(appContext)) {
            appContext.getSharedPreferences(CurfewConfig.PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putBoolean(CurfewConfig.KEY_MANUAL_TEST_ACTIVE, false).commit()
        }
        clearSnooze(appContext)
        CurfewScheduler.cancelCurfewSnoozeAlarm(appContext)
        CurfewAppResolver.invalidateCache()
        if (syncAndNotify) {
            syncBlockState(appContext)
            runCatching { WardActiveModesTelemetrySyncer.syncAsync(appContext) }
        }
    }

    internal fun isPackageInstalled(
        pm: PackageManager,
        packageName: String,
        includeHidden: Boolean = false
    ): Boolean = CurfewLifecycleHandler.isPackageInstalled(pm, packageName, includeHidden)

    internal fun isVpnTeardownAllowedOnReleaseFailSecure(
        fetchPolicyFailed: Boolean,
        instantKill: Boolean,
        isVpnTeardownNeeded: Boolean,
        isAlwaysOnConfigured: Boolean
    ): Boolean = !fetchPolicyFailed && !instantKill && (isVpnTeardownNeeded || isAlwaysOnConfigured)

    internal fun shouldExecuteReleaseCleanup(
        isVpnTeardownNeeded: Boolean,
        isAlwaysOnConfigured: Boolean,
        wasBlockEnforced: Boolean,
        hasTrackedSuspendedPackages: Boolean,
        hasLingeringSuspendedPackages: Boolean
    ): Boolean = isVpnTeardownNeeded ||
        isAlwaysOnConfigured ||
        wasBlockEnforced ||
        hasTrackedSuspendedPackages ||
        hasLingeringSuspendedPackages

    @Synchronized
    internal fun awaitIdleForTeardown() = Unit

    @Synchronized
    fun enforce(
        context: Context,
        activate: Boolean,
        calendar: Calendar = CurfewConfig.getCurfewCalendar(context)
    ): Boolean {
        if (ProtectionTransitionGuard.isTeardownInProgress) {
            Timber.tag(TAG).d("Skipping CurfewEnforcer.enforce because teardown is in progress")
            return false
        }
        if (!SafeDpmGateway.isDeviceOwner(context)) {
            Timber.tag(TAG).w("Cannot enforce curfew: Not Device/Profile Owner")
            return false
        }

        val nowWallMs = TrustedTimeProvider.currentTimeMillis(context)
        val fortressActive = FortressSessionController.isFortressActive(context)
        val isCurfewOverridden = CurfewWindowOverrideStore.isWindowOverridden(context, nowWallMs)
        val isCurfewEffective = isCurfewActive(context, calendar, useFailSecureEnabled = true)
        if (!activate && (fortressActive || (!isSnoozeActive(context) && !isCurfewOverridden && isCurfewEffective))) {
            Timber.tag(TAG).w("Security Alert: Block release rejected - night curfew window or active fortress still holds")
            BlackBoxRecorder.recordCurfew(
                context,
                "RELEASE_REJECTED",
                isSuccess = false,
                error = "Security invariant: release rejected (effective=$isCurfewEffective, fortress=$fortressActive, overridden=$isCurfewOverridden)"
            )
            return true
        }

        return if (activate) {
            val effectiveProfile = EffectiveRestrictionResolver.resolve(context, calendar)
            CurfewLifecycleHandler.enforceActivation(context, effectiveProfile)
        } else {
            CurfewLifecycleHandler.enforceRelease(context)
        }
    }

    @Synchronized
    fun syncCurfewState(context: Context): Boolean {
        val shouldBeActive = isEffectiveBlockActive(context)
        Timber.tag(TAG).d("Syncing curfew/block state: shouldBeActive=%b", shouldBeActive)
        val ok = enforce(context, shouldBeActive)
        if (!ProtectionTransitionGuard.isTeardownInProgress) {
            runCatching { WardActiveModesTelemetrySyncer.syncAsync(context) }
        }
        return ok
    }

    fun syncCurfewStateAsync(context: Context, onComplete: ((Boolean) -> Unit)? = null) {
        val appContext = context.applicationContext ?: context
        enforcerScope.launch {
            var success = false
            try {
                success = syncCurfewState(appContext)
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Error in syncCurfewStateAsync")
            } finally {
                if (onComplete != null) {
                    withContext(Dispatchers.Main) { onComplete.invoke(success) }
                }
            }
        }
    }

    @Synchronized
    fun syncBlockState(context: Context): Boolean {
        val shouldBeActive = isEffectiveBlockActive(context)
        Timber.tag(TAG).d("Syncing block state: shouldBeActive=%b", shouldBeActive)
        val ok = enforce(context, shouldBeActive)
        if (!ProtectionTransitionGuard.isTeardownInProgress) {
            runCatching { WardActiveModesTelemetrySyncer.syncAsync(context) }
        }
        return ok
    }

    fun syncBlockStateAsync(context: Context, onComplete: ((Boolean) -> Unit)? = null) {
        val appContext = context.applicationContext ?: context
        enforcerScope.launch {
            var success = false
            try {
                success = syncBlockState(appContext)
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Error in syncBlockStateAsync")
            } finally {
                if (onComplete != null) {
                    withContext(Dispatchers.Main) { onComplete.invoke(success) }
                }
            }
        }
    }

    @Synchronized
    fun handlePackageAdded(context: Context, packageName: String) {
        CurfewLifecycleHandler.handlePackageAdded(context, packageName)
    }

    @Synchronized
    fun repairStuckSuspendedApps(context: Context): Int =
        CurfewLifecycleHandler.repairStuckSuspendedApps(context)
}
