package com.drmohammed.antiporn.provisioning.engine

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Process
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
    private val CLONE_USER_IDS = setOf(95, 96, 999)
    private val HARD_BLOCK_MARKERS = listOf("SecurityException", "Permission Denial", "does not have permission")
    private val BLOCKED_PKG_ERROR_MARKERS = listOf("Exception", "Error:", "Failure", "is locked", "user locked")
    private val REMOVE_USER_ERROR_MARKERS = listOf(
        "Error", "Exception", "Unknown command", "Failed", "Failure", "Couldn't", "Cannot", "Permission Denial"
    )

    private fun ShellResult.combinedOutput(): String = (stdoutText + "\n" + stderrText).trim()
    private fun String.containsAny(vararg needles: String): Boolean = needles.any { contains(it) }

    @VisibleForTesting
    internal fun parsePmListUsers(output: String, currentUserId: Int = 0): List<ParsedAdbUser> {
        if (output.isBlank()) return emptyList()
        val seenIds = mutableSetOf<Int>()
        val results = mutableListOf<ParsedAdbUser>()
        for (match in USER_INFO_REGEX.findAll(output)) {
            val userId = match.groupValues[1].toIntOrNull() ?: continue
            if (userId <= 0 || userId == currentUserId || !seenIds.add(userId)) continue
            results.add(ParsedAdbUser(userId, match.groupValues[2].trim(), match.groupValues[3].trim()))
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
            lower.containsAny("secure folder", "knox", "المجلد الآمن", "المجلد الامن") ||
                (isSamsung && userId in 150..160) -> BlockingUserCategory.SECURE_FOLDER

            localProfileType == SecondaryProfileDetector.ProfileType.CLONE_APP ||
                userId in CLONE_USER_IDS ||
                lower.containsAny("dual", "clone", "multiapp", "xspace", "parallel", "twin", "مزدوج", "مستنسخ") ->
                BlockingUserCategory.DUAL_APP_CLONE

            localProfileType == SecondaryProfileDetector.ProfileType.PRIVATE_SPACE ||
                lower.containsAny("private", "second space", "الخاصة", "الواجهة الثانية") ->
                BlockingUserCategory.PRIVATE_SPACE

            ((flagsInt and 0x4L) != 0L) || lower.containsAny("guest", "ضيف") -> BlockingUserCategory.GUEST_USER

            localProfileType == SecondaryProfileDetector.ProfileType.WORK_PROFILE ||
                ((flagsInt and 0x20L) != 0L) ||
                lower.containsAny("work", "managed", "island", "shelter", "العمل") ->
                BlockingUserCategory.WORK_PROFILE

            else -> BlockingUserCategory.SECONDARY_USER
        }
    }

    @VisibleForTesting
    internal fun parseThirdPartyPackagesOutput(output: String): Pair<List<String>, Boolean> {
        if (HARD_BLOCK_MARKERS.any { output.contains(it, ignoreCase = true) }) {
            return emptyList<String>() to true
        }
        val packages = output.lineSequence()
            .map { it.trim() }
            .filter { it.startsWith("package:", ignoreCase = false) }
            .map { it.removePrefix("package:").trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .toList()
        if (packages.isNotEmpty()) return packages to false
        return emptyList<String>() to BLOCKED_PKG_ERROR_MARKERS.any { output.contains(it, ignoreCase = true) }
    }

    @VisibleForTesting
    internal fun hasRemoveUserError(res: ShellResult): Boolean {
        val output = res.combinedOutput()
        return !res.isSuccess || REMOVE_USER_ERROR_MARKERS.any { output.contains(it, ignoreCase = true) }
    }

    private fun resolveLocalProcessUserId(): Int? = try {
        SecondaryProfileDetector.extractUserId(Process.myUserHandle())
    } catch (_: Throwable) {
        null
    }

    private suspend fun resolveCurrentUserIdOrThrow(shellExecutor: suspend (String) -> ShellResult): Int {
        val res = shellExecutor("am get-current-user")
        val parsed = res.stdoutText.trim().toIntOrNull()
        if (!res.isSuccess || parsed == null || parsed < 0) {
            throw IllegalStateException("Failed resolving active user via ADB (exit=${res.exitCode}): ${res.combinedOutput()}")
        }
        return parsed
    }

    private suspend fun listUsersOrThrow(shellExecutor: suspend (String) -> ShellResult): String {
        val usersRes = shellExecutor("pm list users")
        val rawOutput = usersRes.combinedOutput()
        if (!usersRes.isSuccess || !rawOutput.contains("UserInfo{")) {
            throw IllegalStateException("pm list users failed (exit=${usersRes.exitCode}): $rawOutput")
        }
        return rawOutput
    }

    suspend fun inspectBlockingUsers(context: Context, client: AdbClient): List<BlockingSecondaryUser> =
        inspectBlockingUsersWithShell(context) { cmd -> client.shell(cmd) }

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
            if (e is CancellationException) throw e
            Timber.tag(TAG).w(e, "Failed reading local secondary profiles from UserManager")
            emptyMap()
        }

        val pm: PackageManager? = appContext.packageManager
        val isSamsung = OemProvisioningHelper.isSamsung()
        return parsedUsers.map { parsed ->
            inspectSingleUser(appContext, pm, isSamsung, parsed, localProfilesById[parsed.userId], shellExecutor)
        }.sortedWith(compareBy<BlockingSecondaryUser> { categorySortOrder(it.category) }.thenBy { it.userId })
    }

    private suspend fun inspectSingleUser(
        appContext: Context,
        pm: PackageManager?,
        isSamsung: Boolean,
        parsed: ParsedAdbUser,
        localProfile: SecondaryProfileDetector.UnaffiliatedProfileInfo?,
        shellExecutor: suspend (String) -> ShellResult
    ): BlockingSecondaryUser {
        val category = classifyAdbUser(parsed.userId, parsed.rawName, parsed.flagsHex, isSamsung, localProfile?.profileType)
        var pkgShellFailed = false
        val pkgRes = try {
            shellExecutor("pm list packages -3 --user ${parsed.userId}")
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            pkgShellFailed = true
            Timber.tag(TAG).w(e, "Failed listing packages for user ${parsed.userId}")
            null
        }

        val (rawPackages, initialBlocked) = parseThirdPartyPackagesOutput(pkgRes?.combinedOutput().orEmpty())
        val adbPackages = rawPackages.filter { it != appContext.packageName }
        val resolvedLabels = adbPackages.map { resolveAppLabel(pm, it) }
        val localPackages = (localProfile?.clonedPackageNames ?: emptyList()).filter { it != appContext.packageName }
        val mergedPackages = (adbPackages + localPackages).distinct()
        val mergedAppNames = (resolvedLabels + localProfile?.clonedAppNames.orEmpty())
            .map { it.trim() }.filter { it.isNotBlank() }.distinct()

        val isAppsInspectionBlocked = pkgShellFailed ||
            (pkgRes != null && !pkgRes.isSuccess) ||
            initialBlocked ||
            (category == BlockingUserCategory.SECURE_FOLDER && mergedAppNames.isEmpty())

        return BlockingSecondaryUser(
            parsed.userId, parsed.rawName, parsed.flagsHex, category,
            mergedAppNames, mergedPackages, isAppsInspectionBlocked
        )
    }

    suspend fun removeApprovedUsers(
        context: Context,
        client: AdbClient,
        approvedUserIds: Set<Int>
    ): SecondaryUserRemovalOutcome = removeApprovedUsersWithShell(context, approvedUserIds) { cmd -> client.shell(cmd) }

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
            return SecondaryUserRemovalOutcome(
                emptyList(),
                approvedUserIds.toList(),
                inspectBlockingUsersWithShell(appContext, shellExecutor),
                approvedUserIds.associateWith { "Refused invalid or active userId=$it" }
            )
        }

        val initialUsersOutput = listUsersOrThrow(shellExecutor)
        val liveTargets = parsePmListUsers(initialUsersOutput, currentUserId).map { it.userId }.filter { it in safeApproved }
        if (liveTargets.isEmpty()) {
            return SecondaryUserRemovalOutcome(emptyList(), emptyList(), inspectBlockingUsersWithShell(appContext, shellExecutor), emptyMap())
        }

        val failureMessages = mutableMapOf<Int, String>()
        for (targetId in liveTargets) {
            val res = shellExecutor("pm remove-user $targetId")
            val output = res.combinedOutput()
            val hasError = hasRemoveUserError(res)
            if (hasError) failureMessages[targetId] = output.ifBlank { "pm remove-user failed (exit=${res.exitCode})" }
            BlackBoxRecorder.recordPairing(
                appContext, "ADB_REMOVE_SECONDARY_USER", isSuccess = !hasError,
                details = "userId=$targetId, exit=${res.exitCode}, output=${output.take(150)}"
            )
        }

        val immediateFailedIds = failureMessages.keys.toSet()
        val pendingPollIds = liveTargets.filter { it !in immediateFailedIds }
        val stillPresentPendingIds = if (pendingPollIds.isNotEmpty()) {
            pollUntilPendingUsersRemoved(pendingPollIds, currentUserId, shellExecutor)
        } else {
            emptySet()
        }

        val removedIds = pendingPollIds.filter { it !in stillPresentPendingIds }
        val failedIds = liveTargets.filter { it in immediateFailedIds || it in stillPresentPendingIds }
        failedIds.forEach { failureMessages.putIfAbsent(it, "User $it still present after pm remove-user") }

        return SecondaryUserRemovalOutcome(removedIds, failedIds, inspectBlockingUsersWithShell(appContext, shellExecutor), failureMessages)
    }

    private suspend fun pollUntilPendingUsersRemoved(
        pendingPollIds: List<Int>,
        currentUserId: Int,
        shellExecutor: suspend (String) -> ShellResult
    ): Set<Int> {
        var stillPresentPendingIds = pendingPollIds.toSet()
        repeat(REMOVAL_POLL_ATTEMPTS) { attempt ->
            val pollRes = try {
                shellExecutor("pm list users")
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Timber.tag(TAG).w(e, "Polling pm list users failed on attempt $attempt")
                null
            }
            val pollOutput = pollRes?.combinedOutput().orEmpty()
            if (pollRes != null && pollRes.isSuccess && pollOutput.contains("UserInfo{")) {
                val currentLiveIds = parsePmListUsers(pollOutput, currentUserId).map { it.userId }.toSet()
                stillPresentPendingIds = pendingPollIds.filter { it in currentLiveIds }.toSet()
                if (stillPresentPendingIds.isEmpty()) return emptySet()
            }
            if (attempt < REMOVAL_POLL_ATTEMPTS - 1) delay(REMOVAL_POLL_INTERVAL_MS)
        }
        return stillPresentPendingIds
    }

    fun resolveSettingsIntentForUsers(context: Context, users: List<BlockingSecondaryUser>): Intent =
        BlockingUserSettingsResolver.resolve(context, users)

    private fun resolveAppLabel(pm: PackageManager?, pkg: String): String {
        if (pm == null) return pkg
        val appInfo = runCatching { pm.getApplicationInfo(pkg, 0) }.getOrNull()
            ?: runCatching {
                @Suppress("DEPRECATION")
                pm.getApplicationInfo(pkg, PackageManager.MATCH_UNINSTALLED_PACKAGES)
            }.getOrNull()
        val label = appInfo?.let { runCatching { pm.getApplicationLabel(it).toString().trim() }.getOrNull() }
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
