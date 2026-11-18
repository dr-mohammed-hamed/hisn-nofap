package com.drmohammed.antiporn.pageshield.engine

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import com.drmohammed.antiporn.accessibility.HisnAccessibilityService
import com.drmohammed.antiporn.accessibility.hook.AccessibilityEventHook
import com.drmohammed.antiporn.accessibility.hook.AccessibilityHookRegistry
import com.drmohammed.antiporn.pageshield.model.AppPageShieldPolicy
import com.drmohammed.antiporn.pageshield.model.PageShieldConfig
import com.drmohammed.antiporn.pageshield.registry.PageShieldRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
class PageShieldAccessibilityHook internal constructor(
    private val getServiceInfo: (AccessibilityService) -> AccessibilityServiceInfo? = { it.serviceInfo },
    private val setServiceInfo: (AccessibilityService, AccessibilityServiceInfo) -> Unit = { svc, info -> svc.serviceInfo = info },
    private val activeServiceProvider: () -> AccessibilityService? = { HisnAccessibilityService.instance }
) : AccessibilityEventHook {

    private val hookScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile
    private var cachedConfig: PageShieldConfig = PageShieldConfig()

    @Volatile
    private var currentForegroundPackage: String? = null

    @Volatile
    private var inAppEventsEnabled: Boolean = false

    private val lastCheckTime = AtomicLong(0L)
    private val lastTriggerTime = ConcurrentHashMap<String, Long>()
    private val pendingRetryJobs = ConcurrentHashMap<String, Job>()

    fun updateConfig(config: PageShieldConfig) {
        cachedConfig = config
        Timber.tag(TAG).i("PageShield config updated: isEnabled=${config.isEnabled}, targets=${config.getTargetPackages()}")
        val activeService = activeServiceProvider()
        val fgPkg = currentForegroundPackage
        if (activeService != null && fgPkg != null) {
            val shouldEnable = config.hasActiveRulesForPackage(fgPkg)
            if (shouldEnable && !inAppEventsEnabled) {
                enableInAppEvents(activeService)
            } else if (!shouldEnable && inAppEventsEnabled) {
                disableInAppEvents(activeService)
            }
        }
    }

    fun onServiceDisconnected() {
        inAppEventsEnabled = false
        currentForegroundPackage = null
        pendingRetryJobs.values.forEach { it.cancel() }
        pendingRetryJobs.clear()
    }

    override fun onWindowStateChanged(
        service: AccessibilityService,
        packageName: String,
        className: String?
    ): Boolean {
        currentForegroundPackage = packageName

        // Dynamic AccessibilityServiceInfo EventTypes management for battery conservation
        val isTarget = cachedConfig.hasActiveRulesForPackage(packageName)

        if (isTarget && !inAppEventsEnabled) {
            enableInAppEvents(service)
        } else if (!isTarget && inAppEventsEnabled) {
            disableInAppEvents(service)
        }

        if (!isTarget) {
            pendingRetryJobs.remove(packageName)?.cancel()
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
            pendingRetryJobs.remove(packageName)?.cancel()
            return true
        }

        // 2. Incremental Retry after 150ms for asynchronous UI rendering (without retaining AccessibilityNodeInfo)
        val retryJob = hookScope.launch {
            delay(150L)
            if (currentForegroundPackage == packageName && activeServiceProvider() === service) {
                val freshConfig = cachedConfig
                if (freshConfig.hasActiveRulesForPackage(packageName)) {
                    val freshPolicy = freshConfig.appPolicies[packageName]
                    if (freshPolicy != null) {
                        checkAndExecuteMatch(service, null, packageName, freshPolicy, width, height)
                    }
                }
            }
        }
        pendingRetryJobs.put(packageName, retryJob)?.cancel()
        retryJob.invokeOnCompletion {
            pendingRetryJobs.remove(packageName, retryJob)
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
                var claimed = false
                val triggerNow = SystemClock.elapsedRealtime()
                lastTriggerTime.compute(packageName) { _, existing ->
                    if (existing != null && triggerNow - existing < POST_TRIGGER_COOLDOWN_MS) {
                        existing
                    } else {
                        claimed = true
                        triggerNow
                    }
                }
                if (!claimed) {
                    return false
                }
                Timber.tag(TAG).i("Target page $pageId DETECTED in $packageName!")
                PageShieldReactionExecutor.executeReaction(service, packageName, pageId, policy)
                return true
            }
        }
        return false
    }

    internal fun enableInAppEvents(service: AccessibilityService) {
        try {
            val info = getServiceInfo(service) ?: AccessibilityServiceInfo()
            val extraFlags = AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
                AccessibilityEvent.TYPE_VIEW_CLICKED or
                AccessibilityEvent.TYPE_VIEW_SCROLLED
            val updatedFlags = info.eventTypes or extraFlags
            if (info.eventTypes != updatedFlags) {
                info.eventTypes = updatedFlags
                setServiceInfo(service, info)
                Timber.tag(TAG).d("Dynamically enabled in-app eventTypes for PageShield target")
            }
            inAppEventsEnabled = true
        } catch (e: Exception) {
            Timber.tag(TAG).w("Error updating serviceInfo to enable in-app events: ${e.message}")
        }
    }

    internal fun disableInAppEvents(service: AccessibilityService) {
        try {
            val info = getServiceInfo(service)
            if (info == null) {
                inAppEventsEnabled = false
                return
            }
            val inAppOnlyFlags = AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
                AccessibilityEvent.TYPE_VIEW_CLICKED
            val updatedFlags = info.eventTypes and inAppOnlyFlags.inv()
            if (info.eventTypes != updatedFlags) {
                info.eventTypes = updatedFlags
                setServiceInfo(service, info)
                Timber.tag(TAG).d("Dynamically reverted in-app eventTypes while preserving baseline events")
            }
            inAppEventsEnabled = false
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
