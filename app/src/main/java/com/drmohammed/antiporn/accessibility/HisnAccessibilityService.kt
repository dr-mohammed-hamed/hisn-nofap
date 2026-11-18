package com.drmohammed.antiporn.accessibility

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.os.Build
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import com.drmohammed.antiporn.accessibility.bus.ForegroundAppEventBus
import com.drmohammed.antiporn.accessibility.control.AccessibilityPolicyCoordinator
import com.drmohammed.antiporn.accessibility.control.AccessibilitySelfActivator
import com.drmohammed.antiporn.accessibility.filter.TransientWindowEventFilter
import com.drmohammed.antiporn.accessibility.hook.AccessibilityHookRegistry
import com.drmohammed.antiporn.core.model.DevicePolicyBundle
import com.drmohammed.antiporn.data.repository.PolicyRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber

/**
 * محرك خدمة الوصول المركزي لتطبيق الحصن (Hisn Accessibility Service).
 * يوفر رصداً لحظياً بسرعة 0ms لتغير نوافذ التطبيقات النشطة في الواجهة (TYPE_WINDOW_STATE_CHANGED).
 * يعمل بنمط الأحداث الصرفة (Zero-Polling) لتوفير 100% من استهلاك المعالج الناتج عن الفحص الدوري.
 */
class HisnAccessibilityService : AccessibilityService() {

    private val screenshotExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()
    private val aiEngineLock = Any()
    private lateinit var eventBus: ForegroundAppEventBus
    private lateinit var windowFilter: TransientWindowEventFilter
    @Volatile
    private var standaloneCoordinator: com.drmohammed.antiporn.ai.service.AiInferenceCoordinator? = null
    @Volatile
    private var disconnectNotified: Boolean = false

    override fun onCreate() {
        super.onCreate()
        eventBus = ForegroundAppEventBus.getInstance(applicationContext)
        windowFilter = TransientWindowEventFilter.getInstance(applicationContext)
        Timber.tag(TAG).i("HisnAccessibilityService created")
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        disconnectNotified = false

        val policyRepo = try {
            PolicyRepository.getInstance(applicationContext)
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Failed getting PolicyRepository in onServiceConnected; falling back to default")
            null
        }
        val policy = policyRepo?.getCurrentPolicy() ?: DevicePolicyBundle.defaultPolicy()
        val isInitialPolicyLoaded = policyRepo?.isInitialPolicyLoaded == true

        if (isInitialPolicyLoaded && !AccessibilityPolicyCoordinator.isAccessibilityRequired(policy)) {
            Timber.tag(TAG).i("HisnAccessibilityService connected but not required by policy; disabling self cleanly")
            AccessibilitySelfActivator.markIntentionalDisable()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                try {
                    disableSelf()
                } catch (e: Exception) {
                    Timber.tag(TAG).w(e, "Error calling disableSelf() in onServiceConnected")
                }
            }
            return
        }

        instance = this
        try {
            val info = serviceInfo ?: android.accessibilityservice.AccessibilityServiceInfo()
            info.flags = info.flags or
                android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                android.accessibilityservice.AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS or
                android.accessibilityservice.AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
            info.eventTypes = info.eventTypes or
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                AccessibilityEvent.TYPE_WINDOWS_CHANGED or
                AccessibilityEvent.TYPE_VIEW_SCROLLED
            serviceInfo = info
            com.drmohammed.antiporn.pageshield.engine.PageShieldAccessibilityHook.getInstance()
        } catch (e: Exception) {
            Timber.tag(TAG).w("Error updating serviceInfo: ${e.message}")
        }
        eventBus.setAccessibilityActive(true)

        // مزامنة محرك حارس غض البصر المستقل على أندرويد 14+ وفقاً للسياسة النشطة فقط
        syncStandaloneAiState(policy.isScreenAiEnabledGlobally)

        // مزامنة قدرات الجهاز بعد اتصال الخدمة
        AccessibilityPolicyCoordinator.notifyCapabilitiesChanged(applicationContext, recordTamper = false)

        Timber.tag(TAG).i("HisnAccessibilityService connected to Android Framework (Instant 0ms Engine Active)")
        com.drmohammed.antiporn.telemetry.BlackBoxRecorder.recordA11y(
            context = applicationContext,
            action = "SERVICE_CONNECTED",
            isSuccess = true,
            details = "Instant 0ms Engine Active"
        )
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val evt = event ?: return

        when (evt.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOWS_CHANGED -> {
                handleWindowStateChanged(evt)
            }
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> {
                standaloneCoordinator?.onViewScrolled()
                    ?: com.drmohammed.antiporn.ai.service.AiInferenceCoordinator.activeInstance?.onViewScrolled()
                AccessibilityHookRegistry.dispatchRawEvent(this, evt)
            }
            else -> {
                // إتاحة معالجة الأحداث المتقدمة لخطافات المرحلة القادمة (Phase 2)
                AccessibilityHookRegistry.dispatchRawEvent(this, evt)
            }
        }
    }

    private fun handleWindowStateChanged(event: AccessibilityEvent) {
        val packageName = event.packageName?.toString() ?: run {
            try {
                val win = windows.firstOrNull { it.isFocused && it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
                val root = win?.root
                val pkg = root?.packageName?.toString()
                try { root?.recycle() } catch (_: Exception) {}
                pkg
            } catch (_: Exception) {
                null
            }
        } ?: return
        val className = event.className?.toString()

        // 1. فلترة النوافذ العابرة ولوحات المفاتيح وواجهة النظام
        if (windowFilter.isTransientOrIgnored(packageName, className)) {
            return
        }

        // 2. فحص خطافات التوسع المستقبلي (مثل نظام التخفي الفوري لتطبيقات البنوك)
        if (AccessibilityHookRegistry.dispatchWindowState(this, packageName, className)) {
            Timber.tag(TAG).d("Window state event consumed by registered hook for $packageName")
            return
        }

        // 3. إرسال الحدث إلى الناقل المركزي لتغذية منظومة «وَقْفَة» ودرع الذكاء الاصطناعي
        eventBus.emitFromAccessibility(packageName, className)
    }

    override fun onInterrupt() {
        Timber.tag(TAG).w("HisnAccessibilityService onInterrupt called by system")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        Timber.tag(TAG).w("HisnAccessibilityService unbinding from system")
        // فصل نوافذ التعتيم المرتبطة بهذه النسخة لمنع ثغرة النافذة الميتة
        com.drmohammed.antiporn.ai.overlay.ScreenBlurOverlayManager.notifyServiceDestroyed(this)
        stopStandaloneAiEngine()
        if (instance === this) {
            instance = null
        }
        if (::eventBus.isInitialized) {
            eventBus.setAccessibilityActive(false)
        }
        notifyDisconnectedOnce()
        com.drmohammed.antiporn.telemetry.BlackBoxRecorder.recordA11y(
            context = applicationContext,
            action = "SERVICE_UNBIND",
            isSuccess = true
        )
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        Timber.tag(TAG).i("HisnAccessibilityService destroyed")
        com.drmohammed.antiporn.ai.overlay.ScreenBlurOverlayManager.notifyServiceDestroyed(this)
        stopStandaloneAiEngine()
        try {
            screenshotExecutor.shutdown()
        } catch (_: Exception) {}
        if (instance === this) {
            instance = null
        }
        if (::eventBus.isInitialized) {
            eventBus.setAccessibilityActive(false)
        }
        notifyDisconnectedOnce()
        com.drmohammed.antiporn.telemetry.BlackBoxRecorder.recordA11y(
            context = applicationContext,
            action = "SERVICE_DESTROYED",
            isSuccess = true
        )
    }

    private fun notifyDisconnectedOnce() {
        if (!disconnectNotified) {
            disconnectNotified = true
            try {
                com.drmohammed.antiporn.pageshield.engine.PageShieldAccessibilityHook.getInstance().onServiceDisconnected()
            } catch (e: Exception) {
                Timber.tag(TAG).w(e, "Error notifying PageShieldAccessibilityHook of disconnect")
            }
            AccessibilityPolicyCoordinator.onServiceDisconnected(applicationContext)
            AccessibilitySelfActivator.clearIntentionalDisable()
        }
    }

    /**
     * هل التقاط الشباك النظيف بدون طبقة التعتيم مدعوم في النظام الحالي.
     * متاح حصرياً على أندرويد 14+ (API 34+) لأن takeScreenshot في أندرويد 11-13 تلتقط التعتيم أيضاً.
     */
    fun isCleanWindowCaptureSupported(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE

    /**
     * أخذ لقطة شاشة نظيفة 100% للنافذة التفاعلية بدون طبقة التعتيم (TYPE_ACCESSIBILITY_OVERLAY).
     * متاح على نظام أندرويد 14+ (API 34+) عبر takeScreenshotOfWindow مع استخراج حدود الشباك بدقة
     * وإرجاع كود الخطأ errorCode للجدولة التكيفية وتجاهل أخطاء FLAG_SECURE بصمت.
     */
    fun captureCleanWindowBitmap(onResult: (Bitmap?, Rect, Int) -> Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val interactiveWindow = try {
                windows.mapNotNull { win ->
                    if (win.type != AccessibilityWindowInfo.TYPE_APPLICATION) return@mapNotNull null
                    val root = win.root
                    val pkg = root?.packageName?.toString()
                    try { root?.recycle() } catch (_: Exception) {}
                    if (pkg == null || pkg == applicationContext.packageName) return@mapNotNull null
                    Pair(win, pkg)
                }.maxByOrNull { (win, pkg) ->
                    val b = Rect()
                    win.getBoundsInScreen(b)
                    val area = (b.right - b.left).toLong() * (b.bottom - b.top).toLong()
                    val targetBonus = if (com.drmohammed.antiporn.ai.core.policy.AiExemptionRegistry.isTarget(pkg)) 2_000_000_000L else 0L
                    val focusBonus = if (win.isFocused) 1_000_000_000L else 0L
                    targetBonus + focusBonus + area
                }?.first
            } catch (e: Exception) {
                null
            }

            if (interactiveWindow != null) {
                val windowBounds = Rect()
                try {
                    interactiveWindow.getBoundsInScreen(windowBounds)
                } catch (e: Exception) {
                    Timber.tag(TAG).w("Error getting window bounds: ${e.message}")
                }

                try {
                    takeScreenshotOfWindow(
                        interactiveWindow.id,
                        screenshotExecutor,
                        object : TakeScreenshotCallback {
                            override fun onSuccess(screenshotResult: ScreenshotResult) {
                                val hwBuffer = screenshotResult.hardwareBuffer
                                val colorSpace = screenshotResult.colorSpace
                                val bitmap = try {
                                    val wrapped = Bitmap.wrapHardwareBuffer(hwBuffer, colorSpace)
                                    if (wrapped != null) {
                                        val origW = wrapped.width
                                        val origH = wrapped.height
                                        val maxDim = 924
                                        val scale = minOf(1.0f, maxDim.toFloat() / maxOf(origW, origH).toFloat())
                                        val targetW = (origW * scale).toInt().coerceAtLeast(1)
                                        val targetH = (origH * scale).toInt().coerceAtLeast(1)
                                        val scaled = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
                                        val canvas = Canvas(scaled)
                                        val paint = Paint(Paint.FILTER_BITMAP_FLAG)
                                        canvas.drawBitmap(wrapped, null, Rect(0, 0, targetW, targetH), paint)
                                        try { wrapped.recycle() } catch (_: Exception) {}
                                        scaled
                                    } else {
                                        null
                                    }
                                } catch (e: Exception) {
                                    Timber.tag(TAG).w("Error converting hardware buffer to bitmap: ${e.message}")
                                    null
                                } finally {
                                    try { hwBuffer.close() } catch (_: Exception) {}
                                }
                                onResult(bitmap, windowBounds, 0)
                            }

                            override fun onFailure(errorCode: Int) {
                                // تجاهل أخطاء FLAG_SECURE وأخطاء النافذة بصمت دون السقوط لـ MediaProjection
                                Timber.tag(TAG).d("takeScreenshotOfWindow failed (code: $errorCode)")
                                onResult(null, windowBounds, errorCode)
                            }
                        }
                    )
                    return
                } catch (e: Exception) {
                    Timber.tag(TAG).w("Error invoking takeScreenshotOfWindow: ${e.message}")
                }
            }
        }

        // على الأنظمة الأقدم من أندرويد 14، لا يوجد التقاط نافذة معزول بدون حجب ذاتي
        onResult(null, Rect(), -1)
    }

    fun captureCleanWindowBitmap(onResult: (Bitmap?, Rect) -> Unit) {
        captureCleanWindowBitmap { bitmap, bounds, _ -> onResult(bitmap, bounds) }
    }

    /**
     * يزامن حالة محرك حارس غض البصر المستقل (API 34+) وفقاً لتفعيل الميزة في السياسة النشطة.
     * محمي بقفل متزامن وفحص مزدوج لضمان عدم تشغيل نسختين من [AiInferenceCoordinator] بالتوازي أبداً.
     */
    fun syncStandaloneAiState(enabled: Boolean) {
        synchronized(aiEngineLock) {
            if (enabled &&
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
                !com.drmohammed.antiporn.ai.service.ScreenAiProjectionService.isRunning
            ) {
                initStandaloneAiEngineLocked()
            } else {
                stopStandaloneAiEngineLocked()
            }
        }
    }

    private fun initStandaloneAiEngineLocked() {
        if (standaloneCoordinator != null) return
        try {
            val coordinator = com.drmohammed.antiporn.ai.service.AiInferenceCoordinator(
                context = applicationContext
            )
            standaloneCoordinator = coordinator
            coordinator.initialize { success ->
                var shouldStopAbandoned = false
                synchronized(aiEngineLock) {
                    val projectionRunning = com.drmohammed.antiporn.ai.service.ScreenAiProjectionService.isRunning
                    if (success && standaloneCoordinator === coordinator && !projectionRunning) {
                        coordinator.start()
                        updateStandaloneAiActive(true)
                        Timber.tag(TAG).i("Standalone Vision Guard engine active inside HisnAccessibilityService on API 34+")
                    } else {
                        if (standaloneCoordinator === coordinator) {
                            standaloneCoordinator = null
                        }
                        updateStandaloneAiActive(false)
                        shouldStopAbandoned = true
                        Timber.tag(TAG).w(
                            "Standalone Vision Guard engine aborted or failed init " +
                                "(success=$success, projectionRunning=$projectionRunning)"
                        )
                    }
                }
                if (shouldStopAbandoned) {
                    try {
                        coordinator.stop()
                    } catch (e: Exception) {
                        Timber.tag(TAG).w("Error cleaning up aborted standalone Vision Guard coordinator: ${e.message}")
                    }
                }
            }
        } catch (e: Exception) {
            standaloneCoordinator = null
            updateStandaloneAiActive(false)
            Timber.tag(TAG).e(e, "Error starting standalone Vision Guard engine in A11y service")
        }
    }

    private fun stopStandaloneAiEngine() {
        synchronized(aiEngineLock) {
            stopStandaloneAiEngineLocked()
        }
    }

    private fun stopStandaloneAiEngineLocked() {
        val existing = standaloneCoordinator
        standaloneCoordinator = null
        updateStandaloneAiActive(false)
        try {
            existing?.stop()
        } catch (e: Exception) {
            Timber.tag(TAG).w("Error stopping standalone Vision Guard: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "AntiPorn_A11yService"

        @Volatile
        var instance: HisnAccessibilityService? = null
            private set

        @Volatile
        var isStandaloneAiActive: Boolean = false
            private set

        private val _isStandaloneAiActiveFlow = MutableStateFlow(false)
        val isStandaloneAiActiveFlow: StateFlow<Boolean> = _isStandaloneAiActiveFlow.asStateFlow()

        private fun updateStandaloneAiActive(active: Boolean) {
            isStandaloneAiActive = active
            _isStandaloneAiActiveFlow.value = active
        }
    }
}
