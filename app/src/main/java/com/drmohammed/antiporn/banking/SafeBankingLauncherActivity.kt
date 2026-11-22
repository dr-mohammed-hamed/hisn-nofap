package com.drmohammed.antiporn.banking

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import timber.log.Timber

/**
 * نشاط وسيط شفاف لتشغيل تطبيق البنك بأمان وبدون وميض أو واجهة مستخدم.
 * يتم استدعاؤه من اختصار سطح المكتب أو ودجت التطبيق، فيقوم بتفعيل التخفي،
 * إطلاق تطبيق البنك المعتمد مع مهلة تسكين غير متزامنة، ثم إنهاء نفسه فوراً.
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
        if (intent?.action == BankingCloakManager.ACTION_OPEN_A11Y_AFTER_BANKING) {
            val token = intent.getStringExtra(BankingShortcutHelper.EXTRA_LAUNCH_TOKEN)
            if (!BankingShortcutHelper.isValidLaunchToken(applicationContext, token)) {
                Timber.tag(TAG).e("Unauthorized a11y restore attempt: invalid or missing security token")
                return
            }
            Timber.tag(TAG).i("Handling direct notification tap to restore Accessibility after banking")
            BankingCloakManager.handleOpenAccessibilityAfterBanking(this)
            return
        }

        val now = SystemClock.elapsedRealtime()
        if (now - lastLaunchTimeMs < DEBOUNCE_WINDOW_MS) {
            Timber.tag(TAG).d("Ignoring rapid duplicate launcher attempt within debounce window")
            return
        }
        lastLaunchTimeMs = now

        if (intent?.action == Intent.ACTION_VIEW || intent?.action == Intent.ACTION_MAIN) {
            val targetPackage = intent.getStringExtra(EXTRA_PACKAGE_NAME) ?: BankingCloakManager.INSTAPAY_PACKAGE
            val token = intent.getStringExtra(BankingShortcutHelper.EXTRA_LAUNCH_TOKEN)
            val isValidToken = BankingShortcutHelper.isValidLaunchToken(applicationContext, token)

            if (!isValidToken && !BankingAppRegistry.isAllowed(targetPackage)) {
                Timber.tag(TAG).e("Unauthorized launcher attempt: invalid or missing security token and non-whitelisted package: $targetPackage")
                return
            }

            if (BankingAppRegistry.isAllowed(targetPackage)) {
                if (!isValidToken) {
                    Timber.tag(TAG).w("Allowing legacy pinned shortcut launch for whitelisted package: $targetPackage")
                }
                // نمرر applicationContext مع مهلة تسكين 250ms لضمان اكتمال فصل الخدمة في النظام
                BankingCloakManager.launchSafely(
                    context = applicationContext,
                    targetPackage = targetPackage,
                    settlingDelayMs = BankingCloakManager.DEFAULT_SETTLING_DELAY_MS
                )
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
        private const val DEBOUNCE_WINDOW_MS = 1000L
        @Volatile
        private var lastLaunchTimeMs = 0L
    }
}
