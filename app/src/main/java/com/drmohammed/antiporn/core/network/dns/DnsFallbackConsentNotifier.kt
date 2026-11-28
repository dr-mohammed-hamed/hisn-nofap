package com.drmohammed.antiporn.core.network.dns

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.drmohammed.antiporn.R
import com.drmohammed.antiporn.notification.manager.AppNotificationManager
import com.drmohammed.antiporn.notification.model.NotificationActionType
import com.drmohammed.antiporn.notification.model.NotificationCategory
import com.drmohammed.antiporn.notification.registry.NotificationRegistry
import com.drmohammed.antiporn.ui.control.web.LocalDnsFallbackConsentActivity
import timber.log.Timber

/**
 * Posts and clears the one-tap consent notification requesting user approval
 * to activate local DNS VPN fallback when Private DNS stalls on a network.
 */
internal object DnsFallbackConsentNotifier {

    private const val TAG = "AntiPorn_DnsConsentNotif"

    fun notifyOnceForNetwork(context: Context, netId: String) {
        try {
            val appCtx = context.applicationContext ?: context
            val notifMgr = AppNotificationManager.getInstance(appCtx)
            val title = appCtx.getString(R.string.notif_dns_fallback_consent_title)
            val message = appCtx.getString(R.string.notif_dns_fallback_consent_msg)

            notifMgr.postEventNotification(
                category = NotificationCategory.SHIELDS,
                title = title,
                message = message,
                dedupKey = "dns_fallback_consent:$netId",
                cooldownMs = 0L,
                actionType = NotificationActionType.APPROVE_LOCAL_DNS_FALLBACK,
                actionPayload = netId,
                systemNotificationId = NotificationRegistry.EVENT_DNS_FALLBACK_CONSENT_ID,
                channelId = NotificationRegistry.CHANNEL_ID_PROTECTION_EVENTS,
                postSystemNotification = false,
                importance = 2
            )

            if (!notifMgr.canPostNotification()) {
                Timber.tag(TAG).d("Notification permission denied; recorded in-app consent entry for $netId")
                return
            }

            val clickIntent = Intent(appCtx, LocalDnsFallbackConsentActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra(LocalDnsFallbackConsentActivity.EXTRA_NET_ID, netId)
            }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            val contentPendingIntent = PendingIntent.getActivity(
                appCtx,
                NotificationRegistry.REQUEST_CODE_DNS_FALLBACK_CONSENT,
                clickIntent,
                flags
            )

            val systemNm = appCtx.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
            val notification = NotificationCompat.Builder(appCtx, NotificationRegistry.CHANNEL_ID_PROTECTION_EVENTS)
                .setSmallIcon(R.drawable.ic_shield_dns)
                .setContentTitle(title)
                .setContentText(message)
                .setStyle(NotificationCompat.BigTextStyle().bigText(message))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(contentPendingIntent)
                .build()

            systemNm.notify(NotificationRegistry.EVENT_DNS_FALLBACK_CONSENT_ID, notification)
        } catch (t: Throwable) {
            Timber.tag(TAG).w(t, "Failed posting DNS fallback consent notification for $netId")
        }
    }

    fun cancelNotification(context: Context) {
        try {
            val appCtx = context.applicationContext ?: context
            AppNotificationManager.getInstance(appCtx)
                .cancelSystemNotification(NotificationRegistry.EVENT_DNS_FALLBACK_CONSENT_ID)
        } catch (t: Throwable) {
            Timber.tag(TAG).d(t, "Skipping DNS fallback consent notification cancellation")
        }
    }

    fun cancelNotificationIfIdle(context: Context) {
        if (!DnsResilienceNetworkRouter.hasPendingConsentPrompts()) {
            cancelNotification(context)
        }
    }
}
