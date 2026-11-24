package com.drmohammed.antiporn.core.network.dns

import android.content.Context
import android.net.Network
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class DnsResilienceManagerTest {

    private val fakeContext = object : android.content.ContextWrapper(null) {
        override fun getApplicationContext(): Context = this
        override fun getSystemService(name: String): Any? = null
    }

    private fun createMockNetwork(netId: Int): Network {
        val constructor = Network::class.java.declaredConstructors.firstOrNull {
            it.parameterTypes.size == 1 && it.parameterTypes[0] == Int::class.javaPrimitiveType
        } ?: Network::class.java.declaredConstructors.first()
        constructor.isAccessible = true
        return if (constructor.parameterTypes.isEmpty()) {
            constructor.newInstance() as Network
        } else {
            constructor.newInstance(netId) as Network
        }
    }

    @Before
    fun setUp() {
        com.drmohammed.antiporn.core.engine.ProtectionTransitionGuard.resetForTest()
        DnsResilienceManager.onPolicyDnsHostChanged(fakeContext)
    }

    @Test
    fun testDnsResilienceManager_initialStateIsPrimaryStrictDot() {
        assertEquals(
            DnsResilienceManager.DnsResilienceMode.PRIMARY_STRICT_DOT,
            DnsResilienceManager.getActiveMode()
        )
        assertFalse(DnsResilienceManager.isLocalVpnFallbackActive())
        assertNull(DnsResilienceManager.getProtectedWifiNetworkId())
        assertEquals(
            "family.cloudflare-dns.com",
            DnsResilienceManager.getEffectiveRuntimeDnsHost("family.cloudflare-dns.com")
        )
    }

    @Test
    fun testDnsResilienceManager_antiFlapping_cellularLostPreservesProtection() {
        val wifiNet = createMockNetwork(263)
        val cellNet = createMockNetwork(260)

        // Simulate activating Level 3 fallback manually for test verification
        val stateLockField = DnsResilienceManager.javaClass.getDeclaredField("stateLock")
        stateLockField.isAccessible = true
        val lock = stateLockField.get(DnsResilienceManager)

        val activeModeField = DnsResilienceManager.javaClass.getDeclaredField("activeMode")
        activeModeField.isAccessible = true

        val protectedNetField = DnsResilienceManager.javaClass.getDeclaredField("protectedWifiNetworkId")
        protectedNetField.isAccessible = true

        val wifiId = DnsResilienceManager.resolveNetworkId(wifiNet)
        synchronized(lock) {
            activeModeField.set(DnsResilienceManager, DnsResilienceManager.DnsResilienceMode.FALLBACK_LOCAL_VPN_DOH)
            protectedNetField.set(DnsResilienceManager, wifiId)
        }

        assertTrue(DnsResilienceManager.isLocalVpnFallbackActive())
        assertEquals(wifiId, DnsResilienceManager.getProtectedWifiNetworkId())

        // Cellular disconnects (e.g. user turned off mobile data or SIM roaming dropped)
        // Must NOT flap or revert Wi-Fi fallback!
        DnsResilienceManager.onNetworkChanged(
            fakeContext,
            "net_lost:CELLULAR",
            cellNet,
            "CELLULAR"
        )
        assertTrue("Cellular disconnect must not flap Wi-Fi resilience", DnsResilienceManager.isLocalVpnFallbackActive())
        assertEquals(wifiId, DnsResilienceManager.getProtectedWifiNetworkId())

        // Wi-Fi itself becomes validated under Opportunistic DNS
        // Must NOT flap or revert!
        DnsResilienceManager.onNetworkChanged(
            fakeContext,
            "net_validated:WIFI",
            wifiNet,
            "WIFI"
        )
        assertTrue("Wi-Fi opportunistic validation must preserve local VPN DoH protection", DnsResilienceManager.isLocalVpnFallbackActive())

        // Wi-Fi is physically disconnected (user left home or disabled Wi-Fi)
        // MUST cleanly restore PRIMARY_STRICT_DOT!
        DnsResilienceManager.onNetworkChanged(
            fakeContext,
            "net_lost:WIFI",
            wifiNet,
            "WIFI"
        )
        assertEquals(
            DnsResilienceManager.DnsResilienceMode.PRIMARY_STRICT_DOT,
            DnsResilienceManager.getActiveMode()
        )
        assertFalse(DnsResilienceManager.isLocalVpnFallbackActive())
        assertNull(DnsResilienceManager.getProtectedWifiNetworkId())
    }

    @Test
    fun testDnsResilienceManager_policyHostChanged_resetsState() {
        val wifiNet = createMockNetwork(263)
        val wifiId = DnsResilienceManager.resolveNetworkId(wifiNet)
        val stateLockField = DnsResilienceManager.javaClass.getDeclaredField("stateLock")
        stateLockField.isAccessible = true
        val lock = stateLockField.get(DnsResilienceManager)

        val activeModeField = DnsResilienceManager.javaClass.getDeclaredField("activeMode")
        activeModeField.isAccessible = true

        val protectedNetField = DnsResilienceManager.javaClass.getDeclaredField("protectedWifiNetworkId")
        protectedNetField.isAccessible = true

        synchronized(lock) {
            activeModeField.set(DnsResilienceManager, DnsResilienceManager.DnsResilienceMode.FALLBACK_LOCAL_VPN_DOH)
            protectedNetField.set(DnsResilienceManager, wifiId)
        }

        DnsResilienceManager.onPolicyDnsHostChanged(fakeContext)

        assertEquals(
            DnsResilienceManager.DnsResilienceMode.PRIMARY_STRICT_DOT,
            DnsResilienceManager.getActiveMode()
        )
        assertNull(DnsResilienceManager.getProtectedWifiNetworkId())
    }

    @Test
    fun testDnsResilienceManager_resetStateSync_immediatelyRestoresState() {
        val wifiNet = createMockNetwork(263)
        val wifiId = DnsResilienceManager.resolveNetworkId(wifiNet)
        val stateLockField = DnsResilienceManager.javaClass.getDeclaredField("stateLock")
        stateLockField.isAccessible = true
        val lock = stateLockField.get(DnsResilienceManager)

        val activeModeField = DnsResilienceManager.javaClass.getDeclaredField("activeMode")
        activeModeField.isAccessible = true

        val protectedNetField = DnsResilienceManager.javaClass.getDeclaredField("protectedWifiNetworkId")
        protectedNetField.isAccessible = true

        synchronized(lock) {
            activeModeField.set(DnsResilienceManager, DnsResilienceManager.DnsResilienceMode.FALLBACK_LOCAL_VPN_DOH)
            protectedNetField.set(DnsResilienceManager, wifiId)
        }

        assertTrue(DnsResilienceManager.isLocalVpnFallbackActive())
        assertEquals(wifiId, DnsResilienceManager.getProtectedWifiNetworkId())

        DnsResilienceManager.resetStateSync()

        assertEquals(
            DnsResilienceManager.DnsResilienceMode.PRIMARY_STRICT_DOT,
            DnsResilienceManager.getActiveMode()
        )
        assertFalse(DnsResilienceManager.isLocalVpnFallbackActive())
        assertNull(DnsResilienceManager.getProtectedWifiNetworkId())
    }

    @Test
    fun testDnsResilienceManager_getEffectiveRuntimeDnsHost_returnsFallbackWhenInLevel2() {
        val stateLockField = DnsResilienceManager.javaClass.getDeclaredField("stateLock")
        stateLockField.isAccessible = true
        val lock = stateLockField.get(DnsResilienceManager)

        val activeModeField = DnsResilienceManager.javaClass.getDeclaredField("activeMode")
        activeModeField.isAccessible = true

        val overrideDotHostField = DnsResilienceManager.javaClass.getDeclaredField("overrideDotHost")
        overrideDotHostField.isAccessible = true

        synchronized(lock) {
            activeModeField.set(DnsResilienceManager, DnsResilienceManager.DnsResilienceMode.FALLBACK_STRICT_DOT)
            overrideDotHostField.set(DnsResilienceManager, "family-filter-dns.cleanbrowsing.org")
        }

        assertEquals(
            "family-filter-dns.cleanbrowsing.org",
            DnsResilienceManager.getEffectiveRuntimeDnsHost("family.cloudflare-dns.com")
        )

        // Synchronous reset clears override
        DnsResilienceManager.resetStateSync()
        assertEquals(
            "family.cloudflare-dns.com",
            DnsResilienceManager.getEffectiveRuntimeDnsHost("family.cloudflare-dns.com")
        )
    }

    @Test
    fun testDnsResilienceManager_wifiAlreadyValidated_strictlySkipsIntervention() = kotlinx.coroutines.runBlocking {
        val wifiNet = createMockNetwork(263)
        var probeInvoked = false

        DnsResilienceManager.resetStateSync()
        DnsResilienceManager.curfewOrVpnActiveChecker = { false }
        DnsResilienceManager.isWifiValidatedChecker = { true }
        DnsResilienceManager.internetProbeOverride = {
            probeInvoked = true
            true
        }

        DnsResilienceManager.evaluateWifiUnvalidatedOrDotErrorDirect(
            fakeContext,
            wifiNet,
            "test_validated"
        )

        assertEquals(
            DnsResilienceManager.DnsResilienceMode.PRIMARY_STRICT_DOT,
            DnsResilienceManager.getActiveMode()
        )
        assertFalse("Probes must not be invoked on already validated Wi-Fi", probeInvoked)
        assertFalse(DnsResilienceManager.isLocalVpnFallbackActive())
    }

    @Test
    fun testDnsResilienceManager_curfewOrVpnActive_strictlySkipsIntervention() = kotlinx.coroutines.runBlocking {
        val wifiNet = createMockNetwork(263)
        var probeInvoked = false

        DnsResilienceManager.resetStateSync()
        DnsResilienceManager.curfewOrVpnActiveChecker = { true }
        DnsResilienceManager.isWifiValidatedChecker = { false }
        DnsResilienceManager.internetProbeOverride = {
            probeInvoked = true
            true
        }

        DnsResilienceManager.evaluateWifiUnvalidatedOrDotErrorDirect(
            fakeContext,
            wifiNet,
            "test_curfew"
        )

        assertEquals(
            DnsResilienceManager.DnsResilienceMode.PRIMARY_STRICT_DOT,
            DnsResilienceManager.getActiveMode()
        )
        assertFalse("Probes must not run during active Curfew/VPN", probeInvoked)
        assertFalse(DnsResilienceManager.isLocalVpnFallbackActive())
    }

    @Test
    fun testDnsResilienceManager_escalatesFromStageAToStageB_whenWifiRemainsUnvalidated() = kotlinx.coroutines.runBlocking {
        val wifiNet = createMockNetwork(263)
        val wifiId = DnsResilienceManager.resolveNetworkId(wifiNet)
        var delayGracePeriodInvoked = false
        var level3Activated = false

        DnsResilienceManager.resetStateSync()
        DnsResilienceManager.policyHostProvider = { "family.cloudflare-dns.com" }
        DnsResilienceManager.curfewOrVpnActiveChecker = { false }
        DnsResilienceManager.isWifiValidatedChecker = { false }
        DnsResilienceManager.internetProbeOverride = { true }
        DnsResilienceManager.applyDnsShieldOverride = { }

        // Primary DoT fails, alternate DoT (Level 2) connects
        DnsResilienceManager.dotProbeOverride = { _, host ->
            host != "family.cloudflare-dns.com"
        }

        // Simulate 6-second wait under Stage A
        DnsResilienceManager.delayOverride = {
            delayGracePeriodInvoked = true
        }

        // Stage B mocks
        DnsResilienceManager.isDeviceOwnerChecker = { true }
        DnsResilienceManager.binaryFilterInitOverride = { true }
        DnsResilienceManager.level3ActivationOverride = { _, _ ->
            level3Activated = true
            true
        }

        DnsResilienceManager.evaluateWifiUnvalidatedOrDotErrorDirect(
            fakeContext,
            wifiNet,
            "test_stage_escalation"
        )

        assertTrue("6-second grace period in Stage A must be evaluated", delayGracePeriodInvoked)
        assertTrue("Level 3 must be activated when Wi-Fi remains unvalidated", level3Activated)
        assertEquals(
            DnsResilienceManager.DnsResilienceMode.FALLBACK_LOCAL_VPN_DOH,
            DnsResilienceManager.getActiveMode()
        )
        assertTrue(DnsResilienceManager.isLocalVpnFallbackActive())
        assertEquals(wifiId, DnsResilienceManager.getProtectedWifiNetworkId())
    }

    @Test
    fun testDnsResilienceManager_nothingOsQuirk_skipsStageA_escalatesDirectlyToLevel3() = kotlinx.coroutines.runBlocking {
        val wifiNet = createMockNetwork(263)
        var stageADelayInvoked = false
        var level3Activated = false

        DnsResilienceManager.resetStateSync()
        DnsResilienceManager.policyHostProvider = { "family.cloudflare-dns.com" }
        DnsResilienceManager.curfewOrVpnActiveChecker = { false }
        DnsResilienceManager.isWifiValidatedChecker = { false } // OS reports unvalidated
        DnsResilienceManager.internetProbeOverride = { true }
        DnsResilienceManager.dotProbeOverride = { _, _ -> true } // Java DoT connects to primary!

        DnsResilienceManager.delayOverride = { ms ->
            if (ms == 6_000L) {
                stageADelayInvoked = true
            }
        }

        DnsResilienceManager.isDeviceOwnerChecker = { true }
        DnsResilienceManager.binaryFilterInitOverride = { true }
        DnsResilienceManager.level3ActivationOverride = { _, _ ->
            level3Activated = true
            true
        }

        DnsResilienceManager.evaluateWifiUnvalidatedOrDotErrorDirect(
            fakeContext,
            wifiNet,
            "test_nothing_os_quirk"
        )

        assertFalse("Stage A must be skipped when primary DoT works on Java but OS is unvalidated", stageADelayInvoked)
        assertTrue("Level 3 must be activated immediately", level3Activated)
        assertEquals(
            DnsResilienceManager.DnsResilienceMode.FALLBACK_LOCAL_VPN_DOH,
            DnsResilienceManager.getActiveMode()
        )
        assertTrue(DnsResilienceManager.isLocalVpnFallbackActive())
    }

    @Test
    fun testDnsResilienceManager_invariant1_abortsEscalationIfBinaryFilterFails() = kotlinx.coroutines.runBlocking {
        val wifiNet = createMockNetwork(263)
        var level3Activated = false

        DnsResilienceManager.resetStateSync()
        DnsResilienceManager.curfewOrVpnActiveChecker = { false }
        DnsResilienceManager.isWifiValidatedChecker = { false }
        DnsResilienceManager.internetProbeOverride = { true }
        DnsResilienceManager.dotProbeOverride = { _, _ -> false } // All DoT fails
        DnsResilienceManager.isDeviceOwnerChecker = { true }
        DnsResilienceManager.binaryFilterInitOverride = { false } // Filter fails to init!
        DnsResilienceManager.level3ActivationOverride = { _, _ ->
            level3Activated = true
            true
        }

        DnsResilienceManager.evaluateWifiUnvalidatedOrDotErrorDirect(
            fakeContext,
            wifiNet,
            "test_invariant1"
        )

        assertFalse("Level 3 MUST NOT activate if BinaryDomainFilter failed (Invariant #1)", level3Activated)
        assertEquals(
            DnsResilienceManager.DnsResilienceMode.PRIMARY_STRICT_DOT,
            DnsResilienceManager.getActiveMode()
        )
        assertFalse(DnsResilienceManager.isLocalVpnFallbackActive())
    }

    @Test
    fun testDnsResilienceManager_dpmHostNotServing_doesNotSkipWhenInitialValidated() = kotlinx.coroutines.runBlocking {
        val wifiNet = createMockNetwork(263)
        var level3Activated = false

        DnsResilienceManager.resetStateSync()
        DnsResilienceManager.policyHostProvider = { "family.cloudflare-dns.com" }
        DnsResilienceManager.curfewOrVpnActiveChecker = { false }
        DnsResilienceManager.isWifiValidatedChecker = { true } // Initial validated is TRUE!
        DnsResilienceManager.internetProbeOverride = { true }
        DnsResilienceManager.dotProbeOverride = { _, _ -> false } // Primary and alternates fail
        DnsResilienceManager.isDeviceOwnerChecker = { true }
        DnsResilienceManager.binaryFilterInitOverride = { true }
        DnsResilienceManager.level3ActivationOverride = { _, _ ->
            level3Activated = true
            true
        }

        DnsResilienceManager.evaluateWifiUnvalidatedOrDotErrorDirect(
            fakeContext,
            wifiNet,
            "dpm_host_not_serving:family.cloudflare-dns.com"
        )

        assertTrue(
            "dpm_host_not_serving must NOT early-return on initialValidated, must proceed to Level 3",
            level3Activated
        )
        assertEquals(
            DnsResilienceManager.DnsResilienceMode.FALLBACK_LOCAL_VPN_DOH,
            DnsResilienceManager.getActiveMode()
        )
    }

    @Test
    fun testDnsResilienceManager_osQuirk_validatesDuringGracePeriod_staysOnPrimary() = kotlinx.coroutines.runBlocking {
        val wifiNet = createMockNetwork(263)
        var validatedChecks = 0
        var level3Activated = false

        DnsResilienceManager.resetStateSync()
        DnsResilienceManager.policyHostProvider = { "family.cloudflare-dns.com" }
        DnsResilienceManager.curfewOrVpnActiveChecker = { false }
        // First check false, during grace period check true!
        DnsResilienceManager.isWifiValidatedChecker = {
            validatedChecks++
            validatedChecks > 2
        }
        DnsResilienceManager.internetProbeOverride = { true }
        DnsResilienceManager.dotProbeOverride = { _, _ -> true }
        DnsResilienceManager.delayOverride = { } // Instant grace delay
        DnsResilienceManager.isDeviceOwnerChecker = { true }
        DnsResilienceManager.binaryFilterInitOverride = { true }
        DnsResilienceManager.level3ActivationOverride = { _, _ ->
            level3Activated = true
            true
        }

        DnsResilienceManager.evaluateWifiUnvalidatedOrDotErrorDirect(
            fakeContext,
            wifiNet,
            "test_os_quirk_grace"
        )

        assertFalse("Must not escalate to Level 3 if network validates during grace", level3Activated)
        assertEquals(
            DnsResilienceManager.DnsResilienceMode.PRIMARY_STRICT_DOT,
            DnsResilienceManager.getActiveMode()
        )
    }

    @Test
    fun testDnsResilienceManager_sameNetworkPrimaryRecovery_succeedsAndRestores() = kotlinx.coroutines.runBlocking {
        val wifiNet = createMockNetwork(263)
        val wifiId = DnsResilienceManager.resolveNetworkId(wifiNet)

        com.drmohammed.antiporn.core.engine.ProtectionTransitionGuard.resetForTest()
        DnsResilienceManager.resetStateSync()
        DnsResilienceNetworkRouter.reset()

        DnsResilienceManager.setFallbackStateForTest(
            mode = DnsResilienceManager.DnsResilienceMode.FALLBACK_LOCAL_VPN_DOH,
            netId = wifiId
        )

        var probeCalled = false
        DnsResilienceManager.dotProbeOverride = { _, host ->
            probeCalled = true
            host == "family.cloudflare-dns.com"
        }

        // Fast recovery execution
        DnsResilienceManager.recoveryDelayOverride = { }

        DnsResilienceManager.scheduleSameNetworkPrimaryRecovery(
            context = fakeContext,
            network = wifiNet,
            netId = wifiId,
            targetPolicyHost = "family.cloudflare-dns.com",
            triggerReason = "test_recovery"
        )

        val job = DnsResilienceNetworkRouter.activeRecoveryJob
        job?.join()

        assertTrue("Recovery probe must have been executed", probeCalled)
        assertEquals(
            DnsResilienceManager.DnsResilienceMode.PRIMARY_STRICT_DOT,
            DnsResilienceManager.getActiveMode()
        )
        assertNull(DnsResilienceManager.getProtectedWifiNetworkId())
        assertEquals(1, DnsResilienceNetworkRouter.recoveryAttemptCountByNet[wifiId])
    }

    @Test
    fun testDnsResilienceManager_sameNetworkRecovery_stopsAfterMaxAttempts() = kotlinx.coroutines.runBlocking {
        val wifiNet = createMockNetwork(263)
        val wifiId = DnsResilienceManager.resolveNetworkId(wifiNet)

        com.drmohammed.antiporn.core.engine.ProtectionTransitionGuard.resetForTest()
        DnsResilienceManager.resetStateSync()
        DnsResilienceNetworkRouter.reset()
        DnsResilienceNetworkRouter.recoveryAttemptCountByNet[wifiId] = DnsResilienceNetworkRouter.MAX_SAME_NET_RECOVERY_ATTEMPTS

        DnsResilienceManager.setFallbackStateForTest(
            mode = DnsResilienceManager.DnsResilienceMode.FALLBACK_LOCAL_VPN_DOH,
            netId = wifiId
        )

        DnsResilienceManager.scheduleSameNetworkPrimaryRecovery(
            context = fakeContext,
            network = wifiNet,
            netId = wifiId,
            targetPolicyHost = "family.cloudflare-dns.com",
            triggerReason = "test_max_recovery"
        )

        assertNull("Job must not be scheduled when max attempts reached", DnsResilienceNetworkRouter.activeRecoveryJob)
        assertEquals(
            DnsResilienceManager.DnsResilienceMode.FALLBACK_LOCAL_VPN_DOH,
            DnsResilienceManager.getActiveMode()
        )
    }
}
