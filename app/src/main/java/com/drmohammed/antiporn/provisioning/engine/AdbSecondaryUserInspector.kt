package com.drmohammed.antiporn.provisioning.engine

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Process
import android.provider.Settings
import androidx.annotation.VisibleForTesting
import com.drmohammed.antiporn.core.engine.SecondaryProfileDetector
import com.drmohammed.antiporn.telemetry.BlackBoxRecorder
import io.github.rhythmcache.adb.AdbClient
import io.github.rhythmcache.adb.ShellResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import timber.log.Timber
import java.util.Locale

/**
 * Inspects and cleans up secondary OS users/profiles over an active local ADB shell session
 * before Device Owner promotion alters system state.
 */
object AdbSecondaryUserInspector {

    private const val TAG = "AntiPorn_AdbUserInsp"
    private const val REMOVAL_POLL_ATTEMPTS = 30
    private const val REMOVAL_POLL_INTERVAL_MS = 400L
    private val USER_INFO_REGEX = Regex("""UserInfo\{(\d+):(.*):([0-9a-fA-F]+)\}""")

    @VisibleForTesting
    internal fun parsePmListUsers(output: String, currentUserId: Int = 0): List<ParsedAdbUser> {
        if (output.isBlank()) return emptyList()
        val seenIds = mutableSetOf<Int>()
        val results = mutableListOf<ParsedAdbUser>()
        for (match in USER_INFO_REGEX.findAll(output)) {
            val userId = match.groupValues[1].toIntOrNull() ?: continue
            if (userId <= 0 || userId == currentUserId) continue
            if (!seenIds.add(userId)) continue
            results.add(
                ParsedAdbUser(
                    userId = userId,
                    rawName = match.groupValues[2].trim(),
                    flagsHex = match.groupValues[3].trim()
                )
            )
        }
        return results
    }

    @VisibleForTesting
    internal fun classifyAdbUser(
        userId: Int,
        rawName: String,
        flagsHex: String,
        isSamsung: Boolean,
        localProfileType: SecondaryProfileDetector.ProfileType? = null
    ): BlockingUserCategory {
        val lower = rawName.trim().lowercase(Locale.ROOT)
        val flagsInt = flagsHex.toLongOrNull(16) ?: 0L

        return when {
            lower.contains("secure folder") ||
                lower.contains("knox") ||
                lower.contains("المجلد الآمن") ||
                lower.contains("المجلد الامن") ||
                (isSamsung && userId in 150..160) -> BlockingUserCategory.SECURE_FOLDER

            localProfileType == SecondaryProfileDetector.ProfileType.CLONE_APP ||
                userId == 95 ||
                userId == 96 ||
                userId == 999 ||
                lower.contains("dual") ||
                lower.contains("clone") ||
                lower.contains("multiapp") ||
                lower.contains("xspace") ||
                lower.contains("parallel") ||
                lower.contains("twin") ||
                lower.contains("مزدوج") ||
                lower.contains("مستنسخ") -> BlockingUserCategory.DUAL_APP_CLONE

            localProfileType == SecondaryProfileDetector.ProfileType.PRIVATE_SPACE ||
                lower.contains("private") ||
                lower.contains("second space") ||
                lower.contains("الخاصة") ||
                lower.contains("الواجهة الثانية") -> BlockingUserCategory.PRIVATE_SPACE

            ((flagsInt and 0x4L) != 0L) ||
                lower.contains("guest") ||
                lower.contains("ضيف") -> BlockingUserCategory.GUEST_USER

            localProfileType == SecondaryProfileDetector.ProfileType.WORK_PROFILE ||
                ((flagsInt and 0x20L) != 0L) ||
                lower.contains("work") ||
                lower.contains("managed") ||
                lower.contains("island") ||
                lower.contains("shelter") ||
                lower.contains("العمل") -> BlockingUserCategory.WORK_PROFILE

            else -> BlockingUserCategory.SECONDARY_USER
        }
    }

    @VisibleForTesting
    internal fun parseThirdPartyPackagesOutput(output: String): Pair<List<String>, Boolean> {
        if (output.contains("SecurityException", ignoreCase = true) ||
            output.contains("Permission Denial", ignoreCase = true) ||
            output.contains("does not have permission", ignoreCase = true)
        ) {
            return emptyList<String>() to true
        }
        val packages = output.lineSequence()
            .map { it.trim() }
            .filter { it.startsWith("package:", ignoreCase = false) }
            .map { it.removePrefix("package:").trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .toList()
        return packages to false
    }

    @VisibleForTesting
    internal fun hasRemoveUserError(res: ShellResult): Boolean {
        val output = (res.stdoutText + "\n" + res.stderrText).trim()
        return !res.isSuccess ||
            output.contains("Error", ignoreCase = true) ||
            output.contains("Exception", ignoreCase = true) ||
            output.contains("Unknown command", ignoreCase = true) ||
            output.contains("Failed", ignoreCase = true) ||
            output.contains("Failure", ignoreCase = true) ||
            output.contains("Couldn't", ignoreCase = true) ||
            output.contains("Cannot", ignoreCase = true) ||
            output.contains("Permission Denial", ignoreCase = true)
    }

    private fun resolveLocalProcessUserId(): Int? {
        return try {
            SecondaryProfileDetector.extractUserId(Process.myUserHandle())
        } catch (_: Throwable) {
            null
        }
    }

    private suspend fun resolveCurrentUserIdOrThrow(shellExecutor: suspend (String) -> ShellResult): Int {
        val res = shellExecutor("am get-current-user")
        val combined = (res.stdoutText + "\n" + res.stderrText).trim()
        val parsed = res.stdoutText.trim().toIntOrNull()
        if (!res.isSuccess || parsed == null || parsed < 0) {
            throw IllegalStateException("Failed resolving active user via ADB (exit=${res.exitCode}): $combined")
        }
        return parsed
    }

    private suspend fun listUsersOrThrow(shellExecutor: suspend (String) -> ShellResult): String {
        val usersRes = shellExecutor("pm list users")
        val rawOutput = (usersRes.stdoutText + "\n" + usersRes.stderrText).trim()
        if (!usersRes.isSuccess || !rawOutput.contains("UserInfo{")) {
            throw IllegalStateException("pm list users failed (exit=${usersRes.exitCode}): $rawOutput")
        }
        return rawOutput
    }

    suspend fun inspectBlockingUsers(context: Context, client: AdbClient): List<BlockingSecondaryUser> {
        return inspectBlockingUsersWithShell(context) { cmd -> client.shell(cmd) }
    }

    @VisibleForTesting
    internal suspend fun inspectBlockingUsersWithShell(
        context: Context,
        shellExecutor: suspend (String) -> ShellResult
    ): List<BlockingSecondaryUser> {
        val appContext = context.applicationContext ?: context
        val currentUserId = resolveCurrentUserIdOrThrow(shellExecutor)
        val localProcessUserId = resolveLocalProcessUserId()
        val rawUsersOutput = listUsersOrThrow(shellExecutor)

        val parsedUsers = parsePmListUsers(rawUsersOutput, currentUserId)
            .filter { localProcessUserId == null || it.userId != localProcessUserId }
        if (parsedUsers.isEmpty()) return emptyList()

        val localProfilesById = try {
            SecondaryProfileDetector.getSecondaryProfiles(appContext).associateBy { it.userId }
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Failed reading local secondary profiles from UserManager")
            emptyMap()
        }

        val pm: PackageManager? = appContext.packageManager
        val isSamsung = OemProvisioningHelper.isSamsung()
        val blockingUsers = mutableListOf<BlockingSecondaryUser>()

        for (parsed in parsedUsers) {
            val localProfile = localProfilesById[parsed.userId]
            val category = classifyAdbUser(
                userId = parsed.userId,
                rawName = parsed.rawName,
                flagsHex = parsed.flagsHex,
                isSamsung = isSamsung,
                localProfileType = localProfile?.profileType
            )

            var pkgShellFailed = false
            val pkgRes = try {
                shellExecutor("pm list packages -3 --user ${parsed.userId}")
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                pkgShellFailed = true
                Timber.tag(TAG).w(e, "Failed listing packages for user ${parsed.userId}")
                null
            }

            val rawPkgOutput = if (pkgRes != null) {
                pkgRes.stdoutText + "\n" + pkgRes.stderrText
            } else {
                ""
            }
            val (rawPackages, initialBlocked) = parseThirdPartyPackagesOutput(rawPkgOutput)
            val adbPackages = rawPackages.filter { it != appContext.packageName }

            val resolvedLabels = adbPackages.map { pkg -> resolveAppLabel(pm, pkg) }
            val localPackages = (localProfile?.clonedPackageNames ?: emptyList())
                .filter { it != appContext.packageName }
            val localLabels = localProfile?.clonedAppNames.orEmpty()

            val mergedPackages = (adbPackages + localPackages).distinct()
            val mergedAppNames = (resolvedLabels + localLabels)
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .distinct()

            val isAppsInspectionBlocked = pkgShellFailed ||
                (pkgRes != null && !pkgRes.isSuccess) ||
                initialBlocked ||
                (category == BlockingUserCategory.SECURE_FOLDER && mergedAppNames.isEmpty())

            blockingUsers.add(
                BlockingSecondaryUser(
                    userId = parsed.userId,
                    rawName = parsed.rawName,
                    flagsHex = parsed.flagsHex,
                    category = category,
                    installedAppNames = mergedAppNames,
                    installedPackageNames = mergedPackages,
                    isAppsInspectionBlocked = isAppsInspectionBlocked
                )
            )
        }

        return blockingUsers.sortedWith(
            compareBy<BlockingSecondaryUser> { categorySortOrder(it.category) }.thenBy { it.userId }
        )
    }

    suspend fun removeApprovedUsers(
        context: Context,
        client: AdbClient,
        approvedUserIds: Set<Int>
    ): SecondaryUserRemovalOutcome {
        return removeApprovedUsersWithShell(context, approvedUserIds) { cmd -> client.shell(cmd) }
    }

    @VisibleForTesting
    internal suspend fun removeApprovedUsersWithShell(
        context: Context,
        approvedUserIds: Set<Int>,
        shellExecutor: suspend (String) -> ShellResult
    ): SecondaryUserRemovalOutcome {
        val appContext = context.applicationContext ?: context
        val currentUserId = resolveCurrentUserIdOrThrow(shellExecutor)
        val localProcessUserId = resolveLocalProcessUserId()

        val safeApproved = approvedUserIds.filter {
            it > 0 && it != currentUserId && (localProcessUserId == null || it != localProcessUserId)
        }.toSet()
        if (safeApproved.isEmpty()) {
            val remaining = inspectBlockingUsersWithShell(appContext, shellExecutor)
            return SecondaryUserRemovalOutcome(
                removedIds = emptyList(),
                failedIds = approvedUserIds.toList(),
                remainingUsers = remaining,
                failureMessages = approvedUserIds.associateWith { "Refused invalid or active userId=$it" }
            )
        }

        val initialUsersOutput = listUsersOrThrow(shellExecutor)
        val liveTargets = parsePmListUsers(initialUsersOutput, currentUserId)
            .map { it.userId }
            .filter { it in safeApproved }

        if (liveTargets.isEmpty()) {
            val remaining = inspectBlockingUsersWithShell(appContext, shellExecutor)
            return SecondaryUserRemovalOutcome(
                removedIds = emptyList(),
                failedIds = emptyList(),
                remainingUsers = remaining,
                failureMessages = emptyMap()
            )
        }

        val failureMessages = mutableMapOf<Int, String>()
        for (targetId in liveTargets) {
            val res = shellExecutor("pm remove-user $targetId")
            val output = (res.stdoutText + "\n" + res.stderrText).trim()
            val hasError = hasRemoveUserError(res)
            if (hasError) {
                failureMessages[targetId] = output.ifBlank { "pm remove-user failed (exit=${res.exitCode})" }
            }
            BlackBoxRecorder.recordPairing(
                appContext,
                "ADB_REMOVE_SECONDARY_USER",
                isSuccess = !hasError,
                details = "userId=$targetId, exit=${res.exitCode}, output=${output.take(150)}"
            )
        }

        var stillPresentIds = liveTargets.toSet()
        repeat(REMOVAL_POLL_ATTEMPTS) { attempt ->
            val pollRes = try {
                shellExecutor("pm list users")
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Timber.tag(TAG).w(e, "Polling pm list users failed on attempt $attempt")
                null
            }
            val pollOutput = if (pollRes != null) (pollRes.stdoutText + "\n" + pollRes.stderrText).trim() else ""
            if (pollRes != null && pollRes.isSuccess && pollOutput.contains("UserInfo{")) {
                val currentLiveIds = parsePmListUsers(pollOutput, currentUserId).map { it.userId }.toSet()
                stillPresentIds = liveTargets.filter { it in currentLiveIds }.toSet()
                if (stillPresentIds.isEmpty()) return@repeat
            }
            if (attempt < REMOVAL_POLL_ATTEMPTS - 1) delay(REMOVAL_POLL_INTERVAL_MS)
        }

        val removedIds = liveTargets.filter { it !in stillPresentIds }
        val failedIds = liveTargets.filter { it in stillPresentIds }
        failedIds.forEach { failedId ->
            failureMessages.putIfAbsent(failedId, "User $failedId still present after pm remove-user")
        }

        val remainingUsers = inspectBlockingUsersWithShell(appContext, shellExecutor)
        return SecondaryUserRemovalOutcome(
            removedIds = removedIds,
            failedIds = failedIds,
            remainingUsers = remainingUsers,
            failureMessages = failureMessages
        )
    }

    fun resolveSettingsIntentForUsers(context: Context, users: List<BlockingSecondaryUser>): Intent {
        val pm = context.packageManager
        val candidates: List<Intent> = when (users.firstOrNull()?.category) {
            BlockingUserCategory.SECURE_FOLDER -> listOfNotNull(
                Intent().setComponent(ComponentName("com.samsung.knox.securefolder", "com.samsung.knox.securefolder.presentation.switcher.SwitchActivity")),
                runCatching { pm.getLaunchIntentForPackage("com.samsung.knox.securefolder") }.getOrNull(),
                Intent(Settings.ACTION_SECURITY_SETTINGS),
                Intent(Settings.ACTION_SETTINGS)
            )
            BlockingUserCategory.DUAL_APP_CLONE -> return SecondaryProfileDetector.resolveCloneSettingsIntent(context)
            BlockingUserCategory.WORK_PROFILE -> listOf(
                Intent(Settings.ACTION_SYNC_SETTINGS),
                Intent(Settings.ACTION_SECURITY_SETTINGS),
                Intent(Settings.ACTION_SETTINGS)
            )
            BlockingUserCategory.PRIVATE_SPACE -> listOf(
                Intent(Settings.ACTION_SECURITY_SETTINGS),
                Intent(Settings.ACTION_PRIVACY_SETTINGS),
                Intent(Settings.ACTION_SETTINGS)
            )
            else -> listOf(
                Intent("android.settings.USER_SETTINGS"),
                Intent(Settings.ACTION_SYNC_SETTINGS),
                Intent(Settings.ACTION_SECURITY_SETTINGS),
                Intent(Settings.ACTION_SETTINGS)
            )
        }
        for (candidate in candidates) {
            candidate.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (candidate.resolveActivityInfo(pm, 0)?.exported == true) return candidate
        }
        return Intent(Settings.ACTION_SETTINGS).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
    }

    private fun resolveAppLabel(pm: PackageManager?, pkg: String): String {
        if (pm == null) return pkg
        val appInfo = runCatching { pm.getApplicationInfo(pkg, 0) }.getOrNull()
            ?: runCatching {
                @Suppress("DEPRECATION")
                pm.getApplicationInfo(pkg, PackageManager.MATCH_UNINSTALLED_PACKAGES)
            }.getOrNull()
        val label = appInfo?.let {
            runCatching { pm.getApplicationLabel(it).toString().trim() }.getOrNull()
        }
        return if (!label.isNullOrBlank()) label else pkg
    }
    private fun categorySortOrder(category: BlockingUserCategory): Int = when (category) {
        BlockingUserCategory.SECURE_FOLDER -> 0
        BlockingUserCategory.DUAL_APP_CLONE -> 1
        BlockingUserCategory.WORK_PROFILE -> 2
        BlockingUserCategory.PRIVATE_SPACE -> 3
        BlockingUserCategory.GUEST_USER -> 4
        BlockingUserCategory.SECONDARY_USER -> 5
    }
}
