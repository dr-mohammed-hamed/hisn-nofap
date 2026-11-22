package com.drmohammed.antiporn.banking

import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Toast
import androidx.annotation.VisibleForTesting
import androidx.core.app.NotificationCompat
import com.drmohammed.antiporn.R
import com.drmohammed.antiporn.accessibility.HisnAccessibilityService
import com.drmohammed.antiporn.accessibility.control.AccessibilityPolicyCoordinator
import com.drmohammed.antiporn.accessibility.control.AccessibilityReconcileResult
import com.drmohammed.antiporn.accessibility.control.AccessibilitySelfActivator
import com.drmohammed.antiporn.core.time.TrustedTimeProvider
import com.drmohammed.antiporn.notification.registry.NotificationRegistry
import java.lang.ref.WeakReference
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
 * 2. زمنية متجددة (Fail-Safe Keep-Alive): منبه نظامي قسري لمدة 3 دقائق (180_000ms) يتجدد تلقائياً أثناء تفاعل المستخدم داخل البنك، ويعيد الحماية تلقائياً إذا تعطلت المراقبة أو تم تجاوز المهلة.
 */
object BankingCloakManager {

    private const val TAG = "AntiPorn_BankingCloak"

    const val INSTAPAY_PACKAGE = "com.egyptianbanks.instapay"
    const val CLOAK_TIMEOUT_MS = 180_000L // 3 minutes
    const val DEFAULT_SETTLING_DELAY_MS = 250L // الثابت القياسي للواجهات
    const val MIN_CLOAK_SETTLING_DELAY_MS = 350L // الحد الأدنى الفعلي لضمان اكتمال فصل الخدمة في أندرويد
    const val ACTION_RESTORE_BANKING_CLOAK = "com.drmohammed.antiporn.action.RESTORE_BANKING_CLOAK"
    const val ACTION_OPEN_A11Y_AFTER_BANKING = "com.drmohammed.antiporn.action.OPEN_A11Y_AFTER_BANKING"
    private const val RESTORE_ALARM_REQUEST_CODE = 4001
    private const val NOTIF_CLOAK_ACTIVE_REQUEST_CODE = 4002
    private const val NOTIF_MANUAL_RESTORE_REQUEST_CODE = 4003
    private const val CLOAK_LAUNCH_GRACE_MS = 2_500L
    private const val CLOAK_PREFS_NAME = "hisn_banking_cloak_prefs"
    private const val KEY_ACTIVE_TARGET_PACKAGE = "active_target_package"
    private val fallbackMonoOriginNanos: Long = System.nanoTime()

    @Volatile
    private var cachedMainHandler: Handler? = null

    @Volatile
    private var cloakGeneration: Long = 0L

    @Volatile
    private var cloakGraceUntilElapsedMs: Long = 0L

    @Volatile
    private var pendingLaunchRunnable: Runnable? = null

    @VisibleForTesting
    @Volatile
    internal var disableCleanlyAction: (Context, Long) -> Boolean = { ctx, durationMs ->
        AccessibilitySelfActivator.disableCleanly(ctx, isTemporaryCloak = true, cloakDurationMs = durationMs)
    }

    @VisibleForTesting
    @Volatile
    internal var accessibilityActiveCheckOverride: ((Context) -> Boolean)? = null

    private fun getMainHandlerOrNull(): Handler? {
        cachedMainHandler?.let { return it }
        return try {
            val looper = Looper.getMainLooper() ?: return null
            Handler(looper).also { cachedMainHandler = it }
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Main Looper unavailable (headless/test environment)")
            null
        }
    }

    private fun safeElapsedRealtime(): Long {
        return try {
            TrustedTimeProvider.elapsedRealtimeProvider()
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "elapsedRealtimeProvider unavailable; falling back to monotonic nanoTime")
            ((System.nanoTime() - fallbackMonoOriginNanos) / 1_000_000L) + 1L
        }
    }

    private fun cancelPendingLaunch() {
        val pending = pendingLaunchRunnable
        pendingLaunchRunnable = null
        if (pending != null) {
            getMainHandlerOrNull()?.removeCallbacks(pending)
        }
    }

    @VisibleForTesting
    @Synchronized
    fun resetForTesting() {
        cloakGeneration++
        cloakGraceUntilElapsedMs = 0L
        cancelPendingLaunch()
        activeTargetPackage = INSTAPAY_PACKAGE
        disableCleanlyAction = { ctx, durationMs ->
            AccessibilitySelfActivator.disableCleanly(ctx, isTemporaryCloak = true, cloakDurationMs = durationMs)
        }
        accessibilityActiveCheckOverride = null
    }

    /**
     * الحزم النظامية المستثناة من إيقاف وضع التخفي عند ظهورها كواجهات عابرة أو لوحة مفاتيح.
     * تشمل واجهات النظام، وأذونات أندرويد، وكافة لوحات المفاتيح الآمنة لمصنعي الأجهزة الأصلية (OEMs):
     * ريلمي/أوبو (ColorOS)، شاومي (MIUI/HyperOS)، سامسونج (One UI)، وهواوي.
     */
    val DEFAULT_IGNORED_PACKAGES: Set<String> = setOf(
        // إطار أندرويد وواجهة النظام وإعدادات النظام لتأكيد الهوية والبصمة (ConfirmDeviceCredentialActivity)
        "com.android.settings",
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
        "com.samsung.android.biometrics.app.setting", // واجهة مطابقة وبصمة سامسونج
        // فيفو وآيكو (Funtouch OS / OriginOS)
        "com.vivo.fingerprint", // خدمات وواجهات البصمة في فيفو
        "com.vivo.fingerprintui",
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

    fun isCloaked(context: Context): Boolean = AccessibilitySelfActivator.isCloaked(context)

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
            val devEnabled = Settings.Global.getInt(
                resolver,
                Settings.Global.DEVELOPMENT_SETTINGS_ENABLED,
                0
            ) == 1
            val adbEnabled = Settings.Global.getInt(
                resolver,
                Settings.Global.ADB_ENABLED,
                0
            ) == 1
            devEnabled || adbEnabled
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Could not query Developer Options / ADB state")
            false
        }
    }

    /**
     * تشغيل آمن لتطبيق البنك المحدد مع تفعيل التخفي النظيف لخدمة الوصول وجدولة منبه استعادة الحماية.
     * إذا كانت خدمة الوصول غير نشطة أصلاً، يُشغّل التطبيق البنكي مباشرة دون تخفي أو منبه أو تأخير.
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
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Failed dismissing overlays for banking launch")
        }

        // 2. تسجيل حالة خيارات المطورين في السجلات دون إزعاج المستخدم بتنبيهات Toast
        if (isDeveloperOptionsEnabled(appContext)) {
            Timber.tag(TAG).d("Developer options or ADB enabled on device")
        }

        try {
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Could not add flags to launchIntent")
        }

        // 3. التحقق مما إذا كانت خدمة الوصول نشطة أو مفعلة فعلياً
        val isA11yActiveOrEnabled = accessibilityActiveCheckOverride?.invoke(appContext) ?: run {
            val resolver = try {
                appContext.contentResolver
            } catch (e: Exception) {
                Timber.tag(TAG).w(e, "Could not access ContentResolver in launchSafely")
                null
            }
            if (resolver == null) {
                // بيئة اختبارات JVM بدون ContentResolver: نستمر بمسار التخفي الحتمي
                true
            } else {
                HisnAccessibilityService.instance != null ||
                    AccessibilitySelfActivator.isServiceEnabledInSettings(appContext)
            }
        }

        if (!isA11yActiveOrEnabled && !isCloaked(appContext)) {
            Timber.tag(TAG).i("Accessibility is not active or enabled; launching banking app $targetPackage directly without cloaking")
            return startActivityDirect(context, launchIntent, appContext, targetPackage)
        }

        activeTargetPackage = targetPackage
        persistActiveTargetPackage(appContext, targetPackage)

        // 4. إيقاف خدمة الوصول بنظافة تامة وتخزين حالة التخفي المؤقت
        val cloaked = disableCleanlyAction(appContext, CLOAK_TIMEOUT_MS)
        if (!cloaked) {
            Timber.tag(TAG).e("Banking cloak activation failed for target: $targetPackage; aborting launch")
            cloakGeneration++
            cloakGraceUntilElapsedMs = 0L
            cancelPendingLaunch()
            activeTargetPackage = INSTAPAY_PACKAGE
            clearPersistedActiveTargetPackage(appContext)
            cancelRestoreAlarm(appContext)
            cancelNotification(appContext, NotificationRegistry.EVENT_BANKING_CLOAK_ACTIVE_ID)
            return false
        }
        Timber.tag(TAG).i("Banking cloak activated (result=$cloaked) for target: $targetPackage")

        // 5. عرض إشعار مستمر لحالة الوضع البنكي الآمن يتيح الاستعادة بنقرة واحدة
        showCloakActiveNotification(appContext)

        // 6. جدولة منبه استعادة الحماية كصمام أمان نظامي يتجدد ديناميكياً أثناء بقاء المستخدم داخل البنك
        scheduleRestoreAlarm(appContext)

        // 7. تشغيل تطبيق البنك في مهمة جديدة مع ضمان مهلة تسكين كافية (>= 350ms في الإنتاج)
        val effectiveDelayMs = if (settlingDelayMs > 0L) {
            maxOf(settlingDelayMs, MIN_CLOAK_SETTLING_DELAY_MS)
        } else {
            0L
        }

        val generation = ++cloakGeneration
        cloakGraceUntilElapsedMs = safeElapsedRealtime() + effectiveDelayMs + CLOAK_LAUNCH_GRACE_MS
        cancelPendingLaunch()

        val contextRef = WeakReference(context)

        return if (effectiveDelayMs > 0L) {
            val handler = getMainHandlerOrNull()
            if (handler != null) {
                val runnable = Runnable {
                    synchronized(this@BankingCloakManager) {
                        if (cloakGeneration != generation || !isCloaked(appContext)) {
                            Timber.tag(TAG).d(
                                "Skipping stale delayed banking launch (gen=$generation, currentGen=$cloakGeneration)"
                            )
                            return@synchronized
                        }
                        pendingLaunchRunnable = null
                    }
                    startActivityInternal(contextRef.get() ?: appContext, launchIntent, appContext, targetPackage)
                }
                pendingLaunchRunnable = runnable
                try {
                    handler.postDelayed(runnable, effectiveDelayMs)
                    true
                } catch (e: Exception) {
                    pendingLaunchRunnable = null
                    Timber.tag(TAG).w(e, "Handler postDelayed failed, launching immediately")
                    startActivityInternal(contextRef.get() ?: appContext, launchIntent, appContext, targetPackage)
                }
            } else {
                Timber.tag(TAG).w("Main handler unavailable for delayed launch; launching immediately")
                startActivityInternal(contextRef.get() ?: appContext, launchIntent, appContext, targetPackage)
            }
        } else {
            startActivityInternal(contextRef.get() ?: appContext, launchIntent, appContext, targetPackage)
        }
    }

    private fun startActivityDirect(
        context: Context,
        launchIntent: Intent,
        appContext: Context,
        targetPackage: String
    ): Boolean {
        val targetContext = if (context is android.app.Activity && (context.isFinishing || context.isDestroyed)) appContext else context
        return try {
            targetContext.startActivity(launchIntent)
            Timber.tag(TAG).i("Successfully started banking activity directly (no cloak needed) for $targetPackage")
            true
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to start banking activity directly for $targetPackage")
            false
        }
    }

    private fun startActivityInternal(
        context: Context,
        launchIntent: Intent,
        appContext: Context,
        targetPackage: String
    ): Boolean {
        val targetContext = if (context is android.app.Activity && (context.isFinishing || context.isDestroyed)) appContext else context
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
        targetPackage: String = resolveActiveTargetPackage(context)
    ) {
        if (!isCloaked(context)) return

        // عند بقاء المستخدم متفاعلاً داخل تطبيق البنك، نخرج من مهلة الإقلاع ونجدد مؤقت الأمان الزمني لمنع المقاطعة
        if (newForegroundPackage == targetPackage) {
            cloakGraceUntilElapsedMs = 0L
            AccessibilitySelfActivator.refreshCloakExpiry(context, CLOAK_TIMEOUT_MS)
            scheduleRestoreAlarm(context, CLOAK_TIMEOUT_MS)
            return
        }

        if (safeElapsedRealtime() < cloakGraceUntilElapsedMs) {
            Timber.tag(TAG).d(
                "Within banking launch grace window; ignoring transient foreground package=$newForegroundPackage while waiting for $targetPackage"
            )
            return
        }

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
     * استعادة حماية خدمة الوصول وفق السياسة النشطة وإلغاء المنبه وإشعار التخفي فوراً.
     */
    @Synchronized
    fun restoreProtection(context: Context) {
        cloakGeneration++
        cloakGraceUntilElapsedMs = 0L
        cancelPendingLaunch()

        val appContext = context.applicationContext ?: context
        cancelRestoreAlarm(appContext)
        cancelNotification(appContext, NotificationRegistry.EVENT_BANKING_CLOAK_ACTIVE_ID)

        val result = AccessibilityPolicyCoordinator.reconcile(appContext, forceUncloak = true)
        activeTargetPackage = INSTAPAY_PACKAGE
        clearPersistedActiveTargetPackage(appContext)

        if (result is AccessibilityReconcileResult.NeedsManualActivation) {
            showManualRestoreNotification(appContext)
        } else {
            cancelNotification(appContext, NotificationRegistry.EVENT_BANKING_MANUAL_RESTORE_ID)
        }

        Timber.tag(TAG).i("Safe banking session concluded; reconcile result=$result")
    }

    /**
     * يُستدعى عند نقر المستخدم على إشعار الوضع البنكي في أجهزة QR OOBE (بدون WRITE_SECURE_SETTINGS)
     * لمسح حالة التخفي وفتح إعدادات إمكانية الوصول مباشرة.
     */
    @Synchronized
    fun handleOpenAccessibilityAfterBanking(context: Context) {
        cloakGeneration++
        cloakGraceUntilElapsedMs = 0L
        cancelPendingLaunch()

        val appContext = context.applicationContext ?: context
        cancelRestoreAlarm(appContext)
        cancelNotification(appContext, NotificationRegistry.EVENT_BANKING_CLOAK_ACTIVE_ID)
        cancelNotification(appContext, NotificationRegistry.EVENT_BANKING_MANUAL_RESTORE_ID)
        AccessibilitySelfActivator.clearCloakState(appContext)
        activeTargetPackage = INSTAPAY_PACKAGE
        clearPersistedActiveTargetPackage(appContext)

        try {
            val settingsIntent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            val launchContext = if (context is android.app.Activity && !context.isFinishing && !context.isDestroyed) {
                context
            } else {
                settingsIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                appContext
            }
            launchContext.startActivity(settingsIntent)
            Timber.tag(TAG).i("Opened Android Accessibility Settings after banking session")
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Failed opening Android Accessibility Settings after banking session")
        }
    }

    /**
     * جدولة منبه استعادة الحماية النظامي عبر AlarmManager.
     */
    fun scheduleRestoreAlarm(context: Context, timeoutMs: Long = CLOAK_TIMEOUT_MS) {
        val appContext = context.applicationContext ?: context
        try {
            val alarmManager = appContext.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
            val pendingIntent = createRestorePendingIntent(appContext) ?: return
            val triggerAtMillis = safeElapsedRealtime() + timeoutMs

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

    private fun showCloakActiveNotification(appContext: Context) {
        try {
            val manager = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
            val canAutoRestore = AccessibilitySelfActivator.canWriteSecureSettings(appContext)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            val pendingIntent = if (canAutoRestore) {
                val tapIntent = Intent(appContext, BankingRestoreReceiver::class.java).apply {
                    action = ACTION_RESTORE_BANKING_CLOAK
                }
                PendingIntent.getBroadcast(
                    appContext,
                    NOTIF_CLOAK_ACTIVE_REQUEST_CODE,
                    tapIntent,
                    flags
                )
            } else {
                val tapIntent = Intent(appContext, SafeBankingLauncherActivity::class.java).apply {
                    action = ACTION_OPEN_A11Y_AFTER_BANKING
                    putExtra(BankingShortcutHelper.EXTRA_LAUNCH_TOKEN, BankingShortcutHelper.getOrCreateLaunchToken(appContext))
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                PendingIntent.getActivity(
                    appContext,
                    NOTIF_CLOAK_ACTIVE_REQUEST_CODE,
                    tapIntent,
                    flags
                )
            }

            val title = appContext.getString(R.string.notif_banking_cloak_active_title)
            val message = appContext.getString(
                if (canAutoRestore) R.string.notif_banking_cloak_active_auto_msg
                else R.string.notif_banking_cloak_active_manual_msg
            )

            val notification = NotificationCompat.Builder(appContext, NotificationRegistry.CHANNEL_ID_PROTECTION_EVENTS)
                .setSmallIcon(R.drawable.ic_shield_device_owner)
                .setContentTitle(title)
                .setContentText(message)
                .setStyle(NotificationCompat.BigTextStyle().bigText(message))
                .setOngoing(true)
                .setAutoCancel(false)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setContentIntent(pendingIntent)
                .build()

            manager.notify(NotificationRegistry.EVENT_BANKING_CLOAK_ACTIVE_ID, notification)
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Could not show ongoing banking cloak notification")
        }
    }

    private fun showManualRestoreNotification(appContext: Context) {
        try {
            val manager = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
            val settingsIntent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            val pendingIntent = PendingIntent.getActivity(
                appContext,
                NOTIF_MANUAL_RESTORE_REQUEST_CODE,
                settingsIntent,
                flags
            )

            val title = appContext.getString(R.string.notif_banking_manual_restore_title)
            val message = appContext.getString(R.string.notif_banking_manual_restore_msg)

            val notification = NotificationCompat.Builder(appContext, NotificationRegistry.CHANNEL_ID_PROTECTION_EVENTS)
                .setSmallIcon(R.drawable.ic_shield_device_owner)
                .setContentTitle(title)
                .setContentText(message)
                .setStyle(NotificationCompat.BigTextStyle().bigText(message))
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setContentIntent(pendingIntent)
                .build()

            manager.notify(NotificationRegistry.EVENT_BANKING_MANUAL_RESTORE_ID, notification)
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Could not show manual restore banking notification")
        }
    }

    private fun cancelNotification(appContext: Context, notificationId: Int) {
        try {
            val manager = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
            manager.cancel(notificationId)
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Could not cancel notification $notificationId")
        }
    }

    private fun resolveActiveTargetPackage(context: Context): String {
        val inMemory = activeTargetPackage
        if (inMemory != INSTAPAY_PACKAGE) return inMemory
        return try {
            val appContext = context.applicationContext ?: context
            val persisted = appContext.getSharedPreferences(CLOAK_PREFS_NAME, Context.MODE_PRIVATE)
                .getString(KEY_ACTIVE_TARGET_PACKAGE, null)
            if (!persisted.isNullOrBlank()) {
                activeTargetPackage = persisted
                persisted
            } else {
                inMemory
            }
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Could not read persisted active banking target package")
            inMemory
        }
    }

    private fun persistActiveTargetPackage(appContext: Context, targetPackage: String) {
        try {
            appContext.getSharedPreferences(CLOAK_PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_ACTIVE_TARGET_PACKAGE, targetPackage)
                .apply()
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Could not persist active banking target package")
        }
    }

    private fun clearPersistedActiveTargetPackage(appContext: Context) {
        try {
            appContext.getSharedPreferences(CLOAK_PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .remove(KEY_ACTIVE_TARGET_PACKAGE)
                .apply()
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Could not clear persisted active banking target package")
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
