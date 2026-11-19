package com.drmohammed.antiporn.core.network.dns

import com.drmohammed.antiporn.core.model.DevicePolicyBundle
import com.drmohammed.antiporn.core.util.BrowserUrlSanitizer

/**
 * Deterministic 6-tier security policy evaluator for local DNS inspection.
 *
 * Evaluation Hierarchy:
 * - Tier 0: Captive Portals & Google System Essentials (Always Pass).
 * - Tier 1: Unbypassable Adult Content (PornKeywordMatcher + BinaryDomainFilter PORN).
 *           Cannot be overridden by custom whitelists.
 * - Tier 2: Telegram Web Shield (t.me, web.telegram.org) when enabled.
 * - Tier 3: Custom Guardian/User Rules (Blocklist / Whitelist-only mode).
 * - Tier 4: Local Ads & Trackers Filter (BinaryDomainFilter ADS).
 * - Tier 5: Anti-Bypass DoH Canary & Apple Private Relay blocking.
 * - Tier 6: YouTube Restricted Mode (SafeSearch VIP response) when enabled.
 */
object DnsPolicyEvaluator {

    sealed class PolicyAction {
        object Pass : PolicyAction()
        object BlockPorn : PolicyAction()
        object BlockAds : PolicyAction()
        object BlockTelegramWeb : PolicyAction()
        object BlockCustom : PolicyAction()
        object BlockDohBypass : PolicyAction()
        object SynthesizeYouTubeRestricted : PolicyAction()
    }

    val TELEGRAM_WEB_DOMAINS: Set<String> = setOf(
        "telegram.org",
        "web.telegram.org",
        "webk.telegram.org",
        "webz.telegram.org",
        "t.me",
        "telegram.me",
        "telegram.dog",
        "telegra.ph",
        "graph.org",
        "telesco.pe"
    )

    val DOH_BYPASS_DOMAINS: Set<String> = setOf(
        "use-application-dns.net",
        "mask.icloud.com",
        "mask-h2.icloud.com",
        "dns.google",
        "dns.google.com",
        "cloudflare-dns.com",
        "one.one.one.one",
        "dns.quad9.net",
        "doh.opendns.com",
        "dns.adguard-dns.com",
        "dns.nextdns.io",
        "dns.mullvad.net",
        "doh.dns.sb",
        "_dns.resolver.arpa"
    )

    val YOUTUBE_DOMAINS: Set<String> = setOf(
        "www.youtube.com",
        "m.youtube.com",
        "youtubei.googleapis.com",
        "youtube.googleapis.com",
        "www.youtube-nocookie.com"
    )

    val CORE_GOOGLE_SYSTEM_DOMAINS: Set<String> = setOf(
        "mtalk.google.com",
        "fcm.googleapis.com",
        "firebaseinstallations.googleapis.com",
        "android.clients.google.com",
        "play.googleapis.com",
        "accounts.google.com",
        "clients3.google.com",
        "gstatic.com",
        "android.com"
    )

    val ESSENTIAL_DEEP_LINK_DOMAINS: Set<String> = setOf(
        "branch.io",
        "app.link",
        "adjust.com",
        "appsflyer.com",
        "kochava.com",
        "singular.net"
    )

    private val SANITIZED_TELEGRAM_WEB_DOMAINS: Set<String> =
        TELEGRAM_WEB_DOMAINS.mapNotNull { DnsPacketParser.sanitizeRule(it).takeIf { s -> s.contains('.') } }.toSet()

    private val SANITIZED_DOH_BYPASS_DOMAINS: Set<String> =
        DOH_BYPASS_DOMAINS.mapNotNull { DnsPacketParser.sanitizeRule(it).takeIf { s -> s.contains('.') } }.toSet()

    private val SANITIZED_ESSENTIAL_DEEP_LINK_DOMAINS: Set<String> =
        ESSENTIAL_DEEP_LINK_DOMAINS.mapNotNull { DnsPacketParser.sanitizeRule(it).takeIf { s -> s.contains('.') } }.toSet()

    /**
     * Evaluates a parsed DNS query against the active device policy bundle.
     */
    fun evaluate(
        query: DnsPacketParser.ParsedDnsQuery,
        policy: DevicePolicyBundle,
        compiledCustomBlocked: Set<String>
    ): PolicyAction {
        val qName = query.qName.trim().lowercase().trimEnd('.')

        // Tier 0: Captive Portals & Core OS Connectivity
        if (isCaptivePortalOrExempt(qName)) {
            return PolicyAction.Pass
        }

        // Hoist binary filter lookup once for Tier 1 (Porn) and Tier 4 (Ads)
        val binaryResult = BinaryDomainFilter.checkDomainWithSubdomains(qName)

        // Tier 1: Unbypassable Adult Content (BinaryDomainFilter PORN is immutable;
        // PornKeywordMatcher overridable by explicit domain-level customAllowedUrls without skipping later tiers)
        if (binaryResult == BinaryDomainFilter.FilterResult.BLOCKED_PORN) {
            return PolicyAction.BlockPorn
        }
        if (PornKeywordMatcher.isAdultDomain(qName) && !isDomainInSet(qName, policy.customAllowedUrls)) {
            return PolicyAction.BlockPorn
        }

        // Tier 2: Telegram Web Shield
        if (policy.isTelegramWebBlocked && isDomainInSanitizedSet(qName, SANITIZED_TELEGRAM_WEB_DOMAINS)) {
            return PolicyAction.BlockTelegramWeb
        }

        // Tier 3: Guardian/User Custom Rules (strictly gated on full-system filtering; never short-circuit before Tiers 5 & 6)
        if (policy.isFullSystemFilteringEnabled) {
            when (policy.browserFilterMode) {
                DevicePolicyBundle.BROWSER_FILTER_WHITELIST_ONLY -> {
                    val isSystemExempt = isCoreGoogleSystemExempt(qName)
                    val isEducationalExempt = isEducationalDnsExempt(qName)
                    if (!isSystemExempt && !isEducationalExempt && !isDomainInSet(qName, policy.customAllowedUrls, isAllowlist = true)) {
                        return PolicyAction.BlockCustom
                    }
                }
                DevicePolicyBundle.BROWSER_FILTER_BLOCKLIST -> {
                    if (DnsPacketParser.isDomainBlockedCompiled(qName, compiledCustomBlocked)) {
                        return PolicyAction.BlockCustom
                    }
                }
                DevicePolicyBundle.BROWSER_FILTER_OFF -> Unit
            }
        }

        // Tier 4: Local Ads & Trackers Filter (never short-circuit Pass before Tiers 5 & 6)
        if (policy.isAdBlockingEnabled &&
            binaryResult == BinaryDomainFilter.FilterResult.BLOCKED_ADS
        ) {
            if (!isDomainInSanitizedSet(qName, SANITIZED_ESSENTIAL_DEEP_LINK_DOMAINS) &&
                !isDomainInSet(qName, policy.customAllowedUrls, isAllowlist = true)
            ) {
                return PolicyAction.BlockAds
            }
        }

        // Tier 5: Anti-Bypass DoH Canary & Encrypted Relay Blocking
        if (isDomainInSanitizedSet(qName, SANITIZED_DOH_BYPASS_DOMAINS)) {
            return PolicyAction.BlockDohBypass
        }

        // Tier 6: YouTube Restricted Mode VIP Synthesis
        if (policy.isYouTubeRestrictedModeEnabled && isYouTubeRestrictedDomain(qName)) {
            return PolicyAction.SynthesizeYouTubeRestricted
        }

        return PolicyAction.Pass
    }

    private val CORE_CONNECTIVITY_PROBES: Set<String> = setOf(
        "connectivitycheck.gstatic.com",
        "connectivitycheck.android.com",
        "clients3.google.com",
        "captive.apple.com",
        "msftconnecttest.com"
    )

    private fun isCoreGoogleSystemExempt(qName: String): Boolean {
        for (domain in CORE_GOOGLE_SYSTEM_DOMAINS) {
            if (qName == domain || qName.endsWith(".$domain")) {
                return true
            }
        }
        return false
    }

    private fun isEducationalDnsExempt(qName: String): Boolean {
        for (domain in BrowserUrlSanitizer.EDUCATIONAL_DNS_DOMAINS) {
            if (qName == domain || qName.endsWith(".$domain")) {
                return true
            }
        }
        return false
    }

    private fun isYouTubeRestrictedDomain(qName: String): Boolean {
        if (YOUTUBE_DOMAINS.contains(qName)) return true
        for (target in YOUTUBE_DOMAINS) {
            if (qName.endsWith(".$target")) {
                return true
            }
        }
        return false
    }

    private fun isCaptivePortalOrExempt(qName: String): Boolean {
        if (CORE_CONNECTIVITY_PROBES.contains(qName)) return true
        for (exempt in BrowserUrlSanitizer.CAPTIVE_PORTAL_EXEMPTIONS) {
            if (qName == exempt || qName.endsWith(".$exempt")) {
                return true
            }
        }
        return false
    }

    private fun isDomainInSanitizedSet(normalizedQName: String, sanitizedSet: Set<String>): Boolean {
        if (sanitizedSet.isEmpty()) return false
        val noWwwQuery = if (normalizedQName.startsWith("www.") && normalizedQName.removePrefix("www.").contains('.')) {
            normalizedQName.removePrefix("www.")
        } else {
            normalizedQName
        }
        if (sanitizedSet.contains(normalizedQName) || sanitizedSet.contains(noWwwQuery)) {
            return true
        }
        var dotIndex = noWwwQuery.indexOf('.')
        while (dotIndex != -1 && dotIndex < noWwwQuery.length - 1) {
            if (sanitizedSet.contains(noWwwQuery.substring(dotIndex + 1))) {
                return true
            }
            dotIndex = noWwwQuery.indexOf('.', dotIndex + 1)
        }
        return false
    }

    private fun isDomainInSet(qName: String, domainSet: Set<String>, isAllowlist: Boolean = false): Boolean {
        if (domainSet.isEmpty()) return false
        val normalizedQName = qName.trim().lowercase().trimEnd('.')
        val noWwwQuery = if (normalizedQName.startsWith("www.") && normalizedQName.removePrefix("www.").contains('.')) {
            normalizedQName.removePrefix("www.")
        } else {
            normalizedQName
        }

        for (target in domainSet) {
            val cleanTarget = if (isAllowlist) {
                DnsPacketParser.extractHostForAllowlist(target)
            } else {
                DnsPacketParser.sanitizeRule(target)
            }
            if (cleanTarget.isBlank() || !cleanTarget.contains('.')) continue

            if (normalizedQName == cleanTarget || normalizedQName.endsWith(".$cleanTarget")) {
                return true
            }
            if (noWwwQuery == cleanTarget || noWwwQuery.endsWith(".$cleanTarget")) {
                return true
            }
        }
        return false
    }
}
