package com.drmohammed.antiporn.core.network.dns

import android.content.Context
import android.content.SharedPreferences
import android.net.Network
import com.drmohammed.antiporn.core.model.DevicePolicyBundle
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.Inet6Address
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class DnsResilienceManagerTest {

    private val fakeContext = object : android.content.ContextWrapper(null) {
        override fun getApplicationContext(): Context = this
        override fun getSystemService(name: String): Any? = null
    }

    private fun createPrefsContext(prefsMap: MutableMap<String, InMemorySharedPreferences> = mutableMapOf()): Context {
        return object : android.content.ContextWrapper(null) {
            override fun getApplicationContext(): Context = this
            override fun getSystemService(name: String): Any? = null
            override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences {
                return prefsMap.getOrPut(name ?: "default") { InMemorySharedPreferences() }
            }
        }
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

    @After
    fun tearDown() {
        DnsResilienceManager.resetStateSync()
        com.drmohammed.antiporn.core.engine.ProtectionTransitionGuard.resetForTest()
    }

    @Test
    fun testDnsResilienceManager_initialStateIsPrimaryStrictDot() {
        assertEquals(
            DnsResilienceManager.DnsResilienceMode.PRIMARY_STRICT_DOT,
            DnsResilienceManager.getActiveMode()
        )
        assertFalse(DnsResilienceManager.isLocalVpnFallbackActive())
        assertNull(DnsResilienceManager.getProtectedNetworkId())
        assertEquals(
            "family.cloudflare-dns.com",
            DnsResilienceManager.getEffectiveRuntimeDnsHost("family.cloudflare-dns.com")
        )
    }

    @Test
    fun testDnsResilienceManager_antiFlapping_cellularLostPreservesProtection() {
        val wifiNet = createMockNetwork(263)
        val cellNet = createMockNetwork(260)

        val wifiId = DnsResilienceManager.resolveNetworkId(wifiNet)
        DnsResilienceManager.setStateForTest(
            mode = DnsResilienceManager.DnsResilienceMode.FALLBACK_LOCAL_VPN_DOH,
            netId = wifiId,
            transport = "WIFI"
        )

        assertTrue(DnsResilienceManager.isLocalVpnFallbackActive())
        assertEquals(wifiId, DnsResilienceManager.getProtectedNetworkId())

        // Cellular disconnects (e.g. user turned off mobile data or SIM roaming dropped)
        // Must NOT flap or revert Wi-Fi fallback!
        DnsResilienceManager.onNetworkChanged(
            fakeContext,
            "net_lost:CELLULAR",
            cellNet,
            "CELLULAR"
        )
        assertTrue("Cellular disconnect must not flap Wi-Fi resilience", DnsResilienceManager.isLocalVpnFallbackActive())
        assertEquals(wifiId, DnsResilienceManager.getProtectedNetworkId())

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
        assertNull(DnsResilienceManager.getProtectedNetworkId())
    }

    @Test
    fun testDnsResilienceManager_policyHostChanged_resetsState() {
        val wifiNet = createMockNetwork(263)
        val wifiId = DnsResilienceManager.resolveNetworkId(wifiNet)
        DnsResilienceManager.setStateForTest(
            mode = DnsResilienceManager.DnsResilienceMode.FALLBACK_LOCAL_VPN_DOH,
            netId = wifiId,
            transport = "WIFI"
        )

        DnsResilienceManager.onPolicyDnsHostChanged(fakeContext)

        assertEquals(
            DnsResilienceManager.DnsResilienceMode.PRIMARY_STRICT_DOT,
            DnsResilienceManager.getActiveMode()
        )
        assertNull(DnsResilienceManager.getProtectedNetworkId())
    }

    @Test
    fun testDnsResilienceManager_resetStateSync_immediatelyRestoresState() {
        val wifiNet = createMockNetwork(263)
        val wifiId = DnsResilienceManager.resolveNetworkId(wifiNet)
        DnsResilienceManager.setStateForTest(
            mode = DnsResilienceManager.DnsResilienceMode.FALLBACK_LOCAL_VPN_DOH,
            netId = wifiId,
            transport = "WIFI"
        )

        assertTrue(DnsResilienceManager.isLocalVpnFallbackActive())
        assertEquals(wifiId, DnsResilienceManager.getProtectedNetworkId())

        DnsResilienceManager.resetStateSync()

        assertEquals(
            DnsResilienceManager.DnsResilienceMode.PRIMARY_STRICT_DOT,
            DnsResilienceManager.getActiveMode()
        )
        assertFalse(DnsResilienceManager.isLocalVpnFallbackActive())
        assertNull(DnsResilienceManager.getProtectedNetworkId())
    }

    @Test
    fun testDnsResilienceManager_getEffectiveRuntimeDnsHost_returnsFallbackWhenInLevel2() {
        DnsResilienceManager.setStateForTest(
            mode = DnsResilienceManager.DnsResilienceMode.FALLBACK_STRICT_DOT,
            overrideHost = "family-filter-dns.cleanbrowsing.org"
        )

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
        DnsResilienceManager.localVpnFallbackEnabledChecker = { true }
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
        assertEquals(wifiId, DnsResilienceManager.getProtectedNetworkId())
    }

    @Test
    fun testDnsResilienceManager_osQuirk_triesStageAFirst_andEscalatesOnlyIfStageAFails() = kotlinx.coroutines.runBlocking {
        val wifiNet = createMockNetwork(263)
        var stageADelayInvoked = false
        var level3Activated = false

        DnsResilienceManager.resetStateSync()
        DnsResilienceManager.policyHostProvider = { "family.cloudflare-dns.com" }
        DnsResilienceManager.curfewOrVpnActiveChecker = { false }
        DnsResilienceManager.isWifiValidatedChecker = { false } // OS reports unvalidated across all candidates
        DnsResilienceManager.internetProbeOverride = { true }
        DnsResilienceManager.dotProbeOverride = { _, _ -> true } // Java DoT connects to primary and alternates!
        DnsResilienceManager.applyDnsShieldOverride = { }

        DnsResilienceManager.delayOverride = { ms ->
            if (ms == 6_000L) {
                stageADelayInvoked = true
            }
        }

        DnsResilienceManager.localVpnFallbackEnabledChecker = { true }
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

        assertTrue("Stage A alternate DoT must be attempted even on OS_DOT_VALIDATION_QUIRK", stageADelayInvoked)
        assertTrue("Level 3 must activate after Stage A candidates fail OS validation", level3Activated)
        assertEquals(
            DnsResilienceManager.DnsResilienceMode.FALLBACK_LOCAL_VPN_DOH,
            DnsResilienceManager.getActiveMode()
        )
        assertEquals(
            DnsResilienceManager.FailoverTriggerClass.OS_DOT_VALIDATION_QUIRK,
            DnsResilienceManager.getFailoverTriggerClass()
        )
        assertNull(
            "OS_DOT_VALIDATION_QUIRK Level 3 must NOT schedule same-network recovery",
            DnsResilienceNetworkRouter.activeRecoveryJob
        )
        assertTrue(DnsResilienceManager.isLocalVpnFallbackActive())
    }

    @Test
    fun testDnsResilienceManager_invariant1_abortsEscalationIfBinaryFilterFails() = kotlinx.coroutines.runBlocking {
        val wifiNet = createMockNetwork(263)
        var level3Activated = false
        var binaryFilterChecked = false

        DnsResilienceManager.resetStateSync()
        DnsResilienceManager.curfewOrVpnActiveChecker = { false }
        DnsResilienceManager.isWifiValidatedChecker = { false }
        DnsResilienceManager.internetProbeOverride = { true }
        DnsResilienceManager.dotProbeOverride = { _, _ -> false } // All DoT fails
        DnsResilienceManager.localVpnFallbackEnabledChecker = { true }
        DnsResilienceManager.isDeviceOwnerChecker = { true }
        DnsResilienceManager.binaryFilterInitOverride = {
            binaryFilterChecked = true
            false // Filter fails to init!
        }
        DnsResilienceManager.level3ActivationOverride = { _, _ ->
            level3Activated = true
            true
        }

        DnsResilienceManager.evaluateWifiUnvalidatedOrDotErrorDirect(
            fakeContext,
            wifiNet,
            "test_invariant1"
        )

        assertTrue("BinaryDomainFilter check must execute before aborting", binaryFilterChecked)
        assertFalse("Level 3 MUST NOT activate if BinaryDomainFilter failed (Invariant #1)", level3Activated)
        assertEquals(
            DnsResilienceManager.DnsResilienceMode.PRIMARY_STRICT_DOT,
            DnsResilienceManager.getActiveMode()
        )
        assertFalse(DnsResilienceManager.isLocalVpnFallbackActive())
    }

    @Test
    fun testCellularAndEthernetUnvalidated_triggerFailoverWhenPrimaryPhysical() = kotlinx.coroutines.runBlocking {
        for (transport in listOf("CELLULAR", "ETHERNET")) {
            val net = createMockNetwork(if (transport == "CELLULAR") 340 else 341)
            val netId = DnsResilienceManager.resolveNetworkId(net)
            var level3Activated = false

            DnsResilienceManager.resetStateSync()
            DnsResilienceManager.curfewOrVpnActiveChecker = { false }
            DnsResilienceManager.isNetworkValidatedChecker = { false }
            DnsResilienceManager.physicalTransportProvider = { _, _ -> transport }
            DnsResilienceManager.activeNetworkProvider = { net }
            DnsResilienceManager.allPhysicalNetworksProvider = { listOf(net) }
            DnsResilienceManager.internetProbeOverride = { true }
            DnsResilienceManager.dotProbeOverride = { _, _ -> false }
            DnsResilienceManager.localVpnFallbackEnabledChecker = { true }
            DnsResilienceManager.isDeviceOwnerChecker = { true }
            DnsResilienceManager.binaryFilterInitOverride = { true }
            DnsResilienceManager.level3ActivationOverride = { _, _ ->
                level3Activated = true
                true
            }

            DnsResilienceManager.evaluateNetworkUnvalidatedOrDotErrorDirect(
                fakeContext,
                net,
                transport,
                "${transport.lowercase()}_unvalidated_timeout_10s"
            )

            assertTrue("$transport must activate Level 3 failover", level3Activated)
            assertEquals(DnsResilienceManager.DnsResilienceMode.FALLBACK_LOCAL_VPN_DOH, DnsResilienceManager.getActiveMode())
            assertEquals(netId, DnsResilienceManager.getProtectedNetworkId())
            assertEquals(transport, DnsResilienceManager.getProtectedTransport())
            assertEquals(DnsResilienceManager.FailoverTriggerClass.PORT_853_BLOCKED, DnsResilienceManager.getFailoverTriggerClass())
        }
    }

    @Test
    fun testBackgroundCellularUnvalidated_ignoredWhileWifiValidatedDefault() = kotlinx.coroutines.runBlocking {
        val wifiNet = createMockNetwork(263)
        val cellNet = createMockNetwork(340)
        var probeInvoked = false

        DnsResilienceManager.resetStateSync()
        DnsResilienceManager.curfewOrVpnActiveChecker = { false }
        DnsResilienceManager.physicalTransportProvider = { _, net ->
            if (net === wifiNet) "WIFI" else "CELLULAR"
        }
        DnsResilienceManager.isNetworkValidatedChecker = { net -> net === wifiNet }
        DnsResilienceManager.activeNetworkProvider = { wifiNet }
        DnsResilienceManager.allPhysicalNetworksProvider = { listOf(wifiNet, cellNet) }
        DnsResilienceManager.internetProbeOverride = {
            probeInvoked = true
            true
        }

        DnsResilienceManager.evaluateNetworkUnvalidatedOrDotErrorDirect(
            fakeContext,
            cellNet,
            "CELLULAR",
            "cellular_unvalidated_timeout_10s"
        )

        assertFalse("Background Cellular must never probe or trigger failover while Wi-Fi is validated", probeInvoked)
        assertEquals(DnsResilienceManager.DnsResilienceMode.PRIMARY_STRICT_DOT, DnsResilienceManager.getActiveMode())
    }

    @Test
    fun testProtectedCellularFallback_handoffDecisionA3_restoresWhenWifiValidatedOrActiveDefault() {
        val cellNet = createMockNetwork(340)
        val cellId = DnsResilienceManager.resolveNetworkId(cellNet)
        val wifiNet = createMockNetwork(350)

        // Weak unvalidated non-default Wi-Fi must NOT tear down Cellular fallback early
        DnsResilienceManager.resetStateSync()
        DnsResilienceManager.setStateForTest(
            mode = DnsResilienceManager.DnsResilienceMode.FALLBACK_LOCAL_VPN_DOH,
            netId = cellId,
            transport = "CELLULAR"
        )
        DnsResilienceManager.physicalTransportProvider = { _, net ->
            if (net === wifiNet) "WIFI" else "CELLULAR"
        }
        DnsResilienceManager.activeNetworkProvider = { cellNet }

        DnsResilienceManager.onNetworkChanged(fakeContext, "net_available:WIFI", wifiNet, "WIFI")
        assertEquals(
            "Weak unvalidated non-default Wi-Fi must preserve Cellular fallback",
            DnsResilienceManager.DnsResilienceMode.FALLBACK_LOCAL_VPN_DOH,
            DnsResilienceManager.getActiveMode()
        )

        // When Wi-Fi becomes validated (NET_VALIDATED), Cellular fallback restores PRIMARY_STRICT_DOT
        DnsResilienceManager.onNetworkChanged(fakeContext, "net_validated:WIFI", wifiNet, "WIFI")
        assertEquals(DnsResilienceManager.DnsResilienceMode.PRIMARY_STRICT_DOT, DnsResilienceManager.getActiveMode())
        assertNull(DnsResilienceManager.getProtectedNetworkId())

        // Or when Wi-Fi becomes active default network (net_available as activeNetwork)
        DnsResilienceManager.resetStateSync()
        DnsResilienceManager.setStateForTest(
            mode = DnsResilienceManager.DnsResilienceMode.FALLBACK_LOCAL_VPN_DOH,
            netId = cellId,
            transport = "CELLULAR"
        )
        DnsResilienceManager.physicalTransportProvider = { _, net ->
            if (net === wifiNet) "WIFI" else "CELLULAR"
        }
        DnsResilienceManager.activeNetworkProvider = { wifiNet }
        DnsResilienceManager.onNetworkChanged(fakeContext, "net_available:WIFI", wifiNet, "WIFI")
        assertEquals(DnsResilienceManager.DnsResilienceMode.PRIMARY_STRICT_DOT, DnsResilienceManager.getActiveMode())
    }

    @Test
    fun testProcessDeathPersistence_hydratesAndReconcilesStartupCorrectly() = kotlinx.coroutines.runBlocking {
        val prefsMap = mutableMapOf<String, InMemorySharedPreferences>()
        val prefsCtx = createPrefsContext(prefsMap)
        val cellNet = createMockNetwork(340)
        val cellId = DnsResilienceManager.resolveNetworkId(cellNet)

        // Persist Level 3 state on Cellular net=340
        DnsResilienceManager.resetStateSync(prefsCtx)
        DnsResilienceManager.curfewOrVpnActiveChecker = { false }
        DnsResilienceManager.isNetworkValidatedChecker = { false }
        DnsResilienceManager.physicalTransportProvider = { _, _ -> "CELLULAR" }
        DnsResilienceManager.activeNetworkProvider = { cellNet }
        DnsResilienceManager.allPhysicalNetworksProvider = { listOf(cellNet) }
        DnsResilienceManager.internetProbeOverride = { true }
        DnsResilienceManager.dotProbeOverride = { _, _ -> true } // OS validation quirk -> Level 3 directly
        DnsResilienceManager.delayOverride = { }
        DnsResilienceManager.localVpnFallbackEnabledChecker = { true }
        DnsResilienceManager.isDeviceOwnerChecker = { true }
        DnsResilienceManager.binaryFilterInitOverride = { true }
        DnsResilienceManager.level3ActivationOverride = { _, _ -> true }

        DnsResilienceManager.evaluateNetworkUnvalidatedOrDotErrorDirect(prefsCtx, cellNet, "CELLULAR", "test_persist")
        assertEquals(DnsResilienceManager.DnsResilienceMode.FALLBACK_LOCAL_VPN_DOH, DnsResilienceManager.getActiveMode())

        // Simulate fresh process restart (clear in-memory only without clearing prefs!)
        val hydratedField = DnsResilienceManager.javaClass.getDeclaredField("isHydrated")
        hydratedField.isAccessible = true
        val modeField = DnsResilienceManager.javaClass.getDeclaredField("activeMode")
        modeField.isAccessible = true
        val netField = DnsResilienceManager.javaClass.getDeclaredField("protectedNetworkId")
        netField.isAccessible = true
        modeField.set(DnsResilienceManager, DnsResilienceManager.DnsResilienceMode.PRIMARY_STRICT_DOT)
        netField.set(DnsResilienceManager, null)
        hydratedField.setBoolean(DnsResilienceManager, false)

        // Case 1: Protected network 340 is still absent -> startup reconciliation restores PRIMARY_STRICT_DOT & wipes prefs
        DnsResilienceManager.allPhysicalNetworksProvider = { emptyList() }
        DnsResilienceManager.reconcilePersistedStateOnStartup(prefsCtx)
        assertEquals(DnsResilienceManager.DnsResilienceMode.PRIMARY_STRICT_DOT, DnsResilienceManager.getActiveMode())
        assertNull(DnsResilienceManager.getProtectedNetworkId())

        // Case 2: Re-persist and verify ensureHydrated restores state cleanly before VpnService binds
        val persistedPrefs = prefsCtx.getSharedPreferences("antiporn_dns_resilience_prefs", Context.MODE_PRIVATE)
        persistedPrefs.edit()
            .putString("active_mode", DnsResilienceManager.DnsResilienceMode.FALLBACK_LOCAL_VPN_DOH.name)
            .putString("protected_network_id", cellId)
            .putString("protected_transport", "CELLULAR")
            .putString("failover_trigger_class", DnsResilienceManager.FailoverTriggerClass.OS_DOT_VALIDATION_QUIRK.name)
            .commit()
        modeField.set(DnsResilienceManager, DnsResilienceManager.DnsResilienceMode.PRIMARY_STRICT_DOT)
        netField.set(DnsResilienceManager, null)
        hydratedField.setBoolean(DnsResilienceManager, false)

        DnsResilienceManager.ensureHydrated(prefsCtx)
        assertEquals(DnsResilienceManager.DnsResilienceMode.FALLBACK_LOCAL_VPN_DOH, DnsResilienceManager.getActiveMode())
        assertEquals(cellId, DnsResilienceManager.getProtectedNetworkId())
        assertEquals("CELLULAR", DnsResilienceManager.getProtectedTransport())
        assertEquals(DnsResilienceManager.FailoverTriggerClass.OS_DOT_VALIDATION_QUIRK, DnsResilienceManager.getFailoverTriggerClass())
    }

    @Test
    fun testExternalDnsResetWhileUnvalidated_firstDriftFastTracks_secondDriftEscalatesImmediately() {
        val cellNet = createMockNetwork(340)
        DnsResilienceManager.resetStateSync()
        DnsResilienceManager.curfewOrVpnActiveChecker = { false }
        DnsResilienceManager.physicalTransportProvider = { _, _ -> "CELLULAR" }
        DnsResilienceManager.isNetworkValidatedChecker = { false }
        DnsResilienceManager.activeNetworkProvider = { cellNet }
        DnsResilienceManager.allPhysicalNetworksProvider = { listOf(cellNet) }
        DnsResilienceManager.delayOverride = { kotlinx.coroutines.delay(5_000L) }
        DnsResilienceManager.internetProbeOverride = { false } // Prevent actual socket I/O in background coroutine

        val firstEscalated = DnsResilienceManager.onExternalDnsResetWhileUnvalidated(fakeContext, cellNet, "CELLULAR", 1)
        assertFalse("1st drift must fast-track (1.5s) and return escalated=false", firstEscalated)

        val secondEscalated = DnsResilienceManager.onExternalDnsResetWhileUnvalidated(fakeContext, cellNet, "CELLULAR", 2)
        assertTrue("2nd drift within 60s must bypass debounce immediately and return escalated=true", secondEscalated)
    }

    @Test
    fun testDualStackBootstrap_resolvesControlDNoAdsPornAndIpv6Presets() {
        val controlDDualStack = DnsResilienceManager.resolveBootstrapCandidates(
            host = DevicePolicyBundle.CONTROLD_NO_ADS_PORN_DNS_HOST,
            families = LinkIpFamilies(hasIpv4 = true, hasIpv6 = true)
        )
        assertTrue("ControlD no-ads-porn preset must have verified bootstrap IPs", controlDDualStack.size >= 2)
        assertEquals("76.76.2.11", controlDDualStack[0].hostAddress)
        assertTrue("Dual-stack ControlD must include IPv6 fallback", controlDDualStack.any { it is Inet6Address })

        val cloudflareV6Only = DnsResilienceManager.resolveBootstrapCandidates(
            host = DevicePolicyBundle.CLOUDFLARE_FAMILY_DNS_HOST,
            families = LinkIpFamilies(hasIpv4 = false, hasIpv6 = true)
        )
        assertTrue("IPv6-only Cloudflare must place IPv6 endpoint first", cloudflareV6Only.firstOrNull() is Inet6Address)
    }

    @Test
    fun testZeroBalanceSpoofedTcp443_tlsProbeFails_preservesPrimaryStrictDot() {
        val server = ServerSocket(0)
        val serverDone = CountDownLatch(1)
        val serverThread = Thread {
            try {
                server.use { srv ->
                    val sock = srv.accept()
                    sock.use {
                        val out = it.getOutputStream()
                        out.write("HTTP/1.1 302 Found\r\nLocation: http://zero-balance.carrier/\r\n\r\n".toByteArray())
                        out.flush()
                    }
                }
            } catch (_: Exception) {
            } finally {
                serverDone.countDown()
            }
        }
        serverThread.isDaemon = true
        serverThread.start()

        val probeOk = Socket("127.0.0.1", server.localPort).use { clientSock ->
            clientSock.soTimeout = 1_500
            DnsResilienceManager.verifyTls443OnSocket(clientSock, DevicePolicyBundle.CLOUDFLARE_FAMILY_DNS_HOST)
        }
        serverDone.await(2, TimeUnit.SECONDS)
        assertFalse("Plaintext HTTP 302 spoofed TCP 443 must fail TLS+SNI verification", probeOk)

        // End-to-end check on unvalidated Cellular when TLS 443 probe fails: stays PRIMARY_STRICT_DOT
        val cellNet = createMockNetwork(341)
        DnsResilienceManager.resetStateSync()
        DnsResilienceManager.curfewOrVpnActiveChecker = { false }
        DnsResilienceManager.physicalTransportProvider = { _, _ -> "CELLULAR" }
        DnsResilienceManager.isNetworkValidatedChecker = { false }
        DnsResilienceManager.isNetworkCaptivePortalChecker = { false }
        DnsResilienceManager.activeNetworkProvider = { cellNet }
        DnsResilienceManager.allPhysicalNetworksProvider = { listOf(cellNet) }
        DnsResilienceManager.internetProbeOverride = { false }

        var level3Called = false
        DnsResilienceManager.level3ActivationOverride = { _, _ ->
            level3Called = true
            true
        }

        kotlinx.coroutines.runBlocking {
            DnsResilienceManager.evaluateNetworkUnvalidatedOrDotErrorDirect(
                context = fakeContext,
                network = cellNet,
                transport = "CELLULAR",
                triggerReason = "net_unvalidated"
            )
        }
        assertFalse("Failed TLS 443 probe must NOT trigger Level 3 VPN fallback", level3Called)
        assertEquals(DnsResilienceManager.DnsResilienceMode.PRIMARY_STRICT_DOT, DnsResilienceManager.getActiveMode())
    }

    @Test
    fun testDotProbe_requiresValidRfc7858WireResponse_rejectsServfailOrEof() {
        val server = ServerSocket(0)
        val serverDone = CountDownLatch(1)
        val worker = Thread {
            try {
                server.use { srv ->
                    // Connection 1: immediate EOF after reading request
                    srv.accept().use { s ->
                        val dis = DataInputStream(s.getInputStream())
                        val len = dis.readUnsignedShort()
                        dis.readFully(ByteArray(len))
                    }
                    // Connection 2: valid Question echo with RCODE=2 (SERVFAIL)
                    srv.accept().use { s ->
                        respondToFramedQuery(s, rcode = 2, flipTxId = false)
                    }
                    // Connection 3: NOERROR but mismatched TxID
                    srv.accept().use { s ->
                        respondToFramedQuery(s, rcode = 0, flipTxId = true)
                    }
                    // Connection 4: valid NOERROR matching TxID + Question
                    srv.accept().use { s ->
                        respondToFramedQuery(s, rcode = 0, flipTxId = false)
                    }
                }
            } catch (_: Exception) {
            } finally {
                serverDone.countDown()
            }
        }
        worker.isDaemon = true
        worker.start()

        val eofOk = Socket("127.0.0.1", server.localPort).use { sock ->
            sock.soTimeout = 1_500
            DnsResilienceManager.exchangeRfc7858ProbeOnSocket(sock)
        }
        assertFalse("Immediate EOF on DoT socket must fail wire probe", eofOk)

        val servfailOk = Socket("127.0.0.1", server.localPort).use { sock ->
            sock.soTimeout = 1_500
            DnsResilienceManager.exchangeRfc7858ProbeOnSocket(sock)
        }
        assertFalse("SERVFAIL response on DoT socket must fail wire probe", servfailOk)

        val wrongTxIdOk = Socket("127.0.0.1", server.localPort).use { sock ->
            sock.soTimeout = 1_500
            DnsResilienceManager.exchangeRfc7858ProbeOnSocket(sock)
        }
        assertFalse("Mismatched TxID response on DoT socket must fail wire probe", wrongTxIdOk)

        val validOk = Socket("127.0.0.1", server.localPort).use { sock ->
            sock.soTimeout = 1_500
            DnsResilienceManager.exchangeRfc7858ProbeOnSocket(sock)
        }
        assertTrue("Valid RFC 7858 NOERROR matching TxID + Question must pass wire probe", validOk)
        serverDone.await(2, TimeUnit.SECONDS)
    }

    @Test
    fun testStageAGracePeriodRace_networkLostDuringDelay_abortsStageBEscalation() = kotlinx.coroutines.runBlocking {
        val wifiNet = createMockNetwork(263)
        var level3Called = false

        DnsResilienceManager.resetStateSync()
        DnsResilienceManager.policyHostProvider = { "family.cloudflare-dns.com" }
        DnsResilienceManager.curfewOrVpnActiveChecker = { false }
        DnsResilienceManager.physicalTransportProvider = { _, _ -> "WIFI" }
        DnsResilienceManager.isNetworkValidatedChecker = { false }
        DnsResilienceManager.activeNetworkProvider = { wifiNet }
        DnsResilienceManager.allPhysicalNetworksProvider = { listOf(wifiNet) }
        DnsResilienceManager.internetProbeOverride = { true }
        DnsResilienceManager.applyDnsShieldOverride = { }
        DnsResilienceManager.dotProbeOverride = { _, host -> host != "family.cloudflare-dns.com" }
        DnsResilienceManager.localVpnFallbackEnabledChecker = { true }
        DnsResilienceManager.isDeviceOwnerChecker = { true }
        DnsResilienceManager.binaryFilterInitOverride = { true }
        DnsResilienceManager.level3ActivationOverride = { _, _ ->
            level3Called = true
            true
        }

        // Simulate protected network dropping mid-way through the 6s Level 2 grace window
        DnsResilienceManager.delayOverride = {
            DnsResilienceManager.onNetworkChanged(fakeContext, "net_lost:WIFI", wifiNet, "WIFI")
        }

        DnsResilienceManager.evaluateNetworkUnvalidatedOrDotErrorDirect(fakeContext, wifiNet, "WIFI", "test_grace_race")

        assertFalse("Stage B Level 3 MUST NOT overwrite restored state after net_lost during 6s grace", level3Called)
        assertEquals(DnsResilienceManager.DnsResilienceMode.PRIMARY_STRICT_DOT, DnsResilienceManager.getActiveMode())
        assertNull(DnsResilienceManager.getProtectedNetworkId())
    }

    @Test
    fun testCaptivePortalImmediate_setsCaptiveTriggerWithoutStageA() = kotlinx.coroutines.runBlocking {
        val wifiNet = createMockNetwork(264)
        var stageADelayCalled = false
        var level3Called = false

        DnsResilienceManager.resetStateSync()
        DnsResilienceManager.curfewOrVpnActiveChecker = { false }
        DnsResilienceManager.physicalTransportProvider = { _, _ -> "WIFI" }
        DnsResilienceManager.isNetworkValidatedChecker = { false }
        DnsResilienceManager.isNetworkCaptivePortalChecker = { true }
        DnsResilienceManager.activeNetworkProvider = { wifiNet }
        DnsResilienceManager.allPhysicalNetworksProvider = { listOf(wifiNet) }
        DnsResilienceManager.delayOverride = { stageADelayCalled = true }
        DnsResilienceManager.localVpnFallbackEnabledChecker = { true }
        DnsResilienceManager.isDeviceOwnerChecker = { true }
        DnsResilienceManager.binaryFilterInitOverride = { true }
        DnsResilienceManager.level3ActivationOverride = { _, _ ->
            level3Called = true
            true
        }

        DnsResilienceManager.evaluateNetworkUnvalidatedOrDotErrorDirect(fakeContext, wifiNet, "WIFI", "wifi_captive_portal_immediate")

        assertFalse("Captive portal must bypass Stage A", stageADelayCalled)
        assertTrue(level3Called)
        assertEquals(DnsResilienceManager.FailoverTriggerClass.CAPTIVE_PORTAL, DnsResilienceManager.getFailoverTriggerClass())
        assertEquals(DnsResilienceManager.DnsResilienceMode.FALLBACK_LOCAL_VPN_DOH, DnsResilienceManager.getActiveMode())
    }

    @Test
    fun testProtectedWifiFallback_handoffToValidatedNewWifi_restoresPrimaryStrictDot() {
        val badWifi = createMockNetwork(265)
        val goodWifi = createMockNetwork(266)
        val badId = DnsResilienceManager.resolveNetworkId(badWifi)

        DnsResilienceManager.resetStateSync()
        DnsResilienceManager.setStateForTest(
            mode = DnsResilienceManager.DnsResilienceMode.FALLBACK_LOCAL_VPN_DOH,
            netId = badId,
            transport = "WIFI"
        )
        DnsResilienceManager.onNetworkChanged(fakeContext, "net_validated:WIFI", goodWifi, "WIFI")

        assertEquals(DnsResilienceManager.DnsResilienceMode.PRIMARY_STRICT_DOT, DnsResilienceManager.getActiveMode())
        assertNull(DnsResilienceManager.getProtectedNetworkId())
    }

    private fun respondToFramedQuery(socket: Socket, rcode: Int, flipTxId: Boolean) {
        val dis = DataInputStream(socket.getInputStream())
        val reqLen = dis.readUnsignedShort()
        val req = ByteArray(reqLen)
        dis.readFully(req)
        val rep = req.copyOf()
        if (flipTxId) {
            rep[0] = (rep[0].toInt() xor 0xFF).toByte()
        }
        rep[2] = ((rep[2].toInt() and 0x7F) or 0x81).toByte() // QR=1, OPCODE=0, RD=1
        rep[3] = (0x80 or (rcode and 0x0F)).toByte() // RA=1, RCODE
        val dos = DataOutputStream(socket.getOutputStream())
        dos.writeShort(rep.size)
        dos.write(rep)
        dos.flush()
    }

    private class InMemorySharedPreferences : SharedPreferences {
        private val values = mutableMapOf<String, Any?>()
        override fun getAll(): MutableMap<String, *> = values.toMutableMap()
        override fun getString(key: String?, defValue: String?): String? = values[key] as? String ?: defValue
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
            @Suppress("UNCHECKED_CAST") ((values[key] as? Set<String>)?.toMutableSet() ?: defValues)
        override fun getInt(key: String?, defValue: Int): Int = values[key] as? Int ?: defValue
        override fun getLong(key: String?, defValue: Long): Long = values[key] as? Long ?: defValue
        override fun getFloat(key: String?, defValue: Float): Float = values[key] as? Float ?: defValue
        override fun getBoolean(key: String?, defValue: Boolean): Boolean = values[key] as? Boolean ?: defValue
        override fun contains(key: String?): Boolean = values.containsKey(key)
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
            private val pending = mutableMapOf<String, Any?>()
            private var clearAll = false
            override fun putString(key: String?, value: String?) = apply { if (key != null) pending[key] = value }
            override fun putStringSet(key: String?, v: MutableSet<String>?) = apply { if (key != null) pending[key] = v?.toSet() }
            override fun putInt(key: String?, value: Int) = apply { if (key != null) pending[key] = value }
            override fun putLong(key: String?, value: Long) = apply { if (key != null) pending[key] = value }
            override fun putFloat(key: String?, value: Float) = apply { if (key != null) pending[key] = value }
            override fun putBoolean(key: String?, value: Boolean) = apply { if (key != null) pending[key] = value }
            override fun remove(key: String?) = apply { if (key != null) pending[key] = null }
            override fun clear() = apply { clearAll = true }
            override fun commit(): Boolean { apply(); return true }
            override fun apply() {
                if (clearAll) { values.clear(); clearAll = false }
                for ((k, v) in pending) { if (v == null) values.remove(k) else values[k] = v }
                pending.clear()
            }
        }
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
        DnsResilienceManager.localVpnFallbackEnabledChecker = { true }
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
        DnsResilienceManager.localVpnFallbackEnabledChecker = { true }
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

    @Test
    fun testOsDotValidationQuirk_triesStageAAdGuardBeforeLevel3_succeedsWithZeroVpn() = kotlinx.coroutines.runBlocking {
        val wifiNet = createMockNetwork(500)
        var level3Activated = false
        var adGuardApplied = false

        DnsResilienceManager.resetStateSync()
        DnsResilienceManager.policyHostProvider = { "family.cloudflare-dns.com" }
        DnsResilienceManager.curfewOrVpnActiveChecker = { false }
        DnsResilienceManager.internetProbeOverride = { true }
        DnsResilienceManager.dotProbeOverride = { _, _ -> true }
        DnsResilienceManager.localVpnFallbackEnabledChecker = { true }
        DnsResilienceManager.delayOverride = { }
        DnsResilienceManager.isWifiValidatedChecker = { adGuardApplied }
        DnsResilienceManager.applyDnsShieldOverride = {
            if (DnsResilienceManager.getEffectiveRuntimeDnsHost("family.cloudflare-dns.com") == DevicePolicyBundle.ADGUARD_FAMILY_DNS_HOST) {
                adGuardApplied = true
            }
        }
        DnsResilienceManager.level3ActivationOverride = { _, _ ->
            level3Activated = true
            true
        }

        DnsResilienceManager.evaluateWifiUnvalidatedOrDotErrorDirect(
            fakeContext,
            wifiNet,
            "test_os_quirk_stage_a_adguard"
        )

        assertTrue("Stage A must apply AdGuard Family DoT", adGuardApplied)
        assertFalse("Level 3 VPN must NOT activate when Stage A AdGuard validates", level3Activated)
        assertFalse(DnsResilienceManager.isLocalVpnFallbackActive())
        assertEquals(DnsResilienceManager.DnsResilienceMode.FALLBACK_STRICT_DOT, DnsResilienceManager.getActiveMode())
        assertEquals(
            DevicePolicyBundle.ADGUARD_FAMILY_DNS_HOST,
            DnsResilienceManager.getEffectiveRuntimeDnsHost("family.cloudflare-dns.com")
        )
    }

    @Test
    fun testStageAFails_localVpnFallbackDisabled_notifiesConsentStrictlyOncePerNetId() = kotlinx.coroutines.runBlocking {
        val wifiNet1 = createMockNetwork(501)
        val wifiNet2 = createMockNetwork(502)
        val netId1 = DnsResilienceManager.resolveNetworkId(wifiNet1)
        val netId2 = DnsResilienceManager.resolveNetworkId(wifiNet2)
        val notifiedNetIds = mutableListOf<String>()
        var level3Activated = false

        DnsResilienceManager.resetStateSync()
        DnsResilienceManager.policyHostProvider = { "family.cloudflare-dns.com" }
        DnsResilienceManager.curfewOrVpnActiveChecker = { false }
        DnsResilienceManager.isNetworkValidatedChecker = { false }
        DnsResilienceManager.internetProbeOverride = { true }
        DnsResilienceManager.dotProbeOverride = { _, _ -> false }
        DnsResilienceManager.localVpnFallbackEnabledChecker = { false }
        DnsResilienceManager.consentNotifierOverride = { _, id -> notifiedNetIds.add(id) }
        DnsResilienceManager.level3ActivationOverride = { _, _ ->
            level3Activated = true
            true
        }

        // First evaluation on netId1 -> notifies consent once, does not activate Level 3
        DnsResilienceManager.evaluateNetworkUnvalidatedOrDotErrorDirect(
            fakeContext,
            wifiNet1,
            "WIFI",
            "wifi_unvalidated_timeout_10s"
        )
        assertFalse(level3Activated)
        assertEquals(DnsResilienceManager.DnsResilienceMode.PRIMARY_STRICT_DOT, DnsResilienceManager.getActiveMode())
        assertTrue(DnsResilienceManager.isExhaustedDotOnNetwork(fakeContext, netId1))
        assertEquals(listOf(netId1), notifiedNetIds)

        // Repeat trigger on same netId1 -> suppressed, no duplicate notification
        DnsResilienceManager.evaluateNetworkUnvalidatedOrDotErrorDirect(
            fakeContext,
            wifiNet1,
            "WIFI",
            "dpm_host_not_serving:family.cloudflare-dns.com"
        )
        assertEquals(listOf(netId1), notifiedNetIds)

        // Losing netId1 clears exhausted/consent state; new network netId2 re-notifies once
        DnsResilienceManager.onNetworkChanged(fakeContext, "net_lost:WIFI", wifiNet1, "WIFI")
        assertFalse(DnsResilienceManager.isExhaustedDotOnNetwork(fakeContext, netId1))

        DnsResilienceManager.evaluateNetworkUnvalidatedOrDotErrorDirect(
            fakeContext,
            wifiNet2,
            "WIFI",
            "wifi_unvalidated_timeout_10s"
        )
        assertEquals(listOf(netId1, netId2), notifiedNetIds)
    }

    @Test
    fun testPort853Blocked_recoveryRequiresSystemDnsResolved_andLocksPrimaryBlockedOnRepeatFailure() = kotlinx.coroutines.runBlocking {
        val wifiNet = createMockNetwork(503)
        val wifiId = DnsResilienceManager.resolveNetworkId(wifiNet)

        // 1. Direct probe with requireSystemDnsResolution=true must reject when OS DNS cannot resolve hostname
        val probeWithoutSysDns = DnsResilienceProbeClient.probeDotHostDetailed(
            network = wifiNet,
            host = "family.cloudflare-dns.com",
            requireSystemDnsResolution = true
        )
        assertFalse("Probe must fail when system DNS cannot resolve host", probeWithoutSysDns.reachable)
        assertFalse(probeWithoutSysDns.systemDnsResolved)

        // 2. Same-network recovery with systemDnsResolved=false must NOT restore primary
        com.drmohammed.antiporn.core.engine.ProtectionTransitionGuard.resetForTest()
        DnsResilienceManager.resetStateSync()
        DnsResilienceNetworkRouter.reset()
        DnsResilienceManager.setFallbackStateForTest(
            mode = DnsResilienceManager.DnsResilienceMode.FALLBACK_LOCAL_VPN_DOH,
            netId = wifiId
        )
        DnsResilienceManager.recoveryDelayOverride = { }
        DnsResilienceManager.dotProbeDetailedOverride = { _, _, _ ->
            DnsResilienceProbeClient.DotProbeResult(
                reachable = true,
                systemDnsResolved = false,
                usedBootstrapFallback = true
            )
        }
        DnsResilienceManager.scheduleSameNetworkPrimaryRecovery(
            context = fakeContext,
            network = wifiNet,
            netId = wifiId,
            targetPolicyHost = "family.cloudflare-dns.com",
            triggerReason = "wifi_unvalidated_timeout_10s"
        )
        DnsResilienceNetworkRouter.activeRecoveryJob?.join()
        assertEquals(
            "Must NOT false-recover on bootstrap IP when systemDnsResolved=false",
            DnsResilienceManager.DnsResilienceMode.FALLBACK_LOCAL_VPN_DOH,
            DnsResilienceManager.getActiveMode()
        )

        // 3. When systemDnsResolved=true, recovery restores PRIMARY_STRICT_DOT; if it fails again on same netId, primaryBlockedOnNetIds locks!
        DnsResilienceManager.resetStateSync()
        DnsResilienceNetworkRouter.reset()
        DnsResilienceManager.setFallbackStateForTest(
            mode = DnsResilienceManager.DnsResilienceMode.FALLBACK_LOCAL_VPN_DOH,
            netId = wifiId
        )
        DnsResilienceManager.recoveryDelayOverride = { }
        DnsResilienceManager.dotProbeDetailedOverride = { _, _, _ ->
            DnsResilienceProbeClient.DotProbeResult(
                reachable = true,
                systemDnsResolved = true,
                usedBootstrapFallback = false
            )
        }
        DnsResilienceManager.scheduleSameNetworkPrimaryRecovery(
            context = fakeContext,
            network = wifiNet,
            netId = wifiId,
            targetPolicyHost = "family.cloudflare-dns.com",
            triggerReason = "wifi_unvalidated_timeout_10s"
        )
        DnsResilienceNetworkRouter.activeRecoveryJob?.join()
        assertEquals(DnsResilienceManager.DnsResilienceMode.PRIMARY_STRICT_DOT, DnsResilienceManager.getActiveMode())

        // Simulate immediate repeat failure on the same netId -> locks primaryBlockedOnNetIds and prevents repeat recovery loop
        DnsResilienceManager.curfewOrVpnActiveChecker = { false }
        DnsResilienceManager.isNetworkValidatedChecker = { false }
        DnsResilienceManager.physicalTransportProvider = { _, _ -> "WIFI" }
        DnsResilienceManager.activeNetworkProvider = { wifiNet }
        DnsResilienceManager.allPhysicalNetworksProvider = { listOf(wifiNet) }
        DnsResilienceManager.internetProbeOverride = { true }
        DnsResilienceManager.dotProbeOverride = { _, _ -> false }
        DnsResilienceManager.localVpnFallbackEnabledChecker = { true }
        DnsResilienceManager.isDeviceOwnerChecker = { true }
        DnsResilienceManager.binaryFilterInitOverride = { true }
        DnsResilienceManager.level3ActivationOverride = { _, _ -> true }
        DnsResilienceManager.recoveryDelayOverride = { }

        DnsResilienceManager.evaluateNetworkUnvalidatedOrDotErrorDirect(
            fakeContext,
            wifiNet,
            "WIFI",
            "dpm_host_not_serving:family.cloudflare-dns.com"
        )

        assertTrue(
            "Repeat failure after same-network recovery must lock primaryBlockedOnNetIds",
            DnsResilienceNetworkRouter.isPrimaryBlockedOnNetwork(wifiId)
        )
        assertNull(
            "Recovery must NOT be rescheduled once primaryBlockedOnNetIds is locked",
            DnsResilienceNetworkRouter.activeRecoveryJob
        )
    }

    @Test
    fun testExternalVpnCoexistenceRevoked_resetsLevel3WithoutForcingSettings() {
        val wifiNet = createMockNetwork(504)
        val wifiId = DnsResilienceManager.resolveNetworkId(wifiNet)
        DnsResilienceManager.resetStateSync()
        DnsResilienceManager.setStateForTest(
            mode = DnsResilienceManager.DnsResilienceMode.FALLBACK_LOCAL_VPN_DOH,
            netId = wifiId,
            transport = "WIFI"
        )

        DnsResilienceManager.onExternalVpnCoexistenceRevoked(fakeContext)

        assertEquals(DnsResilienceManager.DnsResilienceMode.PRIMARY_STRICT_DOT, DnsResilienceManager.getActiveMode())
        assertFalse(DnsResilienceManager.isRestoringPrimaryNow())
        assertNull(DnsResilienceManager.getProtectedNetworkId())
    }
}
