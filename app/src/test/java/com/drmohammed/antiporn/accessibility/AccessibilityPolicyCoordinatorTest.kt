package com.drmohammed.antiporn.accessibility

import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import android.os.SystemClock
import com.drmohammed.antiporn.accessibility.control.AccessibilityPolicyCoordinator
import com.drmohammed.antiporn.accessibility.control.AccessibilityReconcileResult
import com.drmohammed.antiporn.accessibility.control.AccessibilityRequirementReason
import com.drmohammed.antiporn.accessibility.control.AccessibilitySelfActivator
import com.drmohammed.antiporn.core.model.DevicePolicyBundle
import com.drmohammed.antiporn.core.time.TrustedTimeProvider
import com.drmohammed.antiporn.pageshield.model.AppPageShieldPolicy
import com.drmohammed.antiporn.pageshield.model.PageShieldConfig
import com.drmohammed.antiporn.pageshield.registry.PageShieldRegistry
import com.drmohammed.antiporn.waqfa.model.WaqfaConfig
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AccessibilityPolicyCoordinatorTest {

    private lateinit var dummyContext: Context
    private var fakeElapsedMs = 10_000L

    @Before
    fun setUp() {
        fakeElapsedMs = 10_000L
        TrustedTimeProvider.resetForTesting()
        TrustedTimeProvider.elapsedRealtimeProvider = { fakeElapsedMs }
        dummyContext = object : ContextWrapper(null) {
            override fun getApplicationContext(): Context = this
            override fun getPackageName(): String = "com.drmohammed.antiporn"
        }
        AccessibilityPolicyCoordinator.onCapabilitiesChangedCallback = null
        AccessibilityPolicyCoordinator.policyResolverOverride = null
        AccessibilitySelfActivator.clearCloakState(dummyContext)
        AccessibilitySelfActivator.clearIntentionalDisable()
    }

    @After
    fun tearDown() {
        AccessibilityPolicyCoordinator.onCapabilitiesChangedCallback = null
        AccessibilityPolicyCoordinator.policyResolverOverride = null
        AccessibilitySelfActivator.clearCloakState(dummyContext)
        AccessibilitySelfActivator.clearIntentionalDisable()
        TrustedTimeProvider.resetForTesting()
        TrustedTimeProvider.elapsedRealtimeProvider = { SystemClock.elapsedRealtime() }
    }

    private fun activePageShieldConfig(): PageShieldConfig = PageShieldConfig(
        isEnabled = true,
        appPolicies = mapOf(
            PageShieldRegistry.PACKAGE_YOUTUBE to AppPageShieldPolicy(
                packageName = PageShieldRegistry.PACKAGE_YOUTUBE,
                enabledPageIds = setOf(PageShieldRegistry.PAGE_YOUTUBE_SHORTS)
            )
        )
    )

    private fun standardWaqfaConfig(): WaqfaConfig = WaqfaConfig(
        isEnabled = true,
        targetPackages = setOf("com.instagram.android"),
        useInstantAccessibilityMode = false
    )

    private fun instantWaqfaConfig(): WaqfaConfig = WaqfaConfig(
        isEnabled = true,
        targetPackages = setOf("com.instagram.android"),
        useInstantAccessibilityMode = true
    )

    // =========================================================================
    // 1. Default Policy (Zero-Accessibility-When-Unused)
    // =========================================================================

    @Test
    fun defaultPolicy_requiresNeitherAccessibilityNorUsageAccess() {
        val policy = DevicePolicyBundle.defaultPolicy()

        assertTrue(AccessibilityPolicyCoordinator.getRequiredReasons(policy, sdkInt = 34).isEmpty())
        assertTrue(AccessibilityPolicyCoordinator.getRequiredReasons(policy, sdkInt = 33).isEmpty())
        assertFalse(AccessibilityPolicyCoordinator.isAccessibilityRequired(policy, sdkInt = 34))
        assertFalse(AccessibilityPolicyCoordinator.isAccessibilityRequired(policy, sdkInt = 33))
        assertFalse(AccessibilityPolicyCoordinator.isUsageAccessRequired(policy, isA11yActive = false, sdkInt = 34))
        assertFalse(AccessibilityPolicyCoordinator.isUsageAccessRequired(policy, isA11yActive = false, sdkInt = 33))
    }

    // =========================================================================
    // 2. Waqfa: Bank-Compatible Default vs Instant Accessibility Mode
    // =========================================================================

    @Test
    fun waqfaStandardMode_doesNotRequireAccessibility_requiresUsageAccessWhenA11yOff() {
        val policy = DevicePolicyBundle.defaultPolicy().copy(
            waqfaConfig = standardWaqfaConfig()
        )

        assertTrue(AccessibilityPolicyCoordinator.getRequiredReasons(policy, sdkInt = 34).isEmpty())
        assertFalse(AccessibilityPolicyCoordinator.isAccessibilityRequired(policy, sdkInt = 34))
        assertTrue(AccessibilityPolicyCoordinator.isUsageAccessRequired(policy, isA11yActive = false, sdkInt = 34))
        // If A11y is already active, Usage Access is not required
        assertFalse(AccessibilityPolicyCoordinator.isUsageAccessRequired(policy, isA11yActive = true, sdkInt = 34))
    }

    @Test
    fun waqfaInstantMode_requiresAccessibility_doesNotRequireUsageAccess() {
        val policy = DevicePolicyBundle.defaultPolicy().copy(
            waqfaConfig = instantWaqfaConfig()
        )

        assertEquals(
            setOf(AccessibilityRequirementReason.WAQFA_INSTANT),
            AccessibilityPolicyCoordinator.getRequiredReasons(policy, sdkInt = 34)
        )
        assertTrue(AccessibilityPolicyCoordinator.isAccessibilityRequired(policy, sdkInt = 34))
        assertFalse(AccessibilityPolicyCoordinator.isUsageAccessRequired(policy, isA11yActive = false, sdkInt = 34))
        assertFalse(AccessibilityPolicyCoordinator.isUsageAccessRequired(policy, isA11yActive = false, sdkInt = 33))
    }

    // =========================================================================
    // 3. PageShield + Waqfa Auto-Upgrade & Revert
    // =========================================================================

    @Test
    fun pageShieldPlusStandardWaqfa_autoUpgradesAndRevertsCleanly() {
        // Both enabled -> PageShield requires A11y, so Waqfa auto-upgrades (Usage Access not needed)
        val combinedPolicy = DevicePolicyBundle.defaultPolicy().copy(
            pageShieldConfig = activePageShieldConfig(),
            waqfaConfig = standardWaqfaConfig()
        )

        assertEquals(
            setOf(AccessibilityRequirementReason.PAGE_SHIELD),
            AccessibilityPolicyCoordinator.getRequiredReasons(combinedPolicy, sdkInt = 34)
        )
        assertTrue(AccessibilityPolicyCoordinator.isAccessibilityRequired(combinedPolicy, sdkInt = 34))
        assertFalse(AccessibilityPolicyCoordinator.isUsageAccessRequired(combinedPolicy, isA11yActive = false, sdkInt = 34))

        // Turn off PageShield -> reverts to bank-compatible Waqfa (A11y no longer required, Usage Access required)
        val revertedPolicy = combinedPolicy.copy(
            pageShieldConfig = combinedPolicy.pageShieldConfig.copy(isEnabled = false)
        )

        assertTrue(AccessibilityPolicyCoordinator.getRequiredReasons(revertedPolicy, sdkInt = 34).isEmpty())
        assertFalse(AccessibilityPolicyCoordinator.isAccessibilityRequired(revertedPolicy, sdkInt = 34))
        assertTrue(AccessibilityPolicyCoordinator.isUsageAccessRequired(revertedPolicy, isA11yActive = false, sdkInt = 34))
    }

    // =========================================================================
    // 4. Vision AI: API 34+ vs API 33
    // =========================================================================

    @Test
    fun visionAi_api34Plus_requiresAccessibilityNotUsageAccess() {
        val policy = DevicePolicyBundle.defaultPolicy().copy(
            isScreenAiEnabledGlobally = true,
            screenAiSchemaVersion = DevicePolicyBundle.CURRENT_SCREEN_AI_SCHEMA_VERSION
        )

        assertEquals(
            setOf(AccessibilityRequirementReason.VISION_AI),
            AccessibilityPolicyCoordinator.getRequiredReasons(policy, sdkInt = Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
        )
        assertTrue(AccessibilityPolicyCoordinator.isAccessibilityRequired(policy, sdkInt = 34))
        assertTrue(AccessibilityPolicyCoordinator.isAccessibilityRequired(policy, sdkInt = 35))
        assertFalse(AccessibilityPolicyCoordinator.isUsageAccessRequired(policy, isA11yActive = false, sdkInt = 34))
    }

    @Test
    fun visionAi_api33AndBelow_doesNotRequireAccessibility_requiresUsageAccessWhenA11yOff() {
        val policy = DevicePolicyBundle.defaultPolicy().copy(
            isScreenAiEnabledGlobally = true,
            screenAiSchemaVersion = DevicePolicyBundle.CURRENT_SCREEN_AI_SCHEMA_VERSION
        )

        assertTrue(AccessibilityPolicyCoordinator.getRequiredReasons(policy, sdkInt = 33).isEmpty())
        assertFalse(AccessibilityPolicyCoordinator.isAccessibilityRequired(policy, sdkInt = 33))
        assertTrue(AccessibilityPolicyCoordinator.isUsageAccessRequired(policy, isA11yActive = false, sdkInt = 33))
        assertFalse(AccessibilityPolicyCoordinator.isUsageAccessRequired(policy, isA11yActive = true, sdkInt = 33))
    }

    @Test
    fun visionAiApi34Plus_withStandardWaqfa_autoUpgradesWaqfaWithoutUsageAccess() {
        val policy = DevicePolicyBundle.defaultPolicy().copy(
            isScreenAiEnabledGlobally = true,
            waqfaConfig = standardWaqfaConfig()
        )

        assertEquals(
            setOf(AccessibilityRequirementReason.VISION_AI),
            AccessibilityPolicyCoordinator.getRequiredReasons(policy, sdkInt = 34)
        )
        assertFalse(AccessibilityPolicyCoordinator.isUsageAccessRequired(policy, isA11yActive = false, sdkInt = 34))

        // On API 33, neither Vision AI nor standard Waqfa requires A11y -> Usage Access required
        assertTrue(AccessibilityPolicyCoordinator.getRequiredReasons(policy, sdkInt = 33).isEmpty())
        assertTrue(AccessibilityPolicyCoordinator.isUsageAccessRequired(policy, isA11yActive = false, sdkInt = 33))
    }

    @Test
    fun allThreeReasonsActiveOnApi34_reportsCompleteReasonSet() {
        val policy = DevicePolicyBundle.defaultPolicy().copy(
            pageShieldConfig = activePageShieldConfig(),
            waqfaConfig = instantWaqfaConfig(),
            isScreenAiEnabledGlobally = true
        )

        assertEquals(
            setOf(
                AccessibilityRequirementReason.PAGE_SHIELD,
                AccessibilityRequirementReason.WAQFA_INSTANT,
                AccessibilityRequirementReason.VISION_AI
            ),
            AccessibilityPolicyCoordinator.getRequiredReasons(policy, sdkInt = 34)
        )
        assertTrue(AccessibilityPolicyCoordinator.isAccessibilityRequired(policy, sdkInt = 34))
        assertFalse(AccessibilityPolicyCoordinator.isUsageAccessRequired(policy, isA11yActive = false, sdkInt = 34))
    }

    // =========================================================================
    // 5. Empty Target Packages Edge Cases
    // =========================================================================

    @Test
    fun emptyTargetPackages_neverRequireAccessibilityOrUsageAccess() {
        val emptyPageShieldNoApps = DevicePolicyBundle.defaultPolicy().copy(
            pageShieldConfig = PageShieldConfig(isEnabled = true, appPolicies = emptyMap())
        )
        assertFalse(AccessibilityPolicyCoordinator.isAccessibilityRequired(emptyPageShieldNoApps, sdkInt = 34))
        assertFalse(AccessibilityPolicyCoordinator.isUsageAccessRequired(emptyPageShieldNoApps, isA11yActive = false, sdkInt = 34))

        val emptyPageShieldNoPages = DevicePolicyBundle.defaultPolicy().copy(
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
        assertFalse(AccessibilityPolicyCoordinator.isAccessibilityRequired(emptyPageShieldNoPages, sdkInt = 34))

        val emptyWaqfaStandard = DevicePolicyBundle.defaultPolicy().copy(
            waqfaConfig = WaqfaConfig(
                isEnabled = true,
                targetPackages = emptySet(),
                useInstantAccessibilityMode = false
            )
        )
        assertFalse(AccessibilityPolicyCoordinator.isAccessibilityRequired(emptyWaqfaStandard, sdkInt = 34))
        assertFalse(AccessibilityPolicyCoordinator.isUsageAccessRequired(emptyWaqfaStandard, isA11yActive = false, sdkInt = 34))

        val emptyWaqfaInstant = DevicePolicyBundle.defaultPolicy().copy(
            waqfaConfig = WaqfaConfig(
                isEnabled = true,
                targetPackages = emptySet(),
                useInstantAccessibilityMode = true
            )
        )
        assertFalse(AccessibilityPolicyCoordinator.isAccessibilityRequired(emptyWaqfaInstant, sdkInt = 34))
        assertFalse(AccessibilityPolicyCoordinator.isUsageAccessRequired(emptyWaqfaInstant, isA11yActive = false, sdkInt = 34))
    }

    // =========================================================================
    // 6. Reconcile: Cloak Skipping vs forceUncloak & Policy-Driven Behavior
    // =========================================================================

    @Test
    fun reconcile_whenCloakedAndNotForceUncloak_returnsSkippedCloakedAndPreservesCloak() {
        AccessibilitySelfActivator.disableCleanly(dummyContext, isTemporaryCloak = true)
        assertTrue(AccessibilitySelfActivator.isCloaked(dummyContext))

        val requiredPolicy = DevicePolicyBundle.defaultPolicy().copy(
            pageShieldConfig = activePageShieldConfig()
        )
        val result = AccessibilityPolicyCoordinator.reconcile(
            context = dummyContext,
            policy = requiredPolicy,
            forceUncloak = false
        )

        assertTrue(result is AccessibilityReconcileResult.SkippedCloaked)
        assertTrue(AccessibilitySelfActivator.isCloaked(dummyContext))
    }

    @Test
    fun reconcile_whenCloakedAndForceUncloakWithPolicyNotRequiringA11y_clearsCloakWithoutEnabling() {
        AccessibilitySelfActivator.disableCleanly(dummyContext, isTemporaryCloak = true)
        assertTrue(AccessibilitySelfActivator.isCloaked(dummyContext))

        val defaultPolicy = DevicePolicyBundle.defaultPolicy()
        val result = AccessibilityPolicyCoordinator.reconcile(
            context = dummyContext,
            policy = defaultPolicy,
            forceUncloak = true
        )

        assertFalse(AccessibilitySelfActivator.isCloaked(dummyContext))
        assertTrue(result is AccessibilityReconcileResult.NotRequiredAlreadyOff)
    }

    @Test
    fun reconcile_whenCloakedAndForceUncloakWithPolicyRequiringA11y_clearsCloakAndReportsManualActivationInHeadless() {
        AccessibilitySelfActivator.disableCleanly(dummyContext, isTemporaryCloak = true)
        assertTrue(AccessibilitySelfActivator.isCloaked(dummyContext))

        val requiredPolicy = DevicePolicyBundle.defaultPolicy().copy(
            pageShieldConfig = activePageShieldConfig(),
            waqfaConfig = instantWaqfaConfig()
        )
        val result = AccessibilityPolicyCoordinator.reconcile(
            context = dummyContext,
            policy = requiredPolicy,
            forceUncloak = true
        )

        assertFalse(AccessibilitySelfActivator.isCloaked(dummyContext))
        assertTrue(result is AccessibilityReconcileResult.NeedsManualActivation)
        val manual = result as AccessibilityReconcileResult.NeedsManualActivation
        assertEquals(
            setOf(
                AccessibilityRequirementReason.PAGE_SHIELD,
                AccessibilityRequirementReason.WAQFA_INSTANT
            ),
            manual.reasons
        )
    }

    @Test
    fun reconcile_whenNotCloakedAndNotRequired_returnsNotRequiredAlreadyOff() {
        val result = AccessibilityPolicyCoordinator.reconcile(
            context = dummyContext,
            policy = DevicePolicyBundle.defaultPolicy(),
            forceUncloak = false
        )

        assertTrue(result is AccessibilityReconcileResult.NotRequiredAlreadyOff)
    }

    // =========================================================================
    // 7. onServiceDisconnected: Supervised Tamper Detection vs Intentional/Cloak
    // =========================================================================

    @Test
    fun onServiceDisconnected_whenSupervisedAndRequiredAndUnexpected_recordsTamperTrue() {
        val recordedTamperFlags = mutableListOf<Boolean>()
        AccessibilityPolicyCoordinator.onCapabilitiesChangedCallback = { _, recordTamper ->
            recordedTamperFlags.add(recordTamper)
        }
        AccessibilityPolicyCoordinator.policyResolverOverride = {
            DevicePolicyBundle.defaultPolicy().copy(
                isSupervised = true,
                pageShieldConfig = activePageShieldConfig()
            )
        }

        AccessibilityPolicyCoordinator.onServiceDisconnected(dummyContext)

        assertEquals(listOf(true), recordedTamperFlags)
    }

    @Test
    fun onServiceDisconnected_whenIntentionalDisableActive_recordsTamperFalse() {
        val recordedTamperFlags = mutableListOf<Boolean>()
        AccessibilityPolicyCoordinator.onCapabilitiesChangedCallback = { _, recordTamper ->
            recordedTamperFlags.add(recordTamper)
        }
        AccessibilityPolicyCoordinator.policyResolverOverride = {
            DevicePolicyBundle.defaultPolicy().copy(
                isSupervised = true,
                pageShieldConfig = activePageShieldConfig()
            )
        }
        AccessibilitySelfActivator.markIntentionalDisable()

        AccessibilityPolicyCoordinator.onServiceDisconnected(dummyContext)

        assertEquals(listOf(false), recordedTamperFlags)
    }

    @Test
    fun onServiceDisconnected_whenBankingCloakActive_recordsTamperFalse() {
        val recordedTamperFlags = mutableListOf<Boolean>()
        AccessibilityPolicyCoordinator.onCapabilitiesChangedCallback = { _, recordTamper ->
            recordedTamperFlags.add(recordTamper)
        }
        AccessibilityPolicyCoordinator.policyResolverOverride = {
            DevicePolicyBundle.defaultPolicy().copy(
                isSupervised = true,
                pageShieldConfig = activePageShieldConfig()
            )
        }
        AccessibilitySelfActivator.disableCleanly(dummyContext, isTemporaryCloak = true)
        AccessibilitySelfActivator.clearIntentionalDisable()
        assertTrue(AccessibilitySelfActivator.isCloaked(dummyContext))

        AccessibilityPolicyCoordinator.onServiceDisconnected(dummyContext)

        assertEquals(listOf(false), recordedTamperFlags)
    }

    @Test
    fun onServiceDisconnected_whenNotSupervised_recordsTamperFalse() {
        val recordedTamperFlags = mutableListOf<Boolean>()
        AccessibilityPolicyCoordinator.onCapabilitiesChangedCallback = { _, recordTamper ->
            recordedTamperFlags.add(recordTamper)
        }
        AccessibilityPolicyCoordinator.policyResolverOverride = {
            DevicePolicyBundle.defaultPolicy().copy(
                isSupervised = false,
                pageShieldConfig = activePageShieldConfig()
            )
        }

        AccessibilityPolicyCoordinator.onServiceDisconnected(dummyContext)

        assertEquals(listOf(false), recordedTamperFlags)
    }
}
