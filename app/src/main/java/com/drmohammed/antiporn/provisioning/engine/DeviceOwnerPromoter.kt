package com.drmohammed.antiporn.provisioning.engine

import android.content.ComponentName
import android.content.Context
import android.os.SystemClock
import com.drmohammed.antiporn.AdminReceiver
import com.drmohammed.antiporn.R
import com.drmohammed.antiporn.core.model.DevicePolicyBundle
import com.drmohammed.antiporn.provisioning.service.AdbPairingNotificationService
import com.drmohammed.antiporn.telemetry.BlackBoxRecorder
import io.github.rhythmcache.adb.AdbClient
import io.github.rhythmcache.adb.FileKeyProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import timber.log.Timber
import java.io.File

/**
 * OEM-specific ADB permission toggle required before Device Owner promotion can proceed.
 */
enum class OemPermissionType { XIAOMI_SECURITY_SETTINGS, REALME_OPPO_PERMISSION_MONITORING }

/**
 * Result sealed class for the Device Owner promotion workflow.
 */
sealed class PromotionResult {
    data object Success : PromotionResult()
    data class SecondaryUsersBlocking(val users: List<BlockingSecondaryUser>) : PromotionResult()
    data class OemPermissionRequired(val oemType: OemPermissionType, val message: String) : PromotionResult()
    data class AccountsBlocking(val accountLabels: List<String>, val accountTypes: List<String>, val message: String) : PromotionResult()
    data class Failure(val message: String, val cause: Throwable? = null) : PromotionResult()
}

/**
 * Outcome of attempting ADB-based recovery for packages left disabled by a previous freeze.
 */
sealed class HealingOutcome {
    data class Healed(val healedCount: Int) : HealingOutcome()
    data object NothingToHeal : HealingOutcome()
    data object NotPaired : HealingOutcome()
    data class Failed(val message: String) : HealingOutcome()
}

/**
 * Coordinates on-device Device Owner promotion via local wireless ADB,
 * handling mutual exclusion, TLS pairing/connection retries, and explicit-consent user cleanup.
 */
object DeviceOwnerPromoter {

    private const val TAG = "AntiPorn_Promoter"
    private const val LOCALHOST = "127.0.0.1"
    private const val ADB_KEY_FILENAME = "antiporn_adb.key"
    private const val PROMOTION_TIMEOUT_MS = 65_000L
    private const val MAX_DNS_HOST_LENGTH = 253
    private val DNS_LABEL_REGEX = Regex("^[a-zA-Z0-9]([a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?$")
    private val promotionMutex = Mutex()

    val isPromotionActive: Boolean
        get() = promotionMutex.isLocked

    internal fun sanitizeDnsHostForAdb(rawHost: String?): String {
        val candidate = rawHost?.trim().orEmpty()
        if (candidate.isBlank() || candidate.length > MAX_DNS_HOST_LENGTH || !candidate.contains('.') ||
            candidate.contains("..") || candidate.startsWith('.') || candidate.endsWith('.')
        ) {
            return DevicePolicyBundle.CLOUDFLARE_FAMILY_DNS_HOST
        }
        val labels = candidate.split('.')
        return if (labels.size >= 2 && labels.all { DNS_LABEL_REGEX.matches(it) }) candidate
        else DevicePolicyBundle.CLOUDFLARE_FAMILY_DNS_HOST
    }

    private fun localizedString(context: Context, resId: Int, vararg args: Any): String? = runCatching {
        if (args.isEmpty()) context.getString(resId) else context.getString(resId, *args)
    }.getOrNull()?.takeIf { it.isNotBlank() }

    suspend fun pairAndPromote(
        context: Context,
        pairingPort: Int,
        connectPort: Int,
        pairingCode: String
    ): PromotionResult = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext ?: context
        runWithPromotionLockAndTimeout(appContext, PROMOTION_TIMEOUT_MS, true, "pairAndPromote", "PAIR_AND_PROMOTE_EXCEPTION") { startTime ->
            val keyProvider = FileKeyProvider(File(appContext.filesDir, ADB_KEY_FILENAME))
            BlackBoxRecorder.recordPairing(appContext, "PAIR_TLS_START", isSuccess = true, details = "pairingPort=$pairingPort")
            Timber.tag(TAG).i("Pairing via SPAKE2 TLS to $LOCALHOST:$pairingPort with code length ${pairingCode.length}...")
            AdbClient.pairTls(LOCALHOST, pairingPort, pairingCode.trim(), keyProvider)

            val pairDuration = SystemClock.elapsedRealtime() - startTime
            Timber.tag(TAG).i("Pairing handshake successful in ${pairDuration}ms! Waiting 500ms for adbd key persistence...")
            BlackBoxRecorder.recordPairing(appContext, "PAIR_TLS_SUCCESS", isSuccess = true, executionTimeMs = pairDuration)
            delay(500)

            val adminComponent = ComponentName(appContext, AdminReceiver::class.java).flattenToShortString()
            val finalResult = connectWithRetry(appContext, connectPort, keyProvider) { client, reconnectProvider ->
                AdbPromotionExecutor.executeZeroAccountPromotion(appContext, client, adminComponent, 0, reconnectProvider)
            }
            recordPromotionOutcome(appContext, finalResult, SystemClock.elapsedRealtime() - startTime, afterCleanup = false)
            finalResult
        }
    }

    suspend fun promoteIfAlreadyPaired(context: Context, connectPort: Int): PromotionResult = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext ?: context
        runWithPromotionLockAndTimeout(appContext, PROMOTION_TIMEOUT_MS, true, "promoteIfAlreadyPaired", "PAIR_AND_PROMOTE_EXCEPTION") { startTime ->
            val keyFile = File(appContext.filesDir, ADB_KEY_FILENAME)
            if (!keyFile.exists()) {
                return@runWithPromotionLockAndTimeout PromotionResult.Failure(
                    localizedString(appContext, R.string.provisioning_error_not_paired) ?: "Device has not been paired yet."
                )
            }
            val keyProvider = FileKeyProvider(keyFile)
            val adminComponent = ComponentName(appContext, AdminReceiver::class.java).flattenToShortString()
            val finalResult = connectWithRetry(appContext, connectPort, keyProvider) { client, reconnectProvider ->
                AdbPromotionExecutor.executeZeroAccountPromotion(appContext, client, adminComponent, 0, reconnectProvider)
            }
            recordPromotionOutcome(appContext, finalResult, SystemClock.elapsedRealtime() - startTime, afterCleanup = false)
            finalResult
        }
    }

    /**
     * Removes ONLY the secondary OS users explicitly approved by the user after inspecting
     * the live user/package list, then immediately completes Device Owner promotion over
     * the already-paired Wireless ADB connection.
     */
    suspend fun removeSecondaryUsersAndPromote(
        context: Context,
        connectPort: Int,
        approvedUserIds: Set<Int>,
        explicitUserConsentConfirmed: Boolean
    ): PromotionResult = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext ?: context
        if (!explicitUserConsentConfirmed || approvedUserIds.isEmpty() || approvedUserIds.any { it <= 0 }) {
            val err = localizedString(appContext, R.string.provisioning_error_consent_required)
                ?: "Explicit user consent for valid secondary user IDs is required."
            return@withContext PromotionResult.Failure(err)
        }
        runWithPromotionLockAndTimeout(appContext, PROMOTION_TIMEOUT_MS, false, "removeSecondaryUsersAndPromote", "REMOVE_USERS_AND_PROMOTE_EXCEPTION") { startTime ->
            val keyFile = File(appContext.filesDir, ADB_KEY_FILENAME)
            if (!keyFile.exists()) {
                return@runWithPromotionLockAndTimeout PromotionResult.Failure(
                    localizedString(appContext, R.string.provisioning_error_adb_not_paired) ?: "Wireless pairing session expired."
                )
            }
            val keyProvider = FileKeyProvider(keyFile)
            val adminComponent = ComponentName(appContext, AdminReceiver::class.java).flattenToShortString()
            val finalResult = connectWithRetry(appContext, connectPort, keyProvider) { client, reconnectProvider ->
                val outcome = AdbSecondaryUserInspector.removeApprovedUsers(appContext, client, approvedUserIds)
                if (outcome.failedIds.isNotEmpty()) {
                    return@connectWithRetry PromotionResult.Failure(
                        localizedString(appContext, R.string.provisioning_error_remove_secondary_user_failed, outcome.failedIds.joinToString(", "))
                            ?: "Could not remove user/profile (${outcome.failedIds.joinToString(", ")})"
                    )
                }
                if (outcome.remainingUsers.isNotEmpty()) return@connectWithRetry PromotionResult.SecondaryUsersBlocking(outcome.remainingUsers)
                delay(1200L)
                AdbPromotionExecutor.executeZeroAccountPromotion(appContext, client, adminComponent, 0, reconnectProvider)
            }
            recordPromotionOutcome(appContext, finalResult, SystemClock.elapsedRealtime() - startTime, afterCleanup = true)
            finalResult
        }
    }

    /**
     * Connects over the saved pairing key and heals any packages stranded in a disabled state
     * from an interrupted freeze-based promotion.
     */
    suspend fun healStrandedPackagesViaAdb(context: Context, connectPort: Int): HealingOutcome = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext ?: context
        if (!promotionMutex.tryLock()) {
            return@withContext HealingOutcome.Failed(
                localizedString(appContext, R.string.provisioning_error_already_in_progress) ?: "Promotion already in progress."
            )
        }
        try {
            val initialLedger = ProvisioningFreezeRecoveryStore.getPendingLedger(appContext)
                ?: return@withContext HealingOutcome.NothingToHeal
            val keyFile = File(appContext.filesDir, ADB_KEY_FILENAME)
            if (!keyFile.exists()) return@withContext HealingOutcome.NotPaired

            withTimeout(25_000L) {
                val port = resolveConnectPort(connectPort)
                if (port <= 0) {
                    return@withTimeout HealingOutcome.Failed(
                        localizedString(appContext, R.string.provisioning_error_heal_failed)
                            ?: "Could not connect over Wireless ADB to heal stranded packages."
                    )
                }
                val freshClient = openFreshAdbClient(port, FileKeyProvider(keyFile))
                    ?: return@withTimeout HealingOutcome.Failed(
                        localizedString(appContext, R.string.provisioning_error_heal_failed)
                            ?: "Could not connect over Wireless ADB to heal stranded packages."
                    )
                val healed = freshClient.use { client -> AdbPackageFreezeManager.healStrandedPackagesViaAdb(appContext, client) }
                val ledgerAfter = ProvisioningFreezeRecoveryStore.getPendingLedger(appContext)
                if (ledgerAfter == null) {
                    AdbShellValidation.cancelFreezeRecoveryNotification(appContext)
                    if (healed > 0) HealingOutcome.Healed(healed) else HealingOutcome.NothingToHeal
                } else {
                    val remaining = ProvisioningFreezeRecoveryStore.findStillDisabledTrackedPackages(appContext)
                    when {
                        remaining.isEmpty() -> {
                            AdbShellValidation.cancelFreezeRecoveryNotification(appContext)
                            val inferredHealed = if (healed > 0) healed else initialLedger.packages.size
                            if (inferredHealed > 0) HealingOutcome.Healed(inferredHealed) else HealingOutcome.NothingToHeal
                        }
                        else -> HealingOutcome.Failed(
                            localizedString(appContext, R.string.provisioning_error_heal_failed)
                                ?: "Could not re-enable all stranded packages."
                        )
                    }
                }
            }
        } catch (te: TimeoutCancellationException) {
            Timber.tag(TAG).w(te, "Timed out healing stranded packages via ADB")
            HealingOutcome.Failed(
                localizedString(appContext, R.string.provisioning_error_heal_failed)
                    ?: "Could not re-enable stranded apps via Wireless ADB."
            )
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            Timber.tag(TAG).e(t, "Failed healing stranded packages via ADB")
            HealingOutcome.Failed(
                localizedString(appContext, R.string.provisioning_error_heal_failed)
                    ?: "Could not re-enable stranded apps via Wireless ADB."
            )
        } finally {
            promotionMutex.unlock()
        }
    }

    private suspend fun runWithPromotionLockAndTimeout(
        appContext: Context,
        timeoutMs: Long,
        checkDangerAck: Boolean,
        operationName: String,
        exceptionEventName: String,
        block: suspend (startTime: Long) -> PromotionResult
    ): PromotionResult {
        if (!promotionMutex.tryLock()) {
            return PromotionResult.Failure(
                localizedString(appContext, R.string.provisioning_error_already_in_progress) ?: "Promotion already in progress."
            )
        }
        val startTime = SystemClock.elapsedRealtime()
        return try {
            if (checkDangerAck && OemProvisioningHelper.requiresPreActivationDangerAck(appContext) && !ProvisioningAckStore.hasValidAck(appContext)) {
                val err = appContext.getString(R.string.provisioning_error_ack_required)
                Timber.tag(TAG).e("Promotion blocked by defense-in-depth: Danger acknowledgment required but missing or expired")
                BlackBoxRecorder.recordPairing(appContext, "PROMOTION_BLOCKED_NO_ACK", isSuccess = false, error = err)
                return PromotionResult.Failure(err)
            }
            withTimeout(timeoutMs) { block(startTime) }
        } catch (te: TimeoutCancellationException) {
            val totalDuration = SystemClock.elapsedRealtime() - startTime
            val timeoutSeconds = (timeoutMs / 1000L).toInt()
            val errorMsg = localizedString(appContext, R.string.provisioning_error_timeout_seconds, timeoutSeconds)
                ?: "Promotion timed out after $timeoutSeconds seconds"
            Timber.tag(TAG).e(te, "%s", errorMsg)
            BlackBoxRecorder.recordPairing(appContext, "PROMOTION_TIMEOUT", isSuccess = false, executionTimeMs = totalDuration, error = errorMsg)
            PromotionResult.Failure(errorMsg, te)
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            Timber.tag(TAG).e(t, "Failed during $operationName")
            val fallback = localizedString(appContext, R.string.provisioning_error_unknown) ?: "Unknown error"
            val errorMsg = "${t.javaClass.simpleName}: ${t.message ?: fallback}"
            BlackBoxRecorder.recordPairing(appContext, exceptionEventName, isSuccess = false, error = errorMsg)
            PromotionResult.Failure(errorMsg, t)
        } finally {
            promotionMutex.unlock()
        }
    }

    private suspend fun resolveConnectPort(connectPort: Int): Int {
        var effectivePort = connectPort.takeIf { it > 0 } ?: AdbPairingNotificationService.capturedConnectPort
        if (effectivePort <= 0) {
            Timber.tag(TAG).i("Connect port is 0, polling up to 8s for mDNS resolution...")
            val pollStart = SystemClock.elapsedRealtime()
            while (effectivePort <= 0 && SystemClock.elapsedRealtime() - pollStart < 8000) {
                delay(250)
                effectivePort = AdbPairingNotificationService.capturedConnectPort
            }
        }
        return effectivePort
    }

    private suspend fun openFreshAdbClient(fallbackPort: Int, keyProvider: FileKeyProvider): AdbClient? {
        val port = AdbPairingNotificationService.capturedConnectPort.takeIf { it > 0 } ?: fallbackPort
        if (port <= 0) return null
        val hosts = listOfNotNull(LOCALHOST, AdbMdnsDiscovery.lastDiscoveredHost?.takeIf { it != LOCALHOST && it.isNotBlank() })
        for (host in hosts) {
            try {
                return AdbClient.connectTls(host, port, keyProvider)
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                Timber.tag(TAG).w("Reconnect TLS to $host:$port failed: ${t.message}")
            }
        }
        return null
    }

    private suspend fun connectWithRetry(
        appContext: Context,
        connectPort: Int,
        keyProvider: FileKeyProvider,
        block: suspend (AdbClient, suspend () -> AdbClient?) -> PromotionResult
    ): PromotionResult {
        var effectiveConnectPort = resolveConnectPort(connectPort)
        if (effectiveConnectPort <= 0) {
            val timeoutErr = localizedString(appContext, R.string.provisioning_error_connect_port_timeout)
                ?: "Connect port discovery timed out after successful pairing"
            Timber.tag(TAG).e(timeoutErr)
            BlackBoxRecorder.recordPairing(appContext, "CONNECT_PORT_TIMEOUT", isSuccess = false, error = timeoutErr)
            return PromotionResult.Failure(timeoutErr)
        }
        Timber.tag(TAG).i("Connecting to main connectPort: $effectiveConnectPort...")
        val targetHosts = listOfNotNull(LOCALHOST, AdbMdnsDiscovery.lastDiscoveredHost?.takeIf { it != LOCALHOST && it.isNotBlank() })
        var lastException: Throwable? = null
        var connectedClient: AdbClient? = null
        var connectedPort = effectiveConnectPort
        var isRetry = false

        hostLoop@ for (host in targetHosts) {
            for (attempt in 1..2) {
                if (isRetry) effectiveConnectPort = AdbPairingNotificationService.capturedConnectPort.takeIf { it > 0 } ?: effectiveConnectPort
                try {
                    Timber.tag(TAG).i("Connecting to $host:$effectiveConnectPort (attempt $attempt)...")
                    connectedClient = AdbClient.connectTls(host, effectiveConnectPort, keyProvider)
                    connectedPort = effectiveConnectPort
                    break@hostLoop
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    lastException = t
                    isRetry = true
                    Timber.tag(TAG).w("Connect attempt $attempt to $host:$effectiveConnectPort failed: ${t.message}")
                    if (attempt < 2) delay(500)
                }
            }
        }
        val client = connectedClient ?: throw (lastException ?: IllegalStateException("Failed to connect over TLS"))
        val reconnectProvider: suspend () -> AdbClient? = { openFreshAdbClient(connectedPort, keyProvider) }
        return client.use { block(it, reconnectProvider) }
    }

    private fun recordPromotionOutcome(appContext: Context, result: PromotionResult, durationMs: Long, afterCleanup: Boolean) {
        when (result) {
            is PromotionResult.Success -> {
                ProvisioningAckStore.clearAck(appContext)
                BlackBoxRecorder.recordPairing(appContext, if (afterCleanup) "PROMOTION_SUCCESS_AFTER_USER_CLEANUP" else "PROMOTION_SUCCESS", isSuccess = true, executionTimeMs = durationMs)
            }
            is PromotionResult.SecondaryUsersBlocking -> BlackBoxRecorder.recordPairing(
                appContext, "PROMOTION_PAUSED_SECONDARY_USERS", isSuccess = false, executionTimeMs = durationMs,
                details = "count=${result.users.size}, ids=${result.users.joinToString(",") { it.userId.toString() }}"
            )
            is PromotionResult.OemPermissionRequired -> BlackBoxRecorder.recordPairing(
                appContext, "PROMOTION_OEM_PERMISSION_REQUIRED", isSuccess = false, executionTimeMs = durationMs,
                details = "oemType=${result.oemType.name}", error = result.message
            )
            is PromotionResult.AccountsBlocking -> BlackBoxRecorder.recordPairing(
                appContext, "PROMOTION_PAUSED_ACCOUNTS_BLOCKING", isSuccess = false, executionTimeMs = durationMs,
                details = "count=${result.accountTypes.size}, types=${result.accountTypes.joinToString(",")}"
            )
            is PromotionResult.Failure -> BlackBoxRecorder.recordPairing(
                appContext, if (afterCleanup) "PROMOTION_FAILURE_AFTER_USER_CLEANUP" else "PROMOTION_FAILURE",
                isSuccess = false, executionTimeMs = durationMs, error = result.message
            )
        }
    }

    suspend fun executeZeroAccountPromotion(
        context: Context,
        client: AdbClient,
        adminComponent: String,
        userId: Int = 0,
        reconnectProvider: (suspend () -> AdbClient?)? = null
    ): PromotionResult = AdbPromotionExecutor.executeZeroAccountPromotion(context, client, adminComponent, userId, reconnectProvider)
}
