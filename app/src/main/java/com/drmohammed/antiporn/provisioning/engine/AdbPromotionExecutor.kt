package com.drmohammed.antiporn.provisioning.engine

import android.accounts.AccountManager
import android.content.Context
import android.os.Build
import com.drmohammed.antiporn.R
import com.drmohammed.antiporn.accessibility.control.AccessibilitySelfActivator
import com.drmohammed.antiporn.core.model.DevicePolicyBundle
import com.drmohammed.antiporn.shields.DnsShield
import com.drmohammed.antiporn.telemetry.BlackBoxRecorder
import io.github.rhythmcache.adb.AdbClient
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

        val sdkVerStr = client.shell("getprop ro.build.version.sdk").stdoutText.trim()
        val sdkVersion = sdkVerStr.toIntOrNull() ?: Build.VERSION.SDK_INT
        Timber.tag(TAG).i("Executing zero-account promotion on Android SDK $sdkVersion for user $userId")

        val result = if (sdkVersion >= 31) {
            when (val primaryResult = executeSetupBypassPromotion(appContext, client, adminComponent, userId)) {
                is PromotionResult.Success -> {
                    disableWirelessAdbSilently(client)
                    primaryResult
                }
                is PromotionResult.SecondaryUsersBlocking -> primaryResult
                is PromotionResult.Failure -> {
                    Timber.tag(TAG).w("Setup bypass failed: ${primaryResult.message}. Attempting fallback to package freeze...")
                    val fallbackResult = executePackageFreezePromotion(appContext, client, adminComponent, userId)
                    if (fallbackResult is PromotionResult.Success) {
                        disableWirelessAdbSilently(client)
                    }
                    fallbackResult
                }
            }
        } else {
            val legacyResult = executePackageFreezePromotion(appContext, client, adminComponent, userId)
            if (legacyResult is PromotionResult.Success) {
                disableWirelessAdbSilently(client)
            }
            legacyResult
        }

        if (result is PromotionResult.Failure) {
            val lowerMsg = result.message.lowercase()
            val secFolderLocalized = appContext.getString(R.string.provisioning_error_samsung_secure_folder)
            if (result.message == secFolderLocalized ||
                lowerMsg.contains("already several users") ||
                lowerMsg.contains("user 150") ||
                lowerMsg.contains("user 95")
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

    private suspend fun grantPrivilegedAppOpsAndPermissions(appContext: Context, client: AdbClient) {
        try {
            val grantRes = client.shell("pm grant ${appContext.packageName} android.permission.WRITE_SECURE_SETTINGS")
            val rawGrantOutput = (grantRes.stdoutText + "\n" + grantRes.stderrText).trim()
            val sanitizedGrantOutput = sanitizeShellOutput(rawGrantOutput)
            val granted = AccessibilitySelfActivator.canWriteSecureSettings(appContext)
            if (granted) {
                Timber.tag(TAG).i("Verified WRITE_SECURE_SETTINGS granted via ADB")
                BlackBoxRecorder.recordPairing(
                    appContext,
                    "ADB_WRITE_SECURE_SETTINGS_GRANT",
                    isSuccess = true,
                    details = sanitizedGrantOutput.ifBlank { "granted=true" }
                )
            } else {
                val oemHint = if (OemProvisioningHelper.isRealmeOrOppo()) {
                    "Disable permission monitoring required on OPPO/Realme"
                } else {
                    null
                }
                val details = listOfNotNull(
                    oemHint,
                    sanitizedGrantOutput.takeIf { it.isNotBlank() } ?: "canWriteSecureSettings=false"
                ).joinToString(" | ")
                Timber.tag(TAG).w("WRITE_SECURE_SETTINGS grant via ADB did not take effect: $details")
                BlackBoxRecorder.recordPairing(
                    appContext,
                    "ADB_WRITE_SECURE_SETTINGS_GRANT",
                    isSuccess = false,
                    error = details
                )
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            val oemHint = if (OemProvisioningHelper.isRealmeOrOppo()) {
                "Disable permission monitoring required on OPPO/Realme"
            } else {
                null
            }
            val details = listOfNotNull(
                oemHint,
                sanitizeShellOutput("${e.javaClass.simpleName}: ${e.message ?: "Unknown error"}")
            ).joinToString(" | ")
            Timber.tag(TAG).w(e, "Failed granting WRITE_SECURE_SETTINGS permission via ADB")
            BlackBoxRecorder.recordPairing(
                appContext,
                "ADB_WRITE_SECURE_SETTINGS_GRANT",
                isSuccess = false,
                error = details
            )
        }
        try {
            client.shell("appops set ${appContext.packageName} ACCESS_RESTRICTED_SETTINGS allow")
            Timber.tag(TAG).i("Granted ACCESS_RESTRICTED_SETTINGS allow via ADB")
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Timber.tag(TAG).w(e, "Failed setting ACCESS_RESTRICTED_SETTINGS via ADB")
        }
        try {
            client.shell("dumpsys deviceidle whitelist +${appContext.packageName}")
            Timber.tag(TAG).i("Whitelisted package in deviceidle via ADB")
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Timber.tag(TAG).w(e, "Failed adding to deviceidle whitelist via ADB")
        }
    }

    private suspend fun disableWirelessAdbSilently(client: AdbClient) {
        try {
            Timber.tag(TAG).i("Disabling adb_wifi_enabled for security cleanup (leaving adb_enabled intact)...")
            client.shell("settings put global adb_wifi_enabled 0")
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Timber.tag(TAG).w(e, "Failed disabling adb_wifi_enabled after promotion")
        }
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

            val specRes = client.shell("settings put global private_dns_specifier $safeHost")
            val specOutput = (specRes.stdoutText + "\n" + specRes.stderrText).trim()
            if (hasShellError(specOutput)) {
                val err = "specifier write failed: ${sanitizeShellOutput(specOutput)}"
                Timber.tag(TAG).w("ADB Private DNS provisioning aborted at Step 1 ($safeHost): $err")
                BlackBoxRecorder.recordPairing(appContext, "ADB_PRIVATE_DNS_PROVISION", isSuccess = false, details = "host=$safeHost", error = err)
                return
            }

            val verifySpec = client.shell("settings get global private_dns_specifier").stdoutText.trim()
            if (!verifySpec.equals(safeHost, ignoreCase = true)) {
                val err = "specifier read-back mismatch: expected=$safeHost, actual=${sanitizeShellOutput(verifySpec)}"
                Timber.tag(TAG).w("ADB Private DNS provisioning aborted at Step 2: $err")
                BlackBoxRecorder.recordPairing(appContext, "ADB_PRIVATE_DNS_PROVISION", isSuccess = false, details = "host=$safeHost", error = err)
                return
            }

            val modeRes = client.shell("settings put global private_dns_mode hostname")
            val modeOutput = (modeRes.stdoutText + "\n" + modeRes.stderrText).trim()
            if (hasShellError(modeOutput)) {
                val err = "mode write failed: ${sanitizeShellOutput(modeOutput)}"
                Timber.tag(TAG).w("ADB Private DNS provisioning failed at Step 3 ($safeHost): $err")
                BlackBoxRecorder.recordPairing(appContext, "ADB_PRIVATE_DNS_PROVISION", isSuccess = false, details = "host=$safeHost", error = err)
                return
            }

            val verifyMode = client.shell("settings get global private_dns_mode").stdoutText.trim()
            if (verifyMode.equals("hostname", ignoreCase = true)) {
                Timber.tag(TAG).i("Successfully provisioned Private DNS via ADB (mode=hostname, specifier=$safeHost)")
                BlackBoxRecorder.recordPairing(appContext, "ADB_PRIVATE_DNS_PROVISION", isSuccess = true, details = "host=$safeHost, mode=hostname")
            } else {
                val err = "mode read-back mismatch: expected=hostname, actual=${sanitizeShellOutput(verifyMode)}"
                Timber.tag(TAG).w("ADB Private DNS mode verification failed ($safeHost): $err")
                BlackBoxRecorder.recordPairing(appContext, "ADB_PRIVATE_DNS_PROVISION", isSuccess = false, details = "host=$safeHost", error = err)
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            val err = sanitizeShellOutput("${e.javaClass.simpleName}: ${e.message ?: "Unknown error"}")
            Timber.tag(TAG).w(e, "Failed provisioning Private DNS via ADB for host=$targetHost")
            BlackBoxRecorder.recordPairing(appContext, "ADB_PRIVATE_DNS_PROVISION", isSuccess = false, details = "host=$targetHost", error = err)
        }
    }

    private fun hasShellError(output: String): Boolean {
        return output.contains("Exception", ignoreCase = true) ||
            output.contains("Error", ignoreCase = true) ||
            output.contains("Permission Denial", ignoreCase = true)
    }

    private fun sanitizeShellOutput(raw: String): String {
        return raw.replace(Regex("\\s+"), " ").trim().take(300)
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

            var doResult = client.shell("dpm set-device-owner --user $userId $adminComponent")
            var output = (doResult.stdoutText + "\n" + doResult.stderrText).trim()

            if (output.contains("unknown option: --user", ignoreCase = true) ||
                output.contains("illegal option", ignoreCase = true)
            ) {
                doResult = client.shell("dpm set-device-owner $adminComponent")
                output = (doResult.stdoutText + "\n" + doResult.stderrText).trim()
            }

            Timber.tag(TAG).i("DPM bypass execution output: $output")
            val isSuccess = (output.contains("Success", ignoreCase = true) || output.contains("device owner set", ignoreCase = true)) &&
                !output.contains("error", ignoreCase = true) && !output.contains("exception", ignoreCase = true)

            return if (isSuccess) PromotionResult.Success else PromotionResult.Failure(formatFriendlyError(context, output))
        } finally {
            withContext(NonCancellable) {
                withTimeoutOrNull(5_000L) {
                    if (isBypassed) {
                        try {
                            client.shell("settings --user $userId put secure user_setup_complete 1")
                        } catch (e: Exception) {
                            Timber.tag(TAG).e(e, "Failed to restore user_setup_complete in finally via ADB")
                        }
                        try {
                            context?.let { ctx ->
                                android.provider.Settings.Secure.putInt((ctx.applicationContext ?: ctx).contentResolver, "user_setup_complete", 1)
                                Timber.tag(TAG).i("Local ContentResolver fallback restored user_setup_complete=1 successfully")
                            }
                        } catch (e: Exception) {
                            Timber.tag(TAG).w(e, "Local ContentResolver fallback for user_setup_complete failed")
                        }
                        try {
                            client.shell("CLASSPATH=$dexPath app_process /system/bin SetupStateHelper $userId")
                        } catch (e: Exception) {
                            Timber.tag(TAG).e(e, "Failed to refresh SetupStateHelper in finally")
                        }
                    }
                    try {
                        client.shell("rm -f $dexPath")
                    } catch (e: Exception) {
                        Timber.tag(TAG).w(e, "Failed to remove helper dex in finally")
                    }
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
        val accountTypes = mutableSetOf<String>()
        dumpsys.lines().forEach { line ->
            regex.find(line)?.let { accountTypes.add(it.groupValues[1]) }
        }

        val authenticatorMap = context?.let { ctx ->
            try {
                AccountManager.get(ctx.applicationContext ?: ctx).authenticatorTypes
                    .associate { it.type to it.packageName }
            } catch (e: Exception) {
                Timber.tag(TAG).w(e, "Failed to retrieve authenticatorTypes from AccountManager")
                emptyMap()
            }
        } ?: emptyMap()

        val packagesToDisable = mutableSetOf<String>()
        accountTypes.forEach { type ->
            val mappedPkg = authenticatorMap[type]
            if (mappedPkg != null) {
                packagesToDisable.add(mappedPkg)
            } else {
                when {
                    type.contains("google", ignoreCase = true) -> packagesToDisable.add("com.google.android.gms")
                    type.contains("whatsapp", ignoreCase = true) -> packagesToDisable.add("com.whatsapp")
                    type.contains("telegram", ignoreCase = true) -> packagesToDisable.add("org.telegram.messenger")
                    type.contains("samsung", ignoreCase = true) -> packagesToDisable.add("com.osp.app.signin")
                    type.contains("xiaomi", ignoreCase = true) -> packagesToDisable.add("com.xiaomi.account")
                }
            }
        }
        packagesToDisable.add("com.google.android.gms")

        try {
            val trapScript = packagesToDisable.joinToString("; ") { "pm enable --user $userId $it" }
            val disableScript = packagesToDisable.joinToString("; ") { "pm disable-user --user $userId $it" }

            val atomicCmd = "sh -c 'trap \"$trapScript\" EXIT; $disableScript; sleep 1; dpm set-device-owner --user $userId $adminComponent'"
            var doResult = client.shell(atomicCmd)
            var output = (doResult.stdoutText + "\n" + doResult.stderrText).trim()

            if (output.contains("unknown option: --user", ignoreCase = true) ||
                output.contains("illegal option", ignoreCase = true)
            ) {
                val legacyAtomicCmd = "sh -c 'trap \"$trapScript\" EXIT; $disableScript; sleep 1; dpm set-device-owner $adminComponent'"
                doResult = client.shell(legacyAtomicCmd)
                output = (doResult.stdoutText + "\n" + doResult.stderrText).trim()
            }

            Timber.tag(TAG).i("DPM legacy execution output: $output")
            val isSuccess = (output.contains("Success", ignoreCase = true) || output.contains("device owner set", ignoreCase = true)) &&
                !output.contains("error", ignoreCase = true) && !output.contains("exception", ignoreCase = true)

            return if (isSuccess) PromotionResult.Success else PromotionResult.Failure(formatFriendlyError(context, output))
        } finally {
            withContext(NonCancellable) {
                withTimeoutOrNull(5_000L) {
                    for (pkg in packagesToDisable) {
                        try {
                            client.shell("pm enable --user $userId $pkg")
                        } catch (e: Exception) {
                            Timber.tag(TAG).e(e, "Failed to re-enable package $pkg in finally")
                        }
                    }
                    try {
                        client.shell("pm enable --user $userId com.google.android.gms")
                    } catch (e: Exception) {
                        Timber.tag(TAG).e(e, "Failed to re-enable com.google.android.gms in finally")
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
