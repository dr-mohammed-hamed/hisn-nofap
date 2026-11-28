package com.drmohammed.antiporn.ui.control.web

import com.drmohammed.antiporn.core.model.DevicePolicyBundle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WebFilterUiStateTest {

    @Test
    fun `isLocalVpnNeeded and localVpnReason reflect full system and ad blocking combinations`() {
        val neither = WebFilterUiState(isFullSystemFiltering = false, isAdBlocking = false)
        assertFalse(neither.isLocalVpnNeeded)
        assertNull(neither.localVpnReason)
        assertEquals(EngineMode.LIGHT_PRIVATE_DNS, neither.engineMode)

        val fullOnly = WebFilterUiState(isFullSystemFiltering = true, isAdBlocking = false)
        assertTrue(fullOnly.isLocalVpnNeeded)
        assertEquals(LocalVpnReason.FULL_SYSTEM_FILTER, fullOnly.localVpnReason)
        assertEquals(EngineMode.LOCAL_VPN, fullOnly.engineMode)

        val adOnly = WebFilterUiState(isFullSystemFiltering = false, isAdBlocking = true)
        assertTrue(adOnly.isLocalVpnNeeded)
        assertEquals(LocalVpnReason.AD_BLOCKING, adOnly.localVpnReason)

        val fallbackOnly = WebFilterUiState(isLocalVpnFallbackEnabled = true)
        assertFalse(fallbackOnly.isLocalVpnNeeded)
        assertNull(fallbackOnly.localVpnReason)
        assertEquals(EngineMode.LIGHT_PRIVATE_DNS, fallbackOnly.engineMode)
        assertFalse(WebFilterUiState().isLocalVpnFallbackEnabled)
        assertTrue(
            WebFilterUiState.fromPolicy(
                isRemote = false,
                wardId = "",
                policy = DevicePolicyBundle.defaultPolicy().copy(isLocalVpnFallbackEnabled = true)
            ).isLocalVpnFallbackEnabled
        )

        val both = WebFilterUiState(isFullSystemFiltering = true, isAdBlocking = true)
        assertTrue(both.isLocalVpnNeeded)
        assertEquals(LocalVpnReason.BOTH, both.localVpnReason)
    }

    @Test
    fun `adBlockDnsHint matches selected DNS preset`() {
        assertEquals(
            AdBlockDnsHint.NONE,
            WebFilterUiState(dnsHost = DevicePolicyBundle.CLOUDFLARE_FAMILY_DNS_HOST).adBlockDnsHint
        )
        assertEquals(
            AdBlockDnsHint.CONFIRMED_BY_DNS,
            WebFilterUiState(dnsHost = DevicePolicyBundle.ADGUARD_FAMILY_DNS_HOST).adBlockDnsHint
        )
        assertEquals(
            AdBlockDnsHint.CONFIRMED_BY_DNS,
            WebFilterUiState(dnsHost = DevicePolicyBundle.CONTROLD_NO_ADS_PORN_DNS_HOST).adBlockDnsHint
        )
        assertEquals(
            AdBlockDnsHint.CONFIRMED_BY_DNS,
            WebFilterUiState(dnsHost = DevicePolicyBundle.CONTROLD_FAMILY_DNS_HOST).adBlockDnsHint
        )
        assertEquals(
            AdBlockDnsHint.POSSIBLE_CUSTOM_DNS,
            WebFilterUiState(dnsHost = "abc123.dns.nextdns.io").adBlockDnsHint
        )
        assertEquals(
            DnsPreset.CLOUDFLARE,
            WebFilterUiState(dnsHost = "   ").selectedDnsPreset
        )
    }

    @Test
    fun `showWhitelistFullSystemWarning is true only when both flags are enabled`() {
        assertFalse(WebFilterUiState(isWhitelistOnlyMode = false, isFullSystemFiltering = false).showWhitelistFullSystemWarning)
        assertFalse(WebFilterUiState(isWhitelistOnlyMode = true, isFullSystemFiltering = false).showWhitelistFullSystemWarning)
        assertFalse(WebFilterUiState(isWhitelistOnlyMode = false, isFullSystemFiltering = true).showWhitelistFullSystemWarning)
        assertTrue(WebFilterUiState(isWhitelistOnlyMode = true, isFullSystemFiltering = true).showWhitelistFullSystemWarning)
    }

    @Test
    fun `showUnaffiliatedLocalWarning is false when remote or secure settings writable or no local vpn`() {
        val localBlockedNoPerm = WebFilterUiState(
            isRemote = false,
            isFullSystemFiltering = true,
            isUnaffiliatedBlocked = true,
            canWriteSecureSettings = false
        )
        assertTrue(localBlockedNoPerm.showUnaffiliatedLocalWarning)

        assertFalse(localBlockedNoPerm.copy(isRemote = true).showUnaffiliatedLocalWarning)
        assertFalse(localBlockedNoPerm.copy(canWriteSecureSettings = true).showUnaffiliatedLocalWarning)
        assertFalse(localBlockedNoPerm.copy(isUnaffiliatedBlocked = false).showUnaffiliatedLocalWarning)
        assertFalse(localBlockedNoPerm.copy(isFullSystemFiltering = false, isAdBlocking = false).showUnaffiliatedLocalWarning)
    }

    @Test
    fun `hasUnsavedChanges detects modifications accurately including isBlockExternalVpn`() {
        val initial = DevicePolicyBundle.defaultPolicy().copy(
            dnsHost = DevicePolicyBundle.ADGUARD_FAMILY_DNS_HOST,
            isBlockExternalVpnEnabled = true,
            browserFilterMode = DevicePolicyBundle.BROWSER_FILTER_OFF
        )
        val baseState = WebFilterUiState.fromPolicy(isRemote = false, wardId = "", policy = initial)
        assertFalse(baseState.hasUnsavedChanges())

        assertTrue(baseState.copy(isBlockExternalVpn = false).hasUnsavedChanges())
        assertTrue(baseState.copy(dnsHost = DevicePolicyBundle.CLOUDFLARE_FAMILY_DNS_HOST).hasUnsavedChanges())
        assertTrue(baseState.copy(dnsHost = "").hasUnsavedChanges())
        assertTrue(baseState.copy(isIncognitoBlocked = !initial.isIncognitoBlocked).hasUnsavedChanges())
        assertTrue(baseState.copy(isTelegramWebBlocked = !initial.isTelegramWebBlocked).hasUnsavedChanges())
        assertTrue(baseState.copy(isYouTubeRestricted = !initial.isYouTubeRestrictedModeEnabled).hasUnsavedChanges())
        assertTrue(baseState.copy(isFullSystemFiltering = true).hasUnsavedChanges())
        assertTrue(baseState.copy(isAdBlocking = true).hasUnsavedChanges())
        assertTrue(baseState.copy(isLocalVpnFallbackEnabled = true).hasUnsavedChanges())
        assertTrue(baseState.copy(isWhitelistOnlyMode = true).hasUnsavedChanges())
        assertTrue(baseState.copy(blockedUrls = setOf("example.com")).hasUnsavedChanges())
        assertTrue(baseState.copy(allowedUrls = setOf("safe.com")).hasUnsavedChanges())
        assertFalse(baseState.copy(dnsHost = "  ${DevicePolicyBundle.ADGUARD_FAMILY_DNS_HOST.uppercase()}  ").hasUnsavedChanges())
    }

    @Test
    fun `computeBrowserFilterMode returns expected mode`() {
        assertEquals(
            DevicePolicyBundle.BROWSER_FILTER_OFF,
            WebFilterUiState().computeBrowserFilterMode()
        )
        assertEquals(
            DevicePolicyBundle.BROWSER_FILTER_BLOCKLIST,
            WebFilterUiState(isFullSystemFiltering = true).computeBrowserFilterMode()
        )
        assertEquals(
            DevicePolicyBundle.BROWSER_FILTER_BLOCKLIST,
            WebFilterUiState(blockedUrls = setOf("evil.com")).computeBrowserFilterMode()
        )
        assertEquals(
            DevicePolicyBundle.BROWSER_FILTER_BLOCKLIST,
            WebFilterUiState(allowedUrls = setOf("good.com")).computeBrowserFilterMode()
        )
        assertEquals(
            DevicePolicyBundle.BROWSER_FILTER_WHITELIST_ONLY,
            WebFilterUiState(isWhitelistOnlyMode = true, isFullSystemFiltering = true).computeBrowserFilterMode()
        )
    }
}
