package com.drmohammed.antiporn.core.network.dns

import java.net.IDN

/**
 * Domain rule sanitization, smart service expansion, and O(1)/O(k) compiled domain matching.
 * Extracted from [DnsPacketParser] to keep packet codec and rule matching cleanly separated.
 */
internal object DomainRuleMatcher {

    // Smart mapping: If user blocks the primary service, automatically associate its core CDN/auxiliary domains
    private val SMART_SERVICE_EXPANSIONS = mapOf(
        "youtube.com" to setOf("youtube.com", "youtu.be", "googlevideo.com", "ytimg.com", "youtube-nocookie.com", "youtubekids.com"),
        "tiktok.com" to setOf("tiktok.com", "tiktokcdn.com", "tiktokv.com", "musical.ly", "byteoversea.com", "ibytedtos.com"),
        "facebook.com" to setOf("facebook.com", "fbcdn.net", "facebook.net", "fb.com", "messenger.com"),
        "instagram.com" to setOf("instagram.com", "cdninstagram.com")
    )

    /**
     * Sanitizes a domain rule by stripping protocol, port, wildcard (*.), and leading www.
     * If the raw rule contains a URL path, query, or fragment component (e.g. "youtube.com/shorts"),
     * returns "" so DNS filtering ignores path rules (handled by BrowserShield / PageShield).
     */
    fun sanitizeRule(rule: String): String {
        val trimmed = rule.trim().lowercase()
            .removePrefix("https://")
            .removePrefix("http://")

        val withoutTrailingSlash = trimmed.trimEnd('/')
        if (withoutTrailingSlash.contains('/') || withoutTrailingSlash.contains('?') || withoutTrailingSlash.contains('#')) {
            return ""
        }

        val cleaned = withoutTrailingSlash
            .split(':').first()
            .removePrefix("*.")
            .trimStart('.')
            .trimEnd('.')
            .trim()
        val asciiCleaned = if (cleaned.any { it.code > 127 }) {
            try {
                IDN.toASCII(cleaned, IDN.ALLOW_UNASSIGNED).lowercase()
            } catch (_: IllegalArgumentException) {
                cleaned
            }
        } else {
            cleaned
        }
        return if (asciiCleaned.startsWith("www.") && asciiCleaned.removePrefix("www.").contains('.')) {
            asciiCleaned.removePrefix("www.")
        } else {
            asciiCleaned
        }
    }

    /**
     * Pre-compiles raw user rules into a flattened, sanitized set of target domains,
     * including automatic smart service expansions.
     */
    fun compileEffectiveBlockedDomains(rules: Set<String>): Set<String> {
        if (rules.isEmpty()) return emptySet()
        val result = HashSet<String>()
        for (rawRule in rules) {
            val rule = sanitizeRule(rawRule)
            if (rule.isBlank()) continue
            result.add(rule)
            SMART_SERVICE_EXPANSIONS[rule]?.let { expansions ->
                for (exp in expansions) {
                    val sanitizedExp = sanitizeRule(exp)
                    if (sanitizedExp.isNotBlank()) {
                        result.add(sanitizedExp)
                    }
                }
            }
        }
        return result
    }

    /**
     * High-speed O(1) domain match against precompiled blocked domains.
     * Checks exact match and parent subdomains without repetitive string allocations.
     */
    fun isDomainBlockedCompiled(qName: String, compiledBlockedDomains: Set<String>): Boolean {
        if (compiledBlockedDomains.isEmpty()) return false

        val trimmedQuery = qName.trim().lowercase().trimEnd('.')
        val normalizedQuery = if (trimmedQuery.startsWith("www.") && trimmedQuery.removePrefix("www.").contains('.')) {
            trimmedQuery.removePrefix("www.")
        } else {
            trimmedQuery
        }

        if (compiledBlockedDomains.contains(normalizedQuery)) return true

        var dotIndex = normalizedQuery.indexOf('.')
        while (dotIndex != -1 && dotIndex < normalizedQuery.length - 1) {
            val parentDomain = normalizedQuery.substring(dotIndex + 1)
            if (compiledBlockedDomains.contains(parentDomain)) {
                return true
            }
            dotIndex = normalizedQuery.indexOf('.', dotIndex + 1)
        }

        return false
    }

    /**
     * Determines whether [qName] matches any blocked domain rule including subdomains and smart expansions.
     */
    fun isDomainBlocked(qName: String, customBlockedDomains: Set<String>): Boolean {
        if (customBlockedDomains.isEmpty()) return false
        val compiled = compileEffectiveBlockedDomains(customBlockedDomains)
        return isDomainBlockedCompiled(qName, compiled)
    }
}
