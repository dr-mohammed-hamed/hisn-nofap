package com.drmohammed.antiporn.waqfa.engine

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.PowerManager
import android.os.SystemClock
import com.drmohammed.antiporn.accessibility.bus.ForegroundAppEventBus
import com.drmohammed.antiporn.accessibility.control.AccessibilitySelfActivator
import com.drmohammed.antiporn.banking.BankingCloakManager
import com.drmohammed.antiporn.waqfa.model.WaqfaConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * مراقب التطبيق النشط في الواجهة لنظام «وَقْفَة».
 * يعمل بنمط هجين ذكي متوافق تماماً مع المادة 8 من الدستور (قانون الصفر بولينج):
 * - طالما أن [HisnAccessibilityService] متصلة ونشطة: يدخل في سبات تام (0% CPU / Zero Polling).
 * - في حال انقطاع الخدمة: يحاول أولاً الإفاقة الصامتة (مع مهلة تبريد 15 ثانية)،
 *   ثم ينشط كـ Fallback احتياطي بمعدل 400ms حصراً أثناء إضاءة الشاشة.
 * - عند تفعيل وضع التخفي البنكي (BankingCloakManager.isCloaked)، يستمر المراقب بالعمل
 *   حتى لو كان نظام «وَقْفَة» معطلاً، لرصد مغادرة تطبيق البنك واستعادة الحماية فوراً.
 */
class WaqfaForegroundWatcher internal constructor(
    context: Context,
    private val checkIntervalMs: Long = 400L,
    injectedUsageStatsManager: UsageStatsManager? = null,
    injectedEventBus: ForegroundAppEventBus? = null,
    private val injectedScheduler: WatcherScheduler? = null,
    private val sessionManagerProvider: ((Context) -> WaqfaSessionManager)? = null,
    private val configProvider: ((Context) -> WaqfaConfig)? = null
) {
    constructor(context: Context, checkIntervalMs: Long = 400L) : this(
        context = context,
        checkIntervalMs = checkIntervalMs,
        injectedUsageStatsManager = null,
        injectedEventBus = null,
        injectedScheduler = null,
        sessionManagerProvider = null,
        configProvider = null
    )

    private val context = context.applicationContext
    private var handlerThread: HandlerThread? = null
    private var dynamicScheduler: WatcherScheduler? = injectedScheduler

    private val scheduler: WatcherScheduler
        get() = synchronized(this) {
            dynamicScheduler ?: run {
                val ht = HandlerThread("WaqfaWatcherThread").apply { start() }
                handlerThread = ht
                HandlerWatcherScheduler(Handler(ht.looper)).also { dynamicScheduler = it }
            }
        }

    private val usageStatsManager: UsageStatsManager? =
        injectedUsageStatsManager ?: (this.context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager)

    private val eventBus: ForegroundAppEventBus =
        injectedEventBus ?: ForegroundAppEventBus.getInstance(this.context)

    private fun getSessionManager(): WaqfaSessionManager =
        sessionManagerProvider?.invoke(context) ?: WaqfaSessionManager.getInstance(context)

    private fun getConfig(): WaqfaConfig =
        configProvider?.invoke(context) ?: getSessionManager().getConfig()

    private fun getElapsedRealtime(): Long {
        return try {
            SystemClock.elapsedRealtime()
        } catch (e: Throwable) {
            System.currentTimeMillis()
        }
    }

    private var watcherScope: CoroutineScope? = null
    private var stateCollectorJob: Job? = null

    @Volatile
    internal var isRunning = false
        internal set

    @Volatile
    internal var isScreenOn = false
        internal set

    @Volatile
    internal var lastForegroundPackage: String = ""
        internal set

    @Volatile
    private var lastHealingAttemptTimeMs: Long = 0L

    internal val checkRunnable = object : Runnable {
        override fun run() {
            if (!isRunning || !isScreenOn) return

            // قانون الصفر بولينج: إذا كانت خدمة الوصول نشطة، يتوقف الفحص الدوري تماماً
            if (eventBus.isAccessibilityActive.value) {
                Timber.tag(TAG).d("HisnAccessibilityService is active; WaqfaForegroundWatcher dormant (Zero-Polling)")
                return
            }

            // محاولة الإفاقة الذاتية الصامتة مع مهلة تبريد 15 ثانية
            val now = getElapsedRealtime()
            if (now - lastHealingAttemptTimeMs >= SELF_HEALING_COOLDOWN_MS) {
                lastHealingAttemptTimeMs = now
                if (AccessibilitySelfActivator.enableSilently(context)) {
                    Timber.tag(TAG).i("Self-healing triggered HisnAccessibilityService activation; holding polling loop")
                    scheduler.postDelayed(this, checkIntervalMs * 2)
                    return
                }
            }

            val config = getConfig()
            val isCloaked = BankingCloakManager.isCloaked
            if ((!config.isEnabled || config.targetPackages.isEmpty()) && !isCloaked) {
                Timber.tag(TAG).d("Waqfa is disabled or has no targets; pausing foreground watcher loop")
                return
            }

            evaluateForegroundPackage(config)
            scheduler.postDelayed(this, checkIntervalMs)
        }
    }

    @Synchronized
    fun start() {
        if (isRunning) return
        isRunning = true

        stateCollectorJob?.cancel()
        stateCollectorJob = null
        watcherScope?.cancel()

        val scope = CoroutineScope(Dispatchers.Default)
        watcherScope = scope

        // مراقبة تفاعلية لحالة خدمة الوصول المركزية
        stateCollectorJob = scope.launch {
            eventBus.isAccessibilityActive.collect { active ->
                if (active) {
                    scheduler.removeCallbacks(checkRunnable)
                    Timber.tag(TAG).i("HisnAccessibilityService connected: Waqfa polling stopped (Zero-Polling Active)")
                } else if (isRunning && isScreenOn) {
                    Timber.tag(TAG).w("HisnAccessibilityService disconnected: Activating Waqfa fallback polling loop")
                    scheduler.removeCallbacks(checkRunnable)
                    scheduler.post(checkRunnable)
                }
            }
        }

        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val interactive = pm?.isInteractive ?: false
        isScreenOn = interactive

        if (!interactive) {
            Timber.tag(TAG).i("WaqfaForegroundWatcher started in standby: screen is not interactive")
            return
        }

        if (!eventBus.isAccessibilityActive.value) {
            scheduler.removeCallbacks(checkRunnable)
            scheduler.post(checkRunnable)
            Timber.tag(TAG).i("WaqfaForegroundWatcher fallback started (interval: ${checkIntervalMs}ms)")
        } else {
            Timber.tag(TAG).i("WaqfaForegroundWatcher started in dormant state (Accessibility 0ms active)")
        }
    }

    @Synchronized
    fun stop() {
        isRunning = false
        dynamicScheduler?.removeCallbacks(checkRunnable)
        stateCollectorJob?.cancel()
        stateCollectorJob = null
        watcherScope?.cancel()
        watcherScope = null
        lastForegroundPackage = ""
        handlerThread?.quitSafely()
        handlerThread = null
        if (injectedScheduler == null) {
            dynamicScheduler = null
        }
        Timber.tag(TAG).i("WaqfaForegroundWatcher stopped")
    }

    fun notifyConfigChanged() {
        if (isRunning && isScreenOn && !eventBus.isAccessibilityActive.value) {
            scheduler.removeCallbacks(checkRunnable)
            val config = getConfig()
            val isCloaked = BankingCloakManager.isCloaked
            if ((config.isEnabled && config.targetPackages.isNotEmpty()) || isCloaked) {
                scheduler.post(checkRunnable)
            }
        }
    }

    fun onScreenStateChanged(screenOn: Boolean) {
        isScreenOn = screenOn
        if (screenOn) {
            if (isRunning && !eventBus.isAccessibilityActive.value) {
                scheduler.removeCallbacks(checkRunnable)
                scheduler.post(checkRunnable)
            }
        } else {
            scheduler.removeCallbacks(checkRunnable)
            if (BankingCloakManager.isCloaked) {
                Timber.tag(TAG).i("Screen turned off while banking cloak active; restoring protection immediately")
                BankingCloakManager.restoreProtection(context)
            }
        }
        Timber.tag(TAG).d("WaqfaForegroundWatcher screen state: screenOn=$screenOn, a11yActive=${eventBus.isAccessibilityActive.value}")
    }

    internal fun evaluateForegroundPackage(config: WaqfaConfig) {
        val usm = usageStatsManager ?: return
        val isCloaked = BankingCloakManager.isCloaked
        if ((!config.isEnabled || config.targetPackages.isEmpty()) && !isCloaked) {
            return
        }

        val now = System.currentTimeMillis()
        val events = try {
            usm.queryEvents(now - (checkIntervalMs * 3), now)
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Error querying usage events in WaqfaForegroundWatcher")
            null
        } ?: return

        var latestForeground = ""
        val event = UsageEvents.Event()

        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            @Suppress("DEPRECATION")
            val isForeground = event.eventType == UsageEvents.Event.ACTIVITY_RESUMED ||
                event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND
            if (isForeground) {
                val pkg = event.packageName ?: ""
                if (pkg.isNotBlank()) {
                    latestForeground = pkg
                }
            }
        }

        if (latestForeground.isNotBlank()) {
            onForegroundPackageDetected(latestForeground)
        }
    }

    internal fun onForegroundPackageDetected(latestForeground: String) {
        if (latestForeground.isNotBlank()) {
            lastForegroundPackage = latestForeground
            eventBus.emitFromFallbackWatcher(latestForeground)
            BankingCloakManager.onForegroundPackageChanged(context, latestForeground)
        }
    }

    companion object {
        private const val TAG = "AntiPorn_WaqfaWatcher"
        private const val SELF_HEALING_COOLDOWN_MS = 15_000L // 15 ثانية

        @Volatile
        private var instance: WaqfaForegroundWatcher? = null

        fun getInstance(context: Context): WaqfaForegroundWatcher {
            return instance ?: synchronized(this) {
                instance ?: WaqfaForegroundWatcher(context.applicationContext).also { instance = it }
            }
        }

        fun setInstance(watcher: WaqfaForegroundWatcher?) {
            instance = watcher
        }
    }
}

/**
 * واجهة مجردة لجدولة المهام لتسهيل اختبار WaqfaForegroundWatcher دون الارتباط بـ Android Looper.
 */
interface WatcherScheduler {
    fun post(runnable: Runnable)
    fun postDelayed(runnable: Runnable, delayMs: Long)
    fun removeCallbacks(runnable: Runnable)
}

private class HandlerWatcherScheduler(private val handler: Handler) : WatcherScheduler {
    override fun post(runnable: Runnable) {
        handler.post(runnable)
    }

    override fun postDelayed(runnable: Runnable, delayMs: Long) {
        handler.postDelayed(runnable, delayMs)
    }

    override fun removeCallbacks(runnable: Runnable) {
        handler.removeCallbacks(runnable)
    }
}
