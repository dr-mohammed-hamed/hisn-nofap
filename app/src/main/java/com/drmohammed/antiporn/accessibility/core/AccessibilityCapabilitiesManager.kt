package com.drmohammed.antiporn.accessibility.core

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.view.accessibility.AccessibilityEvent
import timber.log.Timber

/**
 * Computes and synchronizes [AccessibilityServiceInfo] eventTypes and flags based on the union
 * of base core capabilities and active [HisnAccessibilityModule] requirements.
 *
 * Enforces check-before-write to avoid redundant binder writes to Android's AccessibilityManager.
 */
object AccessibilityCapabilitiesManager {

    private const val TAG = "AntiPorn_A11yCaps"
    private val capabilityLock = Any()

    const val BASE_EVENT_TYPES =
        AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
            AccessibilityEvent.TYPE_WINDOWS_CHANGED or
            AccessibilityEvent.TYPE_VIEW_SCROLLED

    const val BASE_FLAGS =
        AccessibilityServiceInfo.DEFAULT or
            AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
            AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS or
            AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS

    fun <T> withCapabilityLock(block: () -> T): T = synchronized(capabilityLock) { block() }

    /**
     * Returns `(eventTypes, flags)` combining base capabilities with module requirements for
     * [foregroundPackage].
     */
    fun computeDesiredCapabilities(foregroundPackage: String?): Pair<Int, Int> {
        val desiredEvents = BASE_EVENT_TYPES or AccessibilityModuleRegistry.computeRequiredEventTypes(foregroundPackage)
        val desiredFlags = BASE_FLAGS or AccessibilityModuleRegistry.computeRequiredFlags(foregroundPackage)
        return desiredEvents to desiredFlags
    }

    /**
     * Updates [service]'s [AccessibilityServiceInfo] only when `eventTypes` or `flags` differ
     * from the desired bitmasks for [foregroundPackage].
     *
     * @return true if `serviceInfo` was updated, false if already up to date or an error occurred.
     */
    fun syncServiceInfo(service: AccessibilityService, foregroundPackage: String?): Boolean {
        return syncServiceInfo(service) { foregroundPackage }
    }

    /**
     * Atomically resolves the current foreground package via [foregroundPackageProvider] and updates
     * [service]'s [AccessibilityServiceInfo] under [capabilityLock].
     *
     * @return true if `serviceInfo` was updated, false if already up to date or an error occurred.
     */
    fun syncServiceInfo(service: AccessibilityService, foregroundPackageProvider: () -> String?): Boolean {
        return syncServiceInfo(
            foregroundPackageProvider = foregroundPackageProvider,
            serviceInfoGetter = { service.serviceInfo },
            serviceInfoSetter = { info -> service.serviceInfo = info }
        )
    }

    internal fun syncServiceInfo(
        foregroundPackage: String?,
        serviceInfoGetter: () -> AccessibilityServiceInfo?,
        serviceInfoSetter: (AccessibilityServiceInfo) -> Unit,
        serviceInfoFactory: () -> AccessibilityServiceInfo = { AccessibilityServiceInfo() }
    ): Boolean {
        return syncServiceInfo(
            foregroundPackageProvider = { foregroundPackage },
            serviceInfoGetter = serviceInfoGetter,
            serviceInfoSetter = serviceInfoSetter,
            serviceInfoFactory = serviceInfoFactory
        )
    }

    internal fun syncServiceInfo(
        foregroundPackageProvider: () -> String?,
        serviceInfoGetter: () -> AccessibilityServiceInfo?,
        serviceInfoSetter: (AccessibilityServiceInfo) -> Unit,
        serviceInfoFactory: () -> AccessibilityServiceInfo = { AccessibilityServiceInfo() }
    ): Boolean {
        return synchronized(capabilityLock) {
            var resolvedPackage: String? = null
            try {
                resolvedPackage = foregroundPackageProvider()
                val (desiredEvents, desiredFlags) = computeDesiredCapabilities(resolvedPackage)
                val currentInfo = serviceInfoGetter()
                if (currentInfo != null &&
                    currentInfo.eventTypes == desiredEvents &&
                    currentInfo.flags == desiredFlags
                ) {
                    return@synchronized false
                }
                val infoToApply = currentInfo ?: serviceInfoFactory()
                infoToApply.eventTypes = desiredEvents
                infoToApply.flags = desiredFlags
                serviceInfoSetter(infoToApply)
                Timber.tag(TAG).d(
                    "Synced serviceInfo for pkg=%s: eventTypes=0x%x flags=0x%x",
                    resolvedPackage,
                    desiredEvents,
                    desiredFlags
                )
                true
            } catch (e: Exception) {
                Timber.tag(TAG).w(e, "Failed syncing AccessibilityServiceInfo for pkg=%s", resolvedPackage)
                false
            }
        }
    }
}
