package com.drmohammed.antiporn.accessibility.util

import android.os.SystemClock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import timber.log.Timber

/**
 * Thread-safe monotonic-clock rate limiter and cooldown tracker keyed by string identifiers.
 */
class AccessibilityThrottle internal constructor(
    private val clockMs: () -> Long = { safeElapsedRealtime() },
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES
) {
    constructor(clockMs: () -> Long = { safeElapsedRealtime() }) : this(
        clockMs = clockMs,
        maxEntries = DEFAULT_MAX_ENTRIES
    )

    private val lastActionTimestamps = ConcurrentHashMap<String, Long>()
    private val evictionLock = Any()

    /**
     * Atomically checks whether at least [intervalMs] have elapsed since the last allowed action
     * for [key]; if so, records the current monotonic timestamp and returns true.
     */
    fun shouldAllow(key: String, intervalMs: Long): Boolean {
        val now = clockMs()
        evictIfNeeded(key, now, staleThresholdMs = intervalMs)
        var allowed = false
        lastActionTimestamps.compute(key) { _, previous ->
            if (previous == null || intervalMs <= 0L || (now - previous) >= intervalMs) {
                allowed = true
                now
            } else {
                previous
            }
        }
        return allowed
    }

    /**
     * Explicitly marks [key] as having performed an action at the current monotonic timestamp.
     */
    fun markAction(key: String) {
        val now = clockMs()
        evictIfNeeded(key, now)
        lastActionTimestamps.compute(key) { _, existing ->
            if (existing == null || now >= existing) now else existing
        }
    }

    private fun evictIfNeeded(key: String, now: Long, staleThresholdMs: Long = 0L) {
        if (maxEntries <= 0 || lastActionTimestamps.containsKey(key) || lastActionTimestamps.size < maxEntries) {
            return
        }
        synchronized(evictionLock) {
            if (lastActionTimestamps.containsKey(key) || lastActionTimestamps.size < maxEntries) {
                return
            }
            if (staleThresholdMs > 0L) {
                val iterator = lastActionTimestamps.entries.iterator()
                while (iterator.hasNext()) {
                    val entry = iterator.next()
                    if ((now - entry.value) >= staleThresholdMs) {
                        iterator.remove()
                    }
                }
            }
            while (lastActionTimestamps.size >= maxEntries) {
                val oldestKey = lastActionTimestamps.entries.minByOrNull { it.value }?.key ?: break
                lastActionTimestamps.remove(oldestKey)
            }
        }
    }

    /**
     * Returns true if [key] has a recorded action timestamp within [cooldownMs] without mutating state.
     */
    fun isInCooldown(key: String, cooldownMs: Long): Boolean {
        if (cooldownMs <= 0L) return false
        val last = lastActionTimestamps[key] ?: return false
        val elapsed = clockMs() - last
        return elapsed in 0 until cooldownMs
    }

    /**
     * Clears all recorded timestamps.
     */
    fun clear() {
        lastActionTimestamps.clear()
    }

    companion object {
        private const val TAG = "AntiPorn_A11yThrottle"
        internal const val DEFAULT_MAX_ENTRIES = 256
        private val fallbackLogged = AtomicBoolean(false)

        private fun safeElapsedRealtime(): Long {
            return try {
                SystemClock.elapsedRealtime()
            } catch (e: RuntimeException) {
                if (fallbackLogged.compareAndSet(false, true)) {
                    Timber.tag(TAG).d(e, "SystemClock.elapsedRealtime unavailable; falling back to nanoTime")
                }
                System.nanoTime() / 1_000_000L
            }
        }
    }
}
