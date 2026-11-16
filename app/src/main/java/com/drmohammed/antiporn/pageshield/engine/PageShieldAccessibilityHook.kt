package com.drmohammed.antiporn.pageshield.engine

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import com.drmohammed.antiporn.accessibility.hook.AccessibilityEventHook
import com.drmohammed.antiporn.accessibility.hook.AccessibilityHookRegistry
import com.drmohammed.antiporn.data.repository.PolicyRepository
import com.drmohammed.antiporn.pageshield.model.AppPageShieldPolicy
import com.drmohammed.antiporn.pageshield.model.PageShieldConfig
import com.drmohammed.antiporn.pageshield.registry.PageShieldRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * خطاف خدمة الوصول لدرع الصفحات (Page Shield Accessibility Hook).
 * يراقب ويتعرف على الصفحات المحظورة داخل التطبيقات المستهدفة بأقصى كفاءة للبطارية وخنق زمني دقيق.
 */
class PageShieldAccessibilityHook private constructor() : AccessibilityEventHook {

    private val hookScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile
    private var cachedConfig: PageShieldConfig = PageShieldConfig()

    @Volatile
    private var currentForegroundPackage: String? = null

    private val lastCheckTime = AtomicLong(0L)
    private val lastTriggerTime = ConcurrentHashMap<String, Long>()

    fun updateConfig(config: PageShieldConfig) {
        cachedConfig = config
        Timber.tag(TAG).i("PageShield config updated: isEnabled=${config.isEnabled}, targets=${config.getTargetPackages()}")
    }

    override fun onWindowStateChanged(
        service: AccessibilityService,
        packageName: String,
        className: String?
    ): Boolean {
        val previousPackage = currentForegroundPackage
        currentForegroundPackage = packageName

        // Dynamic AccessibilityServiceInfo EventTypes management for battery conservation
        val isTarget = cachedConfig.hasActiveRulesForPackage(packageName)
        val wasTarget = previousPackage != null && cachedConfig.hasActiveRulesForPackage(previousPackage)

        if (isTarget && !wasTarget) {
            enableInAppEvents(service)
        } else if (!isTarget && wasTarget) {
            disableInAppEvents(service)
        }

        if (!isTarget) {
            return false
        }

        return evaluatePackageEvents(service, null, packageName)
    }

    override fun onRawAccessibilityEvent(
        service: AccessibilityService,
        event: AccessibilityEvent
    ): Boolean {
        val packageName = event.packageName?.toString() ?: return false

        // O(1) Package Gate: Reject immediately if package has no active rules in current policy
        if (!cachedConfig.hasActiveRulesForPackage(packageName)) {
            return false
        }

        // Throttle check frequency to once every 300ms
        val now = SystemClock.elapsedRealtime()
        val last = lastCheckTime.get()
        if (now - last < THROTTLE_INTERVAL_MS) {
            return false
        }
        if (!lastCheckTime.compareAndSet(last, now)) {
            return false
        }

        return evaluatePackageEvents(service, event, packageName)
    }

    private fun evaluatePackageEvents(
        service: AccessibilityService,
        event: AccessibilityEvent?,
        packageName: String
    ): Boolean {
        // Post-Trigger Cooldown check
        val now = SystemClock.elapsedRealtime()
        val lastTrigger = lastTriggerTime[packageName] ?: 0L
        if (now - lastTrigger < POST_TRIGGER_COOLDOWN_MS) {
            return true
        }

        val policy = cachedConfig.appPolicies[packageName] ?: return false
        val displayMetrics = service.resources.displayMetrics
        val width = displayMetrics.widthPixels
        val height = displayMetrics.heightPixels

        // 1. Direct Evaluation
        if (checkAndExecuteMatch(service, event, packageName, policy, width, height)) {
            return true
        }

        // 2. Incremental Retry after 150ms for asynchronous UI rendering (without retaining AccessibilityNodeInfo)
        hookScope.launch {
            delay(150L)
            if (currentForegroundPackage == packageName) {
                checkAndExecuteMatch(service, null, packageName, policy, width, height)
            }
        }

        return false
    }

    private fun checkAndExecuteMatch(
        service: AccessibilityService,
        event: AccessibilityEvent?,
        packageName: String,
        policy: AppPageShieldPolicy,
        screenWidth: Int,
        screenHeight: Int
    ): Boolean {
        val now = SystemClock.elapsedRealtime()
        val lastTrigger = lastTriggerTime[packageName] ?: 0L
        if (now - lastTrigger < POST_TRIGGER_COOLDOWN_MS) {
            return false
        }

        for (pageId in policy.enabledPageIds) {
            val pageDef = PageShieldRegistry.findPage(packageName, pageId) ?: continue
            val detected = pageDef.detectorStrategy.isPageDetected(
                service = service,
                event = event,
                screenWidth = screenWidth,
                screenHeight = screenHeight
            )

            if (detected) {
                Timber.tag(TAG).i("Target page $pageId DETECTED in $packageName!")
                lastTriggerTime[packageName] = SystemClock.elapsedRealtime()
                PageShieldReactionExecutor.executeReaction(service, packageName, pageId, policy)
                return true
            }
        }
        return false
    }

    private fun enableInAppEvents(service: AccessibilityService) {
        try {
            val info = service.serviceInfo ?: AccessibilityServiceInfo()
            val targetFlags = AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
                AccessibilityEvent.TYPE_VIEW_SCROLLED or
                AccessibilityEvent.TYPE_VIEW_CLICKED or
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                AccessibilityEvent.TYPE_WINDOWS_CHANGED
            if (info.eventTypes != targetFlags) {
                info.eventTypes = targetFlags
                service.serviceInfo = info
                Timber.tag(TAG).d("Dynamically enabled in-app eventTypes for PageShield target")
            }
        } catch (e: Exception) {
            Timber.tag(TAG).w("Error updating serviceInfo to enable in-app events: ${e.message}")
        }
    }

    private fun disableInAppEvents(service: AccessibilityService) {
        try {
            val info = service.serviceInfo ?: return
            val defaultFlags = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                AccessibilityEvent.TYPE_WINDOWS_CHANGED
            if (info.eventTypes != defaultFlags) {
                info.eventTypes = defaultFlags
                service.serviceInfo = info
                Timber.tag(TAG).d("Dynamically reverted eventTypes to default (zero overhead outside targets)")
            }
        } catch (e: Exception) {
            Timber.tag(TAG).w("Error updating serviceInfo to disable in-app events: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "AntiPorn_PageShieldHook"
        private const val THROTTLE_INTERVAL_MS = 300L
        private const val POST_TRIGGER_COOLDOWN_MS = 1200L

        @Volatile
        private var instance: PageShieldAccessibilityHook? = null

        fun getInstance(): PageShieldAccessibilityHook {
            return instance ?: synchronized(this) {
                instance ?: PageShieldAccessibilityHook().also {
                    instance = it
                    AccessibilityHookRegistry.registerHook(it)
                }
            }
        }
    }
}
