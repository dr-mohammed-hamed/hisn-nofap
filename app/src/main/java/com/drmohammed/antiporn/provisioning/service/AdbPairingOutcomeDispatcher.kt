package com.drmohammed.antiporn.provisioning.service

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import com.drmohammed.antiporn.MainActivity
import com.drmohammed.antiporn.R
import com.drmohammed.antiporn.notification.registry.NotificationRegistry
import com.drmohammed.antiporn.provisioning.engine.AdbShellValidation
import com.drmohammed.antiporn.provisioning.engine.BlockingSecondaryUser
import com.drmohammed.antiporn.provisioning.engine.DeviceOwnerPromoter
import com.drmohammed.antiporn.provisioning.engine.OemPermissionType
import com.drmohammed.antiporn.provisioning.engine.PromotionResult
import com.drmohammed.antiporn.provisioning.engine.ProvisioningAckStore
import com.drmohammed.antiporn.provisioning.engine.ProvisioningFreezeRecoveryStore
import com.drmohammed.antiporn.provisioning.receiver.PairingCodeReceiver
import com.drmohammed.antiporn.provisioning.ui.BlockingAccountsPayload
import com.drmohammed.antiporn.provisioning.ui.ProvisioningWizardActivity
import com.drmohammed.antiporn.telemetry.BlackBoxRecorder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

internal data class OutcomeDispatchContext(
    val context: Context,
    val promptState: AdbPairingPromptState,
    val pairingPort: Int,
    val connectPort: Int,
    val onStopService: () -> Unit
)

/**
 * Dispatches promotion outcomes, mDNS port notifications/broadcasts, and error fallbacks
 * for [AdbPairingNotificationService].
 */
internal object AdbPairingOutcomeDispatcher {

    private const val TAG = "AntiPorn_OutcomeDisp"

    fun dispatchPortSnapshot(context: Context, snapshot: PortPromptSnapshot, ports: Pair<Int, Int>) {
        Timber.tag(TAG).i("Port state updated: pairing=${ports.first}, connect=${ports.second}")
        broadcastPorts(context, ports.first, ports.second)
        if (snapshot.shouldPrompt) {
            BlackBoxRecorder.recordPairing(context, "PAIRING_PORT_CAPTURED", isSuccess = true, details = "port=${snapshot.pairingPort}")
            notifyPairingPrompt(context, snapshot.pairingPort, snapshot.connectPort)
        }
    }

    fun dispatchServiceLost(context: Context, snapshot: ServiceLostSnapshot, ports: Pair<Int, Int>) {
        if (snapshot.shouldBroadcast) {
            broadcastPorts(context, ports.first, ports.second)
        }
        if (snapshot.shouldCancelPrompt) {
            cancelPairingPromptNotification(context)
        }
    }

    fun dispatchClearSticky(context: Context, snapshot: ClearStickySnapshot) {
        if (!snapshot.cleared) return
        val nm = notificationManager(context) ?: return
        nm.cancel(AdbPairingNotificationService.PAIRING_PROMPT_NOTIFICATION_ID)
        if (snapshot.shouldRefreshPrompt) {
            nm.notify(
                AdbPairingNotificationService.PAIRING_PROMPT_NOTIFICATION_ID,
                AdbPairingNotificationFactory.buildPairingPromptNotification(context, snapshot.pairingPort, snapshot.connectPort)
            )
        }
    }

    fun notifyGuideStatus(context: Context, isWirelessAdbOn: Boolean) {
        notificationManager(context)?.notify(
            AdbPairingNotificationService.NOTIFICATION_ID,
            AdbPairingNotificationFactory.buildGuideNotification(context, isWirelessAdbOn)
        )
    }

    fun notifyPairingPrompt(context: Context, pairingPort: Int, connectPort: Int) {
        notificationManager(context)?.notify(
            AdbPairingNotificationService.PAIRING_PROMPT_NOTIFICATION_ID,
            AdbPairingNotificationFactory.buildPairingPromptNotification(context, pairingPort, connectPort)
        )
    }

    fun cancelPairingPromptNotification(context: Context) {
        notificationManager(context)?.cancel(AdbPairingNotificationService.PAIRING_PROMPT_NOTIFICATION_ID)
    }

    fun dispatchInvalidPairingInput(context: Context, rawCodeNull: Boolean, codeLength: Int, port: Int, ports: Pair<Int, Int>) {
        Timber.tag(TAG).w("ACTION_SUBMIT_PAIRING_CODE invalid code: len=$codeLength, port=$port")
        BlackBoxRecorder.recordPairing(context, "REMOTE_INPUT_EMPTY_OR_INVALID", isSuccess = false, details = "rawCodeNull=$rawCodeNull, port=$port")
        notifyPairingPrompt(context, ports.first, ports.second)
    }

    fun dispatchAckRequiredBlocked(context: Context, port: Int, ports: Pair<Int, Int>) {
        Timber.tag(TAG).w("Submission blocked: Danger acknowledgment required but missing or expired")
        BlackBoxRecorder.recordPairing(context, "SUBMISSION_BLOCKED_NO_ACK", isSuccess = false, details = "port=$port")
        sendResultBroadcast(context, success = false, errorMessage = context.getString(R.string.provisioning_error_ack_required))
        notifyPairingPrompt(context, ports.first, ports.second)
    }

    suspend fun dispatchResult(dispatchCtx: OutcomeDispatchContext, result: PromotionResult) {
        when (result) {
            is PromotionResult.Success -> dispatchSuccess(dispatchCtx)
            is PromotionResult.SecondaryUsersBlocking -> dispatchSecondaryUsersBlocking(dispatchCtx, result.users)
            is PromotionResult.OemPermissionRequired -> dispatchOemPermissionRequired(dispatchCtx, result.oemType, result.message)
            is PromotionResult.AccountsBlocking -> dispatchAccountsBlocking(dispatchCtx, result)
            is PromotionResult.Failure -> {
                val alreadyInProgressMsg = dispatchCtx.context.getString(R.string.provisioning_error_already_in_progress)
                if (DeviceOwnerPromoter.isPromotionActive && result.message == alreadyInProgressMsg) {
                    dispatchCtx.promptState.cancelPromotionWithoutStickyOutcome()
                    Timber.tag(TAG).i("Ignoring concurrent lock rejection because another promotion is active")
                    return
                }
                dispatchFailure(dispatchCtx, result.message)
            }
        }
    }

    suspend fun dispatchUnexpectedError(dispatchCtx: OutcomeDispatchContext, error: Throwable) {
        if (error is CancellationException) throw error
        Timber.tag(TAG).e(error, "Unexpected exception during pairing code submission")
        val fallback = dispatchCtx.context.getString(R.string.provisioning_error_unknown)
        dispatchFailure(dispatchCtx, "${error.javaClass.simpleName}: ${error.message ?: fallback}")
    }

    private fun dispatchSuccess(dispatchCtx: OutcomeDispatchContext) {
        val context = dispatchCtx.context
        AdbPairingNotificationService.pendingStickyOutcome = null
        dispatchCtx.promptState.markPromotionSuccess()
        Timber.tag(TAG).i("Promotion succeeded via service submission")
        ProvisioningAckStore.clearAck(context)
        AdbShellValidation.cancelFreezeRecoveryNotification(context)

        val mainIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val optionsBundle = AdbPairingNotificationFactory.buildBalOptionsBundle()
        try {
            context.startActivity(mainIntent, optionsBundle)
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Direct launch of MainActivity failed")
        }

        sendResultBroadcast(context, success = true)
        notificationManager(context)?.notify(
            AdbPairingNotificationService.SUCCESS_NOTIFICATION_ID,
            AdbPairingNotificationFactory.buildSuccessNotification(context, mainIntent, optionsBundle)
        )
        dispatchCtx.onStopService()
    }

    private fun dispatchSecondaryUsersBlocking(dispatchCtx: OutcomeDispatchContext, users: List<BlockingSecondaryUser>) {
        val context = dispatchCtx.context
        dispatchCtx.promptState.markStickyOutcome(dispatchCtx.pairingPort)
        val effectiveConnectPort = dispatchCtx.connectPort.takeIf { it > 0 } ?: dispatchCtx.promptState.capturedConnectPort
        val usersJson = BlockingSecondaryUser.listToJson(users)
        AdbPairingNotificationService.pendingStickyOutcome =
            AdbPairingNotificationService.StickyOutcomePayload.BlockingUsers(usersJson, effectiveConnectPort)
        Timber.tag(TAG).w("Promotion paused: ${users.size} blocking secondary user(s) detected")

        context.sendBroadcast(
            Intent(PairingCodeReceiver.ACTION_PROVISIONING_RESULT).apply {
                setPackage(context.packageName)
                putExtra(PairingCodeReceiver.EXTRA_SUCCESS, false)
                putExtra(PairingCodeReceiver.EXTRA_BLOCKING_USERS_JSON, usersJson)
                putExtra(AdbPairingNotificationService.EXTRA_CONNECT_PORT, effectiveConnectPort)
            }
        )

        val wizardIntent = Intent(context, ProvisioningWizardActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(ProvisioningWizardActivity.EXTRA_BLOCKING_USERS_JSON, usersJson)
            putExtra(ProvisioningWizardActivity.EXTRA_CONNECT_PORT, effectiveConnectPort)
        }
        val optionsBundle = AdbPairingNotificationFactory.buildBalOptionsBundle()
        try {
            context.startActivity(wizardIntent, optionsBundle)
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Direct launch of ProvisioningWizardActivity for blocking users failed")
        }

        notificationManager(context)?.notify(
            AdbPairingNotificationService.PAIRING_PROMPT_NOTIFICATION_ID,
            AdbPairingNotificationFactory.buildBlockingUsersNotification(context, wizardIntent, optionsBundle)
        )
    }

    private fun dispatchOemPermissionRequired(
        dispatchCtx: OutcomeDispatchContext,
        oemType: OemPermissionType,
        message: String
    ) {
        val context = dispatchCtx.context
        dispatchCtx.promptState.markStickyOutcome(dispatchCtx.pairingPort)
        val effectiveConnectPort = dispatchCtx.connectPort.takeIf { it > 0 } ?: dispatchCtx.promptState.capturedConnectPort
        AdbPairingNotificationService.pendingStickyOutcome =
            AdbPairingNotificationService.StickyOutcomePayload.OemPermission(oemType.name, effectiveConnectPort)
        Timber.tag(TAG).w("Promotion paused: OEM permission toggle required (${oemType.name})")

        context.sendBroadcast(
            Intent(PairingCodeReceiver.ACTION_PROVISIONING_RESULT).apply {
                setPackage(context.packageName)
                putExtra(PairingCodeReceiver.EXTRA_SUCCESS, false)
                putExtra(PairingCodeReceiver.EXTRA_ERROR_MESSAGE, message)
                putExtra(PairingCodeReceiver.EXTRA_OEM_PERMISSION_TYPE, oemType.name)
                putExtra(AdbPairingNotificationService.EXTRA_CONNECT_PORT, effectiveConnectPort)
            }
        )

        val wizardIntent = Intent(context, ProvisioningWizardActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(ProvisioningWizardActivity.EXTRA_OEM_PERMISSION_TYPE, oemType.name)
            putExtra(ProvisioningWizardActivity.EXTRA_CONNECT_PORT, effectiveConnectPort)
        }
        val optionsBundle = AdbPairingNotificationFactory.buildBalOptionsBundle()
        try {
            context.startActivity(wizardIntent, optionsBundle)
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Direct launch of ProvisioningWizardActivity for OEM permission failed")
        }

        notificationManager(context)?.notify(
            AdbPairingNotificationService.PAIRING_PROMPT_NOTIFICATION_ID,
            AdbPairingNotificationFactory.buildOemPermissionNotification(context, wizardIntent, optionsBundle, message)
        )
    }

    private fun dispatchAccountsBlocking(
        dispatchCtx: OutcomeDispatchContext,
        result: PromotionResult.AccountsBlocking
    ) {
        val context = dispatchCtx.context
        dispatchCtx.promptState.markStickyOutcome(dispatchCtx.pairingPort)
        val effectiveConnectPort = dispatchCtx.connectPort.takeIf { it > 0 } ?: dispatchCtx.promptState.capturedConnectPort
        val accountsJson = BlockingAccountsPayload(
            accountLabels = result.accountLabels,
            accountTypes = result.accountTypes,
            message = result.message
        ).toJson()
        AdbPairingNotificationService.pendingStickyOutcome =
            AdbPairingNotificationService.StickyOutcomePayload.AccountsBlocking(accountsJson, effectiveConnectPort)
        Timber.tag(TAG).w("Promotion paused: ${result.accountLabels.size} blocking account label(s) detected")

        context.sendBroadcast(
            Intent(PairingCodeReceiver.ACTION_PROVISIONING_RESULT).apply {
                setPackage(context.packageName)
                putExtra(PairingCodeReceiver.EXTRA_SUCCESS, false)
                putExtra(PairingCodeReceiver.EXTRA_ERROR_MESSAGE, result.message)
                putExtra(PairingCodeReceiver.EXTRA_BLOCKING_ACCOUNTS_JSON, accountsJson)
                putExtra(AdbPairingNotificationService.EXTRA_CONNECT_PORT, effectiveConnectPort)
            }
        )

        val wizardIntent = Intent(context, ProvisioningWizardActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(ProvisioningWizardActivity.EXTRA_BLOCKING_ACCOUNTS_JSON, accountsJson)
            putExtra(ProvisioningWizardActivity.EXTRA_CONNECT_PORT, effectiveConnectPort)
        }
        val optionsBundle = AdbPairingNotificationFactory.buildBalOptionsBundle()
        try {
            context.startActivity(wizardIntent, optionsBundle)
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Direct launch of ProvisioningWizardActivity for blocking accounts failed")
        }

        notificationManager(context)?.notify(
            AdbPairingNotificationService.PAIRING_PROMPT_NOTIFICATION_ID,
            AdbPairingNotificationFactory.buildAccountsBlockingNotification(
                context,
                wizardIntent,
                optionsBundle,
                result.accountLabels
            )
        )
    }

    private suspend fun dispatchFailure(dispatchCtx: OutcomeDispatchContext, message: String) {
        val context = dispatchCtx.context
        dispatchCtx.promptState.markStickyOutcome(dispatchCtx.pairingPort)
        val effectiveConnectPort = dispatchCtx.connectPort.takeIf { it > 0 } ?: dispatchCtx.promptState.capturedConnectPort
        Timber.tag(TAG).e("Promotion failed: $message")
        sendResultBroadcast(context, success = false, errorMessage = message, connectPort = effectiveConnectPort)

        val stranded = withContext(Dispatchers.IO) {
            ProvisioningFreezeRecoveryStore.findStillDisabledTrackedPackages(context)
        }
        if (stranded.isNotEmpty()) {
            val recoveryIntent = Intent(context, ProvisioningWizardActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(ProvisioningWizardActivity.EXTRA_SHOW_RECOVERY_DIALOG, true)
                putExtra(ProvisioningWizardActivity.EXTRA_CONNECT_PORT, effectiveConnectPort)
            }
            val optionsBundle = AdbPairingNotificationFactory.buildBalOptionsBundle()
            try {
                context.startActivity(recoveryIntent, optionsBundle)
            } catch (e: Exception) {
                Timber.tag(TAG).w(e, "Direct launch of ProvisioningWizardActivity for freeze recovery failed")
            }
            val nm = notificationManager(context)
            nm?.notify(
                AdbPairingNotificationService.PAIRING_PROMPT_NOTIFICATION_ID,
                AdbPairingNotificationFactory.buildFailureNotification(context, message, recoveryIntent, optionsBundle)
            )
            nm?.notify(
                NotificationRegistry.EVENT_FREEZE_RECOVERY_ID,
                AdbPairingNotificationFactory.buildFreezeRecoveryNotification(context, stranded.size, recoveryIntent, optionsBundle)
            )
        } else {
            notificationManager(context)?.notify(
                AdbPairingNotificationService.PAIRING_PROMPT_NOTIFICATION_ID,
                AdbPairingNotificationFactory.buildFailureNotification(context, message)
            )
        }
    }

    private fun broadcastPorts(context: Context, pairingPort: Int, connectPort: Int) {
        context.sendBroadcast(Intent(AdbPairingNotificationService.ACTION_PORTS_DISCOVERED).apply {
            setPackage(context.packageName)
            putExtra(AdbPairingNotificationService.EXTRA_PAIRING_PORT, pairingPort)
            putExtra(AdbPairingNotificationService.EXTRA_CONNECT_PORT, connectPort)
        })
    }

    private fun sendResultBroadcast(context: Context, success: Boolean, errorMessage: String? = null, connectPort: Int = 0) {
        context.sendBroadcast(Intent(PairingCodeReceiver.ACTION_PROVISIONING_RESULT).apply {
            setPackage(context.packageName)
            putExtra(PairingCodeReceiver.EXTRA_SUCCESS, success)
            if (errorMessage != null) putExtra(PairingCodeReceiver.EXTRA_ERROR_MESSAGE, errorMessage)
            if (connectPort > 0) putExtra(AdbPairingNotificationService.EXTRA_CONNECT_PORT, connectPort)
        })
    }

    private fun notificationManager(context: Context): NotificationManager? =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
}
