package com.drmohammed.antiporn.banking

import android.app.Activity
import android.content.Context
import com.drmohammed.antiporn.R
import com.drmohammed.antiporn.core.time.TrustedTimeProvider
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import timber.log.Timber

/**
 * مساعد ذكي لعرض دعوة إضافة اختصار الدخول البنكي الآمن بعد تفعيل الحماية الفورية،
 * فقط عندما يكون على الجهاز تطبيقات بنكية مثبتة (بأولوية إنستاباي)، مع احترام تفضيل المستخدم ومنع الإزعاج المتكرر.
 */
object BankingShortcutPromptHelper {

    private const val TAG = "AntiPorn_BankPrompt"
    private const val PREFS_NAME = "hisn_banking_prompt_prefs"
    private const val KEY_PINNED = "key_banking_shortcut_pinned"
    private const val KEY_DISMISSED_AT_MS = "key_banking_prompt_dismissed_at_ms"
    private const val DISMISS_COOLDOWN_MS = 14L * 24L * 60L * 60L * 1000L // 14 days

    /**
     * يتحقق من وجود تطبيقات بنكية مثبتة ويعرض نافذة إضافة الاختصار الآمن إن لم يسبق تثبيته أو رفضه مؤخراً.
     *
     * @return true إذا تم عرض النافذة (وسيتم استدعاء [onDismiss] عند إغلاقها)،
     *         false إذا لم تُعرض النافذة (لم يُثبت أي بنك أو سبق التثبيت/الرفض مؤخراً).
     */
    fun maybePrompt(
        context: Context,
        forceShow: Boolean = false,
        onDismiss: () -> Unit = {}
    ): Boolean {
        val activity = context as? Activity
        if (activity != null && (activity.isFinishing || activity.isDestroyed)) {
            return false
        }

        val appContext = context.applicationContext ?: context
        if (!forceShow && shouldSuppressPrompt(appContext)) {
            return false
        }

        val installedBankingApps = try {
            BankingAppRegistry.getInstalledBankingApps(appContext)
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Failed querying installed banking apps")
            emptyList()
        }

        if (installedBankingApps.isEmpty()) {
            return false
        }

        val primaryApp = installedBankingApps.first()
        var callbackDispatched = false
        val dispatchDismissOnce = {
            if (!callbackDispatched) {
                callbackDispatched = true
                onDismiss()
            }
        }

        return try {
            MaterialAlertDialogBuilder(context)
                .setTitle(R.string.banking_shortcut_prompt_title)
                .setMessage(R.string.banking_shortcut_prompt_msg)
                .setPositiveButton(R.string.banking_shortcut_prompt_add_btn) { dialog, _ ->
                    val pinned = BankingShortcutHelper.pinBankingShortcut(
                        context = context,
                        targetPackage = primaryApp.packageName,
                        customLabel = primaryApp.appName
                    )
                    if (pinned) {
                        markPinned(appContext)
                    }
                    dialog.dismiss()
                    dispatchDismissOnce()
                }
                .setNegativeButton(R.string.banking_shortcut_prompt_later_btn) { dialog, _ ->
                    markDismissed(appContext)
                    dialog.dismiss()
                    dispatchDismissOnce()
                }
                .setOnCancelListener {
                    markDismissed(appContext)
                    dispatchDismissOnce()
                }
                .show()
            true
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Could not display banking shortcut prompt dialog")
            false
        }
    }

    private fun shouldSuppressPrompt(appContext: Context): Boolean {
        return try {
            val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            if (prefs.getBoolean(KEY_PINNED, false)) {
                return true
            }
            val lastDismissedAt = prefs.getLong(KEY_DISMISSED_AT_MS, 0L)
            val now = TrustedTimeProvider.currentTimeMillis(appContext)
            val elapsed = now - lastDismissedAt
            lastDismissedAt > 0L && elapsed in 0L until DISMISS_COOLDOWN_MS
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Failed checking banking shortcut prompt suppression state")
            false
        }
    }

    private fun markPinned(appContext: Context) {
        try {
            appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_PINNED, true)
                .apply()
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Failed persisting pinned state for banking shortcut prompt")
        }
    }

    private fun markDismissed(appContext: Context) {
        try {
            val now = TrustedTimeProvider.currentTimeMillis(appContext)
            appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putLong(KEY_DISMISSED_AT_MS, now)
                .apply()
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Failed persisting dismissed timestamp for banking shortcut prompt")
        }
    }
}
