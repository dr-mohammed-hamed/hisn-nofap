package com.drmohammed.antiporn.accessibility.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccessibilityThrottleTest {

    @Test
    fun shouldAllow_gatesByMonotonicIntervalPerKey() {
        var now = 1_000L
        val throttle = AccessibilityThrottle(clockMs = { now })

        assertTrue(throttle.shouldAllow("pkg_a", intervalMs = 1_000L))
        assertTrue(throttle.shouldAllow("pkg_b", intervalMs = 1_000L))

        now += 999L
        assertFalse(throttle.shouldAllow("pkg_a", intervalMs = 1_000L))

        now += 1L
        assertTrue(throttle.shouldAllow("pkg_a", intervalMs = 1_000L))
        assertTrue(throttle.shouldAllow("pkg_a", intervalMs = 0L))
    }

    @Test
    fun markActionAndIsInCooldown_trackCooldownWithoutMutatingTimestamp() {
        var now = 5_000L
        val throttle = AccessibilityThrottle(clockMs = { now })

        assertFalse(throttle.isInCooldown("block_overlay", cooldownMs = 2_000L))

        throttle.markAction("block_overlay")
        assertTrue(throttle.isInCooldown("block_overlay", cooldownMs = 2_000L))
        assertFalse(throttle.isInCooldown("block_overlay", cooldownMs = 0L))

        now += 1_999L
        assertTrue(throttle.isInCooldown("block_overlay", cooldownMs = 2_000L))

        now += 1L
        assertFalse(throttle.isInCooldown("block_overlay", cooldownMs = 2_000L))
    }

    @Test
    fun clear_resetsRecordedIntervalsAndCooldowns() {
        var now = 10_000L
        val throttle = AccessibilityThrottle(clockMs = { now })

        assertTrue(throttle.shouldAllow("key_1", intervalMs = 5_000L))
        throttle.markAction("key_2")
        assertFalse(throttle.shouldAllow("key_1", intervalMs = 5_000L))
        assertTrue(throttle.isInCooldown("key_2", cooldownMs = 5_000L))

        throttle.clear()

        assertTrue(throttle.shouldAllow("key_1", intervalMs = 5_000L))
        assertFalse(throttle.isInCooldown("key_2", cooldownMs = 5_000L))
    }

    @Test
    fun shouldAllow_evictsStaleAndOldestEntriesWhenCapReached() {
        var now = 1_000L
        val throttle = AccessibilityThrottle(clockMs = { now }, maxEntries = 3)

        assertTrue(throttle.shouldAllow("k1", intervalMs = 500L))
        now += 100L
        assertTrue(throttle.shouldAllow("k2", intervalMs = 500L))
        now += 100L
        assertTrue(throttle.shouldAllow("k3", intervalMs = 500L))

        // At t=1700, k1 (1000) and k2 (1100) are stale for intervalMs=500 -> evicted when k4 is added
        now = 1_700L
        assertTrue(throttle.shouldAllow("k4", intervalMs = 500L))

        // k1 was evicted so it is allowed immediately even with a large interval
        assertTrue(throttle.shouldAllow("k1", intervalMs = 10_000L))
    }
}
