package com.drmohammed.antiporn.pageshield.engine

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.drmohammed.antiporn.core.time.TrustedTimeProvider
import timber.log.Timber

/**
 * مدير جدولة منبهات فك التجميد الدقيقة لدرع الصفحات.
 */
object PageShieldAlarmScheduler {

    private const val TAG = "AntiPorn_PageShieldAlarm"
    const val ACTION_UNFREEZE = "com.drmohammed.antiporn.ACTION_PAGE_SHIELD_UNFREEZE"
    const val EXTRA_PACKAGE_NAME = "extra_package_name"
    const val EXTRA_APP_NAME = "extra_app_name"

    fun scheduleUnfreezeAlarm(
        context: Context,
        packageName: String,
        appName: String,
        triggerEpochMs: Long
    ) {
        val appContext = context.applicationContext
        val alarmManager = appContext.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return

        val intent = Intent(appContext, PageShieldAlarmReceiver::class.java).apply {
            action = ACTION_UNFREEZE
            putExtra(EXTRA_PACKAGE_NAME, packageName)
            putExtra(EXTRA_APP_NAME, appName)
        }

        val requestCode = packageName.hashCode()
        val pendingIntent = PendingIntent.getBroadcast(
            appContext,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val elapsedTrigger = TrustedTimeProvider.computeElapsedTrigger(triggerEpochMs, appContext)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (alarmManager.canScheduleExactAlarms()) {
                    alarmManager.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, elapsedTrigger, pendingIntent)
                    Timber.tag(TAG).i("Scheduled exact unfreeze alarm for $packageName at epoch $triggerEpochMs (elapsed=$elapsedTrigger)")
                } else {
                    alarmManager.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, elapsedTrigger, pendingIntent)
                    Timber.tag(TAG).w("Scheduled inexact unfreeze alarm fallback for $packageName at epoch $triggerEpochMs (elapsed=$elapsedTrigger)")
                }
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, elapsedTrigger, pendingIntent)
                Timber.tag(TAG).i("Scheduled exact unfreeze alarm for $packageName at epoch $triggerEpochMs (elapsed=$elapsedTrigger)")
            } else {
                alarmManager.setExact(AlarmManager.ELAPSED_REALTIME_WAKEUP, elapsedTrigger, pendingIntent)
                Timber.tag(TAG).i("Scheduled exact unfreeze alarm (pre-M) for $packageName at epoch $triggerEpochMs (elapsed=$elapsedTrigger)")
            }
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to schedule unfreeze alarm for $packageName, falling back to setAndAllowWhileIdle")
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    alarmManager.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, elapsedTrigger, pendingIntent)
                }
            } catch (ex: Exception) {
                Timber.tag(TAG).e(ex, "Fatal error scheduling fallback alarm for $packageName")
            }
        }
    }

    fun cancelUnfreezeAlarm(context: Context, packageName: String) {
        val appContext = context.applicationContext
        val alarmManager = appContext.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return

        val intent = Intent(appContext, PageShieldAlarmReceiver::class.java).apply {
            action = ACTION_UNFREEZE
            putExtra(EXTRA_PACKAGE_NAME, packageName)
        }

        val requestCode = packageName.hashCode()
        val pendingIntent = PendingIntent.getBroadcast(
            appContext,
            requestCode,
            intent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        )

        if (pendingIntent != null) {
            alarmManager.cancel(pendingIntent)
            pendingIntent.cancel()
            Timber.tag(TAG).d("Cancelled unfreeze alarm for $packageName")
        }
    }
}
