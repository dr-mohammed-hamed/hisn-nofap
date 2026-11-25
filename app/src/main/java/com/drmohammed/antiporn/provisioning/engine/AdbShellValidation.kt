package com.drmohammed.antiporn.provisioning.engine

import android.app.NotificationManager
import android.content.Context
import androidx.annotation.StringRes
import com.drmohammed.antiporn.notification.registry.NotificationRegistry
import io.github.rhythmcache.adb.ShellResult
import timber.log.Timber

/**
 * Shared validation regexes, shell output helpers, and safe string/notification utilities
 * for ADB provisioning engine components.
 */
internal object AdbShellValidation {

    private const val TAG = "AntiPorn_AdbShell"

    val PACKAGE_NAME_REGEX = Regex("^[a-zA-Z0-9_]+(\\.[a-zA-Z0-9_]+)+$")
    val SAFE_PATH_REGEX = Regex("^[a-zA-Z0-9_./-]+$")
    val ADMIN_COMPONENT_REGEX = Regex("^[a-zA-Z0-9_]+(\\.[a-zA-Z0-9_]+)+/\\.?[a-zA-Z0-9_.]+$")

    fun ShellResult.combinedOutput(): String = (stdoutText + "\n" + stderrText).trim()

    fun isDpmUserOptionUnsupported(output: String): Boolean =
        output.contains("unknown option: --user", ignoreCase = true) ||
            output.contains("illegal option", ignoreCase = true)

    fun cancelFreezeRecoveryNotification(context: Context?) {
        val appContext = context?.applicationContext ?: context ?: return
        try {
            (appContext.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager)
                ?.cancel(NotificationRegistry.EVENT_FREEZE_RECOVERY_ID)
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Failed cancelling freeze recovery notification")
        }
    }
}

internal fun Context?.safeGetString(@StringRes resId: Int, vararg args: Any): String? = this?.let { ctx ->
    runCatching {
        if (args.isEmpty()) ctx.getString(resId) else ctx.getString(resId, *args)
    }.recoverCatching {
        if (args.isEmpty()) ctx.resources.getString(resId) else ctx.resources.getString(resId, *args)
    }.getOrNull()?.takeIf { it.isNotBlank() }
}
