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

    const val DPM_BEGIN_MARKER = "__HISN_DPM_BEGIN__"
    const val DPM_END_MARKER = "__HISN_DPM_END__"

    val PACKAGE_NAME_REGEX = Regex("^[a-zA-Z0-9_]+(\\.[a-zA-Z0-9_]+)+$")
    val SAFE_PATH_REGEX = Regex("^[a-zA-Z0-9_./-]+$")
    val ADMIN_COMPONENT_REGEX = Regex("^[a-zA-Z0-9_]+(\\.[a-zA-Z0-9_]+)+/\\.?[a-zA-Z0-9_.]+$")
    private val PACKAGE_NEW_STATE_REGEX = Regex("^\\s*Package\\s+\\S+\\s+new state:.*$", RegexOption.IGNORE_CASE)
    private val STACK_TRACE_AT_REGEX = Regex("^\\s*at\\s+.*$")
    private val STACK_TRACE_CAUSED_BY_REGEX = Regex("^\\s*Caused by:\\s*.*$")
    private val STACK_TRACE_MORE_REGEX = Regex("^\\s*\\.\\.\\.\\s*\\d+\\s+more.*$")
    private val DPM_ERROR_LINE_REGEX = Regex("^\\s*(?:Error:|(?:[a-zA-Z0-9_.]+\\.)?[A-Za-z0-9_]*(?:Exception|Error)\\b:?)", RegexOption.IGNORE_CASE)

    fun ShellResult.combinedOutput(): String = (stdoutText + "\n" + stderrText).trim()

    fun isDpmUserOptionUnsupported(output: String): Boolean =
        output.contains("unknown option: --user", ignoreCase = true) ||
            output.contains("illegal option", ignoreCase = true)

    fun isAdminBeingRemovedOutput(output: String): Boolean =
        output.contains("being removed", ignoreCase = true) ||
            output.contains("mRemovingAdmins", ignoreCase = true)

    fun extractDelimitedDpmOutput(raw: String): String {
        val startIdx = raw.indexOf(DPM_BEGIN_MARKER)
        if (startIdx < 0) return raw.trim()
        val contentStart = startIdx + DPM_BEGIN_MARKER.length
        val endIdx = raw.indexOf(DPM_END_MARKER, startIndex = contentStart)
        return if (endIdx >= contentStart) raw.substring(contentStart, endIdx).trim() else raw.trim()
    }

    fun sanitizeUserFacingError(raw: String, maxLength: Int = 220): String {
        if (maxLength <= 0 || raw.isBlank()) return ""
        val cleanedLines = mutableListOf<String>()
        var inUsageBlock = false
        for (line in raw.lineSequence()) {
            val noMarkers = line.replace(DPM_BEGIN_MARKER, "").replace(DPM_END_MARKER, "")
            val trimmedStart = noMarkers.trimStart()
            if (trimmedStart.startsWith("usage: dpm", ignoreCase = true)) {
                inUsageBlock = true
                continue
            }
            if (inUsageBlock) {
                val isErrorLine = DPM_ERROR_LINE_REGEX.containsMatchIn(noMarkers) ||
                    noMarkers.contains("Error:", ignoreCase = true) ||
                    noMarkers.contains("Exception:", ignoreCase = true)
                if (!isErrorLine) continue
                inUsageBlock = false
            }
            if (PACKAGE_NEW_STATE_REGEX.matches(noMarkers) ||
                STACK_TRACE_AT_REGEX.matches(noMarkers) ||
                STACK_TRACE_CAUSED_BY_REGEX.matches(noMarkers) ||
                STACK_TRACE_MORE_REGEX.matches(noMarkers)
            ) {
                continue
            }
            val collapsedLine = noMarkers.trim()
            if (collapsedLine.isNotEmpty()) cleanedLines.add(collapsedLine)
        }
        val collapsed = cleanedLines.joinToString(" ").replace(Regex("\\s+"), " ").trim()
        if (collapsed.length <= maxLength) return collapsed
        return if (maxLength <= 3) collapsed.take(maxLength)
        else collapsed.take(maxLength - 3).trimEnd() + "..."
    }

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
