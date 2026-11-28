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
import com.drmohammed.antiporn.provisioning.engine.AdbShellValidation.sanitizeUserFacingError
import com.drmohammed.antiporn.shields.DnsShield
import com.drmohammed.antiporn.telemetry.BlackBoxRecorder
import com.drmohammed.antiporn.telemetry.SafeDpmGateway
import io.github.rhythmcache.adb.AdbClient
import io.github.rhythmcache.adb.ShellResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
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
        ) ?: sanitizeUserFacingError(rawOutput)
        return SetupBypassOutcome.OemPermissionRequired(oemType, msg)
    }

    private fun buildOemOrHardStop(context: Context?, rawOutput: String, isXiaomi: Boolean, isRealmeOrOppo: Boolean): SetupBypassOutcome =
        if (isRealmeOrOppo || isXiaomi) buildOemPermissionOutcome(context, rawOutput, isRealmeOrOppo)
        else SetupBypassOutcome.HardStopFailure(formatFriendlyError(context, rawOutput))

    internal fun classifySetupBypassOutput(context: Context?, output: String, isXiaomi: Boolean, isRealmeOrOppo: Boolean): SetupBypassOutcome {
        if (isDpmOwnerSetSuccess(output)) return SetupBypassOutcome.Success
        val lower = output.lowercase()
        return when {
            lower.contains("already several users") || lower.contains("user 150") || lower.contains("user 95") ->
                SetupBypassOutcome.SecondaryUsersError(output)
            isPermissionDenialOutput(lower) -> buildOemOrHardStop(context, output, isXiaomi, isRealmeOrOppo)
            isAccountsBlockedOutput(lower) -> SetupBypassOutcome.AccountsBlockedFailure(output)
            !lower.contains("already provisioned") && lower.contains("already") && lower.contains("owner") ->
                SetupBypassOutcome.HardStopFailure(context.safeGetString(R.string.provisioning_error_already_device_owner) ?: sanitizeUserFacingError(output))
            lower.contains("precondition 99") ->
                SetupBypassOutcome.HardStopFailure(context.safeGetString(R.string.provisioning_error_realme_precondition) ?: sanitizeUserFacingError(output))
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
        context = context, adminComponent = adminComponent, userId = userId,
        shellExecutor = { cmd -> client.shell(cmd) }, reconnectProvider = reconnectProvider,
        postPromotionVerifier = { appCtx, uid, didBypass -> AdbPostPromotionVerifier.verifyAndFinalize(appCtx, client, uid, didBypass, reconnectProvider) },
        packageFreezeRunner = { appCtx, comp, uid -> AdbPackageFreezeManager.executePackageFreezePromotion(appCtx, client, comp, uid, reconnectProvider) }
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
        blockingUsersInspector: suspend (Context) -> List<BlockingSecondaryUser> = { AdbSecondaryUserInspector.inspectBlockingUsersWithShell(it, shellExecutor) },
        sdkVersionProvider: suspend () -> Int = { shellExecutor("getprop ro.build.version.sdk").stdoutText.trim().toIntOrNull() ?: Build.VERSION.SDK_INT },
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
            return PromotionResult.Failure(appContext.safeGetString(R.string.provisioning_error_heal_failed) ?: "Could not re-enable stranded apps via Wireless ADB.")
        }
        if (isDeviceOwnerChecker(appContext)) return postPromotionVerifier(appContext, userId, true)

        val blockingUsers = blockingUsersInspector(appContext)
        if (blockingUsers.isNotEmpty()) {
            Timber.tag(TAG).w("Found ${blockingUsers.size} blocking secondary user(s); pausing promotion")
            return PromotionResult.SecondaryUsersBlocking(blockingUsers)
        }
        grantPrivilegedAppOpsAndPermissions(appContext, shellExecutor, canWriteSecureSettingsChecker, isXiaomi, isRealmeOrOppo)?.let { return it }
        provisionPrivateDnsViaAdbWithShell(appContext, shellExecutor)
        tryFastPathZeroAccountPromotion(appContext, shellExecutor, adminComponent, userId, isXiaomi, isRealmeOrOppo, blockingUsersInspector, postPromotionVerifier)?.let { return it }

        val sdkVersion = sdkVersionProvider()
        Timber.tag(TAG).i("Executing zero-account promotion on Android SDK $sdkVersion for user $userId")
        if (sdkVersion >= 31) {
            return when (val outcome = executeSetupBypassPromotion(appContext, shellExecutor, adminComponent, userId, isXiaomi, isRealmeOrOppo)) {
                is SetupBypassOutcome.Success -> postPromotionVerifier(appContext, userId, true)
                is SetupBypassOutcome.OemPermissionRequired -> PromotionResult.OemPermissionRequired(outcome.oemType, outcome.message)
                is SetupBypassOutcome.SecondaryUsersError -> recheckSecondaryUsersOrFail(appContext, blockingUsersInspector, outcome.rawOutput)
                is SetupBypassOutcome.HardStopFailure -> PromotionResult.Failure(outcome.message)
                is SetupBypassOutcome.AccountsBlockedFailure -> {
                    Timber.tag(TAG).w("Setup bypass blocked by accounts; invoking triple-failsafe package freeze")
                    delay(1500L)
                    runPackageFreezeAndVerify(appContext, adminComponent, userId, packageFreezeRunner, postPromotionVerifier, didSetupBypass = true)
                }
            }
        }
        return runPackageFreezeAndVerify(appContext, adminComponent, userId, packageFreezeRunner, postPromotionVerifier)
    }

    private suspend fun runPackageFreezeAndVerify(
        appContext: Context, adminComponent: String, userId: Int,
        packageFreezeRunner: suspend (Context, String, Int) -> PromotionResult,
        postPromotionVerifier: suspend (Context, Int, Boolean) -> PromotionResult,
        didSetupBypass: Boolean = false
    ): PromotionResult = packageFreezeRunner(appContext, adminComponent, userId).let {
        if (it is PromotionResult.Success) postPromotionVerifier(appContext, userId, didSetupBypass) else it
    }

    private suspend fun tryFastPathZeroAccountPromotion(
        appContext: Context, shellExecutor: suspend (String) -> ShellResult, adminComponent: String, userId: Int,
        isXiaomi: Boolean, isRealmeOrOppo: Boolean,
        blockingUsersInspector: suspend (Context) -> List<BlockingSecondaryUser>,
        postPromotionVerifier: suspend (Context, Int, Boolean) -> PromotionResult
    ): PromotionResult? {
        val audit = AdbAccountInspector.inspectAccountsWithShell(
            appContext, userId, shellExecutor, AdbAccountInspector.buildLocalAuthenticatorMap(appContext), recordAudit = true
        )
        if (audit.status != AccountAuditStatus.VERIFIED_ZERO) return null
        val output = runDpmSetDeviceOwnerWithUserFallback(userId, adminComponent, shellExecutor)
        BlackBoxRecorder.recordPairing(appContext, "FAST_PATH_ZERO_ACCOUNT_DPM", isSuccess = isDpmOwnerSetSuccess(output), details = sanitizeUserFacingError(output))
        return when (val outcome = classifySetupBypassOutput(appContext, output, isXiaomi, isRealmeOrOppo)) {
            is SetupBypassOutcome.Success -> postPromotionVerifier(appContext, userId, false)
            is SetupBypassOutcome.OemPermissionRequired -> PromotionResult.OemPermissionRequired(outcome.oemType, outcome.message)
            is SetupBypassOutcome.SecondaryUsersError -> recheckSecondaryUsersOrFail(appContext, blockingUsersInspector, outcome.rawOutput)
            is SetupBypassOutcome.AccountsBlockedFailure -> null
            is SetupBypassOutcome.HardStopFailure -> PromotionResult.Failure(outcome.message)
        }
    }

    private suspend fun recheckSecondaryUsersOrFail(
        appContext: Context, blockingUsersInspector: suspend (Context) -> List<BlockingSecondaryUser>, rawOutput: String
    ): PromotionResult = blockingUsersInspector(appContext).let {
        if (it.isNotEmpty()) PromotionResult.SecondaryUsersBlocking(it) else PromotionResult.Failure(formatFriendlyError(appContext, rawOutput))
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
        var denialText = ""
        try {
            val combined = shellExecutor("pm grant ${appContext.packageName} android.permission.WRITE_SECURE_SETTINGS").combinedOutput()
            val sanitizedOutput = sanitizeUserFacingError(combined, maxLength = 300)
            if (canWriteSecureSettingsChecker(appContext)) {
                BlackBoxRecorder.recordPairing(appContext, "ADB_WRITE_SECURE_SETTINGS_GRANT", isSuccess = true, details = sanitizedOutput.ifBlank { "granted=true" })
            } else {
                denialText = combined
                recordSecureSettingsGrantFailure(appContext, sanitizedOutput.ifBlank { "canWriteSecureSettings=false" }, isRealmeOrOppo)
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            denialText = sanitizeUserFacingError("${e.javaClass.simpleName}: ${e.message ?: "Unknown error"}", maxLength = 300)
            recordSecureSettingsGrantFailure(appContext, denialText, isRealmeOrOppo, e)
        }
        if (denialText.isNotEmpty() && (isRealmeOrOppo || isXiaomi) && isPermissionDenialOutput(denialText.lowercase())) {
            val outcome = buildOemPermissionOutcome(appContext, "", isRealmeOrOppo)
            return PromotionResult.OemPermissionRequired(outcome.oemType, outcome.message)
        }
        runShellStep("Grant ACCESS_RESTRICTED_SETTINGS") {
            val res = shellExecutor("appops set ${appContext.packageName} ACCESS_RESTRICTED_SETTINGS allow")
            if (!res.isSuccess) Timber.tag(TAG).w("Grant ACCESS_RESTRICTED_SETTINGS failed (exit=${res.exitCode}): ${sanitizeUserFacingError(res.combinedOutput(), 200)}")
        }
        runShellStep("Whitelist package in deviceidle") {
            val res = shellExecutor("dumpsys deviceidle whitelist +${appContext.packageName}")
            if (!res.isSuccess) Timber.tag(TAG).w("Whitelist package in deviceidle failed (exit=${res.exitCode}): ${sanitizeUserFacingError(res.combinedOutput(), 200)}")
        }
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

    private suspend fun putAndVerifyDnsSetting(
        appContext: Context, shellExecutor: suspend (String) -> ShellResult,
        key: String, expected: String, stepLabel: String, host: String
    ): Boolean {
        val putOut = shellExecutor("settings put global $key $expected").combinedOutput()
        if (hasShellError(putOut)) return recordDnsProvisionError(appContext, host, stepLabel, sanitizeUserFacingError(putOut, 300)).let { false }
        val actual = shellExecutor("settings get global $key").stdoutText.trim()
        if (!actual.equals(expected, ignoreCase = true)) {
            recordDnsProvisionError(appContext, host, "$stepLabel verification", "expected=$expected, actual=${sanitizeUserFacingError(actual, 300)}")
            return false
        }
        return true
    }

    internal suspend fun provisionPrivateDnsViaAdbWithShell(
        appContext: Context, shellExecutor: suspend (String) -> ShellResult, sdkInt: Int = Build.VERSION.SDK_INT
    ) {
        if (sdkInt < Build.VERSION_CODES.Q) return
        var targetHost = DevicePolicyBundle.CLOUDFLARE_FAMILY_DNS_HOST
        try {
            val rawHost = try { DnsShield.getEffectiveDnsHost(appContext).trim() } catch (e: Exception) {
                if (e is CancellationException) throw e
                Timber.tag(TAG).w(e, "Failed reading effective DNS host; falling back to default"); ""
            }
            targetHost = DeviceOwnerPromoter.sanitizeDnsHostForAdb(rawHost)
            if (!putAndVerifyDnsSetting(appContext, shellExecutor, "private_dns_specifier", targetHost, "specifier", targetHost)) return
            if (!putAndVerifyDnsSetting(appContext, shellExecutor, "private_dns_mode", "hostname", "mode", targetHost)) return
            BlackBoxRecorder.recordPairing(appContext, "ADB_PRIVATE_DNS_PROVISION", isSuccess = true, details = "host=$targetHost, mode=hostname")
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            val err = sanitizeUserFacingError("${e.javaClass.simpleName}: ${e.message ?: "Unknown error"}", 300)
            Timber.tag(TAG).w(e, "Failed provisioning Private DNS via ADB for host=$targetHost")
            BlackBoxRecorder.recordPairing(appContext, "ADB_PRIVATE_DNS_PROVISION", isSuccess = false, details = "host=$targetHost", error = err)
        }
    }

    private fun hasShellError(output: String): Boolean =
        output.contains("Exception", ignoreCase = true) || output.contains("Error", ignoreCase = true) || output.contains("Permission Denial", ignoreCase = true)

    internal suspend fun runDpmSetDeviceOwnerWithUserFallback(
        userId: Int, adminComponent: String, shellExecutor: suspend (String) -> ShellResult
    ): String {
        require(userId >= 0) { "Invalid userId: $userId" }
        require(ADMIN_COMPONENT_REGEX.matches(adminComponent)) { "Unsafe adminComponent: $adminComponent" }
        var output = ""
        for (attempt in 0..2) {
            if (attempt > 0) delay(1500L)
            output = shellExecutor("dpm set-device-owner --user $userId $adminComponent").combinedOutput()
            if (isDpmUserOptionUnsupported(output)) output = shellExecutor("dpm set-device-owner $adminComponent").combinedOutput()
            if (!AdbShellValidation.isAdminBeingRemovedOutput(output)) break
        }
        return output
    }

    private suspend fun executeSetupBypassPromotion(
        context: Context?, shellExecutor: suspend (String) -> ShellResult,
        adminComponent: String, userId: Int, isXiaomi: Boolean, isRealmeOrOppo: Boolean
    ): SetupBypassOutcome {
        require(userId >= 0) { "Invalid userId: $userId" }
        require(ADMIN_COMPONENT_REGEX.matches(adminComponent)) { "Unsafe adminComponent: $adminComponent" }

        val dexPath = "/data/local/tmp/antiporn_helper.dex"
        runShellStep("Clean stale helper dex") { shellExecutor("rm -f $dexPath") }
        val dexRes = shellExecutor("echo '$HELPER_DEX_BASE64' | base64 -d > $dexPath && chmod 444 $dexPath")
        if (!dexRes.isSuccess) {
            val err = dexRes.combinedOutput().ifBlank { "Failed staging helper DEX" }
            return buildOemOrHardStop(context, err, isXiaomi, isRealmeOrOppo)
        }
        var isBypassed = false
        try {
            val setupOutput = shellExecutor("settings --user $userId put secure user_setup_complete 0").combinedOutput()
            if (isPermissionDenialOutput(setupOutput.lowercase())) return buildOemOrHardStop(context, setupOutput, isXiaomi, isRealmeOrOppo)
            isBypassed = true
            val helperRes = shellExecutor("CLASSPATH=$dexPath app_process /system/bin SetupStateHelper $userId")
            val helperOut = helperRes.combinedOutput()
            context?.let {
                BlackBoxRecorder.recordPairing(it, "SETUP_STATE_HELPER_RESULT", isSuccess = helperRes.isSuccess, details = "exit=${helperRes.exitCode}, output=${sanitizeUserFacingError(helperOut, 300)}")
            }
            if (!helperOut.contains("SETUP_STATE_SUCCESS")) {
                return if ((isRealmeOrOppo || isXiaomi) && isPermissionDenialOutput(helperOut.lowercase())) {
                    buildOemPermissionOutcome(context, helperOut, isRealmeOrOppo)
                } else SetupBypassOutcome.AccountsBlockedFailure(helperOut)
            }
            val output = runDpmSetDeviceOwnerWithUserFallback(userId, adminComponent, shellExecutor)
            Timber.tag(TAG).i("DPM bypass execution output: ${sanitizeUserFacingError(output)}")
            return classifySetupBypassOutput(context, output, isXiaomi, isRealmeOrOppo)
        } finally {
            withContext(NonCancellable) {
                withTimeoutOrNull(5_000L) {
                    if (isBypassed) {
                        runShellStep("ContentResolver user_setup_complete") {
                            context?.let { (it.applicationContext ?: it).contentResolver?.let { r -> Settings.Secure.putInt(r, "user_setup_complete", 1) } }
                        }
                        withTimeoutOrNull(2_000L) {
                            runShellStep("Restore user_setup_complete via ADB") { shellExecutor("settings --user $userId put secure user_setup_complete 1") }
                        }
                        withTimeoutOrNull(2_000L) {
                            runShellStep("Refresh SetupStateHelper in finally") { shellExecutor("CLASSPATH=$dexPath app_process /system/bin SetupStateHelper $userId") }
                        }
                    }
                    runShellStep("Remove helper dex in finally") { shellExecutor("rm -f $dexPath") }
                }
            }
        }
    }

    internal fun formatFriendlyError(context: Context?, output: String): String {
        val sanitized = sanitizeUserFacingError(output)
        if (context == null) return sanitized
        val lower = output.lowercase()
        return when {
            lower.contains("already several users") || lower.contains("user 150") || lower.contains("user 95") ->
                context.safeGetString(R.string.provisioning_error_samsung_secure_folder) ?: sanitized
            lower.contains("precondition 99") ->
                context.safeGetString(R.string.provisioning_error_realme_precondition) ?: sanitized
            isAccountsBlockedOutput(lower) ->
                context.safeGetString(R.string.provisioning_error_accounts_blocked_generic) ?: "Active accounts on the device are preventing Device Owner activation."
            AdbShellValidation.isAdminBeingRemovedOutput(output) ->
                context.safeGetString(R.string.provisioning_error_admin_removing) ?: "System is finishing a previous reset. Please retry in a few seconds."
            !lower.contains("already provisioned") && lower.contains("already") && lower.contains("owner") ->
                context.safeGetString(R.string.provisioning_error_already_device_owner) ?: sanitized
            isPermissionDenialOutput(lower) -> when {
                OemProvisioningHelper.isRealmeOrOppo() -> context.safeGetString(R.string.provisioning_error_realme_permission_monitoring) ?: sanitized
                OemProvisioningHelper.isXiaomi() -> context.safeGetString(R.string.provisioning_error_xiaomi_security_settings) ?: sanitized
                else -> sanitized
            }
            else -> sanitized
        }
    }
}
