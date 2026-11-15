package com.drmohammed.antiporn.banking

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.core.graphics.drawable.toBitmap
import com.drmohammed.antiporn.R
import timber.log.Timber

/**
 * مساعد تثبيت اختصارات التطبيقات البنكية (مثل إنستاباي) على الشاشة الرئيسية للهاتف.
 */
object BankingShortcutHelper {

    private const val TAG = "AntiPorn_BankingShortcutHelper"
    const val SHORTCUT_ID_INSTAPAY = "shortcut_instapay_safe"

    /**
     * تثبيت اختصار إنستاباي الآمن على الشاشة الرئيسية (Pinned Shortcut).
     *
     * @param context سياق التطبيق
     * @return true إذا تم إرسال طلب التثبيت بنجاح، false إذا كان المشغل لا يدعم تثبيت الاختصارات.
     */
    fun pinInstaPayShortcut(context: Context): Boolean {
        val appContext = context.applicationContext ?: context
        if (!isInstaPayInstalled(appContext)) {
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

        val icon = try {
            val appIconDrawable = appContext.packageManager.getApplicationIcon(BankingCloakManager.INSTAPAY_PACKAGE)
            val width = if (appIconDrawable.intrinsicWidth > 0) appIconDrawable.intrinsicWidth else 108
            val height = if (appIconDrawable.intrinsicHeight > 0) appIconDrawable.intrinsicHeight else 108
            val bitmap = appIconDrawable.toBitmap(width, height)
            IconCompat.createWithBitmap(bitmap)
        } catch (e: Throwable) {
            Timber.tag(TAG).w(e, "Could not fetch InstaPay app icon, falling back to launcher icon")
            IconCompat.createWithResource(appContext, R.mipmap.ic_launcher)
        }

        val targetIntent = Intent(appContext, SafeBankingLauncherActivity::class.java).apply {
            action = Intent.ACTION_VIEW
        }

        val shortcutInfo = ShortcutInfoCompat.Builder(appContext, SHORTCUT_ID_INSTAPAY)
            .setShortLabel(appContext.getString(R.string.banking_shortcut_label))
            .setLongLabel(appContext.getString(R.string.banking_shortcut_long_label))
            .setIcon(icon)
            .setIntent(targetIntent)
            .build()

        try {
            ShortcutManagerCompat.requestPinShortcut(appContext, shortcutInfo, null)
        } catch (e: Throwable) {
            Timber.tag(TAG).w(e, "Error requesting pin shortcut")
        }

        try {
            Toast.makeText(appContext, R.string.shortcut_pin_success, Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Toast failed")
        }

        return true
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
