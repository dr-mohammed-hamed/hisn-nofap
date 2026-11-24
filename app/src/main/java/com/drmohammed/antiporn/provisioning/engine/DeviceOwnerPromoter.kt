package com.drmohammed.antiporn.provisioning.engine

import android.accounts.AccountManager
import android.content.ComponentName
import android.content.Context
import android.os.SystemClock
import androidx.annotation.VisibleForTesting
import com.drmohammed.antiporn.AdminReceiver
import com.drmohammed.antiporn.R
import com.drmohammed.antiporn.accessibility.control.AccessibilitySelfActivator
import com.drmohammed.antiporn.core.model.DevicePolicyBundle
import com.drmohammed.antiporn.provisioning.service.AdbPairingNotificationService
import com.drmohammed.antiporn.shields.DnsShield
import com.drmohammed.antiporn.telemetry.BlackBoxRecorder
import io.github.rhythmcache.adb.AdbClient
import io.github.rhythmcache.adb.FileKeyProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import java.io.File

/**
 * Result sealed class for the Device Owner promotion workflow.
 */
sealed class PromotionResult {
    data object Success : PromotionResult()
    data class Failure(val message: String, val cause: Throwable? = null) : PromotionResult()
}

/**
 * Executes on-device Device Owner promotion via local wireless ADB.
 *
 * Sequence:
 * 1. Loads/generates persistent ADB RSA key pair in the app's private files directory.
 * 2. Executes SPAKE2 password-authenticated TLS 1.3 pairing with localhost (127.0.0.1:pairingPort).
 * 3. Connects over TLS to localhost (127.0.0.1:connectPort).
 * 4. Dispatches: `dpm set-device-owner com.drmohammed.antiporn/.AdminReceiver`
 * 5. Evaluates output for "Success".
 */
object DeviceOwnerPromoter {

    private const val TAG = "AntiPorn_Promoter"
    private const val LOCALHOST = "127.0.0.1"
    private const val MAX_DNS_HOST_LENGTH = 253
    private val DNS_LABEL_REGEX = Regex("^[a-zA-Z0-9]([a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?$")
    private val promotionMutex = Mutex()

    @VisibleForTesting
    internal fun sanitizeDnsHostForAdb(rawHost: String?): String {
        val candidate = rawHost?.trim().orEmpty()
        if (candidate.isBlank() ||
            candidate.length > MAX_DNS_HOST_LENGTH ||
            !candidate.contains('.') ||
            candidate.contains("..") ||
            candidate.startsWith('.') ||
            candidate.endsWith('.')
        ) {
            return DevicePolicyBundle.CLOUDFLARE_FAMILY_DNS_HOST
        }
        val labels = candidate.split('.')
        val allLabelsValid = labels.size >= 2 && labels.all { DNS_LABEL_REGEX.matches(it) }
        return if (allLabelsValid) candidate else DevicePolicyBundle.CLOUDFLARE_FAMILY_DNS_HOST
    }

    suspend fun pairAndPromote(
        context: Context,
        pairingPort: Int,
        connectPort: Int,
        pairingCode: String
    ): PromotionResult = withContext(Dispatchers.IO) {
        if (!promotionMutex.tryLock()) {
            return@withContext PromotionResult.Failure("Promotion already in progress.")
        }
        val appContext = context.applicationContext
        val startTime = SystemClock.elapsedRealtime()
        try {
            if (OemProvisioningHelper.requiresPreActivationDangerAck(appContext) && !ProvisioningAckStore.hasValidAck(appContext)) {
                val err = appContext.getString(R.string.provisioning_error_ack_required)
                Timber.tag(TAG).e("Promotion blocked by defense-in-depth: Danger acknowledgment required but missing or expired")
                BlackBoxRecorder.recordPairing(
                    appContext,
                    "PROMOTION_BLOCKED_NO_ACK",
                    isSuccess = false,
                    error = err
                )
                return@withContext PromotionResult.Failure(err)
            }
            withTimeout(30_000L) {
                val keyFile = File(appContext.filesDir, "antiporn_adb.key")
                val keyProvider = FileKeyProvider(keyFile)

                BlackBoxRecorder.recordPairing(
                    appContext,
                    "PAIR_TLS_START",
                    isSuccess = true,
                    details = "pairingPort=$pairingPort"
                )

                Timber.tag(TAG).i("Pairing via SPAKE2 TLS to $LOCALHOST:$pairingPort with code length ${pairingCode.length}...")
                AdbClient.pairTls(LOCALHOST, pairingPort, pairingCode.trim(), keyProvider)
                val pairDuration = SystemClock.elapsedRealtime() - startTime
                Timber.tag(TAG).i("Pairing handshake successful in ${pairDuration}ms! Waiting 500ms for adbd key persistence...")
                BlackBoxRecorder.recordPairing(
                    appContext,
                    "PAIR_TLS_SUCCESS",
                    isSuccess = true,
                    executionTimeMs = pairDuration
                )
                delay(500)

                // Resolve connectPort if not yet discovered
                var effectiveConnectPort = connectPort
                if (effectiveConnectPort <= 0) {
                    effectiveConnectPort = AdbPairingNotificationService.capturedConnectPort
                }
                if (effectiveConnectPort <= 0) {
                    Timber.tag(TAG).i("Connect port is 0 after pairing, polling up to 8s for mDNS resolution...")
                    val pollStart = SystemClock.elapsedRealtime()
                    while (effectiveConnectPort <= 0 && SystemClock.elapsedRealtime() - pollStart < 8000) {
                        delay(250)
                        effectiveConnectPort = AdbPairingNotificationService.capturedConnectPort
                    }
                }

                if (effectiveConnectPort <= 0) {
                    val timeoutErr = "Connect port discovery timed out after successful pairing"
                    Timber.tag(TAG).e(timeoutErr)
                    BlackBoxRecorder.recordPairing(
                        appContext,
                        "CONNECT_PORT_TIMEOUT",
                        isSuccess = false,
                        error = timeoutErr
                    )
                    return@withTimeout PromotionResult.Failure(timeoutErr)
                }

                Timber.tag(TAG).i("Connecting to main connectPort: $effectiveConnectPort...")
                val adminComponent = ComponentName(appContext, AdminReceiver::class.java).flattenToShortString()

                val targetHosts = listOfNotNull(
                    LOCALHOST,
                    AdbMdnsDiscovery.lastDiscoveredHost?.takeIf { it != LOCALHOST && it.isNotBlank() }
                )

                var lastException: Throwable? = null
                var result: PromotionResult? = null

                hostLoop@ for (host in targetHosts) {
                    for (attempt in 1..2) {
                        try {
                            Timber.tag(TAG).i("Connecting to $host:$effectiveConnectPort (attempt $attempt)...")
                            AdbClient.connectTls(host, effectiveConnectPort, keyProvider).use { client ->
                                result = executeZeroAccountPromotion(appContext, client, adminComponent, 0)
                            }
                            if (result is PromotionResult.Success) {
                                break@hostLoop
                            }
                        } catch (t: Throwable) {
                            if (t is CancellationException) throw t
                            lastException = t
                            Timber.tag(TAG).w("Connect attempt $attempt to $host failed: ${t.message}")
                            if (attempt < 2) {
                                delay(500)
                            }
                        }
                    }
                }

                val finalResult = result ?: throw (lastException ?: IllegalStateException("Failed to connect over TLS"))
                val totalDuration = SystemClock.elapsedRealtime() - startTime
                if (finalResult is PromotionResult.Success) {
                    ProvisioningAckStore.clearAck(appContext)
                    BlackBoxRecorder.recordPairing(
                        appContext,
                        "PROMOTION_SUCCESS",
                        isSuccess = true,
                        executionTimeMs = totalDuration
                    )
                } else if (finalResult is PromotionResult.Failure) {
                    BlackBoxRecorder.recordPairing(
                        appContext,
                        "PROMOTION_FAILURE",
                        isSuccess = false,
                        executionTimeMs = totalDuration,
                        error = finalResult.message
                    )
                }
                finalResult
            }
        } catch (te: TimeoutCancellationException) {
            val totalDuration = SystemClock.elapsedRealtime() - startTime
            val errorMsg = "Promotion timed out after 30 seconds"
            Timber.tag(TAG).e(te, errorMsg)
            BlackBoxRecorder.recordPairing(
                appContext,
                "PROMOTION_TIMEOUT",
                isSuccess = false,
                executionTimeMs = totalDuration,
                error = errorMsg
            )
            PromotionResult.Failure(errorMsg, te)
        } catch (t: Throwable) {
            Timber.tag(TAG).e(t, "Failed during pairAndPromote")
            val errorMsg = "${t.javaClass.simpleName}: ${t.message ?: "Unknown error during wireless promotion"}"
            BlackBoxRecorder.recordPairing(
                appContext,
                "PAIR_AND_PROMOTE_EXCEPTION",
                isSuccess = false,
                error = errorMsg
            )
            PromotionResult.Failure(errorMsg, t)
        } finally {
            promotionMutex.unlock()
        }
    }

    suspend fun promoteIfAlreadyPaired(
        context: Context,
        connectPort: Int
    ): PromotionResult = withContext(Dispatchers.IO) {
        if (!promotionMutex.tryLock()) {
            return@withContext PromotionResult.Failure("Promotion already in progress.")
        }
        val appContext = context.applicationContext
        val startTime = SystemClock.elapsedRealtime()
        try {
            if (OemProvisioningHelper.requiresPreActivationDangerAck(appContext) && !ProvisioningAckStore.hasValidAck(appContext)) {
                val err = appContext.getString(R.string.provisioning_error_ack_required)
                Timber.tag(TAG).e("Promotion blocked by defense-in-depth: Danger acknowledgment required but missing or expired")
                BlackBoxRecorder.recordPairing(
                    appContext,
                    "PROMOTION_BLOCKED_NO_ACK",
                    isSuccess = false,
                    error = err
                )
                return@withContext PromotionResult.Failure(err)
            }
            withTimeout(30_000L) {
                val keyFile = File(appContext.filesDir, "antiporn_adb.key")
                if (!keyFile.exists()) {
                    return@withTimeout PromotionResult.Failure("Device has not been paired yet.")
                }
                val keyProvider = FileKeyProvider(keyFile)
                val adminComponent = ComponentName(appContext, AdminReceiver::class.java).flattenToShortString()

                val result = AdbClient.connectTls(LOCALHOST, connectPort, keyProvider).use { client ->
                    executeZeroAccountPromotion(appContext, client, adminComponent, 0)
                }
                if (result is PromotionResult.Success) {
                    ProvisioningAckStore.clearAck(appContext)
                }
                result
            }
        } catch (te: TimeoutCancellationException) {
            val totalDuration = SystemClock.elapsedRealtime() - startTime
            val errorMsg = "Promotion timed out after 30 seconds"
            Timber.tag(TAG).e(te, errorMsg)
            BlackBoxRecorder.recordPairing(
                appContext,
                "PROMOTION_TIMEOUT",
                isSuccess = false,
                executionTimeMs = totalDuration,
                error = errorMsg
            )
            PromotionResult.Failure(errorMsg, te)
        } catch (t: Throwable) {
            Timber.tag(TAG).e(t, "Failed during promoteIfAlreadyPaired")
            val errorMsg = "${t.javaClass.simpleName}: ${t.message ?: "Failed connecting to already paired port"}"
            BlackBoxRecorder.recordPairing(
                appContext,
                "PAIR_AND_PROMOTE_EXCEPTION",
                isSuccess = false,
                error = errorMsg
            )
            PromotionResult.Failure(errorMsg, t)
        } finally {
            promotionMutex.unlock()
        }
    }

    /**
     * Executes the zero-account bypass promotion sequence based on Android SDK level.
     */
    suspend fun executeZeroAccountPromotion(
        context: Context,
        client: AdbClient,
        adminComponent: String,
        userId: Int = 0
    ): PromotionResult {
        val appContext = context.applicationContext
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

        // Provision Private DNS via ADB before Device Owner promotion / restriction lock and before disabling wireless ADB
        provisionPrivateDnsViaAdb(appContext, client)

        val sdkVerStr = client.shell("getprop ro.build.version.sdk").stdoutText.trim()
        val sdkVersion = sdkVerStr.toIntOrNull() ?: android.os.Build.VERSION.SDK_INT
        Timber.tag(TAG).i("Executing zero-account promotion on Android SDK $sdkVersion for user $userId")

        val result = if (sdkVersion >= 31) {
            val primaryResult = executeSetupBypassPromotion(context, client, adminComponent, userId)
            if (primaryResult is PromotionResult.Success) {
                disableWirelessAdbSilently(client)
                primaryResult
            } else {
                Timber.tag(TAG).w("Setup bypass failed: ${(primaryResult as PromotionResult.Failure).message}. Attempting fallback to package freeze...")
                val fallbackResult = executePackageFreezePromotion(context, client, adminComponent, userId)
                if (fallbackResult is PromotionResult.Success) {
                    disableWirelessAdbSilently(client)
                    fallbackResult
                } else {
                    fallbackResult
                }
            }
        } else {
            val legacyResult = executePackageFreezePromotion(context, client, adminComponent, userId)
            if (legacyResult is PromotionResult.Success) {
                disableWirelessAdbSilently(client)
            }
            legacyResult
        }

        return result
    }

    suspend fun executeZeroAccountPromotion(
        client: AdbClient,
        adminComponent: String,
        userId: Int = 0
    ): PromotionResult {
        val sdkVerStr = client.shell("getprop ro.build.version.sdk").stdoutText.trim()
        val sdkVersion = sdkVerStr.toIntOrNull() ?: android.os.Build.VERSION.SDK_INT
        return if (sdkVersion >= 31) {
            val primaryResult = executeSetupBypassPromotion(null, client, adminComponent, userId)
            if (primaryResult is PromotionResult.Success) {
                disableWirelessAdbSilently(client)
                primaryResult
            } else {
                Timber.tag(TAG).w("Setup bypass failed without context. Attempting fallback to package freeze...")
                val fallbackResult = executePackageFreezePromotion(null, client, adminComponent, userId)
                if (fallbackResult is PromotionResult.Success) {
                    disableWirelessAdbSilently(client)
                    fallbackResult
                } else {
                    fallbackResult
                }
            }
        } else {
            val result = executePackageFreezePromotion(null, client, adminComponent, userId)
            if (result is PromotionResult.Success) {
                disableWirelessAdbSilently(client)
            }
            result
        }
    }

    private suspend fun disableWirelessAdbSilently(client: AdbClient) {
        try {
            Timber.tag(TAG).i("Disabling adb_wifi_enabled for security cleanup (leaving adb_enabled intact)...")
            client.shell("settings put global adb_wifi_enabled 0")
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Failed disabling adb_wifi_enabled after promotion")
        }
    }

    /**
     * Provisions Private DNS directly via ADB Shell before Device Owner promotion locks
     * `DISALLOW_CONFIG_PRIVATE_DNS` and before wireless ADB is disabled.
     *
     * CRITICAL INVARIANT: Never sets `private_dns_mode=hostname` unless `private_dns_specifier`
     * has been verified via read-back to match the validated target hostname, preventing any
     * risk of breaking device internet connectivity.
     */
    private suspend fun provisionPrivateDnsViaAdb(appContext: Context, client: AdbClient) {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q) return

        var targetHost = DevicePolicyBundle.CLOUDFLARE_FAMILY_DNS_HOST
        try {
            val rawHost = try {
                DnsShield.getEffectiveDnsHost(appContext).trim()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Timber.tag(TAG).w(e, "Failed reading effective DNS host; falling back to default")
                ""
            }
            val safeHost = sanitizeDnsHostForAdb(rawHost)
            targetHost = safeHost

            // Step 1: Write private_dns_specifier
            val specRes = client.shell("settings put global private_dns_specifier $safeHost")
            val specOutput = (specRes.stdoutText + "\n" + specRes.stderrText).trim()
            if (hasShellError(specOutput)) {
                val err = "specifier write failed: ${sanitizeShellOutput(specOutput)}"
                Timber.tag(TAG).w("ADB Private DNS provisioning aborted at Step 1 ($safeHost): $err")
                BlackBoxRecorder.recordPairing(
                    appContext,
                    "ADB_PRIVATE_DNS_PROVISION",
                    isSuccess = false,
                    details = "host=$safeHost",
                    error = err
                )
                return
            }

            // Step 2: Strict read-back verification before enabling hostname mode
            val verifySpecRes = client.shell("settings get global private_dns_specifier")
            val verifySpec = verifySpecRes.stdoutText.trim()
            if (!verifySpec.equals(safeHost, ignoreCase = true)) {
                val err = "specifier read-back mismatch: expected=$safeHost, actual=${sanitizeShellOutput(verifySpec)}"
                Timber.tag(TAG).w("ADB Private DNS provisioning aborted at Step 2: $err")
                BlackBoxRecorder.recordPairing(
                    appContext,
                    "ADB_PRIVATE_DNS_PROVISION",
                    isSuccess = false,
                    details = "host=$safeHost",
                    error = err
                )
                return
            }

            // Step 3: Activate hostname mode and verify read-back
            val modeRes = client.shell("settings put global private_dns_mode hostname")
            val modeOutput = (modeRes.stdoutText + "\n" + modeRes.stderrText).trim()
            if (hasShellError(modeOutput)) {
                val err = "mode write failed: ${sanitizeShellOutput(modeOutput)}"
                Timber.tag(TAG).w("ADB Private DNS provisioning failed at Step 3 ($safeHost): $err")
                BlackBoxRecorder.recordPairing(
                    appContext,
                    "ADB_PRIVATE_DNS_PROVISION",
                    isSuccess = false,
                    details = "host=$safeHost",
                    error = err
                )
                return
            }

            val verifyMode = client.shell("settings get global private_dns_mode").stdoutText.trim()
            val modeVerified = verifyMode.equals("hostname", ignoreCase = true)
            if (modeVerified) {
                Timber.tag(TAG).i("Successfully provisioned Private DNS via ADB (mode=hostname, specifier=$safeHost)")
                BlackBoxRecorder.recordPairing(
                    appContext,
                    "ADB_PRIVATE_DNS_PROVISION",
                    isSuccess = true,
                    details = "host=$safeHost, mode=hostname"
                )
            } else {
                val err = "mode read-back mismatch: expected=hostname, actual=${sanitizeShellOutput(verifyMode)}"
                Timber.tag(TAG).w("ADB Private DNS mode verification failed ($safeHost): $err")
                BlackBoxRecorder.recordPairing(
                    appContext,
                    "ADB_PRIVATE_DNS_PROVISION",
                    isSuccess = false,
                    details = "host=$safeHost",
                    error = err
                )
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            val err = sanitizeShellOutput("${e.javaClass.simpleName}: ${e.message ?: "Unknown error"}")
            Timber.tag(TAG).w(e, "Failed provisioning Private DNS via ADB for host=$targetHost")
            BlackBoxRecorder.recordPairing(
                appContext,
                "ADB_PRIVATE_DNS_PROVISION",
                isSuccess = false,
                details = "host=$targetHost",
                error = err
            )
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

        // Push embedded dex helper to /data/local/tmp
        client.shell("echo '$HELPER_DEX_BASE64' | base64 -d > $dexPath")

        var isBypassed = false
        try {
            // 1. Temporarily clear user_setup_complete
            val setupRes = client.shell("settings --user $userId put secure user_setup_complete 0")
            isBypassed = true

            if (setupRes.stdoutText.contains("SecurityException", ignoreCase = true) ||
                setupRes.stderrText.contains("SecurityException", ignoreCase = true) ||
                setupRes.stderrText.contains("Permission Denial", ignoreCase = true)) {
                val errorMsg = if (OemProvisioningHelper.isRealmeOrOppo()) {
                    context?.getString(R.string.provisioning_error_realme_permission_monitoring)
                } else {
                    context?.getString(R.string.provisioning_error_xiaomi_security_settings)
                } ?: setupRes.stderrText.ifBlank { setupRes.stdoutText }
                return PromotionResult.Failure(errorMsg)
            }

            // 2. Refresh DevicePolicyManagerService cache via our helper
            client.shell("CLASSPATH=$dexPath app_process /system/bin SetupStateHelper $userId")

            // 3. Dispatch set-device-owner command
            var cmd = "dpm set-device-owner --user $userId $adminComponent"
            var doResult = client.shell(cmd)
            var output = (doResult.stdoutText + "\n" + doResult.stderrText).trim()

            if (output.contains("unknown option: --user", ignoreCase = true) ||
                output.contains("illegal option", ignoreCase = true)) {
                cmd = "dpm set-device-owner $adminComponent"
                doResult = client.shell(cmd)
                output = (doResult.stdoutText + "\n" + doResult.stderrText).trim()
            }

            Timber.tag(TAG).i("DPM bypass execution output: $output")

            val isSuccess = (output.contains("Success", ignoreCase = true) || output.contains("device owner set", ignoreCase = true)) &&
                !output.contains("error", ignoreCase = true) && !output.contains("exception", ignoreCase = true)

            return if (isSuccess) {
                PromotionResult.Success
            } else {
                PromotionResult.Failure(formatFriendlyError(context, output))
            }
        } finally {
            // 4. Guaranteed Restoration in finally (runs safely even if parent coroutine was cancelled/timed out)
            withContext(NonCancellable) {
                withTimeoutOrNull(5_000L) {
                    if (isBypassed) {
                        try {
                            client.shell("settings --user $userId put secure user_setup_complete 1")
                        } catch (e: Exception) {
                            Timber.tag(TAG).e(e, "Failed to restore user_setup_complete in finally via ADB")
                        }
                        // Local ContentResolver Fallback: Guarantees user_setup_complete=1 even if ADB disconnected!
                        try {
                            context?.let { ctx ->
                                android.provider.Settings.Secure.putInt(ctx.contentResolver, "user_setup_complete", 1)
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
                AccountManager.get(ctx.applicationContext).authenticatorTypes
                    .associate { it.type to it.packageName }
            } catch (e: Exception) {
                Timber.tag(TAG).w(e, "Failed to retrieve authenticatorTypes from AccountManager")
                emptyMap()
            }
        } ?: emptyMap()

        val packagesToDisable = mutableSetOf<String>()
        accountTypes.forEach { type ->
            authenticatorMap[type]?.let { pkg ->
                packagesToDisable.add(pkg)
            } ?: run {
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

            // Dispatch atomic shell script with trapped EXIT handler for kernel-level re-enablement even on disconnect
            val atomicCmd = "sh -c 'trap \"$trapScript\" EXIT; $disableScript; sleep 1; dpm set-device-owner --user $userId $adminComponent'"
            var doResult = client.shell(atomicCmd)
            var output = (doResult.stdoutText + "\n" + doResult.stderrText).trim()

            if (output.contains("unknown option: --user", ignoreCase = true) ||
                output.contains("illegal option", ignoreCase = true)) {
                val legacyAtomicCmd = "sh -c 'trap \"$trapScript\" EXIT; $disableScript; sleep 1; dpm set-device-owner $adminComponent'"
                doResult = client.shell(legacyAtomicCmd)
                output = (doResult.stdoutText + "\n" + doResult.stderrText).trim()
            }

            Timber.tag(TAG).i("DPM legacy execution output: $output")

            val isSuccess = (output.contains("Success", ignoreCase = true) || output.contains("device owner set", ignoreCase = true)) &&
                !output.contains("error", ignoreCase = true) && !output.contains("exception", ignoreCase = true)

            return if (isSuccess) {
                PromotionResult.Success
            } else {
                PromotionResult.Failure(formatFriendlyError(context, output))
            }
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
