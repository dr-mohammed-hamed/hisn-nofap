package com.drmohammed.antiporn.fortress.engine

import android.content.Context
import android.content.SharedPreferences
import com.drmohammed.antiporn.fortress.model.FortressProfile
import timber.log.Timber

/**
 * Low-level SharedPreferences persistence and one-time legacy Nap migration helper
 * for [FortressSessionController].
 */
internal object FortressSessionPrefsStore {

    private const val TAG = "AntiPorn_FortressPrefs"

    const val PREFS_NAME = "antiporn_fortress_prefs"
    const val KEY_ACTIVE = "key_fortress_active"
    const val KEY_FORTRESS_ID = "key_fortress_id"
    const val KEY_FORTRESS_NAME = "key_fortress_name"
    const val KEY_IS_EMERGENCY = "key_fortress_is_emergency"
    const val KEY_DURATION_MINUTES = "key_fortress_duration_minutes"
    const val KEY_START_ELAPSED = "key_fortress_start_elapsed"
    const val KEY_START_WALL_MS = "key_fortress_start_wall_ms"
    const val KEY_END_ELAPSED = "key_fortress_end_elapsed"
    const val KEY_END_WALL_MS = "key_fortress_end_wall_ms"
    const val KEY_BOOT_COUNT = "key_fortress_boot_count"

    // Legacy Nap preferences constants preserved here so migration survives NapConfig removal.
    private const val LEGACY_NAP_PREFS_NAME = "antiporn_nap_prefs"
    private const val LEGACY_NAP_KEY_ACTIVE = "key_nap_active"
    private const val LEGACY_NAP_KEY_START_ELAPSED = "key_nap_start_elapsed"
    private const val LEGACY_NAP_KEY_END_ELAPSED = "key_nap_end_elapsed"
    private const val LEGACY_NAP_KEY_END_WALL_MS = "key_nap_end_wall_ms"
    private const val LEGACY_NAP_KEY_BOOT_COUNT = "key_nap_boot_count"
    private const val LEGACY_NAP_KEY_MIGRATED = "key_nap_migrated_to_fortress"

    fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun saveSession(
        context: Context,
        fortressId: String,
        fortressName: String,
        isEmergency: Boolean,
        durationMinutes: Int,
        startElapsed: Long,
        startWallMs: Long,
        endElapsed: Long,
        endWall: Long,
        bootCount: Int
    ) {
        prefs(context).edit()
            .putBoolean(KEY_ACTIVE, true)
            .putString(KEY_FORTRESS_ID, fortressId)
            .putString(KEY_FORTRESS_NAME, fortressName)
            .putBoolean(KEY_IS_EMERGENCY, isEmergency)
            .putInt(KEY_DURATION_MINUTES, durationMinutes)
            .putLong(KEY_START_ELAPSED, startElapsed)
            .putLong(KEY_START_WALL_MS, startWallMs)
            .putLong(KEY_END_ELAPSED, endElapsed)
            .putLong(KEY_END_WALL_MS, endWall)
            .putInt(KEY_BOOT_COUNT, bootCount)
            .commit()
    }

    fun updateTimingAfterExtend(
        context: Context,
        startElapsed: Long,
        startWallMs: Long,
        endElapsed: Long,
        endWall: Long,
        bootCount: Int
    ) {
        prefs(context).edit()
            .putLong(KEY_START_ELAPSED, startElapsed)
            .putLong(KEY_START_WALL_MS, startWallMs)
            .putLong(KEY_END_ELAPSED, endElapsed)
            .putLong(KEY_END_WALL_MS, endWall)
            .putInt(KEY_BOOT_COUNT, bootCount)
            .putBoolean(KEY_ACTIVE, true)
            .commit()
    }

    fun clearSession(context: Context) {
        try {
            prefs(context).edit()
                .putBoolean(KEY_ACTIVE, false)
                .remove(KEY_FORTRESS_ID)
                .remove(KEY_FORTRESS_NAME)
                .remove(KEY_IS_EMERGENCY)
                .remove(KEY_DURATION_MINUTES)
                .putLong(KEY_START_ELAPSED, 0L)
                .remove(KEY_START_WALL_MS)
                .putLong(KEY_END_ELAPSED, 0L)
                .putLong(KEY_END_WALL_MS, 0L)
                .putInt(KEY_BOOT_COUNT, -1)
                .commit()
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "clearSession failed")
        }
    }

    /**
     * Copies an active legacy Nap session into `antiporn_fortress_prefs` once and marks Nap prefs migrated.
     */
    fun migrateLegacyNapIfNeeded(context: Context): Boolean {
        return try {
            val napPrefs = context.getSharedPreferences(LEGACY_NAP_PREFS_NAME, Context.MODE_PRIVATE)
            if (napPrefs.getBoolean(LEGACY_NAP_KEY_MIGRATED, false)) {
                return true
            }
            if (napPrefs.getBoolean(LEGACY_NAP_KEY_ACTIVE, false)) {
                val fortressPrefs = prefs(context)
                if (!fortressPrefs.getBoolean(KEY_ACTIVE, false)) {
                    saveSession(
                        context = context,
                        fortressId = FortressProfile.EMERGENCY_FORTRESS_ID,
                        fortressName = "",
                        isEmergency = true,
                        durationMinutes = FortressProfile.DEFAULT_EMERGENCY_DURATION_MINUTES,
                        startElapsed = napPrefs.getLong(LEGACY_NAP_KEY_START_ELAPSED, 0L),
                        startWallMs = 0L,
                        endElapsed = napPrefs.getLong(LEGACY_NAP_KEY_END_ELAPSED, 0L),
                        endWall = napPrefs.getLong(LEGACY_NAP_KEY_END_WALL_MS, 0L),
                        bootCount = napPrefs.getInt(LEGACY_NAP_KEY_BOOT_COUNT, -1)
                    )
                    Timber.tag(TAG).i("Migrated active legacy Nap session to Emergency Fortress")
                }
            }
            napPrefs.edit()
                .putBoolean(LEGACY_NAP_KEY_ACTIVE, false)
                .putLong(LEGACY_NAP_KEY_START_ELAPSED, 0L)
                .putLong(LEGACY_NAP_KEY_END_ELAPSED, 0L)
                .putLong(LEGACY_NAP_KEY_END_WALL_MS, 0L)
                .putInt(LEGACY_NAP_KEY_BOOT_COUNT, -1)
                .putBoolean(LEGACY_NAP_KEY_MIGRATED, true)
                .commit()
            true
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed migrating legacy Nap session")
            false
        }
    }
}
