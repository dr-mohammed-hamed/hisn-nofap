package com.drmohammed.antiporn.pageshield.engine

import android.accessibilityservice.AccessibilityService
import android.content.Context
import com.drmohammed.antiporn.core.policy.AppPolicyRegistry
import com.drmohammed.antiporn.pageshield.model.AppPageShieldPolicy
import com.drmohammed.antiporn.pageshield.model.PageShieldReaction
import com.drmohammed.antiporn.pageshield.registry.PageShieldRegistry
import com.drmohammed.antiporn.telemetry.BlackBoxRecorder
import com.drmohammed.antiporn.telemetry.SafeDpmGateway
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * منفذ ردود الفعل الذكي لدرع الصفحات.
 * ينفذ الخروج المدروس والتجميد المؤقت المحصن ضد حلقة التكرار ومصائد الـ Backstack.
 */
object PageShieldReactionExecutor {

    private const val TAG = "AntiPorn_PageShieldExec"
    private val executorScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun executeReaction(
        service: AccessibilityService,
        packageName: String,
        pageId: String,
        policy: AppPageShieldPolicy
    ) {
        val appContext = service.applicationContext

        // Immunity check: Never touch AntiPorn or critical system immune apps
        if (packageName == appContext.packageName || AppPolicyRegistry.isImmune(packageName)) {
            Timber.tag(TAG).w("Immunity Guard: Refusing to execute PageShield reaction on immune package $packageName")
            return
        }

        val appName = try {
            val pm = appContext.packageManager
            val ai = pm.getApplicationInfo(packageName, 0)
            pm.getApplicationLabel(ai).toString()
        } catch (_: Exception) {
            packageName
        }

        when (policy.reaction) {
            PageShieldReaction.EXIT_ONLY -> {
                handleExitOnly(service, packageName, pageId, appName)
            }
            PageShieldReaction.EXIT_AND_FREEZE -> {
                handleExitAndFreeze(service, packageName, pageId, appName, policy.freezeDurationMinutes)
            }
        }
    }

    private fun handleExitOnly(
        service: AccessibilityService,
        packageName: String,
        pageId: String,
        appName: String
    ) {
        Timber.tag(TAG).i("Executing EXIT_ONLY for $packageName (page: $pageId). Performing BACK...")
        // 1. First step: send BACK to return user to previous safe tab/feed in the app
        service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)

        BlackBoxRecorder.recordPageShield(
            context = service.applicationContext,
            action = "EXIT_ONLY_BACK_TRIGGERED",
            isSuccess = true,
            details = "pkg=$packageName, page=$pageId"
        )

        // 2. Second step: Verify after 300ms; if blocked page is still present (e.g. opened via deep link), escalate to HOME
        executorScope.launch {
            delay(300L)
            val pageDef = PageShieldRegistry.findPage(packageName, pageId)
            val displayMetrics = service.resources.displayMetrics
            val isStillBlocked = pageDef?.detectorStrategy?.isPageDetected(
                service = service,
                event = null,
                screenWidth = displayMetrics.widthPixels,
                screenHeight = displayMetrics.heightPixels
            ) == true

            if (isStillBlocked) {
                Timber.tag(TAG).w("Blocked page $pageId still visible after BACK; escalating to HOME.")
                service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
                BlackBoxRecorder.recordPageShield(
                    context = service.applicationContext,
                    action = "EXIT_ONLY_ESCALATED_HOME",
                    isSuccess = true,
                    details = "pkg=$packageName, page=$pageId"
                )
            }
        }
    }

    private fun handleExitAndFreeze(
        service: AccessibilityService,
        packageName: String,
        pageId: String,
        appName: String,
        durationMinutes: Int
    ) {
        Timber.tag(TAG).i("Executing EXIT_AND_FREEZE for $packageName (page: $pageId, freeze: ${durationMinutes}m)...")
        val appContext = service.applicationContext

        // Step 1: Perform BACK first to dismiss the Shorts player from YouTube's backstack
        // This ensures YouTube will not reopen onto Shorts when unfrozen later.
        service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)

        executorScope.launch {
            // Step 2: Allow YouTube UI to pop the backstack
            delay(250L)
            if (com.drmohammed.antiporn.core.engine.ProtectionTransitionGuard.isTeardownInProgress) return@launch

            // Step 3: Exit to Home Screen
            service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
            if (com.drmohammed.antiporn.core.engine.ProtectionTransitionGuard.isTeardownInProgress) return@launch

            // Step 4: Suspend package via SafeDpmGateway
            if (SafeDpmGateway.isDeviceOwner(appContext)) {
                val res = SafeDpmGateway.setPackagesSuspended(appContext, arrayOf(packageName), true)
                if (res.isSuccess) {
                    Timber.tag(TAG).i("Suspended $packageName for $durationMinutes minutes via SafeDpmGateway")

                    // Step 5: Record in PageShieldFreezeStore using TrustedTime Epoch
                    val record = PageShieldFreezeStore.recordFreeze(appContext, packageName, appName, durationMinutes)

                    // Step 6: Schedule unfreeze exact alarm
                    PageShieldAlarmScheduler.scheduleUnfreezeAlarm(
                        context = appContext,
                        packageName = packageName,
                        appName = appName,
                        triggerEpochMs = record.freezeEndEpochMs
                    )

                    // Step 7: Post user explanation notification
                    PageShieldNotificationHelper.postFreezeStartedNotification(appContext, appName, durationMinutes)

                    BlackBoxRecorder.recordPageShield(
                        context = appContext,
                        action = "APP_FROZEN",
                        isSuccess = true,
                        details = "pkg=$packageName, duration=$durationMinutes min, endEpoch=${record.freezeEndEpochMs}"
                    )
                } else {
                    val errMsg = (res as? com.drmohammed.antiporn.telemetry.DpmExecutionResult.Failure)?.message ?: "Unknown DPM failure"
                    Timber.tag(TAG).e("Failed suspending $packageName via SafeDpmGateway: $errMsg")
                }
            } else {
                Timber.tag(TAG).w("Device Owner missing; skipping setPackagesSuspended for $packageName")
            }
        }
    }

    /**
     * تفكيك آمن لكافة تجميدات درع الصفحات عند إلغاء تنصيب مسؤول الجهاز.
     */
    suspend fun teardown(context: Context) {
        executorScope.coroutineContext.cancelChildren()
        val appContext = context.applicationContext
        Timber.tag(TAG).i("Executing PageShield teardown: clearing all frozen records and alarms...")
        val frozenRecords = PageShieldFreezeStore.getAllFrozenRecords(appContext)

        for (record in frozenRecords) {
            PageShieldAlarmScheduler.cancelUnfreezeAlarm(appContext, record.packageName)
            PageShieldFreezeStore.clearFreeze(appContext, record.packageName)
            if (SafeDpmGateway.isDeviceOwner(appContext)) {
                try {
                    SafeDpmGateway.setPackagesSuspended(appContext, arrayOf(record.packageName), false)
                } catch (e: Exception) {
                    Timber.tag(TAG).w("Error unsuspending ${record.packageName} during teardown: ${e.message}")
                }
            }
        }
    }
}
