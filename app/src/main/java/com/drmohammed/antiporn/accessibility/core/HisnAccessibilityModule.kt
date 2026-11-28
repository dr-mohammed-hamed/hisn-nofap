package com.drmohammed.antiporn.accessibility.core

import android.accessibilityservice.AccessibilityService
import android.os.Build
import android.view.accessibility.AccessibilityEvent
import com.drmohammed.antiporn.accessibility.control.AccessibilityRequirementReason
import com.drmohammed.antiporn.core.model.DevicePolicyBundle

/**
 * Result produced by a [HisnAccessibilityModule] when handling a foreground change or accessibility event.
 */
enum class ModuleDispatchResult {
    IGNORED,
    OBSERVED,
    ACTION_TAKEN
}

/**
 * Pluggable module contract for [com.drmohammed.antiporn.accessibility.HisnAccessibilityService].
 *
 * Modules declare their policy requirement, dynamic capability flags/event types, and priority-ordered
 * event handlers without modifying the core service class.
 */
interface HisnAccessibilityModule {

    val moduleId: String

    val priority: Int

    val requirementReason: AccessibilityRequirementReason

    fun isRequired(policy: DevicePolicyBundle, sdkInt: Int = Build.VERSION.SDK_INT): Boolean

    fun requiredEventTypes(foregroundPackage: String?): Int = 0

    fun requiredFlags(foregroundPackage: String?): Int = 0

    fun onPolicyUpdated(policy: DevicePolicyBundle) {}

    fun onServiceConnected(service: AccessibilityService) {}

    fun onServiceDisconnected() {}

    fun onForegroundChanged(
        service: AccessibilityService,
        packageName: String,
        className: String?,
        event: AccessibilityEvent?,
        alreadyBlockedByHigherPriority: Boolean
    ): ModuleDispatchResult = ModuleDispatchResult.IGNORED

    fun onEvent(
        service: AccessibilityService,
        event: AccessibilityEvent,
        alreadyBlockedByHigherPriority: Boolean
    ): ModuleDispatchResult = ModuleDispatchResult.IGNORED
}
