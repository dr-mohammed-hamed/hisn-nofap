package com.drmohammed.antiporn.banking

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import timber.log.Timber

/**
 * مستقبل المنبه النظامي لاستعادة الحماية تلقائياً بعد انقضاء المهلة الزمنية للتخفي.
 */
class BankingRestoreReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context?, intent: Intent?) {
        val targetContext = context ?: return
        val action = try { intent?.action } catch (e: Throwable) { null }
        if (action != BankingCloakManager.ACTION_RESTORE_BANKING_CLOAK) {
            Timber.tag(TAG).w("Ignoring unexpected broadcast action: $action")
            return
        }
        Timber.tag(TAG).i("BankingRestoreReceiver triggered (action=$action); restoring protection")
        BankingCloakManager.restoreProtection(targetContext)
    }

    companion object {
        private const val TAG = "AntiPorn_BankingCloak"
    }
}
