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
    fun testApplyStandaloneDot_dpmCalledFirst_andNeverWritesGlobalOnHostNotServing() {
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
                "setDpm:$expectedHost",
                "writeGlobal:private_dns_specifier=$expectedHost",
                "writeGlobal:private_dns_mode=hostname",
                "clearPending"
            ),
            executionLog
        )
    }

    @Test
    fun testApplyStandaloneDot_dpmFailsWithHostNotServing_strictlySkipsGlobalWrite() {
        val executionLog = mutableListOf<String>()
        var clearPendingHostNotServing = 0
        val (resHostNotServing, fallbackHostNotServing) = CurfewVpnDnsBridge.applyStandaloneDotInternal(
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
                true to DevicePolicyManager.PRIVATE_DNS_SET_ERROR_HOST_NOT_SERVING
            },
            clearDnsRestorePending = { clearPendingHostNotServing++ }
        )
        assertEquals(DevicePolicyManager.PRIVATE_DNS_SET_ERROR_HOST_NOT_SERVING, resHostNotServing)
        assertFalse("HOST_NOT_SERVING must NOT report usedFallback=true", fallbackHostNotServing)
        assertEquals(0, clearPendingHostNotServing)
        assertEquals(
            "Settings.Global must NEVER be written when DPM returns HOST_NOT_SERVING (error 1)",
            listOf("markInternalMutation", "setDpm:$expectedHost"),
            executionLog
        )

        // OEM Failure Setting (error 2) falls back to Settings.Global direct write
        val fallbackLog = mutableListOf<String>()
        var clearPendingFailureSetting = 0
        val (resFailureSetting, fallbackFailureSetting) = CurfewVpnDnsBridge.applyStandaloneDotInternal(
            targetHost = expectedHost,
            currentDpmMode = DevicePolicyManager.PRIVATE_DNS_MODE_OFF,
            currentDpmHost = null,
            globalMode = "off",
            globalHost = null,
            isDeviceOwner = true,
            markInternalMutation = { fallbackLog.add("markInternalMutation") },
            writeGlobal = { key, value ->
                fallbackLog.add("writeGlobal:$key=$value")
                true
            },
            setDpmPrivateDns = { host ->
                fallbackLog.add("setDpm:$host")
                false to DevicePolicyManager.PRIVATE_DNS_SET_ERROR_FAILURE_SETTING
            },
            clearDnsRestorePending = {
                clearPendingFailureSetting++
                fallbackLog.add("clearPending")
            }
        )
        assertEquals(DevicePolicyManager.PRIVATE_DNS_SET_ERROR_FAILURE_SETTING, resFailureSetting)
        assertTrue("FAILURE_SETTING with successful Settings.Global write reports usedFallback=true", fallbackFailureSetting)
        assertEquals(1, clearPendingFailureSetting)
        assertEquals(
            listOf(
                "markInternalMutation",
                "setDpm:$expectedHost",
                "writeGlobal:private_dns_specifier=$expectedHost",
                "writeGlobal:private_dns_mode=hostname",
                "clearPending"
            ),
            fallbackLog
        )
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

    @Test
    fun testApplyStandaloneDot_successfulFallback_resetsCircuitBreaker() {
        val executionLog = mutableListOf<String>()
        var clearPendingCalls = 0

        // Simulate circuit breaker previously tripped
        CurfewVpnDnsBridge.applyStandaloneDotInternal(
            targetHost = expectedHost,
            currentDpmMode = DevicePolicyManager.PRIVATE_DNS_MODE_OFF,
            currentDpmHost = null,
            globalMode = "off",
            globalHost = null,
            isDeviceOwner = false, // non-DO tripping
            markInternalMutation = {},
            writeGlobal = { _, _ -> throw SecurityException("Denied") },
            setDpmPrivateDns = { false to DevicePolicyManager.PRIVATE_DNS_SET_ERROR_FAILURE_SETTING },
            clearDnsRestorePending = {}
        )
        assertTrue("Circuit breaker should be tripped", CurfewVpnDnsBridge.isDirectGlobalWriteDeniedForTest())

        // Now run Device Owner fallback write which succeeds (atomic unlock in real life)
        val (resCode, usedFallback) = CurfewVpnDnsBridge.applyStandaloneDotInternal(
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
                false to DevicePolicyManager.PRIVATE_DNS_SET_ERROR_FAILURE_SETTING
            },
            clearDnsRestorePending = { clearPendingCalls++ }
        )

        assertEquals(DevicePolicyManager.PRIVATE_DNS_SET_ERROR_FAILURE_SETTING, resCode)
        assertTrue("Successful Settings.Global fallback must report usedFallback=true", usedFallback)
        assertFalse("Successful Settings.Global fallback write must reset circuit breaker", CurfewVpnDnsBridge.isDirectGlobalWriteDeniedForTest())
        assertEquals(1, clearPendingCalls)
        assertEquals(
            listOf(
                "markInternalMutation",
                "setDpm:$expectedHost",
                "writeGlobal:private_dns_specifier=$expectedHost",
                "writeGlobal:private_dns_mode=hostname"
            ),
            executionLog
        )
    }

    @Test
    fun testApplyStandaloneDot_fallbackPreservesUnaffiliatedUsersBlockedState() {
        CurfewVpnDnsBridge.setPrivateDnsBlockedByUnaffiliatedUsersForTest(true)

        val (_, usedFallback) = CurfewVpnDnsBridge.applyStandaloneDotInternal(
            targetHost = expectedHost,
            currentDpmMode = DevicePolicyManager.PRIVATE_DNS_MODE_OFF,
            currentDpmHost = null,
            globalMode = "off",
            globalHost = null,
            isDeviceOwner = true,
            markInternalMutation = {},
            writeGlobal = { _, _ -> true },
            setDpmPrivateDns = { false to DevicePolicyManager.PRIVATE_DNS_SET_ERROR_FAILURE_SETTING },
            clearDnsRestorePending = {}
        )

        assertTrue(usedFallback)
        assertTrue(
            "Fallback write must NEVER reset isPrivateDnsBlockedByUnaffiliatedUsers to false",
            CurfewVpnDnsBridge.isPrivateDnsBlockedByUnaffiliatedUsers
        )
    }

    @Test
    fun testFastHeal_abortsModeWriteWhenSpecifierWriteFails() {
        val writtenKeys = mutableListOf<String>()
        var clearPendingCalls = 0

        val result = CurfewVpnDnsBridge.tryFastHealGlobalPrivateDnsInternal(
            sdkInt = Build.VERSION_CODES.Q,
            hasActiveTransitions = { false },
            isDeviceOwner = { true },
            isLocalVpnExpected = { false },
            getExpectedHost = { expectedHost },
            readGlobal = { key -> if (key == "private_dns_mode") "off" else null },
            markInternalMutation = {},
            writeGlobal = { key, _ ->
                writtenKeys.add(key)
                false
            },
            clearDnsRestorePending = { clearPendingCalls++ }
        )

        assertEquals(DnsFastHealOutcome.FAILED_NEEDS_DPM, result.outcome)
        assertEquals(
            "Must write private_dns_specifier first and abort before writing private_dns_mode",
            listOf("private_dns_specifier"),
            writtenKeys
        )
        assertEquals(0, clearPendingCalls)
    }

    @Test
    fun testApplyStandaloneDot_abortsModeWriteWhenSpecifierWriteFails() {
        val doWrittenKeys = mutableListOf<String>()
        val (doResCode, doFallback) = CurfewVpnDnsBridge.applyStandaloneDotInternal(
            targetHost = expectedHost,
            currentDpmMode = DevicePolicyManager.PRIVATE_DNS_MODE_OFF,
            currentDpmHost = null,
            globalMode = "off",
            globalHost = null,
            isDeviceOwner = true,
            markInternalMutation = {},
            writeGlobal = { key, _ ->
                doWrittenKeys.add(key)
                false
            },
            setDpmPrivateDns = { false to DevicePolicyManager.PRIVATE_DNS_SET_ERROR_FAILURE_SETTING }
        )

        assertEquals(DevicePolicyManager.PRIVATE_DNS_SET_ERROR_FAILURE_SETTING, doResCode)
        assertFalse(doFallback)
        assertEquals(
            "DO fallback must abort private_dns_mode write if private_dns_specifier write returns false",
            listOf("private_dns_specifier"),
            doWrittenKeys
        )

        val nonDoWrittenKeys = mutableListOf<String>()
        val (nonDoResCode, nonDoFallback) = CurfewVpnDnsBridge.applyStandaloneDotInternal(
            targetHost = expectedHost,
            currentDpmMode = DevicePolicyManager.PRIVATE_DNS_MODE_OFF,
            currentDpmHost = null,
            globalMode = "off",
            globalHost = null,
            isDeviceOwner = false,
            markInternalMutation = {},
            writeGlobal = { key, _ ->
                nonDoWrittenKeys.add(key)
                false
            },
            setDpmPrivateDns = { false to DevicePolicyManager.PRIVATE_DNS_SET_ERROR_FAILURE_SETTING }
        )

        assertEquals(DevicePolicyManager.PRIVATE_DNS_SET_ERROR_FAILURE_SETTING, nonDoResCode)
        assertFalse(nonDoFallback)
        assertEquals(
            "Non-DO fallback must abort private_dns_mode write if private_dns_specifier write returns false",
            listOf("private_dns_specifier"),
            nonDoWrittenKeys
        )
    }

    @Test
    fun testIsLocalVpnDnsStateConsistent_rejectsHostileHostnameEvenWhenUnaffiliatedBlocked() {
        assertFalse(
            "Hostile non-family hostname must NEVER be treated as healthy even when unaffiliatedBlocked=true",
            CurfewVpnDnsBridge.isLocalVpnDnsStateConsistent(
                dpmMode = DevicePolicyManager.PRIVATE_DNS_MODE_PROVIDER_HOSTNAME,
                globalMode = "hostname",
                globalHost = "dns.google",
                isUnaffiliatedBlocked = true
            )
        )
        assertFalse(
            "Blank hostname in strict mode must be rejected even when unaffiliatedBlocked=true",
            CurfewVpnDnsBridge.isLocalVpnDnsStateConsistent(
                dpmMode = DevicePolicyManager.PRIVATE_DNS_MODE_PROVIDER_HOSTNAME,
                globalMode = "hostname",
                globalHost = "",
                isUnaffiliatedBlocked = true
            )
        )
        assertTrue(
            "Verified family preset must be accepted when unaffiliatedBlocked=true",
            CurfewVpnDnsBridge.isLocalVpnDnsStateConsistent(
                dpmMode = DevicePolicyManager.PRIVATE_DNS_MODE_PROVIDER_HOSTNAME,
                globalMode = "hostname",
                globalHost = "family.cloudflare-dns.com",
                isUnaffiliatedBlocked = true
            )
        )
        for (safeMode in listOf(null, "off", "opportunistic")) {
            assertTrue(
                "Safe global mode '$safeMode' must be accepted for Local VPN DNS",
                CurfewVpnDnsBridge.isLocalVpnDnsStateConsistent(
                    dpmMode = DevicePolicyManager.PRIVATE_DNS_MODE_OPPORTUNISTIC,
                    globalMode = safeMode,
                    globalHost = null,
                    isUnaffiliatedBlocked = true
                )
            )
        }
    }

    @Test
    fun testWritePrivateDnsPair_invokedOnceInsteadOfSeparateWriteGlobalCalls() {
        // 1. Fast-heal path
        var fastHealWriteGlobalCalls = 0
        val fastHealPairCalls = mutableListOf<Pair<String, String>>()
        val fastHealResult = CurfewVpnDnsBridge.tryFastHealGlobalPrivateDnsInternal(
            sdkInt = Build.VERSION_CODES.Q,
            hasActiveTransitions = { false },
            isDeviceOwner = { true },
            isLocalVpnExpected = { false },
            getExpectedHost = { expectedHost },
            readGlobal = { key -> if (key == "private_dns_mode") "off" else null },
            markInternalMutation = {},
            writeGlobal = { _, _ ->
                fastHealWriteGlobalCalls++
                true
            },
            writePrivateDnsPair = { mode, specifier ->
                fastHealPairCalls.add(mode to specifier)
                true
            }
        )
        assertEquals(DnsFastHealOutcome.HEALED_DIRECT, fastHealResult.outcome)
        assertEquals(0, fastHealWriteGlobalCalls)
        assertEquals(listOf("hostname" to expectedHost), fastHealPairCalls)

        // 2. applyStandaloneDotInternal (DO path)
        var dotWriteGlobalCalls = 0
        val dotPairCalls = mutableListOf<Pair<String, String>>()
        val (doRes, doFallback) = CurfewVpnDnsBridge.applyStandaloneDotInternal(
            targetHost = expectedHost,
            currentDpmMode = DevicePolicyManager.PRIVATE_DNS_MODE_OFF,
            currentDpmHost = null,
            globalMode = "off",
            globalHost = null,
            isDeviceOwner = true,
            markInternalMutation = {},
            writeGlobal = { _, _ ->
                dotWriteGlobalCalls++
                true
            },
            setDpmPrivateDns = { false to DevicePolicyManager.PRIVATE_DNS_SET_ERROR_FAILURE_SETTING },
            writePrivateDnsPair = { mode, specifier ->
                dotPairCalls.add(mode to specifier)
                true
            }
        )
        assertEquals(DevicePolicyManager.PRIVATE_DNS_SET_ERROR_FAILURE_SETTING, doRes)
        assertTrue(doFallback)
        assertEquals(0, dotWriteGlobalCalls)
        assertEquals(listOf("hostname" to expectedHost), dotPairCalls)

        // 3. applyStandaloneDotInternal (non-DO path)
        var nonDoWriteGlobalCalls = 0
        val nonDoPairCalls = mutableListOf<Pair<String, String>>()
        val (_, nonDoFallback) = CurfewVpnDnsBridge.applyStandaloneDotInternal(
            targetHost = expectedHost,
            currentDpmMode = DevicePolicyManager.PRIVATE_DNS_MODE_OFF,
            currentDpmHost = null,
            globalMode = "off",
            globalHost = null,
            isDeviceOwner = false,
            markInternalMutation = {},
            writeGlobal = { _, _ ->
                nonDoWriteGlobalCalls++
                true
            },
            setDpmPrivateDns = { false to DevicePolicyManager.PRIVATE_DNS_SET_ERROR_FAILURE_SETTING },
            writePrivateDnsPair = { mode, specifier ->
                nonDoPairCalls.add(mode to specifier)
                true
            }
        )
        assertTrue(nonDoFallback)
        assertEquals(0, nonDoWriteGlobalCalls)
        assertEquals(listOf("hostname" to expectedHost), nonDoPairCalls)
    }

    @Test
    fun testWritePrivateDnsPairOrdered_enforcesSafeOrderForHostnameAndOpportunistic() {
        val hostnameWrites = mutableListOf<Pair<String, String>>()
        assertTrue(
            CurfewVpnDnsBridge.writePrivateDnsPairOrdered("hostname", expectedHost) { k, v ->
                hostnameWrites.add(k to v)
                true
            }
        )
        assertEquals(
            listOf("private_dns_specifier" to expectedHost, "private_dns_mode" to "hostname"),
            hostnameWrites
        )

        val opportunisticWrites = mutableListOf<Pair<String, String>>()
        assertTrue(
            CurfewVpnDnsBridge.writePrivateDnsPairOrdered("opportunistic", "") { k, v ->
                opportunisticWrites.add(k to v)
                true
            }
        )
        assertEquals(
            "Downgrading to opportunistic must write private_dns_mode BEFORE clearing private_dns_specifier",
            listOf("private_dns_mode" to "opportunistic", "private_dns_specifier" to ""),
            opportunisticWrites
        )

        val abortedOpportunistic = mutableListOf<String>()
        assertFalse(
            CurfewVpnDnsBridge.writePrivateDnsPairOrdered("opportunistic", "") { k, _ ->
                abortedOpportunistic.add(k)
                false
            }
        )
        assertEquals(listOf("private_dns_mode"), abortedOpportunistic)
    }

    @Test
    fun testZeroBlackoutGuard_skipsEmergencyVpnWhenHostileNonFamilyDnsLocked() {
        assertFalse(
            "Hostile pre-existing non-family Private DNS must NOT trigger Emergency VPN (prevents total blackout)",
            CurfewVpnDnsBridge.isDoTLeakingAndUnaffiliatedBlockedInternal(
                sdkInt = Build.VERSION_CODES.Q,
                isBlocked = true,
                hasVerifiedFamilyDns = false,
                canWriteSecure = false,
                hasBlockingNonFamilyHost = true
            )
        )
        assertTrue(
            "Leaking DoT without hostile lock, without verified family DNS, and without WRITE_SECURE_SETTINGS must activate Emergency VPN",
            CurfewVpnDnsBridge.isDoTLeakingAndUnaffiliatedBlockedInternal(
                sdkInt = Build.VERSION_CODES.Q,
                isBlocked = true,
                hasVerifiedFamilyDns = false,
                canWriteSecure = false,
                hasBlockingNonFamilyHost = false
            )
        )
        assertFalse(
            "Granting WRITE_SECURE_SETTINGS disables Emergency VPN fallback in favor of atomic DoT",
            CurfewVpnDnsBridge.isDoTLeakingAndUnaffiliatedBlockedInternal(
                sdkInt = Build.VERSION_CODES.Q,
                isBlocked = true,
                hasVerifiedFamilyDns = false,
                canWriteSecure = true,
                hasBlockingNonFamilyHost = false
            )
        )
    }

    @Test
    fun testHasActiveVerifiedFamilyDns_acceptsCustomApprovedPolicyHostAndRejectsHostile() {
        assertTrue(
            CurfewVpnDnsBridge.hasActiveVerifiedFamilyDns(
                globalMode = "hostname",
                globalHost = "abc123.dns.nextdns.io",
                approvedPolicyHost = "abc123.dns.nextdns.io"
            )
        )
        assertFalse(
            CurfewVpnDnsBridge.hasActiveVerifiedFamilyDns(
                globalMode = "hostname",
                globalHost = "dns.google",
                approvedPolicyHost = "abc123.dns.nextdns.io"
            )
        )
    }

    @Test
    fun testResolveTargetAlwaysOnPackage_respectsExternalVpnCoexistenceAndBlackhole() {
        val pkg = "com.drmohammed.antiporn"
        assertEquals(
            null,
            CurfewVpnDnsBridge.resolveTargetAlwaysOnPackagePure(
                packageName = pkg,
                requiredMode = CurfewBlackholeVpnService.VpnMode.DNS_FILTERING_ONLY,
                shouldBlockExternalVpn = false
            )
        )
        assertEquals(
            pkg,
            CurfewVpnDnsBridge.resolveTargetAlwaysOnPackagePure(
                packageName = pkg,
                requiredMode = CurfewBlackholeVpnService.VpnMode.DNS_FILTERING_ONLY,
                shouldBlockExternalVpn = true
            )
        )
        assertEquals(
            pkg,
            CurfewVpnDnsBridge.resolveTargetAlwaysOnPackagePure(
                packageName = pkg,
                requiredMode = CurfewBlackholeVpnService.VpnMode.CURFEW_BLACKHOLE,
                shouldBlockExternalVpn = false
            )
        )
        assertEquals(
            null,
            CurfewVpnDnsBridge.resolveTargetAlwaysOnPackagePure(
                packageName = pkg,
                requiredMode = CurfewBlackholeVpnService.VpnMode.IDLE,
                shouldBlockExternalVpn = true
            )
        )
    }

    @Test
    fun testUnaffiliatedDpmReprobe_retriesOnlyWhenUnreliableAfterCooldown() {
        assertFalse(CurfewVpnDnsBridge.shouldAllowUnaffiliatedDpmReprobe(countReliable = true, nowMs = 1_000_000L, lastRetryMs = 0L))
        assertTrue(CurfewVpnDnsBridge.shouldAllowUnaffiliatedDpmReprobe(countReliable = false, nowMs = 10_000L, lastRetryMs = 0L))
        assertFalse(CurfewVpnDnsBridge.shouldAllowUnaffiliatedDpmReprobe(countReliable = false, nowMs = 100_000L, lastRetryMs = 10_000L))
        assertTrue(CurfewVpnDnsBridge.shouldAllowUnaffiliatedDpmReprobe(countReliable = false, nowMs = 310_000L, lastRetryMs = 10_000L))
    }

    @Test
    fun testEvaluateUnaffiliatedDnsNoticeState_distinguishesVpnLockedDotAndWriteSecure() {
        assertEquals(
            UnaffiliatedDnsNoticeState.NONE,
            CurfewVpnDnsBridge.evaluateUnaffiliatedDnsNoticeStateInternal(
                unaffiliatedBlocked = true,
                canWriteSecure = true,
                isDotLeaking = false,
                isLocalVpnTunnelHealthy = false,
                hasVerifiedFamilyDns = true
            )
        )
        assertEquals(
            UnaffiliatedDnsNoticeState.EMERGENCY_VPN_ACTIVE,
            CurfewVpnDnsBridge.evaluateUnaffiliatedDnsNoticeStateInternal(
                unaffiliatedBlocked = true,
                canWriteSecure = false,
                isDotLeaking = true,
                isLocalVpnTunnelHealthy = true,
                hasVerifiedFamilyDns = false
            )
        )
        assertEquals(
            UnaffiliatedDnsNoticeState.DOT_LOCKED_NO_CUSTOMIZE,
            CurfewVpnDnsBridge.evaluateUnaffiliatedDnsNoticeStateInternal(
                unaffiliatedBlocked = true,
                canWriteSecure = false,
                isDotLeaking = false,
                isLocalVpnTunnelHealthy = false,
                hasVerifiedFamilyDns = true
            )
        )
    }

    @Test
    fun testEvaluateWebFilterCapability_blocksMisleadingSavesWhenUnaffiliatedWithoutWriteSecure() {
        assertTrue(
            CurfewVpnDnsBridge.evaluateWebFilterCapabilityInternal(
                sdkInt = Build.VERSION_CODES.Q,
                isDeviceOwner = true,
                unaffiliatedBlocked = true,
                canWriteSecure = false,
                globalMode = "hostname",
                globalHost = "family.cloudflare-dns.com",
                blockingHostileHost = null,
                initialDnsHost = "family.cloudflare-dns.com",
                requestedDnsHost = "family.adguard-dns.com",
                wantsLocalVpnFiltering = false
            ) is WebFilterCapabilityResult.BlockedDnsHostChange
        )
        assertTrue(
            CurfewVpnDnsBridge.evaluateWebFilterCapabilityInternal(
                sdkInt = Build.VERSION_CODES.Q,
                isDeviceOwner = true,
                unaffiliatedBlocked = true,
                canWriteSecure = false,
                globalMode = "hostname",
                globalHost = "family.cloudflare-dns.com",
                blockingHostileHost = null,
                initialDnsHost = "family.cloudflare-dns.com",
                requestedDnsHost = "family.cloudflare-dns.com",
                wantsLocalVpnFiltering = true
            ) is WebFilterCapabilityResult.BlockedLocalFilterByLockedDot
        )
        assertTrue(
            CurfewVpnDnsBridge.evaluateWebFilterCapabilityInternal(
                sdkInt = Build.VERSION_CODES.Q,
                isDeviceOwner = true,
                unaffiliatedBlocked = true,
                canWriteSecure = false,
                globalMode = "hostname",
                globalHost = "dns.google",
                blockingHostileHost = "dns.google",
                initialDnsHost = "family.cloudflare-dns.com",
                requestedDnsHost = "family.adguard-dns.com",
                wantsLocalVpnFiltering = false
            ) is WebFilterCapabilityResult.BlockedHostilePrivateDns
        )
        assertEquals(
            WebFilterCapabilityResult.Allowed,
            CurfewVpnDnsBridge.evaluateWebFilterCapabilityInternal(
                sdkInt = Build.VERSION_CODES.Q,
                isDeviceOwner = true,
                unaffiliatedBlocked = true,
                canWriteSecure = true,
                globalMode = "hostname",
                globalHost = "family.cloudflare-dns.com",
                blockingHostileHost = null,
                initialDnsHost = "family.cloudflare-dns.com",
                requestedDnsHost = "family.adguard-dns.com",
                wantsLocalVpnFiltering = true
            )
        )
        assertEquals(
            WebFilterCapabilityResult.Allowed,
            CurfewVpnDnsBridge.evaluateWebFilterCapabilityInternal(
                sdkInt = Build.VERSION_CODES.Q,
                isDeviceOwner = true,
                unaffiliatedBlocked = true,
                canWriteSecure = false,
                globalMode = null,
                globalHost = null,
                blockingHostileHost = null,
                initialDnsHost = "family.cloudflare-dns.com",
                requestedDnsHost = "family.adguard-dns.com",
                wantsLocalVpnFiltering = true
            )
        )
    }

    @Test
    fun testApplyStandaloneDot_offlineHostNotServing_preArmsSettingsGlobalAndMarksPendingDpmSync() {
        val executionLog = mutableListOf<String>()
        var pendingDpmState: Boolean? = null
        var clearPendingCalls = 0

        val (resCode, usedFallback) = CurfewVpnDnsBridge.applyStandaloneDotInternal(
            targetHost = expectedHost,
            currentDpmMode = DevicePolicyManager.PRIVATE_DNS_MODE_OPPORTUNISTIC,
            currentDpmHost = null,
            globalMode = "opportunistic",
            globalHost = "",
            isDeviceOwner = true,
            markInternalMutation = { executionLog.add("markInternalMutation") },
            writeGlobal = { key, value ->
                executionLog.add("writeGlobal:$key=$value")
                true
            },
            setDpmPrivateDns = { host ->
                executionLog.add("setDpm:$host")
                true to DevicePolicyManager.PRIVATE_DNS_SET_ERROR_HOST_NOT_SERVING
            },
            clearDnsRestorePending = { clearPendingCalls++ },
            isDeviceOffline = true,
            markPendingDpmSync = { pending ->
                pendingDpmState = pending
                executionLog.add("markPendingDpm=$pending")
            }
        )

        assertEquals(DevicePolicyManager.PRIVATE_DNS_SET_ERROR_HOST_NOT_SERVING, resCode)
        assertTrue("Offline HOST_NOT_SERVING must pre-arm via Settings.Global", usedFallback)
        assertEquals(true, pendingDpmState)
        assertEquals(1, clearPendingCalls)
        assertEquals(
            listOf(
                "markInternalMutation",
                "setDpm:$expectedHost",
                "writeGlobal:private_dns_specifier=$expectedHost",
                "writeGlobal:private_dns_mode=hostname",
                "markPendingDpm=true"
            ),
            executionLog
        )

        // When online later and DPM succeeds, pending DPM sync must be cleared cleanly
        val (onlineRes, onlineFallback) = CurfewVpnDnsBridge.applyStandaloneDotInternal(
            targetHost = expectedHost,
            currentDpmMode = DevicePolicyManager.PRIVATE_DNS_MODE_OPPORTUNISTIC,
            currentDpmHost = null,
            globalMode = "hostname",
            globalHost = expectedHost,
            isDeviceOwner = true,
            markInternalMutation = {},
            writeGlobal = { _, _ -> true },
            setDpmPrivateDns = { _ -> true to DevicePolicyManager.PRIVATE_DNS_SET_NO_ERROR },
            clearDnsRestorePending = {},
            isDeviceOffline = false,
            markPendingDpmSync = { pending -> pendingDpmState = pending }
        )

        assertEquals(DevicePolicyManager.PRIVATE_DNS_SET_NO_ERROR, onlineRes)
        assertFalse(onlineFallback)
        assertEquals(false, pendingDpmState)
    }

    @Test
    fun testIsStandaloneDnsStateConsistent_offlinePreArmedReportsConsistent_onlineRequiresDpmConvergence() {
        assertTrue(
            "Offline pre-armed state must report consistent/ACTIVE without false tamper alarm",
            CurfewVpnDnsBridge.isStandaloneDnsStateConsistent(
                dpmMode = DevicePolicyManager.PRIVATE_DNS_MODE_OPPORTUNISTIC,
                dpmHost = null,
                globalMode = "hostname",
                globalHost = expectedHost,
                targetHost = expectedHost,
                isUnaffiliatedBlocked = false,
                isOfflinePreArmedPendingDpm = true
            )
        )
        assertFalse(
            "Online state with un-converged DPM must require sync until DPM succeeds",
            CurfewVpnDnsBridge.isStandaloneDnsStateConsistent(
                dpmMode = DevicePolicyManager.PRIVATE_DNS_MODE_OPPORTUNISTIC,
                dpmHost = null,
                globalMode = "hostname",
                globalHost = expectedHost,
                targetHost = expectedHost,
                isUnaffiliatedBlocked = false,
                isOfflinePreArmedPendingDpm = false
            )
        )
    }

    @Test
    fun testApplyStandaloneDot_dpmSucceedsAndGlobalMatches_noDoubleGlobalWrite() {
        var globalWriteCalls = 0
        val (resCode, usedFallback) = CurfewVpnDnsBridge.applyStandaloneDotInternal(
            targetHost = expectedHost,
            currentDpmMode = DevicePolicyManager.PRIVATE_DNS_MODE_OFF,
            currentDpmHost = null,
            globalMode = "off",
            globalHost = null,
            isDeviceOwner = true,
            markInternalMutation = {},
            writeGlobal = { _, _ ->
                globalWriteCalls++
                true
            },
            setDpmPrivateDns = { true to DevicePolicyManager.PRIVATE_DNS_SET_NO_ERROR },
            readGlobalAfterDpm = { "hostname" to expectedHost }
        )

        assertEquals(DevicePolicyManager.PRIVATE_DNS_SET_NO_ERROR, resCode)
        assertFalse(usedFallback)
        assertEquals("Must NOT perform double write to Settings.Global when DPM succeeded and global matches", 0, globalWriteCalls)
    }

    @Test
    fun testStandaloneDotEnforcer_errorFailureSettingWithGlobalAlreadyMatched_reportsUsedFallback() {
        var clearedRestorePending = false
        val (resCode, usedFallback) = StandaloneDotEnforcer.applyStandaloneDotInternal(
            targetHost = expectedHost,
            currentDpmMode = DevicePolicyManager.PRIVATE_DNS_MODE_OFF,
            currentDpmHost = null,
            globalMode = "hostname",
            globalHost = expectedHost,
            isDeviceOwner = true,
            markInternalMutation = {},
            writeGlobal = { _, _ -> true },
            setDpmPrivateDns = { false to DevicePolicyManager.PRIVATE_DNS_SET_ERROR_FAILURE_SETTING },
            clearDnsRestorePending = { clearedRestorePending = true }
        )

        assertEquals(DevicePolicyManager.PRIVATE_DNS_SET_ERROR_FAILURE_SETTING, resCode)
        assertTrue("Pre-armed Settings.Global must report usedFallback=true on DPM error 2", usedFallback)
        assertTrue("clearDnsRestorePending must be called when fail-closed global fallback is active", clearedRestorePending)
    }
}
