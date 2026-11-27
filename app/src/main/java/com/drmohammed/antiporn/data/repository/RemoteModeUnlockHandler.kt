package com.drmohammed.antiporn.data.repository

import android.content.Context
import androidx.core.content.edit
import com.drmohammed.antiporn.core.model.DevicePolicyBundle
import com.drmohammed.antiporn.curfew.CurfewEnforcer
import com.drmohammed.antiporn.fortress.engine.FortressSessionController
import com.drmohammed.antiporn.telemetry.BlackBoxRecorder
import timber.log.Timber

/**
 * Executes idempotent, silent remote mode unlocks commanded by a verified Guardian
 * for Night Curfew, an active Fortress, or both. Never posts user notifications.
 */
object RemoteModeUnlockHandler {

    private const val TAG = "AntiPorn_RemoteUnlock"

    @Synchronized
    fun checkAndApplyRemoteModeUnlock(context: Context, policy: DevicePolicyBundle) {
        val appContext = context.applicationContext ?: context
        val requestedAt = policy.remoteModeUnlockRequestedAt ?: return
        if (requestedAt <= 0L) return
        if (!policy.isSupervised || policy.guardianUid.isNullOrBlank()) return

        val target = policy.remoteModeUnlockTarget?.trim()?.uppercase(java.util.Locale.ROOT) ?: return
        if (target != DevicePolicyBundle.REMOTE_MODE_UNLOCK_CURFEW &&
            target != DevicePolicyBundle.REMOTE_MODE_UNLOCK_FORTRESS &&
            target != DevicePolicyBundle.REMOTE_MODE_UNLOCK_ALL
        ) {
            Timber.tag(TAG).w("Ignoring unknown remoteModeUnlockTarget: %s", target)
            return
        }

        val prefs = appContext.getSharedPreferences(PolicyRepository.PREFS_NAME, Context.MODE_PRIVATE)
        val lastProcessed = prefs.getLong(PolicyRepository.KEY_LAST_PROCESSED_REMOTE_MODE_UNLOCK_AT, 0L)
        if (requestedAt <= lastProcessed) return

        Timber.tag(TAG).i("Applying remote Guardian mode unlock target=%s requestedAt=%d", target, requestedAt)

        try {
            synchronized(CurfewEnforcer) {
                if (target == DevicePolicyBundle.REMOTE_MODE_UNLOCK_CURFEW ||
                    target == DevicePolicyBundle.REMOTE_MODE_UNLOCK_ALL
                ) {
                    CurfewEnforcer.applyCurfewWindowOverride(appContext, policy = policy, syncAndNotify = false)
                }

                if (target == DevicePolicyBundle.REMOTE_MODE_UNLOCK_FORTRESS ||
                    target == DevicePolicyBundle.REMOTE_MODE_UNLOCK_ALL
                ) {
                    FortressSessionController.unlockActiveFortressByGuardian(appContext)
                }

                prefs.edit(commit = true) {
                    putLong(PolicyRepository.KEY_LAST_PROCESSED_REMOTE_MODE_UNLOCK_AT, requestedAt)
                }

                CurfewEnforcer.syncBlockStateAsync(appContext)
            }

            WardActiveModesTelemetrySyncer.syncAsync(appContext)
            BlackBoxRecorder.recordGate(
                appContext,
                "REMOTE_MODE_UNLOCK_APPLIED",
                isSuccess = true,
                details = "target=$target, requestedAt=$requestedAt"
            )
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed applying remote Guardian mode unlock target=%s requestedAt=%d", target, requestedAt)
            BlackBoxRecorder.recordGate(
                appContext,
                "REMOTE_MODE_UNLOCK_FAILED",
                isSuccess = false,
                error = e.message ?: e.javaClass.simpleName
            )
        }
    }
}
