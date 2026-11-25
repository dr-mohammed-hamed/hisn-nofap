package com.drmohammed.antiporn.provisioning.engine

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import android.provider.Settings
import androidx.annotation.VisibleForTesting
import timber.log.Timber

/**
 * Persists and validates user acknowledgment of OEM danger warnings (such as Samsung
 * Secure Folder or Android 15 Private Space data loss risks) prior to Device Owner promotion.
 *
 * Implements a 15-minute Time-To-Live (TTL), negative clock jump protection, boot-count
 * validation across reboots, and automatic eviction of expired acknowledgments.
 */
object ProvisioningAckStore {

    private const val TAG = "AntiPorn_AckStore"
    const val PREFS_NAME = "provisioning_danger_ack_prefs"
    const val KEY_ACK_TIMESTAMP = "danger_ack_timestamp_ms"
    const val KEY_BOOT_COUNT = "danger_ack_boot_count"
    const val ACK_TTL_MS = 15 * 60 * 1000L // 15 minutes

    @VisibleForTesting
    internal var nowMsProvider: (() -> Long)? = null

    @VisibleForTesting
    internal var bootCountProvider: ((Context) -> Int)? = null

    private fun getPrefs(context: Context): SharedPreferences {
        return (context.applicationContext ?: context).getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    private fun currentEpochMs(): Long = nowMsProvider?.invoke() ?: SystemClock.elapsedRealtime()

    private fun currentBootCount(context: Context): Int {
        val appContext = context.applicationContext ?: context
        bootCountProvider?.let { return it(appContext) }
        return runCatching {
            Settings.Global.getInt(appContext.contentResolver, Settings.Global.BOOT_COUNT, -1)
        }.getOrDefault(-1)
    }

    @Synchronized
    fun recordAck(context: Context, nowMs: Long = currentEpochMs()) {
        try {
            val bootCount = currentBootCount(context)
            getPrefs(context).edit()
                .putLong(KEY_ACK_TIMESTAMP, nowMs)
                .putInt(KEY_BOOT_COUNT, bootCount)
                .apply()
            Timber.tag(TAG).i("Recorded provisioning danger acknowledgment at $nowMs (boot=$bootCount)")
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed recording provisioning danger acknowledgment")
        }
    }

    @Synchronized
    fun hasValidAck(context: Context, nowMs: Long = currentEpochMs()): Boolean {
        return try {
            val prefs = getPrefs(context)
            val saved = prefs.getLong(KEY_ACK_TIMESTAMP, 0L)
            if (saved <= 0L) {
                return false
            }
            val currentBoot = currentBootCount(context)
            val savedBoot = prefs.getInt(KEY_BOOT_COUNT, -1)
            if (currentBoot >= 0 && savedBoot >= 0 && currentBoot != savedBoot) {
                Timber.tag(TAG).w("Acknowledgment invalidated across reboot (savedBoot=%d, currentBoot=%d)", savedBoot, currentBoot)
                clearAck(context)
                return false
            }
            val elapsed = nowMs - saved
            if (elapsed < 0L || elapsed > ACK_TTL_MS) {
                Timber.tag(TAG).w("Acknowledgment invalid or expired (elapsed: %d ms). Clearing ack.", elapsed)
                clearAck(context)
                false
            } else {
                true
            }
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed checking provisioning danger acknowledgment validity")
            false
        }
    }

    @Synchronized
    fun clearAck(context: Context) {
        try {
            getPrefs(context).edit()
                .remove(KEY_ACK_TIMESTAMP)
                .remove(KEY_BOOT_COUNT)
                .apply()
            Timber.tag(TAG).i("Cleared provisioning danger acknowledgment")
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed clearing provisioning danger acknowledgment")
        }
    }
}
