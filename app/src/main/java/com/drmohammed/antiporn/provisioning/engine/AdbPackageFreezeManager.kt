package com.drmohammed.antiporn.provisioning.engine

import android.accounts.AccountManager
import android.content.Context
import androidx.annotation.VisibleForTesting
import com.drmohammed.antiporn.provisioning.engine.AdbShellValidation.ADMIN_COMPONENT_REGEX
import com.drmohammed.antiporn.provisioning.engine.AdbShellValidation.PACKAGE_NAME_REGEX
import com.drmohammed.antiporn.provisioning.engine.AdbShellValidation.SAFE_PATH_REGEX
import com.drmohammed.antiporn.provisioning.engine.AdbShellValidation.combinedOutput
import com.drmohammed.antiporn.telemetry.BlackBoxRecorder
import io.github.rhythmcache.adb.AdbClient
import io.github.rhythmcache.adb.ShellResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber

/**
 * Manages triple-failsafe temporary package freezing and recovery for Device Owner promotion
 * when system accounts block direct `dpm set-device-owner`:
 * 1. Persistent synchronous ledger ([ProvisioningFreezeRecoveryStore]) written prior to freezing.
 * 2. Detached `mksh` watchdog + in-shell `trap ... EXIT` / signal cleanup with unique attempt lockfile.
 * 3. `NonCancellable` `finally` verification with bounded primary & ADB reconnect healing fallback.
 */
internal object AdbPackageFreezeManager {

    private const val TAG = "AntiPorn_FreezeMgr"
    private const val PRIMARY_FINALLY_TIMEOUT_MS = 3_500L
    private const val RECONNECT_FINALLY_TIMEOUT_MS = 5_500L
    private const val WATCHDOG_LAUNCH_TIMEOUT_MS = 3_000L

    private val ACCOUNT_TYPE_REGEX = Regex("Account \\{name=.*?, type=(.*?)\\}")

    private val CRITICAL_OS_PACKAGES = setOf(
        "android", "com.android.systemui", "com.android.settings", "com.android.phone",
        "com.android.providers.settings", "com.miui.home", "com.sec.android.app.launcher",
        "com.google.android.apps.nexuslauncher", "com.android.launcher3", "com.oppo.launcher"
    )

    private fun lockPathForAttempt(userId: Int): String =
        "/data/local/tmp/antiporn_freeze_${userId}_${System.nanoTime()}.lock"

    private fun staleLockGlobForUser(userId: Int): String {
        require(userId >= 0) { "Invalid userId: $userId" }
        return "/data/local/tmp/antiporn_freeze_${userId}*.lock"
    }

    @VisibleForTesting
    internal fun filterSafePackagesForFreeze(
        packages: Collection<String>,
        alreadyDisabledPackages: Set<String> = emptySet(),
        selfPackageName: String? = null
    ): List<String> {
        val selfPkg = selfPackageName?.trim()?.takeIf { it.isNotBlank() }
        val distinct = packages.map { it.trim() }.filter { it.isNotBlank() }.distinct()
        val filtered = distinct.filter { pkg ->
            PACKAGE_NAME_REGEX.matches(pkg) && pkg !in CRITICAL_OS_PACKAGES &&
                pkg != selfPkg && pkg !in alreadyDisabledPackages
        }
        val rejectedInvalid = distinct.filter { !PACKAGE_NAME_REGEX.matches(it) }
        if (rejectedInvalid.isNotEmpty()) {
            Timber.tag(TAG).w("Filtered invalid package name(s) before shell interpolation: $rejectedInvalid")
        }
        return filtered
    }

    @VisibleForTesting
    internal fun buildWatchdogScript(lockPath: String, userId: Int, safePackages: List<String>, delaySeconds: Int): String {
        require(userId >= 0) { "Invalid userId: $userId" }
        require(SAFE_PATH_REGEX.matches(lockPath)) { "Unsafe lockPath: $lockPath" }
        require(safePackages.isNotEmpty() && safePackages.all { PACKAGE_NAME_REGEX.matches(it) }) { "Invalid watchdog packages" }
        val safeDelay = delaySeconds.coerceIn(1, 120)
        val enableCmds = safePackages.joinToString("; ") { "pm enable --user $userId $it" }
        return "( trap '' HUP; exec </dev/null >/dev/null 2>&1; sleep $safeDelay; " +
            "if [ -f \"$lockPath\" ]; then $enableCmds; rm -f \"$lockPath\"; fi ) &"
    }

    @VisibleForTesting
    internal fun buildAtomicFreezeAndDpmScript(
        lockPath: String,
        userId: Int,
        adminComponent: String,
        safePackages: List<String>
    ): String {
        require(userId >= 0) { "Invalid userId: $userId" }
        require(SAFE_PATH_REGEX.matches(lockPath)) { "Unsafe lockPath: $lockPath" }
        require(ADMIN_COMPONENT_REGEX.matches(adminComponent)) { "Unsafe adminComponent: $adminComponent" }
        require(safePackages.isNotEmpty() && safePackages.all { PACKAGE_NAME_REGEX.matches(it) }) { "Invalid freeze packages" }
        val enableCmds = safePackages.joinToString("; ") { "pm enable --user $userId $it" }
        val disableCmds = safePackages.joinToString("; ") { "pm disable-user --user $userId $it" }
        return "sh -c 'touch \"$lockPath\"; restore() { trap - EXIT HUP INT TERM; $enableCmds; rm -f \"$lockPath\"; }; " +
            "trap restore EXIT; trap \"restore; exit 129\" HUP; trap \"restore; exit 130\" INT; trap \"restore; exit 143\" TERM; " +
            "$disableCmds; sleep 1; " +
            "out=\$(dpm set-device-owner --user $userId $adminComponent 2>&1); rc=\$?; printf \"%s\\n\" \"\$out\"; " +
            "case \"\$out\" in *\"unknown option: --user\"*|*\"Unknown option: --user\"*|*\"illegal option\"*|*\"Illegal option\"*) " +
            "out2=\$(dpm set-device-owner $adminComponent 2>&1); rc=\$?; printf \"%s\\n\" \"\$out2\";; esac; exit \$rc'"
    }

    suspend fun executePackageFreezePromotion(
        context: Context?,
        client: AdbClient,
        adminComponent: String,
        userId: Int,
        reconnectProvider: (suspend () -> AdbClient?)? = null
    ): PromotionResult {
        val appContext = context?.applicationContext ?: context
        val authenticatorMap = appContext?.let { ctx ->
            try {
                AccountManager.get(ctx)?.authenticatorTypes.orEmpty()
                    .mapNotNull { info ->
                        val type = info?.type?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                        val pkg = info.packageName?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                        type to pkg
                    }.toMap()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Timber.tag(TAG).w(e, "Failed to retrieve authenticatorTypes from AccountManager")
                emptyMap()
            }
        } ?: emptyMap()

        val reconnectAndHeal: (suspend (List<String>, Int) -> Set<String>)? = reconnectProvider?.let { provider ->
            { packages, targetUserId ->
                val freshClient = provider()
                if (freshClient == null) packages.toSet()
                else freshClient.use { fresh ->
                    healAndVerifyPackages(packages, targetUserId, lockPath = null) { cmd -> fresh.shell(cmd) }
                }
            }
        }
        return executePackageFreezeWithShell(
            appContext, userId, adminComponent, authenticatorMap, { cmd -> client.shell(cmd) }, reconnectAndHeal
        )
    }

    @VisibleForTesting
    internal suspend fun executePackageFreezeWithShell(
        context: Context?,
        userId: Int,
        adminComponent: String,
        accountAuthenticatorMap: Map<String, String>,
        shellExecutor: suspend (String) -> ShellResult,
        reconnectAndHeal: (suspend (List<String>, Int) -> Set<String>)? = null
    ): PromotionResult {
        require(userId >= 0) { "Invalid userId: $userId" }
        require(ADMIN_COMPONENT_REGEX.matches(adminComponent)) { "Unsafe adminComponent: $adminComponent" }

        val appContext = context?.applicationContext ?: context
        val candidates = collectAccountPackagesToFreeze(shellExecutor, accountAuthenticatorMap)
        val alreadyDisabled = try {
            queryDisabledPackages(userId, shellExecutor)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Timber.tag(TAG).w(e, "Failed querying pre-existing disabled packages")
            emptySet()
        }

        val safePackages = filterSafePackagesForFreeze(candidates, alreadyDisabled, appContext?.packageName)
        if (safePackages.isEmpty()) {
            val output = AdbPromotionExecutor.runDpmSetDeviceOwnerWithUserFallback(userId, adminComponent, shellExecutor)
            Timber.tag(TAG).i("DPM direct execution (no freeze targets) output: $output")
            return mapDpmOutputToPromotionResult(appContext, output)
        }

        val priorLedgerPackages = appContext?.let { ctx ->
            ProvisioningFreezeRecoveryStore.getPendingLedger(ctx)
                ?.takeIf { it.userId == userId }
                ?.packages
                ?.filter { it !in safePackages }
                .orEmpty()
        }.orEmpty()

        val lockPath = lockPathForAttempt(userId)
        appContext?.let { ProvisioningFreezeRecoveryStore.recordPendingFreeze(it, userId, priorLedgerPackages + safePackages) }

        try {
            val delaySeconds = (safePackages.size * 2 + 12).coerceIn(18, 35)
            val watchdogScript = buildWatchdogScript(lockPath, userId, safePackages, delaySeconds)
            try {
                withTimeoutOrNull(WATCHDOG_LAUNCH_TIMEOUT_MS) { shellExecutor(watchdogScript) }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Timber.tag(TAG).w(e, "Watchdog launch returned non-fatal error; continuing with trap + finally")
            }

            val atomicScript = buildAtomicFreezeAndDpmScript(lockPath, userId, adminComponent, safePackages)
            val output = shellExecutor(atomicScript).combinedOutput()
            Timber.tag(TAG).i("DPM atomic freeze execution output: $output")
            return mapDpmOutputToPromotionResult(appContext, output)
        } finally {
            verifyAndRecoverInFinally(
                appContext, userId, lockPath, safePackages, priorLedgerPackages, shellExecutor, reconnectAndHeal
            )
        }
    }

    private fun mapDpmOutputToPromotionResult(appContext: Context?, output: String): PromotionResult {
        return when (val outcome = AdbPromotionExecutor.classifySetupBypassOutput(
            appContext, output, OemProvisioningHelper.isXiaomi(), OemProvisioningHelper.isRealmeOrOppo()
        )) {
            is SetupBypassOutcome.Success -> PromotionResult.Success
            is SetupBypassOutcome.OemPermissionRequired -> PromotionResult.OemPermissionRequired(outcome.oemType, outcome.message)
            is SetupBypassOutcome.HardStopFailure -> PromotionResult.Failure(outcome.message)
            is SetupBypassOutcome.SecondaryUsersError,
            is SetupBypassOutcome.AccountsBlockedFailure ->
                PromotionResult.Failure(AdbPromotionExecutor.formatFriendlyError(appContext, output))
        }
    }

    /**
     * Re-enables any packages recorded in [ProvisioningFreezeRecoveryStore] via ADB and verifies
     * their enabled state, updating or clearing the persistent ledger accordingly.
     */
    suspend fun healStrandedPackagesViaAdb(context: Context, client: AdbClient): Int =
        healStrandedPackagesWithShell(context) { cmd -> client.shell(cmd) }

    internal suspend fun healStrandedPackagesWithShell(
        context: Context,
        shellExecutor: suspend (String) -> ShellResult
    ): Int {
        val appContext = context.applicationContext ?: context
        val ledger = ProvisioningFreezeRecoveryStore.getPendingLedger(appContext) ?: return 0
        if (ledger.userId < 0) {
            ProvisioningFreezeRecoveryStore.clearLedger(appContext)
            AdbShellValidation.cancelFreezeRecoveryNotification(appContext)
            return 0
        }
        val validPackages = ledger.packages.filter { PACKAGE_NAME_REGEX.matches(it) }.distinct()
        if (validPackages.isEmpty()) {
            ProvisioningFreezeRecoveryStore.clearLedger(appContext)
            AdbShellValidation.cancelFreezeRecoveryNotification(appContext)
            return 0
        }

        for (pkg in validPackages) {
            try {
                shellExecutor("pm enable --user ${ledger.userId} $pkg")
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Timber.tag(TAG).w(e, "Failed running pm enable for stranded package $pkg")
            }
        }

        return try {
            val disabledAfter = queryDisabledPackages(ledger.userId, shellExecutor)
            val remaining = validPackages.filter { it in disabledAfter }
            val healedCount = validPackages.size - remaining.size
            if (remaining.isEmpty()) {
                ProvisioningFreezeRecoveryStore.clearLedger(appContext)
                AdbShellValidation.cancelFreezeRecoveryNotification(appContext)
                cleanupUserLockfiles(ledger.userId, shellExecutor)
            } else {
                ProvisioningFreezeRecoveryStore.recordPendingFreeze(appContext, ledger.userId, remaining)
            }
            BlackBoxRecorder.recordPairing(
                appContext, "ADB_FREEZE_LEDGER_HEALED", isSuccess = remaining.isEmpty(),
                details = "userId=${ledger.userId}, healed=$healedCount, remaining=${remaining.size}",
                error = remaining.takeIf { it.isNotEmpty() }?.joinToString(",")
            )
            healedCount
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Timber.tag(TAG).w(e, "Failed verifying stranded package recovery")
            BlackBoxRecorder.recordPairing(
                appContext, "ADB_FREEZE_LEDGER_HEALED", isSuccess = false,
                error = "${e.javaClass.simpleName}: ${e.message}"
            )
            0
        }
    }

    private suspend fun verifyAndRecoverInFinally(
        appContext: Context?,
        userId: Int,
        lockPath: String,
        safePackages: List<String>,
        priorLedgerPackages: List<String>,
        shellExecutor: suspend (String) -> ShellResult,
        reconnectAndHeal: (suspend (List<String>, Int) -> Set<String>)?
    ) {
        withContext(NonCancellable) {
            val primaryRemaining = withTimeoutOrNull(PRIMARY_FINALLY_TIMEOUT_MS) {
                try {
                    healAndVerifyPackages(safePackages, userId, lockPath, shellExecutor)
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    Timber.tag(TAG).w(e, "Primary ADB finally recovery failed; attempting reconnect heal")
                    null
                }
            }
            val remainingDisabled = primaryRemaining ?: withTimeoutOrNull(RECONNECT_FINALLY_TIMEOUT_MS) {
                if (reconnectAndHeal == null) null
                else try {
                    reconnectAndHeal(safePackages, userId)
                } catch (re: Exception) {
                    if (re is CancellationException) throw re
                    Timber.tag(TAG).w(re, "Reconnect heal failed")
                    null
                }
            }
            val finalRemaining = remainingDisabled ?: safePackages.toSet()
            if (appContext != null) {
                val combinedRemaining = (priorLedgerPackages + finalRemaining).distinct()
                if (combinedRemaining.isEmpty()) {
                    ProvisioningFreezeRecoveryStore.clearLedger(appContext)
                    AdbShellValidation.cancelFreezeRecoveryNotification(appContext)
                    BlackBoxRecorder.recordPairing(
                        appContext, "ADB_FREEZE_RESTORE_VERIFIED", isSuccess = true,
                        details = "userId=$userId, count=${safePackages.size}"
                    )
                } else {
                    ProvisioningFreezeRecoveryStore.recordPendingFreeze(appContext, userId, combinedRemaining)
                    BlackBoxRecorder.recordPairing(
                        appContext, "ADB_FREEZE_RESTORE_INCOMPLETE", isSuccess = false,
                        error = "userId=$userId, stillDisabled=${combinedRemaining.joinToString(",")}"
                    )
                }
            }
        }
    }

    private suspend fun healAndVerifyPackages(
        safePackages: List<String>,
        userId: Int,
        lockPath: String?,
        shellExecutor: suspend (String) -> ShellResult
    ): Set<String> {
        val disabledBefore = queryDisabledPackages(userId, shellExecutor)
        val needEnable = safePackages.filter { it in disabledBefore }
        for (pkg in needEnable) {
            try {
                shellExecutor("pm enable --user $userId $pkg")
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Timber.tag(TAG).w(e, "pm enable failed for $pkg during finally recovery")
            }
        }
        val stillDisabled = if (needEnable.isEmpty()) emptySet()
        else safePackages.toSet().intersect(queryDisabledPackages(userId, shellExecutor))
        if (stillDisabled.isEmpty()) {
            if (lockPath != null) {
                try {
                    shellExecutor("rm -f \"$lockPath\"")
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    Timber.tag(TAG).d(e, "Lockfile cleanup skipped")
                }
            } else {
                cleanupUserLockfiles(userId, shellExecutor)
            }
        }
        return stillDisabled
    }

    private suspend fun cleanupUserLockfiles(userId: Int, shellExecutor: suspend (String) -> ShellResult) {
        try {
            shellExecutor("rm -f ${staleLockGlobForUser(userId)}")
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Timber.tag(TAG).d(e, "Stale lockfile cleanup skipped")
        }
    }

    private suspend fun queryDisabledPackages(userId: Int, shellExecutor: suspend (String) -> ShellResult): Set<String> {
        val res = shellExecutor("pm list packages -d --user $userId")
        val combined = res.combinedOutput()
        if (!res.isSuccess || combined.contains("Exception", ignoreCase = true) || combined.contains("Permission Denial", ignoreCase = true)) {
            throw IllegalStateException("pm list packages -d failed (exit=${res.exitCode}): $combined")
        }
        return res.stdoutText.lineSequence()
            .map { it.trim() }
            .filter { it.startsWith("package:") }
            .map { it.removePrefix("package:").trim() }
            .filter { PACKAGE_NAME_REGEX.matches(it) }
            .toSet()
    }

    private suspend fun collectAccountPackagesToFreeze(
        shellExecutor: suspend (String) -> ShellResult,
        authenticatorMap: Map<String, String>
    ): Set<String> {
        val dumpsys = try {
            shellExecutor("dumpsys account").stdoutText
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Timber.tag(TAG).w(e, "Failed dumping accounts before freeze")
            ""
        }
        val accountTypes = dumpsys.lines().mapNotNull { ACCOUNT_TYPE_REGEX.find(it)?.groupValues?.get(1) }.toSet()
        val packagesToDisable = mutableSetOf("com.google.android.gms")
        accountTypes.forEach { type ->
            val mappedPkg = authenticatorMap[type]
            if (mappedPkg != null) packagesToDisable.add(mappedPkg) else when {
                type.contains("google", ignoreCase = true) -> packagesToDisable.add("com.google.android.gms")
                type.contains("whatsapp", ignoreCase = true) -> packagesToDisable.add("com.whatsapp")
                type.contains("telegram", ignoreCase = true) -> packagesToDisable.add("org.telegram.messenger")
                type.contains("samsung", ignoreCase = true) -> packagesToDisable.add("com.osp.app.signin")
                type.contains("xiaomi", ignoreCase = true) -> packagesToDisable.add("com.xiaomi.account")
            }
        }
        return packagesToDisable
    }
}
