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
    const val SURVIVING_AUTH_BEGIN_MARKER = "__HISN_SURVIVING_AUTH_BEGIN__"
    const val SURVIVING_AUTH_END_MARKER = "__HISN_SURVIVING_AUTH_END__"
    const val SURVIVING_AUTH_UNAVAILABLE = "__UNAVAILABLE__"

    val PACKAGE_NAME_REGEX = Regex("^[a-zA-Z0-9_]+(\\.[a-zA-Z0-9_]+)+$")
    val SAFE_ACCOUNT_TYPE_REGEX = Regex("^[a-zA-Z0-9_.-]+$")
    val SAFE_PATH_REGEX = Regex("^[a-zA-Z0-9_./-]+$")
    val ADMIN_COMPONENT_REGEX = Regex("^[a-zA-Z0-9_]+(\\.[a-zA-Z0-9_]+)+/\\.?[a-zA-Z0-9_.]+$")

    internal data class SurvivingAuthSnapshot(
        val observed: Boolean,
        val types: List<String>
    )
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

    fun extractPreDpmOutput(raw: String): String =
        raw.substringBefore(SURVIVING_AUTH_BEGIN_MARKER).substringBefore(DPM_BEGIN_MARKER)

    fun extractSurvivingAuthSnapshot(raw: String): SurvivingAuthSnapshot {
        val startIdx = raw.indexOf(SURVIVING_AUTH_BEGIN_MARKER)
        if (startIdx < 0) return SurvivingAuthSnapshot(observed = false, types = emptyList())
        val contentStart = startIdx + SURVIVING_AUTH_BEGIN_MARKER.length
        val endIdx = raw.indexOf(SURVIVING_AUTH_END_MARKER, startIndex = contentStart)
        if (endIdx < contentStart) return SurvivingAuthSnapshot(observed = false, types = emptyList())
        val body = raw.substring(contentStart, endIdx).trim()
        if (body == SURVIVING_AUTH_UNAVAILABLE) return SurvivingAuthSnapshot(observed = false, types = emptyList())
        val types = body.split(Regex("\\s+"))
            .map { it.trim() }
            .filter { it.isNotEmpty() && SAFE_ACCOUNT_TYPE_REGEX.matches(it) }
            .distinct()
        return SurvivingAuthSnapshot(observed = true, types = types)
    }

    fun hasDisableFailureSignal(text: String): Boolean =
        text.contains("SecurityException", ignoreCase = true) ||
            text.contains("Failure", ignoreCase = true) ||
            text.contains("Exception", ignoreCase = true) ||
            text.contains("Error:", ignoreCase = true) ||
            text.contains("Unknown package", ignoreCase = true) ||
            text.contains("protected package", ignoreCase = true)

    fun buildFreezeSettleAndProbeCmd(trackedAccountTypes: List<String>): String {
        val safeTracked = trackedAccountTypes.map { it.trim() }.filter { SAFE_ACCOUNT_TYPE_REGEX.matches(it) }.distinct()
        if (safeTracked.isEmpty()) return "sleep 2"
        val typesArg = safeTracked.joinToString(" ")
        return "surv=\"\"; prev_surv=\"\"; stable=0; obs=0; settle=0; " +
            "while [ \$settle -lt 6 ]; do sleep 1; settle=\$((settle + 1)); " +
            "auths=\$(dumpsys account 2>/dev/null | grep \"AuthenticatorDescription\"); " +
            "if [ -z \"\$auths\" ]; then [ \$settle -ge 2 ] && break; continue; fi; " +
            "obs=1; surv=\"\"; " +
            "for t in $typesArg; do case \"\$auths\" in *\"type=\$t}\"*|*\"type=\$t,\"*) surv=\"\$surv \$t\";; esac; done; " +
            "[ -z \"\$surv\" ] && break; " +
            "if [ \"\$surv\" = \"\$prev_surv\" ]; then stable=\$((stable + 1)); [ \$stable -ge 2 ] && break; " +
            "else stable=0; prev_surv=\"\$surv\"; fi; done; " +
            "printf \"%s\\n\" \"$SURVIVING_AUTH_BEGIN_MARKER\"; " +
            "if [ \"\$obs\" = \"1\" ]; then printf \"%s\\n\" \"\$surv\"; else printf \"%s\\n\" \"$SURVIVING_AUTH_UNAVAILABLE\"; fi; " +
            "printf \"%s\\n\" \"$SURVIVING_AUTH_END_MARKER\""
    }

    fun sanitizeUserFacingError(raw: String, maxLength: Int = 220): String {
        if (maxLength <= 0 || raw.isBlank()) return ""
        val cleanedLines = mutableListOf<String>()
        var inUsageBlock = false
        for (line in raw.lineSequence()) {
            val noMarkers = line
                .replace(DPM_BEGIN_MARKER, "")
                .replace(DPM_END_MARKER, "")
                .replace(SURVIVING_AUTH_BEGIN_MARKER, "")
                .replace(SURVIVING_AUTH_END_MARKER, "")
                .replace(SURVIVING_AUTH_UNAVAILABLE, "")
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
