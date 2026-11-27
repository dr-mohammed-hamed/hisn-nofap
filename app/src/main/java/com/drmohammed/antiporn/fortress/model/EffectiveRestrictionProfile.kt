package com.drmohammed.antiporn.fortress.model

import android.content.Context
import com.drmohammed.antiporn.R

/**
 * Snapshot of a currently running Fortress session with dual-clock timestamps and remaining duration.
 */
data class ActiveFortressSession(
    val fortressId: String,
    val fortressName: String,
    val isEmergency: Boolean,
    val durationMinutes: Int,
    val startElapsedMs: Long,
    val endElapsedMs: Long,
    val endWallMs: Long,
    val remainingMs: Long
) {
    /**
     * Resolves a localized display name for notifications, UI banners, and telemetry.
     */
    fun resolveDisplayName(context: Context): String {
        val trimmed = fortressName.trim()
        if (trimmed.isNotBlank()) return trimmed
        return if (isEmergency || fortressId == FortressProfile.EMERGENCY_FORTRESS_ID) {
            context.getString(R.string.fortress_emergency_default_name)
        } else {
            fortressId
        }
    }
}

/**
 * Point-in-time snapshot of all active restriction modes (Night Curfew, Active Fortress, Instant Kill).
 */
data class ActiveModesSnapshot(
    val isNightCurfewActive: Boolean,
    val isNightCurfewWindowActiveNow: Boolean,
    val isNightCurfewOverriddenForWindow: Boolean,
    val nightCurfewWindowEndWallMs: Long,
    val activeFortress: ActiveFortressSession?,
    val isInstantInternetKillEnabled: Boolean
) {
    val isAnyModeActive: Boolean
        get() = isNightCurfewActive || activeFortress != null
}

/**
 * Unified effective restriction output resolved via Strictest-Wins intersection across all active modes.
 */
data class EffectiveRestrictionProfile(
    val isBlockActive: Boolean,
    val shouldCutInternet: Boolean,
    val effectiveAllowedUserApps: Set<String>,
    val effectiveVpnBypassPackages: Set<String>,
    val isNightCurfewActive: Boolean,
    val activeFortress: ActiveFortressSession?,
    val isInstantInternetKillActive: Boolean,
    val signature: String
)
