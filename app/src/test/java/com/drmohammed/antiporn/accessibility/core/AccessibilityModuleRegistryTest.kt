package com.drmohammed.antiporn.accessibility.core

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import com.drmohammed.antiporn.accessibility.control.AccessibilityPolicyCoordinator
import com.drmohammed.antiporn.accessibility.control.AccessibilityRequirementReason
import com.drmohammed.antiporn.core.model.DevicePolicyBundle
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import sun.misc.Unsafe

class AccessibilityModuleRegistryTest {

    @Before
    fun setUp() {
        AccessibilityModuleRegistry.clearForTesting()
    }

    @After
    fun tearDown() {
        AccessibilityModuleRegistry.clearForTesting()
    }

    private fun fakeService(): AccessibilityService = object : AccessibilityService() {
        override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
        override fun onInterrupt() {}
    }

    private fun allocateDummyEvent(): AccessibilityEvent {
        val field = Unsafe::class.java.getDeclaredField("theUnsafe")
        field.isAccessible = true
        val unsafe = field.get(null) as Unsafe
        return unsafe.allocateInstance(AccessibilityEvent::class.java) as AccessibilityEvent
    }

    private fun createStubModule(
        id: String,
        priority: Int,
        reason: AccessibilityRequirementReason = AccessibilityRequirementReason.CUSTOM_SHIELD,
        required: Boolean = false
    ): HisnAccessibilityModule = object : HisnAccessibilityModule {
        override val moduleId: String = id
        override val priority: Int = priority
        override val requirementReason: AccessibilityRequirementReason = reason
        override fun isRequired(policy: DevicePolicyBundle, sdkInt: Int): Boolean = required
    }

    @Test
    fun register_sortsModulesInAscendingPriorityOrder() {
        AccessibilityModuleRegistry.register(createStubModule("p30", priority = 30))
        AccessibilityModuleRegistry.register(createStubModule("p10", priority = 10))
        AccessibilityModuleRegistry.register(createStubModule("p20", priority = 20))

        val ids = AccessibilityModuleRegistry.getModules().map { it.moduleId }
        assertEquals(listOf("p10", "p20", "p30"), ids)
    }

    @Test
    fun register_deduplicatesByModuleIdAndUnregisterRemovesModule() {
        AccessibilityModuleRegistry.register(createStubModule("mod_a", priority = 40))
        AccessibilityModuleRegistry.register(createStubModule("mod_b", priority = 20))
        AccessibilityModuleRegistry.register(createStubModule("mod_a", priority = 5))

        val afterReplace = AccessibilityModuleRegistry.getModules()
        assertEquals(listOf("mod_a", "mod_b"), afterReplace.map { it.moduleId })
        assertEquals(listOf(5, 20), afterReplace.map { it.priority })

        AccessibilityModuleRegistry.unregister("mod_a")
        assertEquals(listOf("mod_b"), AccessibilityModuleRegistry.getModules().map { it.moduleId })

        AccessibilityModuleRegistry.unregister("does_not_exist")
        assertEquals(listOf("mod_b"), AccessibilityModuleRegistry.getModules().map { it.moduleId })
    }

    @Test
    fun dispatchForegroundChanged_runsAllModulesAndPropagatesBlockedFlag() {
        val calls = mutableListOf<Pair<String, Boolean>>()

        AccessibilityModuleRegistry.register(object : HisnAccessibilityModule {
            override val moduleId = "observer_before"
            override val priority = 10
            override val requirementReason = AccessibilityRequirementReason.PAGE_SHIELD
            override fun isRequired(policy: DevicePolicyBundle, sdkInt: Int) = false
            override fun onForegroundChanged(
                service: AccessibilityService,
                packageName: String,
                className: String?,
                event: AccessibilityEvent?,
                alreadyBlockedByHigherPriority: Boolean
            ): ModuleDispatchResult {
                calls.add(moduleId to alreadyBlockedByHigherPriority)
                return ModuleDispatchResult.OBSERVED
            }
        })

        AccessibilityModuleRegistry.register(object : HisnAccessibilityModule {
            override val moduleId = "ignored_before"
            override val priority = 15
            override val requirementReason = AccessibilityRequirementReason.CUSTOM_SHIELD
            override fun isRequired(policy: DevicePolicyBundle, sdkInt: Int) = false
            override fun onForegroundChanged(
                service: AccessibilityService,
                packageName: String,
                className: String?,
                event: AccessibilityEvent?,
                alreadyBlockedByHigherPriority: Boolean
            ): ModuleDispatchResult {
                calls.add(moduleId to alreadyBlockedByHigherPriority)
                return ModuleDispatchResult.IGNORED
            }
        })

        AccessibilityModuleRegistry.register(object : HisnAccessibilityModule {
            override val moduleId = "throwing_mid"
            override val priority = 20
            override val requirementReason = AccessibilityRequirementReason.WAQFA_INSTANT
            override fun isRequired(policy: DevicePolicyBundle, sdkInt: Int) = false
            override fun onForegroundChanged(
                service: AccessibilityService,
                packageName: String,
                className: String?,
                event: AccessibilityEvent?,
                alreadyBlockedByHigherPriority: Boolean
            ): ModuleDispatchResult {
                calls.add(moduleId to alreadyBlockedByHigherPriority)
                throw IllegalStateException("boom")
            }
        })

        AccessibilityModuleRegistry.register(object : HisnAccessibilityModule {
            override val moduleId = "blocker"
            override val priority = 30
            override val requirementReason = AccessibilityRequirementReason.CUSTOM_SHIELD
            override fun isRequired(policy: DevicePolicyBundle, sdkInt: Int) = false
            override fun onForegroundChanged(
                service: AccessibilityService,
                packageName: String,
                className: String?,
                event: AccessibilityEvent?,
                alreadyBlockedByHigherPriority: Boolean
            ): ModuleDispatchResult {
                calls.add(moduleId to alreadyBlockedByHigherPriority)
                return ModuleDispatchResult.ACTION_TAKEN
            }
        })

        AccessibilityModuleRegistry.register(object : HisnAccessibilityModule {
            override val moduleId = "observer_after"
            override val priority = 40
            override val requirementReason = AccessibilityRequirementReason.VISION_AI
            override fun isRequired(policy: DevicePolicyBundle, sdkInt: Int) = false
            override fun onForegroundChanged(
                service: AccessibilityService,
                packageName: String,
                className: String?,
                event: AccessibilityEvent?,
                alreadyBlockedByHigherPriority: Boolean
            ): ModuleDispatchResult {
                calls.add(moduleId to alreadyBlockedByHigherPriority)
                return ModuleDispatchResult.OBSERVED
            }
        })

        val actionTaken = AccessibilityModuleRegistry.dispatchForegroundChanged(
            service = fakeService(),
            packageName = "com.example.app",
            className = "MainActivity",
            event = null
        )

        assertTrue(actionTaken)
        assertEquals(
            listOf(
                "observer_before" to false,
                "ignored_before" to false,
                "throwing_mid" to false,
                "blocker" to false,
                "observer_after" to true
            ),
            calls
        )
    }

    @Test
    fun dispatchEvent_runsAllModulesWithExceptionIsolationAndBlockedFlag() {
        val calls = mutableListOf<Pair<String, Boolean>>()

        AccessibilityModuleRegistry.register(object : HisnAccessibilityModule {
            override val moduleId = "first_observed"
            override val priority = 5
            override val requirementReason = AccessibilityRequirementReason.PAGE_SHIELD
            override fun isRequired(policy: DevicePolicyBundle, sdkInt: Int) = false
            override fun onEvent(
                service: AccessibilityService,
                event: AccessibilityEvent,
                alreadyBlockedByHigherPriority: Boolean
            ): ModuleDispatchResult {
                calls.add(moduleId to alreadyBlockedByHigherPriority)
                return ModuleDispatchResult.OBSERVED
            }
        })

        AccessibilityModuleRegistry.register(object : HisnAccessibilityModule {
            override val moduleId = "blocker"
            override val priority = 10
            override val requirementReason = AccessibilityRequirementReason.CUSTOM_SHIELD
            override fun isRequired(policy: DevicePolicyBundle, sdkInt: Int) = false
            override fun onEvent(
                service: AccessibilityService,
                event: AccessibilityEvent,
                alreadyBlockedByHigherPriority: Boolean
            ): ModuleDispatchResult {
                calls.add(moduleId to alreadyBlockedByHigherPriority)
                return ModuleDispatchResult.ACTION_TAKEN
            }
        })

        AccessibilityModuleRegistry.register(object : HisnAccessibilityModule {
            override val moduleId = "throwing_after_block"
            override val priority = 20
            override val requirementReason = AccessibilityRequirementReason.WAQFA_INSTANT
            override fun isRequired(policy: DevicePolicyBundle, sdkInt: Int) = false
            override fun onEvent(
                service: AccessibilityService,
                event: AccessibilityEvent,
                alreadyBlockedByHigherPriority: Boolean
            ): ModuleDispatchResult {
                calls.add(moduleId to alreadyBlockedByHigherPriority)
                throw RuntimeException("simulated failure")
            }
        })

        AccessibilityModuleRegistry.register(object : HisnAccessibilityModule {
            override val moduleId = "passive_tail"
            override val priority = 30
            override val requirementReason = AccessibilityRequirementReason.VISION_AI
            override fun isRequired(policy: DevicePolicyBundle, sdkInt: Int) = false
            override fun onEvent(
                service: AccessibilityService,
                event: AccessibilityEvent,
                alreadyBlockedByHigherPriority: Boolean
            ): ModuleDispatchResult {
                calls.add(moduleId to alreadyBlockedByHigherPriority)
                return ModuleDispatchResult.IGNORED
            }
        })

        val actionTaken = AccessibilityModuleRegistry.dispatchEvent(
            service = fakeService(),
            event = allocateDummyEvent()
        )

        assertTrue(actionTaken)
        assertEquals(
            listOf(
                "first_observed" to false,
                "blocker" to false,
                "throwing_after_block" to true,
                "passive_tail" to true
            ),
            calls
        )
    }

    @Test
    fun collectRequiredReasonsAndIsAnyModuleRequired_integrateWithPolicyCoordinator() {
        val defaultPolicy = DevicePolicyBundle.defaultPolicy()
        assertTrue(AccessibilityModuleRegistry.collectRequiredReasons(defaultPolicy, 34).isEmpty())
        assertFalse(AccessibilityModuleRegistry.isAnyModuleRequired(defaultPolicy, 34))
        assertFalse(AccessibilityPolicyCoordinator.isAccessibilityRequired(defaultPolicy, 34))

        AccessibilityModuleRegistry.register(object : HisnAccessibilityModule {
            override val moduleId = "throwing_req"
            override val priority = 10
            override val requirementReason = AccessibilityRequirementReason.PAGE_SHIELD
            override fun isRequired(policy: DevicePolicyBundle, sdkInt: Int): Boolean {
                throw IllegalStateException("req check failed")
            }
        })
        AccessibilityModuleRegistry.register(
            createStubModule(
                id = "custom_shield",
                priority = 20,
                reason = AccessibilityRequirementReason.CUSTOM_SHIELD,
                required = true
            )
        )

        val reasons = AccessibilityModuleRegistry.collectRequiredReasons(defaultPolicy, 34)
        assertEquals(setOf(AccessibilityRequirementReason.CUSTOM_SHIELD), reasons)
        assertTrue(AccessibilityModuleRegistry.isAnyModuleRequired(defaultPolicy, 34))

        assertEquals(
            setOf(AccessibilityRequirementReason.CUSTOM_SHIELD),
            AccessibilityPolicyCoordinator.getRequiredReasons(defaultPolicy, 34)
        )
        assertTrue(AccessibilityPolicyCoordinator.isAccessibilityRequired(defaultPolicy, 34))
    }

    @Test
    fun lifecycleNotifications_isolateExceptionsAcrossModules() {
        val policyCalls = mutableListOf<String>()
        val connectedCalls = mutableListOf<String>()
        val disconnectedCalls = mutableListOf<String>()

        AccessibilityModuleRegistry.register(object : HisnAccessibilityModule {
            override val moduleId = "throwing_lifecycle"
            override val priority = 10
            override val requirementReason = AccessibilityRequirementReason.CUSTOM_SHIELD
            override fun isRequired(policy: DevicePolicyBundle, sdkInt: Int) = false
            override fun onPolicyUpdated(policy: DevicePolicyBundle) {
                policyCalls.add(moduleId)
                throw RuntimeException("policy error")
            }
            override fun onServiceConnected(service: AccessibilityService) {
                connectedCalls.add(moduleId)
                throw RuntimeException("connect error")
            }
            override fun onServiceDisconnected() {
                disconnectedCalls.add(moduleId)
                throw RuntimeException("disconnect error")
            }
        })

        AccessibilityModuleRegistry.register(object : HisnAccessibilityModule {
            override val moduleId = "healthy_lifecycle"
            override val priority = 20
            override val requirementReason = AccessibilityRequirementReason.PAGE_SHIELD
            override fun isRequired(policy: DevicePolicyBundle, sdkInt: Int) = false
            override fun onPolicyUpdated(policy: DevicePolicyBundle) {
                policyCalls.add(moduleId)
            }
            override fun onServiceConnected(service: AccessibilityService) {
                connectedCalls.add(moduleId)
            }
            override fun onServiceDisconnected() {
                disconnectedCalls.add(moduleId)
            }
        })

        AccessibilityModuleRegistry.notifyPolicyUpdated(DevicePolicyBundle.defaultPolicy())
        AccessibilityModuleRegistry.notifyServiceConnected(fakeService())
        AccessibilityModuleRegistry.notifyServiceDisconnected()

        assertEquals(listOf("throwing_lifecycle", "healthy_lifecycle"), policyCalls)
        assertEquals(listOf("throwing_lifecycle", "healthy_lifecycle"), connectedCalls)
        assertEquals(listOf("throwing_lifecycle", "healthy_lifecycle"), disconnectedCalls)
    }

    @Test
    fun notifyPolicyUpdated_dropsOutOfOrderPolicySoModulesNeverRegress() {
        val seenVersions = mutableListOf<Long>()
        AccessibilityModuleRegistry.register(object : HisnAccessibilityModule {
            override val moduleId = "version_sink"
            override val priority = 10
            override val requirementReason = AccessibilityRequirementReason.CUSTOM_SHIELD
            override fun isRequired(policy: DevicePolicyBundle, sdkInt: Int) = false
            override fun onPolicyUpdated(policy: DevicePolicyBundle) {
                seenVersions.add(policy.version)
            }
        })

        val base = DevicePolicyBundle.defaultPolicy()
        AccessibilityModuleRegistry.notifyPolicyUpdated(base.copy(version = 6L, updatedAt = 200L))
        AccessibilityModuleRegistry.notifyPolicyUpdated(base.copy(version = 5L, updatedAt = 300L))
        AccessibilityModuleRegistry.notifyPolicyUpdated(base.copy(version = 6L, updatedAt = 150L))
        AccessibilityModuleRegistry.notifyPolicyUpdated(base.copy(version = 6L, updatedAt = 250L))
        AccessibilityModuleRegistry.notifyPolicyUpdated(base.copy(version = 7L, updatedAt = 100L))

        assertEquals(listOf(6L, 6L, 7L), seenVersions)
    }

    @Test
    fun computeRequiredEventTypesAndFlags_orMasksAcrossModulesWithExceptionIsolation() {
        val targetPkg = "com.example.video"

        AccessibilityModuleRegistry.register(object : HisnAccessibilityModule {
            override val moduleId = "throwing_caps"
            override val priority = 5
            override val requirementReason = AccessibilityRequirementReason.CUSTOM_SHIELD
            override fun isRequired(policy: DevicePolicyBundle, sdkInt: Int) = false
            override fun requiredEventTypes(foregroundPackage: String?): Int {
                throw IllegalStateException("events boom")
            }
            override fun requiredFlags(foregroundPackage: String?): Int {
                throw IllegalStateException("flags boom")
            }
        })

        AccessibilityModuleRegistry.register(object : HisnAccessibilityModule {
            override val moduleId = "caps_a"
            override val priority = 10
            override val requirementReason = AccessibilityRequirementReason.PAGE_SHIELD
            override fun isRequired(policy: DevicePolicyBundle, sdkInt: Int) = false
            override fun requiredEventTypes(foregroundPackage: String?): Int =
                if (foregroundPackage == targetPkg) AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED else 0
            override fun requiredFlags(foregroundPackage: String?): Int =
                if (foregroundPackage == targetPkg) android.accessibilityservice.AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS else 0
        })

        AccessibilityModuleRegistry.register(object : HisnAccessibilityModule {
            override val moduleId = "caps_b"
            override val priority = 20
            override val requirementReason = AccessibilityRequirementReason.WAQFA_INSTANT
            override fun isRequired(policy: DevicePolicyBundle, sdkInt: Int) = false
            override fun requiredEventTypes(foregroundPackage: String?): Int =
                if (foregroundPackage == targetPkg) AccessibilityEvent.TYPE_VIEW_CLICKED else 0
            override fun requiredFlags(foregroundPackage: String?): Int =
                if (foregroundPackage == targetPkg) android.accessibilityservice.AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE else 0
        })

        assertEquals(
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or AccessibilityEvent.TYPE_VIEW_CLICKED,
            AccessibilityModuleRegistry.computeRequiredEventTypes(targetPkg)
        )
        assertEquals(
            android.accessibilityservice.AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS or
                android.accessibilityservice.AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE,
            AccessibilityModuleRegistry.computeRequiredFlags(targetPkg)
        )
        assertEquals(0, AccessibilityModuleRegistry.computeRequiredEventTypes("com.other.app"))
        assertEquals(0, AccessibilityModuleRegistry.computeRequiredFlags("com.other.app"))
    }
}
