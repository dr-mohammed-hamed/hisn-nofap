package com.drmohammed.antiporn.accessibility.control

import android.Manifest
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.drmohammed.antiporn.accessibility.HisnAccessibilityService
import com.drmohammed.antiporn.core.time.TrustedTimeProvider
import com.drmohammed.antiporn.telemetry.SafeDpmGateway
import timber.log.Timber

/**
 * مدير التفعيل والإفاقة والتخفي الذاتي لخدمة الوصول (HisnAccessibilityService).
 * يستفيد من صلاحيات Device Owner و WRITE_SECURE_SETTINGS للتحكم البرمجي الصامت،
 * ويستخدم disableSelf() النظيف دون تعطيل المكون في PackageManager أبداً.
 */
object AccessibilitySelfActivator {

    private const val TAG = "AntiPorn_A11yActivator"
    private const val COLON_DELIMITER = ":"
    private const val CLOAK_PREFS_NAME = "hisn_banking_cloak_prefs"
    private const val KEY_IS_CLOAKED = "is_cloaked"
    private const val KEY_CLOAK_EXPIRES_AT_MS = "cloak_expires_at_ms"
    private const val KEY_CLOAK_BOOT_COUNT = "cloak_boot_count"
    private const val DEFAULT_CLOAK_DURATION_MS = 180_000L
    internal const val MAX_CLOAK_DURATION_MS = 10L * 60L * 1000L
    private const val INTENTIONAL_DISABLE_GRACE_MS = 5_000L
    private val fallbackMonoOriginNanos: Long = System.nanoTime()

    @Volatile
    var isTemporarilyCloaked: Boolean = false
        private set

    @Volatile
    private var cloakExpiresAtMs: Long = 0L

    private val intentionalDisableLock = Any()

    @Volatile
    var isIntentionalDisableInProgress: Boolean = false
        private set

    @Volatile
    private var intentionalDisableMarkedAtMs: Long = -1L

    private fun safeElapsedRealtime(): Long {
        return try {
            TrustedTimeProvider.elapsedRealtimeProvider()
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "elapsedRealtimeProvider unavailable; falling back to monotonic nanoTime")
            ((System.nanoTime() - fallbackMonoOriginNanos) / 1_000_000L) + 1L
        }
    }

    private fun currentTrustedNow(appContext: Context): Long {
        return try {
            TrustedTimeProvider.currentTimeMillis(appContext)
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "TrustedTimeProvider unavailable; falling back to elapsedRealtime")
            safeElapsedRealtime()
        }
    }

    private fun currentBootCount(appContext: Context): Int {
        return try {
            val resolver = appContext.contentResolver ?: return -1
            Settings.Global.getInt(resolver, Settings.Global.BOOT_COUNT, -1)
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Could not read Settings.Global.BOOT_COUNT")
            -1
        }
    }

    fun markIntentionalDisable() {
        synchronized(intentionalDisableLock) {
            intentionalDisableMarkedAtMs = safeElapsedRealtime()
            isIntentionalDisableInProgress = true
        }
    }

    fun clearIntentionalDisable() {
        synchronized(intentionalDisableLock) {
            isIntentionalDisableInProgress = false
            intentionalDisableMarkedAtMs = -1L
        }
    }

    fun isIntentionalDisableActive(): Boolean {
        synchronized(intentionalDisableLock) {
            if (!isIntentionalDisableInProgress) return false
            val markedAt = intentionalDisableMarkedAtMs
            if (markedAt < 0L) {
                isIntentionalDisableInProgress = false
                intentionalDisableMarkedAtMs = -1L
                return false
            }
            val elapsed = safeElapsedRealtime() - markedAt
            if (elapsed < 0L || elapsed > INTENTIONAL_DISABLE_GRACE_MS) {
                isIntentionalDisableInProgress = false
                intentionalDisableMarkedAtMs = -1L
                return false
            }
            return true
        }
    }

    fun getServiceComponent(context: Context): ComponentName {
        return ComponentName(context.applicationContext ?: context, HisnAccessibilityService::class.java)
    }

    /**
     * يتحقق مما إذا كان التطبيق يمتلك صلاحية WRITE_SECURE_SETTINGS رسمياً.
     */
    fun canWriteSecureSettings(context: Context): Boolean {
        return try {
            val appContext = context.applicationContext ?: context
            ContextCompat.checkSelfPermission(
                appContext,
                Manifest.permission.WRITE_SECURE_SETTINGS
            ) == PackageManager.PERMISSION_GRANTED
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Could not check WRITE_SECURE_SETTINGS permission")
            false
        }
    }

    /**
     * يتحقق مما إذا كانت هناك راية تخفي بنكي محفوظة (دون مسحها)،
     * ليتمكن مستقبل الإقلاع (BootReceiver) من استعادة الحماية إذا أعيد تشغيل الهاتف أثناء التخفي.
     */
    fun hasPendingCloakFlag(context: Context): Boolean {
        if (isTemporarilyCloaked) return true
        return try {
            val appContext = context.applicationContext ?: context
            appContext.getSharedPreferences(CLOAK_PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_IS_CLOAKED, false)
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Could not inspect persisted banking cloak flag")
            false
        }
    }

    /**
     * يتحقق من حالة التخفي البنكي المؤقت سواء في الذاكرة أو المخزنة في SharedPreferences،
     * ويقوم تلقائياً بمسح الحالة إذا انقضت مهلة الانتهاء أو تغير عداد إقلاع النظام.
     */
    @Synchronized
    fun isCloaked(context: Context): Boolean {
        val appContext = context.applicationContext ?: context
        val now = currentTrustedNow(appContext)

        if (isTemporarilyCloaked) {
            val memExpiry = cloakExpiresAtMs
            if (memExpiry <= 0L || now >= memExpiry) {
                clearCloakState(appContext)
                return false
            }
            return true
        }

        val persisted = try {
            val prefs = appContext.getSharedPreferences(CLOAK_PREFS_NAME, Context.MODE_PRIVATE)
            val flagged = prefs.getBoolean(KEY_IS_CLOAKED, false)
            if (!flagged) {
                return false
            }
            val expiresAt = prefs.getLong(KEY_CLOAK_EXPIRES_AT_MS, 0L)
            val savedBoot = prefs.getInt(KEY_CLOAK_BOOT_COUNT, -1)
            Pair(expiresAt, savedBoot)
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Could not read persisted banking cloak state")
            null
        } ?: return false

        val (expiresAt, savedBoot) = persisted
        val currBoot = currentBootCount(appContext)
        if (savedBoot != -1 && currBoot != -1 && savedBoot != currBoot) {
            Timber.tag(TAG).i("Clearing stale banking cloak state after device reboot (savedBoot=$savedBoot, currBoot=$currBoot)")
            clearCloakState(appContext)
            return false
        }

        if (expiresAt <= 0L || now >= expiresAt) {
            clearCloakState(appContext)
            return false
        }

        isTemporarilyCloaked = true
        cloakExpiresAtMs = expiresAt
        return true
    }

    /**
     * يجدد مهلة التخفي المؤقت أثناء استمرار المستخدم داخل التطبيق البنكي.
     */
    @Synchronized
    fun refreshCloakExpiry(context: Context, cloakDurationMs: Long = DEFAULT_CLOAK_DURATION_MS) {
        val appContext = context.applicationContext ?: context
        if (isCloaked(appContext)) {
            persistCloakState(appContext, cloakDurationMs)
        }
    }

    /**
     * يمسح حالة التخفي البنكي المؤقت من الذاكرة والتخزين الدائم.
     */
    @Synchronized
    fun clearCloakState(context: Context) {
        isTemporarilyCloaked = false
        cloakExpiresAtMs = 0L
        try {
            val appContext = context.applicationContext ?: context
            appContext.getSharedPreferences(CLOAK_PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_IS_CLOAKED, false)
                .putLong(KEY_CLOAK_EXPIRES_AT_MS, 0L)
                .putInt(KEY_CLOAK_BOOT_COUNT, -1)
                .apply()
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Could not clear persisted banking cloak state")
        }
    }

    private fun persistCloakState(appContext: Context, cloakDurationMs: Long) {
        val boundedDuration = cloakDurationMs.coerceIn(1_000L, MAX_CLOAK_DURATION_MS)
        val expiresAt = currentTrustedNow(appContext) + boundedDuration
        val bootCount = currentBootCount(appContext)
        isTemporarilyCloaked = true
        cloakExpiresAtMs = expiresAt
        try {
            appContext.getSharedPreferences(CLOAK_PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_IS_CLOAKED, true)
                .putLong(KEY_CLOAK_EXPIRES_AT_MS, expiresAt)
                .putInt(KEY_CLOAK_BOOT_COUNT, bootCount)
                .apply()
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Could not persist banking cloak state")
        }
    }

    /**
     * يضمن بقاء مكون HisnAccessibilityService ظاهراً وممكّناً في PackageManager
     * لإصلاح أي أجهزة تعطّل فيها المكون سابقاً في إصدارات أقدم.
     */
    fun ensureComponentVisibleInPackageManager(context: Context) {
        try {
            val appContext = context.applicationContext ?: context
            val pm = appContext.packageManager ?: return
            val component = getServiceComponent(appContext)
            if (pm.getComponentEnabledSetting(component) == PackageManager.COMPONENT_ENABLED_STATE_DISABLED) {
                pm.setComponentEnabledSetting(
                    component,
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                    PackageManager.DONT_KILL_APP
                )
                Timber.tag(TAG).i("Restored HisnAccessibilityService component visibility in PackageManager")
            }
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Could not verify/restore HisnAccessibilityService component visibility")
        }
    }

    /**
     * يتحقق مما إذا كانت الخدمة مفعلة ومسجلة في إعدادات نظام أندرويد.
     */
    fun isServiceEnabledInSettings(context: Context): Boolean {
        return try {
            val appContext = context.applicationContext ?: context
            val resolver = appContext.contentResolver ?: return false
            val isGlobalEnabled = Settings.Secure.getInt(resolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0) == 1
            if (!isGlobalEnabled) return false

            val enabledServices = Settings.Secure.getString(
                resolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ).orEmpty()

            val component = getServiceComponent(appContext)
            val longName = component.flattenToString()
            val shortName = component.flattenToShortString()

            val servicesList = enabledServices.split(COLON_DELIMITER).map { it.trim() }
            servicesList.contains(longName) || servicesList.contains(shortName)
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Error checking isServiceEnabledInSettings")
            false
        }
    }

    /**
     * تفعيل خدمة الوصول برمجياً وبصمت تام دون مسح أي خدمات أخرى للمستخدم.
     *
     * @param forceUncloak عند تمرير true، يُلغى وضع التخفي المؤقت ويُفرض التفعيل مجدداً.
     */
    @Synchronized
    fun enableSilently(context: Context, forceUncloak: Boolean = false): Boolean {
        val appContext = context.applicationContext ?: context

        if (forceUncloak) {
            clearCloakState(appContext)
        } else if (isCloaked(appContext)) {
            Timber.tag(TAG).d("enableSilently skipped: Service is temporarily cloaked (e.g. Banking protection)")
            return false
        }

        try {
            // 1. التأكد من ظهور المكون البرمجي في PackageManager
            ensureComponentVisibleInPackageManager(appContext)

            // 2. السماح للخدمة على مستوى DevicePolicyManager إذا كان التطبيق Device Owner
            if (SafeDpmGateway.isDeviceOwner(appContext)) {
                try {
                    val dpm = appContext.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
                    val admin = SafeDpmGateway.getAdminComponent(appContext)
                    if (dpm != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                        val permitted = dpm.getPermittedAccessibilityServices(admin)
                        if (permitted != null && !permitted.contains(appContext.packageName)) {
                            // إضافة حزمتنا للقائمة المسموحة دون إزالة القيود عن التطبيقات الأخرى
                            dpm.setPermittedAccessibilityServices(admin, permitted + appContext.packageName)
                        }
                    }
                } catch (e: Exception) {
                    Timber.tag(TAG).w(e, "Failed reconciling permitted accessibility services via DPM")
                }
            }

            // 3. التحقق من صلاحية WRITE_SECURE_SETTINGS قبل الكتابة في Settings.Secure
            val resolver = try {
                appContext.contentResolver
            } catch (e: Exception) {
                Timber.tag(TAG).w(e, "Could not access ContentResolver in enableSilently")
                null
            }
            if (resolver == null) {
                Timber.tag(TAG).d("enableSilently skipped: ContentResolver unavailable (headless/test)")
                return false
            }
            if (!canWriteSecureSettings(appContext)) {
                Timber.tag(TAG).d("enableSilently skipped cleanly: WRITE_SECURE_SETTINGS not granted")
                return false
            }

            // 4. قراءة وتحديث Settings.Secure دون مسح الخدمات الأخرى
            val component = getServiceComponent(appContext)
            val existingServices = Settings.Secure.getString(
                resolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ).orEmpty()

            val longName = component.flattenToString()
            val shortName = component.flattenToShortString()

            val currentTokens = existingServices.split(COLON_DELIMITER)
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .toMutableSet()

            val alreadyConfigured = currentTokens.contains(longName) || currentTokens.contains(shortName)

            if (!alreadyConfigured) {
                currentTokens.add(longName)
                val newConfig = currentTokens.joinToString(COLON_DELIMITER)
                val writeOk = Settings.Secure.putString(
                    resolver,
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                    newConfig
                )
                Timber.tag(TAG).i("Added $longName to ENABLED_ACCESSIBILITY_SERVICES (Result: $writeOk)")
                if (!writeOk) {
                    Timber.tag(TAG).w("Failed writing ENABLED_ACCESSIBILITY_SERVICES in enableSilently")
                    return false
                }
            }

            // 5. تفعيل الراية العامة ACCESSIBILITY_ENABLED = 1
            if (Settings.Secure.getInt(resolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0) != 1) {
                val enabledOk = Settings.Secure.putInt(resolver, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
                if (!enabledOk) {
                    Timber.tag(TAG).w("Failed setting ACCESSIBILITY_ENABLED=1 in enableSilently")
                    return false
                }
            }

            if (!isServiceEnabledInSettings(appContext)) {
                Timber.tag(TAG).w("enableSilently verification failed: service not reflected in Settings.Secure")
                return false
            }

            Timber.tag(TAG).i("HisnAccessibilityService successfully enabled silently")
            return true
        } catch (e: SecurityException) {
            Timber.tag(TAG).w(e, "SecurityException: Missing WRITE_SECURE_SETTINGS permission")
            return false
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Unexpected error in enableSilently")
            return false
        }
    }

    /**
     * إيقاف خدمة الوصول بنظافة تامة عبر disableSelf() و/أو Settings.Secure دون المساس بـ PackageManager.
     *
     * @param isTemporaryCloak إذا كان true، يُحفظ وضع التخفي المؤقت لتطبيقات البنوك في الذاكرة و SharedPreferences.
     * @param cloakDurationMs مهلة انتهاء التخفي المؤقت بالمللي ثانية.
     */
    @Synchronized
    fun disableCleanly(
        context: Context,
        isTemporaryCloak: Boolean = false,
        cloakDurationMs: Long = DEFAULT_CLOAK_DURATION_MS
    ): Boolean {
        val appContext = context.applicationContext ?: context
        var disableSelfCalled = false
        var settingsWriteSuccess = false

        markIntentionalDisable()
        if (isTemporaryCloak) {
            persistCloakState(appContext, cloakDurationMs)
        }
        return try {
            val component = getServiceComponent(appContext)
            val longName = try {
                component.flattenToString()
            } catch (e: Exception) {
                Timber.tag(TAG).w(e, "Could not flatten component to string; using fallback")
                "${appContext.packageName}/${HisnAccessibilityService::class.java.name}"
            }
            val shortName = try {
                component.flattenToShortString()
            } catch (e: Exception) {
                Timber.tag(TAG).w(e, "Could not flatten component to short string; using fallback")
                "${appContext.packageName}/.accessibility.HisnAccessibilityService"
            }

            // 1. استدعاء disableSelf() النظيف إذا كانت الخدمة متصلة (أندرويد 7+)
            val activeService = HisnAccessibilityService.instance
            if (activeService != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                try {
                    activeService.disableSelf()
                    disableSelfCalled = true
                    Timber.tag(TAG).i("Called HisnAccessibilityService.disableSelf() cleanly")
                } catch (e: Exception) {
                    Timber.tag(TAG).w(e, "Failed calling disableSelf() on HisnAccessibilityService")
                }
            }

            // 2. إزالة الخدمة من Settings.Secure إذا توفرت صلاحية WRITE_SECURE_SETTINGS (Check-Before-Write)
            val resolver = try {
                appContext.contentResolver
            } catch (e: Exception) {
                Timber.tag(TAG).w(e, "Could not access ContentResolver in disableCleanly")
                null
            }

            if (resolver != null && canWriteSecureSettings(appContext)) {
                try {
                    val existingServices = Settings.Secure.getString(
                        resolver,
                        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
                    ).orEmpty()

                    val existingTokens = existingServices.split(COLON_DELIMITER)
                        .map { it.trim() }
                        .filter { it.isNotEmpty() }

                    val remainingTokens = existingTokens.filter { it != longName && it != shortName }

                    if (remainingTokens.size != existingTokens.size) {
                        val updatedConfig = remainingTokens.joinToString(COLON_DELIMITER)
                        settingsWriteSuccess = Settings.Secure.putString(
                            resolver,
                            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                            updatedConfig
                        )
                    }

                    if (remainingTokens.isEmpty() &&
                        Settings.Secure.getInt(resolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0) != 0
                    ) {
                        val disabledGlobal = Settings.Secure.putInt(resolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0)
                        if (disabledGlobal) {
                            settingsWriteSuccess = true
                        }
                    }
                } catch (e: Exception) {
                    Timber.tag(TAG).w(e, "Settings.Secure write failed while disabling cleanly")
                }
            }

            val isTestOrHeadless = (resolver == null)
            val serviceStillEnabled = !isTestOrHeadless && isServiceEnabledInSettings(appContext)
            val success = disableSelfCalled || settingsWriteSuccess || isTestOrHeadless || !serviceStillEnabled

            if (success) {
                if (!isTemporaryCloak) {
                    clearCloakState(appContext)
                }
                Timber.tag(TAG).i(
                    "HisnAccessibilityService disabled cleanly (isTemporaryCloak=$isTemporaryCloak, " +
                        "disableSelf=$disableSelfCalled, secureSettings=$settingsWriteSuccess, headless=$isTestOrHeadless)"
                )
                true
            } else {
                clearCloakState(appContext)
                Timber.tag(TAG).e("Failed disabling HisnAccessibilityService cleanly")
                false
            }
        } catch (e: Exception) {
            clearCloakState(appContext)
            Timber.tag(TAG).w(e, "Unexpected error disabling HisnAccessibilityService cleanly")
            false
        } finally {
            // إذا لم تكن الخدمة متصلة ولم تنجح كتابة الإعدادات فلن يُستدعى onUnbind، لذا نصفر الراية فوراً
            if (!disableSelfCalled && !settingsWriteSuccess) {
                clearIntentionalDisable()
            }
        }
    }

    /**
     * واجهة متوافقة مع الإصدارات السابقة — تفوض مباشرة إلى [disableCleanly] دون تعطيل المكون في PackageManager.
     */
    @Synchronized
    fun disableAndCloakSilently(context: Context, isTemporaryCloak: Boolean = false): Boolean {
        return disableCleanly(context, isTemporaryCloak = isTemporaryCloak)
    }
}
