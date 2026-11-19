package com.drmohammed.antiporn.core.util

import java.net.IDN

/**
 * Utility for sanitizing and validating URLs and hostnames for Chromium enterprise policy
 * restrictions (URLBlocklist and URLAllowlist).
 */
object BrowserUrlSanitizer {

    val CAPTIVE_PORTAL_EXEMPTIONS: List<String> = listOf(
        "connectivitycheck.gstatic.com",
        "clients3.google.com",
        "accounts.google.com",
        "accounts.youtube.com",
        "play.google.com",
        "play.googleapis.com",
        "ssl.gstatic.com"
    )

    /**
     * Vetted educational streaming services, DRM players, and SSO/Captcha verification endpoints
     * required for online learning platforms (e.g. Bunny Stream, VdoCipher, Vimeo player, YouTube embed,
     * Turnstile, Google Accounts) without unblocking full social media or video browsing.
     */
    val EDUCATIONAL_ALLOWLIST_RULES: List<String> = listOf(
        // Bunny Stream & CDN (dominant in Middle East & global LMS platforms)
        "iframe.mediadelivery.net",
        "video.bunnycdn.com",
        "b-cdn.net",
        // VdoCipher DRM Video Player (widely used in academies and universities)
        "player.vdocipher.com",
        "vdocipher.com",
        // Vimeo Embed Player (embed player only; main vimeo browsing remains restricted)
        "player.vimeo.com",
        "vimeocdn.com",
        // YouTube Embed Player (embed endpoints only; main youtube.com browsing remains restricted)
        "youtube-nocookie.com",
        "youtube.com/embed*",
        "googlevideo.com",
        "ytimg.com",
        // Cloudflare Stream & CDN Delivery
        "iframe.cloudflarestream.com",
        "cloudflarestream.com",
        "videodelivery.net",
        // Wistia & Gumlet video embeds
        "fast.wistia.net",
        "play.gumlet.io",
        // Bot protection & Captcha Verification (Turnstile, hCaptcha, reCAPTCHA)
        "challenges.cloudflare.com",
        "hcaptcha.com",
        "recaptcha.net",
        // Single Sign-On (Google & Microsoft for university portals)
        "accounts.google.com",
        "login.microsoftonline.com"
    )

    /**
     * Required technical helper schemes for Chromium enterprise lockdown mode (URLBlocklist = ["*"]).
     * Allows in-memory blob URLs (PDF downloads, HLS streams) and data URIs without arbitrary navigation.
     */
    val TECHNICAL_HELPER_EXEMPTIONS: List<String> = listOf(
        "about:blank",
        "about:srcdoc",
        "data:*",
        "blob:*",
        "chrome://*"
    )

    /**
     * DNS host exemptions required when Full-System Filtering is enabled alongside Whitelist Only mode.
     * Contains only valid DNS hostnames (no path rules or technical URI schemes).
     */
    val EDUCATIONAL_DNS_DOMAINS: List<String> = listOf(
        "iframe.mediadelivery.net",
        "video.bunnycdn.com",
        "b-cdn.net",
        "player.vdocipher.com",
        "vdocipher.com",
        "player.vimeo.com",
        "vimeocdn.com",
        "youtube-nocookie.com",
        "googlevideo.com",
        "ytimg.com",
        "iframe.cloudflarestream.com",
        "cloudflarestream.com",
        "videodelivery.net",
        "fast.wistia.net",
        "play.gumlet.io",
        "challenges.cloudflare.com",
        "hcaptcha.com",
        "recaptcha.net",
        "accounts.google.com",
        "login.microsoftonline.com"
    )

    private val KNOWN_COMPOUND_TLDS: Set<String> = setOf(
        "edu.eg", "com.eg", "gov.eg", "org.eg", "net.eg", "sci.eg",
        "edu.sa", "com.sa", "gov.sa", "org.sa", "net.sa", "med.sa",
        "co.uk", "org.uk", "ac.uk", "gov.uk", "me.uk", "net.uk",
        "com.au", "edu.au", "gov.au", "net.au", "org.au",
        "co.za", "edu.za", "gov.za", "org.za",
        "com.tr", "edu.tr", "gov.tr", "org.tr",
        "co.in", "edu.in", "gov.in", "org.in", "net.in",
        "com.br", "edu.br", "gov.br", "org.br",
        "co.jp", "ac.jp", "ne.jp", "go.jp",
        "com.mx", "edu.mx", "gob.mx", "org.mx",
        "com.pk", "edu.pk", "gov.pk", "org.pk",
        "com.ng", "edu.ng", "gov.ng",
        "com.kw", "edu.kw", "gov.kw",
        "com.qa", "edu.qa", "gov.qa",
        "com.om", "edu.om", "gov.om",
        "com.jo", "edu.jo", "gov.jo",
        "com.lb", "edu.lb", "gov.lb",
        "com.ae", "edu.ae", "gov.ae", "net.ae",
        "edu.ps", "com.ps", "gov.ps"
    )

    /** Multi-tenant host providers and search platforms where subdomains must NEVER be stripped to parent domain */
    private val PRESERVE_SUBDOMAIN_PROVIDERS: Set<String> = setOf(
        "wordpress.com", "blogspot.com", "github.io", "gitlab.io",
        "firebaseapp.com", "web.app", "pages.dev", "netlify.app",
        "vercel.app", "wixsite.com", "weebly.com", "azurewebsites.net",
        "google.com"
    )

    val SOCIAL_MEDIA_PRESETS: List<String> = listOf(
        "facebook.com",
        "instagram.com",
        "tiktok.com",
        "twitter.com",
        "x.com",
        "reddit.com",
        "snapchat.com",
        "threads.net",
        "pinterest.com"
    )

    /**
     * Sanitizes and validates a URL or domain pattern for Chromium URLBlocklist / URLAllowlist.
     *
     * Rules:
     * - Trims whitespace; returns null if null or blank.
     * - Converts domain/host part to lowercase and encodes internationalized domain names (IDN) to Punycode (ASCII).
     * - Preserves case-sensitivity for paths and query parameters.
     * - Strips protocol schemes (http://, https://).
     * - Strips redundant www. prefix if followed by a domain with a dot.
     * - Strips trailing slashes from domains or paths.
     * - Preserves valid paths (e.g. example.com/path).
     * - Rejects strings containing spaces, control/invalid characters, credentials (@), or missing dots (unless wildcard '*').
     * - Supports wildcard patterns (e.g. '*' or '*.example.com').
     *
     * @return Sanitized string in Chromium enterprise policy format, or null if input is invalid.
     */
    fun sanitize(input: String?): String? {
        if (input == null) return null
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return null

        // Reject any string containing whitespace
        if (trimmed.any { it.isWhitespace() }) return null

        // Reject user credentials (@ in authority)
        if (trimmed.contains('@')) return null

        var cleaned = trimmed

        // Strip protocol schemes case-insensitively
        val lowerForScheme = cleaned.lowercase()
        if (lowerForScheme.startsWith("https://")) {
            cleaned = cleaned.substring(8)
        } else if (lowerForScheme.startsWith("http://")) {
            cleaned = cleaned.substring(7)
        }

        // Strip leading slashes if any were left (e.g. //example.com)
        while (cleaned.startsWith('/')) {
            cleaned = cleaned.substring(1)
        }

        if (cleaned.isEmpty()) return null

        // Remove redundant leading dot if user entered .example.com
        if (cleaned.startsWith(".") && !cleaned.startsWith("..") && cleaned.length > 1) {
            cleaned = cleaned.substring(1)
        }

        // Strip trailing slashes
        cleaned = cleaned.trimEnd('/')
        if (cleaned.isEmpty()) return null

        // Strip fragment identifier (#...) as Chromium URL filters do not support fragments
        if (cleaned.contains('#')) {
            cleaned = cleaned.substringBefore('#').trimEnd('/')
            if (cleaned.isEmpty()) return null
        }

        // Special case: Chromium wildcard all '*'
        if (cleaned == "*") {
            return "*"
        }

        // Require either a dot or a wildcard
        if (!cleaned.contains('.') && !cleaned.contains('*')) {
            return null
        }

        // Disallow consecutive dots or invalid punctuation
        if (cleaned.contains("..") || (cleaned.contains("*.") && cleaned.indexOf("*.") != 0)) {
            return null
        }

        // Split into host (with optional port) and path/query (preserving path/query casing)
        val pathIndex = cleaned.indexOfAny(charArrayOf('/', '?'))
        val hostAndPort = if (pathIndex != -1) cleaned.substring(0, pathIndex) else cleaned
        val pathAndQuery = if (pathIndex != -1) cleaned.substring(pathIndex) else ""

        if (hostAndPort.isEmpty()) return null

        // Separate host and port
        val colonIndex = hostAndPort.lastIndexOf(':')
        val rawHost: String
        val portSuffix: String
        if (colonIndex != -1) {
            rawHost = hostAndPort.substring(0, colonIndex)
            val portStr = hostAndPort.substring(colonIndex + 1)
            if (portStr.isEmpty() || !portStr.all { it.isDigit() }) return null
            val portNum = portStr.toIntOrNull() ?: return null
            if (portNum !in 1..65535) return null
            portSuffix = ":$portNum"
        } else {
            rawHost = hostAndPort
            portSuffix = ""
        }

        if (rawHost.isEmpty()) return null

        // Lowercase host part
        var host = rawHost.lowercase()

        // Strip redundant www. prefix if followed by a domain with a dot
        if (host.startsWith("www.") && host.removePrefix("www.").contains('.')) {
            host = host.removePrefix("www.")
        } else if (host.startsWith("*.www.") && host.removePrefix("*.www.").contains('.')) {
            host = "*." + host.removePrefix("*.www.")
        }

        // Handle wildcard prefix and convert internationalized domain names (IDN) to Punycode (ASCII)
        val hasWildcardPrefix = host.startsWith("*.")
        val domainToAscii = if (hasWildcardPrefix) host.removePrefix("*.") else host

        val asciiDomain = if (domainToAscii == "*") {
            "*"
        } else {
            try {
                IDN.toASCII(domainToAscii)
            } catch (_: Exception) {
                return null
            }
        }

        val normalizedHost = if (hasWildcardPrefix) "*.$asciiDomain" else asciiDomain

        // Validate normalized host format
        if (normalizedHost == "*") {
            // Wildcard host
        } else if (normalizedHost.startsWith("*.")) {
            val domainPart = normalizedHost.removePrefix("*.")
            if (domainPart.isEmpty() || domainPart.contains('*')) return null
            val labels = domainPart.split('.')
            if (labels.size < 2 || labels.any { !isValidHostLabel(it) }) return null
        } else {
            if (normalizedHost.contains('*')) return null
            val labels = normalizedHost.split('.')
            if (labels.size < 2) return null
            if (labels.any { !isValidHostLabel(it) }) return null
        }

        // Validate path and query characters
        if (pathAndQuery.isNotEmpty()) {
            for (ch in pathAndQuery) {
                if (ch in " <>\"\\^{}|`" || ch.isWhitespace() || ch.code < 32 || ch.code > 126) {
                    return null
                }
            }
        }

        return normalizedHost + portSuffix + pathAndQuery
    }

    /**
     * Checks whether an input URL or domain string is valid under sanitization rules.
     */
    fun isValid(input: String?): Boolean = sanitize(input) != null

    /**
     * Sanitizes a collection of URL strings, discarding any invalid inputs and returning unique values.
     */
    fun sanitizeList(inputs: Collection<String>?): List<String> {
        if (inputs == null) return emptyList()
        return inputs.mapNotNull { sanitize(it) }.distinct()
    }

    /**
     * Extracts the registered root domain (eTLD+1) for a given hostname.
     * Examples:
     * - "student.platform.edu" -> "platform.edu"
     * - "student.mansoura.edu.eg" -> "mansoura.edu.eg"
     * - "login.portal.academy.com" -> "academy.com"
     * - "192.168.1.1" -> "192.168.1.1"
     * - "example.com" -> "example.com"
     */
    fun extractRootDomain(host: String): String {
        val trimmed = host.trim().lowercase().trimEnd('.')
        if (trimmed.isEmpty()) return ""

        val labels = trimmed.split('.')
        if (labels.size <= 2) {
            return trimmed
        }

        // If all labels are numeric, it's an IPv4 address; return as-is
        if (labels.all { label -> label.all { ch -> ch.isDigit() } }) {
            return trimmed
        }

        val secondToLast = labels[labels.size - 2]
        val last = labels.last()
        val suffixCandidate = "$secondToLast.$last"

        if (PRESERVE_SUBDOMAIN_PROVIDERS.contains(suffixCandidate)) {
            return trimmed
        }

        return if (KNOWN_COMPOUND_TLDS.contains(suffixCandidate)) {
            if (labels.size >= 3) {
                labels.takeLast(3).joinToString(".")
            } else {
                trimmed
            }
        } else {
            labels.takeLast(2).joinToString(".")
        }
    }

    /**
     * Sanitizes and normalizes an input URL or domain specifically for the Allowlist (Whitelist).
     * Extracts the root domain (eTLD+1) without path, query, port, or wildcard prefixes,
     * ensuring that subpages, sibling subdomains, and authentication redirects within the platform
     * function seamlessly without being blocked by Chromium or DNS.
     */
    fun sanitizeForAllowlist(input: String?): String? {
        if (input == null) return null
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return null

        // Reject strings containing whitespace or credentials
        if (trimmed.any { it.isWhitespace() } || trimmed.contains('@')) return null

        var cleaned = trimmed

        // Strip protocol schemes
        val lowerForScheme = cleaned.lowercase()
        if (lowerForScheme.startsWith("https://")) {
            cleaned = cleaned.substring(8)
        } else if (lowerForScheme.startsWith("http://")) {
            cleaned = cleaned.substring(7)
        }

        while (cleaned.startsWith('/')) {
            cleaned = cleaned.substring(1)
        }
        if (cleaned.isEmpty()) return null

        // Strip trailing slash and fragment
        cleaned = cleaned.trimEnd('/')
        if (cleaned.contains('#')) {
            cleaned = cleaned.substringBefore('#').trimEnd('/')
        }
        if (cleaned.isEmpty()) return null

        // Extract host part (before '/', '?', ':')
        val pathIndex = cleaned.indexOfAny(charArrayOf('/', '?'))
        val hostAndPort = if (pathIndex != -1) cleaned.substring(0, pathIndex) else cleaned
        val hostOnly = hostAndPort.split(':').first().trim()
        if (hostOnly.isEmpty()) return null

        // Remove wildcard (*.) or leading dot
        var normalizedHost = hostOnly.lowercase()
        if (normalizedHost.startsWith("*.")) {
            normalizedHost = normalizedHost.removePrefix("*.")
        }
        if (normalizedHost.startsWith(".")) {
            normalizedHost = normalizedHost.removePrefix(".")
        }
        if (normalizedHost.startsWith("www.") && normalizedHost.removePrefix("www.").contains('.')) {
            normalizedHost = normalizedHost.removePrefix("www.")
        }

        if (normalizedHost.isEmpty() || !normalizedHost.contains('.')) {
            return null
        }

        // Convert IDN to Punycode
        val asciiHost = try {
            IDN.toASCII(normalizedHost)
        } catch (_: Exception) {
            return null
        }

        val rootDomain = extractRootDomain(asciiHost)
        if (rootDomain.isEmpty()) return null

        val labels = rootDomain.split('.')
        if (labels.size < 2 || labels.any { !isValidHostLabel(it) }) {
            return null
        }

        return rootDomain
    }

    // Cleans an existing policy string to be compliant with Chromium URL filter format:
    // - Removes wildcard prefixes ('*.') because naked domain covers all subdomains in Chromium.
    // - Removes trailing wildcards (e.g. domain.com/wildcard -> domain.com).
    fun cleanForChromiumAllowlist(rule: String): String {
        var cleaned = rule.trim()
        if (cleaned.startsWith("*.")) {
            cleaned = cleaned.removePrefix("*.")
        } else if (cleaned.startsWith(".")) {
            cleaned = cleaned.removePrefix(".")
        }
        if (cleaned.endsWith("/*")) {
            cleaned = cleaned.removeSuffix("/*")
        }
        return cleaned.trimEnd('/')
    }

    private fun isValidHostLabel(label: String): Boolean {
        if (label.isEmpty() || label.length > 63) return false
        if (label.startsWith('-') || label.endsWith('-')) return false
        if (label.startsWith('_') || label.endsWith('_')) return false
        return label.all { it.isLetterOrDigit() || it == '-' || it == '_' }
    }
}
