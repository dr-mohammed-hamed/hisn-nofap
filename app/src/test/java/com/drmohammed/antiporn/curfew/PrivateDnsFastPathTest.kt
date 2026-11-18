package com.drmohammed.antiporn.curfew

import android.app.admin.DevicePolicyManager
import android.os.Build
import com.drmohammed.antiporn.core.engine.ProtectionTransitionGuard
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for the zero-latency Private DNS fast-heal path ([CurfewVpnDnsBridge]),
 * inverted Settings.Global-before-DPM ordering, and [ProtectionTransitionGuard] active-transition guards.
 */
class PrivateDnsFastPathTest {

    private val expectedHost = "family.cloudflare-dns.com"
    private var fakeElapsedMs = 10_000L

    @Before
    fun setUp() {
        ProtectionTransitionGuard.resetForTest()
        CurfewVpnDnsBridge.resetFastHealCircuitBreakerForTest()
        fakeElapsedMs = 10_000L
        ProtectionTransitionGuard.elapsedRealtimeProvider = { fakeElapsedMs }
    }

    @After
    fun tearDown() {
        ProtectionTransitionGuard.resetForTest()
        CurfewVpnDnsBridge.resetFastHealCircuitBreakerForTest()
    }

    @Test
    fun testFastHeal_tamperedModeOrHost_restoresDirectlyAndClearsPending() {
        val tamperedCases = listOf(
            "off" to null,
            "opportunistic" to "",
            "hostname" to "dns.google"
        )

        for ((observedMode, observedHost) in tamperedCases) {
            val written = linkedMapOf<String, String>()
            var markMutationCalls = 0
            var clearPendingCalls = 0

            val result = CurfewVpnDnsBridge.tryFastHealGlobalPrivateDnsInternal(
                sdkInt = Build.VERSION_CODES.Q,
                hasActiveTransitions = { ProtectionTransitionGuard.hasActiveTransitions() },
                isDeviceOwner = { true },
                isLocalVpnExpected = { false },
                getExpectedHost = { expectedHost },
                readGlobal = { key ->
                    when (key) {
                        "private_dns_mode" -> observedMode
                        "private_dns_specifier" -> observedHost
                        else -> null
                    }
                },
                markInternalMutation = { markMutationCalls++ },
                writeGlobal = { key, value ->
                    written[key] = value
                    true
                },
                clearDnsRestorePending = { clearPendingCalls++ }
            )

            assertEquals("Case ($observedMode, $observedHost) must heal directly", DnsFastHealOutcome.HEALED_DIRECT, result.outcome)
            assertEquals(observedMode, result.observedMode)
            assertEquals(observedHost, result.observedHost)
            assertEquals(expectedHost, result.expectedHost)
            assertTrue("Latency must be non-negative", result.latencyMs >= 0L)
            assertEquals("hostname", written["private_dns_mode"])
            assertEquals(expectedHost, written["private_dns_specifier"])
            assertEquals(1, markMutationCalls)
            assertEquals(1, clearPendingCalls)
        }
    }

    @Test
    fun testFastHeal_alreadyCompliantCaseInsensitive_skipsWritesToPreventFeedbackLoop() {
        var writeCalls = 0
        var markMutationCalls = 0
        var clearPendingCalls = 0

        val result = CurfewVpnDnsBridge.tryFastHealGlobalPrivateDnsInternal(
            sdkInt = Build.VERSION_CODES.Q,
            hasActiveTransitions = { false },
            isDeviceOwner = { true },
            isLocalVpnExpected = { false },
            getExpectedHost = { expectedHost },
            readGlobal = { key ->
                when (key) {
                    "private_dns_mode" -> "hostname"
                    "private_dns_specifier" -> "FAMILY.CLOUDFLARE-DNS.COM"
                    else -> null
                }
            },
            markInternalMutation = { markMutationCalls++ },
            writeGlobal = { _, _ ->
                writeCalls++
                true
            },
            clearDnsRestorePending = { clearPendingCalls++ }
        )

        assertEquals(DnsFastHealOutcome.ALREADY_COMPLIANT, result.outcome)
        assertEquals("hostname", result.observedMode)
        assertEquals("FAMILY.CLOUDFLARE-DNS.COM", result.observedHost)
        assertEquals(expectedHost, result.expectedHost)
        assertEquals(0, writeCalls)
        assertEquals(0, markMutationCalls)
        assertEquals(0, clearPendingCalls)
    }

    @Test
    fun testFastHeal_rapidSecondTamperInsideSettleWindow_healsImmediatelyWithoutWaiting() {
        ProtectionTransitionGuard.markInternalDnsMutation()
        fakeElapsedMs += 500L // Inside the 3.5s settle window, with no active transition running

        assertTrue(
            "Precondition: 500ms after mutation must still be inside 3.5s settle window",
            ProtectionTransitionGuard.isWithinSettleWindow(3500L)
        )
        assertFalse(
            "hasActiveTransitions must be false once mutation completes",
            ProtectionTransitionGuard.hasActiveTransitions()
        )

        val written = mutableMapOf<String, String>()
        val result = CurfewVpnDnsBridge.tryFastHealGlobalPrivateDnsInternal(
            sdkInt = Build.VERSION_CODES.Q,
            hasActiveTransitions = { ProtectionTransitionGuard.hasActiveTransitions() },
            isDeviceOwner = { true },
            isLocalVpnExpected = { false },
            getExpectedHost = { expectedHost },
            readGlobal = { key -> if (key == "private_dns_mode") "off" else null },
            markInternalMutation = { ProtectionTransitionGuard.markInternalDnsMutation() },
            writeGlobal = { key, value ->
                written[key] = value
                true
            }
        )

        assertEquals(DnsFastHealOutcome.HEALED_DIRECT, result.outcome)
        assertEquals("hostname", written["private_dns_mode"])
        assertEquals(expectedHost, written["private_dns_specifier"])
    }

    @Test
    fun testFastHeal_teardownInProgress_skipsWithZeroWrites() {
        ProtectionTransitionGuard.isTeardownInProgress = true
        assertTrue(ProtectionTransitionGuard.hasActiveTransitions())

        var writeCalls = 0
        val result = CurfewVpnDnsBridge.tryFastHealGlobalPrivateDnsInternal(
            sdkInt = Build.VERSION_CODES.Q,
            hasActiveTransitions = { ProtectionTransitionGuard.hasActiveTransitions() },
            isDeviceOwner = { true },
            isLocalVpnExpected = { false },
            getExpectedHost = { expectedHost },
            readGlobal = { "off" },
            markInternalMutation = {},
            writeGlobal = { _, _ ->
                writeCalls++
                true
            }
        )

        assertEquals(DnsFastHealOutcome.SKIPPED_ACTIVE_TRANSITION, result.outcome)
        assertEquals(0, writeCalls)
    }

    @Test
    fun testFastHeal_activeInternalTransition_skipsInsideBlockAndResumesAfter() {
        var writeCalls = 0
        lateinit var resultInside: DnsFastHealResult

        ProtectionTransitionGuard.runInternalTransition {
            assertTrue(ProtectionTransitionGuard.hasActiveTransitions())
            resultInside = CurfewVpnDnsBridge.tryFastHealGlobalPrivateDnsInternal(
                sdkInt = Build.VERSION_CODES.Q,
                hasActiveTransitions = { ProtectionTransitionGuard.hasActiveTransitions() },
                isDeviceOwner = { true },
                isLocalVpnExpected = { false },
                getExpectedHost = { expectedHost },
                readGlobal = { "off" },
                markInternalMutation = {},
                writeGlobal = { _, _ ->
                    writeCalls++
                    true
                }
            )
        }

        assertEquals(DnsFastHealOutcome.SKIPPED_ACTIVE_TRANSITION, resultInside.outcome)
        assertEquals(0, writeCalls)
        assertFalse(ProtectionTransitionGuard.hasActiveTransitions())
    }

    @Test
    fun testTransitionGuard_stuckTokenExceeding15Seconds_evictedAutomatically() {
        val stuckToken = ProtectionTransitionGuard.nextToken.incrementAndGet()
        ProtectionTransitionGuard.activeTransitions[stuckToken] = fakeElapsedMs

        fakeElapsedMs += 15_000L
        assertTrue("Token at exact 15s boundary must still be considered active", ProtectionTransitionGuard.hasActiveTransitions())

        fakeElapsedMs += 1L
        assertFalse("Token exceeding 15s timeout must be evicted", ProtectionTransitionGuard.hasActiveTransitions())
        assertTrue(ProtectionTransitionGuard.activeTransitions.isEmpty())
    }

    @Test
    fun testFastHeal_environmentGuardRails_skipWithoutWriting() {
        data class GuardCase(
            val label: String,
            val sdkInt: Int,
            val isDeviceOwner: Boolean,
            val isLocalVpnExpected: Boolean,
            val expectedOutcome: DnsFastHealOutcome
        )

        val cases = listOf(
            GuardCase("Curfew VPN active", Build.VERSION_CODES.Q, true, true, DnsFastHealOutcome.SKIPPED_VPN_EXPECTED),
            GuardCase("Unsupported API 28", Build.VERSION_CODES.P, true, false, DnsFastHealOutcome.SKIPPED_UNSUPPORTED_SDK),
            GuardCase("Not Device Owner", Build.VERSION_CODES.Q, false, false, DnsFastHealOutcome.SKIPPED_NOT_DEVICE_OWNER)
        )

        for (case in cases) {
            var writeCalls = 0
            val result = CurfewVpnDnsBridge.tryFastHealGlobalPrivateDnsInternal(
                sdkInt = case.sdkInt,
                hasActiveTransitions = { false },
                isDeviceOwner = { case.isDeviceOwner },
                isLocalVpnExpected = { case.isLocalVpnExpected },
                getExpectedHost = { expectedHost },
                readGlobal = { "off" },
                markInternalMutation = {},
                writeGlobal = { _, _ ->
                    writeCalls++
                    true
                }
            )

            assertEquals("Guard '${case.label}' returned wrong outcome", case.expectedOutcome, result.outcome)
            assertEquals("Guard '${case.label}' must perform zero writes", 0, writeCalls)
        }
    }

    @Test
    fun testFastHeal_securityException_tripsCircuitBreakerAndShortCircuitsSubsequentCalls() {
        var writeAttempts = 0
        var clearPendingCalls = 0

        val first = CurfewVpnDnsBridge.tryFastHealGlobalPrivateDnsInternal(
            sdkInt = Build.VERSION_CODES.Q,
            hasActiveTransitions = { false },
            isDeviceOwner = { true },
            isLocalVpnExpected = { false },
            getExpectedHost = { expectedHost },
            readGlobal = { key -> if (key == "private_dns_mode") "off" else null },
            markInternalMutation = {},
            writeGlobal = { _, _ ->
                writeAttempts++
                throw SecurityException("Permission denial: WRITE_SECURE_SETTINGS")
            },
            clearDnsRestorePending = { clearPendingCalls++ }
        )

        assertEquals(DnsFastHealOutcome.PERMISSION_DENIED_NEEDS_DPM, first.outcome)
        assertTrue(CurfewVpnDnsBridge.isDirectGlobalWriteDeniedForTest())
        assertEquals(1, writeAttempts)
        assertEquals(0, clearPendingCalls)
        assertNotNull(first.errorMessage)

        val second = CurfewVpnDnsBridge.tryFastHealGlobalPrivateDnsInternal(
            sdkInt = Build.VERSION_CODES.Q,
            hasActiveTransitions = { false },
            isDeviceOwner = { true },
            isLocalVpnExpected = { false },
            getExpectedHost = { expectedHost },
            readGlobal = { key -> if (key == "private_dns_mode") "off" else null },
            markInternalMutation = {},
            writeGlobal = { _, _ ->
                writeAttempts++
                true
            },
            clearDnsRestorePending = { clearPendingCalls++ }
        )

        assertEquals(DnsFastHealOutcome.PERMISSION_DENIED_NEEDS_DPM, second.outcome)
        assertEquals("Circuit breaker must prevent further Settings.Global writes", 1, writeAttempts)
        assertEquals(0, clearPendingCalls)
    }

    @Test
    fun testFastHeal_nonPermissionWriteFailure_returnsFailedWithoutTrippingCircuitBreaker() {
        val failureWriters: List<Pair<String, (String, String) -> Boolean>> = listOf(
            "putString returns false" to { _, _ -> false },
            "IllegalStateException" to { _, _ -> throw IllegalStateException("ContentProvider unavailable") }
        )

        for ((label, failingWriter) in failureWriters) {
            var clearPendingCalls = 0
            val result = CurfewVpnDnsBridge.tryFastHealGlobalPrivateDnsInternal(
                sdkInt = Build.VERSION_CODES.Q,
                hasActiveTransitions = { false },
                isDeviceOwner = { true },
                isLocalVpnExpected = { false },
                getExpectedHost = { expectedHost },
                readGlobal = { key -> if (key == "private_dns_mode") "off" else null },
                markInternalMutation = {},
                writeGlobal = failingWriter,
                clearDnsRestorePending = { clearPendingCalls++ }
            )

            assertEquals("Case '$label' must delegate to DPM", DnsFastHealOutcome.FAILED_NEEDS_DPM, result.outcome)
            assertFalse("Case '$label' must NOT trip SecurityException circuit breaker", CurfewVpnDnsBridge.isDirectGlobalWriteDeniedForTest())
            assertEquals(0, clearPendingCalls)
        }
    }

    @Test
    fun testApplyStandaloneDot_writesSettingsGlobalStrictlyBeforeDpmCall() {
        val executionLog = mutableListOf<String>()

        val (resultCode, usedFallback) = CurfewVpnDnsBridge.applyStandaloneDotInternal(
            targetHost = expectedHost,
            currentDpmMode = DevicePolicyManager.PRIVATE_DNS_MODE_OFF,
            currentDpmHost = null,
            globalMode = "off",
            globalHost = null,
            isDeviceOwner = true,
            markInternalMutation = { executionLog.add("markInternalMutation") },
            writeGlobal = { key, value ->
                executionLog.add("writeGlobal:$key=$value")
                true
            },
            setDpmPrivateDns = { host ->
                executionLog.add("setDpm:$host")
                true to DevicePolicyManager.PRIVATE_DNS_SET_NO_ERROR
            },
            clearDnsRestorePending = { executionLog.add("clearPending") }
        )

        assertEquals(DevicePolicyManager.PRIVATE_DNS_SET_NO_ERROR, resultCode)
        assertFalse(usedFallback)
        assertEquals(
            listOf(
                "markInternalMutation",
                "writeGlobal:private_dns_mode=hostname",
                "writeGlobal:private_dns_specifier=$expectedHost",
                "setDpm:$expectedHost",
                "clearPending"
            ),
            executionLog
        )
    }

    @Test
    fun testApplyStandaloneDot_dpmFailsAfterGlobalWriteSucceeded_returnsFallbackAndClearsPending() {
        val dpmFailureCases = listOf(
            true to DevicePolicyManager.PRIVATE_DNS_SET_ERROR_HOST_NOT_SERVING,
            false to DevicePolicyManager.PRIVATE_DNS_SET_ERROR_FAILURE_SETTING
        )

        for ((dpmOk, dpmCode) in dpmFailureCases) {
            var clearPendingCalls = 0

            val (resultCode, usedFallback) = CurfewVpnDnsBridge.applyStandaloneDotInternal(
                targetHost = expectedHost,
                currentDpmMode = DevicePolicyManager.PRIVATE_DNS_MODE_OFF,
                currentDpmHost = null,
                globalMode = "off",
                globalHost = null,
                isDeviceOwner = true,
                markInternalMutation = {},
                writeGlobal = { _, _ -> true },
                setDpmPrivateDns = { dpmOk to dpmCode },
                clearDnsRestorePending = { clearPendingCalls++ }
            )

            assertEquals(dpmCode, resultCode)
            assertTrue("DPM failure ($dpmOk, $dpmCode) after Global write must report usedFallback=true", usedFallback)
            assertEquals(1, clearPendingCalls)
        }
    }

    @Test
    fun testApplyStandaloneDot_globalWriteSecurityException_tripsCircuitBreakerAndStillRunsDpm() {
        var dpmHostCalled: String? = null
        var clearPendingCalls = 0

        val (resultCode, usedFallback) = CurfewVpnDnsBridge.applyStandaloneDotInternal(
            targetHost = expectedHost,
            currentDpmMode = DevicePolicyManager.PRIVATE_DNS_MODE_OFF,
            currentDpmHost = null,
            globalMode = "off",
            globalHost = null,
            isDeviceOwner = true,
            markInternalMutation = {},
            writeGlobal = { _, _ -> throw SecurityException("Permission denial: WRITE_SECURE_SETTINGS") },
            setDpmPrivateDns = { host ->
                dpmHostCalled = host
                true to DevicePolicyManager.PRIVATE_DNS_SET_NO_ERROR
            },
            clearDnsRestorePending = { clearPendingCalls++ }
        )

        assertTrue(CurfewVpnDnsBridge.isDirectGlobalWriteDeniedForTest())
        assertEquals(expectedHost, dpmHostCalled)
        assertEquals(DevicePolicyManager.PRIVATE_DNS_SET_NO_ERROR, resultCode)
        assertFalse(usedFallback)
        assertEquals(1, clearPendingCalls)
    }
}
