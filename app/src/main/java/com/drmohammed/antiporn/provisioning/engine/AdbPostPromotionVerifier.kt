package com.drmohammed.antiporn.provisioning.engine

import android.content.Context
import android.os.Build
import android.provider.Settings
import androidx.annotation.VisibleForTesting
import com.drmohammed.antiporn.R
import com.drmohammed.antiporn.accessibility.control.AccessibilitySelfActivator
import com.drmohammed.antiporn.core.engine.ProtectionEngine
import com.drmohammed.antiporn.core.engine.SystemAppsHealingManager
import com.drmohammed.antiporn.provisioning.engine.AdbShellValidation.combinedOutput
import com.drmohammed.antiporn.shields.DnsShield
import com.drmohammed.antiporn.telemetry.BlackBoxRecorder
import com.drmohammed.antiporn.telemetry.SafeDpmGateway
import io.github.rhythmcache.adb.AdbClient
import io.github.rhythmcache.adb.ShellResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * Mandatory post-promotion verification gate executed before disabling Wireless ADB.
 *
 * Ensures Device Owner activation actually took effect, repairs `user_setup_complete`,
 * heals any stranded frozen packages, verifies `WRITE_SECURE_SETTINGS` and Private DNS,
 * and triggers initial protection enforcement before closing `adb_wifi_enabled`.
 */
internal object AdbPostPromotionVerifier {

    private const val TAG = "AntiPorn_PostVerify"
    private const val DO_POLL_ATTEMPTS = 10
    private const val DO_POLL_INTERVAL_MS = 200L

    /**
     * Runs the 7-step post-promotion verification gate.
     * Wireless ADB is disabled ONLY after steps 1–6 pass (including verified healing of all
     * tracked frozen packages); if verification fails, Wireless ADB remains open for safe retry.
     */
    suspend fun verifyAndFinalize(
        context: Context,
        client: AdbClient,
        userId: Int,
        didSetupBypass: Boolean,
        reconnectProvider: (suspend () -> AdbClient?)? = null
    ): PromotionResult {
        var activeClient = client
        val ownedClients = mutableListOf<AdbClient>()
        val resilientShell: suspend (String) -> ShellResult = { cmd ->
            try {
                activeClient.shell(cmd)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                val fresh = reconnectProvider?.invoke() ?: throw e
                ownedClients.add(fresh)
                activeClient = fresh
                fresh.shell(cmd)
            }
        }
        return try {
            verifyAndFinalizeWithShell(
                context = context,
                userId = userId,
                didSetupBypass = didSetupBypass,
                shellExecutor = resilientShell,
                privateDnsProvisioner = { appCtx -> AdbPromotionExecutor.provisionPrivateDnsViaAdbWithShell(appCtx, resilientShell) }
            )
        } finally {
            withContext(NonCancellable) {
                ownedClients.forEach { runCatching { it.close() } }
            }
        }
    }

    @VisibleForTesting
    internal suspend fun verifyAndFinalizeWithShell(
        context: Context,
        userId: Int,
        didSetupBypass: Boolean,
        shellExecutor: suspend (String) -> ShellResult,
        isDeviceOwnerChecker: (Context) -> Boolean = { SafeDpmGateway.isDeviceOwner(it) },
        canWriteSecureSettingsChecker: (Context) -> Boolean = { AccessibilitySelfActivator.canWriteSecureSettings(it) },
        privateDnsProvisioner: suspend (Context) -> Unit = {},
        applyEnforcement: suspend (Context) -> Unit = { appCtx ->
            withContext(Dispatchers.IO) {
                try {
                    ProtectionEngine.applyAllSync(appCtx)
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    Timber.tag(TAG).w(e, "Post-promotion ProtectionEngine.applyAllSync encountered non-fatal error")
                }
                try {
                    com.drmohammed.antiporn.data.repository.PolicyRepository.getInstance(appCtx).startFirestoreSync()
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    Timber.tag(TAG).w(e, "Post-promotion PolicyRepository.startFirestoreSync encountered non-fatal error")
                }
                try {
                    com.drmohammed.antiporn.data.repository.ApprovalRepository.getInstance(appCtx).syncZeroTrustBaselineAndReconcileAsync()
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    Timber.tag(TAG).w(e, "Post-promotion syncZeroTrustBaselineAndReconcileAsync encountered non-fatal error")
                }
                try {
                    SystemAppsHealingManager.healPreInstalledStoresSafely(appCtx, force = true)
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    Timber.tag(TAG).w(e, "Post-promotion healPreInstalledStoresSafely encountered non-fatal error")
                }
            }
        },
        pollIntervalMs: Long = DO_POLL_INTERVAL_MS,
        sdkInt: Int = Build.VERSION.SDK_INT
    ): PromotionResult {
        val appContext = context.applicationContext ?: context

        // 1. Poll SafeDpmGateway.isDeviceOwner up to 2,000ms (10 x 200ms)
        if (!awaitDeviceOwnerConfirmed(appContext, isDeviceOwnerChecker, pollIntervalMs)) {
            Timber.tag(TAG).e("Post-DO verification failed: isDeviceOwner=false after ${DO_POLL_ATTEMPTS * DO_POLL_INTERVAL_MS}ms")
            BlackBoxRecorder.recordPairing(
                appContext,
                "DO_POST_VERIFY_FAILED",
                isSuccess = false,
                error = "isDeviceOwner=false after 2000ms"
            )
            val failureMessage = appContext.safeGetString(R.string.provisioning_error_post_verify_failed)
                ?: "Post-promotion Device Owner verification failed."
            return PromotionResult.Failure(failureMessage)
        }

        try {
            com.drmohammed.antiporn.data.repository.ApprovalRepository
                .getInstance(appContext)
                .recordDeviceOwnerEnabledTimestamp()
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Timber.tag(TAG).w(e, "Failed recording DO enabled timestamp in post-verify")
        }

        // 2. Restore and verify user_setup_complete == "1" if setup bypass ran
        if (didSetupBypass) {
            verifyAndRepairSetupComplete(appContext, shellExecutor, userId)
        }

        // 3. Ensure no tracked frozen packages remain disabled
        val allFrozenPackagesHealed = verifyAndHealFrozenPackages(appContext, shellExecutor)

        // 4. Re-check WRITE_SECURE_SETTINGS and Private DNS
        verifySecureSettingsAndPrivateDns(appContext, shellExecutor, canWriteSecureSettingsChecker, privateDnsProvisioner, sdkInt)

        // 5. Apply all shields and heal pre-installed OEM stores on Dispatchers.IO
        applyEnforcement(appContext)

        if (!allFrozenPackagesHealed) {
            Timber.tag(TAG).e("Post-promotion gate keeping Wireless ADB open: stranded frozen packages still remain.")
            BlackBoxRecorder.recordPairing(
                appContext,
                "DO_POST_VERIFY_STRANDED_REMAIN",
                isSuccess = false,
                error = "userId=$userId, ledgerRemaining=true"
            )
            val healFailureMessage = appContext.safeGetString(R.string.provisioning_error_heal_failed)
                ?: "Could not re-enable stranded apps via Wireless ADB."
            return PromotionResult.Failure(healFailureMessage)
        }

        // 6. Record verification success BEFORE disabling wireless ADB
        BlackBoxRecorder.recordPairing(
            appContext,
            "DO_POST_VERIFY_SUCCESS",
            isSuccess = true,
            details = "userId=$userId, didSetupBypass=$didSetupBypass"
        )

        // 7. Absolute final step: disable Wireless ADB
        disableWirelessAdbSilently(shellExecutor)
        return PromotionResult.Success
    }

    private suspend fun awaitDeviceOwnerConfirmed(
        appContext: Context,
        isDeviceOwnerChecker: (Context) -> Boolean,
        pollIntervalMs: Long
    ): Boolean {
        repeat(DO_POLL_ATTEMPTS) { attempt ->
            if (isDeviceOwnerChecker(appContext)) {
                return true
            }
            if (attempt < DO_POLL_ATTEMPTS - 1 && pollIntervalMs > 0L) {
                delay(pollIntervalMs)
            }
        }
        return isDeviceOwnerChecker(appContext)
    }

    private suspend fun verifyAndRepairSetupComplete(
        appContext: Context,
        shellExecutor: suspend (String) -> ShellResult,
        userId: Int
    ) {
        try {
            val current = shellExecutor("settings --user $userId get secure user_setup_complete").stdoutText.trim()
            if (current == "1") return

            Timber.tag(TAG).w("user_setup_complete was '$current' after promotion; restoring to 1")
            try {
                shellExecutor("settings --user $userId put secure user_setup_complete 1")
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Timber.tag(TAG).w(e, "ADB restore of user_setup_complete failed")
            }
            try {
                appContext.contentResolver?.let { resolver ->
                    Settings.Secure.putInt(resolver, "user_setup_complete", 1)
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Timber.tag(TAG).w(e, "ContentResolver restore of user_setup_complete failed")
            }

            val rechecked = shellExecutor("settings --user $userId get secure user_setup_complete").stdoutText.trim()
            if (rechecked != "1") {
                Timber.tag(TAG).e("user_setup_complete re-verification returned '$rechecked'")
                BlackBoxRecorder.recordPairing(
                    appContext,
                    "POST_VERIFY_SETUP_COMPLETE_REPAIR",
                    isSuccess = false,
                    error = "before=$current, after=$rechecked"
                )
            } else {
                BlackBoxRecorder.recordPairing(
                    appContext,
                    "POST_VERIFY_SETUP_COMPLETE_REPAIR",
                    isSuccess = true,
                    details = "repaired from '$current' to '1'"
                )
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Timber.tag(TAG).w(e, "Failed verifying user_setup_complete")
        }
    }

    private suspend fun verifyAndHealFrozenPackages(
        appContext: Context,
        shellExecutor: suspend (String) -> ShellResult
    ): Boolean {
        return try {
            val stillDisabled = ProvisioningFreezeRecoveryStore.findStillDisabledTrackedPackages(appContext)
            if (stillDisabled.isNotEmpty()) {
                Timber.tag(TAG).w("Found ${stillDisabled.size} still-disabled tracked package(s); healing via ADB")
                AdbPackageFreezeManager.healStrandedPackagesWithShell(appContext, shellExecutor)
            }
            ProvisioningFreezeRecoveryStore.getPendingLedger(appContext) == null
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Timber.tag(TAG).w(e, "Failed healing stranded packages in post-verify")
            false
        }
    }

    private suspend fun verifySecureSettingsAndPrivateDns(
        appContext: Context,
        shellExecutor: suspend (String) -> ShellResult,
        canWriteSecureSettingsChecker: (Context) -> Boolean,
        privateDnsProvisioner: suspend (Context) -> Unit,
        sdkInt: Int
    ) {
        if (!canWriteSecureSettingsChecker(appContext)) {
            try {
                val res = shellExecutor("pm grant ${appContext.packageName} android.permission.WRITE_SECURE_SETTINGS")
                val granted = canWriteSecureSettingsChecker(appContext)
                BlackBoxRecorder.recordPairing(
                    appContext,
                    "ADB_WRITE_SECURE_SETTINGS_RETRY",
                    isSuccess = granted,
                    details = res.combinedOutput().take(200).ifBlank { "granted=$granted" }
                )
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Timber.tag(TAG).w(e, "Retry pm grant WRITE_SECURE_SETTINGS failed")
            }
        }

        if (sdkInt >= Build.VERSION_CODES.Q) {
            val isDnsConfigured = try {
                val resolver = appContext.contentResolver
                if (resolver == null) {
                    Timber.tag(TAG).w("ContentResolver unavailable while checking Private DNS in post-verify")
                    false
                } else {
                    val mode = Settings.Global.getString(resolver, "private_dns_mode")
                    val specifier = Settings.Global.getString(resolver, "private_dns_specifier")
                    val expectedHost = DeviceOwnerPromoter.sanitizeDnsHostForAdb(DnsShield.getEffectiveDnsHost(appContext))
                    mode.equals("hostname", ignoreCase = true) && specifier.equals(expectedHost, ignoreCase = true)
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Timber.tag(TAG).w(e, "Failed checking Private DNS configuration in post-verify")
                false
            }
            if (!isDnsConfigured) {
                privateDnsProvisioner(appContext)
            }
        }
    }

    private suspend fun disableWirelessAdbSilently(shellExecutor: suspend (String) -> ShellResult) {
        try {
            Timber.tag(TAG).i("Disabling adb_wifi_enabled for security cleanup (leaving adb_enabled intact)...")
            shellExecutor("settings put global adb_wifi_enabled 0")
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Timber.tag(TAG).w(e, "Failed disabling adb_wifi_enabled")
        }
    }
}
