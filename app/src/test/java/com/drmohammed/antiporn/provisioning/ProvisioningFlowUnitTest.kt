package com.drmohammed.antiporn.provisioning

import com.drmohammed.antiporn.core.model.DevicePolicyBundle
import com.drmohammed.antiporn.provisioning.engine.AdbMdnsDiscovery
import com.drmohammed.antiporn.provisioning.engine.DeviceOwnerPromoter
import com.drmohammed.antiporn.provisioning.engine.OemProvisioningHelper
import com.drmohammed.antiporn.provisioning.receiver.PairingCodeReceiver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket

/**
 * Unit tests for Wireless Debugging provisioning flow helpers:
 * 1. Smart pairing code sanitization (ASCII, Arabic, Persian numerals, stripping noise).
 * 2. Active port liveness probe (ServerSocket bind test for stale cache elimination).
 * 3. OEM manufacturer detection helpers.
 */
class ProvisioningFlowUnitTest {

    @Test
    fun testSanitizePairingCode_nullOrBlank() {
        assertEquals("", PairingCodeReceiver.sanitizePairingCode(null))
        assertEquals("", PairingCodeReceiver.sanitizePairingCode(""))
        assertEquals("", PairingCodeReceiver.sanitizePairingCode("   "))
    }

    @Test
    fun testSanitizePairingCode_standardAscii() {
        assertEquals("123456", PairingCodeReceiver.sanitizePairingCode("123456"))
        assertEquals("098765", PairingCodeReceiver.sanitizePairingCode("098765"))
    }

    @Test
    fun testSanitizePairingCode_easternArabicNumerals() {
        // ١ ٢ ٣ ٤ ٥ ٦ -> 1 2 3 4 5 6
        assertEquals("123456", PairingCodeReceiver.sanitizePairingCode("١٢٣٤٥٦"))
        assertEquals("078912", PairingCodeReceiver.sanitizePairingCode("٠٧٨٩١٢"))
    }

    @Test
    fun testSanitizePairingCode_persianNumerals() {
        // ۱ ۲ ۳ ۴ ۵ ۶ -> 1 2 3 4 5 6
        assertEquals("123456", PairingCodeReceiver.sanitizePairingCode("۱۲۳۴۵۶"))
        assertEquals("078912", PairingCodeReceiver.sanitizePairingCode("۰۷۸۹۱۲"))
    }

    @Test
    fun testSanitizePairingCode_withNoiseAndWhitespace() {
        assertEquals("123456", PairingCodeReceiver.sanitizePairingCode(" 12 - 34 - 56 "))
        assertEquals("123456", PairingCodeReceiver.sanitizePairingCode("Code: 123456."))
        assertEquals("123456", PairingCodeReceiver.sanitizePairingCode("١٢ - ٣٤ ٥٦"))
    }

    @Test
    fun testIsPortActuallyListening_invalidPorts() {
        assertFalse(AdbMdnsDiscovery.isPortActuallyListening(0))
        assertFalse(AdbMdnsDiscovery.isPortActuallyListening(-1))
        assertFalse(AdbMdnsDiscovery.isPortActuallyListening(70000))
    }

    @Test
    fun testIsPortActuallyListening_activeVsDeadPort() {
        // Spin up a temporary ServerSocket on an ephemeral port
        val server = ServerSocket(0)
        val port = server.localPort
        try {
            // When server is listening, binding should fail (EADDRINUSE) -> returns true!
            assertTrue("Active socket must report listening=true", AdbMdnsDiscovery.isPortActuallyListening(port))
        } finally {
            server.close()
        }

        // When closed, binding should succeed -> returns false (nothing is listening)!
        assertFalse("Closed socket must report listening=false", AdbMdnsDiscovery.isPortActuallyListening(port))
    }

    @Test
    fun testOemHelper_manufacturerChecks() {
        // Check OEM helper manufacturer string logic
        val manufacturer = OemProvisioningHelper.manufacturer
        // Just verify it doesn't throw and returns lower case
        assertEquals(manufacturer.lowercase(), manufacturer)
    }

    @Test
    fun testSanitizeDnsHostForAdb_acceptsValidHostsAndRejectsMalformedOrInjectionPayloads() {
        val fallback = DevicePolicyBundle.CLOUDFLARE_FAMILY_DNS_HOST

        // Valid hostnames (including leading/trailing whitespace trimming)
        assertEquals("family.cloudflare-dns.com", DeviceOwnerPromoter.sanitizeDnsHostForAdb("family.cloudflare-dns.com"))
        assertEquals(
            "family-filter-dns.cleanbrowsing.org",
            DeviceOwnerPromoter.sanitizeDnsHostForAdb("  family-filter-dns.cleanbrowsing.org  ")
        )
        assertEquals("a.b", DeviceOwnerPromoter.sanitizeDnsHostForAdb("a.b"))

        // Malformed & shell-injection payloads must fall back safely
        val invalidInputs = listOf(
            null,
            "",
            "   ",
            "localhost",
            ".family.cloudflare-dns.com",
            "family.cloudflare-dns.com.",
            "-bad.example.com",
            "bad-.example.com",
            "foo..bar.com",
            "foo.-bar.com",
            "family.cloudflare-dns.com; rm -rf /",
            "family.cloudflare-dns.com && id",
            "$(whoami).example.com",
            "`id`.example.com",
            "bad host.example.com",
            "${"a".repeat(64)}.example.com",
            ("${"a".repeat(50)}.").repeat(6) + "com"
        )
        for (invalid in invalidInputs) {
            assertEquals(
                "Invalid host '$invalid' must fall back to Cloudflare Family DNS",
                fallback,
                DeviceOwnerPromoter.sanitizeDnsHostForAdb(invalid)
            )
        }
    }
}
