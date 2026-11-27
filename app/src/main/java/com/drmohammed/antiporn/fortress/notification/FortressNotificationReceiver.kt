package com.drmohammed.antiporn.fortress.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.widget.Toast
import com.drmohammed.antiporn.R
import com.drmohammed.antiporn.curfew.CurfewEnforcer
import com.drmohammed.antiporn.curfew.CurfewScheduler
import com.drmohammed.antiporn.curfew.notification.ProtectionNotificationCoordinator
import com.drmohammed.antiporn.data.repository.WardActiveModesTelemetrySyncer
import com.drmohammed.antiporn.fortress.engine.FortressSessionController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Handles action clicks from the active Fortress notification (e.g., extend session)
 * directly from the status bar without requiring the user to open MainActivity.
 */
class FortressNotificationReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) return
        if (intent.action != ACTION_EXTEND_FORTRESS) return

        val now = SystemClock.elapsedRealtime()
        val last = lastExtendClickMs.get()
        if (now - last < DEBOUNCE_MS) {
            Timber.tag(TAG).w("Debounced rapid fortress extend click from notification")
            return
        }
        if (!lastExtendClickMs.compareAndSet(last, now)) {
            Timber.tag(TAG).w("Debounced concurrent fortress extend click from notification")
            return
        }

        val appContext = context.applicationContext ?: context
        val pendingResult = goAsync()
        val finished = AtomicBoolean(false)
        fun finishOnce() {
            if (finished.compareAndSet(false, true)) {
                pendingResult.finish()
            }
        }

        receiverScope.launch {
            var delegatedToSyncCallback = false
            try {
                if (!FortressSessionController.isFortressActive(appContext)) {
                    Timber.tag(TAG).w("Attempted to extend fortress when none is active; syncing notification")
                    ProtectionNotificationCoordinator.syncProtectionNotification(appContext)
                    WardActiveModesTelemetrySyncer.syncAsync(appContext)
                    return@launch
                }

                Timber.tag(TAG).i("Received ACTION_EXTEND_FORTRESS from notification action button")
                val newEnd = FortressSessionController.extendActiveFortress(appContext)
                if (newEnd <= 0L) {
                    Timber.tag(TAG).w("Fortress expired before extend could commit; syncing state")
                    CurfewEnforcer.syncBlockState(appContext)
                    ProtectionNotificationCoordinator.syncProtectionNotification(appContext)
                    WardActiveModesTelemetrySyncer.syncAsync(appContext)
                    return@launch
                }
                CurfewScheduler.scheduleFortressEndAlarm(appContext, FortressSessionController.getEndWall(appContext))
                CurfewEnforcer.syncBlockState(appContext)
                ProtectionNotificationCoordinator.syncProtectionNotification(appContext)
                WardActiveModesTelemetrySyncer.syncAsync(appContext)
                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(appContext, R.string.toast_fortress_extended, Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Error extending fortress in receiver")
                delegatedToSyncCallback = false
            } finally {
                if (!delegatedToSyncCallback) {
                    finishOnce()
                }
            }
        }
    }

    companion object {
        private const val TAG = "AntiPorn_FortressNotifRx"
        private const val DEBOUNCE_MS = 1000L

        private val receiverScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val lastExtendClickMs = AtomicLong(0L)

        const val ACTION_EXTEND_FORTRESS = "com.drmohammed.antiporn.fortress.ACTION_EXTEND_FORTRESS"
    }
}
