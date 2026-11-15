package com.drmohammed.antiporn.pageshield.engine

import android.content.Context
import com.drmohammed.antiporn.core.time.TrustedTimeProvider
import com.drmohammed.antiporn.telemetry.SafeDpmGateway
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * معالج استعادة حالة تجميد «درع الصفحات» بعد إعادة تشغيل الهاتف (Boot Recovery).
 */
object PageShieldBootHandler {

    private const val TAG = "AntiPorn_PageShieldBoot"

    suspend fun onBoot(context: Context): Unit = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext ?: context
        Timber.tag(TAG).i("PageShieldBootHandler reconciling active freezes on boot...")

        try {
            val nowEpoch = TrustedTimeProvider.currentTimeMillis(appContext)
            val frozenRecords = PageShieldFreezeStore.getAllFrozenRecords(appContext)

            for (record in frozenRecords) {
                if (record.isExpired(nowEpoch)) {
                    Timber.tag(TAG).i("PageShield freeze expired for ${record.packageName} during shutdown; lifting suspension.")
                    PageShieldAlarmReceiver.liftFreezeChecked(appContext, record.packageName, record.appName)
                } else {
                    val remainingSeconds = record.remainingMs(nowEpoch) / 1000
                    Timber.tag(TAG).i("PageShield freeze still active for ${record.packageName}; rescheduling unfreeze alarm for remaining ${remainingSeconds}s.")

                    // تأكيد التجميد في DPM
                    if (SafeDpmGateway.isDeviceOwner(appContext)) {
                        SafeDpmGateway.setPackagesSuspended(appContext, arrayOf(record.packageName), true)
                    }

                    PageShieldAlarmScheduler.scheduleUnfreezeAlarm(
                        context = appContext,
                        packageName = record.packageName,
                        appName = record.appName,
                        triggerEpochMs = record.freezeEndEpochMs
                    )
                }
            }
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Error reconciling PageShield on boot")
        }
    }
}
