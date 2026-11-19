package com.drmohammed.antiporn.watchdog

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [ProtectionWatchdogRuntime] lifecycle invariants, host precedence,
 * re-attach safety, and idempotent teardown.
 */
class ProtectionWatchdogRuntimeTest {

    @Before
    fun setUp() {
        ProtectionWatchdogRuntime.forceStop()
    }

    @After
    fun tearDown() {
        ProtectionWatchdogRuntime.forceStop()
    }

    @Test
    fun testWatchdogHostEnumValues() {
        val expected = setOf("NONE", "DEVICE_ADMIN_SERVICE", "LEGACY_BG_SERVICE", "FGS_FALLBACK")
        val actual = ProtectionWatchdogRuntime.WatchdogHost.values().map { it.name }.toSet()
        assertEquals(expected, actual)
    }

    @Test
    fun testInitialState_isStoppedAndNoneHost() {
        assertFalse(ProtectionWatchdogRuntime.isRunning)
        assertEquals(ProtectionWatchdogRuntime.WatchdogHost.NONE, ProtectionWatchdogRuntime.activeHost)
    }

    @Test
    fun testForceStop_isIdempotent() {
        // Calling forceStop on already stopped runtime should never throw
        ProtectionWatchdogRuntime.forceStop()
        ProtectionWatchdogRuntime.forceStop()

        assertFalse(ProtectionWatchdogRuntime.isRunning)
        assertEquals(ProtectionWatchdogRuntime.WatchdogHost.NONE, ProtectionWatchdogRuntime.activeHost)
    }

    @Test
    fun testHostPrecedence_detachFromWrongHostIsIgnored() {
        // Use reflection to inspect and verify host state transition logic
        val activeHostField = ProtectionWatchdogRuntime::class.java.getDeclaredField("activeHost")
        activeHostField.isAccessible = true
        val isRunningField = ProtectionWatchdogRuntime::class.java.getDeclaredField("isRunning")
        isRunningField.isAccessible = true

        // Simulate primary host attached
        activeHostField.set(ProtectionWatchdogRuntime, ProtectionWatchdogRuntime.WatchdogHost.DEVICE_ADMIN_SERVICE)
        isRunningField.set(ProtectionWatchdogRuntime, true)

        assertTrue(ProtectionWatchdogRuntime.isRunning)
        assertEquals(ProtectionWatchdogRuntime.WatchdogHost.DEVICE_ADMIN_SERVICE, ProtectionWatchdogRuntime.activeHost)

        // A stopping fallback host tries to detach
        ProtectionWatchdogRuntime.detach(ProtectionWatchdogRuntime.WatchdogHost.FGS_FALLBACK)

        // Invariant: Primary host MUST NOT be detached by a fallback host!
        assertTrue(ProtectionWatchdogRuntime.isRunning)
        assertEquals(ProtectionWatchdogRuntime.WatchdogHost.DEVICE_ADMIN_SERVICE, ProtectionWatchdogRuntime.activeHost)

        // Detach by matching host succeeds
        ProtectionWatchdogRuntime.detach(ProtectionWatchdogRuntime.WatchdogHost.DEVICE_ADMIN_SERVICE)
        assertFalse(ProtectionWatchdogRuntime.isRunning)
        assertEquals(ProtectionWatchdogRuntime.WatchdogHost.NONE, ProtectionWatchdogRuntime.activeHost)
    }

    @Test
    fun testGenerationIdIncrementsOnStop() {
        val genField = ProtectionWatchdogRuntime::class.java.getDeclaredField("generationId")
        genField.isAccessible = true

        val initialGen = genField.getLong(ProtectionWatchdogRuntime)
        ProtectionWatchdogRuntime.forceStop()
        val postStopGen = genField.getLong(ProtectionWatchdogRuntime)

        assertTrue("generationId must increment on stop to invalidate prior callbacks", postStopGen > initialGen)
    }
}
