package com.drmohammed.antiporn.pageshield.engine

import android.content.Context
import com.drmohammed.antiporn.R
import com.drmohammed.antiporn.notification.manager.AppNotificationManager
import com.drmohammed.antiporn.notification.model.NotificationCategory
import com.drmohammed.antiporn.notification.registry.NotificationRegistry
import timber.log.Timber

/**
 * مساعد إرسال إشعارات «درع الصفحات» عبر [AppNotificationManager].
 */
object PageShieldNotificationHelper {

    private const val TAG = "AntiPorn_PageShieldNotif"

    fun postFreezeStartedNotification(context: Context, appName: String, durationMinutes: Int) {
        try {
            val title = context.getString(R.string.page_shield_notif_frozen_title)
            val message = context.getString(R.string.page_shield_notif_frozen_msg, appName, durationMinutes)
            AppNotificationManager.getInstance(context.applicationContext).postEventNotification(
                category = NotificationCategory.SHIELDS,
                title = title,
                message = message,
                dedupKey = "page_shield_freeze_${appName}_${System.currentTimeMillis() / 60_000}",
                cooldownMs = 5_000L,
                postSystemNotification = true,
                systemNotificationId = NotificationRegistry.EVENT_PAGE_SHIELD_FROZEN_ID,
                channelId = NotificationRegistry.CHANNEL_ID_INFO_QUIET,
                importance = 2
            )
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed posting freeze started notification for $appName")
        }
    }

    fun postUnfreezeNotification(context: Context, appName: String) {
        try {
            val title = context.getString(R.string.page_shield_notif_unfrozen_title)
            val message = context.getString(R.string.page_shield_notif_unfrozen_msg, appName)
            val notifManager = AppNotificationManager.getInstance(context.applicationContext)
            notifManager.cancelSystemNotification(NotificationRegistry.EVENT_PAGE_SHIELD_FROZEN_ID)
            notifManager.postEventNotification(
                category = NotificationCategory.SHIELDS,
                title = title,
                message = message,
                dedupKey = "page_shield_unfreeze_${appName}_${System.currentTimeMillis() / 60_000}",
                cooldownMs = 5_000L,
                postSystemNotification = false,
                systemNotificationId = NotificationRegistry.EVENT_PAGE_SHIELD_UNFROZEN_ID,
                importance = 1
            )
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed posting unfreeze notification for $appName")
        }
    }
}
