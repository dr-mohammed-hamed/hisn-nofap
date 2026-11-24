package com.drmohammed.antiporn.watchdog

import android.content.Context
import android.content.ContextWrapper
import android.net.Network
import com.drmohammed.antiporn.curfew.DnsFastHealOutcome
import com.drmohammed.antiporn.curfew.DnsFastHealResult
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

    @Test
    fun testExternalDnsDriftTracker_sliding60sWindowEvictsOldEvents() {
        var now = 100_000L
        val tracker = ProtectionWatchdogRuntime.ExternalDnsDriftTracker(
            windowMs = 60_000L,
            maxEntries = 16,
            clockMs = { now }
        )
        assertEquals(1, tracker.recordDrift())
        now += 15_000L
        assertEquals(2, tracker.recordDrift())
        now += 46_000L // First event is now 61s old -> evicted
        assertEquals(2, tracker.recordDrift())
        now += 61_000L // All prior events evicted
        assertEquals(1, tracker.recordDrift())
        tracker.reset()
        assertEquals(1, tracker.recordDrift())
    }

    @Test
    fun testExternalDriftDispatch_validatedVsUnvalidatedBehavior() {
        val netConstructor = Network::class.java.declaredConstructors.firstOrNull {
            it.parameterTypes.size == 1 && it.parameterTypes[0] == Int::class.javaPrimitiveType
        } ?: Network::class.java.declaredConstructors.first()
        netConstructor.isAccessible = true
        val cellNet = if (netConstructor.parameterTypes.isEmpty()) {
            netConstructor.newInstance() as Network
        } else {
            netConstructor.newInstance(340) as Network
        }

        val healedResult = DnsFastHealResult(
            outcome = DnsFastHealOutcome.HEALED_DIRECT,
            observedMode = "off",
            observedHost = null,
            expectedHost = "family.adguard-dns.com",
            latencyMs = 2L
        )

        // 1. Invariant #2: On a VALIDATED network, tamper NEVER escalates & ALWAYS triggers DPM fast-async
        ProtectionWatchdogRuntime.externalDnsDriftTracker.reset()
        var validatedEscalateCalls = 0
        var validatedDpmFastCalls = 0
        ProtectionWatchdogRuntime.evaluateExternalDriftAndDispatch(
            result = healedResult,
            activeNet = cellNet,
            transport = "CELLULAR",
            isValidated = true,
            recordTelemetry = { _, _, _ -> },
            escalateUnvalidated = { _, _, _ ->
                validatedEscalateCalls++
                true
            },
            triggerDpmFastAsync = { validatedDpmFastCalls++ }
        )
        assertEquals(0, validatedEscalateCalls)
        assertEquals(1, validatedDpmFastCalls)

        // 2. Decision A2 on UNVALIDATED Cellular:
        //    - Drift #1: fast-tracks, suppresses blocking DPM fight since HEALED_DIRECT restored Settings.Global
        //    - Drift #2: immediately escalates, suppresses competing applyDnsShieldFastAsync
        ProtectionWatchdogRuntime.externalDnsDriftTracker.reset()
        val driftTelemetry = mutableListOf<String>()
        var unvalidatedDpmFastCalls = 0
        val recordedDriftCounts = mutableListOf<Int>()

        ProtectionWatchdogRuntime.evaluateExternalDriftAndDispatch(
            result = healedResult,
            activeNet = cellNet,
            transport = "CELLULAR",
            isValidated = false,
            recordTelemetry = { action, _, details -> driftTelemetry.add("$action:$details") },
            escalateUnvalidated = { _, _, count ->
                recordedDriftCounts.add(count)
                false // Drift #1 fast-tracked (1.5s)
            },
            triggerDpmFastAsync = { unvalidatedDpmFastCalls++ }
        )
        ProtectionWatchdogRuntime.evaluateExternalDriftAndDispatch(
            result = healedResult,
            activeNet = cellNet,
            transport = "CELLULAR",
            isValidated = false,
            recordTelemetry = { action, _, details -> driftTelemetry.add("$action:$details") },
            escalateUnvalidated = { _, _, count ->
                recordedDriftCounts.add(count)
                true // Drift #2 immediately escalated
            },
            triggerDpmFastAsync = { unvalidatedDpmFastCalls++ }
        )

        assertEquals(listOf(1, 2), recordedDriftCounts)
        assertEquals(0, unvalidatedDpmFastCalls)
        assertEquals(2, driftTelemetry.size)
        assertTrue(driftTelemetry[0].startsWith("PRIVATE_DNS_EXTERNAL_DRIFT:"))
        assertTrue(driftTelemetry[1].contains("driftCount60s=2, escalated=true"))

        // 3. FAILED_NEEDS_DPM on UNVALIDATED Cellular: 1st drift runs triggerDpmFastAsync, 2nd drift suppresses it
        val failedResult = healedResult.copy(outcome = DnsFastHealOutcome.FAILED_NEEDS_DPM)
        ProtectionWatchdogRuntime.externalDnsDriftTracker.reset()
        var failedDpmCalls = 0
        ProtectionWatchdogRuntime.evaluateExternalDriftAndDispatch(
            result = failedResult,
            activeNet = cellNet,
            transport = "CELLULAR",
            isValidated = false,
            recordTelemetry = { _, _, _ -> },
            escalateUnvalidated = { _, _, _ -> false },
            triggerDpmFastAsync = { failedDpmCalls++ }
        )
        assertEquals(1, failedDpmCalls)
        ProtectionWatchdogRuntime.evaluateExternalDriftAndDispatch(
            result = failedResult,
            activeNet = cellNet,
            transport = "CELLULAR",
            isValidated = false,
            recordTelemetry = { _, _, _ -> },
            escalateUnvalidated = { _, _, _ -> true },
            triggerDpmFastAsync = { failedDpmCalls++ }
        )
        assertEquals(1, failedDpmCalls)

        // 4. Offline (activeNet == null) dispatches directly to triggerDpmFastAsync without escalating
        var offlineEscalateCalls = 0
        var offlineDpmCalls = 0
        ProtectionWatchdogRuntime.evaluateExternalDriftAndDispatch(
            result = healedResult,
            activeNet = null,
            transport = null,
            isValidated = false,
            recordTelemetry = { _, _, _ -> },
            escalateUnvalidated = { _, _, _ ->
                offlineEscalateCalls++
                true
            },
            triggerDpmFastAsync = { offlineDpmCalls++ }
        )
        assertEquals(0, offlineEscalateCalls)
        assertEquals(1, offlineDpmCalls)
    }
}
