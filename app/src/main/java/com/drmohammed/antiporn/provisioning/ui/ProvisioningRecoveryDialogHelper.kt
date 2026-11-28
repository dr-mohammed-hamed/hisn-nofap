package com.drmohammed.antiporn.provisioning.ui

import android.content.Intent
import android.provider.Settings
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.drmohammed.antiporn.R
import com.drmohammed.antiporn.provisioning.engine.RecoveryPackageInfo
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.util.Collections
import java.util.WeakHashMap

/**
 * Empathetic apology and recovery Material 3 dialog shown whenever packages remain disabled
 * in [com.drmohammed.antiporn.provisioning.engine.ProvisioningFreezeRecoveryStore] after an
 * interrupted or failed Device Owner promotion attempt.
 */
internal class ProvisioningRecoveryDialogHelper {

    private var activeDialog: AlertDialog? = null
    private var activePackages: List<String> = emptyList()
    private var activeHealingHint: String? = null
    private val suppressedDialogs = Collections.newSetFromMap(WeakHashMap<AlertDialog, Boolean>())

    fun isShowing(): Boolean = activeDialog?.isShowing == true

    fun currentHealingHint(): String? = if (isShowing()) activeHealingHint else null

    fun show(
        activity: AppCompatActivity,
        packages: List<RecoveryPackageInfo>,
        healingHint: String? = null,
        onQuickAdbHeal: () -> Unit,
        onDismissedByUser: () -> Unit = {}
    ) {
        if (activity.isFinishing || activity.isDestroyed || packages.isEmpty()) return
        val incomingPackageNames = packages.map { it.packageName }
        if (activeDialog?.isShowing == true &&
            activePackages == incomingPackageNames &&
            (healingHint == null || activeHealingHint == healingHint)
        ) {
            return
        }

        dismiss()
        activePackages = incomingPackageNames
        activeHealingHint = healingHint

        val message = buildDialogMessage(activity, packages, healingHint)
        var actionTaken = false
        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.provisioning_recovery_dialog_title)
            .setMessage(message)
            .setPositiveButton(R.string.provisioning_recovery_btn_quick_adb) { _, _ ->
                actionTaken = true
                if (!activity.isFinishing && !activity.isDestroyed) {
                    onQuickAdbHeal()
                }
            }
            .setNeutralButton(R.string.provisioning_recovery_btn_open_settings) { _, _ ->
                actionTaken = true
                if (!activity.isFinishing && !activity.isDestroyed) {
                    openManageApplicationsSettings(activity)
                }
            }
            .setNegativeButton(R.string.btn_close, null)
            .setCancelable(true)
            .create()

        dialog.setOnDismissListener {
            if (activeDialog === dialog) {
                activeDialog = null
                activePackages = emptyList()
                activeHealingHint = null
            }
            val suppress = suppressedDialogs.remove(dialog)
            if (!actionTaken && !suppress) {
                onDismissedByUser()
            }
        }

        activeDialog = dialog
        dialog.show()
    }

    fun dismiss() {
        activeDialog?.let { dialog ->
            if (dialog.isShowing) {
                suppressedDialogs.add(dialog)
                runCatching { dialog.dismiss() }
            }
        }
        activeDialog = null
        activePackages = emptyList()
        activeHealingHint = null
    }

    private fun buildDialogMessage(
        activity: AppCompatActivity,
        packages: List<RecoveryPackageInfo>,
        healingHint: String?
    ): String = buildString {
        append(activity.getString(R.string.provisioning_recovery_dialog_intro))
        append("\n\n")
        packages.forEach { pkg ->
            append(activity.getString(R.string.provisioning_recovery_package_line, pkg.appLabel, pkg.packageName))
            append('\n')
        }
        append('\n')
        append(activity.getString(R.string.provisioning_recovery_dialog_outro))
        if (!healingHint.isNullOrBlank()) {
            append("\n\n")
            append(healingHint)
        }
    }

    private fun openManageApplicationsSettings(activity: AppCompatActivity) {
        val candidates = listOf(
            Intent(Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS),
            Intent(Settings.ACTION_APPLICATION_SETTINGS),
            Intent(Settings.ACTION_SETTINGS)
        )
        ProvisioningDangerDialogHelper.startSettingsWithFallback(activity, candidates)
    }
}
