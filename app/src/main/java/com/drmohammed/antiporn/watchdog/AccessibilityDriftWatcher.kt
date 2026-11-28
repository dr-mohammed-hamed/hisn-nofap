package com.drmohammed.antiporn.watchdog

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.os.SystemClock
import android.provider.Settings
import com.drmohammed.antiporn.accessibility.control.AccessibilityPolicyCoordinator
import com.drmohammed.antiporn.accessibility.control.AccessibilitySelfActivator
import com.drmohammed.antiporn.core.engine.ProtectionTransitionGuard
import com.drmohammed.antiporn.telemetry.BlackBoxRecorder
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import timber.log.Timber

/**
 * Monitors `Settings.Secure` accessibility setting changes on a background [HandlerThread]
 * and performs rate-limited, backoff-protected silent healing when required by active policy.
 */
internal class AccessibilityDriftWatcher(
    private val isActiveGeneration: (Long) -> Boolean,
    private val clockMs: () -> Long = { safeElapsedRealtime() }
) {

    companion object {
        private const val TAG = "AntiPorn_A11yDriftWatch"
        internal const val RATE_LIMIT_MS = 1_500L
        internal const val FAILURE_WINDOW_MS = 30_000L
        internal const val MAX_CONSECUTIVE_FAILURES = 3
        internal const val BACKOFF_DURATION_MS = 60_000L

        private val fallbackLogged = AtomicBoolean(false)

        private fun safeElapsedRealtime(): Long {
            return try {
                SystemClock.elapsedRealtime()
            } catch (e: RuntimeException) {
                if (fallbackLogged.compareAndSet(false, true)) {
                    Timber.tag(TAG).d(e, "SystemClock.elapsedRealtime unavailable; falling back to nanoTime")
                }
                System.nanoTime() / 1_000_000L
            }
        }
    }

    private val lifecycleLock = Any()
    private val healLock = Any()
    private val activeGeneration = AtomicLong(0L)

    @Volatile
    private var registeredRuntimeGeneration: Long = 0L

    private var a11yObserverThread: HandlerThread? = null
    private var a11yObserver: ContentObserver? = null

    private var lastHealGeneration: Long = -1L
    private var hasAttempted: Boolean = false
    private var lastAttemptMs: Long = -1L
    private var isInBackoff: Boolean = false
    private var backoffUntilMs: Long = -1L
    private val failureTimestamps = ArrayDeque<Long>()

    fun register(appContext: Context, gen: Long) {
        val safeContext = appContext.applicationContext ?: appContext
        synchronized(lifecycleLock) {
            registeredRuntimeGeneration = gen
            if (a11yObserver != null) return

            val observerThread = HandlerThread(
                "AntiPorn_A11yObserver",
                Process.THREAD_PRIORITY_BACKGROUND
            ).apply { start() }

            val observerGen = activeGeneration.incrementAndGet()
            val observer = object : ContentObserver(Handler(observerThread.looper)) {
                override fun onChange(selfChange: Boolean, uri: Uri?) {
                    val runtimeGen = registeredRuntimeGeneration
                    if (!isActiveGeneration(runtimeGen) || observerGen != activeGeneration.get()) return
                    try {
                        evaluateAndHeal(safeContext, uri = uri, gen = observerGen, runtimeGen = runtimeGen)
                    } catch (e: Exception) {
                        Timber.tag(TAG).e(e, "Error handling accessibility drift change (uri=$uri)")
                    }
                }
            }

            try {
                val resolver = safeContext.contentResolver
                if (resolver == null) {
                    activeGeneration.incrementAndGet()
                    Timber.tag(TAG).w("ContentResolver unavailable; skipping Accessibility observer registration")
                    observerThread.quitSafely()
                    return
                }
                val enabledServicesUri = Settings.Secure.getUriFor(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
                val accessibilityEnabledUri = Settings.Secure.getUriFor(Settings.Secure.ACCESSIBILITY_ENABLED)
                if (enabledServicesUri == null && accessibilityEnabledUri == null) {
                    activeGeneration.incrementAndGet()
                    Timber.tag(TAG).w("Settings.Secure URIs for Accessibility unavailable; skipping observer registration")
                    observerThread.quitSafely()
                    return
                }
                if (enabledServicesUri != null) {
                    resolver.registerContentObserver(enabledServicesUri, false, observer)
                }
                if (accessibilityEnabledUri != null) {
                    resolver.registerContentObserver(accessibilityEnabledUri, false, observer)
                }
                a11yObserverThread = observerThread
                a11yObserver = observer
                Timber.tag(TAG).i("ContentObserver registered for Accessibility settings on dedicated HandlerThread")
            } catch (e: Exception) {
                activeGeneration.incrementAndGet()
                try {
                    safeContext.contentResolver?.unregisterContentObserver(observer)
                } catch (cleanupErr: Exception) {
                    Timber.tag(TAG).d(cleanupErr, "Error unregistering accessibility observer during cleanup")
                }
                try {
                    observerThread.quitSafely()
                } catch (quitErr: Exception) {
                    Timber.tag(TAG).w(quitErr, "Error quitting accessibility observer thread during cleanup")
                }
                a11yObserver = null
                a11yObserverThread = null
                Timber.tag(TAG).e(e, "Failed to register ContentObserver for Accessibility settings")
            }
        }
    }

    fun unregister(appContext: Context) {
        val safeContext = appContext.applicationContext ?: appContext
        synchronized(lifecycleLock) {
            activeGeneration.incrementAndGet()
            a11yObserver?.let { observer ->
                try {
                    val resolver = safeContext.contentResolver
                    if (resolver != null) {
                        resolver.unregisterContentObserver(observer)
                        Timber.tag(TAG).i("Accessibility ContentObserver unregistered")
                    }
                } catch (e: Exception) {
                    Timber.tag(TAG).e(e, "Failed to unregister Accessibility ContentObserver")
                }
                a11yObserver = null
            }
            a11yObserverThread?.let { thread ->
                try {
                    thread.quitSafely()
                } catch (e: Exception) {
                    Timber.tag(TAG).w(e, "Failed to quit Accessibility observer HandlerThread")
                }
                a11yObserverThread = null
            }
        }
    }

    internal fun evaluateAndHeal(
        appContext: Context,
        uri: Uri? = null,
        gen: Long = activeGeneration.get(),
        runtimeGen: Long? = null,
        isTeardownInProgress: () -> Boolean = { ProtectionTransitionGuard.isTeardownInProgress },
        isIntentionalDisableActive: () -> Boolean = { AccessibilitySelfActivator.isIntentionalDisableActive() },
        isCloaked: (Context) -> Boolean = { AccessibilitySelfActivator.isCloaked(it) },
        isAccessibilityRequired: (Context) -> Boolean = { AccessibilityPolicyCoordinator.isAccessibilityRequired(it) },
        isServiceEnabledInSettings: (Context) -> Boolean = { AccessibilitySelfActivator.isServiceEnabledInSettings(it) },
        canWriteSecureSettings: (Context) -> Boolean = { AccessibilitySelfActivator.canWriteSecureSettings(it) },
        enableSilently: (Context) -> Boolean = { AccessibilitySelfActivator.enableSilently(it) },
        recordA11y: (Context, String, Boolean, String?) -> Unit = { ctx, action, ok, details ->
            BlackBoxRecorder.recordA11y(ctx, action = action, isSuccess = ok, details = details)
        }
    ): Boolean = synchronized(healLock) {
        val safeContext = appContext.applicationContext ?: appContext
        val currentGen = activeGeneration.get()
        if (gen != currentGen) return false
        if (runtimeGen != null && !isActiveGeneration(runtimeGen)) return false
        if (lastHealGeneration != currentGen) {
            lastHealGeneration = currentGen
            hasAttempted = false
            lastAttemptMs = -1L
            isInBackoff = false
            backoffUntilMs = -1L
            failureTimestamps.clear()
        }
        if (isTeardownInProgress()) return false
        if (isIntentionalDisableActive()) return false
        if (isCloaked(safeContext)) return false
        if (!isAccessibilityRequired(safeContext)) return false

        if (isServiceEnabledInSettings(safeContext)) return false
        if (!canWriteSecureSettings(safeContext)) return false

        val now = clockMs()
        if (isInBackoff) {
            if ((backoffUntilMs - now) > 0L) {
                return false
            }
            isInBackoff = false
            backoffUntilMs = -1L
        }

        if (hasAttempted && (now - lastAttemptMs) in 0L until RATE_LIMIT_MS) {
            return false
        }

        if (gen != activeGeneration.get() ||
            (runtimeGen != null && !isActiveGeneration(runtimeGen)) ||
            isTeardownInProgress() ||
            isIntentionalDisableActive() ||
            isCloaked(safeContext) ||
            !isAccessibilityRequired(safeContext)
        ) {
            return false
        }

        hasAttempted = true
        lastAttemptMs = now

        val healed = enableSilently(safeContext)
        recordA11y(safeContext, "A11Y_DRIFT_FAST_HEAL", healed, "uri=$uri")

        if (healed) {
            failureTimestamps.clear()
            isInBackoff = false
            backoffUntilMs = -1L
            Timber.tag(TAG).i("Accessibility drift fast-healed silently (uri=$uri)")
            return true
        }

        while (failureTimestamps.isNotEmpty() && (now - failureTimestamps.first()) > FAILURE_WINDOW_MS) {
            failureTimestamps.removeFirst()
        }
        failureTimestamps.addLast(now)

        if (failureTimestamps.size >= MAX_CONSECUTIVE_FAILURES) {
            backoffUntilMs = now + BACKOFF_DURATION_MS
            isInBackoff = true
            failureTimestamps.clear()
            Timber.tag(TAG).w(
                "Accessibility drift fast-heal failed $MAX_CONSECUTIVE_FAILURES times; entering ${BACKOFF_DURATION_MS}ms backoff"
            )
            recordA11y(
                safeContext,
                "A11Y_DRIFT_HEAL_BACKOFF",
                false,
                "backoffMs=$BACKOFF_DURATION_MS, failures=$MAX_CONSECUTIVE_FAILURES"
            )
        } else {
            Timber.tag(TAG).w(
                "Accessibility drift fast-heal failed (uri=$uri, recentFailures=${failureTimestamps.size})"
            )
        }
        return false
    }
}
