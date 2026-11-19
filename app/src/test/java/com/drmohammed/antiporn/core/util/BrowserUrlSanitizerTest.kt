package com.drmohammed.antiporn.core.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserUrlSanitizerTest {

    @Test
    fun testSanitize_nullOrBlank_returnsNull() {
        assertNull(BrowserUrlSanitizer.sanitize(null))
        assertNull(BrowserUrlSanitizer.sanitize(""))
        assertNull(BrowserUrlSanitizer.sanitize("   "))
        assertNull(BrowserUrlSanitizer.sanitize("\t\n"))
    }

    @Test
    fun testSanitize_basicDomains_normalized() {
        assertEquals("example.com", BrowserUrlSanitizer.sanitize("example.com"))
        assertEquals("example.com", BrowserUrlSanitizer.sanitize("EXAMPLE.COM"))
        assertEquals("google.com", BrowserUrlSanitizer.sanitize("  Google.com  "))
        assertEquals("sub.domain.co.uk", BrowserUrlSanitizer.sanitize("sub.domain.co.uk"))
    }

    @Test
    fun testSanitize_protocolStripping() {
        assertEquals("example.com", BrowserUrlSanitizer.sanitize("http://example.com"))
        assertEquals("example.com", BrowserUrlSanitizer.sanitize("https://example.com"))
        assertEquals("example.com", BrowserUrlSanitizer.sanitize("HTTP://EXAMPLE.COM"))
        assertEquals("example.com", BrowserUrlSanitizer.sanitize("https://example.com/"))
        assertEquals("example.com", BrowserUrlSanitizer.sanitize("//example.com"))
        assertNull(BrowserUrlSanitizer.sanitize("http://"))
        assertNull(BrowserUrlSanitizer.sanitize("https://"))
    }

    @Test
    fun testSanitize_trailingSlashes_stripped() {
        assertEquals("example.com", BrowserUrlSanitizer.sanitize("example.com/"))
        assertEquals("example.com", BrowserUrlSanitizer.sanitize("https://example.com///"))
        assertEquals("example.com/path", BrowserUrlSanitizer.sanitize("example.com/path/"))
        assertEquals("example.com/path/to/page", BrowserUrlSanitizer.sanitize("example.com/path/to/page///"))
    }

    @Test
    fun testSanitize_wwwStripping() {
        assertEquals("example.com", BrowserUrlSanitizer.sanitize("www.example.com"))
        assertEquals("google.com", BrowserUrlSanitizer.sanitize("https://www.google.com/"))
        assertEquals("*.example.com", BrowserUrlSanitizer.sanitize("*.www.example.com"))
        // www as 2nd level domain should be preserved
        assertEquals("www.com", BrowserUrlSanitizer.sanitize("www.com"))
    }

    @Test
    fun testSanitize_wildcards() {
        assertEquals("*", BrowserUrlSanitizer.sanitize("*"))
        assertEquals("*.example.com", BrowserUrlSanitizer.sanitize("*.example.com"))
        assertEquals("*.sub.example.com", BrowserUrlSanitizer.sanitize("*.sub.example.com"))
        assertEquals("*.google.co.uk", BrowserUrlSanitizer.sanitize("*.google.co.uk"))
    }

    @Test
    fun testSanitize_validPathsAndQueries() {
        assertEquals("example.com/path", BrowserUrlSanitizer.sanitize("example.com/path"))
        assertEquals("example.com/path?foo=bar&baz=1", BrowserUrlSanitizer.sanitize("example.com/path?foo=bar&baz=1"))
        assertEquals("twitter.com/i/flow/login", BrowserUrlSanitizer.sanitize("https://twitter.com/i/flow/login"))
        assertEquals("example.com:8080", BrowserUrlSanitizer.sanitize("example.com:8080"))
        assertEquals("example.com:8080/path", BrowserUrlSanitizer.sanitize("example.com:8080/path"))
    }

    @Test
    fun testSanitize_fragmentStripped() {
        assertEquals("example.com/page", BrowserUrlSanitizer.sanitize("example.com/page#section1"))
        assertEquals("example.com", BrowserUrlSanitizer.sanitize("https://example.com/#top"))
    }

    @Test
    fun testSanitize_invalidInputs_rejected() {
        // No dot, no wildcard
        assertNull(BrowserUrlSanitizer.sanitize("not-a-domain"))
        assertNull(BrowserUrlSanitizer.sanitize("localhost"))
        assertNull(BrowserUrlSanitizer.sanitize("google"))

        // Whitespace
        assertNull(BrowserUrlSanitizer.sanitize("example .com"))
        assertNull(BrowserUrlSanitizer.sanitize("example.com/path with spaces"))

        // Invalid characters
        assertNull(BrowserUrlSanitizer.sanitize("bad<site>.com"))
        assertNull(BrowserUrlSanitizer.sanitize("example.com/\"evil\""))

        // Broken domain syntax
        assertNull(BrowserUrlSanitizer.sanitize("example..com"))
        assertNull(BrowserUrlSanitizer.sanitize(".example.com."))
        assertNull(BrowserUrlSanitizer.sanitize("-example.com"))
        assertNull(BrowserUrlSanitizer.sanitize("example-.com"))
        assertNull(BrowserUrlSanitizer.sanitize("."))
        assertNull(BrowserUrlSanitizer.sanitize(".."))

        // Invalid wildcards
        assertNull(BrowserUrlSanitizer.sanitize("foo*bar.com"))
        assertNull(BrowserUrlSanitizer.sanitize("example.*"))

        // Invalid ports
        assertNull(BrowserUrlSanitizer.sanitize("example.com:abc"))
        assertNull(BrowserUrlSanitizer.sanitize("example.com:99999"))
        assertNull(BrowserUrlSanitizer.sanitize("example.com:0"))
    }

    @Test
    fun testCaptivePortalExemptions_areAllValid() {
        assertTrue(BrowserUrlSanitizer.CAPTIVE_PORTAL_EXEMPTIONS.isNotEmpty())
        for (domain in BrowserUrlSanitizer.CAPTIVE_PORTAL_EXEMPTIONS) {
            val sanitized = BrowserUrlSanitizer.sanitize(domain)
            assertNotNull("Captive portal exemption should be valid: $domain", sanitized)
            assertEquals("Domain should remain unchanged after sanitize: $domain", domain, sanitized)
        }
        assertTrue(BrowserUrlSanitizer.CAPTIVE_PORTAL_EXEMPTIONS.contains("connectivitycheck.gstatic.com"))
        assertTrue(BrowserUrlSanitizer.CAPTIVE_PORTAL_EXEMPTIONS.contains("clients3.google.com"))
        assertTrue(BrowserUrlSanitizer.CAPTIVE_PORTAL_EXEMPTIONS.contains("accounts.google.com"))
    }

    @Test
    fun testSocialMediaPresets_areAllValid() {
        assertTrue(BrowserUrlSanitizer.SOCIAL_MEDIA_PRESETS.isNotEmpty())
        for (domain in BrowserUrlSanitizer.SOCIAL_MEDIA_PRESETS) {
            val sanitized = BrowserUrlSanitizer.sanitize(domain)
            assertNotNull("Social media preset should be valid: $domain", sanitized)
            assertEquals("Preset should remain unchanged after sanitize: $domain", domain, sanitized)
        }
        assertTrue(BrowserUrlSanitizer.SOCIAL_MEDIA_PRESETS.contains("facebook.com"))
        assertTrue(BrowserUrlSanitizer.SOCIAL_MEDIA_PRESETS.contains("instagram.com"))
        assertTrue(BrowserUrlSanitizer.SOCIAL_MEDIA_PRESETS.contains("tiktok.com"))
        assertTrue(BrowserUrlSanitizer.SOCIAL_MEDIA_PRESETS.contains("x.com"))
    }

    @Test
    fun testIsValid_utility() {
        assertTrue(BrowserUrlSanitizer.isValid("google.com"))
        assertTrue(BrowserUrlSanitizer.isValid("*.google.com"))
        assertTrue(BrowserUrlSanitizer.isValid("*"))
        assertFalse(BrowserUrlSanitizer.isValid("not-a-domain"))
        assertFalse(BrowserUrlSanitizer.isValid(null))
        assertFalse(BrowserUrlSanitizer.isValid(""))
    }

    @Test
    fun testSanitize_internationalizedDomainNames_convertsToPunycode() {
        val arabicDomain = "موقع.com"
        val expectedPunycode = java.net.IDN.toASCII("موقع.com")
        assertEquals(expectedPunycode, BrowserUrlSanitizer.sanitize("https://موقع.com/"))
        assertEquals(expectedPunycode, BrowserUrlSanitizer.sanitize("موقع.com"))
        assertEquals("*.$expectedPunycode", BrowserUrlSanitizer.sanitize("*.موقع.com"))

        // Already punycode
        assertEquals(expectedPunycode, BrowserUrlSanitizer.sanitize(expectedPunycode))
    }

    @Test
    fun testSanitize_preservesCaseInPathAndQuery() {
        assertEquals(
            "youtube.com/watch?v=dQw4w9WgXcQ",
            BrowserUrlSanitizer.sanitize("https://www.YOUTUBE.COM/watch?v=dQw4w9WgXcQ")
        )
        assertEquals(
            "example.com/OAuth/Callback?Token=AbCd123",
            BrowserUrlSanitizer.sanitize("EXAMPLE.COM/OAuth/Callback?Token=AbCd123")
        )
    }

    @Test
    fun testSanitize_rejectsUserCredentials() {
        assertNull(BrowserUrlSanitizer.sanitize("user:pass@example.com"))
        assertNull(BrowserUrlSanitizer.sanitize("admin@google.com"))
    }

    @Test
    fun testSanitizeList_filtersAndDeduplicates() {
        val input = listOf(
            "https://www.google.com/",
            "google.com",
            "not-a-domain",
            "  ",
            "HTTP://EXAMPLE.COM",
            "*.youtube.com",
            "example.com"
        )
        val result = BrowserUrlSanitizer.sanitizeList(input)
        assertEquals(3, result.size)
        assertTrue(result.contains("google.com"))
        assertTrue(result.contains("example.com"))
        assertTrue(result.contains("*.youtube.com"))
    }

    @Test
    fun testExtractRootDomain_handlesGenericAndCompoundTlds() {
        assertEquals("platform.edu", BrowserUrlSanitizer.extractRootDomain("student.platform.edu"))
        assertEquals("mansoura.edu.eg", BrowserUrlSanitizer.extractRootDomain("student.mansoura.edu.eg"))
        assertEquals("mansoura.edu.eg", BrowserUrlSanitizer.extractRootDomain("courses.portal.mansoura.edu.eg"))
        assertEquals("academy.com.sa", BrowserUrlSanitizer.extractRootDomain("login.academy.com.sa"))
        assertEquals("domain.co.uk", BrowserUrlSanitizer.extractRootDomain("sub.domain.co.uk"))
        assertEquals("example.com", BrowserUrlSanitizer.extractRootDomain("example.com"))
        assertEquals("192.168.1.1", BrowserUrlSanitizer.extractRootDomain("192.168.1.1"))

        // Multi-tenant platforms must NEVER collapse subdomains to parent domain
        assertEquals("myclass.wordpress.com", BrowserUrlSanitizer.extractRootDomain("myclass.wordpress.com"))
        assertEquals("docs.google.com", BrowserUrlSanitizer.extractRootDomain("docs.google.com"))
        assertEquals("student.github.io", BrowserUrlSanitizer.extractRootDomain("student.github.io"))
        assertEquals("course.blogspot.com", BrowserUrlSanitizer.extractRootDomain("course.blogspot.com"))
    }

    @Test
    fun testSanitizeForAllowlist_extractsCleanRootDomain() {
        assertEquals("platform.edu", BrowserUrlSanitizer.sanitizeForAllowlist("https://student.platform.edu/login?ref=123"))
        assertEquals("mansoura.edu.eg", BrowserUrlSanitizer.sanitizeForAllowlist("https://student.mansoura.edu.eg/courses/10#top"))
        assertEquals("academy.com", BrowserUrlSanitizer.sanitizeForAllowlist("*.academy.com/courses/*"))
        assertEquals("school.edu.eg", BrowserUrlSanitizer.sanitizeForAllowlist("www.courses.school.edu.eg:8080/lesson/1"))
        // Multi-tenant platforms preserve subdomain
        assertEquals("docs.google.com", BrowserUrlSanitizer.sanitizeForAllowlist("https://docs.google.com/document/d/123"))
        assertEquals("myclass.wordpress.com", BrowserUrlSanitizer.sanitizeForAllowlist("https://myclass.wordpress.com/math"))
        assertNull(BrowserUrlSanitizer.sanitizeForAllowlist("not-a-domain"))
        assertNull(BrowserUrlSanitizer.sanitizeForAllowlist(null))
        assertNull(BrowserUrlSanitizer.sanitizeForAllowlist("   "))
        assertNull(BrowserUrlSanitizer.sanitizeForAllowlist("user:pass@example.com"))
    }

    @Test
    fun testCleanForChromiumAllowlist_stripsWildcards() {
        assertEquals("platform.edu", BrowserUrlSanitizer.cleanForChromiumAllowlist("*.platform.edu"))
        assertEquals("platform.edu", BrowserUrlSanitizer.cleanForChromiumAllowlist("platform.edu/*"))
        assertEquals("domain.com", BrowserUrlSanitizer.cleanForChromiumAllowlist("*.domain.com/*"))
        assertEquals("example.com", BrowserUrlSanitizer.cleanForChromiumAllowlist(".example.com"))
    }

    @Test
    fun testEducationalAndTechnicalExemptions_arePopulated() {
        assertTrue(BrowserUrlSanitizer.EDUCATIONAL_ALLOWLIST_RULES.contains("iframe.mediadelivery.net"))
        assertTrue(BrowserUrlSanitizer.EDUCATIONAL_ALLOWLIST_RULES.contains("player.vdocipher.com"))
        assertTrue(BrowserUrlSanitizer.EDUCATIONAL_ALLOWLIST_RULES.contains("player.vimeo.com"))
        assertTrue(BrowserUrlSanitizer.EDUCATIONAL_ALLOWLIST_RULES.contains("youtube.com/embed*"))
        assertTrue(BrowserUrlSanitizer.EDUCATIONAL_ALLOWLIST_RULES.contains("googlevideo.com"))
        assertTrue(BrowserUrlSanitizer.EDUCATIONAL_ALLOWLIST_RULES.contains("ytimg.com"))
        assertTrue(BrowserUrlSanitizer.TECHNICAL_HELPER_EXEMPTIONS.contains("blob:*"))
        assertTrue(BrowserUrlSanitizer.TECHNICAL_HELPER_EXEMPTIONS.contains("data:*"))
        assertTrue(BrowserUrlSanitizer.EDUCATIONAL_DNS_DOMAINS.contains("b-cdn.net"))
        assertTrue(BrowserUrlSanitizer.EDUCATIONAL_DNS_DOMAINS.contains("googlevideo.com"))
        assertTrue(BrowserUrlSanitizer.EDUCATIONAL_DNS_DOMAINS.contains("ytimg.com"))
    }
}
