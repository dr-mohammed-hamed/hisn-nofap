package com.drmohammed.antiporn.fortress.engine

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import android.provider.Settings
import androidx.annotation.VisibleForTesting
import com.drmohammed.antiporn.core.time.TrustedTimeProvider
import com.drmohammed.antiporn.curfew.CurfewScheduler
import com.drmohammed.antiporn.fortress.model.ActiveFortressSession
import com.drmohammed.antiporn.fortress.model.FortressProfile
import com.drmohammed.antiporn.telemetry.BlackBoxRecorder
import com.drmohammed.antiporn.ui.control.gate.SelfControlAccessGateRegistry
import com.drmohammed.antiporn.ui.control.gate.SelfControlSection
import com.drmohammed.antiporn.ui.control.qr.SelfControlQrManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber

/**
 * Thread-safe controller managing single active Fortress sessions with dual-clock anti-tamper
 * ([SystemClock.elapsedRealtime] + [TrustedTimeProvider], fail-closed OR), reboot sanitization,
 * legacy Nap migration, and strictly gated unlock pathways.
 */
object FortressSessionController {

    private const val TAG = "AntiPorn_FortressCtrl"
    const val PREFS_NAME = FortressSessionPrefsStore.PREFS_NAME

    private val defaultElapsedProvider: () -> Long = { SystemClock.elapsedRealtime() }
    private val defaultWallProvider: () -> Long = { TrustedTimeProvider.currentTimeMillis() }
    private val defaultBootCountProvider: (Context) -> Int = { ctx ->
        try {
            Settings.Global.getInt(ctx.contentResolver, Settings.Global.BOOT_COUNT, -1)
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Failed reading Settings.Global.BOOT_COUNT")
            -1
        }
    }

    var elapsedProvider: () -> Long = defaultElapsedProvider
    var wallProvider: () -> Long = defaultWallProvider
    internal var bootCountProvider: (Context) -> Int = defaultBootCountProvider

    @Volatile
    private var legacyMigrationChecked: Boolean = false

    private val _activeSessionFlow = MutableStateFlow<ActiveFortressSession?>(null)
    val activeSessionFlow: StateFlow<ActiveFortressSession?> = _activeSessionFlow.asStateFlow()

    private fun resolveWallNow(context: Context): Long =
        if (wallProvider === defaultWallProvider) {
            TrustedTimeProvider.currentTimeMillis(context)
        } else {
            wallProvider()
        }

    internal fun computeActive(
        elapsed: Long,
        endElapsed: Long,
        wall: Long,
        endWall: Long,
        flagActive: Boolean
    ): Boolean = flagActive && (elapsed < endElapsed || wall < endWall)

    internal fun computeRemaining(
        elapsed: Long,
        endElapsed: Long,
        wall: Long,
        endWall: Long,
        flagActive: Boolean
    ): Long = if (!flagActive) 0L else maxOf(0L, maxOf(endElapsed - elapsed, endWall - wall))

    @Synchronized
    fun migrateLegacyNapSessionIfNeeded(context: Context) {
        if (legacyMigrationChecked) return
        val appContext = context.applicationContext ?: context
        if (FortressSessionPrefsStore.migrateLegacyNapIfNeeded(appContext)) {
            legacyMigrationChecked = true
        }
    }

    /**
     * Starts a Fortress session for [profile]. Enforces single-active-fortress rule:
     * returns existing `endElapsed` if the same fortress is already active, or `0L` if
     * a different fortress is currently running.
     */
    @Synchronized
    fun startFortress(context: Context, profile: FortressProfile): Long {
        val appContext = context.applicationContext ?: context
        migrateLegacyNapSessionIfNeeded(appContext)
        return try {
            val normalized = profile.normalized()
            val existing = getActiveSessionLocked(appContext)
            if (existing != null) {
                return if (existing.fortressId == normalized.id) {
                    existing.endElapsedMs
                } else {
                    Timber.tag(TAG).w(
                        "Rejected startFortress(%s): fortress %s is already active",
                        normalized.id,
                        existing.fortressId
                    )
                    0L
                }
            }

            val elapsedNow = elapsedProvider()
            val wallNow = resolveWallNow(appContext)
            val durationMs = normalized.durationMinutes.toLong() * 60_000L
            val endElapsed = elapsedNow + durationMs
            val endWall = wallNow + durationMs
            val bootCount = bootCountProvider(appContext)

            FortressSessionPrefsStore.saveSession(
                appContext, normalized.id, normalized.name, normalized.isEmergency,
                normalized.durationMinutes, elapsedNow, wallNow, endElapsed, endWall, bootCount
            )
            _activeSessionFlow.value = ActiveFortressSession(
                normalized.id, normalized.name, normalized.isEmergency,
                normalized.durationMinutes, elapsedNow, endElapsed, endWall, durationMs
            )
            BlackBoxRecorder.recordCurfew(
                appContext, "FORTRESS_STARTED", isSuccess = true,
                details = "id=${normalized.id}, emergency=${normalized.isEmergency}, mins=${normalized.durationMinutes}"
            )
            endElapsed
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "startFortress failed")
            0L
        }
    }

    /**
     * Extends the currently active Fortress session by its own configured duration.
     * Returns the new `endElapsedMs`, or `0L` if no fortress is active.
     */
    @Synchronized
    fun extendActiveFortress(context: Context): Long {
        val appContext = context.applicationContext ?: context
        migrateLegacyNapSessionIfNeeded(appContext)
        return try {
            val existing = getActiveSessionLocked(appContext) ?: return 0L
            val elapsedNow = elapsedProvider()
            val wallNow = resolveWallNow(appContext)
            val prefs = FortressSessionPrefsStore.prefs(appContext)
            val sanitizedEndElapsed = getSanitizedEndElapsed(prefs, appContext, elapsedNow, wallNow, existing.endWallMs)
            if (!computeActive(elapsedNow, sanitizedEndElapsed, wallNow, existing.endWallMs, true)) {
                clearSessionLocked(appContext)
                return 0L
            }

            val stepMs = existing.durationMinutes.coerceAtLeast(1).toLong() * 60_000L
            val newEndElapsed = sanitizedEndElapsed + stepMs
            val newEndWall = existing.endWallMs + stepMs
            FortressSessionPrefsStore.updateTimingAfterExtend(
                appContext, elapsedNow, wallNow, newEndElapsed, newEndWall, bootCountProvider(appContext)
            )
            val remaining = computeRemaining(elapsedNow, newEndElapsed, wallNow, newEndWall, true)
            _activeSessionFlow.value = existing.copy(
                startElapsedMs = elapsedNow, endElapsedMs = newEndElapsed,
                endWallMs = newEndWall, remainingMs = remaining
            )
            BlackBoxRecorder.recordCurfew(
                appContext, "FORTRESS_EXTENDED", isSuccess = true,
                details = "id=${existing.fortressId}, addedMins=${existing.durationMinutes}"
            )
            newEndElapsed
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "extendActiveFortress failed")
            0L
        }
    }

    @Synchronized
    fun isFortressActive(context: Context): Boolean = getActiveSession(context) != null

    @Synchronized
    fun getActiveSession(context: Context): ActiveFortressSession? {
        val appContext = context.applicationContext ?: context
        migrateLegacyNapSessionIfNeeded(appContext)
        return getActiveSessionLocked(appContext)
    }

    @Synchronized
    fun remainingMs(context: Context): Long = getActiveSession(context)?.remainingMs ?: 0L

    @Synchronized
    fun getEndWall(context: Context): Long = getActiveSession(context)?.endWallMs ?: 0L

    @Synchronized
    fun getEndElapsed(context: Context): Long = getActiveSession(context)?.endElapsedMs ?: 0L

    /**
     * Unlocks a custom (non-emergency) Fortress directly ONLY when QR lock is disabled AND
     * supervision policy permits [SelfControlSection.FORTRESS_UNLOCK].
     * Emergency Fortresses ALWAYS return false here.
     */
    @Synchronized
    fun unlockActiveFortressDirectWhenAllowed(context: Context): Boolean {
        val appContext = context.applicationContext ?: context
        migrateLegacyNapSessionIfNeeded(appContext)
        val session = getActiveSessionLocked(appContext) ?: return false

        if (session.isEmergency || session.fortressId == FortressProfile.EMERGENCY_FORTRESS_ID) {
            return rejectUnlock(appContext, "FORTRESS_UNLOCK_REJECTED_EMERGENCY", "Emergency fortress cannot be unlocked directly")
        }
        if (SelfControlQrManager.getInstance(appContext).isQrLockEnabled()) {
            return rejectUnlock(appContext, "FORTRESS_UNLOCK_REJECTED_QR_REQUIRED", "QR lock is enabled")
        }
        if (!SelfControlAccessGateRegistry.isSectionPermitted(appContext, SelfControlSection.FORTRESS_UNLOCK)) {
            return rejectUnlock(appContext, "FORTRESS_UNLOCK_DENIED_BY_GUARDIAN", "FORTRESS_UNLOCK not permitted by supervision policy")
        }

        clearSessionLocked(appContext)
        BlackBoxRecorder.recordGate(appContext, "FORTRESS_UNLOCKED_DIRECT", isSuccess = true, details = "id=${session.fortressId}")
        return true
    }

    /**
     * Unlocks the active Fortress (including Emergency Fortress) using a verified QR token
     * or after completed QR Emergency Delay bypass, subject to guardian supervision permission.
     */
    @Synchronized
    fun unlockActiveFortressWithQrToken(
        context: Context,
        rawScannedToken: String,
        isEmergencyBypassCompleted: Boolean = false
    ): Boolean {
        val appContext = context.applicationContext ?: context
        migrateLegacyNapSessionIfNeeded(appContext)
        val session = getActiveSessionLocked(appContext) ?: return false

        if (!SelfControlAccessGateRegistry.isSectionPermitted(appContext, SelfControlSection.FORTRESS_UNLOCK)) {
            return rejectUnlock(appContext, "FORTRESS_QR_UNLOCK_DENIED_BY_GUARDIAN", "FORTRESS_UNLOCK not permitted by supervision policy")
        }

        val qrManager = SelfControlQrManager.getInstance(appContext)
        if (isEmergencyBypassCompleted) {
            if (qrManager.isQrLockEnabled()) {
                Timber.tag(TAG).w("Rejected emergency bypass unlock: QR lock is still enabled")
                return false
            }
            clearSessionLocked(appContext)
            BlackBoxRecorder.recordGate(
                appContext,
                "FORTRESS_UNLOCKED_QR_EMERGENCY_BYPASS",
                isSuccess = true,
                details = "id=${session.fortressId}, emergency=${session.isEmergency}"
            )
            return true
        }

        if (!qrManager.isQrLockEnabled() || !qrManager.verifyToken(rawScannedToken)) {
            return rejectUnlock(appContext, "FORTRESS_QR_UNLOCK_INVALID_TOKEN", "QR token verification failed")
        }

        clearSessionLocked(appContext)
        BlackBoxRecorder.recordGate(
            appContext,
            "FORTRESS_UNLOCKED_QR",
            isSuccess = true,
            details = "id=${session.fortressId}, emergency=${session.isEmergency}"
        )
        return true
    }

    @Synchronized
    fun unlockActiveFortressViaEmergencyBypass(context: Context): Boolean =
        unlockActiveFortressWithQrToken(context, rawScannedToken = "", isEmergencyBypassCompleted = true)

    @Synchronized
    internal fun unlockActiveFortressByGuardian(context: Context): Boolean {
        val appContext = context.applicationContext ?: context
        migrateLegacyNapSessionIfNeeded(appContext)
        val session = getActiveSessionLocked(appContext)
        clearSessionLocked(appContext)
        if (session != null) {
            BlackBoxRecorder.recordGate(
                appContext,
                "FORTRESS_UNLOCKED_BY_GUARDIAN",
                isSuccess = true,
                details = "id=${session.fortressId}, emergency=${session.isEmergency}"
            )
            return true
        }
        return false
    }

    @Synchronized
    internal fun clearForTeardown(context: Context) {
        clearSessionLocked(context.applicationContext ?: context)
    }

    private fun rejectUnlock(appContext: Context, action: String, reason: String): Boolean {
        Timber.tag(TAG).w("Rejected fortress unlock (%s): %s", action, reason)
        BlackBoxRecorder.recordGate(appContext, action, isSuccess = false, error = reason)
        return false
    }

    private fun getActiveSessionLocked(appContext: Context): ActiveFortressSession? {
        return try {
            val prefs = FortressSessionPrefsStore.prefs(appContext)
            if (!prefs.getBoolean(FortressSessionPrefsStore.KEY_ACTIVE, false)) {
                if (_activeSessionFlow.value != null) {
                    _activeSessionFlow.value = null
                }
                return null
            }

            val elapsedNow = elapsedProvider()
            val wallNow = resolveWallNow(appContext)
            val endWall = prefs.getLong(FortressSessionPrefsStore.KEY_END_WALL_MS, 0L)
            val endElapsed = getSanitizedEndElapsed(prefs, appContext, elapsedNow, wallNow, endWall)
            if (!computeActive(elapsedNow, endElapsed, wallNow, endWall, true)) {
                clearSessionLocked(appContext)
                return null
            }

            val fortressId = prefs.getString(FortressSessionPrefsStore.KEY_FORTRESS_ID, null)
                ?.takeIf { it.isNotBlank() }
                ?: FortressProfile.EMERGENCY_FORTRESS_ID
            val isEmergency = prefs.getBoolean(
                FortressSessionPrefsStore.KEY_IS_EMERGENCY,
                fortressId == FortressProfile.EMERGENCY_FORTRESS_ID
            )
            val fortressName = prefs.getString(FortressSessionPrefsStore.KEY_FORTRESS_NAME, "") ?: ""
            val durationMinutes = prefs.getInt(
                FortressSessionPrefsStore.KEY_DURATION_MINUTES,
                FortressProfile.DEFAULT_EMERGENCY_DURATION_MINUTES
            ).coerceAtLeast(1)
            val startElapsed = prefs.getLong(FortressSessionPrefsStore.KEY_START_ELAPSED, 0L)
            val remaining = computeRemaining(elapsedNow, endElapsed, wallNow, endWall, true)

            ActiveFortressSession(
                fortressId = fortressId,
                fortressName = fortressName,
                isEmergency = isEmergency,
                durationMinutes = durationMinutes,
                startElapsedMs = startElapsed,
                endElapsedMs = endElapsed,
                endWallMs = endWall,
                remainingMs = remaining
            )
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "getActiveSessionLocked failed")
            null
        }
    }

    private fun getSanitizedEndElapsed(
        prefs: SharedPreferences,
        context: Context,
        elapsedNow: Long,
        wallNow: Long,
        endWall: Long
    ): Long {
        val startElapsed = prefs.getLong(FortressSessionPrefsStore.KEY_START_ELAPSED, 0L)
        val startWallMs = prefs.getLong(FortressSessionPrefsStore.KEY_START_WALL_MS, 0L)
        var endElapsed = prefs.getLong(FortressSessionPrefsStore.KEY_END_ELAPSED, 0L)
        val savedBootCount = prefs.getInt(FortressSessionPrefsStore.KEY_BOOT_COUNT, -1)
        val currentBootCount = bootCountProvider(context)
        val bootCountChanged = savedBootCount >= 0 && currentBootCount >= 0 && savedBootCount != currentBootCount
        val canUseTrustedWallFallback =
            (savedBootCount < 0 || currentBootCount < 0) &&
                startWallMs > 0L &&
                startElapsed > 0L &&
                elapsedNow >= startElapsed &&
                (TrustedTimeProvider.hasEverSynced() || wallProvider !== defaultWallProvider) &&
                ((wallNow - startWallMs) - (elapsedNow - startElapsed) > 60_000L)
        if (bootCountChanged || (startElapsed > 0L && elapsedNow < startElapsed) || canUseTrustedWallFallback) {
            if (wallNow >= endWall) {
                clearSessionLocked(context)
                return 0L
            }
            endElapsed = elapsedNow + (endWall - wallNow)
            prefs.edit()
                .putLong(FortressSessionPrefsStore.KEY_START_ELAPSED, elapsedNow)
                .putLong(FortressSessionPrefsStore.KEY_START_WALL_MS, wallNow)
                .putLong(FortressSessionPrefsStore.KEY_END_ELAPSED, endElapsed)
                .putInt(FortressSessionPrefsStore.KEY_BOOT_COUNT, currentBootCount)
                .commit()
        }
        return endElapsed
    }

    private fun clearSessionLocked(appContext: Context) {
        FortressSessionPrefsStore.clearSession(appContext)
        _activeSessionFlow.value = null
        CurfewScheduler.cancelFortressEndAlarm(appContext)
    }

    @VisibleForTesting
    @Synchronized
    internal fun resetForTesting() {
        elapsedProvider = defaultElapsedProvider
        wallProvider = defaultWallProvider
        bootCountProvider = defaultBootCountProvider
        legacyMigrationChecked = false
        _activeSessionFlow.value = null
    }
}
