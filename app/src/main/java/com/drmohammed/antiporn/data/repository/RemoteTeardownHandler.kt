package com.drmohammed.antiporn.data.repository

import android.content.Context
import androidx.core.content.edit
import com.drmohammed.antiporn.R
import com.drmohammed.antiporn.core.engine.DeviceOwnerTeardownManager
import com.drmohammed.antiporn.core.model.DevicePolicyBundle
import com.drmohammed.antiporn.notification.manager.AppNotificationManager
import com.drmohammed.antiporn.notification.model.NotificationActionType
import com.drmohammed.antiporn.notification.model.NotificationCategory
import com.drmohammed.antiporn.notification.registry.NotificationRegistry
import com.drmohammed.antiporn.ui.control.qr.SelfControlQrManager
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber

/**
 * Coordinates remote Guardian immediate teardown execution, remote self-control QR reset,
 * and idempotent retry gating so [PolicyRepository] remains focused and modular.
 */
internal class RemoteTeardownHandler(
    private val appContext: Context,
    private val executeTeardown: suspend (Long) -> Result<Unit> = { requestedAt ->
        DeviceOwnerTeardownManager.executeGuardianAuthorizedTeardown(appContext, requestedAt)
    },
    private val onTeardownCompletedNotify: (Long) -> Unit = { requestedAt ->
        AppNotificationManager.getInstance(appContext).postEventNotification(
            category = NotificationCategory.GUARDIAN,
            title = appContext.getString(R.string.notif_ward_remote_teardown_completed_title),
            message = appContext.getString(R.string.notif_ward_remote_teardown_completed_msg),
            dedupKey = "ward_teardown_done_$requestedAt",
            cooldownMs = 0L,
            actionType = NotificationActionType.NONE,
            actionPayload = null,
            systemNotificationId = NotificationRegistry.EVENT_WARD_TEARDOWN_COMPLETED_ID,
            channelId = NotificationRegistry.CHANNEL_ID_PROTECTION_EVENTS,
            postSystemNotification = true,
            importance = 3
        )
    }
) {
    private val remoteTeardownMutex = Mutex()

    suspend fun checkAndExecutePendingGuardianTeardown(cloudPolicy: DevicePolicyBundle): Boolean {
        return remoteTeardownMutex.withLock {
            val prefs = appContext.getSharedPreferences(PolicyRepository.PREFS_NAME, Context.MODE_PRIVATE)
            val lastProcessed = prefs.getLong(PolicyRepository.KEY_LAST_PROCESSED_REMOTE_TEARDOWN_AT, 0L)
            if (!shouldExecuteGuardianTeardown(cloudPolicy, lastProcessed)) {
                return@withLock false
            }
            val requestedAt = cloudPolicy.remoteTeardownRequestedAt ?: return@withLock false
            Timber.tag(TAG).w("[GuardianTeardown] Detected guardian immediate teardown request (requestedAt=%d). Executing...", requestedAt)
            val result = executeTeardown(requestedAt)
            if (result.isSuccess) {
                prefs.edit { putLong(PolicyRepository.KEY_LAST_PROCESSED_REMOTE_TEARDOWN_AT, requestedAt) }
                try {
                    onTeardownCompletedNotify(requestedAt)
                } catch (e: Exception) {
                    Timber.tag(TAG).w(e, "Failed posting ward teardown completion notification")
                }
                true
            } else {
                Timber.tag(TAG).w(
                    result.exceptionOrNull(),
                    "[GuardianTeardown] Execution deferred/failed and will retry: %s",
                    result.exceptionOrNull()?.message
                )
                false
            }
        }
    }

    suspend fun retryPendingGuardianTeardownIfNeeded(currentPolicy: DevicePolicyBundle): Boolean {
        val prefs = appContext.getSharedPreferences(PolicyRepository.PREFS_NAME, Context.MODE_PRIVATE)
        val lastProcessed = prefs.getLong(PolicyRepository.KEY_LAST_PROCESSED_REMOTE_TEARDOWN_AT, 0L)
        if (!shouldExecuteGuardianTeardown(currentPolicy, lastProcessed)) {
            return false
        }
        return checkAndExecutePendingGuardianTeardown(currentPolicy)
    }

    fun checkAndApplyRemoteSelfControlReset(policy: DevicePolicyBundle) {
        val remoteResetAt = policy.remoteSelfControlResetRequestedAt ?: return
        if (remoteResetAt <= 0L) return
        val prefs = appContext.getSharedPreferences(PolicyRepository.PREFS_NAME, Context.MODE_PRIVATE)
        val lastProcessed = prefs.getLong(PolicyRepository.KEY_LAST_PROCESSED_REMOTE_RESET_AT, 0L)
        if (remoteResetAt > lastProcessed) {
            prefs.edit { putLong(PolicyRepository.KEY_LAST_PROCESSED_REMOTE_RESET_AT, remoteResetAt) }
            Timber.tag(TAG).i("[SelfControlDiag] Executing remote Self-Control QR reset requested at %d", remoteResetAt)
            SelfControlQrManager.getInstance(appContext).completeEmergencyUnlock()
            try {
                AppNotificationManager.getInstance(appContext).postEventNotification(
                    category = NotificationCategory.SYSTEM,
                    title = appContext.getString(R.string.notif_self_control_qr_reset_by_guardian_title),
                    message = appContext.getString(R.string.notif_self_control_qr_reset_by_guardian_msg),
                    dedupKey = "qr_reset_$remoteResetAt",
                    cooldownMs = 0L,
                    actionType = NotificationActionType.NONE,
                    actionPayload = null,
                    systemNotificationId = NotificationRegistry.EVENT_WARD_REQUEST_ID,
                    channelId = NotificationRegistry.CHANNEL_ID_PROTECTION_EVENTS,
                    postSystemNotification = true,
                    importance = 2
                )
            } catch (e: Exception) {
                Timber.tag(TAG).w(e, "Failed posting QR reset notification")
            }
        }
    }

    companion object {
        private const val TAG = "AntiPorn_PolicyRepo"

        fun shouldExecuteGuardianTeardown(
            cloudPolicy: DevicePolicyBundle,
            lastProcessedRequestedAt: Long
        ): Boolean {
            val requestedAt = cloudPolicy.remoteTeardownRequestedAt ?: return false
            if (requestedAt <= 0L || requestedAt <= lastProcessedRequestedAt) return false
            if (!cloudPolicy.isSupervised || cloudPolicy.guardianUid.isNullOrBlank()) return false
            return cloudPolicy.deactivationStatus == DevicePolicyBundle.DEACTIVATION_STATUS_PENDING
        }
    }
}
