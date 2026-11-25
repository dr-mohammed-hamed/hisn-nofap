package com.drmohammed.antiporn.provisioning.engine

import android.content.Context
import android.os.Build
import android.provider.Settings
import androidx.annotation.VisibleForTesting
import com.drmohammed.antiporn.R
import com.drmohammed.antiporn.accessibility.control.AccessibilitySelfActivator
import com.drmohammed.antiporn.core.model.DevicePolicyBundle
import com.drmohammed.antiporn.provisioning.engine.AdbShellValidation.ADMIN_COMPONENT_REGEX
import com.drmohammed.antiporn.provisioning.engine.AdbShellValidation.combinedOutput
import com.drmohammed.antiporn.provisioning.engine.AdbShellValidation.isDpmUserOptionUnsupported
import com.drmohammed.antiporn.shields.DnsShield
import com.drmohammed.antiporn.telemetry.BlackBoxRecorder
import com.drmohammed.antiporn.telemetry.SafeDpmGateway
import io.github.rhythmcache.adb.AdbClient
import io.github.rhythmcache.adb.ShellResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber

internal sealed class SetupBypassOutcome {
    data object Success : SetupBypassOutcome()
    data class OemPermissionRequired(val oemType: OemPermissionType, val message: String) : SetupBypassOutcome()
    data class SecondaryUsersError(val rawOutput: String) : SetupBypassOutcome()
    data class AccountsBlockedFailure(val rawOutput: String) : SetupBypassOutcome()
    data class HardStopFailure(val message: String) : SetupBypassOutcome()
}

/**
 * Executes low-level ADB shell commands for zero-account Device Owner promotion,
 * Private DNS pre-provisioning, and OEM fail-fast permission diagnostics.
 */
internal object AdbPromotionExecutor {

    private const val TAG = "AntiPorn_PromoterExec"
    private fun sanitizeShellOutput(raw: String): String = raw.replace(Regex("\\s+"), " ").trim().take(300)

    @VisibleForTesting
    internal fun filterSafePackagesForFreeze(packages: Collection<String>): List<String> =
        AdbPackageFreezeManager.filterSafePackagesForFreeze(packages)

    internal fun isDpmOwnerSetSuccess(output: String): Boolean =
        (output.contains("Success", ignoreCase = true) || output.contains("device owner set", ignoreCase = true)) &&
            !output.contains("error", ignoreCase = true) && !output.contains("exception", ignoreCase = true)

    private fun isPermissionDenialOutput(lower: String): Boolean =
        lower.contains("securityexception") || lower.contains("permission denial") ||
            lower.contains("usb debugging (security settings)") || lower.contains("permission monitoring")

    private fun isAccountsBlockedOutput(lower: String): Boolean =
        lower.contains("already some accounts") || lower.contains("accounts on the device") ||
            lower.contains("has accounts") || lower.contains("account(s) on the device") ||
            lower.contains("existing accounts")

    private fun buildOemPermissionOutcome(context: Context?, rawOutput: String, isRealmeOrOppo: Boolean): SetupBypassOutcome.OemPermissionRequired {
        val oemType = if (isRealmeOrOppo) OemPermissionType.REALME_OPPO_PERMISSION_MONITORING else OemPermissionType.XIAOMI_SECURITY_SETTINGS
        val msg = context.safeGetString(
            if (isRealmeOrOppo) R.string.provisioning_error_realme_permission_monitoring else R.string.provisioning_error_xiaomi_security_settings
        ) ?: rawOutput
        return SetupBypassOutcome.OemPermissionRequired(oemType, msg)
    }

    private fun buildOemPermissionRequired(context: Context, isRealmeOrOppo: Boolean): PromotionResult.OemPermissionRequired {
        val outcome = buildOemPermissionOutcome(context, "", isRealmeOrOppo)
        return PromotionResult.OemPermissionRequired(outcome.oemType, outcome.message)
    }

    @VisibleForTesting
    internal fun classifySetupBypassOutput(context: Context?, output: String, isXiaomi: Boolean, isRealmeOrOppo: Boolean): SetupBypassOutcome {
        if (isDpmOwnerSetSuccess(output)) return SetupBypassOutcome.Success
        val lower = output.lowercase()
        return when {
            lower.contains("already several users") || lower.contains("user 150") || lower.contains("user 95") ->
                SetupBypassOutcome.SecondaryUsersError(output)
            isPermissionDenialOutput(lower) -> when {
                isRealmeOrOppo -> buildOemPermissionOutcome(context, output, true)
                isXiaomi -> buildOemPermissionOutcome(context, output, false)
                else -> SetupBypassOutcome.HardStopFailure(formatFriendlyError(context, output))
            }
            isAccountsBlockedOutput(lower) ->
                SetupBypassOutcome.AccountsBlockedFailure(output)
            !lower.contains("already provisioned") && lower.contains("already") && lower.contains("owner") ->
                SetupBypassOutcome.HardStopFailure(context.safeGetString(R.string.provisioning_error_already_device_owner) ?: output)
            lower.contains("precondition 99") ->
                SetupBypassOutcome.HardStopFailure(context.safeGetString(R.string.provisioning_error_realme_precondition) ?: output)
            else -> SetupBypassOutcome.HardStopFailure(formatFriendlyError(context, output))
        }
    }

    suspend fun executeZeroAccountPromotion(
        context: Context,
        client: AdbClient,
        adminComponent: String,
        userId: Int = 0,
        reconnectProvider: (suspend () -> AdbClient?)? = null
    ): PromotionResult = executeZeroAccountPromotionWithShell(
        context = context,
        adminComponent = adminComponent,
        userId = userId,
        shellExecutor = { cmd -> client.shell(cmd) },
        reconnectProvider = reconnectProvider,
        postPromotionVerifier = { appCtx, uid, didBypass ->
            AdbPostPromotionVerifier.verifyAndFinalize(appCtx, client, uid, didBypass)
        },
        packageFreezeRunner = { appCtx, comp, uid ->
            AdbPackageFreezeManager.executePackageFreezePromotion(appCtx, client, comp, uid, reconnectProvider)
        }
    )

    @VisibleForTesting
    internal suspend fun executeZeroAccountPromotionWithShell(
        context: Context,
        adminComponent: String,
        userId: Int = 0,
        shellExecutor: suspend (String) -> ShellResult,
        reconnectProvider: (suspend () -> AdbClient?)? = null,
        isDeviceOwnerChecker: (Context) -> Boolean = { SafeDpmGateway.isDeviceOwner(it) },
        canWriteSecureSettingsChecker: (Context) -> Boolean = { AccessibilitySelfActivator.canWriteSecureSettings(it) },
        blockingUsersInspector: suspend (Context) -> List<BlockingSecondaryUser> = { appCtx ->
            AdbSecondaryUserInspector.inspectBlockingUsersWithShell(appCtx, shellExecutor)
        },
        sdkVersionProvider: suspend () -> Int = {
            shellExecutor("getprop ro.build.version.sdk").stdoutText.trim().toIntOrNull() ?: Build.VERSION.SDK_INT
        },
        isXiaomi: Boolean = OemProvisioningHelper.isXiaomi(),
        isRealmeOrOppo: Boolean = OemProvisioningHelper.isRealmeOrOppo(),
        postPromotionVerifier: suspend (Context, Int, Boolean) -> PromotionResult,
        packageFreezeRunner: suspend (Context, String, Int) -> PromotionResult
    ): PromotionResult {
        require(userId >= 0) { "Invalid userId: $userId" }
        require(ADMIN_COMPONENT_REGEX.matches(adminComponent)) { "Unsafe adminComponent: $adminComponent" }

        val appContext = context.applicationContext ?: context
        AdbPackageFreezeManager.healStrandedPackagesWithShell(appContext, shellExecutor)
        if (ProvisioningFreezeRecoveryStore.getPendingLedger(appContext) != null) {
            return PromotionResult.Failure(
                appContext.safeGetString(R.string.provisioning_error_heal_failed)
                    ?: "Could not re-enable stranded apps via Wireless ADB."
            )
        }
        if (isDeviceOwnerChecker(appContext)) {
            return postPromotionVerifier(appContext, userId, true)
        }
        val blockingUsers = blockingUsersInspector(appContext)
        if (blockingUsers.isNotEmpty()) {
            Timber.tag(TAG).w("Found ${blockingUsers.size} blocking secondary user(s); pausing promotion")
            return PromotionResult.SecondaryUsersBlocking(blockingUsers)
        }
        grantPrivilegedAppOpsAndPermissions(
            appContext, shellExecutor, canWriteSecureSettingsChecker, isXiaomi, isRealmeOrOppo
        )?.let { return it }
        provisionPrivateDnsViaAdbWithShell(appContext, shellExecutor)
        tryFastPathZeroAccountPromotion(
            appContext, shellExecutor, adminComponent, userId, isXiaomi, isRealmeOrOppo, blockingUsersInspector, postPromotionVerifier
        )?.let { return it }

        val sdkVersion = sdkVersionProvider()
        Timber.tag(TAG).i("Executing zero-account promotion on Android SDK $sdkVersion for user $userId")
        if (sdkVersion >= 31) {
            return when (val outcome = executeSetupBypassPromotion(
                appContext, shellExecutor, adminComponent, userId, isXiaomi, isRealmeOrOppo
            )) {
                is SetupBypassOutcome.Success ->
                    postPromotionVerifier(appContext, userId, true)
                is SetupBypassOutcome.OemPermissionRequired ->
                    PromotionResult.OemPermissionRequired(outcome.oemType, outcome.message)
                is SetupBypassOutcome.SecondaryUsersError ->
                    recheckSecondaryUsersOrFail(appContext, blockingUsersInspector, outcome.rawOutput)
                is SetupBypassOutcome.HardStopFailure ->
                    PromotionResult.Failure(outcome.message)
                is SetupBypassOutcome.AccountsBlockedFailure -> {
                    Timber.tag(TAG).w("Setup bypass blocked by accounts; invoking triple-failsafe package freeze")
                    runPackageFreezeAndVerify(appContext, adminComponent, userId, packageFreezeRunner, postPromotionVerifier)
                }
            }
        }
        return runPackageFreezeAndVerify(appContext, adminComponent, userId, packageFreezeRunner, postPromotionVerifier)
    }

    private suspend fun runPackageFreezeAndVerify(
        appContext: Context,
        adminComponent: String,
        userId: Int,
        packageFreezeRunner: suspend (Context, String, Int) -> PromotionResult,
        postPromotionVerifier: suspend (Context, Int, Boolean) -> PromotionResult
    ): PromotionResult {
        val res = packageFreezeRunner(appContext, adminComponent, userId)
        return if (res is PromotionResult.Success) {
            postPromotionVerifier(appContext, userId, false)
        } else res
    }

    private suspend fun tryFastPathZeroAccountPromotion(
        appContext: Context,
        shellExecutor: suspend (String) -> ShellResult,
        adminComponent: String,
        userId: Int,
        isXiaomi: Boolean,
        isRealmeOrOppo: Boolean,
        blockingUsersInspector: suspend (Context) -> List<BlockingSecondaryUser>,
        postPromotionVerifier: suspend (Context, Int, Boolean) -> PromotionResult
    ): PromotionResult? {
        val dumpsysRes = try {
            shellExecutor("dumpsys account")
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Timber.tag(TAG).w(e, "dumpsys account failed during fast-path check")
            return null
        }
        val combinedDumpsys = dumpsysRes.combinedOutput()
        if (!dumpsysRes.isSuccess || hasShellError(combinedDumpsys) || dumpsysRes.stdoutText.contains("Account {")) {
            return null
        }

        val output = runDpmSetDeviceOwnerWithUserFallback(userId, adminComponent, shellExecutor)
        BlackBoxRecorder.recordPairing(appContext, "FAST_PATH_ZERO_ACCOUNT_DPM", isSuccess = isDpmOwnerSetSuccess(output), details = sanitizeShellOutput(output))
        return when (val outcome = classifySetupBypassOutput(appContext, output, isXiaomi, isRealmeOrOppo)) {
            is SetupBypassOutcome.Success -> postPromotionVerifier(appContext, userId, false)
            is SetupBypassOutcome.OemPermissionRequired -> PromotionResult.OemPermissionRequired(outcome.oemType, outcome.message)
            is SetupBypassOutcome.SecondaryUsersError -> recheckSecondaryUsersOrFail(appContext, blockingUsersInspector, outcome.rawOutput)
            is SetupBypassOutcome.AccountsBlockedFailure -> null
            is SetupBypassOutcome.HardStopFailure -> PromotionResult.Failure(outcome.message)
        }
    }

    private suspend fun recheckSecondaryUsersOrFail(
        appContext: Context,
        blockingUsersInspector: suspend (Context) -> List<BlockingSecondaryUser>,
        rawOutput: String
    ): PromotionResult {
        val rechecked = blockingUsersInspector(appContext)
        return if (rechecked.isNotEmpty()) PromotionResult.SecondaryUsersBlocking(rechecked)
        else PromotionResult.Failure(formatFriendlyError(appContext, rawOutput))
    }

    private fun recordSecureSettingsGrantFailure(appContext: Context, rawDetail: String, isRealmeOrOppo: Boolean, cause: Throwable? = null) {
        val oemHint = if (isRealmeOrOppo) "Disable permission monitoring required on OPPO/Realme" else null
        val details = listOfNotNull(oemHint, rawDetail.takeIf { it.isNotBlank() }).joinToString(" | ")
        if (cause != null) Timber.tag(TAG).w(cause, "Failed granting WRITE_SECURE_SETTINGS via ADB: $details")
        else Timber.tag(TAG).w("WRITE_SECURE_SETTINGS grant via ADB did not take effect: $details")
        BlackBoxRecorder.recordPairing(appContext, "ADB_WRITE_SECURE_SETTINGS_GRANT", isSuccess = false, error = details)
    }

    private suspend fun grantPrivilegedAppOpsAndPermissions(
        appContext: Context,
        shellExecutor: suspend (String) -> ShellResult,
        canWriteSecureSettingsChecker: (Context) -> Boolean,
        isXiaomi: Boolean,
        isRealmeOrOppo: Boolean
    ): PromotionResult.OemPermissionRequired? {
        if (isXiaomi) {
            runShellStep("Read Xiaomi persist.security.adbinput") {
                val prop = shellExecutor("getprop persist.security.adbinput").stdoutText.trim()
                BlackBoxRecorder.recordPairing(appContext, "XIAOMI_ADB_INPUT_PROP", isSuccess = true, details = "persist.security.adbinput=${prop.ifBlank { "<blank>" }}")
            }
        }
        try {
            val combined = shellExecutor("pm grant ${appContext.packageName} android.permission.WRITE_SECURE_SETTINGS").combinedOutput()
            val sanitizedOutput = sanitizeShellOutput(combined)
            if (canWriteSecureSettingsChecker(appContext)) {
                BlackBoxRecorder.recordPairing(appContext, "ADB_WRITE_SECURE_SETTINGS_GRANT", isSuccess = true, details = sanitizedOutput.ifBlank { "granted=true" })
            } else {
                recordSecureSettingsGrantFailure(appContext, sanitizedOutput.ifBlank { "canWriteSecureSettings=false" }, isRealmeOrOppo)
                if ((isRealmeOrOppo || isXiaomi) && isPermissionDenialOutput(combined.lowercase())) {
                    return buildOemPermissionRequired(appContext, isRealmeOrOppo)
                }
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            val errDetail = sanitizeShellOutput("${e.javaClass.simpleName}: ${e.message ?: "Unknown error"}")
            recordSecureSettingsGrantFailure(appContext, errDetail, isRealmeOrOppo, e)
            if ((isRealmeOrOppo || isXiaomi) && isPermissionDenialOutput(errDetail.lowercase())) {
                return buildOemPermissionRequired(appContext, isRealmeOrOppo)
            }
        }
        runShellStep("Grant ACCESS_RESTRICTED_SETTINGS") { shellExecutor("appops set ${appContext.packageName} ACCESS_RESTRICTED_SETTINGS allow") }
        runShellStep("Whitelist package in deviceidle") { shellExecutor("dumpsys deviceidle whitelist +${appContext.packageName}") }
        return null
    }

    private suspend inline fun runShellStep(label: String, action: () -> Unit) {
        try { action() } catch (e: Exception) {
            if (e is CancellationException) throw e
            Timber.tag(TAG).w(e, "Failed step: $label")
        }
    }

    private fun recordDnsProvisionError(appContext: Context, host: String, stepLabel: String, err: String) {
        Timber.tag(TAG).w("ADB Private DNS provisioning $stepLabel ($host): $err")
        BlackBoxRecorder.recordPairing(appContext, "ADB_PRIVATE_DNS_PROVISION", isSuccess = false, details = "host=$host", error = err)
    }

    internal suspend fun provisionPrivateDnsViaAdb(appContext: Context, client: AdbClient) =
        provisionPrivateDnsViaAdbWithShell(appContext, shellExecutor = { cmd -> client.shell(cmd) })

    internal suspend fun provisionPrivateDnsViaAdbWithShell(
        appContext: Context,
        shellExecutor: suspend (String) -> ShellResult,
        sdkInt: Int = Build.VERSION.SDK_INT
    ) {
        if (sdkInt < Build.VERSION_CODES.Q) return
        var targetHost = DevicePolicyBundle.CLOUDFLARE_FAMILY_DNS_HOST
        try {
            val rawHost = try {
                DnsShield.getEffectiveDnsHost(appContext).trim()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Timber.tag(TAG).w(e, "Failed reading effective DNS host; falling back to default")
                ""
            }
            val safeHost = DeviceOwnerPromoter.sanitizeDnsHostForAdb(rawHost)
            targetHost = safeHost
            val specOutput = shellExecutor("settings put global private_dns_specifier $safeHost").combinedOutput()
            if (hasShellError(specOutput)) return recordDnsProvisionError(appContext, safeHost, "Step 1", sanitizeShellOutput(specOutput))
            val verifySpec = shellExecutor("settings get global private_dns_specifier").stdoutText.trim()
            if (!verifySpec.equals(safeHost, ignoreCase = true)) return recordDnsProvisionError(appContext, safeHost, "Step 2", "expected=$safeHost, actual=${sanitizeShellOutput(verifySpec)}")
            val modeOutput = shellExecutor("settings put global private_dns_mode hostname").combinedOutput()
            if (hasShellError(modeOutput)) return recordDnsProvisionError(appContext, safeHost, "Step 3", sanitizeShellOutput(modeOutput))
            val verifyMode = shellExecutor("settings get global private_dns_mode").stdoutText.trim()
            if (verifyMode.equals("hostname", ignoreCase = true)) {
                BlackBoxRecorder.recordPairing(appContext, "ADB_PRIVATE_DNS_PROVISION", isSuccess = true, details = "host=$safeHost, mode=hostname")
            } else recordDnsProvisionError(appContext, safeHost, "mode verification", "actual=${sanitizeShellOutput(verifyMode)}")
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            val err = sanitizeShellOutput("${e.javaClass.simpleName}: ${e.message ?: "Unknown error"}")
            Timber.tag(TAG).w(e, "Failed provisioning Private DNS via ADB for host=$targetHost")
            BlackBoxRecorder.recordPairing(appContext, "ADB_PRIVATE_DNS_PROVISION", isSuccess = false, details = "host=$targetHost", error = err)
        }
    }

    private fun hasShellError(output: String): Boolean =
        output.contains("Exception", ignoreCase = true) || output.contains("Error", ignoreCase = true) || output.contains("Permission Denial", ignoreCase = true)

    internal suspend fun runDpmSetDeviceOwnerWithUserFallback(
        userId: Int,
        adminComponent: String,
        shellExecutor: suspend (String) -> ShellResult
    ): String {
        require(userId >= 0) { "Invalid userId: $userId" }
        require(ADMIN_COMPONENT_REGEX.matches(adminComponent)) { "Unsafe adminComponent: $adminComponent" }
        var output = shellExecutor("dpm set-device-owner --user $userId $adminComponent").combinedOutput()
        if (isDpmUserOptionUnsupported(output)) {
            output = shellExecutor("dpm set-device-owner $adminComponent").combinedOutput()
        }
        return output
    }

    private suspend fun executeSetupBypassPromotion(
        context: Context?,
        shellExecutor: suspend (String) -> ShellResult,
        adminComponent: String,
        userId: Int,
        isXiaomi: Boolean,
        isRealmeOrOppo: Boolean
    ): SetupBypassOutcome {
        require(userId >= 0) { "Invalid userId: $userId" }
        require(ADMIN_COMPONENT_REGEX.matches(adminComponent)) { "Unsafe adminComponent: $adminComponent" }

        val dexPath = "/data/local/tmp/antiporn_helper.dex"
        shellExecutor("echo '$HELPER_DEX_BASE64' | base64 -d > $dexPath")
        var isBypassed = false
        try {
            val setupOutput = shellExecutor("settings --user $userId put secure user_setup_complete 0").combinedOutput()
            if (isPermissionDenialOutput(setupOutput.lowercase())) {
                return when {
                    isRealmeOrOppo -> buildOemPermissionOutcome(context, setupOutput, true)
                    isXiaomi -> buildOemPermissionOutcome(context, setupOutput, false)
                    else -> SetupBypassOutcome.HardStopFailure(formatFriendlyError(context, setupOutput))
                }
            }
            isBypassed = true
            val helperRes = shellExecutor("CLASSPATH=$dexPath app_process /system/bin SetupStateHelper $userId")
            context?.let {
                BlackBoxRecorder.recordPairing(it, "SETUP_STATE_HELPER_RESULT", isSuccess = helperRes.isSuccess, details = "exit=${helperRes.exitCode}, output=${sanitizeShellOutput(helperRes.combinedOutput())}")
            }
            val output = runDpmSetDeviceOwnerWithUserFallback(userId, adminComponent, shellExecutor)
            Timber.tag(TAG).i("DPM bypass execution output: $output")
            return classifySetupBypassOutput(context, output, isXiaomi, isRealmeOrOppo)
        } finally {
            withContext(NonCancellable) {
                withTimeoutOrNull(5_000L) {
                    if (isBypassed) {
                        runShellStep("Restore user_setup_complete via ADB") { shellExecutor("settings --user $userId put secure user_setup_complete 1") }
                        runShellStep("ContentResolver user_setup_complete") {
                            context?.let {
                                (it.applicationContext ?: it).contentResolver?.let { resolver ->
                                    Settings.Secure.putInt(resolver, "user_setup_complete", 1)
                                }
                            }
                        }
                        runShellStep("Refresh SetupStateHelper in finally") { shellExecutor("CLASSPATH=$dexPath app_process /system/bin SetupStateHelper $userId") }
                    }
                    runShellStep("Remove helper dex in finally") { shellExecutor("rm -f $dexPath") }
                }
            }
        }
    }

    internal fun formatFriendlyError(context: Context?, output: String): String {
        if (context == null) return output
        val lower = output.lowercase()
        return when {
            lower.contains("already several users") || lower.contains("user 150") || lower.contains("user 95") ->
                context.safeGetString(R.string.provisioning_error_samsung_secure_folder) ?: output
            lower.contains("precondition 99") ->
                context.safeGetString(R.string.provisioning_error_realme_precondition) ?: output
            !isAccountsBlockedOutput(lower) && !lower.contains("already provisioned") && lower.contains("already") && lower.contains("owner") ->
                context.safeGetString(R.string.provisioning_error_already_device_owner) ?: output
            isPermissionDenialOutput(lower) -> when {
                OemProvisioningHelper.isRealmeOrOppo() ->
                    context.safeGetString(R.string.provisioning_error_realme_permission_monitoring) ?: output
                OemProvisioningHelper.isXiaomi() ->
                    context.safeGetString(R.string.provisioning_error_xiaomi_security_settings) ?: output
                else -> output
            }
            else -> output
        }
    }
}
