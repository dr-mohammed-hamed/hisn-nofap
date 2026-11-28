package com.drmohammed.antiporn.accessibility.core

import android.accessibilityservice.AccessibilityServiceInfo
import android.view.accessibility.AccessibilityEvent
import com.drmohammed.antiporn.accessibility.control.AccessibilityRequirementReason
import com.drmohammed.antiporn.core.model.DevicePolicyBundle
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import sun.misc.Unsafe

class AccessibilityCapabilitiesManagerTest {

    @Before
    fun setUp() {
        AccessibilityModuleRegistry.clearForTesting()
    }

    @After
    fun tearDown() {
        AccessibilityModuleRegistry.clearForTesting()
    }

    private fun allocateServiceInfo(
        eventTypes: Int = 0,
        flags: Int = 0
    ): AccessibilityServiceInfo {
        val field = Unsafe::class.java.getDeclaredField("theUnsafe")
        field.isAccessible = true
        val unsafe = field.get(null) as Unsafe
        val info = unsafe.allocateInstance(AccessibilityServiceInfo::class.java) as AccessibilityServiceInfo
        info.eventTypes = eventTypes
        info.flags = flags
        return info
    }

    private fun testModule(
        id: String,
        targetPackage: String,
        eventTypes: Int,
        flags: Int
    ): HisnAccessibilityModule = object : HisnAccessibilityModule {
        override val moduleId: String = id
        override val priority: Int = 10
        override val requirementReason: AccessibilityRequirementReason =
            AccessibilityRequirementReason.CUSTOM_SHIELD

        override fun isRequired(policy: DevicePolicyBundle, sdkInt: Int): Boolean = true

        override fun requiredEventTypes(foregroundPackage: String?): Int =
            if (foregroundPackage == targetPackage) eventTypes else 0

        override fun requiredFlags(foregroundPackage: String?): Int =
            if (foregroundPackage == targetPackage) flags else 0
    }

    @Test
    fun computeDesiredCapabilities_emptyRegistryReturnsBaseCapabilities() {
        val (events, flags) = AccessibilityCapabilitiesManager.computeDesiredCapabilities(null)

        assertTrue((AccessibilityCapabilitiesManager.BASE_FLAGS and AccessibilityServiceInfo.DEFAULT) != 0)
        assertEquals(AccessibilityCapabilitiesManager.BASE_EVENT_TYPES, events)
        assertEquals(AccessibilityCapabilitiesManager.BASE_FLAGS, flags)
    }

    @Test
    fun computeDesiredCapabilities_appliesModuleRequirementsOnlyForTargetPackage() {
        val targetPackage = "com.example.target"
        val extraEvents = AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
            AccessibilityEvent.TYPE_VIEW_CLICKED
        val extraFlags = AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS

        AccessibilityModuleRegistry.register(
            testModule(
                id = "dynamic_test",
                targetPackage = targetPackage,
                eventTypes = extraEvents,
                flags = extraFlags
            )
        )

        val (targetEvents, targetFlags) =
            AccessibilityCapabilitiesManager.computeDesiredCapabilities(targetPackage)
        assertEquals(
            AccessibilityCapabilitiesManager.BASE_EVENT_TYPES or extraEvents,
            targetEvents
        )
        assertEquals(
            AccessibilityCapabilitiesManager.BASE_FLAGS or extraFlags,
            targetFlags
        )

        val (otherEvents, otherFlags) =
            AccessibilityCapabilitiesManager.computeDesiredCapabilities("com.example.other")
        assertEquals(AccessibilityCapabilitiesManager.BASE_EVENT_TYPES, otherEvents)
        assertEquals(AccessibilityCapabilitiesManager.BASE_FLAGS, otherFlags)
    }

    @Test
    fun syncServiceInfo_skipsSetterWhenCapabilitiesAlreadyMatch() {
        val currentInfo = allocateServiceInfo(
            eventTypes = AccessibilityCapabilitiesManager.BASE_EVENT_TYPES,
            flags = AccessibilityCapabilitiesManager.BASE_FLAGS
        )
        var setterCalled = false

        val updated = AccessibilityCapabilitiesManager.syncServiceInfo(
            foregroundPackage = null,
            serviceInfoGetter = { currentInfo },
            serviceInfoSetter = { setterCalled = true },
            serviceInfoFactory = { allocateServiceInfo() }
        )

        assertFalse(updated)
        assertFalse(setterCalled)
    }

    @Test
    fun syncServiceInfo_updatesAndCallsSetterWhenEventTypesOrFlagsDiffer() {
        val currentWithDifferentEvents = allocateServiceInfo(
            eventTypes = 0,
            flags = AccessibilityCapabilitiesManager.BASE_FLAGS
        )
        var appliedInfo: AccessibilityServiceInfo? = null
        var setterCalls = 0

        val updatedEvents = AccessibilityCapabilitiesManager.syncServiceInfo(
            foregroundPackage = null,
            serviceInfoGetter = { currentWithDifferentEvents },
            serviceInfoSetter = { info ->
                setterCalls++
                appliedInfo = info
            },
            serviceInfoFactory = { allocateServiceInfo() }
        )

        assertTrue(updatedEvents)
        assertEquals(1, setterCalls)
        assertSame(currentWithDifferentEvents, appliedInfo)
        assertEquals(AccessibilityCapabilitiesManager.BASE_EVENT_TYPES, appliedInfo?.eventTypes)
        assertEquals(AccessibilityCapabilitiesManager.BASE_FLAGS, appliedInfo?.flags)

        val currentWithDifferentFlags = allocateServiceInfo(
            eventTypes = AccessibilityCapabilitiesManager.BASE_EVENT_TYPES,
            flags = 0
        )
        appliedInfo = null

        val updatedFlags = AccessibilityCapabilitiesManager.syncServiceInfo(
            foregroundPackage = null,
            serviceInfoGetter = { currentWithDifferentFlags },
            serviceInfoSetter = { info ->
                setterCalls++
                appliedInfo = info
            },
            serviceInfoFactory = { allocateServiceInfo() }
        )

        assertTrue(updatedFlags)
        assertEquals(2, setterCalls)
        assertSame(currentWithDifferentFlags, appliedInfo)
        assertEquals(AccessibilityCapabilitiesManager.BASE_EVENT_TYPES, appliedInfo?.eventTypes)
        assertEquals(AccessibilityCapabilitiesManager.BASE_FLAGS, appliedInfo?.flags)
    }

    @Test
    fun syncServiceInfo_createsInfoViaFactoryWhenCurrentInfoIsNull() {
        val createdInfo = allocateServiceInfo()
        var factoryCalls = 0
        var appliedInfo: AccessibilityServiceInfo? = null

        val updated = AccessibilityCapabilitiesManager.syncServiceInfo(
            foregroundPackage = null,
            serviceInfoGetter = { null },
            serviceInfoSetter = { info -> appliedInfo = info },
            serviceInfoFactory = {
                factoryCalls++
                createdInfo
            }
        )

        assertTrue(updated)
        assertEquals(1, factoryCalls)
        assertSame(createdInfo, appliedInfo)
        assertEquals(AccessibilityCapabilitiesManager.BASE_EVENT_TYPES, createdInfo.eventTypes)
        assertEquals(AccessibilityCapabilitiesManager.BASE_FLAGS, createdInfo.flags)
    }

    @Test
    fun syncServiceInfo_returnsFalseSafelyWhenGetterOrSetterThrows() {
        var setterCalled = false
        val getterFailed = AccessibilityCapabilitiesManager.syncServiceInfo(
            foregroundPackage = null,
            serviceInfoGetter = { throw IllegalStateException("binder dead") },
            serviceInfoSetter = { setterCalled = true },
            serviceInfoFactory = { allocateServiceInfo() }
        )

        assertFalse(getterFailed)
        assertFalse(setterCalled)

        val setterFailed = AccessibilityCapabilitiesManager.syncServiceInfo(
            foregroundPackage = null,
            serviceInfoGetter = { allocateServiceInfo() },
            serviceInfoSetter = { throw IllegalStateException("write rejected") },
            serviceInfoFactory = { allocateServiceInfo() }
        )

        assertFalse(setterFailed)
    }

    @Test
    fun syncServiceInfo_providerResolvesLatestPackageAtomically() {
        val targetPackage = "com.example.target"
        val extraEvents = AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        AccessibilityModuleRegistry.register(
            testModule(
                id = "provider_test",
                targetPackage = targetPackage,
                eventTypes = extraEvents,
                flags = 0
            )
        )
        var currentPkg: String? = null
        var appliedInfo: AccessibilityServiceInfo? = null

        currentPkg = targetPackage
        val updated = AccessibilityCapabilitiesManager.syncServiceInfo(
            foregroundPackageProvider = { currentPkg },
            serviceInfoGetter = { allocateServiceInfo() },
            serviceInfoSetter = { info -> appliedInfo = info },
            serviceInfoFactory = { allocateServiceInfo() }
        )

        assertTrue(updated)
        assertEquals(AccessibilityCapabilitiesManager.BASE_EVENT_TYPES or extraEvents, appliedInfo?.eventTypes)
    }
}
