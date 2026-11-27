package com.drmohammed.antiporn.fortress.engine

import android.content.Context
import com.drmohammed.antiporn.core.time.TrustedTimeProvider
import com.drmohammed.antiporn.curfew.CurfewConfig
import timber.log.Timber

/**
 * Persists a one-shot Guardian override for the current Night Curfew window.
 * Automatically expires once the window's end wall-clock timestamp has passed.
 */
object CurfewWindowOverrideStore {

    private const val TAG = "AntiPorn_CurfewOverride"
    internal const val KEY_CURFEW_WINDOW_OVERRIDE_END_WALL = "key_curfew_window_override_end_wall"

    @Synchronized
    fun isWindowOverridden(
        context: Context,
        nowWallMs: Long = TrustedTimeProvider.currentTimeMillis(context)
    ): Boolean {
        return try {
            val prefs = context.getSharedPreferences(CurfewConfig.PREFS_NAME, Context.MODE_PRIVATE)
            val endWall = prefs.getLong(KEY_CURFEW_WINDOW_OVERRIDE_END_WALL, 0L)
            if (endWall <= 0L) {
                false
            } else if (nowWallMs < endWall) {
                true
            } else {
                prefs.edit().remove(KEY_CURFEW_WINDOW_OVERRIDE_END_WALL).commit()
                false
            }
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Failed checking curfew window override")
            false
        }
    }

    @Synchronized
    fun getOverrideEndWallMs(context: Context): Long {
        return try {
            val prefs = context.getSharedPreferences(CurfewConfig.PREFS_NAME, Context.MODE_PRIVATE)
            prefs.getLong(KEY_CURFEW_WINDOW_OVERRIDE_END_WALL, 0L)
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Failed reading curfew window override end wall")
            0L
        }
    }

    @Synchronized
    fun setOverrideUntil(context: Context, windowEndWallMs: Long) {
        if (windowEndWallMs <= 0L) return
        try {
            val prefs = context.getSharedPreferences(CurfewConfig.PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().putLong(KEY_CURFEW_WINDOW_OVERRIDE_END_WALL, windowEndWallMs).commit()
            Timber.tag(TAG).i("Night curfew window overridden until wallMs=%d", windowEndWallMs)
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed setting curfew window override")
        }
    }

    @Synchronized
    fun clearOverride(context: Context) {
        try {
            val prefs = context.getSharedPreferences(CurfewConfig.PREFS_NAME, Context.MODE_PRIVATE)
            if (prefs.contains(KEY_CURFEW_WINDOW_OVERRIDE_END_WALL)) {
                prefs.edit().remove(KEY_CURFEW_WINDOW_OVERRIDE_END_WALL).commit()
            }
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Failed clearing curfew window override")
        }
    }
}
