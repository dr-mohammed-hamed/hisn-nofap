package com.drmohammed.antiporn.banking

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import timber.log.Timber

/**
 * نشاط وسيط شفاف لتشغيل تطبيق البنك بأمان وبدون وميض أو واجهة مستخدم.
 * يتم استدعاؤه من اختصار سطح المكتب أو ودجت التطبيق، فيقوم بتفعيل التخفي،
 * إطلاق تطبيق البنك المعتمد، ثم إنهاء نفسه فوراً.
 */
class SafeBankingLauncherActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleLauncherIntent(intent)
        finish()
        applyZeroTransition()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleLauncherIntent(intent)
        finish()
        applyZeroTransition()
    }

    private fun handleLauncherIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_VIEW || intent?.action == Intent.ACTION_MAIN) {
            val targetPackage = intent.getStringExtra(EXTRA_PACKAGE_NAME) ?: BankingCloakManager.INSTAPAY_PACKAGE
            if (BankingAppRegistry.isAllowed(targetPackage)) {
                BankingCloakManager.launchSafely(this, targetPackage)
            } else {
                Timber.tag(TAG).e("Unauthorized launcher attempt for non-whitelisted package: $targetPackage")
            }
        }
    }

    private fun applyZeroTransition() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            overrideActivityTransition(OVERRIDE_TRANSITION_OPEN, 0, 0)
            overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
        } else {
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }
    }

    companion object {
        private const val TAG = "AntiPorn_SafeBankLaunch"
        const val EXTRA_PACKAGE_NAME = "com.drmohammed.antiporn.extra.BANKING_PACKAGE"
    }
}
