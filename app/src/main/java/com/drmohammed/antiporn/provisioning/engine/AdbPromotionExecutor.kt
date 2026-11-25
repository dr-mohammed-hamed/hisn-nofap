package com.drmohammed.antiporn.provisioning.engine

import android.accounts.AccountManager
import android.content.Context
import android.os.Build
import android.provider.Settings
import androidx.annotation.VisibleForTesting
import com.drmohammed.antiporn.R
import com.drmohammed.antiporn.accessibility.control.AccessibilitySelfActivator
import com.drmohammed.antiporn.core.model.DevicePolicyBundle
import com.drmohammed.antiporn.shields.DnsShield
import com.drmohammed.antiporn.telemetry.BlackBoxRecorder
import io.github.rhythmcache.adb.AdbClient
import io.github.rhythmcache.adb.ShellResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber

/**
 * Executes low-level ADB shell commands for zero-account Device Owner promotion,
 * Private DNS pre-provisioning, and post-promotion Wireless ADB cleanup.
 */
internal object AdbPromotionExecutor {

    private const val TAG = "AntiPorn_PromoterExec"
    private val PACKAGE_NAME_REGEX = Regex("^[a-zA-Z0-9_]+(\\.[a-zA-Z0-9_]+)+$")

    private fun ShellResult.combinedOutput(): String = (stdoutText + "\n" + stderrText).trim()

    @VisibleForTesting
    internal fun filterSafePackagesForFreeze(packages: Collection<String>): List<String> {
        val distinct = packages.distinct()
        val valid = distinct.filter { PACKAGE_NAME_REGEX.matches(it) }
        if (valid.size < distinct.size) {
            Timber.tag(TAG).w("Filtered invalid package name(s) before shell interpolation: ${distinct - valid.toSet()}")
        }
        return valid
    }

    private fun isDpmOwnerSetSuccess(output: String): Boolean =
        (output.contains("Success", ignoreCase = true) || output.contains("device owner set", ignoreCase = true)) &&
            !output.contains("error", ignoreCase = true) &&
            !output.contains("exception", ignoreCase = true)

    suspend fun executeZeroAccountPromotion(
        context: Context,
        client: AdbClient,
        adminComponent: String,
        userId: Int = 0
    ): PromotionResult {
        val appContext = context.applicationContext ?: context
        val blockingUsers = AdbSecondaryUserInspector.inspectBlockingUsers(appContext, client)
        if (blockingUsers.isNotEmpty()) {
            Timber.tag(TAG).w("Found ${blockingUsers.size} blocking secondary user(s); pausing promotion for explicit user confirmation")
            return PromotionResult.SecondaryUsersBlocking(blockingUsers)
        }

        grantPrivilegedAppOpsAndPermissions(appContext, client)
        provisionPrivateDnsViaAdb(appContext, client)

        val sdkVersion = client.shell("getprop ro.build.version.sdk").stdoutText.trim().toIntOrNull() ?: Build.VERSION.SDK_INT
        Timber.tag(TAG).i("Executing zero-account promotion on Android SDK $sdkVersion for user $userId")

        val result = if (sdkVersion >= 31) {
            when (val primary = executeSetupBypassPromotion(appContext, client, adminComponent, userId)) {
                is PromotionResult.Failure -> {
                    Timber.tag(TAG).w("Setup bypass failed: ${primary.message}. Attempting fallback to package freeze...")
                    executePackageFreezePromotion(appContext, client, adminComponent, userId)
                }
                else -> primary
            }
        } else {
            executePackageFreezePromotion(appContext, client, adminComponent, userId)
        }

        if (result is PromotionResult.Success) {
            disableWirelessAdbSilently(client)
            return result
        }
        if (result is PromotionResult.Failure) {
            val lowerMsg = result.message.lowercase()
            val secFolderLocalized = appContext.getString(R.string.provisioning_error_samsung_secure_folder)
            if (result.message == secFolderLocalized ||
                lowerMsg.contains("already several users") || lowerMsg.contains("user 150") || lowerMsg.contains("user 95")
            ) {
                val rechecked = AdbSecondaryUserInspector.inspectBlockingUsers(appContext, client)
                if (rechecked.isNotEmpty()) {
                    Timber.tag(TAG).w("Safety fallback re-check detected ${rechecked.size} blocking user(s)")
                    return PromotionResult.SecondaryUsersBlocking(rechecked)
                }
            }
        }
        return result
    }

    private fun recordSecureSettingsGrantFailure(appContext: Context, rawDetail: String, cause: Throwable? = null) {
        val oemHint = if (OemProvisioningHelper.isRealmeOrOppo()) "Disable permission monitoring required on OPPO/Realme" else null
        val details = listOfNotNull(oemHint, rawDetail.takeIf { it.isNotBlank() }).joinToString(" | ")
        if (cause != null) {
            Timber.tag(TAG).w(cause, "Failed granting WRITE_SECURE_SETTINGS permission via ADB: $details")
        } else {
            Timber.tag(TAG).w("WRITE_SECURE_SETTINGS grant via ADB did not take effect: $details")
        }
        BlackBoxRecorder.recordPairing(appContext, "ADB_WRITE_SECURE_SETTINGS_GRANT", isSuccess = false, error = details)
    }

    private suspend fun grantPrivilegedAppOpsAndPermissions(appContext: Context, client: AdbClient) {
        try {
            val grantRes = client.shell("pm grant ${appContext.packageName} android.permission.WRITE_SECURE_SETTINGS")
            val sanitizedOutput = sanitizeShellOutput(grantRes.combinedOutput())
            if (AccessibilitySelfActivator.canWriteSecureSettings(appContext)) {
                Timber.tag(TAG).i("Verified WRITE_SECURE_SETTINGS granted via ADB")
                BlackBoxRecorder.recordPairing(appContext, "ADB_WRITE_SECURE_SETTINGS_GRANT", isSuccess = true, details = sanitizedOutput.ifBlank { "granted=true" })
            } else {
                recordSecureSettingsGrantFailure(appContext, sanitizedOutput.ifBlank { "canWriteSecureSettings=false" })
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            recordSecureSettingsGrantFailure(appContext, sanitizeShellOutput("${e.javaClass.simpleName}: ${e.message ?: "Unknown error"}"), e)
        }
        runShellStep("Grant ACCESS_RESTRICTED_SETTINGS") {
            client.shell("appops set ${appContext.packageName} ACCESS_RESTRICTED_SETTINGS allow")
            Timber.tag(TAG).i("Granted ACCESS_RESTRICTED_SETTINGS allow via ADB")
        }
        runShellStep("Whitelist package in deviceidle") {
            client.shell("dumpsys deviceidle whitelist +${appContext.packageName}")
            Timber.tag(TAG).i("Whitelisted package in deviceidle via ADB")
        }
    }

    private suspend fun disableWirelessAdbSilently(client: AdbClient) {
        runShellStep("Disable adb_wifi_enabled after promotion") {
            Timber.tag(TAG).i("Disabling adb_wifi_enabled for security cleanup (leaving adb_enabled intact)...")
            client.shell("settings put global adb_wifi_enabled 0")
        }
    }

    private suspend inline fun runShellStep(label: String, action: () -> Unit) {
        try {
            action()
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Timber.tag(TAG).w(e, "Failed step: $label")
        }
    }

    private fun recordDnsProvisionError(appContext: Context, host: String, stepLabel: String, err: String) {
        Timber.tag(TAG).w("ADB Private DNS provisioning $stepLabel ($host): $err")
        BlackBoxRecorder.recordPairing(appContext, "ADB_PRIVATE_DNS_PROVISION", isSuccess = false, details = "host=$host", error = err)
    }

    private suspend fun provisionPrivateDnsViaAdb(appContext: Context, client: AdbClient) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return

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

            val specOutput = client.shell("settings put global private_dns_specifier $safeHost").combinedOutput()
            if (hasShellError(specOutput)) {
                recordDnsProvisionError(appContext, safeHost, "aborted at Step 1", "specifier write failed: ${sanitizeShellOutput(specOutput)}")
                return
            }

            val verifySpec = client.shell("settings get global private_dns_specifier").stdoutText.trim()
            if (!verifySpec.equals(safeHost, ignoreCase = true)) {
                recordDnsProvisionError(appContext, safeHost, "aborted at Step 2", "specifier read-back mismatch: expected=$safeHost, actual=${sanitizeShellOutput(verifySpec)}")
                return
            }

            val modeOutput = client.shell("settings put global private_dns_mode hostname").combinedOutput()
            if (hasShellError(modeOutput)) {
                recordDnsProvisionError(appContext, safeHost, "failed at Step 3", "mode write failed: ${sanitizeShellOutput(modeOutput)}")
                return
            }

            val verifyMode = client.shell("settings get global private_dns_mode").stdoutText.trim()
            if (verifyMode.equals("hostname", ignoreCase = true)) {
                Timber.tag(TAG).i("Successfully provisioned Private DNS via ADB (mode=hostname, specifier=$safeHost)")
                BlackBoxRecorder.recordPairing(appContext, "ADB_PRIVATE_DNS_PROVISION", isSuccess = true, details = "host=$safeHost, mode=hostname")
            } else {
                recordDnsProvisionError(appContext, safeHost, "mode verification failed", "mode read-back mismatch: expected=hostname, actual=${sanitizeShellOutput(verifyMode)}")
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            val err = sanitizeShellOutput("${e.javaClass.simpleName}: ${e.message ?: "Unknown error"}")
            Timber.tag(TAG).w(e, "Failed provisioning Private DNS via ADB for host=$targetHost")
            BlackBoxRecorder.recordPairing(appContext, "ADB_PRIVATE_DNS_PROVISION", isSuccess = false, details = "host=$targetHost", error = err)
        }
    }

    private fun hasShellError(output: String): Boolean =
        output.contains("Exception", ignoreCase = true) ||
            output.contains("Error", ignoreCase = true) ||
            output.contains("Permission Denial", ignoreCase = true)

    private fun sanitizeShellOutput(raw: String): String = raw.replace(Regex("\\s+"), " ").trim().take(300)

    private suspend inline fun runDpmWithUserFallback(
        client: AdbClient,
        userId: Int,
        adminComponent: String,
        commandBuilder: (dpmArgs: String) -> String
    ): String {
        var output = client.shell(commandBuilder("--user $userId $adminComponent")).combinedOutput()
        if (output.contains("unknown option: --user", ignoreCase = true) ||
            output.contains("illegal option", ignoreCase = true)
        ) {
            output = client.shell(commandBuilder(adminComponent)).combinedOutput()
        }
        return output
    }

    private suspend fun executeSetupBypassPromotion(
        context: Context?,
        client: AdbClient,
        adminComponent: String,
        userId: Int
    ): PromotionResult {
        val dexPath = "/data/local/tmp/antiporn_helper.dex"
        client.shell("echo '$HELPER_DEX_BASE64' | base64 -d > $dexPath")

        var isBypassed = false
        try {
            val setupRes = client.shell("settings --user $userId put secure user_setup_complete 0")
            isBypassed = true

            if (setupRes.stdoutText.contains("SecurityException", ignoreCase = true) ||
                setupRes.stderrText.contains("SecurityException", ignoreCase = true) ||
                setupRes.stderrText.contains("Permission Denial", ignoreCase = true)
            ) {
                val errorMsg = if (OemProvisioningHelper.isRealmeOrOppo()) {
                    context?.getString(R.string.provisioning_error_realme_permission_monitoring)
                } else {
                    context?.getString(R.string.provisioning_error_xiaomi_security_settings)
                } ?: setupRes.stderrText.ifBlank { setupRes.stdoutText }
                return PromotionResult.Failure(errorMsg)
            }

            client.shell("CLASSPATH=$dexPath app_process /system/bin SetupStateHelper $userId")
            val output = runDpmWithUserFallback(client, userId, adminComponent) { "dpm set-device-owner $it" }
            Timber.tag(TAG).i("DPM bypass execution output: $output")
            return if (isDpmOwnerSetSuccess(output)) PromotionResult.Success else PromotionResult.Failure(formatFriendlyError(context, output))
        } finally {
            withContext(NonCancellable) {
                withTimeoutOrNull(5_000L) {
                    if (isBypassed) {
                        runShellStep("Restore user_setup_complete via ADB") {
                            client.shell("settings --user $userId put secure user_setup_complete 1")
                        }
                        runShellStep("Local ContentResolver fallback for user_setup_complete") {
                            context?.let { ctx ->
                                Settings.Secure.putInt((ctx.applicationContext ?: ctx).contentResolver, "user_setup_complete", 1)
                                Timber.tag(TAG).i("Local ContentResolver fallback restored user_setup_complete=1 successfully")
                            }
                        }
                        runShellStep("Refresh SetupStateHelper in finally") {
                            client.shell("CLASSPATH=$dexPath app_process /system/bin SetupStateHelper $userId")
                        }
                    }
                    runShellStep("Remove helper dex in finally") { client.shell("rm -f $dexPath") }
                }
            }
        }
    }

    private suspend fun executePackageFreezePromotion(
        context: Context?,
        client: AdbClient,
        adminComponent: String,
        userId: Int
    ): PromotionResult {
        val dumpsys = client.shell("dumpsys account").stdoutText
        val regex = "Account \\{name=.*?, type=(.*?)\\}".toRegex()
        val accountTypes = dumpsys.lines().mapNotNull { regex.find(it)?.groupValues?.get(1) }.toSet()

        val authenticatorMap = context?.let { ctx ->
            try {
                AccountManager.get(ctx.applicationContext ?: ctx).authenticatorTypes.associate { it.type to it.packageName }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Timber.tag(TAG).w(e, "Failed to retrieve authenticatorTypes from AccountManager")
                emptyMap()
            }
        } ?: emptyMap()

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
        val safePackages = filterSafePackagesForFreeze(packagesToDisable)

        try {
            val trapScript = safePackages.joinToString("; ") { "pm enable --user $userId $it" }
            val disableScript = safePackages.joinToString("; ") { "pm disable-user --user $userId $it" }
            val output = runDpmWithUserFallback(client, userId, adminComponent) { dpmArgs ->
                "sh -c 'trap \"$trapScript\" EXIT; $disableScript; sleep 1; dpm set-device-owner $dpmArgs'"
            }
            Timber.tag(TAG).i("DPM legacy execution output: $output")
            return if (isDpmOwnerSetSuccess(output)) PromotionResult.Success else PromotionResult.Failure(formatFriendlyError(context, output))
        } finally {
            withContext(NonCancellable) {
                withTimeoutOrNull(5_000L) {
                    for (pkg in safePackages) {
                        runShellStep("Re-enable package $pkg in finally") { client.shell("pm enable --user $userId $pkg") }
                    }
                }
            }
        }
    }

    private fun formatFriendlyError(context: Context?, output: String): String {
        if (context == null) return output
        val lower = output.lowercase()
        return when {
            lower.contains("already several users") || lower.contains("user 150") || lower.contains("user 95") ->
                context.getString(R.string.provisioning_error_samsung_secure_folder)
            lower.contains("precondition 99") ->
                context.getString(R.string.provisioning_error_realme_precondition)
            lower.contains("already") && lower.contains("owner") ->
                context.getString(R.string.provisioning_error_already_device_owner)
            lower.contains("securityexception") || lower.contains("permission denial") -> {
                if (OemProvisioningHelper.isRealmeOrOppo()) {
                    context.getString(R.string.provisioning_error_realme_permission_monitoring)
                } else {
                    context.getString(R.string.provisioning_error_xiaomi_security_settings)
                }
            }
            else -> output
        }
    }
}
