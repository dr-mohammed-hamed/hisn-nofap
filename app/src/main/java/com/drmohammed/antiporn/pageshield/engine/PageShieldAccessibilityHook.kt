package com.drmohammed.antiporn.pageshield.engine

import android.accessibilityservice.AccessibilityService
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import com.drmohammed.antiporn.accessibility.HisnAccessibilityService
import com.drmohammed.antiporn.accessibility.control.AccessibilityRequirementReason
import com.drmohammed.antiporn.accessibility.core.HisnAccessibilityModule
import com.drmohammed.antiporn.accessibility.core.ModuleDispatchResult
import com.drmohammed.antiporn.accessibility.util.AccessibilityThrottle
import com.drmohammed.antiporn.core.model.DevicePolicyBundle
import com.drmohammed.antiporn.pageshield.model.AppPageShieldPolicy
import com.drmohammed.antiporn.pageshield.model.PageShieldConfig
import com.drmohammed.antiporn.pageshield.registry.PageShieldRegistry
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * خطاف خدمة الوصول لدرع الصفحات (Page Shield Accessibility Module).
 * يراقب ويتعرف على الصفحات المحظورة داخل التطبيقات المستهدفة بأقصى كفاءة للبطارية وخنق زمني دقيق.
 */
class PageShieldAccessibilityHook internal constructor(
    private val activeServiceProvider: () -> AccessibilityService? = { HisnAccessibilityService.instance },
    private val refreshCapabilitiesCallback: ((AccessibilityService) -> Unit)? = null,
    private val clockMs: () -> Long = { defaultElapsedRealtime() },
    private val eventPackageNameProvider: (AccessibilityEvent) -> String? = { it.packageName?.toString() },
    private val isPasswordEventProvider: (AccessibilityEvent) -> Boolean = { it.isPassword },
    private val pageDetector: (AccessibilityService, AccessibilityEvent?, String, String, Int, Int) -> Boolean = { service, event, packageName, pageId, width, height ->
        PageShieldRegistry.findPage(packageName, pageId)?.detectorStrategy?.isPageDetected(
            service = service,
            event = event,
            screenWidth = width,
            screenHeight = height
        ) == true
    },
    private val reactionExecutor: (AccessibilityService, String, String, AppPageShieldPolicy) -> Unit = { service, packageName, pageId, policy ->
        PageShieldReactionExecutor.executeReaction(service, packageName, pageId, policy)
    },
    private val displaySizeProvider: (AccessibilityService) -> Pair<Int, Int>? = { service ->
        val metrics = try {
            service.resources?.displayMetrics
        } catch (e: Exception) {
            Timber.tag(TAG).d(e, "Failed reading displayMetrics from AccessibilityService")
            null
        }
        if (metrics == null || metrics.widthPixels <= 0 || metrics.heightPixels <= 0) {
            null
        } else {
            metrics.widthPixels to metrics.heightPixels
        }
    }
) : HisnAccessibilityModule {

    override val moduleId: String = MODULE_ID
    override val priority: Int = 30
    override val requirementReason: AccessibilityRequirementReason = AccessibilityRequirementReason.PAGE_SHIELD

    private val hookScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile
    private var cachedConfig: PageShieldConfig = PageShieldConfig()

    @Volatile
    private var currentForegroundPackage: String? = null

    private val eventThrottle = AccessibilityThrottle(clockMs)
    private val lastTriggerTime = ConcurrentHashMap<String, Long>()
    private val pendingRetryJobs = ConcurrentHashMap<String, Job>()

    override fun isRequired(policy: DevicePolicyBundle, sdkInt: Int): Boolean =
        policy.pageShieldConfig.isEnabled && policy.pageShieldConfig.getTargetPackages().isNotEmpty()

    override fun requiredEventTypes(foregroundPackage: String?): Int {
        return if (foregroundPackage != null && cachedConfig.hasActiveRulesForPackage(foregroundPackage)) {
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
                AccessibilityEvent.TYPE_VIEW_CLICKED or
                AccessibilityEvent.TYPE_VIEW_SCROLLED
        } else {
            0
        }
    }

    override fun onPolicyUpdated(policy: DevicePolicyBundle) {
        updateConfig(policy.pageShieldConfig)
    }

    fun updateConfig(config: PageShieldConfig) {
        cachedConfig = config
        Timber.tag(TAG).i(
            "PageShield config updated: isEnabled=%b, targets=%s",
            config.isEnabled,
            config.getTargetPackages()
        )
        val activeService = activeServiceProvider()
        if (activeService != null) {
            if (refreshCapabilitiesCallback != null) {
                refreshCapabilitiesCallback.invoke(activeService)
            } else {
                (activeService as? HisnAccessibilityService)?.refreshCapabilities()
            }
        }
    }

    override fun onServiceConnected(service: AccessibilityService) {
        Timber.tag(TAG).d("PageShield module connected to AccessibilityService")
    }

    override fun onServiceDisconnected() {
        currentForegroundPackage = null
        pendingRetryJobs.values.forEach { it.cancel() }
        pendingRetryJobs.clear()
    }

    override fun onForegroundChanged(
        service: AccessibilityService,
        packageName: String,
        className: String?,
        event: AccessibilityEvent?,
        alreadyBlockedByHigherPriority: Boolean
    ): ModuleDispatchResult {
        currentForegroundPackage = packageName
        if (alreadyBlockedByHigherPriority ||
            (event != null && isPasswordEventProvider(event)) ||
            !cachedConfig.hasActiveRulesForPackage(packageName)
        ) {
            pendingRetryJobs.remove(packageName)?.cancel()
            return ModuleDispatchResult.IGNORED
        }
        return evaluatePackageEvents(service, event, packageName)
    }

    override fun onEvent(
        service: AccessibilityService,
        event: AccessibilityEvent,
        alreadyBlockedByHigherPriority: Boolean
    ): ModuleDispatchResult {
        if (alreadyBlockedByHigherPriority || isPasswordEventProvider(event)) {
            return ModuleDispatchResult.IGNORED
        }
        val packageName = eventPackageNameProvider(event) ?: return ModuleDispatchResult.IGNORED

        // O(1) Package Gate: Reject immediately if package has no active rules in current policy
        if (!cachedConfig.hasActiveRulesForPackage(packageName)) {
            return ModuleDispatchResult.IGNORED
        }

        // Throttle check frequency to once every 300ms
        if (!eventThrottle.shouldAllow(THROTTLE_KEY, THROTTLE_INTERVAL_MS)) {
            return ModuleDispatchResult.IGNORED
        }

        return evaluatePackageEvents(service, event, packageName)
    }

    private fun evaluatePackageEvents(
        service: AccessibilityService,
        event: AccessibilityEvent?,
        packageName: String
    ): ModuleDispatchResult {
        // Post-Trigger Cooldown check
        val now = clockMs()
        val lastTrigger = lastTriggerTime[packageName]
        if (lastTrigger != null && now - lastTrigger < POST_TRIGGER_COOLDOWN_MS) {
            return ModuleDispatchResult.OBSERVED
        }

        val policy = cachedConfig.appPolicies[packageName] ?: return ModuleDispatchResult.IGNORED
        val (width, height) = displaySizeProvider(service) ?: return ModuleDispatchResult.IGNORED

        // 1. Direct Evaluation
        if (checkAndExecuteMatch(service, event, packageName, policy, width, height)) {
            pendingRetryJobs.remove(packageName)?.cancel()
            return ModuleDispatchResult.ACTION_TAKEN
        }

        // 2. Incremental Retry after 150ms for asynchronous UI rendering (without retaining AccessibilityNodeInfo)
        val retryJob = hookScope.launch {
            try {
                delay(150L)
                if (currentForegroundPackage == packageName && activeServiceProvider() === service) {
                    val freshConfig = cachedConfig
                    if (freshConfig.hasActiveRulesForPackage(packageName)) {
                        val freshPolicy = freshConfig.appPolicies[packageName]
                        if (freshPolicy != null) {
                            val retrySize = displaySizeProvider(service) ?: return@launch
                            checkAndExecuteMatch(
                                service = service,
                                event = null,
                                packageName = packageName,
                                policy = freshPolicy,
                                screenWidth = retrySize.first,
                                screenHeight = retrySize.second
                            )
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.tag(TAG).w(e, "Error in delayed PageShield evaluation for %s", packageName)
            }
        }
        pendingRetryJobs.put(packageName, retryJob)?.cancel()
        retryJob.invokeOnCompletion {
            pendingRetryJobs.remove(packageName, retryJob)
        }

        return ModuleDispatchResult.OBSERVED
    }

    private fun checkAndExecuteMatch(
        service: AccessibilityService,
        event: AccessibilityEvent?,
        packageName: String,
        policy: AppPageShieldPolicy,
        screenWidth: Int,
        screenHeight: Int
    ): Boolean {
        val now = clockMs()
        val lastTrigger = lastTriggerTime[packageName]
        if (lastTrigger != null && now - lastTrigger < POST_TRIGGER_COOLDOWN_MS) {
            return false
        }

        for (pageId in policy.enabledPageIds) {
            val detected = pageDetector(service, event, packageName, pageId, screenWidth, screenHeight)

            if (detected) {
                var claimed = false
                val triggerNow = clockMs()
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
                reactionExecutor(service, packageName, pageId, policy)
                return true
            }
        }
        return false
    }

    companion object {
        const val MODULE_ID = "page_shield"
        private const val TAG = "AntiPorn_PageShieldHook"
        private const val THROTTLE_KEY = "page_shield_event"
        private const val THROTTLE_INTERVAL_MS = 300L
        private const val POST_TRIGGER_COOLDOWN_MS = 1200L
        private val clockFallbackLogged = AtomicBoolean(false)

        private fun defaultElapsedRealtime(): Long {
            return try {
                SystemClock.elapsedRealtime()
            } catch (e: RuntimeException) {
                if (clockFallbackLogged.compareAndSet(false, true)) {
                    Timber.tag(TAG).d(e, "SystemClock.elapsedRealtime unavailable; falling back to nanoTime")
                }
                System.nanoTime() / 1_000_000L
            }
        }

        @Volatile
        private var instance: PageShieldAccessibilityHook? = null

        fun getInstance(): PageShieldAccessibilityHook {
            return instance ?: synchronized(this) {
                instance ?: PageShieldAccessibilityHook().also {
                    instance = it
                }
            }
        }
    }
}
