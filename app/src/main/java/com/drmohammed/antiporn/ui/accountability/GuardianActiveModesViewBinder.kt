package com.drmohammed.antiporn.ui.accountability

import android.os.Build
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import com.drmohammed.antiporn.R
import com.drmohammed.antiporn.accessibility.control.AccessibilityPolicyCoordinator
import com.drmohammed.antiporn.core.model.DevicePolicyBundle
import com.drmohammed.antiporn.fortress.model.FortressProfile
import com.drmohammed.antiporn.ui.home.HomeFortressesViewBinder
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * In-screen view binder inside [GuardianControlActivity] displaying the ward's active
 * Fortress and Night Curfew telemetry with 1-tap confirmed remote unlock controls.
 */
internal class GuardianActiveModesViewBinder(
    private val activity: AppCompatActivity,
    private val onUnlockModeRequested: (target: String) -> Unit
) {
    private val cardBanner: MaterialCardView? = activity.findViewById(R.id.cardWardActiveModesBanner)
    private val tvFortressStatus: TextView? = activity.findViewById(R.id.tvWardActiveFortressStatus)
    private val tvCurfewStatus: TextView? = activity.findViewById(R.id.tvWardActiveCurfewStatus)
    private val btnUnlockFortress: MaterialButton? = activity.findViewById(R.id.btnGuardianUnlockFortress)
    private val btnUnlockCurfew: MaterialButton? = activity.findViewById(R.id.btnGuardianUnlockCurfew)
    private val btnUnlockAll: MaterialButton? = activity.findViewById(R.id.btnGuardianUnlockAllModes)
    private val cardCapabilityAlert: MaterialCardView? = activity.findViewById(R.id.cardWardCapabilityAlert)
    private val ivCapabilityIcon: ImageView? = activity.findViewById(R.id.ivWardCapabilityAlertIcon)
    private val tvCapabilityTitle: TextView? = activity.findViewById(R.id.tvWardCapabilityAlertTitle)
    private val tvCapabilityMsg: TextView? = activity.findViewById(R.id.tvWardCapabilityAlertMsg)

    private var confirmDialog: AlertDialog? = null
    private var latestState: GuardianControlUiState = GuardianControlUiState()

    fun setupListeners() {
        btnUnlockFortress?.setOnClickListener {
            confirmAndUnlock(
                target = DevicePolicyBundle.REMOTE_MODE_UNLOCK_FORTRESS,
                msgRes = R.string.guardian_confirm_unlock_fortress_msg
            )
        }
        btnUnlockCurfew?.setOnClickListener {
            confirmAndUnlock(
                target = DevicePolicyBundle.REMOTE_MODE_UNLOCK_CURFEW,
                msgRes = R.string.guardian_confirm_unlock_curfew_msg
            )
        }
        btnUnlockAll?.setOnClickListener {
            confirmAndUnlock(
                target = DevicePolicyBundle.REMOTE_MODE_UNLOCK_ALL,
                msgRes = R.string.guardian_confirm_unlock_all_msg
            )
        }
    }

    fun render(state: GuardianControlUiState) {
        latestState = state
        val nowMs = com.drmohammed.antiporn.core.time.TrustedTimeProvider.currentTimeMillis(activity.applicationContext)
        val remainingMs = (state.wardActiveFortressEndsAtWall - nowMs).coerceAtLeast(0L)
        val isFortressActive = !state.wardActiveFortressId.isNullOrBlank() && remainingMs > 0L
        val isCurfewActive = state.wardIsCurfewActiveNow

        if (!isFortressActive && !isCurfewActive) {
            cardBanner?.visibility = View.GONE
            return
        }

        cardBanner?.visibility = View.VISIBLE
        tvFortressStatus?.isVisible = isFortressActive
        if (isFortressActive) {
            val resolvedName = state.wardActiveFortressName?.takeIf { it.isNotBlank() }
                ?: if (state.wardActiveFortressIsEmergency || state.wardActiveFortressId == FortressProfile.EMERGENCY_FORTRESS_ID) {
                    activity.getString(R.string.fortress_emergency_default_name)
                } else {
                    state.wardActiveFortressId.orEmpty()
                }
            val countdown = HomeFortressesViewBinder.formatCountdown(remainingMs)
            tvFortressStatus?.text = activity.getString(
                R.string.guardian_active_fortress_status,
                resolvedName,
                countdown
            )
        }

        tvCurfewStatus?.isVisible = isCurfewActive
        btnUnlockFortress?.isVisible = isFortressActive
        btnUnlockCurfew?.isVisible = isCurfewActive
        btnUnlockAll?.isVisible = isFortressActive && isCurfewActive

        val enabled = !state.isSaving && !state.isLoading
        btnUnlockFortress?.isEnabled = enabled
        btnUnlockCurfew?.isEnabled = enabled
        btnUnlockAll?.isEnabled = enabled
    }

    fun hasActiveCountdown(state: GuardianControlUiState): Boolean =
        !state.wardActiveFortressId.isNullOrBlank() &&
            state.wardActiveFortressEndsAtWall > com.drmohammed.antiporn.core.time.TrustedTimeProvider.currentTimeMillis(activity.applicationContext)

    fun needsCountdownTick(state: GuardianControlUiState): Boolean =
        tvFortressStatus?.isVisible == true || hasActiveCountdown(state)

    private fun confirmAndUnlock(target: String, msgRes: Int) {
        if (activity.isFinishing || activity.isDestroyed || confirmDialog?.isShowing == true) return
        val displayName = latestState.wardName.trim().ifBlank {
            activity.getString(R.string.status_ward_badge)
        }
        confirmDialog = MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.guardian_confirm_unlock_title)
            .setMessage(activity.getString(msgRes, displayName))
            .setPositiveButton(android.R.string.ok) { d, _ ->
                d.dismiss()
                onUnlockModeRequested(target)
            }
            .setNegativeButton(R.string.dialog_btn_cancel, null)
            .setOnDismissListener { confirmDialog = null }
            .show()
    }

    fun renderWardCapabilityAlert(state: GuardianControlUiState) {
        val card = cardCapabilityAlert ?: return
        val currentPolicy = state.currentPolicy
        val wardSdk = state.wardSdkInt ?: Build.VERSION.SDK_INT
        val isA11yRequired = AccessibilityPolicyCoordinator.isAccessibilityRequired(currentPolicy, sdkInt = wardSdk)
        val isUsageRequired = AccessibilityPolicyCoordinator.isUsageAccessRequired(
            currentPolicy,
            isA11yActive = state.wardHasAccessibilityService == true,
            sdkInt = wardSdk
        )
        when {
            state.wardA11yTamperAt > 0L && state.wardHasAccessibilityService == false && isA11yRequired -> {
                val red = ContextCompat.getColor(activity, R.color.status_red)
                applyCapabilityStyle(
                    card = card,
                    strokeAndTint = red,
                    bgColor = ContextCompat.getColor(activity, R.color.status_red_bg),
                    textColor = red,
                    titleRes = R.string.guardian_alert_a11y_tamper_title,
                    msgRes = R.string.guardian_alert_a11y_tamper_msg
                )
            }
            state.wardHasAccessibilityService == false && state.wardCanWriteSecureSettings == false && isA11yRequired -> {
                val amber = ContextCompat.getColor(activity, R.color.shield_amber_calm)
                applyCapabilityStyle(
                    card = card,
                    strokeAndTint = amber,
                    bgColor = ContextCompat.getColor(activity, R.color.shield_amber_subtle),
                    textColor = MaterialColors.getColor(card, com.google.android.material.R.attr.colorOnSurface),
                    titleRes = R.string.guardian_alert_a11y_pending_qr_title,
                    msgRes = R.string.guardian_alert_a11y_pending_qr_msg
                )
            }
            state.wardHasUsageStats == false && isUsageRequired -> {
                val amber = ContextCompat.getColor(activity, R.color.shield_amber_calm)
                applyCapabilityStyle(
                    card = card,
                    strokeAndTint = amber,
                    bgColor = ContextCompat.getColor(activity, R.color.shield_amber_subtle),
                    textColor = MaterialColors.getColor(card, com.google.android.material.R.attr.colorOnSurface),
                    titleRes = R.string.guardian_alert_usage_access_pending_title,
                    msgRes = R.string.guardian_alert_usage_access_pending_msg
                )
            }
            else -> card.visibility = View.GONE
        }
    }

    private fun applyCapabilityStyle(
        card: MaterialCardView,
        strokeAndTint: Int,
        bgColor: Int,
        textColor: Int,
        titleRes: Int,
        msgRes: Int
    ) {
        card.visibility = View.VISIBLE
        card.setCardBackgroundColor(bgColor)
        card.strokeColor = strokeAndTint
        ivCapabilityIcon?.setColorFilter(strokeAndTint)
        tvCapabilityTitle?.setTextColor(textColor)
        tvCapabilityMsg?.setTextColor(textColor)
        tvCapabilityTitle?.setText(titleRes)
        tvCapabilityMsg?.setText(msgRes)
    }

    fun onDestroy() {
        confirmDialog?.dismiss()
        confirmDialog = null
    }
}
