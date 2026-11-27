package com.drmohammed.antiporn.provisioning.ui

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.drmohammed.antiporn.provisioning.engine.BlockingSecondaryUser
import com.drmohammed.antiporn.provisioning.engine.DeviceOwnerPromoter
import com.drmohammed.antiporn.provisioning.engine.OemPermissionType
import com.drmohammed.antiporn.provisioning.engine.ProvisioningAckStore
import com.drmohammed.antiporn.provisioning.service.AdbPairingNotificationService

/**
 * Coordinates mutually exclusive provisioning outcome dialogs (secondary users,
 * OEM permissions, and blocking accounts) and their saved instance state for [ProvisioningWizardActivity].
 */
internal class ProvisioningOutcomeDialogCoordinator {

    private val blockingUsersDialogHelper = BlockingSecondaryUsersDialogHelper()
    private val oemPermissionDialogHelper = ProvisioningOemPermissionDialogHelper()
    private val accountsBlockingDialogHelper = ProvisioningAccountsBlockingDialogHelper()

    private var pendingBlockingUsersJson: String? = null
    private var pendingOemPermissionType: OemPermissionType? = null
    private var pendingBlockingAccountsJson: String? = null

    fun hasAnyPendingOutcome(): Boolean =
        pendingOemPermissionType != null || pendingBlockingUsersJson != null || pendingBlockingAccountsJson != null

    fun isAnyShowing(): Boolean =
        blockingUsersDialogHelper.isShowing() || oemPermissionDialogHelper.isShowing() || accountsBlockingDialogHelper.isShowing()

    fun dismissAll() {
        blockingUsersDialogHelper.dismiss()
        oemPermissionDialogHelper.dismiss()
        accountsBlockingDialogHelper.dismiss()
    }

    fun clearAll() {
        clearAllExcept(keep = null)
    }

    fun saveState(outState: Bundle) {
        pendingBlockingUsersJson?.let { outState.putString(KEY_PENDING_BLOCKING_USERS_JSON, it) }
        (oemPermissionDialogHelper.currentOemType() ?: pendingOemPermissionType)?.let {
            outState.putString(KEY_PENDING_OEM_TYPE, it.name)
        }
        (accountsBlockingDialogHelper.currentPayload()?.toJson() ?: pendingBlockingAccountsJson)?.let {
            outState.putString(KEY_PENDING_BLOCKING_ACCOUNTS_JSON, it)
        }
    }

    fun restoreState(
        savedInstanceState: Bundle,
        activity: AppCompatActivity,
        dangerDialogHelper: ProvisioningDangerDialogHelper,
        onDeleteSecondaryUsers: (Set<Int>) -> Unit,
        onRetryAlreadyPaired: () -> Unit
    ) {
        val restoredJson = savedInstanceState.getString(KEY_PENDING_BLOCKING_USERS_JSON)
        if (!restoredJson.isNullOrBlank()) {
            val restoredUsers = BlockingSecondaryUser.listFromJson(restoredJson)
            if (restoredUsers.isNotEmpty()) {
                showBlockingUsers(activity, restoredUsers, dangerDialogHelper, onDeleteSecondaryUsers)
            }
        }
        savedInstanceState.getString(KEY_PENDING_OEM_TYPE)?.let { raw ->
            runCatching { OemPermissionType.valueOf(raw) }.getOrNull()?.let {
                showOemPermission(activity, it, onRetryAlreadyPaired)
            }
        }
        BlockingAccountsPayload.fromJson(savedInstanceState.getString(KEY_PENDING_BLOCKING_ACCOUNTS_JSON))?.let {
            showAccountsBlocking(activity, it, onRetryAlreadyPaired)
        }
    }

    fun consumeIntentExtras(
        targetIntent: Intent?,
        activity: AppCompatActivity,
        dangerDialogHelper: ProvisioningDangerDialogHelper,
        onConnectPortDiscovered: (Int) -> Unit,
        onDeleteSecondaryUsers: (Set<Int>) -> Unit,
        onRetryAlreadyPaired: () -> Unit,
        onRecoveryRequested: () -> Unit
    ) {
        if (targetIntent == null) return
        if (targetIntent.getBooleanExtra(ProvisioningWizardActivity.EXTRA_REQUIRE_DANGER_ACK, false)) {
            targetIntent.removeExtra(ProvisioningWizardActivity.EXTRA_REQUIRE_DANGER_ACK)
            dangerDialogHelper.ensureDangerAcknowledgedBeforeAction(activity) {}
        }
        val extraConnPort = targetIntent.getIntExtra(ProvisioningWizardActivity.EXTRA_CONNECT_PORT, 0)
        if (extraConnPort > 0) {
            onConnectPortDiscovered(extraConnPort)
            targetIntent.removeExtra(ProvisioningWizardActivity.EXTRA_CONNECT_PORT)
        }
        val usersJson = targetIntent.getStringExtra(ProvisioningWizardActivity.EXTRA_BLOCKING_USERS_JSON)
        if (!usersJson.isNullOrBlank()) {
            targetIntent.removeExtra(ProvisioningWizardActivity.EXTRA_BLOCKING_USERS_JSON)
            val users = BlockingSecondaryUser.listFromJson(usersJson)
            if (users.isNotEmpty()) showBlockingUsers(activity, users, dangerDialogHelper, onDeleteSecondaryUsers)
        }
        targetIntent.getStringExtra(ProvisioningWizardActivity.EXTRA_OEM_PERMISSION_TYPE)?.let { rawType ->
            targetIntent.removeExtra(ProvisioningWizardActivity.EXTRA_OEM_PERMISSION_TYPE)
            runCatching { OemPermissionType.valueOf(rawType) }.getOrNull()?.let {
                showOemPermission(activity, it, onRetryAlreadyPaired)
            }
        }
        targetIntent.getStringExtra(ProvisioningWizardActivity.EXTRA_BLOCKING_ACCOUNTS_JSON)?.let { rawJson ->
            targetIntent.removeExtra(ProvisioningWizardActivity.EXTRA_BLOCKING_ACCOUNTS_JSON)
            BlockingAccountsPayload.fromJson(rawJson)?.let {
                showAccountsBlocking(activity, it, onRetryAlreadyPaired)
            }
        }
        if (targetIntent.getBooleanExtra(ProvisioningWizardActivity.EXTRA_SHOW_RECOVERY_DIALOG, false)) {
            targetIntent.removeExtra(ProvisioningWizardActivity.EXTRA_SHOW_RECOVERY_DIALOG)
            onRecoveryRequested()
        }
    }

    fun reconcilePendingStickyOutcome(
        activity: AppCompatActivity,
        isLocalPromoting: Boolean,
        dangerDialogHelper: ProvisioningDangerDialogHelper,
        onConnectPortDiscovered: (Int) -> Unit,
        onDeleteSecondaryUsers: (Set<Int>) -> Unit,
        onRetryAlreadyPaired: () -> Unit
    ) {
        if (DeviceOwnerPromoter.isPromotionActive || isLocalPromoting || hasAnyPendingOutcome()) return
        when (val pending = AdbPairingNotificationService.pendingStickyOutcome ?: return) {
            is AdbPairingNotificationService.StickyOutcomePayload.BlockingUsers -> {
                if (pending.connectPort > 0) onConnectPortDiscovered(pending.connectPort)
                val users = BlockingSecondaryUser.listFromJson(pending.usersJson)
                if (users.isNotEmpty()) showBlockingUsers(activity, users, dangerDialogHelper, onDeleteSecondaryUsers)
            }
            is AdbPairingNotificationService.StickyOutcomePayload.OemPermission -> {
                if (pending.connectPort > 0) onConnectPortDiscovered(pending.connectPort)
                runCatching { OemPermissionType.valueOf(pending.oemTypeName) }.getOrNull()?.let {
                    showOemPermission(activity, it, onRetryAlreadyPaired)
                }
            }
            is AdbPairingNotificationService.StickyOutcomePayload.AccountsBlocking -> {
                if (pending.connectPort > 0) onConnectPortDiscovered(pending.connectPort)
                BlockingAccountsPayload.fromJson(pending.accountsJson)?.let {
                    showAccountsBlocking(activity, it, onRetryAlreadyPaired)
                }
            }
        }
    }

    fun showBlockingUsers(
        activity: AppCompatActivity,
        users: List<BlockingSecondaryUser>,
        dangerDialogHelper: ProvisioningDangerDialogHelper,
        onDeleteSecondaryUsers: (Set<Int>) -> Unit
    ) {
        if (activity.isFinishing || activity.isDestroyed || users.isEmpty()) return
        clearAllExcept(keep = ActiveOutcome.USERS)
        pendingBlockingUsersJson = BlockingSecondaryUser.listToJson(users)
        blockingUsersDialogHelper.show(
            activity = activity,
            users = users,
            onOpenSettings = { dangerDialogHelper.openBlockingUsersSettings(activity, users) },
            onConfirmedDeleteAndActivate = { approvedUserIds ->
                pendingBlockingUsersJson = null
                ProvisioningAckStore.recordAck(activity)
                AdbPairingNotificationService.clearStickyOutcome(activity, suppressReprompt = true)
                onDeleteSecondaryUsers(approvedUserIds)
            },
            onCancel = {
                pendingBlockingUsersJson = null
                AdbPairingNotificationService.clearStickyOutcome(activity)
            }
        )
    }

    fun showOemPermission(
        activity: AppCompatActivity,
        oemType: OemPermissionType,
        onRetryAlreadyPaired: () -> Unit
    ) {
        if (activity.isFinishing || activity.isDestroyed) return
        clearAllExcept(keep = ActiveOutcome.OEM)
        pendingOemPermissionType = oemType
        oemPermissionDialogHelper.show(
            activity = activity,
            oemType = oemType,
            onRetryNow = onRetryAlreadyPaired,
            onDismissed = {
                pendingOemPermissionType = null
                AdbPairingNotificationService.clearStickyOutcome(activity)
            }
        )
    }

    fun showAccountsBlocking(
        activity: AppCompatActivity,
        payload: BlockingAccountsPayload,
        onRetryAlreadyPaired: () -> Unit
    ) {
        if (activity.isFinishing || activity.isDestroyed) return
        clearAllExcept(keep = ActiveOutcome.ACCOUNTS)
        pendingBlockingAccountsJson = payload.toJson()
        accountsBlockingDialogHelper.show(
            activity = activity,
            payload = payload,
            onRetryNow = onRetryAlreadyPaired,
            onDismissed = {
                pendingBlockingAccountsJson = null
                AdbPairingNotificationService.clearStickyOutcome(activity)
            }
        )
    }

    private fun clearAllExcept(keep: ActiveOutcome?) {
        if (keep != ActiveOutcome.USERS) {
            pendingBlockingUsersJson = null
            blockingUsersDialogHelper.dismiss()
        }
        if (keep != ActiveOutcome.OEM) {
            pendingOemPermissionType = null
            oemPermissionDialogHelper.dismiss()
        }
        if (keep != ActiveOutcome.ACCOUNTS) {
            pendingBlockingAccountsJson = null
            accountsBlockingDialogHelper.dismiss()
        }
    }

    private enum class ActiveOutcome { USERS, OEM, ACCOUNTS }

    companion object {
        private const val KEY_PENDING_BLOCKING_USERS_JSON = "key_pending_blocking_users_json"
        private const val KEY_PENDING_OEM_TYPE = "key_pending_oem_type"
        private const val KEY_PENDING_BLOCKING_ACCOUNTS_JSON = "key_pending_blocking_accounts_json"
    }
}
