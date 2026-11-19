package com.drmohammed.antiporn

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.drmohammed.antiporn.core.engine.ProtectionTransitionGuard
import com.drmohammed.antiporn.notification.registry.NotificationRegistry
import com.drmohammed.antiporn.watchdog.ProtectionWatchdogRuntime
import com.drmohammed.antiporn.watchdog.WatchdogOrchestrator
import timber.log.Timber

/**
 * Watchdog Service host.
 *
 * On Android 8.0+ (API 26+), this service acts exclusively as an emergency fallback host
 * ([ProtectionWatchdogRuntime.WatchdogHost.FGS_FALLBACK]) if [HisnDeviceAdminService] cannot be bound.
 *
 * On Android 7.0–7.1 (API 24–25), this service acts as a standard quiet background service
 * ([ProtectionWatchdogRuntime.WatchdogHost.LEGACY_BG_SERVICE]) with ZERO notifications.
 */
class DnsWatchdogService : Service() {

    companion object {
        private const val TAG = "AntiPorn_WatchdogService"
        const val ACTION_EMERGENCY_FALLBACK = "com.drmohammed.antiporn.ACTION_EMERGENCY_FALLBACK"

        const val CHANNEL_ID = NotificationRegistry.CHANNEL_ID_WATCHDOG_FALLBACK
        const val NOTIFICATION_ID = NotificationRegistry.FGS_WATCHDOG_ID

        @Volatile
        var isRunning: Boolean = false
            private set

        /**
         * Legacy bridge: redirects external callers directly to [WatchdogOrchestrator.ensureActive].
         */
        fun startService(context: Context) {
            WatchdogOrchestrator.ensureActive(context)
        }
    }

    override fun onCreate() {
        super.onCreate()
        Timber.tag(TAG).i("DnsWatchdogService created")
        isRunning = true

        val appContext = applicationContext ?: this

        // ==========================================
        // 1. Android 7.0–7.1 (API 24–25): Pure Background Service (Zero Notification)
        // ==========================================
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            ProtectionWatchdogRuntime.attach(
                context = appContext,
                host = ProtectionWatchdogRuntime.WatchdogHost.LEGACY_BG_SERVICE
            )
            return
        }

        // ==========================================
        // 2. Android 8.0+ (API 26+): Emergency FGS Contract Compliance
        // ==========================================
        // If HisnDeviceAdminService bound while this service was being started by the system,
        // or if teardown is in progress, we MUST satisfy the FGS start contract before stopping
        // to prevent ForegroundServiceDidNotStartInTimeException.
        if (HisnDeviceAdminService.isBound || ProtectionTransitionGuard.isTeardownInProgress) {
            Timber.tag(TAG).i("DeviceAdminService is bound or teardown active; stopping safely with FGS contract.")
            stopSafelyWithFgsContract()
            return
        }

        // Start fallback foreground notification with lazy channel creation
        startForegroundFallbackNotification()
        ProtectionWatchdogRuntime.attach(
            context = appContext,
            host = ProtectionWatchdogRuntime.WatchdogHost.FGS_FALLBACK
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        isRunning = true
        Timber.tag(TAG).i("DnsWatchdogService started (action=${intent?.action})")

        val appContext = applicationContext ?: this

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (HisnDeviceAdminService.isBound || ProtectionTransitionGuard.isTeardownInProgress) {
                stopSafelyWithFgsContract()
                return START_NOT_STICKY
            }
            startForegroundFallbackNotification()
            ProtectionWatchdogRuntime.attach(
                context = appContext,
                host = ProtectionWatchdogRuntime.WatchdogHost.FGS_FALLBACK
            )
        } else {
            ProtectionWatchdogRuntime.attach(
                context = appContext,
                host = ProtectionWatchdogRuntime.WatchdogHost.LEGACY_BG_SERVICE
            )
        }

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        Timber.tag(TAG).w("DnsWatchdogService destroyed")
        isRunning = false

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            ProtectionWatchdogRuntime.detach(ProtectionWatchdogRuntime.WatchdogHost.LEGACY_BG_SERVICE)
        } else {
            ProtectionWatchdogRuntime.detach(ProtectionWatchdogRuntime.WatchdogHost.FGS_FALLBACK)
        }

        super.onDestroy()
    }

    /**
     * Satisfies Android OS Foreground Service startup requirements then immediately terminates
     * cleanly when primary [HisnDeviceAdminService] is active.
     */
    private fun stopSafelyWithFgsContract() {
        try {
            startForegroundFallbackNotification()
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Failed startForeground fallback before stopping")
        }
        isRunning = false
        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Failed stopForeground fallback")
        }
        stopSelf()
    }

    /**
     * Lazily creates the fallback notification channel with [NotificationManager.IMPORTANCE_LOW]
     * and displays the fallback ongoing notification.
     */
    private fun startForegroundFallbackNotification() {
        try {
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return

            val channelTitle = getString(R.string.watchdog_notification_title)
            val channelDesc = getString(R.string.watchdog_notification_desc)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                // Lazily create fallback channel with IMPORTANCE_LOW to prevent secondary OS alerts
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    channelTitle,
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = channelDesc
                    setShowBadge(false)
                }
                notificationManager.createNotificationChannel(channel)
            }

            val notification = NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(channelTitle)
                .setSmallIcon(R.drawable.ic_shield_dns)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build()

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed promoting DnsWatchdogService to foreground")
            stopSelf()
        }
    }
}
