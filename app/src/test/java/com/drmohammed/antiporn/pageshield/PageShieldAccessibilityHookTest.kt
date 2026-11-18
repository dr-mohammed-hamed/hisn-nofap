package com.drmohammed.antiporn.pageshield

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.view.accessibility.AccessibilityEvent
import com.drmohammed.antiporn.pageshield.engine.PageShieldAccessibilityHook
import com.drmohammed.antiporn.pageshield.model.AppPageShieldPolicy
import com.drmohammed.antiporn.pageshield.model.PageShieldConfig
import com.drmohammed.antiporn.pageshield.registry.PageShieldRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PageShieldAccessibilityHookTest {

    private class ServiceInfoHarness(
        initialEventTypes: Int?,
        provideActiveService: Boolean = false
    ) {
        var info: AccessibilityServiceInfo? = initialEventTypes?.let { types ->
            AccessibilityServiceInfo().apply { eventTypes = types }
        }
        var setServiceInfoCallCount: Int = 0
            private set

        val currentEventTypes: Int
            get() = info?.eventTypes ?: 0

        val service: AccessibilityService = object : AccessibilityService() {
            override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
            override fun onInterrupt() {}
        }

        val hook: PageShieldAccessibilityHook = PageShieldAccessibilityHook(
            getServiceInfo = { info },
            setServiceInfo = { _, updated ->
                setServiceInfoCallCount++
                info = updated
            },
            activeServiceProvider = { if (provideActiveService) service else null }
        )
    }

    @Test
    fun enableInAppEvents_addsInAppFlagsWhilePreservingBaselineWindowAndScrollFlags() {
        val baseline = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
            AccessibilityEvent.TYPE_WINDOWS_CHANGED or
            AccessibilityEvent.TYPE_VIEW_SCROLLED
        val harness = ServiceInfoHarness(initialEventTypes = baseline)

        harness.hook.enableInAppEvents(harness.service)

        val updated = harness.currentEventTypes
        assertTrue((updated and AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) != 0)
        assertTrue((updated and AccessibilityEvent.TYPE_WINDOWS_CHANGED) != 0)
        assertTrue((updated and AccessibilityEvent.TYPE_VIEW_SCROLLED) != 0)
        assertTrue((updated and AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) != 0)
        assertTrue((updated and AccessibilityEvent.TYPE_VIEW_CLICKED) != 0)
        assertEquals(1, harness.setServiceInfoCallCount)
    }

    @Test
    fun disableInAppEvents_clearsInAppOnlyFlagsAndStrictlyPreservesViewScrolledAndWindowFlags() {
        val combined = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
            AccessibilityEvent.TYPE_WINDOWS_CHANGED or
            AccessibilityEvent.TYPE_VIEW_SCROLLED or
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
            AccessibilityEvent.TYPE_VIEW_CLICKED or
            AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED
        val harness = ServiceInfoHarness(initialEventTypes = combined)

        harness.hook.disableInAppEvents(harness.service)

        val updated = harness.currentEventTypes
        // In-app heavy flags must be cleared
        assertFalse((updated and AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) != 0)
        assertFalse((updated and AccessibilityEvent.TYPE_VIEW_CLICKED) != 0)
        // TYPE_VIEW_SCROLLED and baseline window/custom flags MUST remain untouched
        assertTrue((updated and AccessibilityEvent.TYPE_VIEW_SCROLLED) != 0)
        assertTrue((updated and AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) != 0)
        assertTrue((updated and AccessibilityEvent.TYPE_WINDOWS_CHANGED) != 0)
        assertTrue((updated and AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED) != 0)
        assertEquals(1, harness.setServiceInfoCallCount)
    }

    @Test
    fun enableThenDisableInAppEvents_roundTripRestoresExactBaselineWithViewScrolledIntact() {
        val baseline = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
            AccessibilityEvent.TYPE_WINDOWS_CHANGED or
            AccessibilityEvent.TYPE_VIEW_SCROLLED
        val harness = ServiceInfoHarness(initialEventTypes = baseline)

        harness.hook.enableInAppEvents(harness.service)
        assertTrue((harness.currentEventTypes and AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) != 0)
        assertTrue((harness.currentEventTypes and AccessibilityEvent.TYPE_VIEW_CLICKED) != 0)
        assertTrue((harness.currentEventTypes and AccessibilityEvent.TYPE_VIEW_SCROLLED) != 0)

        harness.hook.disableInAppEvents(harness.service)
        assertEquals(baseline, harness.currentEventTypes)
        assertTrue((harness.currentEventTypes and AccessibilityEvent.TYPE_VIEW_SCROLLED) != 0)
    }

    @Test
    fun disableInAppEvents_whenAlreadyCleanOrNullServiceInfo_isNoOp() {
        val baseline = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
            AccessibilityEvent.TYPE_WINDOWS_CHANGED or
            AccessibilityEvent.TYPE_VIEW_SCROLLED
        val cleanHarness = ServiceInfoHarness(initialEventTypes = baseline)

        cleanHarness.hook.disableInAppEvents(cleanHarness.service)
        assertEquals(baseline, cleanHarness.currentEventTypes)
        assertEquals(0, cleanHarness.setServiceInfoCallCount)

        val nullInfoHarness = ServiceInfoHarness(initialEventTypes = null)
        nullInfoHarness.hook.disableInAppEvents(nullInfoHarness.service)
        assertEquals(0, nullInfoHarness.setServiceInfoCallCount)
    }

    @Test
    fun onWindowStateChanged_enteringAndLeavingTargetApp_preservesViewScrolled() {
        val baseline = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
            AccessibilityEvent.TYPE_WINDOWS_CHANGED or
            AccessibilityEvent.TYPE_VIEW_SCROLLED
        val harness = ServiceInfoHarness(initialEventTypes = baseline)

        harness.hook.updateConfig(
            PageShieldConfig(
                isEnabled = true,
                appPolicies = mapOf(
                    PageShieldRegistry.PACKAGE_YOUTUBE to AppPageShieldPolicy(
                        packageName = PageShieldRegistry.PACKAGE_YOUTUBE,
                        enabledPageIds = setOf(PageShieldRegistry.PAGE_YOUTUBE_SHORTS)
                    )
                )
            )
        )

        // Start in a non-target app
        harness.hook.onWindowStateChanged(harness.service, "com.android.launcher", null)
        assertEquals(baseline, harness.currentEventTypes)

        // Enter YouTube (target) -> enables in-app events (evaluatePackageEvents may throw on stub resources, wrap safely)
        runCatching {
            harness.hook.onWindowStateChanged(harness.service, PageShieldRegistry.PACKAGE_YOUTUBE, null)
        }
        assertTrue((harness.currentEventTypes and AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) != 0)
        assertTrue((harness.currentEventTypes and AccessibilityEvent.TYPE_VIEW_CLICKED) != 0)
        assertTrue((harness.currentEventTypes and AccessibilityEvent.TYPE_VIEW_SCROLLED) != 0)

        // Leave YouTube to Chrome (non-target) -> disables in-app events while preserving TYPE_VIEW_SCROLLED & baseline window flags
        harness.hook.onWindowStateChanged(harness.service, "com.android.chrome", null)
        assertEquals(baseline, harness.currentEventTypes)
        assertTrue((harness.currentEventTypes and AccessibilityEvent.TYPE_VIEW_SCROLLED) != 0)
        assertFalse((harness.currentEventTypes and AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) != 0)
        assertFalse((harness.currentEventTypes and AccessibilityEvent.TYPE_VIEW_CLICKED) != 0)
    }

    @Test
    fun updateConfig_whileTargetInForeground_immediatelyTogglesInAppEvents() {
        val baseline = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
            AccessibilityEvent.TYPE_WINDOWS_CHANGED or
            AccessibilityEvent.TYPE_VIEW_SCROLLED
        val harness = ServiceInfoHarness(initialEventTypes = baseline, provideActiveService = true)
        val youtubeEnabledConfig = PageShieldConfig(
            isEnabled = true,
            appPolicies = mapOf(
                PageShieldRegistry.PACKAGE_YOUTUBE to AppPageShieldPolicy(
                    packageName = PageShieldRegistry.PACKAGE_YOUTUBE,
                    enabledPageIds = setOf(PageShieldRegistry.PAGE_YOUTUBE_SHORTS)
                )
            )
        )

        // Start in YouTube while PageShield is disabled -> baseline intact
        harness.hook.onWindowStateChanged(harness.service, PageShieldRegistry.PACKAGE_YOUTUBE, null)
        assertEquals(baseline, harness.currentEventTypes)

        // Enable PageShield for YouTube while user is still inside YouTube -> immediately enables in-app events
        harness.hook.updateConfig(youtubeEnabledConfig)
        assertTrue((harness.currentEventTypes and AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) != 0)
        assertTrue((harness.currentEventTypes and AccessibilityEvent.TYPE_VIEW_CLICKED) != 0)
        assertTrue((harness.currentEventTypes and AccessibilityEvent.TYPE_VIEW_SCROLLED) != 0)

        // Disable PageShield while user is still inside YouTube -> immediately reverts in-app events
        harness.hook.updateConfig(PageShieldConfig(isEnabled = false))
        assertEquals(baseline, harness.currentEventTypes)
        assertFalse((harness.currentEventTypes and AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) != 0)
        assertFalse((harness.currentEventTypes and AccessibilityEvent.TYPE_VIEW_CLICKED) != 0)
    }

    @Test
    fun onWindowStateChanged_afterConfigRemovesTarget_stillDisablesInAppEventsViaTrackedFlag() {
        val baseline = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
            AccessibilityEvent.TYPE_WINDOWS_CHANGED or
            AccessibilityEvent.TYPE_VIEW_SCROLLED
        val harness = ServiceInfoHarness(initialEventTypes = baseline, provideActiveService = false)
        harness.hook.updateConfig(
            PageShieldConfig(
                isEnabled = true,
                appPolicies = mapOf(
                    PageShieldRegistry.PACKAGE_YOUTUBE to AppPageShieldPolicy(
                        packageName = PageShieldRegistry.PACKAGE_YOUTUBE,
                        enabledPageIds = setOf(PageShieldRegistry.PAGE_YOUTUBE_SHORTS)
                    )
                )
            )
        )

        runCatching {
            harness.hook.onWindowStateChanged(harness.service, PageShieldRegistry.PACKAGE_YOUTUBE, null)
        }
        assertTrue((harness.currentEventTypes and AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) != 0)

        // Config disables PageShield while activeServiceProvider is null; inAppEventsEnabled must still be true
        harness.hook.updateConfig(PageShieldConfig(isEnabled = false))
        // Leaving YouTube must still disable in-app events cleanly instead of leaking them
        harness.hook.onWindowStateChanged(harness.service, "com.android.chrome", null)
        assertEquals(baseline, harness.currentEventTypes)
        assertFalse((harness.currentEventTypes and AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) != 0)
        assertFalse((harness.currentEventTypes and AccessibilityEvent.TYPE_VIEW_CLICKED) != 0)
    }

    @Test
    fun onServiceDisconnected_resetsTrackedInAppEventsStateForNextConnection() {
        val baseline = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
            AccessibilityEvent.TYPE_WINDOWS_CHANGED or
            AccessibilityEvent.TYPE_VIEW_SCROLLED
        val harness = ServiceInfoHarness(initialEventTypes = baseline, provideActiveService = true)
        harness.hook.updateConfig(
            PageShieldConfig(
                isEnabled = true,
                appPolicies = mapOf(
                    PageShieldRegistry.PACKAGE_YOUTUBE to AppPageShieldPolicy(
                        packageName = PageShieldRegistry.PACKAGE_YOUTUBE,
                        enabledPageIds = setOf(PageShieldRegistry.PAGE_YOUTUBE_SHORTS)
                    )
                )
            )
        )

        runCatching {
            harness.hook.onWindowStateChanged(harness.service, PageShieldRegistry.PACKAGE_YOUTUBE, null)
        }
        assertTrue((harness.currentEventTypes and AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) != 0)

        // Simulate service disconnect and fresh reconnect with baseline serviceInfo
        harness.hook.onServiceDisconnected()
        harness.info = AccessibilityServiceInfo().apply { eventTypes = baseline }

        runCatching {
            harness.hook.onWindowStateChanged(harness.service, PageShieldRegistry.PACKAGE_YOUTUBE, null)
        }
        assertTrue((harness.currentEventTypes and AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) != 0)
        assertTrue((harness.currentEventTypes and AccessibilityEvent.TYPE_VIEW_CLICKED) != 0)
    }
}
