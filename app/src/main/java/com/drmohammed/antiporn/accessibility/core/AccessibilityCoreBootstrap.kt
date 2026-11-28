package com.drmohammed.antiporn.accessibility.core

import android.content.Context
import androidx.annotation.VisibleForTesting
import com.drmohammed.antiporn.core.model.DevicePolicyBundle
import com.drmohammed.antiporn.data.repository.PolicyRepository
import com.drmohammed.antiporn.pageshield.engine.PageShieldAccessibilityHook
import timber.log.Timber

/**
 * Idempotent bootstrapper that registers built-in [HisnAccessibilityModule] implementations into
 * [AccessibilityModuleRegistry] and seeds them with the active [DevicePolicyBundle].
 */
object AccessibilityCoreBootstrap {

    private const val TAG = "AntiPorn_A11yBoot"

    private val registrationLock = Any()

    private var modulesRegistered = false

    @Volatile
    private var initialized = false

    /**
     * Ensures accessibility modules are registered and notified of the current policy once.
     * Passing [initialPolicy] avoids re-entrant [PolicyRepository.getInstance] lookups during
     * [PolicyRepository] construction.
     */
    fun ensureInitialized(context: Context, initialPolicy: DevicePolicyBundle? = null) {
        ensureInitializedInternal(context, initialPolicy)
    }

    internal fun ensureInitializedInternal(
        context: Context,
        initialPolicy: DevicePolicyBundle? = null
    ): Boolean {
        if (initialized) return false
        val appContext = context.applicationContext ?: context
        synchronized(registrationLock) {
            if (!modulesRegistered) {
                AccessibilityModuleRegistry.register(PageShieldAccessibilityHook.getInstance())
                modulesRegistered = true
            }
        }

        val resolvedPolicy = initialPolicy ?: runCatching {
            PolicyRepository.getInstance(appContext).getCurrentPolicy()
        }.onFailure {
            Timber.tag(TAG).w(it, "Failed to resolve initial policy during AccessibilityCoreBootstrap; modules stay unseeded until a policy is supplied")
        }.getOrNull() ?: return false

        AccessibilityModuleRegistry.notifyPolicyUpdated(resolvedPolicy)
        initialized = true
        return true
    }

    @VisibleForTesting
    fun resetForTesting() {
        synchronized(registrationLock) {
            initialized = false
            modulesRegistered = false
        }
    }
}
