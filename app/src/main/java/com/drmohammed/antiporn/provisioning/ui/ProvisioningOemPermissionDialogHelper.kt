package com.drmohammed.antiporn.provisioning.ui

import android.content.Intent
import android.provider.Settings
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.drmohammed.antiporn.R
import com.drmohammed.antiporn.provisioning.engine.OemPermissionType
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * Interactive Material 3 guidance dialog shown when OEM-specific Developer Options toggles
 * (Xiaomi Security Settings or Realme/Oppo Permission Monitoring) block Device Owner promotion.
 */
internal class ProvisioningOemPermissionDialogHelper {

    private var activeDialog: AlertDialog? = null
    private var activeOemType: OemPermissionType? = null

    fun isShowing(): Boolean = activeDialog?.isShowing == true

    fun currentOemType(): OemPermissionType? = if (isShowing()) activeOemType else null

    fun show(
        activity: AppCompatActivity,
        oemType: OemPermissionType,
        onRetryNow: () -> Unit,
        onDismissed: () -> Unit = {}
    ) {
        if (activity.isFinishing || activity.isDestroyed) return
        if (activeDialog?.isShowing == true && activeOemType == oemType) {
            return
        }

        dismiss()
        activeOemType = oemType

        val messageRes = when (oemType) {
            OemPermissionType.XIAOMI_SECURITY_SETTINGS -> R.string.provisioning_oem_dialog_xiaomi_desc
            OemPermissionType.REALME_OPPO_PERMISSION_MONITORING -> R.string.provisioning_oem_dialog_realme_desc
        }

        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.provisioning_oem_dialog_title)
            .setMessage(messageRes)
            .setPositiveButton(R.string.provisioning_oem_btn_retry_now) { _, _ ->
                if (!activity.isFinishing && !activity.isDestroyed) {
                    onRetryNow()
                }
            }
            .setNeutralButton(R.string.provisioning_oem_btn_open_dev_options, null)
            .setNegativeButton(R.string.btn_back, null)
            .setCancelable(true)
            .create()

        dialog.setOnDismissListener {
            if (activeDialog === dialog) {
                activeDialog = null
                activeOemType = null
                onDismissed()
            }
        }

        activeDialog = dialog
        dialog.show()

        // Override neutral button after show() so jumping to Developer Options keeps the dialog open
        // and leaves "Retry Now" immediately ready when the user presses Back.
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.setOnClickListener {
            if (!activity.isFinishing && !activity.isDestroyed) {
                openDeveloperOptions(activity)
            }
        }
    }

    fun dismiss() {
        activeDialog?.let { dialog ->
            activeDialog = null
            activeOemType = null
            if (dialog.isShowing) {
                runCatching { dialog.dismiss() }
            }
        }
    }

    private fun openDeveloperOptions(activity: AppCompatActivity) {
        val adjacentFlag = if (activity.isInMultiWindowMode) Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT else 0
        val candidates = listOf(
            Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).apply { flags = adjacentFlag },
            Intent(Settings.ACTION_SETTINGS).apply { flags = adjacentFlag }
        )
        ProvisioningDangerDialogHelper.startSettingsWithFallback(activity, candidates)
    }
}
