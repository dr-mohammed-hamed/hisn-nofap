package com.drmohammed.antiporn.banking

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.widget.Toast
import com.drmohammed.antiporn.R
import com.drmohammed.antiporn.accessibility.control.AccessibilitySelfActivator
import timber.log.Timber

/**
 * مدير التخفي الآمن لتطبيقات البنوك والمصارف (Core Banking Cloak Engine).
 * 
 * يتيح فتح تطبيقات البنوك الحساسة (مثل إنستاباي - InstaPay) بدون أن تعترضها أو تفحصها
 * خدمة إمكانية الوصول HisnAccessibilityService، مما يحول دون إغلاق البنك لنفسه بدعوى
 * وجود برمجيات خبيثة أو خدمات وصول نشطة.
 * 
 * يعمل بآلية أمان مزدوجة:
 * 1. فورية: مراقبة مغادرة حزمة البنك فوراً واستعادة الحماية بمجرد فتح أي تطبيق آخر أو العودة للشاشة الرئيسية.
 * 2. زمنية (Fail-Safe): منبه نظامي قسري لمدة 3 دقائق (180_000ms) يعيد الحماية تلقائياً حتى لو تعطلت المراقبة.
 */
object BankingCloakManager {

    private const val TAG = "AntiPorn_BankingCloak"

    const val INSTAPAY_PACKAGE = "com.egyptianbanks.instapay"
    const val CLOAK_TIMEOUT_MS = 180_000L // 3 minutes
    const val DEFAULT_SETTLING_DELAY_MS = 250L // ربع ثانية لضمان اكتمال فصل الخدمة في أندرويد
    const val ACTION_RESTORE_BANKING_CLOAK = "com.drmohammed.antiporn.action.RESTORE_BANKING_CLOAK"
    private const val RESTORE_ALARM_REQUEST_CODE = 4001

    /**
     * الحزم النظامية المستثناة من إيقاف وضع التخفي عند ظهورها كواجهات عابرة أو لوحة مفاتيح.
     * تشمل واجهات النظام، وأذونات أندرويد، وكافة لوحات المفاتيح الآمنة لمصنعي الأجهزة الأصلية (OEMs):
     * ريلمي/أوبو (ColorOS)، شاومي (MIUI/HyperOS)، سامسونج (One UI)، وهواوي.
     */
    val DEFAULT_IGNORED_PACKAGES: Set<String> = setOf(
        // إطار أندرويد وواجهة النظام
        "com.android.systemui",
        "android",
        "com.android.permissioncontroller",
        "com.google.android.permissioncontroller",
        // ريلمي وأوبو وون بلس (Realme UI / ColorOS / OxygenOS)
        "com.coloros.keyboard", // لوحة مفاتيح ريلمي/أوبو الآمنة لكلمات السر والـ PIN
        "com.oppo.keyboard",
        "com.oplus.keyboard",
        "com.sohu.inputmethod.sogou.oem",
        "com.coloros.safecenter",
        "com.coloros.securitypermission",
        "com.oplus.securitypermission",
        "com.coloros.systemclone",
        "com.heytap.speechassist",
        // شاومي وريدمي وبوكو (MIUI / HyperOS)
        "com.miui.securityadd", // لوحة إدخال الـ PIN المحمية في شاومي
        "com.sohu.inputmethod.sogou.xiaomi",
        "com.miui.securitycenter",
        "com.lbe.security.miui",
        // سامسونج (One UI)
        "com.sec.android.inputmethod",
        "com.samsung.android.honeyboard",
        // هواوي وهونر (EMUI / MagicOS)
        "com.huawei.secime",
        "com.huawei.securitymgr",
        "com.huawei.systemmanager",
        // لوحات المفاتيح الشائعة
        "com.google.android.inputmethod.latin",
        "com.touchtype.swiftkey"
    )

    @Volatile
    var activeTargetPackage: String = INSTAPAY_PACKAGE
        private set

    val isCloaked: Boolean
        get() = AccessibilitySelfActivator.isTemporarilyCloaked

    /**
     * يتحقق مما إذا كانت الحزمة تنتمي لقائمة الحزم المستثناة العابرة.
     */
    fun isIgnoredPackage(packageName: String): Boolean {
        return packageName in DEFAULT_IGNORED_PACKAGES
    }

    /**
     * يتحقق مما إذا كانت خيارات المطورين (Developer Options) أو تصحيح أخطاء USB مفعلة على الهاتف.
     */
    fun isDeveloperOptionsEnabled(context: Context): Boolean {
        return try {
            val resolver = (context.applicationContext ?: context).contentResolver
            val devEnabled = android.provider.Settings.Global.getInt(
                resolver,
                android.provider.Settings.Global.DEVELOPMENT_SETTINGS_ENABLED,
                0
            ) == 1
            val adbEnabled = android.provider.Settings.Global.getInt(
                resolver,
                android.provider.Settings.Global.ADB_ENABLED,
                0
            ) == 1
            devEnabled || adbEnabled
        } catch (e: Exception) {
            false
        }
    }

    /**
     * تشغيل آمن لتطبيق البنك المحدد مع تفعيل التخفي الصامت لخدمة الوصول وجدولة منبه استعادة الحماية.
     *
     * @param settlingDelayMs مهلة تسكين اختيارية بالمللي ثانية لضمان اكتمال فصل الخدمة في أندرويد قبل ظهور البنك.
     */
    @Synchronized
    fun launchSafely(
        context: Context,
        targetPackage: String = INSTAPAY_PACKAGE,
        settlingDelayMs: Long = 0L
    ): Boolean {
        if (!BankingAppRegistry.isAllowed(targetPackage)) {
            Timber.tag(TAG).e("Security violation: Rejecting banking cloak request for non-whitelisted package: $targetPackage")
            return false
        }

        val appContext = context.applicationContext ?: context
        val pm = try {
            appContext.packageManager
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Unable to access package manager")
            null
        }

        val launchIntent = try {
            pm?.getLaunchIntentForPackage(targetPackage)
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Error querying launch intent for $targetPackage")
            null
        }

        if (launchIntent == null) {
            try {
                Toast.makeText(appContext, R.string.banking_app_not_installed, Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Timber.tag(TAG).w(e, "Could not display Toast (headless/test environment)")
            }
            Timber.tag(TAG).w("Target banking package not installed or unlaunchable: $targetPackage")
            return false
        }

        // 1. تطهير وفصل كافة نوافذ التعتيم العائمة فوراً لمنع حظر Tapjacking في البنك
        try {
            com.drmohammed.antiporn.ai.overlay.ScreenBlurOverlayManager.dismissAllOverlaysForBanking()
        } catch (e: Throwable) {
            Timber.tag(TAG).w(e, "Failed dismissing overlays for banking launch")
        }

        // 2. فحص استباقي لخيارات المطورين والتنبيه عليها دون المساس بـ ADB (المادة 2 من الدستور)
        if (isDeveloperOptionsEnabled(appContext)) {
            try {
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    try {
                        Toast.makeText(appContext, R.string.banking_dev_options_warning, Toast.LENGTH_LONG).show()
                    } catch (e: Throwable) {
                        Timber.tag(TAG).w(e, "Toast for dev options warning failed")
                    }
                }
            } catch (e: Throwable) {
                Timber.tag(TAG).w(e, "Posting dev options warning toast failed")
            }
        }

        activeTargetPackage = targetPackage

        // 3. إيقاف وتخفي خدمة الوصول صامتاً
        val cloaked = AccessibilitySelfActivator.disableAndCloakSilently(appContext, isTemporaryCloak = true)
        Timber.tag(TAG).i("Banking cloak activated (result=$cloaked) for target: $targetPackage")

        // 4. جدولة منبه استعادة الحماية كصمام أمان زمني
        scheduleRestoreAlarm(appContext)

        // 5. تشغيل تطبيق البنك في مهمة جديدة
        try {
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        } catch (e: Throwable) {
            Timber.tag(TAG).w(e, "Could not add flags to launchIntent")
        }

        return if (settlingDelayMs > 0L) {
            try {
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    startActivityInternal(context, launchIntent, appContext, targetPackage)
                }, settlingDelayMs)
                true
            } catch (e: Throwable) {
                Timber.tag(TAG).w(e, "Handler postDelayed failed, launching immediately")
                startActivityInternal(context, launchIntent, appContext, targetPackage)
            }
        } else {
            startActivityInternal(context, launchIntent, appContext, targetPackage)
        }
    }

    private fun startActivityInternal(
        context: Context,
        launchIntent: Intent,
        appContext: Context,
        targetPackage: String
    ): Boolean {
        val targetContext = if (context is android.app.Activity && context.isFinishing) appContext else context
        return try {
            targetContext.startActivity(launchIntent)
            Timber.tag(TAG).i("Successfully started banking activity for $targetPackage")
            true
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to start activity for $targetPackage; rolling back cloak immediately")
            restoreProtection(appContext)
            false
        }
    }

    /**
     * يتم استدعاؤها عند تغير التطبيق المتواجد في الواجهة (من خلال ForegroundAppEventBus أو Fallback Watcher).
     */
    fun onForegroundPackageChanged(
        context: Context,
        newForegroundPackage: String,
        targetPackage: String = activeTargetPackage
    ) {
        if (!AccessibilitySelfActivator.isTemporarilyCloaked) return

        if (newForegroundPackage.isNotBlank() &&
            newForegroundPackage != targetPackage &&
            newForegroundPackage != context.packageName &&
            !isIgnoredPackage(newForegroundPackage)
        ) {
            Timber.tag(TAG).i("Foreground switched to $newForegroundPackage while cloaked for $targetPackage; restoring protection")
            restoreProtection(context)
        }
    }

    /**
     * استعادة حماية خدمة الوصول وإلغاء المنبه الزمني فوراً.
     */
    @Synchronized
    fun restoreProtection(context: Context) {
        val appContext = context.applicationContext ?: context
        cancelRestoreAlarm(appContext)
        val restored = AccessibilitySelfActivator.enableSilently(appContext, forceUncloak = true)
        activeTargetPackage = INSTAPAY_PACKAGE
        Timber.tag(TAG).i("Safe banking session concluded; HisnAccessibilityService restored (enabled=$restored)")
    }

    /**
     * جدولة منبه استعادة الحماية النظامي عبر AlarmManager.
     */
    fun scheduleRestoreAlarm(context: Context, timeoutMs: Long = CLOAK_TIMEOUT_MS) {
        val appContext = context.applicationContext ?: context
        try {
            val alarmManager = appContext.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
            val pendingIntent = createRestorePendingIntent(appContext) ?: return
            val triggerAtMillis = SystemClock.elapsedRealtime() + timeoutMs

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (alarmManager.canScheduleExactAlarms()) {
                    alarmManager.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAtMillis, pendingIntent)
                } else {
                    alarmManager.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAtMillis, pendingIntent)
                }
            } else {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAtMillis, pendingIntent)
            }
            Timber.tag(TAG).d("Scheduled restore alarm for +${timeoutMs}ms")
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Failed to schedule restore alarm")
        }
    }

    /**
     * إلغاء منبه استعادة الحماية.
     */
    fun cancelRestoreAlarm(context: Context) {
        val appContext = context.applicationContext ?: context
        try {
            val alarmManager = appContext.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
            val pendingIntent = createRestorePendingIntent(appContext) ?: return
            alarmManager.cancel(pendingIntent)
            pendingIntent.cancel()
            Timber.tag(TAG).d("Canceled restore alarm")
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Failed to cancel restore alarm")
        }
    }

    private fun createRestorePendingIntent(context: Context): PendingIntent? {
        return try {
            val intent = Intent(context, BankingRestoreReceiver::class.java).apply {
                action = ACTION_RESTORE_BANKING_CLOAK
            }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            PendingIntent.getBroadcast(context, RESTORE_ALARM_REQUEST_CODE, intent, flags)
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Failed to create PendingIntent for BankingRestoreReceiver")
            null
        }
    }
}
