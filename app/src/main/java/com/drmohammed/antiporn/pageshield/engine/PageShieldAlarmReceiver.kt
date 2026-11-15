package com.drmohammed.antiporn.pageshield.engine

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.drmohammed.antiporn.core.policy.AppPolicyRegistry
import com.drmohammed.antiporn.curfew.CurfewAppResolver
import com.drmohammed.antiporn.curfew.CurfewConfig
import com.drmohammed.antiporn.curfew.CurfewEnforcer
import com.drmohammed.antiporn.data.repository.ApprovalRepository
import com.drmohammed.antiporn.data.repository.PolicyRepository
import com.drmohammed.antiporn.telemetry.BlackBoxRecorder
import com.drmohammed.antiporn.telemetry.SafeDpmGateway
import com.drmohammed.antiporn.waqfa.engine.WaqfaCooldownStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * مستقبل منبه انتهاء مدة التجميد المؤقت لدرع الصفحات.
 * يتحقق بدقة من عدم تضارب فك التجميد مع حظر التجوال أو وقفة أو القائمة السوداء قبل رفع التعليق.
 */
class PageShieldAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) return
        if (intent.action != PageShieldAlarmScheduler.ACTION_UNFREEZE) return

        val packageName = intent.getStringExtra(PageShieldAlarmScheduler.EXTRA_PACKAGE_NAME) ?: return
        val appName = intent.getStringExtra(PageShieldAlarmScheduler.EXTRA_APP_NAME) ?: packageName

        val appContext = context.applicationContext ?: context
        val pendingResult = goAsync()

        CoroutineScope(Dispatchers.IO).launch {
            try {
                Timber.tag(TAG).i("Alarm fired: lifting PageShield freeze for $packageName")
                liftFreezeChecked(appContext, packageName, appName)
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Error lifting PageShield freeze in receiver for $packageName")
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        private const val TAG = "AntiPorn_PageShieldAlarm"

        suspend fun liftFreezeChecked(context: Context, packageName: String, appName: String) {
            // 1. مسح سجل درع الصفحات دائماً لانتهاء مؤقت الدرع
            PageShieldFreezeStore.clearFreeze(context, packageName)

            // 2. التحقق من دروع التجميد الأخرى لمنع أي تضارب
            if (isAppFrozenByOtherShields(context, packageName)) {
                Timber.tag(TAG).w("Package $packageName will remain suspended: active constraint from Curfew/Waqfa/Blacklist")
                BlackBoxRecorder.recordPageShield(
                    context = context,
                    action = "UNFREEZE_SKIPPED_DUE_TO_OTHER_SHIELD",
                    isSuccess = true,
                    details = "pkg=$packageName, app=$appName"
                )
                return
            }

            // 3. فك التجميد بأمان عبر SafeDpmGateway
            if (SafeDpmGateway.isDeviceOwner(context)) {
                val res = SafeDpmGateway.setPackagesSuspended(context, arrayOf(packageName), false)
                if (res.isSuccess) {
                    Timber.tag(TAG).i("Successfully unsuspended $packageName via SafeDpmGateway")
                    PageShieldNotificationHelper.postUnfreezeNotification(context, appName)
                    BlackBoxRecorder.recordPageShield(
                        context = context,
                        action = "APP_UNFROZEN",
                        isSuccess = true,
                        details = "pkg=$packageName, app=$appName"
                    )
                } else {
                    val errMsg = (res as? com.drmohammed.antiporn.telemetry.DpmExecutionResult.Failure)?.message ?: "Unknown DPM failure"
                    Timber.tag(TAG).e("Failed unsuspending $packageName via SafeDpmGateway: $errMsg")
                }
            } else {
                Timber.tag(TAG).w("Device Owner missing; cleared store only for $packageName")
            }
        }

        /**
         * فحص ما إذا كان التطبيق يخضع لقيود تجميد من أنظمة الحصن الأخرى.
         */
        fun isAppFrozenByOtherShields(context: Context, packageName: String): Boolean {
            // أ. وقفة (Waqfa Cooldown)
            if (WaqfaCooldownStore.isPackageInCooldown(context, packageName)) {
                return true
            }

            // ب. حظر التجوال (Curfew)
            if (CurfewEnforcer.isEffectiveBlockActive(context)) {
                val curfewTargets = CurfewAppResolver.getCurfewTargetPackages(context)
                val trackedCurfew = CurfewConfig.getTrackedSuspendedPackages(context)
                val storesAndSearch = CurfewConfig.OEM_APP_STORES + CurfewConfig.CURFEW_SEARCH_APPS
                if (curfewTargets.contains(packageName) || trackedCurfew.contains(packageName) || storesAndSearch.contains(packageName)) {
                    return true
                }
            }

            // ج. القائمة السوداء الدائمة والمخصصة
            if (AppPolicyRegistry.isPermanentlyBlacklisted(packageName)) {
                return true
            }

            val policy = try {
                PolicyRepository.getInstance(context).getCurrentPolicy()
            } catch (_: Exception) {
                null
            }

            if (policy != null && policy.customBlacklistPackages.contains(packageName)) {
                return true
            }

            // د. نظام انعدام الثقة (Zero-Trust) والتطبيقات المعلقة للموافقة
            if (policy?.lockNewInstallsUntilApproved == true) {
                try {
                    val approvalRepo = ApprovalRepository.getInstance(context)
                    val unapproved = approvalRepo.getLocalPendingApprovals()
                        .filter { !it.isApproved }
                        .map { it.packageName }
                    if (unapproved.contains(packageName)) {
                        return true
                    }
                } catch (e: Exception) {
                    Timber.tag(TAG).w("Error checking approval repository for $packageName: ${e.message}")
                }
            }

            return false
        }
    }
}
