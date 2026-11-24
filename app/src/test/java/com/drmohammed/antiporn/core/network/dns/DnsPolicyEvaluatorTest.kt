package com.drmohammed.antiporn.core.network.dns

import com.drmohammed.antiporn.core.model.DevicePolicyBundle
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File

class DnsPolicyEvaluatorTest {

    @Before
    fun setUp() {
        val filterFile = requireNotNull(
            listOf(
                File("src/main/assets/hisn_dns_filter.bin"),
                File("app/src/main/assets/hisn_dns_filter.bin")
            ).firstOrNull { it.exists() }
        ) { "Bundled hisn_dns_filter.bin asset must exist for unit tests" }
        assertTrue("BinaryDomainFilter must initialize cleanly", BinaryDomainFilter.initForTesting(filterFile))
    }

    @After
    fun tearDown() {
        BinaryDomainFilter.resetForTesting()
    }

    private fun mockQuery(domain: String, qType: Int = 1): DnsPacketParser.ParsedDnsQuery {
        return DnsPacketParser.ParsedDnsQuery(
            srcIp = byteArrayOf(10, 0, 0, 1),
            dstIp = byteArrayOf(10, (-1).toByte(), (-1).toByte(), 1),
            srcPort = 54321,
            dstPort = 53,
            ipId = 100,
            transactionId = 0x1234.toShort(),
            qName = domain,
            qType = qType,
            questionSection = ByteArray(0),
            rawDnsPayload = ByteArray(12)
        )
    }

    @Test
    fun testTier0CaptivePortalsAlwaysPass() {
        val policy = DevicePolicyBundle.defaultPolicy().copy(
            browserFilterMode = DevicePolicyBundle.BROWSER_FILTER_WHITELIST_ONLY,
            customAllowedUrls = emptySet()
        )

        val connectivityQuery = mockQuery("connectivitycheck.gstatic.com")
        val action = DnsPolicyEvaluator.evaluate(connectivityQuery, policy, emptySet())
        assertTrue("Captive portal check must pass even under whitelist mode", action is DnsPolicyEvaluator.PolicyAction.Pass)

        val appleQuery = mockQuery("captive.apple.com")
        val appleAction = DnsPolicyEvaluator.evaluate(appleQuery, policy, emptySet())
        assertTrue("Apple captive portal check must pass", appleAction is DnsPolicyEvaluator.PolicyAction.Pass)
    }

    @Test
    fun testTier1UnbypassablePornCannotBeWhitelisted() {
        // Even if guardian attempts to whitelist pornhub.com, Tier 1 MUST block it via BinaryDomainFilter
        val policy = DevicePolicyBundle.defaultPolicy().copy(
            customAllowedUrls = setOf("pornhub.com", "xvideos.com")
        )

        val pornQuery = mockQuery("www.pornhub.com")
        val action = DnsPolicyEvaluator.evaluate(pornQuery, policy, emptySet())
        assertTrue("Pornography domain in binary filter must be unbypassably blocked!", action is DnsPolicyEvaluator.PolicyAction.BlockPorn)
    }

    @Test
    fun testTier1HeuristicPornOverrideByWhitelist() {
        // Heuristic keyword match (not in binary filter) can be explicitly whitelisted by user
        val heuristicDomain = "test-custom-heuristic-porn-site.com"
        assertTrue(PornKeywordMatcher.isAdultDomain(heuristicDomain))

        val policyBlocked = DevicePolicyBundle.defaultPolicy().copy(
            customAllowedUrls = emptySet()
        )
        val actionBlocked = DnsPolicyEvaluator.evaluate(mockQuery(heuristicDomain), policyBlocked, emptySet())
        assertTrue("Non-whitelisted heuristic porn must be blocked", actionBlocked is DnsPolicyEvaluator.PolicyAction.BlockPorn)

        val policyWhitelisted = DevicePolicyBundle.defaultPolicy().copy(
            customAllowedUrls = setOf("https://$heuristicDomain")
        )
        val actionWhitelisted = DnsPolicyEvaluator.evaluate(mockQuery(heuristicDomain), policyWhitelisted, emptySet())
        assertTrue("Domain-level whitelisted heuristic porn must pass (user override)", actionWhitelisted is DnsPolicyEvaluator.PolicyAction.Pass)

        // Path-scoped allow rules MUST NOT whitelist the entire apex domain at DNS level
        val policyPathAllowed = DevicePolicyBundle.defaultPolicy().copy(
            customAllowedUrls = setOf("https://$heuristicDomain/gallery")
        )
        val actionPathAllowed = DnsPolicyEvaluator.evaluate(mockQuery(heuristicDomain), policyPathAllowed, emptySet())
        assertTrue("Path-scoped allow rule must NOT override DNS Tier 1", actionPathAllowed is DnsPolicyEvaluator.PolicyAction.BlockPorn)
    }

    @Test
    fun testTier2TelegramWebBlockedWhenEnabled() {
        val policyBlocked = DevicePolicyBundle.defaultPolicy().copy(isTelegramWebBlocked = true)
        val tgQuery = mockQuery("web.telegram.org")
        val actionBlocked = DnsPolicyEvaluator.evaluate(tgQuery, policyBlocked, emptySet())
        assertTrue("Telegram web must be blocked when policy is true", actionBlocked is DnsPolicyEvaluator.PolicyAction.BlockTelegramWeb)

        val tMeQuery = mockQuery("t.me")
        val actionTme = DnsPolicyEvaluator.evaluate(tMeQuery, policyBlocked, emptySet())
        assertTrue("t.me must be blocked when telegram policy is true", actionTme is DnsPolicyEvaluator.PolicyAction.BlockTelegramWeb)

        val policyAllowed = DevicePolicyBundle.defaultPolicy().copy(isTelegramWebBlocked = false)
        val actionAllowed = DnsPolicyEvaluator.evaluate(tgQuery, policyAllowed, emptySet())
        assertTrue("Telegram web should pass when policy is false", actionAllowed is DnsPolicyEvaluator.PolicyAction.Pass)
    }

    @Test
    fun testTier3WhitelistOnlyFullSystemExemptsGoogleAndCoreServices() {
        val policy = DevicePolicyBundle.defaultPolicy().copy(
            browserFilterMode = DevicePolicyBundle.BROWSER_FILTER_WHITELIST_ONLY,
            isFullSystemFilteringEnabled = true,
            customAllowedUrls = setOf("myuniversity.edu")
        )

        // Core Google and Android services must pass to prevent OS freeze
        assertTrue(DnsPolicyEvaluator.evaluate(mockQuery("play.googleapis.com"), policy, emptySet()) is DnsPolicyEvaluator.PolicyAction.Pass)
        assertTrue(DnsPolicyEvaluator.evaluate(mockQuery("fonts.gstatic.com"), policy, emptySet()) is DnsPolicyEvaluator.PolicyAction.Pass)
        assertTrue(DnsPolicyEvaluator.evaluate(mockQuery("android.clients.google.com"), policy, emptySet()) is DnsPolicyEvaluator.PolicyAction.Pass)
        assertTrue(DnsPolicyEvaluator.evaluate(mockQuery("developer.android.com"), policy, emptySet()) is DnsPolicyEvaluator.PolicyAction.Pass)
        assertTrue(DnsPolicyEvaluator.evaluate(mockQuery("myuniversity.edu"), policy, emptySet()) is DnsPolicyEvaluator.PolicyAction.Pass)

        // Unallowed non-Google and broad Google search services must be blocked
        assertTrue(DnsPolicyEvaluator.evaluate(mockQuery("random-unallowed-service.com"), policy, emptySet()) is DnsPolicyEvaluator.PolicyAction.BlockCustom)
        assertTrue(
            "Apex google.com must NOT be blanket-exempt in Whitelist-Only mode",
            DnsPolicyEvaluator.evaluate(mockQuery("www.google.com"), policy, emptySet()) is DnsPolicyEvaluator.PolicyAction.BlockCustom
        )
    }

    @Test
    fun testTier3BlocklistAndFullSystemFilteringIsolation() {
        val compiledBlocked = DnsPacketParser.compileEffectiveBlockedDomains(setOf("example-blocked.org"))

        // Full system filtering ENABLED + BLOCKLIST -> blocks custom blocked domains
        val fullBlocklistPolicy = DevicePolicyBundle.defaultPolicy().copy(
            isFullSystemFilteringEnabled = true,
            browserFilterMode = DevicePolicyBundle.BROWSER_FILTER_BLOCKLIST,
            customBlockedUrls = setOf("example-blocked.org")
        )
        assertTrue(
            DnsPolicyEvaluator.evaluate(mockQuery("sub.example-blocked.org"), fullBlocklistPolicy, compiledBlocked)
                is DnsPolicyEvaluator.PolicyAction.BlockCustom
        )
        assertTrue(
            DnsPolicyEvaluator.evaluate(mockQuery("wikipedia.org"), fullBlocklistPolicy, compiledBlocked)
                is DnsPolicyEvaluator.PolicyAction.Pass
        )

        // Full system filtering DISABLED (e.g. VPN running only for Local Ad Blocking) -> Tier 3 MUST NOT fire!
        val adOnlyWhitelistPolicy = DevicePolicyBundle.defaultPolicy().copy(
            isFullSystemFilteringEnabled = false,
            isAdBlockingEnabled = true,
            browserFilterMode = DevicePolicyBundle.BROWSER_FILTER_WHITELIST_ONLY,
            customAllowedUrls = setOf("myuniversity.edu")
        )
        assertTrue(
            "Whitelist-only browser rule must NOT block system DNS when isFullSystemFilteringEnabled=false",
            DnsPolicyEvaluator.evaluate(mockQuery("wikipedia.org"), adOnlyWhitelistPolicy, compiledBlocked)
                is DnsPolicyEvaluator.PolicyAction.Pass
        )

        val adOnlyBlocklistPolicy = DevicePolicyBundle.defaultPolicy().copy(
            isFullSystemFilteringEnabled = false,
            isAdBlockingEnabled = true,
            browserFilterMode = DevicePolicyBundle.BROWSER_FILTER_BLOCKLIST,
            customBlockedUrls = setOf("example-blocked.org")
        )
        assertTrue(
            "Blocklist browser rule must NOT block system DNS when isFullSystemFilteringEnabled=false",
            DnsPolicyEvaluator.evaluate(mockQuery("example-blocked.org"), adOnlyBlocklistPolicy, compiledBlocked)
                is DnsPolicyEvaluator.PolicyAction.Pass
        )

        // BROWSER_FILTER_OFF must be a no-op even when isFullSystemFilteringEnabled=true
        val filterOffPolicy = DevicePolicyBundle.defaultPolicy().copy(
            isFullSystemFilteringEnabled = true,
            browserFilterMode = DevicePolicyBundle.BROWSER_FILTER_OFF,
            customBlockedUrls = setOf("example-blocked.org")
        )
        assertTrue(
            "BROWSER_FILTER_OFF must never block custom domains",
            DnsPolicyEvaluator.evaluate(mockQuery("example-blocked.org"), filterOffPolicy, compiledBlocked)
                is DnsPolicyEvaluator.PolicyAction.Pass
        )
    }

    @Test
    fun testTier3WhitelistOnlyAndCustomAllowDoNotBypassYouTubeRestrictedModeOrDoH() {
        val whitelistWithYt = DevicePolicyBundle.defaultPolicy().copy(
            browserFilterMode = DevicePolicyBundle.BROWSER_FILTER_WHITELIST_ONLY,
            isFullSystemFilteringEnabled = true,
            isYouTubeRestrictedModeEnabled = true,
            customAllowedUrls = setOf("www.youtube.com", "youtubei.googleapis.com")
        )
        assertTrue(
            "Whitelisted www.youtube.com must still synthesize YouTube Restricted Mode",
            DnsPolicyEvaluator.evaluate(mockQuery("www.youtube.com"), whitelistWithYt, emptySet()) is DnsPolicyEvaluator.PolicyAction.SynthesizeYouTubeRestricted
        )
        assertTrue(
            "youtubei.googleapis.com must still synthesize YouTube Restricted Mode",
            DnsPolicyEvaluator.evaluate(mockQuery("youtubei.googleapis.com"), whitelistWithYt, emptySet()) is DnsPolicyEvaluator.PolicyAction.SynthesizeYouTubeRestricted
        )

        val blocklistWithYtAllowed = DevicePolicyBundle.defaultPolicy().copy(
            browserFilterMode = DevicePolicyBundle.BROWSER_FILTER_BLOCKLIST,
            isYouTubeRestrictedModeEnabled = true,
            customAllowedUrls = setOf("youtube.com", "dns.google")
        )
        assertTrue(
            "Custom-allowed youtube.com must still enforce Tier 6 Restricted Mode on www.youtube.com",
            DnsPolicyEvaluator.evaluate(mockQuery("www.youtube.com"), blocklistWithYtAllowed, emptySet()) is DnsPolicyEvaluator.PolicyAction.SynthesizeYouTubeRestricted
        )
        assertTrue(
            "Custom-allowed DoH endpoint must still be blocked by Tier 5",
            DnsPolicyEvaluator.evaluate(mockQuery("dns.google"), blocklistWithYtAllowed, emptySet()) is DnsPolicyEvaluator.PolicyAction.BlockDohBypass
        )
    }

    @Test
    fun testTier4AdsFilterRespectsAdBlockingFlag() {
        val doubleclick = "doubleclick.net"
        val filterResult = BinaryDomainFilter.checkDomainWithSubdomains(doubleclick)
        assertEquals("doubleclick.net must exist as BLOCKED_ADS in binary filter", BinaryDomainFilter.FilterResult.BLOCKED_ADS, filterResult)

        // When ad blocking disabled: should pass
        val policyDisabled = DevicePolicyBundle.defaultPolicy().copy(isAdBlockingEnabled = false)
        val actionDisabled = DnsPolicyEvaluator.evaluate(mockQuery(doubleclick), policyDisabled, emptySet())
        assertTrue("Ads should pass when isAdBlockingEnabled is false", actionDisabled is DnsPolicyEvaluator.PolicyAction.Pass)

        // When ad blocking enabled: should block
        val policyEnabled = DevicePolicyBundle.defaultPolicy().copy(isAdBlockingEnabled = true)
        val actionEnabled = DnsPolicyEvaluator.evaluate(mockQuery(doubleclick), policyEnabled, emptySet())
        assertTrue("Ads should be blocked when isAdBlockingEnabled is true", actionEnabled is DnsPolicyEvaluator.PolicyAction.BlockAds)

        // When explicitly whitelisted by user: should pass
        val policyWhitelisted = policyEnabled.copy(customAllowedUrls = setOf(doubleclick))
        val actionWhitelisted = DnsPolicyEvaluator.evaluate(mockQuery(doubleclick), policyWhitelisted, emptySet())
        assertTrue("Explicitly whitelisted ad domain should pass", actionWhitelisted is DnsPolicyEvaluator.PolicyAction.Pass)

        // Essential deep-link domains must pass even when ad blocking is enabled
        val deepLinkAction = DnsPolicyEvaluator.evaluate(mockQuery("example.app.link"), policyEnabled, emptySet())
        assertTrue("Essential deep-link domain must pass", deepLinkAction is DnsPolicyEvaluator.PolicyAction.Pass)
    }

    @Test
    fun testTier5DoHCanaryBlocked() {
        val policy = DevicePolicyBundle.defaultPolicy()
        val canaryQuery = mockQuery("use-application-dns.net")
        val action = DnsPolicyEvaluator.evaluate(canaryQuery, policy, emptySet())
        assertTrue("DoH canary domain must be blocked", action is DnsPolicyEvaluator.PolicyAction.BlockDohBypass)

        val icloudQuery = mockQuery("mask.icloud.com")
        val icloudAction = DnsPolicyEvaluator.evaluate(icloudQuery, policy, emptySet())
        assertTrue("Apple Private Relay must be blocked", icloudAction is DnsPolicyEvaluator.PolicyAction.BlockDohBypass)

        for (dohHost in listOf("one.one.one.one", "dns.nextdns.io", "_dns.resolver.arpa", "dns.adguard-dns.com")) {
            val dohAction = DnsPolicyEvaluator.evaluate(mockQuery(dohHost), policy, emptySet())
            assertTrue("Expanded DoH bypass domain $dohHost must be blocked", dohAction is DnsPolicyEvaluator.PolicyAction.BlockDohBypass)
        }
    }

    @Test
    fun testTier6YouTubeRestrictedModeRewrite() {
        val policyEnabled = DevicePolicyBundle.defaultPolicy().copy(isYouTubeRestrictedModeEnabled = true)

        // The 5 official Google Restricted Mode hostnames must be synthesized
        val officialDomains = listOf(
            "www.youtube.com",
            "m.youtube.com",
            "youtubei.googleapis.com",
            "youtube.googleapis.com",
            "www.youtube-nocookie.com"
        )
        for (domain in officialDomains) {
            val action = DnsPolicyEvaluator.evaluate(mockQuery(domain, qType = 1), policyEnabled, emptySet())
            assertTrue("Domain $domain must trigger Restricted Mode synthesis", action is DnsPolicyEvaluator.PolicyAction.SynthesizeYouTubeRestricted)
        }

        // Apex youtube.com and youtu.be must NOT be rewritten
        val apexAction = DnsPolicyEvaluator.evaluate(mockQuery("youtube.com", qType = 1), policyEnabled, emptySet())
        assertTrue("Apex youtube.com must NOT trigger restricted synthesis", apexAction is DnsPolicyEvaluator.PolicyAction.Pass)

        val youtuBeAction = DnsPolicyEvaluator.evaluate(mockQuery("youtu.be", qType = 1), policyEnabled, emptySet())
        assertTrue("youtu.be must NOT trigger restricted synthesis", youtuBeAction is DnsPolicyEvaluator.PolicyAction.Pass)

        val policyDisabled = DevicePolicyBundle.defaultPolicy().copy(isYouTubeRestrictedModeEnabled = false)
        val actionDisabled = DnsPolicyEvaluator.evaluate(mockQuery("www.youtube.com", qType = 1), policyDisabled, emptySet())
        assertTrue("YouTube domain should pass when Restricted Mode is false", actionDisabled is DnsPolicyEvaluator.PolicyAction.Pass)
    }

    @Test
    fun testTier3Whitelist_allowsPathUrlsAndEducationalExemptions() {
        val policy = DevicePolicyBundle.defaultPolicy().copy(
            isFullSystemFilteringEnabled = true,
            browserFilterMode = DevicePolicyBundle.BROWSER_FILTER_WHITELIST_ONLY,
            customAllowedUrls = setOf("platform.edu/login", "mansoura.edu.eg/courses")
        )

        // Platform with path in rule must resolve base domain and subdomains on DNS
        val platformAction = DnsPolicyEvaluator.evaluate(mockQuery("platform.edu"), policy, emptySet())
        assertTrue("Base domain must pass even if raw rule contains a path", platformAction is DnsPolicyEvaluator.PolicyAction.Pass)

        val subPlatformAction = DnsPolicyEvaluator.evaluate(mockQuery("student.platform.edu"), policy, emptySet())
        assertTrue("Subdomain must pass when parent domain is in allowlist", subPlatformAction is DnsPolicyEvaluator.PolicyAction.Pass)

        val mansouraAction = DnsPolicyEvaluator.evaluate(mockQuery("student.mansoura.edu.eg"), policy, emptySet())
        assertTrue("Compound TLD subdomain must pass", mansouraAction is DnsPolicyEvaluator.PolicyAction.Pass)

        // Educational streaming CDNs must pass automatically in Whitelist mode
        val bunnyAction = DnsPolicyEvaluator.evaluate(mockQuery("iframe.mediadelivery.net"), policy, emptySet())
        assertTrue("Bunny video player must pass in Whitelist mode", bunnyAction is DnsPolicyEvaluator.PolicyAction.Pass)

        val vdoCipherAction = DnsPolicyEvaluator.evaluate(mockQuery("player.vdocipher.com"), policy, emptySet())
        assertTrue("VdoCipher DRM player must pass in Whitelist mode", vdoCipherAction is DnsPolicyEvaluator.PolicyAction.Pass)

        // Non-allowed domain must be blocked
        val blockedAction = DnsPolicyEvaluator.evaluate(mockQuery("randomunallowedwebsite.com"), policy, emptySet())
        assertTrue("Non-allowed domain must be blocked by custom rule in Whitelist mode", blockedAction is DnsPolicyEvaluator.PolicyAction.BlockCustom)
    }

    @Test
    fun testTier5DohBypassDomains_expandedListBlocked() {
        val policy = DevicePolicyBundle.defaultPolicy()
        val hardenedDomains = listOf(
            "dns9.quad9.net",
            "dns10.quad9.net",
            "dns11.quad9.net",
            "doh.umbrella.com",
            "unfiltered.adguard-dns.com",
            "dns-unfiltered.adguard.com",
            "p0.freedns.controld.com",
            "p1.freedns.controld.com",
            "p2.freedns.controld.com",
            "uncensored.freedns.controld.com",
            "base.dns.mullvad.net",
            "doh.pub",
            "dns.alidns.com",
            "dns.switch.ch",
            "doh.applied-privacy.net",
            "dns.digitale-gesellschaft.ch",
            "common.dot.dns.yandex.net"
        )
        for (domain in hardenedDomains) {
            assertTrue("Domain $domain must be in DOH_BYPASS_DOMAINS", DnsPolicyEvaluator.DOH_BYPASS_DOMAINS.contains(domain))
            val action = DnsPolicyEvaluator.evaluate(mockQuery(domain), policy, emptySet())
            assertEquals("DoH bypass domain $domain must result in BlockDohBypass", DnsPolicyEvaluator.PolicyAction.BlockDohBypass, action)
        }
    }

    @Test
    fun testTier5DohBypassDomains_exemptsConfiguredPolicyDnsHost() {
        val policy = DevicePolicyBundle.defaultPolicy().copy(dnsHost = "1bb7e3.dns.nextdns.io")
        assertEquals(
            DnsPolicyEvaluator.PolicyAction.Pass,
            DnsPolicyEvaluator.evaluate(mockQuery("1bb7e3.dns.nextdns.io"), policy, emptySet())
        )
        assertEquals(
            DnsPolicyEvaluator.PolicyAction.BlockDohBypass,
            DnsPolicyEvaluator.evaluate(mockQuery("dns.nextdns.io"), policy, emptySet())
        )
        assertEquals(
            DnsPolicyEvaluator.PolicyAction.BlockDohBypass,
            DnsPolicyEvaluator.evaluate(mockQuery("other.dns.nextdns.io"), policy, emptySet())
        )
    }
}
