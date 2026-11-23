package com.drmohammed.antiporn.accessibility.control

import android.content.Context
import android.os.Build
import androidx.annotation.VisibleForTesting
import com.drmohammed.antiporn.accessibility.HisnAccessibilityService
import com.drmohammed.antiporn.core.model.DevicePolicyBundle
import com.drmohammed.antiporn.data.repository.PolicyRepository
import com.drmohammed.antiporn.telemetry.BlackBoxRecorder
import timber.log.Timber

/**
 * الأسباب السياساتية التي تستوجب تشغيل خدمة الوصول [HisnAccessibilityService].
 */
enum class AccessibilityRequirementReason {
    PAGE_SHIELD,
    WAQFA_INSTANT,
    VISION_AI
}

/**
 * نتيجة مطابقة حالة خدمة الوصول مع السياسة النشطة الحالية.
 */
sealed class AccessibilityReconcileResult {
    object NotRequiredAlreadyOff : AccessibilityReconcileResult()
    object DisabledCleanly : AccessibilityReconcileResult()
    object AlreadyActive : AccessibilityReconcileResult()
    object EnabledSilently : AccessibilityReconcileResult()
    object SkippedCloaked : AccessibilityReconcileResult()
    data class NeedsManualActivation(val reasons: Set<AccessibilityRequirementReason>) : AccessibilityReconcileResult()
}

/**
 * المنسق المركزي لسياسة خدمة الوصول (Accessibility Policy Coordinator).
 * يضمن مبدأ "صفر خدمة وصول عند عدم الحاجة" (Zero-Accessibility-When-Unused)،
 * ويطابق تشغيل وإيقاف الخدمة ومحرّك الذكاء الاصطناعي المستقل بدقة تامة وفقاً للسياسة النشطة.
 */
object AccessibilityPolicyCoordinator {

    private const val TAG = "AntiPorn_A11yCoord"

    /**
     * خطاف اختياري لمزامنة قدرات الجهاز وحالة التلاعب مع السحابة دون اعتماد دائري.
     * المعامل الثاني (recordTamper: Boolean) يكون true عند رصد تعطيل غير متوقع للخدمة تحت الإشراف.
     */
    @Volatile
    var onCapabilitiesChangedCallback: ((Context, Boolean) -> Unit)? = null

    @VisibleForTesting
    @Volatile
    internal var policyResolverOverride: ((Context) -> DevicePolicyBundle?)? = null

    /**
     * يحسب مجموعة الأسباب التي تتطلب تفعيل خدمة الوصول بناءً على [policy] وإصدار أندرويد.
     */
    fun getRequiredReasons(
        policy: DevicePolicyBundle,
        sdkInt: Int = Build.VERSION.SDK_INT
    ): Set<AccessibilityRequirementReason> {
        val reasons = mutableSetOf<AccessibilityRequirementReason>()
        if (policy.pageShieldConfig.isEnabled && policy.pageShieldConfig.getTargetPackages().isNotEmpty()) {
            reasons.add(AccessibilityRequirementReason.PAGE_SHIELD)
        }
        if (policy.waqfaConfig.isEnabled &&
            policy.waqfaConfig.targetPackages.isNotEmpty() &&
            policy.waqfaConfig.useInstantAccessibilityMode
        ) {
            reasons.add(AccessibilityRequirementReason.WAQFA_INSTANT)
        }
        if (policy.isScreenAiEnabledGlobally && sdkInt >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            reasons.add(AccessibilityRequirementReason.VISION_AI)
        }
        return reasons
    }

    /**
     * هل تتطلب السياسة الممررة تشغيل خدمة الوصول؟
     */
    fun isAccessibilityRequired(
        policy: DevicePolicyBundle,
        sdkInt: Int = Build.VERSION.SDK_INT
    ): Boolean = getRequiredReasons(policy, sdkInt).isNotEmpty()

    /**
     * هل تتطلب السياسة الحالية المخزنة في [PolicyRepository] تشغيل خدمة الوصول؟
     */
    fun isAccessibilityRequired(context: Context): Boolean {
        val policy = resolvePolicySafely(context) ?: return false
        return isAccessibilityRequired(policy)
    }

    /**
     * هل تتطلب السياسة الحالية صلاحية الوصول لاستخدام التطبيقات (PACKAGE_USAGE_STATS)؟
     */
    fun isUsageAccessRequired(
        policy: DevicePolicyBundle,
        isA11yActive: Boolean = false,
        sdkInt: Int = Build.VERSION.SDK_INT
    ): Boolean {
        val waqfaStandardNeedsUsage = policy.waqfaConfig.isEnabled &&
            policy.waqfaConfig.targetPackages.isNotEmpty() &&
            !policy.waqfaConfig.useInstantAccessibilityMode &&
            !isA11yActive &&
            !isAccessibilityRequired(policy, sdkInt)

        val visionAiLegacyNeedsUsage = policy.isScreenAiEnabledGlobally &&
            sdkInt < Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
            !isA11yActive

        return waqfaStandardNeedsUsage || visionAiLegacyNeedsUsage
    }

    /**
     * يطابق حالة خدمة الوصول الفعلية مع متطلبات السياسة النشطة.
     */
    @Synchronized
    internal fun awaitIdleForTeardown() {
        // Acquiring and releasing the intrinsic monitor guarantees any in-flight @Synchronized reconcile() has finished
    }

    @Synchronized
    fun reconcile(
        context: Context,
        policy: DevicePolicyBundle? = null,
        forceUncloak: Boolean = false
    ): AccessibilityReconcileResult {
        if (com.drmohammed.antiporn.core.engine.ProtectionTransitionGuard.isTeardownInProgress) {
            Timber.tag(TAG).d("reconcile skipped: Device Owner teardown in progress")
            return AccessibilityReconcileResult.NotRequiredAlreadyOff
        }
        val appContext = context.applicationContext ?: context

        // 1. التأكد من أن مكون الخدمة غير معطل في PackageManager
        AccessibilitySelfActivator.ensureComponentVisibleInPackageManager(appContext)

        // 2. معالجة وضع التخفي البنكي المؤقت
        if (forceUncloak) {
            AccessibilitySelfActivator.clearCloakState(appContext)
        } else if (AccessibilitySelfActivator.isCloaked(appContext)) {
            Timber.tag(TAG).d("reconcile skipped: Banking cloak is currently active")
            return AccessibilityReconcileResult.SkippedCloaked
        }

        // 3. استخلاص السياسة وحساب الأسباب المطلوبة
        val resolvedPolicy = policy ?: resolvePolicySafely(appContext) ?: DevicePolicyBundle.defaultPolicy()
        val reasons = getRequiredReasons(resolvedPolicy)

        // 4. مزامنة حالة محرك الذكاء الاصطناعي المستقل داخل الخدمة إن كانت متصلة
        HisnAccessibilityService.instance?.syncStandaloneAiState(resolvedPolicy.isScreenAiEnabledGlobally)

        val isActiveOrEnabled = HisnAccessibilityService.instance != null ||
            AccessibilitySelfActivator.isServiceEnabledInSettings(appContext)

        // 5. إذا لم تعد الخدمة مطلوبة -> أطفئها بنظافة
        if (reasons.isEmpty()) {
            return if (isActiveOrEnabled) {
                val disabled = AccessibilitySelfActivator.disableCleanly(appContext, isTemporaryCloak = false)
                if (disabled) {
                    Timber.tag(TAG).i("reconcile: Accessibility not required by policy; disabled cleanly")
                    AccessibilityReconcileResult.DisabledCleanly
                } else {
                    Timber.tag(TAG).w("reconcile: Accessibility not required by policy, but disableCleanly failed")
                    AccessibilityReconcileResult.AlreadyActive
                }
            } else {
                AccessibilityReconcileResult.NotRequiredAlreadyOff
            }
        }

        // 6. الخدمة مطلوبة -> تحقق هل هي نشطة بالفعل أم تحتاج تفعيلاً صامتاً أو يدوياً
        if (isActiveOrEnabled) {
            return AccessibilityReconcileResult.AlreadyActive
        }

        return if (AccessibilitySelfActivator.enableSilently(appContext, forceUncloak)) {
            Timber.tag(TAG).i("reconcile: Accessibility enabled silently for reasons=$reasons")
            AccessibilityReconcileResult.EnabledSilently
        } else {
            Timber.tag(TAG).i("reconcile: Accessibility requires manual activation for reasons=$reasons")
            AccessibilityReconcileResult.NeedsManualActivation(reasons)
        }
    }

    /**
     * يُستدعى عند تغير قدرات الجهاز أو اتصال الخدمة لإبلاغ مستمع المزامنة المسجل.
     */
    fun notifyCapabilitiesChanged(context: Context, recordTamper: Boolean = false) {
        val appContext = context.applicationContext ?: context
        try {
            onCapabilitiesChangedCallback?.invoke(appContext, recordTamper)
        } catch (e: Throwable) {
            Timber.tag(TAG).w(e, "Error invoking onCapabilitiesChangedCallback")
        }
    }

    /**
     * يُستدعى عند انفصال [HisnAccessibilityService] من النظام (onUnbind / onDestroy)
     * للتمييز الدقيق بين الإيقاف المقصود/البنكي وبين تلاعب المستخدم تحت الإشراف.
     */
    fun onServiceDisconnected(context: Context) {
        val appContext = context.applicationContext ?: context
        if (AccessibilitySelfActivator.isIntentionalDisableActive() || AccessibilitySelfActivator.isCloaked(appContext)) {
            Timber.tag(TAG).d("onServiceDisconnected: intentional disable or banking cloak active; no tamper recorded")
            notifyCapabilitiesChanged(appContext, recordTamper = false)
            return
        }

        val policy = resolvePolicySafely(appContext)
        if (policy != null && isAccessibilityRequired(policy) && policy.isSupervised) {
            Timber.tag(TAG).w("onServiceDisconnected: Unexpected disconnect while required under guardian supervision!")
            try {
                BlackBoxRecorder.recordA11y(
                    context = appContext,
                    action = "UNEXPECTED_DISCONNECT_SUPERVISED",
                    isSuccess = false,
                    details = "Ward disabled required accessibility service"
                )
            } catch (e: Throwable) {
                Timber.tag(TAG).w(e, "Failed recording tamper event to BlackBoxRecorder")
            }
            notifyCapabilitiesChanged(appContext, recordTamper = true)
        } else {
            notifyCapabilitiesChanged(appContext, recordTamper = false)
        }
    }

    private fun resolvePolicySafely(context: Context): DevicePolicyBundle? {
        policyResolverOverride?.let { override ->
            return override(context)
        }
        return try {
            val appContext = context.applicationContext ?: context
            PolicyRepository.getInstance(appContext).getCurrentPolicy()
        } catch (e: Exception) {
            Timber.tag(TAG).d("Could not resolve policy from PolicyRepository (headless/init): ${e.message}")
            null
        }
    }
}
