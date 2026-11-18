package com.drmohammed.antiporn.services

import com.drmohammed.antiporn.curfew.NetworkChangeCoalescer
import com.drmohammed.antiporn.curfew.PhysicalNetworkChangeTracker
import com.drmohammed.antiporn.services.NetworkWatchdog.Companion.NetworkStateSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Unit tests for [NetworkWatchdog], [PhysicalNetworkChangeTracker], and [NetworkChangeCoalescer].
 */
class NetworkWatchdogTest {

    // -------------------------------------------------------------------------
    // NetworkWatchdog.classifyCapabilityTransition
    // -------------------------------------------------------------------------

    @Test
    fun classifyCapabilityTransition_suppressesIdenticalSnapshots_rssiFlapping() {
        val wifiValidated = NetworkStateSnapshot(
            transport = "WIFI",
            isValidated = true,
            isCaptivePortal = false
        )
        val sameWifiValidated = NetworkStateSnapshot(
            transport = "WIFI",
            isValidated = true,
            isCaptivePortal = false
        )

        assertNull(
            "Initial non-captive snapshot should not emit a transition event",
            NetworkWatchdog.classifyCapabilityTransition(null, wifiValidated, "net100")
        )
        assertNull(
            "Identical capability snapshots (RSSI/bandwidth flapping) must be suppressed",
            NetworkWatchdog.classifyCapabilityTransition(wifiValidated, sameWifiValidated, "net100")
        )
    }

    @Test
    fun classifyCapabilityTransition_detectsValidatedUnvalidatedCaptiveAndTransportChanged() {
        val unvalidatedWifi = NetworkStateSnapshot(
            transport = "WIFI",
            isValidated = false,
            isCaptivePortal = false
        )
        val captiveWifi = NetworkStateSnapshot(
            transport = "WIFI",
            isValidated = false,
            isCaptivePortal = true
        )
        val validatedWifi = NetworkStateSnapshot(
            transport = "WIFI",
            isValidated = true,
            isCaptivePortal = false
        )
        val validatedCell = NetworkStateSnapshot(
            transport = "CELLULAR",
            isValidated = true,
            isCaptivePortal = false
        )
        val vpnSnapshot = NetworkStateSnapshot(
            transport = "VPN",
            isValidated = true,
            isCaptivePortal = false
        )

        val initialCaptive = requireNotNull(NetworkWatchdog.classifyCapabilityTransition(null, captiveWifi, "net1"))
        assertEquals("NET_CAPTIVE_PORTAL", initialCaptive.action)
        assertFalse(initialCaptive.isSuccess)

        val toCaptive = requireNotNull(NetworkWatchdog.classifyCapabilityTransition(unvalidatedWifi, captiveWifi, "net1"))
        assertEquals("NET_CAPTIVE_PORTAL", toCaptive.action)
        assertFalse(toCaptive.isSuccess)

        val toValidated = requireNotNull(NetworkWatchdog.classifyCapabilityTransition(unvalidatedWifi, validatedWifi, "net1"))
        assertEquals("NET_VALIDATED", toValidated.action)
        assertTrue(toValidated.isSuccess)
        assertNull(toValidated.error)

        val toUnvalidated = requireNotNull(NetworkWatchdog.classifyCapabilityTransition(validatedWifi, unvalidatedWifi, "net1"))
        assertEquals("NET_UNVALIDATED", toUnvalidated.action)
        assertFalse(toUnvalidated.isSuccess)
        requireNotNull(toUnvalidated.error)

        val transportChanged = requireNotNull(NetworkWatchdog.classifyCapabilityTransition(validatedWifi, validatedCell, "net2"))
        assertEquals("NET_TRANSPORT_CHANGED", transportChanged.action)
        assertTrue(transportChanged.isSuccess)
        assertTrue(requireNotNull(transportChanged.details).contains("WIFI->CELLULAR"))

        val toVpn = requireNotNull(NetworkWatchdog.classifyCapabilityTransition(validatedCell, vpnSnapshot, "net3"))
        assertEquals("NET_TRANSPORT_CHANGED", toVpn.action)
        assertTrue(requireNotNull(toVpn.details).contains("CELLULAR->VPN"))
    }

    // -------------------------------------------------------------------------
    // NetworkWatchdog.buildLinkSignature
    // -------------------------------------------------------------------------

    @Test
    fun buildLinkSignature_handlesNullAndSortsAddressesAndDnsDeterministically() {
        assertEquals("", NetworkWatchdog.buildLinkSignature(null))

        val sigA = NetworkWatchdog.buildLinkSignature(
            interfaceName = "wlan0",
            linkAddresses = listOf("192.168.1.50", "fe80::1"),
            dnsServers = listOf("1.0.0.3", "1.1.1.3")
        )
        val sigB = NetworkWatchdog.buildLinkSignature(
            interfaceName = "wlan0",
            linkAddresses = listOf("fe80::1", "192.168.1.50"),
            dnsServers = listOf("1.1.1.3", "1.0.0.3")
        )
        val sigRoamed = NetworkWatchdog.buildLinkSignature(
            interfaceName = "wlan0",
            linkAddresses = listOf("10.20.30.40", "fe80::1"),
            dnsServers = listOf("1.1.1.3", "1.0.0.3")
        )

        assertEquals("wlan0|192.168.1.50,fe80::1|1.0.0.3,1.1.1.3", sigA)
        assertEquals("Reordered addresses/DNS must yield identical deterministic signature", sigA, sigB)
        assertTrue(
            "Same-interface DHCP/IP roaming must alter link signature even if DNS servers stay identical",
            sigA != sigRoamed
        )
    }

    // -------------------------------------------------------------------------
    // PhysicalNetworkChangeTracker
    // -------------------------------------------------------------------------

    @Test
    fun tracker_ignoresRssiOnlyIdenticalCapabilityUpdates() {
        val tracker = PhysicalNetworkChangeTracker()

        val initial = tracker.onNetworkObserved(
            networkId = "net-wifi-1",
            transport = "WIFI",
            isValidated = true
        )
        assertEquals("physical_net_available:WIFI", initial)

        repeat(5) {
            assertNull(
                "RSSI/bandwidth-only capability callback must not trigger invalidation",
                tracker.onNetworkObserved(
                    networkId = "net-wifi-1",
                    transport = "WIFI",
                    isValidated = true
                )
            )
        }
    }

    @Test
    fun tracker_triggersOnWifiToCellularNewPhysicalNetworkTransition() {
        val tracker = PhysicalNetworkChangeTracker()

        assertEquals(
            "physical_net_available:WIFI",
            tracker.onNetworkObserved("net-wifi-1", transport = "WIFI", isValidated = true)
        )
        assertEquals(
            "physical_net_available:CELLULAR",
            tracker.onNetworkObserved("net-cell-2", transport = "CELLULAR", isValidated = true)
        )
        assertNull(
            tracker.onNetworkObserved("net-cell-2", transport = "CELLULAR", isValidated = true)
        )
        assertEquals(
            "physical_net_lost:WIFI",
            tracker.onNetworkLost("net-wifi-1")
        )
    }

    @Test
    fun tracker_triggersOnTransportChangeForSameNetworkHandle() {
        val tracker = PhysicalNetworkChangeTracker()

        assertEquals(
            "physical_net_available:WIFI",
            tracker.onNetworkObserved("net-10", transport = "WIFI", isValidated = true)
        )
        assertEquals(
            "physical_net_transport_changed:WIFI->CELLULAR",
            tracker.onNetworkObserved("net-10", transport = "CELLULAR", isValidated = true)
        )
    }

    @Test
    fun tracker_triggersOnLostOfTrackedPhysicalNetworkAndIgnoresDuplicateLost() {
        val tracker = PhysicalNetworkChangeTracker()

        assertEquals(
            "physical_net_available:WIFI",
            tracker.onNetworkObserved("net-wifi-1", transport = "WIFI", isValidated = true)
        )
        assertEquals(
            "physical_net_lost:WIFI",
            tracker.onNetworkLost("net-wifi-1")
        )
        assertNull(
            "Duplicate onLost for already-removed network must be ignored",
            tracker.onNetworkLost("net-wifi-1")
        )
    }

    @Test
    fun tracker_triggersOnValidationRecovery_captivePortalClearance() {
        val tracker = PhysicalNetworkChangeTracker()

        assertEquals(
            "physical_net_available:WIFI",
            tracker.onNetworkObserved("net-captive", transport = "WIFI", isValidated = false)
        )
        assertNull(
            tracker.onNetworkObserved("net-captive", transport = "WIFI", isValidated = false)
        )
        assertEquals(
            "physical_net_validated:WIFI",
            tracker.onNetworkObserved("net-captive", transport = "WIFI", isValidated = true)
        )
        assertNull(
            tracker.onNetworkObserved("net-captive", transport = "WIFI", isValidated = true)
        )
        assertEquals(
            "Degradation from validated -> unvalidated must emit flush reason to clear dead-WiFi pooled sockets",
            "physical_net_unvalidated:WIFI",
            tracker.onNetworkObserved("net-captive", transport = "WIFI", isValidated = false)
        )
        assertEquals(
            "physical_net_validated:WIFI",
            tracker.onNetworkObserved("net-captive", transport = "WIFI", isValidated = true)
        )
    }

    @Test
    fun tracker_triggersOnLinkSignatureChangeAndSuppressesIdenticalSignature() {
        val tracker = PhysicalNetworkChangeTracker()

        assertEquals(
            "physical_net_available:WIFI",
            tracker.onNetworkObserved("net-wifi-1", transport = "WIFI", isValidated = true)
        )

        assertNull(
            "Initial link signature establishes baseline without extra flush",
            tracker.onLinkPropertiesChanged("net-wifi-1", "wlan0|192.168.1.5|192.168.1.1")
        )
        assertNull(
            "Identical link signature must be suppressed",
            tracker.onLinkPropertiesChanged("net-wifi-1", "wlan0|192.168.1.5|192.168.1.1")
        )
        assertEquals(
            "physical_net_link_changed:WIFI",
            tracker.onLinkPropertiesChanged("net-wifi-1", "wlan0|10.0.0.5|10.0.0.1,10.0.0.2")
        )
        assertNull(
            tracker.onLinkPropertiesChanged("net-wifi-1", "wlan0|10.0.0.5|10.0.0.1,10.0.0.2")
        )
    }

    @Test
    fun tracker_ignoresVpnAndUntrackedNetworksOnObserveLinkChangeAndLost() {
        val tracker = PhysicalNetworkChangeTracker()

        assertNull(
            "VPN network (isVpn=true) must be ignored",
            tracker.onNetworkObserved("net-vpn-99", transport = "VPN", isValidated = true, isVpn = true)
        )
        assertNull(
            "VPN network link change must be ignored",
            tracker.onLinkPropertiesChanged("net-vpn-99", "tun0|10.255.255.2|10.255.255.1", isVpn = true)
        )
        assertNull(
            "onLost for ignored VPN network must be ignored",
            tracker.onNetworkLost("net-vpn-99")
        )
        assertNull(
            "onLinkPropertiesChanged for untracked network must be ignored",
            tracker.onLinkPropertiesChanged("net-unknown-7", "wlan0|192.168.1.2|1.1.1.1")
        )
        assertNull(
            "onLost for untracked network must be ignored",
            tracker.onNetworkLost("net-unknown-7")
        )
    }

    @Test
    fun tracker_clearResetsAllTrackedState() {
        val tracker = PhysicalNetworkChangeTracker()

        assertEquals(
            "physical_net_available:WIFI",
            tracker.onNetworkObserved("net-wifi-1", transport = "WIFI", isValidated = true)
        )
        tracker.clear()

        assertNull(
            "After clear(), previously tracked network must no longer trigger onLost",
            tracker.onNetworkLost("net-wifi-1")
        )
        assertEquals(
            "physical_net_available:WIFI",
            tracker.onNetworkObserved("net-wifi-1", transport = "WIFI", isValidated = true)
        )
    }

    // -------------------------------------------------------------------------
    // NetworkChangeCoalescer (Leading + Trailing-Edge Flush)
    // -------------------------------------------------------------------------

    @Test
    fun coalescer_firesLeadingImmediatelyAndSchedulesSingleTrailingFlushForBurstRecovery() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val clock = AtomicLong(1_000L)
        val destroyed = AtomicBoolean(false)
        val flushedReasons = CopyOnWriteArrayList<String>()
        val leadingDone = CountDownLatch(1)
        val trailingDone = CountDownLatch(1)

        try {
            val coalescer = NetworkChangeCoalescer(
                scope = scope,
                clockMs = { clock.get() },
                coalesceMs = 40L,
                isDestroyed = { destroyed.get() },
                onFlush = { reason ->
                    flushedReasons.add(reason)
                    if (flushedReasons.size == 1) leadingDone.countDown()
                    if (flushedReasons.size == 2) trailingDone.countDown()
                }
            )

            // 1. Leading edge fires immediately
            coalescer.notify("physical_net_lost:WIFI")
            assertTrue("Leading flush must fire immediately", leadingDone.await(2, TimeUnit.SECONDS))
            assertEquals(listOf("physical_net_lost:WIFI"), flushedReasons.toList())

            // 2. Subsequent rapid transitions within window coalesce into ONE trailing flush with latest reason
            clock.addAndGet(10L)
            coalescer.notify("physical_net_available:CELLULAR")
            clock.addAndGet(5L)
            coalescer.notify("physical_net_validated:CELLULAR")

            assertTrue("Trailing recovery flush must fire after coalesce window", trailingDone.await(2, TimeUnit.SECONDS))
            assertEquals(
                listOf("physical_net_lost:WIFI", "physical_net_validated:CELLULAR"),
                flushedReasons.toList()
            )

            // 3. Destroyed service suppresses any further flushes
            destroyed.set(true)
            clock.addAndGet(500L)
            coalescer.notify("physical_net_available:WIFI")
            assertEquals(2, flushedReasons.size)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun coalescer_suppressesTrailingFlushWhenDestroyedDuringDelayWindow() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val clock = AtomicLong(5_000L)
        val destroyed = AtomicBoolean(false)
        val flushedReasons = CopyOnWriteArrayList<String>()
        val leadingDone = CountDownLatch(1)

        try {
            val coalescer = NetworkChangeCoalescer(
                scope = scope,
                clockMs = { clock.get() },
                coalesceMs = 80L,
                isDestroyed = { destroyed.get() },
                onFlush = { reason ->
                    flushedReasons.add(reason)
                    leadingDone.countDown()
                }
            )

            coalescer.notify("physical_net_lost:WIFI")
            assertTrue(leadingDone.await(2, TimeUnit.SECONDS))
            clock.addAndGet(5L)
            coalescer.notify("physical_net_available:CELLULAR")
            // Destroy service before the 80ms trailing delay expires
            destroyed.set(true)
            Thread.sleep(130L)
            assertEquals("Trailing flush must not fire after service destruction", 1, flushedReasons.size)
        } finally {
            scope.cancel()
        }
    }
}
