package com.drmohammed.antiporn.provisioning.ui

import android.annotation.SuppressLint
import android.app.Dialog
import android.content.DialogInterface
import android.content.res.ColorStateList
import android.os.Bundle
import android.os.SystemClock
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.os.bundleOf
import androidx.fragment.app.DialogFragment
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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Rotation-safe [DialogFragment] presenting detected blocking secondary users/profiles,
 * enforcing a 5-second safety countdown and explicit consent checkbox across configuration changes.
 */
class BlockingSecondaryUsersDialogFragment : DialogFragment() {

    private var usersJson: String = ""
    private var users: List<BlockingSecondaryUser> = emptyList()
    private var countdownStartElapsedMs: Long = 0L
    private var isConsentChecked: Boolean = false
    private var actionTaken: Boolean = false
    private var countdownJob: Job? = null

    private var activeChkConsent: MaterialCheckBox? = null
    private var activeBtnConfirm: MaterialButton? = null

    @SuppressLint("InflateParams")
    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val ctx = requireContext()
        usersJson = savedInstanceState?.getString(STATE_USERS_JSON)
            ?: arguments?.getString(ARG_USERS_JSON).orEmpty()
        users = BlockingSecondaryUser.listFromJson(usersJson)
        countdownStartElapsedMs = savedInstanceState?.getLong(
            STATE_COUNTDOWN_START_ELAPSED_MS,
            SystemClock.elapsedRealtime()
        ) ?: SystemClock.elapsedRealtime()
        isConsentChecked = savedInstanceState?.getBoolean(STATE_CONSENT_CHECKED, false) ?: false

        if (users.isEmpty()) {
            dismissAllowingStateLoss()
            return MaterialAlertDialogBuilder(ctx).create()
        }

        val inflater = layoutInflater
        val dialogView = inflater.inflate(R.layout.dialog_blocking_secondary_users, null)
        val container = dialogView.findViewById<LinearLayout>(R.id.layoutBlockingUsersContainer)
        val chkConsent = dialogView.findViewById<MaterialCheckBox>(R.id.chkBlockingConsent)
        val btnOpenSettings = dialogView.findViewById<MaterialButton>(R.id.btnOpenBlockingSettings)
        val btnConfirm = dialogView.findViewById<MaterialButton>(R.id.btnConfirmRemoveAndActivate)
        val btnCancel = dialogView.findViewById<MaterialButton>(R.id.btnCancelBlockingUsers)

        activeChkConsent = chkConsent
        activeBtnConfirm = btnConfirm

        val colorError = MaterialColors.getColor(
            ctx,
            androidx.appcompat.R.attr.colorError,
            ContextCompat.getColor(ctx, R.color.status_red)
        )
        val colorOnSurfaceVariant = MaterialColors.getColor(
            ctx,
            com.google.android.material.R.attr.colorOnSurfaceVariant,
            ContextCompat.getColor(ctx, R.color.md_theme_light_onSurfaceVariant)
        )
        val colorOnSurface = MaterialColors.getColor(
            ctx,
            com.google.android.material.R.attr.colorOnSurface,
            ContextCompat.getColor(ctx, R.color.md_theme_light_onSurface)
        )
        val colorWhite = ContextCompat.getColor(ctx, R.color.white)
        val colorRed = ContextCompat.getColor(ctx, R.color.status_red)

        users.forEach { user ->
            val itemView = inflater.inflate(R.layout.item_blocking_secondary_user, container, false)
            val tvTitle = itemView.findViewById<TextView>(R.id.tvUserCategoryTitle)
            val tvApps = itemView.findViewById<TextView>(R.id.tvUserAffectedApps)
            val tvNote = itemView.findViewById<TextView>(R.id.tvUserCategoryNote)

            tvTitle.text = ctx.getString(categoryTitleRes(user.category), user.userId)
            tvNote.setText(categoryNoteRes(user.category))

            when {
                user.installedAppNames.isNotEmpty() -> {
                    val formattedApps = if (user.installedAppNames.size > 6) {
                        val firstSix = user.installedAppNames.take(6).joinToString(", ")
                        ctx.getString(
                            R.string.provisioning_blocking_apps_more_format,
                            firstSix,
                            user.installedAppNames.size - 6
                        )
                    } else {
                        user.installedAppNames.joinToString(", ")
                    }
                    tvApps.text = ctx.getString(R.string.provisioning_blocking_apps_format, formattedApps)
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

        // main: explicit disabled-state colors so the confirm button is never shown as enabled
        // while the safety countdown is running or consent has not been given.
        val disabledBg = ColorUtils.setAlphaComponent(colorOnSurface, 31)
        val disabledText = ColorUtils.setAlphaComponent(colorOnSurface, 97)
        btnConfirm.backgroundTintList = ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_enabled), intArrayOf()),
            intArrayOf(colorRed, disabledBg)
        )
        btnConfirm.setTextColor(
            ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_enabled), intArrayOf()),
                intArrayOf(colorWhite, disabledText)
            )
        )

        chkConsent.isChecked = isConsentChecked
        updateConfirmButtonState()

        chkConsent.setOnCheckedChangeListener { _, checked ->
            isConsentChecked = checked
            updateConfirmButtonState()
        }

        btnOpenSettings.setOnClickListener {
            deliverAction(ACTION_OPEN_SETTINGS)
        }

        btnConfirm.setOnClickListener {
            if (actionTaken || remainingSeconds() > 0 || !chkConsent.isChecked) return@setOnClickListener
            actionTaken = true
            btnConfirm.isEnabled = false
            countdownJob?.cancel()
            countdownJob = null
            val approvedIds = users.map { it.userId }.filter { it > 0 }.toIntArray()
            val reqKey = arguments?.getString(ARG_REQUEST_KEY) ?: REQUEST_KEY
            parentFragmentManager.setFragmentResult(
                reqKey,
                bundleOf(
                    RESULT_ACTION to ACTION_CONFIRM,
                    RESULT_USER_IDS to approvedIds
                )
            )
            dismissAllowingStateLoss()
        }

        btnCancel.setOnClickListener {
            deliverCancelAndDismiss()
        }

        isCancelable = true
        return MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.provisioning_blocking_dialog_title)
            .setView(dialogView)
            .setCancelable(true)
            .create()
    }

    override fun onStart() {
        super.onStart()
        updateConfirmButtonState()
        if (remainingSeconds() > 0) {
            countdownJob?.cancel()
            countdownJob = lifecycleScope.launch {
                while (isActive && remainingSeconds() > 0) {
                    delay(1000L)
                    if (!isAdded) break
                    updateConfirmButtonState()
                }
            }
        }
    }

    override fun onStop() {
        countdownJob?.cancel()
        countdownJob = null
        super.onStop()
    }

    override fun onCancel(dialog: DialogInterface) {
        super.onCancel(dialog)
        if (!actionTaken) {
            actionTaken = true
            deliverAction(ACTION_CANCEL)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_USERS_JSON, usersJson)
        outState.putLong(STATE_COUNTDOWN_START_ELAPSED_MS, countdownStartElapsedMs)
        outState.putBoolean(STATE_CONSENT_CHECKED, activeChkConsent?.isChecked ?: isConsentChecked)
    }

    override fun onDestroy() {
        countdownJob?.cancel()
        countdownJob = null
        activeChkConsent = null
        activeBtnConfirm = null
        super.onDestroy()
    }

    private fun remainingSeconds(): Int {
        val elapsedSec = ((SystemClock.elapsedRealtime() - countdownStartElapsedMs) / 1000L).toInt()
        return (SAFETY_COUNTDOWN_SECONDS - elapsedSec).coerceAtLeast(0)
    }

    private fun updateConfirmButtonState() {
        val btnConfirm = activeBtnConfirm ?: return
        val chkConsent = activeChkConsent ?: return
        val ctx = context ?: return
        val rem = remainingSeconds()
        if (rem > 0) {
            btnConfirm.text = ctx.getString(R.string.provisioning_blocking_btn_confirm_countdown, rem)
        } else {
            btnConfirm.setText(R.string.provisioning_blocking_btn_confirm_ready)
        }
        val ready = rem <= 0 && chkConsent.isChecked
        btnConfirm.isEnabled = ready
        btnConfirm.alpha = if (ready) 1f else 0.7f
    }

    private fun deliverAction(action: String) {
        val reqKey = arguments?.getString(ARG_REQUEST_KEY) ?: REQUEST_KEY
        parentFragmentManager.setFragmentResult(
            reqKey,
            bundleOf(
                RESULT_ACTION to action,
                RESULT_USERS_JSON to usersJson
            )
        )
    }

    private fun deliverCancelAndDismiss() {
        if (!actionTaken) {
            actionTaken = true
            deliverAction(ACTION_CANCEL)
        }
        dismissAllowingStateLoss()
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
        const val TAG = "BlockingSecondaryUsersDialog"
        const val REQUEST_KEY = "blocking_secondary_users_request"
        const val RESULT_ACTION = "result_action"
        const val RESULT_USER_IDS = "result_user_ids"
        const val RESULT_USERS_JSON = "result_users_json"

        const val ACTION_OPEN_SETTINGS = "action_open_settings"
        const val ACTION_CONFIRM = "action_confirm"
        const val ACTION_CANCEL = "action_cancel"

        private const val ARG_REQUEST_KEY = "arg_request_key"
        private const val ARG_USERS_JSON = "arg_users_json"
        private const val STATE_USERS_JSON = "state_users_json"
        private const val STATE_COUNTDOWN_START_ELAPSED_MS = "state_countdown_start_elapsed_ms"
        private const val STATE_CONSENT_CHECKED = "state_consent_checked"
        private const val SAFETY_COUNTDOWN_SECONDS = 5

        fun newInstance(
            users: List<BlockingSecondaryUser>,
            requestKey: String = REQUEST_KEY
        ): BlockingSecondaryUsersDialogFragment {
            return BlockingSecondaryUsersDialogFragment().apply {
                arguments = bundleOf(
                    ARG_REQUEST_KEY to requestKey,
                    ARG_USERS_JSON to BlockingSecondaryUser.listToJson(users)
                )
            }
        }
    }
}
