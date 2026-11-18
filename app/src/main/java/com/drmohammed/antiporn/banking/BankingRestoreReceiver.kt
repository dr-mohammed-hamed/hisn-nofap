package com.drmohammed.antiporn.banking

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import timber.log.Timber

/**
 * مستقبل المنبه النظامي وإشعار الوضع البنكي الآمن لاستعادة الحماية أو فتح إعدادات إمكانية الوصول.
 */
class BankingRestoreReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context?, intent: Intent?) {
        val targetContext = context ?: return
        val action = try {
            intent?.action
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Could not read intent action in BankingRestoreReceiver")
            null
        }
        when (action) {
            BankingCloakManager.ACTION_RESTORE_BANKING_CLOAK -> {
                Timber.tag(TAG).i("BankingRestoreReceiver triggered (action=$action); restoring protection")
                BankingCloakManager.restoreProtection(targetContext)
            }
            BankingCloakManager.ACTION_OPEN_A11Y_AFTER_BANKING -> {
                Timber.tag(TAG).i("BankingRestoreReceiver triggered (action=$action); delegating to restoreProtection without Activity trampoline")
                BankingCloakManager.restoreProtection(targetContext)
            }
            else -> {
                Timber.tag(TAG).w("Ignoring unexpected broadcast action: $action")
            }
        }
    }

    companion object {
        private const val TAG = "AntiPorn_BankingCloak"
    }
}
