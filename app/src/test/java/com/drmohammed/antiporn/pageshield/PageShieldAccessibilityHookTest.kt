package com.drmohammed.antiporn.pageshield

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import com.drmohammed.antiporn.accessibility.control.AccessibilityRequirementReason
import com.drmohammed.antiporn.accessibility.core.ModuleDispatchResult
import com.drmohammed.antiporn.core.model.DevicePolicyBundle
import com.drmohammed.antiporn.pageshield.engine.PageShieldAccessibilityHook
import com.drmohammed.antiporn.pageshield.model.AppPageShieldPolicy
import com.drmohammed.antiporn.pageshield.model.PageShieldConfig
import com.drmohammed.antiporn.pageshield.registry.PageShieldRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import sun.misc.Unsafe

class PageShieldAccessibilityHookTest {

    private class ModuleHarness(
        provideActiveService: Boolean = false
    ) {
        var refreshCallbackCount: Int = 0
            private set
        var lastRefreshedService: AccessibilityService? = null
            private set
        var nowMs: Long = 10_000L
        var eventPackageName: String? = PageShieldRegistry.PACKAGE_YOUTUBE
        var isPasswordEvent: Boolean = false
        var detectedResult: Boolean = false
        var detectorCalls: Int = 0
            private set
        var reactionCalls: Int = 0
            private set
        var lastReactionPageId: String? = null
            private set

        val service: AccessibilityService = object : AccessibilityService() {
            override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
            override fun onInterrupt() {}
        }

        val hook: PageShieldAccessibilityHook = PageShieldAccessibilityHook(
            activeServiceProvider = { if (provideActiveService) service else null },
            refreshCapabilitiesCallback = { svc ->
                refreshCallbackCount++
                lastRefreshedService = svc
            },
            clockMs = { nowMs },
            eventPackageNameProvider = { eventPackageName },
            isPasswordEventProvider = { isPasswordEvent },
            pageDetector = { _, _, _, _, _, _ ->
                detectorCalls++
                detectedResult
            },
            reactionExecutor = { _, _, pageId, _ ->
                reactionCalls++
                lastReactionPageId = pageId
            },
            displaySizeProvider = { 1080 to 2400 }
        )
    }

    private fun allocateDummyEvent(): AccessibilityEvent {
        val field = Unsafe::class.java.getDeclaredField("theUnsafe")
        field.isAccessible = true
        val unsafe = field.get(null) as Unsafe
        return unsafe.allocateInstance(AccessibilityEvent::class.java) as AccessibilityEvent
    }

    private val createdHooks = mutableListOf<PageShieldAccessibilityHook>()

    @After
    fun tearDown() {
        createdHooks.forEach { it.onServiceDisconnected() }
        createdHooks.clear()
    }

    private fun createHarness(provideActiveService: Boolean = false): ModuleHarness {
        return ModuleHarness(provideActiveService).also {
            createdHooks.add(it.hook)
        }
    }

    private fun youtubeEnabledConfig(): PageShieldConfig = PageShieldConfig(
        isEnabled = true,
        appPolicies = mapOf(
            PageShieldRegistry.PACKAGE_YOUTUBE to AppPageShieldPolicy(
                packageName = PageShieldRegistry.PACKAGE_YOUTUBE,
                enabledPageIds = setOf(PageShieldRegistry.PAGE_YOUTUBE_SHORTS)
            )
        )
    )

    @Test
    fun moduleMetadata_matchesPageShieldContract() {
        val harness = createHarness()

        assertEquals("page_shield", harness.hook.moduleId)
        assertEquals(30, harness.hook.priority)
        assertEquals(AccessibilityRequirementReason.PAGE_SHIELD, harness.hook.requirementReason)
    }

    @Test
    fun isRequired_reflectsEnabledFlagAndNonEmptyRules() {
        val harness = createHarness()

        val disabledPolicy = DevicePolicyBundle.defaultPolicy().copy(
            pageShieldConfig = PageShieldConfig(
                isEnabled = false,
                appPolicies = youtubeEnabledConfig().appPolicies
            )
        )
        assertFalse(harness.hook.isRequired(disabledPolicy, sdkInt = 34))

        val emptyPolicies = DevicePolicyBundle.defaultPolicy().copy(
            pageShieldConfig = PageShieldConfig(isEnabled = true, appPolicies = emptyMap())
        )
        assertFalse(harness.hook.isRequired(emptyPolicies, sdkInt = 34))

        val emptyPageIds = DevicePolicyBundle.defaultPolicy().copy(
            pageShieldConfig = PageShieldConfig(
                isEnabled = true,
                appPolicies = mapOf(
                    PageShieldRegistry.PACKAGE_YOUTUBE to AppPageShieldPolicy(
                        packageName = PageShieldRegistry.PACKAGE_YOUTUBE,
                        enabledPageIds = emptySet()
                    )
                )
            )
        )
        assertFalse(harness.hook.isRequired(emptyPageIds, sdkInt = 34))

        val activePolicy = DevicePolicyBundle.defaultPolicy().copy(
            pageShieldConfig = youtubeEnabledConfig()
        )
        assertTrue(harness.hook.isRequired(activePolicy, sdkInt = 33))
        assertTrue(harness.hook.isRequired(activePolicy, sdkInt = 34))
    }

    @Test
    fun requiredEventTypes_returnsInAppEventsOnlyForConfiguredTargets() {
        val harness = createHarness()

        assertEquals(0, harness.hook.requiredEventTypes(PageShieldRegistry.PACKAGE_YOUTUBE))
        assertEquals(0, harness.hook.requiredEventTypes(null))

        harness.hook.updateConfig(youtubeEnabledConfig())

        val expectedEvents = AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
            AccessibilityEvent.TYPE_VIEW_CLICKED or
            AccessibilityEvent.TYPE_VIEW_SCROLLED
        assertEquals(expectedEvents, harness.hook.requiredEventTypes(PageShieldRegistry.PACKAGE_YOUTUBE))
        assertEquals(0, harness.hook.requiredEventTypes("com.android.chrome"))
        assertEquals(0, harness.hook.requiredEventTypes(null))

        harness.hook.updateConfig(PageShieldConfig(isEnabled = false))
        assertEquals(0, harness.hook.requiredEventTypes(PageShieldRegistry.PACKAGE_YOUTUBE))
    }

    @Test
    fun onForegroundChanged_returnsIgnoredWhenBlockedByHigherPriorityOrNonTarget() {
        val harness = createHarness(provideActiveService = true)
        harness.hook.updateConfig(youtubeEnabledConfig())

        val blockedResult = harness.hook.onForegroundChanged(
            service = harness.service,
            packageName = PageShieldRegistry.PACKAGE_YOUTUBE,
            className = null,
            event = null,
            alreadyBlockedByHigherPriority = true
        )
        assertEquals(ModuleDispatchResult.IGNORED, blockedResult)

        val nonTargetResult = harness.hook.onForegroundChanged(
            service = harness.service,
            packageName = "com.android.chrome",
            className = null,
            event = null,
            alreadyBlockedByHigherPriority = false
        )
        assertEquals(ModuleDispatchResult.IGNORED, nonTargetResult)

        val targetResult = harness.hook.onForegroundChanged(
            service = harness.service,
            packageName = PageShieldRegistry.PACKAGE_YOUTUBE,
            className = null,
            event = null,
            alreadyBlockedByHigherPriority = false
        )
        assertEquals(ModuleDispatchResult.OBSERVED, targetResult)
    }

    @Test
    fun updateConfig_triggersCapabilityRefreshOnlyWhenActiveServicePresent() {
        val activeHarness = createHarness(provideActiveService = true)
        activeHarness.hook.updateConfig(youtubeEnabledConfig())

        assertEquals(1, activeHarness.refreshCallbackCount)
        assertSame(activeHarness.service, activeHarness.lastRefreshedService)

        val policy = DevicePolicyBundle.defaultPolicy().copy(
            pageShieldConfig = PageShieldConfig(isEnabled = false)
        )
        activeHarness.hook.onPolicyUpdated(policy)
        assertEquals(2, activeHarness.refreshCallbackCount)
        assertEquals(0, activeHarness.hook.requiredEventTypes(PageShieldRegistry.PACKAGE_YOUTUBE))

        val inactiveHarness = createHarness(provideActiveService = false)
        inactiveHarness.hook.updateConfig(youtubeEnabledConfig())
        assertEquals(0, inactiveHarness.refreshCallbackCount)
    }

    @Test
    fun onServiceDisconnected_clearsForegroundStateAndAllowsNextTargetEvaluation() {
        val harness = createHarness(provideActiveService = true)
        harness.hook.updateConfig(youtubeEnabledConfig())

        val firstVisit = harness.hook.onForegroundChanged(
            service = harness.service,
            packageName = PageShieldRegistry.PACKAGE_YOUTUBE,
            className = null,
            event = null,
            alreadyBlockedByHigherPriority = false
        )
        assertEquals(ModuleDispatchResult.OBSERVED, firstVisit)

        harness.hook.onServiceDisconnected()

        val afterReconnect = harness.hook.onForegroundChanged(
            service = harness.service,
            packageName = PageShieldRegistry.PACKAGE_YOUTUBE,
            className = null,
            event = null,
            alreadyBlockedByHigherPriority = false
        )
        assertEquals(ModuleDispatchResult.OBSERVED, afterReconnect)

        val nonTarget = harness.hook.onForegroundChanged(
            service = harness.service,
            packageName = "com.android.launcher",
            className = null,
            event = null,
            alreadyBlockedByHigherPriority = false
        )
        assertEquals(ModuleDispatchResult.IGNORED, nonTarget)
    }

    @Test
    fun onForegroundChanged_returnsIgnoredForPasswordEvent() {
        val harness = createHarness(provideActiveService = true)
        harness.hook.updateConfig(youtubeEnabledConfig())
        harness.isPasswordEvent = true
        harness.detectedResult = true

        val result = harness.hook.onForegroundChanged(
            service = harness.service,
            packageName = PageShieldRegistry.PACKAGE_YOUTUBE,
            className = null,
            event = allocateDummyEvent(),
            alreadyBlockedByHigherPriority = false
        )

        assertEquals(ModuleDispatchResult.IGNORED, result)
        assertEquals(0, harness.detectorCalls)
        assertEquals(0, harness.reactionCalls)
    }

    @Test
    fun onEvent_blocksHigherPriorityPasswordAndNonTargetEvents() {
        val harness = createHarness(provideActiveService = true)
        harness.hook.updateConfig(youtubeEnabledConfig())
        harness.detectedResult = true
        val event = allocateDummyEvent()

        assertEquals(
            ModuleDispatchResult.IGNORED,
            harness.hook.onEvent(harness.service, event, alreadyBlockedByHigherPriority = true)
        )

        harness.isPasswordEvent = true
        assertEquals(
            ModuleDispatchResult.IGNORED,
            harness.hook.onEvent(harness.service, event, alreadyBlockedByHigherPriority = false)
        )
        harness.isPasswordEvent = false

        harness.eventPackageName = "com.android.chrome"
        assertEquals(
            ModuleDispatchResult.IGNORED,
            harness.hook.onEvent(harness.service, event, alreadyBlockedByHigherPriority = false)
        )

        assertEquals(0, harness.detectorCalls)
        assertEquals(0, harness.reactionCalls)
    }

    @Test
    fun onEvent_throttlesWithin300msAndEvaluatesAfterInterval() {
        val harness = createHarness(provideActiveService = true)
        harness.hook.updateConfig(youtubeEnabledConfig())
        harness.detectedResult = false
        val event = allocateDummyEvent()

        assertEquals(
            ModuleDispatchResult.OBSERVED,
            harness.hook.onEvent(harness.service, event, alreadyBlockedByHigherPriority = false)
        )
        assertEquals(1, harness.detectorCalls)

        harness.nowMs += 299L
        assertEquals(
            ModuleDispatchResult.IGNORED,
            harness.hook.onEvent(harness.service, event, alreadyBlockedByHigherPriority = false)
        )
        assertEquals(1, harness.detectorCalls)

        harness.nowMs += 1L
        assertEquals(
            ModuleDispatchResult.OBSERVED,
            harness.hook.onEvent(harness.service, event, alreadyBlockedByHigherPriority = false)
        )
        assertEquals(2, harness.detectorCalls)
    }

    @Test
    fun onEvent_returnsActionTakenOnMatchAndObservedDuring1200msCooldown() {
        val harness = createHarness(provideActiveService = true)
        harness.hook.updateConfig(youtubeEnabledConfig())
        harness.detectedResult = true
        val event = allocateDummyEvent()

        // First match at t=10_000 -> ACTION_TAKEN and reaction executed
        assertEquals(
            ModuleDispatchResult.ACTION_TAKEN,
            harness.hook.onEvent(harness.service, event, alreadyBlockedByHigherPriority = false)
        )
        assertEquals(1, harness.detectorCalls)
        assertEquals(1, harness.reactionCalls)
        assertEquals(PageShieldRegistry.PAGE_YOUTUBE_SHORTS, harness.lastReactionPageId)

        // At +300ms (still within 1200ms post-trigger cooldown) -> OBSERVED without re-detecting
        harness.nowMs += 300L
        assertEquals(
            ModuleDispatchResult.OBSERVED,
            harness.hook.onEvent(harness.service, event, alreadyBlockedByHigherPriority = false)
        )
        assertEquals(1, harness.detectorCalls)
        assertEquals(1, harness.reactionCalls)

        // At +899ms total -> still in cooldown -> OBSERVED
        harness.nowMs += 599L
        assertEquals(
            ModuleDispatchResult.OBSERVED,
            harness.hook.onEvent(harness.service, event, alreadyBlockedByHigherPriority = false)
        )
        assertEquals(1, harness.detectorCalls)
        assertEquals(1, harness.reactionCalls)

        // At +1199ms total via onForegroundChanged -> still in cooldown -> OBSERVED
        harness.nowMs += 300L
        assertEquals(
            ModuleDispatchResult.OBSERVED,
            harness.hook.onForegroundChanged(
                service = harness.service,
                packageName = PageShieldRegistry.PACKAGE_YOUTUBE,
                className = null,
                event = event,
                alreadyBlockedByHigherPriority = false
            )
        )
        assertEquals(1, harness.detectorCalls)
        assertEquals(1, harness.reactionCalls)

        // At +1200ms total (and 301ms since last allowed onEvent) -> cooldown expired -> ACTION_TAKEN
        harness.nowMs += 1L
        assertEquals(
            ModuleDispatchResult.ACTION_TAKEN,
            harness.hook.onEvent(harness.service, event, alreadyBlockedByHigherPriority = false)
        )
        assertEquals(2, harness.detectorCalls)
        assertEquals(2, harness.reactionCalls)
    }

    @Test
    fun onForegroundChanged_async150msRetryExecutesReactionWhenDelayedUiMatches() {
        val reactionLatch = CountDownLatch(1)
        var detected = false
        var detectorCalls = 0
        var reactionCalls = 0
        var lastRetryEvent: AccessibilityEvent? = allocateDummyEvent()
        var lastReactionPageId: String? = null

        val service = object : AccessibilityService() {
            override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
            override fun onInterrupt() {}
        }

        val hook = PageShieldAccessibilityHook(
            activeServiceProvider = { service },
            clockMs = { 10_000L },
            eventPackageNameProvider = { PageShieldRegistry.PACKAGE_YOUTUBE },
            isPasswordEventProvider = { false },
            pageDetector = { _, event, _, _, _, _ ->
                detectorCalls++
                lastRetryEvent = event
                detected
            },
            reactionExecutor = { _, _, pageId, _ ->
                reactionCalls++
                lastReactionPageId = pageId
                reactionLatch.countDown()
            },
            displaySizeProvider = { 1080 to 2400 }
        )
        createdHooks.add(hook)
        hook.updateConfig(youtubeEnabledConfig())

        val initialEvent = allocateDummyEvent()
        val initialResult = hook.onForegroundChanged(
            service = service,
            packageName = PageShieldRegistry.PACKAGE_YOUTUBE,
            className = null,
            event = initialEvent,
            alreadyBlockedByHigherPriority = false
        )
        assertEquals(ModuleDispatchResult.OBSERVED, initialResult)
        assertEquals(1, detectorCalls)
        assertEquals(0, reactionCalls)
        assertSame(initialEvent, lastRetryEvent)

        // Simulate async UI rendering completing before the 150ms retry executes
        detected = true
        assertTrue("150ms retry must execute reaction after async UI renders", reactionLatch.await(2, TimeUnit.SECONDS))
        assertEquals(2, detectorCalls)
        assertEquals(1, reactionCalls)
        assertEquals(PageShieldRegistry.PAGE_YOUTUBE_SHORTS, lastReactionPageId)
        assertNull("Delayed retry must not retain the original AccessibilityEvent", lastRetryEvent)
    }
}
