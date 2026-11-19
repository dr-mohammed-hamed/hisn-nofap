package com.drmohammed.antiporn.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.drmohammed.antiporn.core.engine.ProtectionEngine
import com.drmohammed.antiporn.core.engine.ProtectionTransitionGuard
import com.drmohammed.antiporn.telemetry.SafeDpmGateway
import com.drmohammed.antiporn.data.repository.ApprovalRepository
import com.drmohammed.antiporn.watchdog.ProtectionWatchdogRuntime
import com.drmohammed.antiporn.watchdog.WatchdogOrchestrator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.util.concurrent.TimeUnit

/**
 * Periodic WorkManager safety net to ensure watchdog persistence and shield invariants
 * survive extreme OEM background kills and Doze mode.
 *
 * Runs offline with [Constraints.NONE] at a 30-minute interval.
 */
class WatchdogHealthWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        private const val TAG = "AntiPorn_WatchdogHealthWorker"
        const val WORK_NAME = "antiporn_watchdog_health_worker"
        const val REPEAT_INTERVAL_MINUTES = 30L

        fun schedule(context: Context) {
            val constraints = Constraints.NONE

            val workRequest = PeriodicWorkRequestBuilder<WatchdogHealthWorker>(
                REPEAT_INTERVAL_MINUTES, TimeUnit.MINUTES
            )
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                workRequest
            )
            Timber.tag(TAG).i("Scheduled periodic Watchdog Health worker every $REPEAT_INTERVAL_MINUTES minutes with KEEP policy")
        }
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        if (isStopped) return@withContext Result.retry()

        try {
            val isDo = SafeDpmGateway.isDeviceOwner(applicationContext)
            val isTeardown = ProtectionTransitionGuard.isTeardownInProgress

            if (isDo && !isTeardown) {
                Timber.tag(TAG).d("Running periodic Watchdog health sweep...")
                WatchdogOrchestrator.ensureActive(applicationContext)

                if (!ProtectionWatchdogRuntime.isRunning) {
                    Timber.tag(TAG).w("ProtectionWatchdogRuntime was not running during health sweep; performing direct baseline reconciliation.")
                    ProtectionEngine.evaluateSystemStatus(applicationContext)
                    ApprovalRepository.getInstance(applicationContext).syncZeroTrustBaselineAndReconcileAsync()
                }
            }
            Result.success()
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Error during WatchdogHealthWorker execution")
            Result.success()
        }
    }
}
