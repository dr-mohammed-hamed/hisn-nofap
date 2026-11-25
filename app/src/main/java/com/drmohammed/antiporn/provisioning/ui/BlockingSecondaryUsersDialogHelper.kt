package com.drmohammed.antiporn.provisioning.ui

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.lifecycle.lifecycleScope
import com.drmohammed.antiporn.R
import com.drmohammed.antiporn.provisioning.engine.BlockingSecondaryUser
import com.drmohammed.antiporn.provisioning.engine.BlockingUserCategory
import com.google.android.material.button.MaterialButton
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Collections
import java.util.WeakHashMap

/**
 * Presents a transparent, explicit-consent confirmation dialog listing every OS-level
 * secondary user/profile detected via ADB and its affected apps, enforcing a 5-second
 * safety countdown + explicit checkbox before deletion can proceed.
 */
class BlockingSecondaryUsersDialogHelper {

    private var activeDialog: AlertDialog? = null
    private var countdownJob: Job? = null
    private var activeUserIds: Set<Int> = emptySet()
    private val suppressedDialogs = Collections.newSetFromMap(WeakHashMap<AlertDialog, Boolean>())

    fun show(
        activity: AppCompatActivity,
        users: List<BlockingSecondaryUser>,
        onOpenSettings: () -> Unit,
        onConfirmedDeleteAndActivate: (Set<Int>) -> Unit,
        onCancel: () -> Unit = {}
    ) {
        if (activity.isFinishing || activity.isDestroyed || users.isEmpty()) return
        val incomingIds = users.map { it.userId }.toSet()
        if (activeDialog?.isShowing == true && incomingIds == activeUserIds) {
            return
        }

        dismiss()
        activeUserIds = incomingIds

        val inflater = LayoutInflater.from(activity)
        val dialogView = inflater.inflate(R.layout.dialog_blocking_secondary_users, null)
        val container = dialogView.findViewById<LinearLayout>(R.id.layoutBlockingUsersContainer)
        val chkConsent = dialogView.findViewById<MaterialCheckBox>(R.id.chkBlockingConsent)
        val btnOpenSettings = dialogView.findViewById<MaterialButton>(R.id.btnOpenBlockingSettings)
        val btnConfirm = dialogView.findViewById<MaterialButton>(R.id.btnConfirmRemoveAndActivate)
        val btnCancel = dialogView.findViewById<MaterialButton>(R.id.btnCancelBlockingUsers)

        val colorError = MaterialColors.getColor(
            activity,
            androidx.appcompat.R.attr.colorError,
            ContextCompat.getColor(activity, R.color.status_red)
        )
        val colorOnSurfaceVariant = MaterialColors.getColor(
            activity,
            com.google.android.material.R.attr.colorOnSurfaceVariant,
            ContextCompat.getColor(activity, R.color.md_theme_light_onSurfaceVariant)
        )
        val colorOnSurface = MaterialColors.getColor(
            activity,
            com.google.android.material.R.attr.colorOnSurface,
            ContextCompat.getColor(activity, R.color.md_theme_light_onSurface)
        )
        val colorWhite = ContextCompat.getColor(activity, R.color.white)
        val colorRed = ContextCompat.getColor(activity, R.color.status_red)

        users.forEach { user ->
            val itemView = inflater.inflate(R.layout.item_blocking_secondary_user, container, false)
            val tvTitle = itemView.findViewById<TextView>(R.id.tvUserCategoryTitle)
            val tvApps = itemView.findViewById<TextView>(R.id.tvUserAffectedApps)
            val tvNote = itemView.findViewById<TextView>(R.id.tvUserCategoryNote)

            tvTitle.text = activity.getString(categoryTitleRes(user.category), user.userId)
            tvNote.setText(categoryNoteRes(user.category))

            when {
                user.installedAppNames.isNotEmpty() -> {
                    val formattedApps = if (user.installedAppNames.size > 6) {
                        val firstSix = user.installedAppNames.take(6).joinToString(", ")
                        activity.getString(
                            R.string.provisioning_blocking_apps_more_format,
                            firstSix,
                            user.installedAppNames.size - 6
                        )
                    } else {
                        user.installedAppNames.joinToString(", ")
                    }
                    tvApps.text = activity.getString(R.string.provisioning_blocking_apps_format, formattedApps)
                    tvApps.setTextColor(colorError)
                }

                user.isAppsInspectionBlocked -> {
                    tvApps.setText(R.string.provisioning_blocking_apps_encrypted_warning)
                    tvApps.setTextColor(colorError)
                }

                else -> {
                    tvApps.setText(R.string.provisioning_blocking_apps_none_visible)
                    tvApps.setTextColor(colorOnSurfaceVariant)
                }
            }

            container.addView(itemView)
        }

        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.provisioning_blocking_dialog_title)
            .setView(dialogView)
            .setCancelable(true)
            .create()
        activeDialog = dialog

        var remainingSeconds = SAFETY_COUNTDOWN_SECONDS
        var actionTaken = false

        val disabledBg = ColorUtils.setAlphaComponent(colorOnSurface, 31)
        val disabledText = ColorUtils.setAlphaComponent(colorOnSurface, 97)
        val confirmBgList = ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_enabled), intArrayOf()),
            intArrayOf(colorRed, disabledBg)
        )
        val confirmTextList = ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_enabled), intArrayOf()),
            intArrayOf(colorWhite, disabledText)
        )
        btnConfirm.backgroundTintList = confirmBgList
        btnConfirm.setTextColor(confirmTextList)

        fun updateConfirmButtonState() {
            if (remainingSeconds > 0) {
                btnConfirm.text = activity.getString(
                    R.string.provisioning_blocking_btn_confirm_countdown,
                    remainingSeconds
                )
            } else {
                btnConfirm.setText(R.string.provisioning_blocking_btn_confirm_ready)
            }
            btnConfirm.isEnabled = (remainingSeconds <= 0 && chkConsent.isChecked)
        }

        updateConfirmButtonState()

        chkConsent.setOnCheckedChangeListener { _, _ ->
            updateConfirmButtonState()
        }

        countdownJob = activity.lifecycleScope.launch {
            while (remainingSeconds > 0) {
                delay(1000L)
                remainingSeconds--
                if (!activity.isFinishing && !activity.isDestroyed) {
                    updateConfirmButtonState()
                }
            }
        }

        btnOpenSettings.setOnClickListener {
            onOpenSettings()
        }

        btnConfirm.setOnClickListener {
            if (actionTaken || remainingSeconds > 0 || !chkConsent.isChecked) return@setOnClickListener
            actionTaken = true
            btnConfirm.isEnabled = false
            countdownJob?.cancel()
            countdownJob = null
            if (!activity.isFinishing && !activity.isDestroyed) {
                suppressedDialogs.add(dialog)
                dialog.dismiss()
            }
            val approvedIds = users.map { it.userId }.filter { it > 0 }.toSet()
            onConfirmedDeleteAndActivate(approvedIds)
        }

        btnCancel.setOnClickListener {
            if (!activity.isFinishing && !activity.isDestroyed) {
                dialog.dismiss()
            }
        }

        dialog.setOnDismissListener {
            countdownJob?.cancel()
            countdownJob = null
            if (activeDialog === dialog) {
                activeDialog = null
                activeUserIds = emptySet()
            }
            val suppressCancel = suppressedDialogs.remove(dialog)
            if (!actionTaken && !suppressCancel) {
                onCancel()
            }
        }

        dialog.show()
    }

    fun dismiss() {
        countdownJob?.cancel()
        countdownJob = null
        activeDialog?.let { dialog ->
            if (dialog.isShowing) {
                suppressedDialogs.add(dialog)
                runCatching { dialog.dismiss() }
            }
        }
        activeDialog = null
        activeUserIds = emptySet()
    }

    @StringRes
    private fun categoryTitleRes(category: BlockingUserCategory): Int = when (category) {
        BlockingUserCategory.SECURE_FOLDER -> R.string.provisioning_blocking_cat_secure_folder_title
        BlockingUserCategory.DUAL_APP_CLONE -> R.string.provisioning_blocking_cat_dual_app_title
        BlockingUserCategory.PRIVATE_SPACE -> R.string.provisioning_blocking_cat_private_space_title
        BlockingUserCategory.WORK_PROFILE -> R.string.provisioning_blocking_cat_work_profile_title
        BlockingUserCategory.GUEST_USER -> R.string.provisioning_blocking_cat_guest_user_title
        BlockingUserCategory.SECONDARY_USER -> R.string.provisioning_blocking_cat_secondary_user_title
    }

    @StringRes
    private fun categoryNoteRes(category: BlockingUserCategory): Int = when (category) {
        BlockingUserCategory.SECURE_FOLDER -> R.string.provisioning_blocking_cat_secure_folder_note
        BlockingUserCategory.DUAL_APP_CLONE -> R.string.provisioning_blocking_cat_dual_app_note
        BlockingUserCategory.PRIVATE_SPACE -> R.string.provisioning_blocking_cat_private_space_note
        BlockingUserCategory.WORK_PROFILE -> R.string.provisioning_blocking_cat_work_profile_note
        BlockingUserCategory.GUEST_USER -> R.string.provisioning_blocking_cat_guest_user_note
        BlockingUserCategory.SECONDARY_USER -> R.string.provisioning_blocking_cat_secondary_user_note
    }

    companion object {
        private const val SAFETY_COUNTDOWN_SECONDS = 5
    }
}
