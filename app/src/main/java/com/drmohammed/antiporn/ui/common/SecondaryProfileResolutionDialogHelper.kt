package com.drmohammed.antiporn.ui.common

import android.content.Context
import android.view.LayoutInflater
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.drmohammed.antiporn.R
import com.drmohammed.antiporn.core.engine.SecondaryProfileDetector
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * Shared UI helper presenting selective, single-profile resolution choices
 * and high-friction irreversible confirmation for Device Owner user removal.
 */
object SecondaryProfileResolutionDialogHelper {

    fun showProfileSelectionDialog(
        context: Context,
        secondaries: List<SecondaryProfileDetector.UnaffiliatedProfileInfo>,
        onOpenSettings: () -> Unit,
        onConfirmRemoveSingleProfile: (SecondaryProfileDetector.UnaffiliatedProfileInfo) -> Unit,
        onActiveDialogChanged: (AlertDialog?) -> Unit = {}
    ): AlertDialog? {
        if (secondaries.isEmpty()) return null

        val labels = secondaries.map { profile ->
            SecondaryProfileDetector.formatProfileDisplayEntry(context, profile)
        }.toTypedArray()

        var selectedIndex = 0
        var transitioningToConfirm = false
        lateinit var selectionDialog: AlertDialog
        selectionDialog = MaterialAlertDialogBuilder(context)
            .setTitle(R.string.dialog_select_profile_title)
            .setSingleChoiceItems(labels, 0) { _, which ->
                selectedIndex = which.coerceIn(0, secondaries.lastIndex)
            }
            .setPositiveButton(R.string.btn_open_cloned_app_settings) { _, _ ->
                onOpenSettings()
            }
            .setNeutralButton(R.string.btn_remove_selected_profile_dpm) { _, _ ->
                transitioningToConfirm = true
                val chosen = secondaries.getOrElse(selectedIndex) { secondaries.first() }
                showMandatoryConfirmDialog(
                    context = context,
                    profile = chosen,
                    onConfirmRemove = onConfirmRemoveSingleProfile,
                    onActiveDialogChanged = onActiveDialogChanged
                )
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create()

        selectionDialog.setOnDismissListener {
            if (!transitioningToConfirm) {
                onActiveDialogChanged(null)
            }
        }
        onActiveDialogChanged(selectionDialog)
        selectionDialog.show()
        return selectionDialog
    }

    fun showMandatoryConfirmDialog(
        context: Context,
        profile: SecondaryProfileDetector.UnaffiliatedProfileInfo,
        onConfirmRemove: (SecondaryProfileDetector.UnaffiliatedProfileInfo) -> Unit,
        onActiveDialogChanged: (AlertDialog?) -> Unit = {}
    ): AlertDialog {
        val dialogView = LayoutInflater.from(context).inflate(R.layout.dialog_confirm_remove_profile, null)
        val tvHeader = dialogView.findViewById<TextView>(R.id.tvProfileRemoveHeader)
        val tvWarning = dialogView.findViewById<TextView>(R.id.tvProfileRemoveWarning)
        val chkConfirm = dialogView.findViewById<MaterialCheckBox>(R.id.chkConfirmProfileRemoval)

        tvHeader.text = SecondaryProfileDetector.formatProfileDisplayEntry(context, profile)
        tvWarning.text = buildConfirmWarningMessage(context, profile)

        val confirmDialog = MaterialAlertDialogBuilder(context)
            .setTitle(R.string.dialog_confirm_remove_clone_title)
            .setView(dialogView)
            .setPositiveButton(R.string.btn_remove_selected_profile_dpm) { _, _ ->
                onConfirmRemove(profile)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create()

        confirmDialog.setOnShowListener {
            val positiveButton = confirmDialog.getButton(AlertDialog.BUTTON_POSITIVE)
            positiveButton.isEnabled = chkConfirm.isChecked
            chkConfirm.setOnCheckedChangeListener { _, isChecked ->
                positiveButton.isEnabled = isChecked
            }
        }
        confirmDialog.setOnDismissListener {
            onActiveDialogChanged(null)
        }
        onActiveDialogChanged(confirmDialog)
        confirmDialog.show()
        return confirmDialog
    }

    private fun buildConfirmWarningMessage(
        context: Context,
        profile: SecondaryProfileDetector.UnaffiliatedProfileInfo
    ): String {
        return when (profile.profileType) {
            SecondaryProfileDetector.ProfileType.CLONE_APP -> {
                if (profile.clonedAppNames.isNotEmpty()) {
                    context.getString(
                        R.string.dialog_confirm_remove_clone_single_msg,
                        profile.clonedAppNames.joinToString(", "),
                        profile.userId
                    )
                } else {
                    context.getString(R.string.dialog_confirm_remove_empty_clone_msg, profile.userId)
                }
            }
            SecondaryProfileDetector.ProfileType.WORK_PROFILE,
            SecondaryProfileDetector.ProfileType.PRIVATE_SPACE,
            SecondaryProfileDetector.ProfileType.SECONDARY_USER -> {
                val typeLabel = context.getString(SecondaryProfileDetector.getProfileTypeLabelRes(profile.profileType))
                context.getString(R.string.dialog_confirm_remove_work_or_private_msg, typeLabel, profile.userId)
            }
        }
    }
}
