package com.drmohammed.antiporn.curfew.notification

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.text.format.DateFormat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.drmohammed.antiporn.MainActivity
import com.drmohammed.antiporn.R
import com.drmohammed.antiporn.core.time.TrustedTimeProvider
import com.drmohammed.antiporn.curfew.CurfewBlackholeVpnService
import com.drmohammed.antiporn.curfew.CurfewConfig
import com.drmohammed.antiporn.curfew.CurfewEnforcer
import com.drmohammed.antiporn.curfew.CurfewScheduleExt
import com.drmohammed.antiporn.curfew.CurfewVpnStateCoordinator
import com.drmohammed.antiporn.fortress.engine.EffectiveRestrictionResolver
import com.drmohammed.antiporn.fortress.engine.FortressSessionController
import com.drmohammed.antiporn.fortress.notification.FortressNotificationReceiver
import com.drmohammed.antiporn.notification.manager.AppNotificationManager
import com.drmohammed.antiporn.notification.model.NotificationActionType
import com.drmohammed.antiporn.notification.model.NotificationCategory
import com.drmohammed.antiporn.notification.registry.NotificationRegistry
import timber.log.Timber
import java.util.Calendar
import java.util.TimeZone

/**
 * Unified Central Coordinator for all Curfew, Fortress, and Guardian Lockdown notifications.
 * Enforces Clean Architecture Single-Source-of-Truth via [EffectiveRestrictionResolver].
 */
object ProtectionNotificationCoordinator {

    private const val TAG = "AntiPorn_NotifCoord"

    const val CHANNEL_ID_PROTECTION = NotificationRegistry.CHANNEL_ID_PROTECTION_VPN
    const val CHANNEL_ID_EVENTS = NotificationRegistry.CHANNEL_ID_PROTECTION_EVENTS

    const val NOTIFICATION_ID_PROTECTION = NotificationRegistry.FGS_CURFEW_VPN_ID
    const val NOTIFICATION_ID_EVENTS = NotificationRegistry.EVENT_NAP_ENDED_ID

    sealed class ProtectionState {
        data class GuardianLockdown(val isCurfewAlsoActive: Boolean, val activeFortressName: String?) : ProtectionState()
        data class FortressActive(val fortressName: String, val isEmergency: Boolean, val endWallMs: Long, val remainingMs: Long) : ProtectionState()
        data class CurfewActive(val endHour: Int, val endMinute: Int) : ProtectionState()
        data class AppsOnlyActive(val activeFortressName: String?, val endHour: Int, val endMinute: Int, val endWallMs: Long) : ProtectionState()
        object DnsFilteringActive : ProtectionState()
        object Inactive : ProtectionState()
    }

    fun canPostNotification(context: Context): Boolean =
        NotificationManagerCompat.from(context).areNotificationsEnabled()

    fun ensureChannels(context: Context) {
        AppNotificationManager.getInstance(context).createNotificationChannels()
    }

    fun getActiveCurfewEndHourAndMinute(context: Context): Pair<Int, Int> {
        val cal = CurfewConfig.getCurfewCalendar(context)
        return CurfewScheduleExt.resolveActiveCurfewEnd(
            calendar = cal,
            startHour = CurfewConfig.getCurfewStartHour(context),
            startMinute = CurfewConfig.getCurfewStartMinute(context),
            endHour = CurfewConfig.getCurfewEndHour(context),
            endMinute = CurfewConfig.getCurfewEndMinute(context),
            isWeekendCurfewEnabled = CurfewConfig.isWeekendCurfewEnabled(context),
            weekendStartHour = CurfewConfig.getWeekendCurfewStartHour(context),
            weekendStartMinute = CurfewConfig.getWeekendCurfewStartMinute(context),
            weekendEndHour = CurfewConfig.getWeekendCurfewEndHour(context),
            weekendEndMinute = CurfewConfig.getWeekendCurfewEndMinute(context)
        )
    }

    private fun formatTimeOfDay(context: Context, hour: Int, minute: Int): String {
        val tz = TimeZone.getTimeZone(CurfewConfig.getTargetTimeZoneId(context))
        val cal = Calendar.getInstance(tz).apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        return DateFormat.getTimeFormat(context).apply { timeZone = tz }.format(cal.time)
    }

    fun resolveCurrentState(context: Context): ProtectionState {
        val effective = EffectiveRestrictionResolver.resolve(context)
        val activeFortress = effective.activeFortress
        val fortressName = activeFortress?.resolveDisplayName(context)

        return when {
            effective.isInstantInternetKillActive -> ProtectionState.GuardianLockdown(
                isCurfewAlsoActive = effective.isNightCurfewActive,
                activeFortressName = fortressName
            )
            activeFortress != null -> {
                if (effective.shouldCutInternet) {
                    ProtectionState.FortressActive(
                        fortressName = fortressName ?: activeFortress.fortressId,
                        isEmergency = activeFortress.isEmergency,
                        endWallMs = activeFortress.endWallMs,
                        remainingMs = activeFortress.remainingMs
                    )
                } else {
                    ProtectionState.AppsOnlyActive(
                        activeFortressName = fortressName,
                        endHour = 0,
                        endMinute = 0,
                        endWallMs = activeFortress.endWallMs
                    )
                }
            }
            effective.isNightCurfewActive -> {
                val (endH, endM) = getActiveCurfewEndHourAndMinute(context)
                if (effective.shouldCutInternet) {
                    ProtectionState.CurfewActive(endHour = endH, endMinute = endM)
                } else {
                    ProtectionState.AppsOnlyActive(
                        activeFortressName = null,
                        endHour = endH,
                        endMinute = endM,
                        endWallMs = 0L
                    )
                }
            }
            isLocalDnsVpnActive(context) -> ProtectionState.DnsFilteringActive
            else -> ProtectionState.Inactive
        }
    }

    private fun isLocalDnsVpnActive(context: Context): Boolean =
        CurfewBlackholeVpnService.evaluateRequiredMode(context) == CurfewBlackholeVpnService.VpnMode.DNS_FILTERING_ONLY ||
            (CurfewBlackholeVpnService.isServiceOrTunnelActive() &&
                CurfewBlackholeVpnService.currentMode == CurfewBlackholeVpnService.VpnMode.DNS_FILTERING_ONLY)

    fun buildProtectionNotification(
        context: Context,
        state: ProtectionState = resolveCurrentState(context)
    ): Notification {
        ensureChannels(context)
        val openAppIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val piFlags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        val contentPendingIntent = PendingIntent.getActivity(context, 0, openAppIntent, piFlags)

        val builder = NotificationCompat.Builder(context, CHANNEL_ID_PROTECTION)
            .setContentIntent(contentPendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOnlyAlertOnce(true)
            .setSilent(true)

        when (state) {
            is ProtectionState.GuardianLockdown -> {
                val desc = when {
                    state.activeFortressName != null ->
                        context.getString(R.string.notif_guardian_lockdown_desc_fortress_format, state.activeFortressName)
                    state.isCurfewAlsoActive ->
                        context.getString(R.string.notif_guardian_lockdown_desc_curfew)
                    else ->
                        context.getString(R.string.notif_guardian_lockdown_desc)
                }
                builder.setContentTitle(context.getString(R.string.notif_guardian_lockdown_title))
                    .setContentText(desc)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(desc))
                    .setSmallIcon(R.drawable.ic_shield_vpn)
                    .setCategory(NotificationCompat.CATEGORY_STATUS)
            }
            is ProtectionState.FortressActive -> {
                val desc = context.getString(R.string.notif_fortress_active_desc_format, state.fortressName)
                builder.setContentTitle(context.getString(R.string.notif_fortress_active_title))
                    .setContentText(desc)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(desc))
                    .setSmallIcon(R.drawable.ic_shield_curfew)
                    .setCategory(NotificationCompat.CATEGORY_STOPWATCH)
                val remainingMs = FortressSessionController.remainingMs(context).takeIf { it > 0L } ?: state.remainingMs
                attachFortressCountdownAndExtend(context, builder, remainingMs, R.drawable.ic_shield_curfew, piFlags)
            }
            is ProtectionState.CurfewActive -> {
                val formattedEnd = formatTimeOfDay(context, state.endHour, state.endMinute)
                val desc = context.getString(R.string.notif_curfew_active_desc_format, formattedEnd)
                builder.setContentTitle(context.getString(R.string.notif_curfew_active_title))
                    .setContentText(desc)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(desc))
                    .setSmallIcon(R.drawable.ic_shield_curfew)
                    .setCategory(NotificationCompat.CATEGORY_STATUS)
            }
            is ProtectionState.AppsOnlyActive -> {
                val desc = if (state.activeFortressName != null) {
                    context.getString(R.string.notif_apps_suspended_fortress_desc_format, state.activeFortressName)
                } else {
                    val formattedEnd = formatTimeOfDay(context, state.endHour, state.endMinute)
                    context.getString(R.string.notif_apps_suspended_curfew_desc_format, formattedEnd)
                }
                builder.setContentTitle(context.getString(R.string.notif_apps_suspended_title))
                    .setContentText(desc)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(desc))
                    .setSmallIcon(R.drawable.ic_shield_freeze)
                    .setCategory(NotificationCompat.CATEGORY_STATUS)
                if (state.activeFortressName != null && state.endWallMs > 0L) {
                    val remainingMs = FortressSessionController.remainingMs(context)
                    attachFortressCountdownAndExtend(context, builder, remainingMs, R.drawable.ic_shield_freeze, piFlags)
                }
            }
            is ProtectionState.DnsFilteringActive, is ProtectionState.Inactive -> {
                val desc = context.getString(R.string.dns_vpn_notification_desc)
                builder.setContentTitle(context.getString(R.string.dns_vpn_notification_title))
                    .setContentText(desc)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(desc))
                    .setSmallIcon(R.drawable.ic_shield_dns)
                    .setCategory(NotificationCompat.CATEGORY_SERVICE)
            }
        }
        return builder.build()
    }

    private fun attachFortressCountdownAndExtend(
        context: Context,
        builder: NotificationCompat.Builder,
        remainingMs: Long,
        iconRes: Int,
        piFlags: Int
    ) {
        builder.setUsesChronometer(true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            builder.setChronometerCountDown(true)
        }
        builder.setWhen(System.currentTimeMillis() + remainingMs.coerceAtLeast(0L))
        builder.setShowWhen(true)

        val extendIntent = Intent(context, FortressNotificationReceiver::class.java).apply {
            action = FortressNotificationReceiver.ACTION_EXTEND_FORTRESS
        }
        val extendPendingIntent = PendingIntent.getBroadcast(context, 101, extendIntent, piFlags)
        builder.addAction(iconRes, context.getString(R.string.notif_action_extend_fortress), extendPendingIntent)
    }

    @Synchronized
    fun syncProtectionNotification(context: Context) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        syncProtectionNotificationInternal(
            context = context,
            onCancel = { id ->
                try {
                    notificationManager.cancel(id)
                } catch (e: Exception) {
                    Timber.tag(TAG).w(e, "Error cancelling protection notification")
                }
            },
            onPost = { id, state ->
                if (canPostNotification(context)) {
                    try {
                        notificationManager.notify(id, buildProtectionNotification(context, state))
                    } catch (e: Exception) {
                        Timber.tag(TAG).e(e, "Error posting protection notification")
                    }
                }
            }
        )
    }

    @androidx.annotation.VisibleForTesting
    internal fun syncProtectionNotificationInternal(
        context: Context,
        onCancel: (Int) -> Unit,
        onPost: (Int, ProtectionState) -> Unit
    ) {
        val state = resolveCurrentState(context)
        if (state is ProtectionState.Inactive) {
            val activeService = CurfewVpnStateCoordinator.activeService?.get()
            if (!CurfewBlackholeVpnService.isServiceOrTunnelActive() || activeService?.isForegroundActive != true) {
                onCancel(NOTIFICATION_ID_PROTECTION)
            }
            return
        }
        if (state is ProtectionState.DnsFilteringActive && CurfewVpnStateCoordinator.canRunDnsVpnSilently(context)) {
            CurfewVpnStateCoordinator.syncForegroundPresentation()
            onCancel(NOTIFICATION_ID_PROTECTION)
            return
        }
        onPost(NOTIFICATION_ID_PROTECTION, state)
    }

    fun postFortressEndedNotification(context: Context, @Suppress("UNUSED_PARAMETER") lastFortressName: String? = null) {
        try {
            val descRes = if (CurfewEnforcer.isEffectiveBlockActive(context)) {
                R.string.notif_fortress_ended_desc_curfew_still_active
            } else {
                R.string.notif_fortress_ended_desc
            }
            AppNotificationManager.getInstance(context).postEventNotification(
                category = NotificationCategory.CURFEW,
                title = context.getString(R.string.notif_fortress_ended_title),
                message = context.getString(descRes),
                dedupKey = "fortress_ended_${TrustedTimeProvider.currentTimeMillis(context) / 60000}",
                cooldownMs = 60_000L,
                actionType = NotificationActionType.NONE,
                postSystemNotification = false
            )
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Error recording fortress ended in notification center")
        }
    }
}
