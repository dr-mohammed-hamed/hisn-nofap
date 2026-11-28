package com.drmohammed.antiporn.data.repository

import android.content.Context
import com.drmohammed.antiporn.R
import com.drmohammed.antiporn.core.util.SafeAndroidLog as Log
import com.drmohammed.antiporn.notification.manager.AppNotificationManager
import com.drmohammed.antiporn.notification.model.NotificationActionType
import com.drmohammed.antiporn.notification.model.NotificationCategory
import com.drmohammed.antiporn.notification.registry.NotificationRegistry

/**
 * Coordinates user-facing and system notifications for Zero-Trust app freeze
 * and Guardian/Self-Control approval decisions.
 */
internal class ApprovalNotificationCoordinator(
    private val appContext: Context,
    private val appLabelResolver: (String) -> String
) {

    fun postFreezeNotification(
        packageName: String,
        appName: String,
        isHighRisk: Boolean
    ) {
        try {
            val notifTitle = if (isHighRisk) {
                appContext.getString(R.string.notification_high_risk_app_frozen_title)
            } else {
                appContext.getString(R.string.toast_app_suspended_by_guardian)
            }
            val notifMessage = if (isHighRisk) {
                appContext.getString(R.string.notification_high_risk_app_frozen_msg, appName)
            } else {
                appContext.getString(R.string.toast_app_suspended_by_guardian) + " ($appName)"
            }
            AppNotificationManager.getInstance(appContext).postEventNotification(
                category = NotificationCategory.APPROVALS,
                title = notifTitle,
                message = notifMessage,
                dedupKey = "zero_trust_freeze_$packageName",
                cooldownMs = 10 * 60 * 1000L,
                actionType = NotificationActionType.OPEN_SELF_APPROVALS,
                actionPayload = packageName,
                postSystemNotification = true,
                importance = 2
            )
        } catch (e: Exception) {
            Log.w(TAG, "Error posting freeze notification for $packageName", e)
        }
    }

    fun cancelFreezeNotification(packageName: String) {
        try {
            AppNotificationManager.getInstance(appContext)
                .cancelSystemNotification("zero_trust_freeze_$packageName".hashCode())
        } catch (e: Exception) {
            Log.w(TAG, "Error cancelling freeze notification for $packageName", e)
        }
    }

    fun postApprovalNotification(
        packageName: String,
        approved: Boolean,
        isCurfewDeferred: Boolean,
        decidedBy: String?
    ) {
        try {
            val appLabel = appLabelResolver(packageName)
            val isSelf = decidedBy?.equals("SELF", ignoreCase = true) == true
            val guardianName = decidedBy?.takeUnless { it.isBlank() || it.equals("null", ignoreCase = true) }
                ?: appContext.getString(R.string.guardian_default_name)

            val title: String
            val desc: String
            val actionType: NotificationActionType

            if (approved) {
                title = appContext.getString(R.string.notif_approval_granted_title)
                if (isCurfewDeferred) {
                    desc = if (isSelf) {
                        appContext.getString(R.string.notif_self_qr_approval_deferred_curfew_desc, appLabel)
                    } else {
                        appContext.getString(R.string.notif_approval_deferred_curfew_desc, appLabel, guardianName)
                    }
                    actionType = NotificationActionType.OPEN_CURFEW
                } else {
                    desc = if (isSelf) {
                        appContext.getString(R.string.notif_self_qr_approval_granted_desc, appLabel)
                    } else {
                        appContext.getString(R.string.notif_approval_granted_desc, guardianName, appLabel)
                    }
                    actionType = NotificationActionType.LAUNCH_APP
                }
            } else {
                title = appContext.getString(R.string.notif_approval_rejected_title)
                desc = if (isSelf) {
                    appContext.getString(R.string.notif_self_qr_approval_rejected_desc, appLabel)
                } else {
                    appContext.getString(R.string.notif_approval_rejected_desc, guardianName, appLabel)
                }
                actionType = NotificationActionType.NONE
            }

            val dedupKey = if (approved) "approval_${packageName}_APPROVED" else "approval_${packageName}_REJECTED"
            val notifManager = AppNotificationManager.getInstance(appContext)
            notifManager.cancelSystemNotification("zero_trust_freeze_$packageName".hashCode())
            notifManager.postEventNotification(
                category = NotificationCategory.APPROVALS,
                title = title,
                message = desc,
                dedupKey = dedupKey,
                cooldownMs = 10 * 60 * 1000L,
                actionType = actionType,
                actionPayload = packageName,
                systemNotificationId = packageName.hashCode(),
                channelId = NotificationRegistry.CHANNEL_ID_PROTECTION_EVENTS,
                postSystemNotification = true,
                importance = 2
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error posting approval notification for $packageName", e)
        }
    }

    companion object {
        private const val TAG = "AntiPorn_ApprovalNotif"
    }
}
