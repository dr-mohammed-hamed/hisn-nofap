package com.drmohammed.antiporn.core.engine

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.annotation.VisibleForTesting
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
    private fun defaultElapsedRealtimeMs(): Long = try {
        SystemClock.elapsedRealtime()
    } catch (_: RuntimeException) {
        System.nanoTime() / 1_000_000L
    }

    @PublishedApi
    @Volatile
    internal var elapsedRealtimeProvider: () -> Long = ::defaultElapsedRealtimeMs
    private val lastInternalDnsMutationElapsed = AtomicLong(0L)

    @Volatile
    var isTeardownInProgress: Boolean = false

    /**
     * Executes synchronous or blocking block under internal transition protection.
     */
    inline fun <T> runInternalTransition(block: () -> T): T {
        val token = nextToken.incrementAndGet()
        activeTransitions[token] = elapsedRealtimeProvider()
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
        activeTransitions[token] = elapsedRealtimeProvider()
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
        lastInternalDnsMutationElapsed.set(elapsedRealtimeProvider())
    }

    private fun evictStuckTransitions(now: Long) {
        val iterator = activeTransitions.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            val duration = now - entry.value
            if (duration > MAX_STUCK_TRANSITION_TIMEOUT_MS) {
                try {
                    Log.w(TAG, "Forced recovery: transition token ${entry.key} stuck for ${duration}ms. Evicting.")
                } catch (_: RuntimeException) {
                    // Allow pure JUnit tests where android.util.Log is not mocked
                }
                iterator.remove()
            }
        }
    }

    /**
     * Returns true ONLY when teardown is in progress or an internal transition block
     * is actively executing right now (excludes the post-mutation 3.5s settle cooldown).
     */
    fun hasActiveTransitions(): Boolean {
        if (isTeardownInProgress) return true
        evictStuckTransitions(elapsedRealtimeProvider())
        return activeTransitions.isNotEmpty()
    }

    /**
     * Evaluates whether the system is within a legitimate transition or settle window.
     */
    fun isWithinSettleWindow(settleMs: Long = 3500L): Boolean {
        if (hasActiveTransitions()) return true

        val last = lastInternalDnsMutationElapsed.get()
        if (last > 0L && (elapsedRealtimeProvider() - last) < settleMs) {
            return true
        }

        return false
    }

    @VisibleForTesting
    internal fun resetForTest() {
        activeTransitions.clear()
        lastInternalDnsMutationElapsed.set(0L)
        isTeardownInProgress = false
        elapsedRealtimeProvider = ::defaultElapsedRealtimeMs
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
