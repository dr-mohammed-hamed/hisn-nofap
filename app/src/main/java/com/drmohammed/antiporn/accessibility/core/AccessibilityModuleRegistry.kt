package com.drmohammed.antiporn.accessibility.core

import android.accessibilityservice.AccessibilityService
import android.os.Build
import android.view.accessibility.AccessibilityEvent
import androidx.annotation.VisibleForTesting
import com.drmohammed.antiporn.accessibility.HisnAccessibilityService
import com.drmohammed.antiporn.accessibility.control.AccessibilityRequirementReason
import com.drmohammed.antiporn.core.model.DevicePolicyBundle
import java.util.concurrent.CopyOnWriteArrayList
import timber.log.Timber

/**
 * Thread-safe central registry and priority dispatcher for [HisnAccessibilityModule] instances.
 *
 * Mutations synchronize on [writeLock] and atomically publish a deduplicated, ascending-priority
 * [CopyOnWriteArrayList] snapshot so readers observe a consistent ordered list without locking.
 */
object AccessibilityModuleRegistry {

    private const val TAG = "AntiPorn_A11yRegistry"

    private val writeLock = Any()

    @Volatile
    private var modules: CopyOnWriteArrayList<HisnAccessibilityModule> = CopyOnWriteArrayList()

    @Volatile
    private var lastNotifiedVersion: Long = -1L

    @Volatile
    private var lastNotifiedUpdatedAt: Long = -1L

    /**
     * Registers [module], replacing any existing module with the same [HisnAccessibilityModule.moduleId],
     * and keeps the registry sorted in ascending [HisnAccessibilityModule.priority] order.
     */
    fun register(module: HisnAccessibilityModule) {
        synchronized(writeLock) {
            val updated = modules
                .filter { it.moduleId != module.moduleId }
                .plus(module)
                .sortedBy { it.priority }
            modules = CopyOnWriteArrayList(updated)
            Timber.tag(TAG).d(
                "Registered accessibility module id=%s priority=%d",
                module.moduleId,
                module.priority
            )
        }
        HisnAccessibilityService.instance?.refreshCapabilities()
    }

    /**
     * Unregisters the module matching [moduleId], if present.
     */
    fun unregister(moduleId: String) {
        var removed = false
        synchronized(writeLock) {
            val current = modules
            val updated = current.filter { it.moduleId != moduleId }
            if (updated.size != current.size) {
                modules = CopyOnWriteArrayList(updated)
                removed = true
                Timber.tag(TAG).d("Unregistered accessibility module id=%s", moduleId)
            }
        }
        if (removed) {
            HisnAccessibilityService.instance?.refreshCapabilities()
        }
    }

    /**
     * Returns an immutable snapshot of registered modules in ascending priority order.
     */
    fun getModules(): List<HisnAccessibilityModule> = modules.toList()

    @VisibleForTesting
    fun clearForTesting() {
        synchronized(writeLock) {
            modules = CopyOnWriteArrayList()
            lastNotifiedVersion = -1L
            lastNotifiedUpdatedAt = -1L
        }
    }

    /**
     * Notifies modules with a policy that is at least as new as the last one published.
     *
     * Out-of-order notifications (e.g. a cold-start Room reconcile racing a newer Firestore
     * update) must never regress module configuration, so strictly older bundles are dropped.
     */
    fun notifyPolicyUpdated(policy: DevicePolicyBundle) {
        synchronized(writeLock) {
            if (policy.version < lastNotifiedVersion ||
                (policy.version == lastNotifiedVersion && policy.updatedAt < lastNotifiedUpdatedAt)
            ) {
                Timber.tag(TAG).d(
                    "Skipping out-of-order policy notification v%d@%d (last v%d@%d)",
                    policy.version, policy.updatedAt, lastNotifiedVersion, lastNotifiedUpdatedAt
                )
                return
            }
            lastNotifiedVersion = policy.version
            lastNotifiedUpdatedAt = policy.updatedAt
        }
        for (module in modules) {
            try {
                module.onPolicyUpdated(policy)
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Error in onPolicyUpdated for module=%s", module.moduleId)
            }
        }
    }

    fun notifyServiceConnected(service: AccessibilityService) {
        for (module in modules) {
            try {
                module.onServiceConnected(service)
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Error in onServiceConnected for module=%s", module.moduleId)
            }
        }
    }

    fun notifyServiceDisconnected() {
        for (module in modules) {
            try {
                module.onServiceDisconnected()
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Error in onServiceDisconnected for module=%s", module.moduleId)
            }
        }
    }

    fun collectRequiredReasons(
        policy: DevicePolicyBundle,
        sdkInt: Int = Build.VERSION.SDK_INT
    ): Set<AccessibilityRequirementReason> {
        val reasons = mutableSetOf<AccessibilityRequirementReason>()
        for (module in modules) {
            try {
                if (module.isRequired(policy, sdkInt)) {
                    reasons.add(module.requirementReason)
                }
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Error in isRequired for module=%s", module.moduleId)
            }
        }
        return reasons
    }

    fun isAnyModuleRequired(
        policy: DevicePolicyBundle,
        sdkInt: Int = Build.VERSION.SDK_INT
    ): Boolean {
        for (module in modules) {
            try {
                if (module.isRequired(policy, sdkInt)) {
                    return true
                }
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Error in isRequired for module=%s", module.moduleId)
            }
        }
        return false
    }

    fun computeRequiredEventTypes(foregroundPackage: String?): Int {
        var mask = 0
        for (module in modules) {
            try {
                mask = mask or module.requiredEventTypes(foregroundPackage)
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Error in requiredEventTypes for module=%s", module.moduleId)
            }
        }
        return mask
    }

    fun computeRequiredFlags(foregroundPackage: String?): Int {
        var mask = 0
        for (module in modules) {
            try {
                mask = mask or module.requiredFlags(foregroundPackage)
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Error in requiredFlags for module=%s", module.moduleId)
            }
        }
        return mask
    }

    /**
     * Dispatches a foreground change to all registered modules in priority order without
     * short-circuiting so passive observers still run when a higher-priority module took action.
     *
     * @return true if at least one module returned [ModuleDispatchResult.ACTION_TAKEN].
     */
    fun dispatchForegroundChanged(
        service: AccessibilityService,
        packageName: String,
        className: String?,
        event: AccessibilityEvent?
    ): Boolean {
        var actionTaken = false
        for (module in modules) {
            try {
                val result = module.onForegroundChanged(
                    service = service,
                    packageName = packageName,
                    className = className,
                    event = event,
                    alreadyBlockedByHigherPriority = actionTaken
                )
                if (result == ModuleDispatchResult.ACTION_TAKEN) {
                    actionTaken = true
                }
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Error in onForegroundChanged for module=%s", module.moduleId)
            }
        }
        return actionTaken
    }

    /**
     * Dispatches an accessibility event to all registered modules in priority order without
     * short-circuiting so passive observers still run when a higher-priority module took action.
     *
     * @return true if at least one module returned [ModuleDispatchResult.ACTION_TAKEN].
     */
    fun dispatchEvent(
        service: AccessibilityService,
        event: AccessibilityEvent
    ): Boolean {
        var actionTaken = false
        for (module in modules) {
            try {
                val result = module.onEvent(
                    service = service,
                    event = event,
                    alreadyBlockedByHigherPriority = actionTaken
                )
                if (result == ModuleDispatchResult.ACTION_TAKEN) {
                    actionTaken = true
                }
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Error in onEvent for module=%s", module.moduleId)
            }
        }
        return actionTaken
    }
}
