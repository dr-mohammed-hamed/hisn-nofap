package com.drmohammed.antiporn.core.engine

import android.content.Context
import android.os.SystemClock
import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Enterprise Transition Guard coordinating legitimate internal policy, VPN, DNS,
 * and curfew mutations across engines to prevent false-positive watchdog tamper alerts.
 */
object ProtectionTransitionGuard {

    private const val TAG = "AntiPorn_TransGuard"
    private const val PREFS_NAME = "protection_transition_guard_prefs"
    private const val KEY_INITIAL_BASELINE_ESTABLISHED = "initial_baseline_established"
    private const val MAX_STUCK_TRANSITION_TIMEOUT_MS = 15_000L

    @PublishedApi
    internal val activeTransitions = ConcurrentHashMap<Long, Long>()
    @PublishedApi
    internal val nextToken = AtomicLong(0L)
    private val lastInternalDnsMutationElapsed = AtomicLong(0L)

    @Volatile
    var isTeardownInProgress: Boolean = false

    /**
     * Executes synchronous or blocking block under internal transition protection.
     */
    inline fun <T> runInternalTransition(block: () -> T): T {
        val token = nextToken.incrementAndGet()
        activeTransitions[token] = SystemClock.elapsedRealtime()
        return try {
            block()
        } finally {
            activeTransitions.remove(token)
        }
    }

    /**
     * Executes suspending block under internal transition protection.
     */
    suspend fun <T> runInternalTransitionSuspend(block: suspend () -> T): T {
        val token = nextToken.incrementAndGet()
        activeTransitions[token] = SystemClock.elapsedRealtime()
        return try {
            block()
        } finally {
            activeTransitions.remove(token)
        }
    }

    /**
     * Marks a deliberate internal DNS or VPN reconfiguration event.
     */
    fun markInternalDnsMutation() {
        lastInternalDnsMutationElapsed.set(SystemClock.elapsedRealtime())
    }

    /**
     * Evaluates whether the system is within a legitimate transition or settle window.
     */
    fun isWithinSettleWindow(settleMs: Long = 3500L): Boolean {
        if (isTeardownInProgress) return true

        val now = SystemClock.elapsedRealtime()
        val iterator = activeTransitions.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            val duration = now - entry.value
            if (duration > MAX_STUCK_TRANSITION_TIMEOUT_MS) {
                Log.w(TAG, "Forced recovery: transition token ${entry.key} stuck for ${duration}ms. Evicting.")
                iterator.remove()
            }
        }
        if (activeTransitions.isNotEmpty()) {
            return true
        }

        val last = lastInternalDnsMutationElapsed.get()
        if (last > 0L && (SystemClock.elapsedRealtime() - last) < settleMs) {
            return true
        }

        return false
    }

    /**
     * Checks if initial Device Owner baseline protection has been established at least once.
     */
    fun hasCompletedInitialEnforcement(context: Context): Boolean {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_INITIAL_BASELINE_ESTABLISHED, false)
    }

    /**
     * Records successful initial baseline enforcement.
     */
    fun markInitialEnforcementCompleted(context: Context) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_INITIAL_BASELINE_ESTABLISHED, true).apply()
        Log.i(TAG, "Initial protection baseline marked as established.")
    }

    /**
     * Resets initial baseline flag (called during Device Owner Teardown).
     */
    fun resetInitialEnforcement(context: Context) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().remove(KEY_INITIAL_BASELINE_ESTABLISHED).apply()
        Log.i(TAG, "Initial protection baseline cleared.")
    }
}
