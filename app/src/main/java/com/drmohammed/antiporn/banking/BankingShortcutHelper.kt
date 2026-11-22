package com.drmohammed.antiporn.banking

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.widget.Toast
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.drmohammed.antiporn.R
import timber.log.Timber

/**
 * مساعد تثبيت اختصارات التطبيقات البنكية الآمنة على الشاشة الرئيسية للهاتف.
 */
object BankingShortcutHelper {

    private const val TAG = "AntiPorn_BankingShortcutHelper"
    const val SHORTCUT_ID_INSTAPAY = "shortcut_instapay_safe"
    const val EXTRA_LAUNCH_TOKEN = "com.drmohammed.antiporn.extra.LAUNCH_TOKEN"
    private const val PREFS_NAME = "banking_security_prefs"
    private const val KEY_LAUNCH_TOKEN = "banking_launch_token"

    @Synchronized
    fun getOrCreateLaunchToken(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        var token = prefs.getString(KEY_LAUNCH_TOKEN, null)
        if (token.isNullOrBlank()) {
            token = java.util.UUID.randomUUID().toString()
            prefs.edit().putString(KEY_LAUNCH_TOKEN, token).apply()
        }
        return token
    }

    fun isValidLaunchToken(context: Context, candidateToken: String?): Boolean {
        if (candidateToken.isNullOrBlank()) return false
        val validToken = getOrCreateLaunchToken(context)
        return java.security.MessageDigest.isEqual(
            candidateToken.toByteArray(Charsets.UTF_8),
            validToken.toByteArray(Charsets.UTF_8)
        )
    }

    /**
     * تثبيت اختصار إنستاباي الآمن على الشاشة الرئيسية (للتوافق القديم).
     */
    fun pinInstaPayShortcut(context: Context): Boolean {
        return pinBankingShortcut(context, BankingCloakManager.INSTAPAY_PACKAGE)
    }

    /**
     * تثبيت اختصار آمن لأي تطبيق بنكي معتمد على الشاشة الرئيسية (Pinned Shortcut).
     *
     * @param context سياق التطبيق
     * @param targetPackage حزمة التطبيق البنكي المعتمد
     * @param customLabel اسم مخصص للتطبيق، أو يتم جلبه تلقائياً من النظام
     * @return true إذا تم إرسال طلب التثبيت بنجاح، false خلاف ذلك.
     */
    fun pinBankingShortcut(context: Context, targetPackage: String, customLabel: String? = null): Boolean {
        val appContext = context.applicationContext ?: context

        if (!BankingAppRegistry.isAllowed(targetPackage)) {
            Timber.tag(TAG).e("Refusing to pin shortcut for non-whitelisted package: $targetPackage")
            return false
        }

        val pm = appContext.packageManager
        val launchIntent = try {
            pm.getLaunchIntentForPackage(targetPackage)
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Error checking if banking app is installed: $targetPackage")
            null
        }

        if (launchIntent == null) {
            try {
                Toast.makeText(appContext, R.string.banking_app_not_installed, Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Timber.tag(TAG).w(e, "Toast failed")
            }
            return false
        }

        val isSupported = try {
            ShortcutManagerCompat.isRequestPinShortcutSupported(appContext)
        } catch (e: Throwable) {
            Timber.tag(TAG).w(e, "Error checking pin shortcut support")
            false
        }

        if (!isSupported) {
            try {
                Toast.makeText(appContext, R.string.shortcut_not_supported, Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Timber.tag(TAG).w(e, "Toast failed")
            }
            return false
        }

        val appLabel = customLabel ?: try {
            val appInfo = pm.getApplicationInfo(targetPackage, 0)
            pm.getApplicationLabel(appInfo).toString()
        } catch (e: Exception) {
            targetPackage
        }

        val icon = createIconSafely(appContext, targetPackage)

        val targetIntent = Intent(appContext, SafeBankingLauncherActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            putExtra(SafeBankingLauncherActivity.EXTRA_PACKAGE_NAME, targetPackage)
            putExtra(EXTRA_LAUNCH_TOKEN, getOrCreateLaunchToken(appContext))
        }

        val shortcutId = "shortcut_safe_bank_" + targetPackage.replace(".", "_")

        val longLabel = try {
            appContext.getString(R.string.banking_shortcut_custom_long_label_format, appLabel)
        } catch (e: Exception) {
            appLabel
        }

        val shortcutInfo = ShortcutInfoCompat.Builder(appContext, shortcutId)
            .setShortLabel(appLabel)
            .setLongLabel(longLabel)
            .setIcon(icon)
            .setIntent(targetIntent)
            .build()

        try {
            ShortcutManagerCompat.requestPinShortcut(appContext, shortcutInfo, null)
        } catch (e: Throwable) {
            Timber.tag(TAG).w(e, "Error requesting pin shortcut for $targetPackage")
        }

        try {
            Toast.makeText(appContext, R.string.shortcut_pin_success, Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Toast failed")
        }

        return true
    }

    /**
     * تحويل آمن لأيقونة التطبيق البنكي إلى IconCompat مع تفادي انهيارات AdaptiveIconDrawable.
     */
    private fun createIconSafely(context: Context, targetPackage: String): IconCompat {
        return try {
            val drawable = context.packageManager.getApplicationIcon(targetPackage)
            val width = if (drawable.intrinsicWidth > 0) drawable.intrinsicWidth else 108
            val height = if (drawable.intrinsicHeight > 0) drawable.intrinsicHeight else 108
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            drawable.setBounds(0, 0, canvas.width, canvas.height)
            drawable.draw(canvas)
            IconCompat.createWithBitmap(bitmap)
        } catch (e: Throwable) {
            Timber.tag(TAG).w(e, "Could not extract banking app icon for $targetPackage, using fallback")
            try {
                IconCompat.createWithResource(context, R.drawable.ic_bank)
            } catch (e2: Throwable) {
                IconCompat.createWithResource(context, R.mipmap.ic_launcher)
            }
        }
    }

    /**
     * التحقق مما إذا كان تطبيق إنستاباي مثبتاً على الهاتف.
     */
    fun isInstaPayInstalled(context: Context): Boolean {
        val appContext = context.applicationContext ?: context
        return try {
            appContext.packageManager.getLaunchIntentForPackage(BankingCloakManager.INSTAPAY_PACKAGE) != null
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Error checking if InstaPay is installed")
            false
        }
    }
}
