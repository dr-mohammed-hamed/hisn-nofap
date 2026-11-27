package com.drmohammed.antiporn.provisioning.service

import android.app.ActivityOptions
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import com.drmohammed.antiporn.MainActivity
import com.drmohammed.antiporn.R
import com.drmohammed.antiporn.notification.registry.NotificationRegistry
import com.drmohammed.antiporn.provisioning.engine.OemProvisioningHelper
import com.drmohammed.antiporn.provisioning.engine.ProvisioningAckStore
import com.drmohammed.antiporn.provisioning.receiver.PairingCodeReceiver
import com.drmohammed.antiporn.provisioning.ui.ProvisioningWizardActivity

/**
 * Builds notification channels and notifications for [AdbPairingNotificationService].
 */
internal object AdbPairingNotificationFactory {

    private val VIBRATION_PATTERN = longArrayOf(0, 250, 100, 250)

    fun createNotificationChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return

        val guideChannel = NotificationChannel(
            AdbPairingNotificationService.CHANNEL_ID_GUIDE,
            context.getString(R.string.provisioning_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = context.getString(R.string.provisioning_channel_desc)
        }

        val headsUpChannel = NotificationChannel(
            AdbPairingNotificationService.CHANNEL_ID_HEADS_UP,
            context.getString(R.string.provisioning_prompt_title),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = context.getString(R.string.provisioning_channel_desc)
            enableVibration(true)
            vibrationPattern = VIBRATION_PATTERN
        }

        val criticalChannel = NotificationChannel(
            NotificationRegistry.CHANNEL_ID_CRITICAL_ALERTS,
            context.getString(R.string.critical_alerts_channel_name),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = context.getString(R.string.critical_alerts_channel_desc)
            setShowBadge(true)
            enableVibration(true)
        }

        manager.createNotificationChannel(guideChannel)
        manager.createNotificationChannel(headsUpChannel)
        manager.createNotificationChannel(criticalChannel)
    }

    fun buildBalOptionsBundle(): Bundle? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return null
        return ActivityOptions.makeBasic().apply {
            @Suppress("DEPRECATION")
            setPendingIntentBackgroundActivityStartMode(
                ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
            )
        }.toBundle()
    }

    fun buildGuideNotification(context: Context, isWirelessAdbOn: Boolean): Notification {
        val message = if (isWirelessAdbOn) {
            context.getString(R.string.provisioning_guide_notif_toggle_done)
        } else {
            context.getString(R.string.provisioning_guide_notif_waiting_toggle)
        }

        return NotificationCompat.Builder(context, AdbPairingNotificationService.CHANNEL_ID_GUIDE)
            .setSmallIcon(R.drawable.ic_shield_device_owner)
            .setContentTitle(context.getString(R.string.provisioning_guide_notif_title))
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    }

    fun buildInProgressNotification(context: Context): Notification {
        return NotificationCompat.Builder(context, AdbPairingNotificationService.CHANNEL_ID_HEADS_UP)
            .setSmallIcon(R.drawable.ic_shield_device_owner)
            .setContentTitle(context.getString(R.string.provisioning_in_progress_title))
            .setContentText(context.getString(R.string.provisioning_in_progress_desc))
            .setOngoing(true)
            .build()
    }

    fun buildSuccessNotification(context: Context, mainIntent: Intent, optionsBundle: Bundle?): Notification {
        val successPending = PendingIntent.getActivity(
            context,
            NotificationRegistry.REQUEST_CODE_PROVISIONING_SUCCESS,
            mainIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            optionsBundle
        )
        return NotificationCompat.Builder(context, AdbPairingNotificationService.CHANNEL_ID_HEADS_UP)
            .setSmallIcon(R.drawable.ic_shield_device_owner)
            .setContentTitle(context.getString(R.string.provisioning_success_title))
            .setContentText(context.getString(R.string.provisioning_success_desc))
            .setContentIntent(successPending)
            .setAutoCancel(true)
            .build()
    }

    fun buildBlockingUsersNotification(context: Context, wizardIntent: Intent, optionsBundle: Bundle?): Notification {
        val wizardPending = PendingIntent.getActivity(
            context,
            NotificationRegistry.REQUEST_CODE_BLOCKING_USERS,
            wizardIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            optionsBundle
        )
        val blockingTitle = context.getString(R.string.provisioning_blocking_users_notif_title)
        val blockingDesc = context.getString(R.string.provisioning_blocking_users_notif_desc)
        return NotificationCompat.Builder(context, AdbPairingNotificationService.CHANNEL_ID_HEADS_UP)
            .setSmallIcon(R.drawable.ic_shield_device_owner)
            .setContentTitle(blockingTitle)
            .setContentText(blockingDesc)
            .setStyle(NotificationCompat.BigTextStyle().bigText(blockingDesc))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setVibrate(VIBRATION_PATTERN)
            .setContentIntent(wizardPending)
            .setAutoCancel(true)
            .build()
    }

    fun buildOemPermissionNotification(
        context: Context,
        wizardIntent: Intent,
        optionsBundle: Bundle?,
        message: String
    ): Notification {
        val wizardPending = PendingIntent.getActivity(
            context,
            NotificationRegistry.REQUEST_CODE_OEM_PERMISSION,
            wizardIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            optionsBundle
        )
        return NotificationCompat.Builder(context, AdbPairingNotificationService.CHANNEL_ID_HEADS_UP)
            .setSmallIcon(R.drawable.ic_shield_device_owner)
            .setContentTitle(context.getString(R.string.provisioning_oem_notif_title))
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setVibrate(VIBRATION_PATTERN)
            .setContentIntent(wizardPending)
            .setAutoCancel(true)
            .build()
    }

    fun buildAccountsBlockingNotification(
        context: Context,
        wizardIntent: Intent,
        optionsBundle: Bundle?,
        accountLabels: List<String>
    ): Notification {
        val wizardPending = PendingIntent.getActivity(
            context,
            NotificationRegistry.REQUEST_CODE_ACCOUNTS_BLOCKING,
            wizardIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            optionsBundle
        )
        val summary = accountLabels
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .takeIf { it.isNotEmpty() }
            ?.joinToString(", ")
            ?: context.getString(R.string.provisioning_accounts_fallback_label)
        val desc = context.getString(R.string.provisioning_accounts_notif_desc, summary)
        return NotificationCompat.Builder(context, AdbPairingNotificationService.CHANNEL_ID_HEADS_UP)
            .setSmallIcon(R.drawable.ic_shield_device_owner)
            .setContentTitle(context.getString(R.string.provisioning_accounts_notif_title))
            .setContentText(desc)
            .setStyle(NotificationCompat.BigTextStyle().bigText(desc))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setVibrate(VIBRATION_PATTERN)
            .setContentIntent(wizardPending)
            .setAutoCancel(true)
            .build()
    }

    fun buildFreezeRecoveryNotification(
        context: Context,
        affectedCount: Int,
        wizardIntent: Intent,
        optionsBundle: Bundle?
    ): Notification {
        val wizardPending = PendingIntent.getActivity(
            context,
            NotificationRegistry.REQUEST_CODE_FREEZE_RECOVERY,
            wizardIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            optionsBundle
        )
        val recoveryDesc = context.getString(R.string.provisioning_recovery_notif_desc, affectedCount)
        return NotificationCompat.Builder(context, NotificationRegistry.CHANNEL_ID_CRITICAL_ALERTS)
            .setSmallIcon(R.drawable.ic_shield_device_owner)
            .setContentTitle(context.getString(R.string.provisioning_recovery_notif_title))
            .setContentText(recoveryDesc)
            .setStyle(NotificationCompat.BigTextStyle().bigText(recoveryDesc))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setContentIntent(wizardPending)
            .setAutoCancel(true)
            .build()
    }

    fun buildFailureNotification(
        context: Context,
        errorMessage: String,
        targetIntent: Intent? = null,
        optionsBundle: Bundle? = null
    ): Notification {
        val wizardIntent = targetIntent ?: Intent(context, ProvisioningWizardActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val wizardPending = PendingIntent.getActivity(
            context,
            NotificationRegistry.REQUEST_CODE_PROVISIONING_FAILURE,
            wizardIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            optionsBundle
        )
        return NotificationCompat.Builder(context, AdbPairingNotificationService.CHANNEL_ID_HEADS_UP)
            .setSmallIcon(R.drawable.ic_shield_device_owner)
            .setContentTitle(context.getString(R.string.provisioning_failed_title))
            .setContentText(errorMessage)
            .setStyle(NotificationCompat.BigTextStyle().bigText(errorMessage))
            .setContentIntent(wizardPending)
            .setAutoCancel(true)
            .setOngoing(false)
            .build()
    }

    fun buildPairingPromptNotification(
        context: Context,
        capturedPairingPort: Int,
        capturedConnectPort: Int
    ): Notification {
        val canSubmitFromNotif = !OemProvisioningHelper.requiresPreActivationDangerAck(context) ||
            ProvisioningAckStore.hasValidAck(context)

        if (!canSubmitFromNotif) {
            val ackWizardIntent = Intent(context, ProvisioningWizardActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra(ProvisioningWizardActivity.EXTRA_REQUIRE_DANGER_ACK, true)
            }
            val ackPendingIntent = PendingIntent.getActivity(
                context,
                NotificationRegistry.REQUEST_CODE_ACK_WIZARD,
                ackWizardIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val promptDesc = context.getString(R.string.provisioning_prompt_ack_required_desc)
            return NotificationCompat.Builder(context, AdbPairingNotificationService.CHANNEL_ID_HEADS_UP)
                .setSmallIcon(R.drawable.ic_shield_device_owner)
                .setContentTitle(context.getString(R.string.provisioning_prompt_ack_required_title))
                .setContentText(promptDesc)
                .setStyle(NotificationCompat.BigTextStyle().bigText(promptDesc))
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_REMINDER)
                .setVibrate(VIBRATION_PATTERN)
                .setContentIntent(ackPendingIntent)
                .setOngoing(true)
                .setAutoCancel(false)
                .build()
        }

        val remoteInput = RemoteInput.Builder(PairingCodeReceiver.EXTRA_PAIRING_CODE)
            .setLabel(context.getString(R.string.provisioning_input_label))
            .setAllowFreeFormInput(true)
            .build()

        val replyIntent = Intent(context, AdbPairingNotificationService::class.java).apply {
            action = AdbPairingNotificationService.ACTION_SUBMIT_PAIRING_CODE
            putExtra(AdbPairingNotificationService.EXTRA_PAIRING_PORT, capturedPairingPort)
            putExtra(AdbPairingNotificationService.EXTRA_CONNECT_PORT, capturedConnectPort)
        }

        val replyPendingIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            PendingIntent.getForegroundService(
                context,
                NotificationRegistry.REQUEST_CODE_PAIRING_REPLY,
                replyIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            )
        } else {
            PendingIntent.getService(
                context,
                NotificationRegistry.REQUEST_CODE_PAIRING_REPLY,
                replyIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            )
        }

        val submitAction = NotificationCompat.Action.Builder(
            R.drawable.ic_shield_device_owner,
            context.getString(R.string.provisioning_action_submit),
            replyPendingIntent
        )
            .addRemoteInput(remoteInput)
            .setShowsUserInterface(false)
            .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY)
            .setAllowGeneratedReplies(false)
            .build()

        val promptDesc = context.getString(R.string.provisioning_prompt_desc, capturedPairingPort)
        return NotificationCompat.Builder(context, AdbPairingNotificationService.CHANNEL_ID_HEADS_UP)
            .setSmallIcon(R.drawable.ic_shield_device_owner)
            .setContentTitle(context.getString(R.string.provisioning_prompt_title))
            .setContentText(promptDesc)
            .setStyle(NotificationCompat.BigTextStyle().bigText(promptDesc))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setVibrate(VIBRATION_PATTERN)
            .addAction(submitAction)
            .setOngoing(true)
            .setAutoCancel(false)
            .build()
    }
}
