package com.drmohammed.antiporn.pageshield

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import com.drmohammed.antiporn.core.model.DevicePolicyBundle
import com.drmohammed.antiporn.pageshield.engine.PageShieldFreezeStore
import com.drmohammed.antiporn.pageshield.model.AppPageShieldPolicy
import com.drmohammed.antiporn.pageshield.model.PageShieldConfig
import com.drmohammed.antiporn.pageshield.model.PageShieldReaction
import com.drmohammed.antiporn.pageshield.registry.ActivityClassStrategy
import com.drmohammed.antiporn.pageshield.registry.CompositePageStrategy
import com.drmohammed.antiporn.pageshield.registry.PageDetectorStrategy
import com.drmohammed.antiporn.pageshield.registry.PageShieldRegistry
import com.drmohammed.antiporn.pageshield.registry.StrategyOperator
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PageShieldUnitTest {

    @Test
    fun testDefaultPageShieldConfig() {
        val config = PageShieldConfig()
        assertFalse(config.isEnabled)
        assertTrue(config.appPolicies.isEmpty())
        assertTrue(config.getTargetPackages().isEmpty())
        assertFalse(config.hasActiveRulesForPackage(PageShieldRegistry.PACKAGE_YOUTUBE))
    }

    @Test
    fun testAppPageShieldPolicySerialization() {
        val policy = AppPageShieldPolicy(
            packageName = "com.google.android.youtube",
            enabledPageIds = setOf("youtube_shorts"),
            reaction = PageShieldReaction.EXIT_AND_FREEZE,
            freezeDurationMinutes = 25
        )

        val map = policy.toMap()
        val restoredFromMap = AppPageShieldPolicy.fromMap(map)
        assertNotNull(restoredFromMap)
        assertEquals(policy.packageName, restoredFromMap?.packageName)
        assertEquals(policy.enabledPageIds, restoredFromMap?.enabledPageIds)
        assertEquals(policy.reaction, restoredFromMap?.reaction)
        assertEquals(policy.freezeDurationMinutes, restoredFromMap?.freezeDurationMinutes)

        val json = policy.toJson()
        val restoredFromJson = AppPageShieldPolicy.fromJson(json)
        assertNotNull(restoredFromJson)
        assertEquals(policy.packageName, restoredFromJson?.packageName)
        assertEquals(policy.enabledPageIds, restoredFromJson?.enabledPageIds)
        assertEquals(policy.reaction, restoredFromJson?.reaction)
        assertEquals(policy.freezeDurationMinutes, restoredFromJson?.freezeDurationMinutes)
    }

    @Test
    fun testPageShieldConfigRoundtrip() {
        val appPolicy = AppPageShieldPolicy(
            packageName = "com.google.android.youtube",
            enabledPageIds = setOf("youtube_shorts"),
            reaction = PageShieldReaction.EXIT_AND_FREEZE,
            freezeDurationMinutes = 30
        )
        val config = PageShieldConfig(
            isEnabled = true,
            appPolicies = mapOf(appPolicy.packageName to appPolicy)
        )

        val json = config.toJson()
        val restored = PageShieldConfig.fromJson(json)

        assertTrue(restored.isEnabled)
        assertEquals(1, restored.appPolicies.size)
        assertTrue(restored.hasActiveRulesForPackage("com.google.android.youtube"))
        assertEquals(setOf("com.google.android.youtube"), restored.getTargetPackages())

        val restoredPolicy = restored.appPolicies["com.google.android.youtube"]
        assertNotNull(restoredPolicy)
        assertEquals(PageShieldReaction.EXIT_AND_FREEZE, restoredPolicy?.reaction)
        assertEquals(30, restoredPolicy?.freezeDurationMinutes)
    }

    @Test
    fun testDevicePolicyBundleBackwardCompatibility() {
        // An old JSON representation without pageShieldConfig
        val legacyJsonStr = """
            {
                "version": 1,
                "isCurfewEnabled": false,
                "dnsHost": "family.cloudflare-dns.com"
            }
        """.trimIndent()

        val parsed = DevicePolicyBundle.fromJson(legacyJsonStr)
        assertNotNull(parsed)
        assertFalse(parsed.pageShieldConfig.isEnabled)
        assertTrue(parsed.pageShieldConfig.appPolicies.isEmpty())
        assertTrue(parsed.selfControlPageShieldAllowed)

        // Explicit false in JSON must still be honored
        val explicitFalseParsed = DevicePolicyBundle.fromJson("""{"selfControlPageShieldAllowed": false}""")
        assertFalse(explicitFalseParsed.selfControlPageShieldAllowed)

        // Bundle roundtrip with active PageShieldConfig
        val activePolicy = AppPageShieldPolicy(
            packageName = PageShieldRegistry.PACKAGE_YOUTUBE,
            enabledPageIds = setOf(PageShieldRegistry.PAGE_YOUTUBE_SHORTS),
            reaction = PageShieldReaction.EXIT_ONLY
        )
        val activeConfig = PageShieldConfig(
            isEnabled = true,
            appPolicies = mapOf(PageShieldRegistry.PACKAGE_YOUTUBE to activePolicy)
        )
        val bundleWithShield = parsed.copy(
            pageShieldConfig = activeConfig,
            selfControlPageShieldAllowed = true
        )

        val roundtripJson = bundleWithShield.toJson()
        val roundtripRestored = DevicePolicyBundle.fromJson(roundtripJson)

        assertTrue(roundtripRestored.pageShieldConfig.isEnabled)
        assertTrue(roundtripRestored.selfControlPageShieldAllowed)
        assertTrue(roundtripRestored.pageShieldConfig.hasActiveRulesForPackage(PageShieldRegistry.PACKAGE_YOUTUBE))
    }

    @Test
    fun testPageShieldRegistryOpenClosed() {
        val ytApp = PageShieldRegistry.findApp(PageShieldRegistry.PACKAGE_YOUTUBE)
        assertNotNull(ytApp)
        assertEquals(PageShieldRegistry.PACKAGE_YOUTUBE, ytApp?.packageName)

        val shortsPage = PageShieldRegistry.findPage(
            PageShieldRegistry.PACKAGE_YOUTUBE,
            PageShieldRegistry.PAGE_YOUTUBE_SHORTS
        )
        assertNotNull(shortsPage)
        assertEquals(PageShieldRegistry.PAGE_YOUTUBE_SHORTS, shortsPage?.pageId)

        // Facebook App & Reels verification
        val fbApp = PageShieldRegistry.findApp(PageShieldRegistry.PACKAGE_FACEBOOK)
        assertNotNull(fbApp)
        assertEquals(PageShieldRegistry.PACKAGE_FACEBOOK, fbApp?.packageName)

        val reelsPage = PageShieldRegistry.findPage(
            PageShieldRegistry.PACKAGE_FACEBOOK,
            PageShieldRegistry.PAGE_FACEBOOK_REELS
        )
        assertNotNull(reelsPage)
        assertEquals(PageShieldRegistry.PAGE_FACEBOOK_REELS, reelsPage?.pageId)
        assertTrue(PageShieldRegistry.registeredApps.size >= 2)

        // Non-registered package should safely return null
        assertNull(PageShieldRegistry.findApp("com.unregistered.app"))
        assertNull(PageShieldRegistry.findPage("com.unregistered.app", "any_page"))
    }

    @Test
    fun testCompositePageStrategy() {
        val trueStrategy = object : PageDetectorStrategy {
            override fun isPageDetected(service: AccessibilityService, event: AccessibilityEvent?, screenWidth: Int, screenHeight: Int): Boolean = true
        }
        val falseStrategy = object : PageDetectorStrategy {
            override fun isPageDetected(service: AccessibilityService, event: AccessibilityEvent?, screenWidth: Int, screenHeight: Int): Boolean = false
        }

        // Dummy service and event are not touched by these stub strategies
        @Suppress("CAST_NEVER_SUCCEEDS")
        val dummyService = null as? AccessibilityService ?: run {
            // Using reflection or simple mock-less stub
            val anyComposite = CompositePageStrategy(trueStrategy, falseStrategy, StrategyOperator.ANY)
            val allComposite = CompositePageStrategy(trueStrategy, falseStrategy, StrategyOperator.ALL)
            val excludeComposite = CompositePageStrategy(trueStrategy, falseStrategy, StrategyOperator.EXCLUDE)

            // When testing strategy logic, verify operator truth tables
            assertTrue(anyComposite.isPageDetected(fakeService(), null, 1080, 2400))
            assertFalse(allComposite.isPageDetected(fakeService(), null, 1080, 2400))
            assertTrue(excludeComposite.isPageDetected(fakeService(), null, 1080, 2400))
        }
    }

    @Test
    fun testFrozenAppRecordExpiration() {
        val freezeStart = 1_000_000L
        val freezeEnd = freezeStart + (15 * 60_000L) // 15 mins later = 1_900_000L

        val record = PageShieldFreezeStore.FrozenAppRecord(
            packageName = "com.google.android.youtube",
            appName = "YouTube",
            freezeStartEpochMs = freezeStart,
            freezeEndEpochMs = freezeEnd,
            durationMinutes = 15
        )

        assertFalse(record.isExpired(1_500_000L))
        assertEquals(400_000L, record.remainingMs(1_500_000L))

        assertTrue(record.isExpired(1_900_000L))
        assertEquals(0L, record.remainingMs(1_900_000L))

        assertTrue(record.isExpired(2_000_000L))
        assertEquals(0L, record.remainingMs(2_000_000L))
    }

    @Test
    fun testAppPageShieldPolicyValidationBounds() {
        // Valid duration within [1, 1440]
        val valid = AppPageShieldPolicy(
            packageName = "com.example.app",
            freezeDurationMinutes = 60
        )
        assertEquals(60, valid.freezeDurationMinutes)

        // Lower boundary
        val min = AppPageShieldPolicy(
            packageName = "com.example.app",
            freezeDurationMinutes = 1
        )
        assertEquals(1, min.freezeDurationMinutes)

        // Upper boundary
        val max = AppPageShieldPolicy(
            packageName = "com.example.app",
            freezeDurationMinutes = 1440
        )
        assertEquals(1440, max.freezeDurationMinutes)
    }

    @Test
    fun testSelfControlSectionPageShieldGate() {
        val allowedPolicy = DevicePolicyBundle.defaultPolicy().copy(
            isSupervised = true,
            isSelfControlAllowed = true,
            selfControlPageShieldAllowed = true
        )
        val deniedPolicy = DevicePolicyBundle.defaultPolicy().copy(
            isSupervised = true,
            isSelfControlAllowed = true,
            selfControlPageShieldAllowed = false
        )

        val allowed = com.drmohammed.antiporn.ui.control.gate.SelfControlAccessGateRegistry.isSectionPermitted(
            policy = allowedPolicy,
            section = com.drmohammed.antiporn.ui.control.gate.SelfControlSection.PAGE_SHIELD
        )
        val denied = com.drmohammed.antiporn.ui.control.gate.SelfControlAccessGateRegistry.isSectionPermitted(
            policy = deniedPolicy,
            section = com.drmohammed.antiporn.ui.control.gate.SelfControlSection.PAGE_SHIELD
        )

        assertTrue(allowed)
        assertFalse(denied)
    }

    private fun fakeService(): AccessibilityService {
        return object : AccessibilityService() {
            override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
            override fun onInterrupt() {}
        }
    }
}
