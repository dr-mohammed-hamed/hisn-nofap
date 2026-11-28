package com.drmohammed.antiporn.provisioning.ui

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.drmohammed.antiporn.provisioning.engine.DeviceOwnerPromoter
import com.drmohammed.antiporn.provisioning.engine.OemPermissionType
import com.drmohammed.antiporn.provisioning.service.AdbPairingNotificationService

/**
 * Coordinates the mutually exclusive provisioning outcome dialogs that are not yet backed by
 * a rotation-safe DialogFragment (OEM permission and blocking accounts).
 *
 * Blocking secondary users are handled by [BlockingSecondaryUsersDialogFragment] together with
 * [ProvisioningWizardViewModel], so this coordinator deliberately no longer owns that dialog.
 */
internal class ProvisioningOutcomeDialogCoordinator {

    private val oemPermissionDialogHelper = ProvisioningOemPermissionDialogHelper()
    private val accountsBlockingDialogHelper = ProvisioningAccountsBlockingDialogHelper()

    private var pendingOemPermissionType: OemPermissionType? = null
    private var pendingBlockingAccountsJson: String? = null

    fun hasAnyPendingOutcome(): Boolean =
        pendingOemPermissionType != null || pendingBlockingAccountsJson != null

    fun isAnyShowing(): Boolean =
        oemPermissionDialogHelper.isShowing() || accountsBlockingDialogHelper.isShowing()

    fun dismissAll() {
        oemPermissionDialogHelper.dismiss()
        accountsBlockingDialogHelper.dismiss()
    }

    fun clearAll() {
        clearAllExcept(keep = null)
    }

    fun saveState(outState: Bundle) {
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
        onRetryAlreadyPaired: () -> Unit
    ) {
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
        onRetryAlreadyPaired: () -> Unit,
        onRecoveryRequested: () -> Unit
    ) {
        if (targetIntent == null) return
        if (targetIntent.getBooleanExtra(ProvisioningWizardActivity.EXTRA_REQUIRE_DANGER_ACK, false)) {
            targetIntent.removeExtra(ProvisioningWizardActivity.EXTRA_REQUIRE_DANGER_ACK)
            dangerDialogHelper.ensureDangerAcknowledgedBeforeAction(activity) {}
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
        onRetryAlreadyPaired: () -> Unit
    ) {
        if (DeviceOwnerPromoter.isPromotionActive || isLocalPromoting || hasAnyPendingOutcome()) return
        when (val pending = AdbPairingNotificationService.pendingStickyOutcome ?: return) {
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
            // BlockingUsers sticky outcomes are consumed by the Activity's DialogFragment path.
            is AdbPairingNotificationService.StickyOutcomePayload.BlockingUsers -> Unit
        }
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
        if (keep != ActiveOutcome.OEM) {
            pendingOemPermissionType = null
            oemPermissionDialogHelper.dismiss()
        }
        if (keep != ActiveOutcome.ACCOUNTS) {
            pendingBlockingAccountsJson = null
            accountsBlockingDialogHelper.dismiss()
        }
    }

    private enum class ActiveOutcome { OEM, ACCOUNTS }

    companion object {
        private const val TAG = "AntiPorn_WizOutcomeDlg"
        private const val KEY_PENDING_OEM_TYPE = "key_pending_oem_type"
        private const val KEY_PENDING_BLOCKING_ACCOUNTS_JSON = "key_pending_blocking_accounts_json"
    }
}
