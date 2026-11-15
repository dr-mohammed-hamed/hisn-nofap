package com.drmohammed.antiporn.banking

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle

/**
 * نشاط وسيط شفاف لتشغيل تطبيق البنك بأمان وبدون وميض أو واجهة مستخدم.
 * يتم استدعاؤه من اختصار سطح المكتب أو ودجت التطبيق، فيقوم بتفعيل التخفي،
 * إطلاق إنستاباي، ثم إنهاء نفسه فوراً.
 */
class SafeBankingLauncherActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent?.action == Intent.ACTION_VIEW || intent?.action == Intent.ACTION_MAIN) {
            BankingCloakManager.launchSafely(this)
        }
        finish()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            overrideActivityTransition(OVERRIDE_TRANSITION_OPEN, 0, 0)
            overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
        } else {
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }
    }
}
