package com.drmohammed.antiporn

import android.app.NotificationManager
import android.app.admin.DeviceAdminService
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.annotation.RequiresApi
import com.drmohammed.antiporn.core.engine.ProtectionTransitionGuard
import com.drmohammed.antiporn.telemetry.SafeDpmGateway
import com.drmohammed.antiporn.notification.registry.NotificationRegistry
import com.drmohammed.antiporn.telemetry.BlackBoxRecorder
import com.drmohammed.antiporn.watchdog.ProtectionWatchdogRuntime
import timber.log.Timber

/**
 * Official Google Android Enterprise [DeviceAdminService] implementation (API 26+).
 *
 * Automatically bound by Android's DevicePolicyManagerService (DPMS) with
 * BIND_AUTO_CREATE | BIND_FOREGROUND_SERVICE flags. This maintains constant foreground priority
 * for the AntiPorn process, exempts it from App Hibernation and Background Execution limits,
 * and hosts all real-time system observers with ZERO startForeground() calls and ZERO ongoing notifications.
 */
@RequiresApi(Build.VERSION_CODES.O)
class HisnDeviceAdminService : DeviceAdminService() {

    companion object {
        private const val TAG = "AntiPorn_DeviceAdminService"

        @Volatile
        var isBound: Boolean = false
            private set
    }

    override fun onCreate() {
        super.onCreate()
        Timber.tag(TAG).i("HisnDeviceAdminService created / bound by DPMS")

        val appContext = applicationContext ?: this
        if (!SafeDpmGateway.isDeviceOwner(appContext) || ProtectionTransitionGuard.isTeardownInProgress) {
            Timber.tag(TAG).w("System not in Device Owner state or teardown in progress. Bypassing watchdog attach.")
            isBound = false
            return
        }

        isBound = true

        // 1. Attach primary watchdog runtime
        ProtectionWatchdogRuntime.attach(
            context = appContext,
            host = ProtectionWatchdogRuntime.WatchdogHost.DEVICE_ADMIN_SERVICE
        )

        // 2. Seamless OTA Migration / Fallback cleanup:
        // If legacy DnsWatchdogService is running with an active notification, stop it immediately.
        if (DnsWatchdogService.isRunning) {
            try {
                Timber.tag(TAG).i("Stopping legacy DnsWatchdogService after successful DeviceAdminService binding.")
                stopService(Intent(appContext, DnsWatchdogService::class.java))
            } catch (e: Exception) {
                Timber.tag(TAG).w(e, "Error stopping legacy DnsWatchdogService")
            }
        }

        // 3. Post-OTA cleanup: delete legacy watchdog notification channel if it exists
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            nm?.deleteNotificationChannel(NotificationRegistry.CHANNEL_ID_WATCHDOG)
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Error deleting legacy watchdog notification channel")
        }

        // 4. Record successful binding in Black Box Flight Recorder
        BlackBoxRecorder.record(
            context = appContext,
            tag = BlackBoxRecorder.TAG_SHIELD,
            actionName = "WATCHDOG_DEVICE_ADMIN_BOUND",
            isSuccess = true,
            metadata = "sdkInt=${Build.VERSION.SDK_INT}"
        )
    }

    override fun onDestroy() {
        Timber.tag(TAG).w("HisnDeviceAdminService destroyed / unbound by DPMS")
        isBound = false

        val appContext = applicationContext ?: this
        ProtectionWatchdogRuntime.detach(ProtectionWatchdogRuntime.WatchdogHost.DEVICE_ADMIN_SERVICE)

        BlackBoxRecorder.record(
            context = appContext,
            tag = BlackBoxRecorder.TAG_SHIELD,
            actionName = "WATCHDOG_DEVICE_ADMIN_UNBOUND",
            isSuccess = true
        )

        super.onDestroy()
    }
}
