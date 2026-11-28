package com.drmohammed.antiporn.ui.home

import android.content.Context
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import com.drmohammed.antiporn.R
import com.drmohammed.antiporn.core.engine.ProtectionEngine
import com.drmohammed.antiporn.databinding.FragmentHomeBinding
import com.drmohammed.antiporn.fortress.engine.FortressSessionController
import com.drmohammed.antiporn.fortress.model.ActiveFortressSession
import com.drmohammed.antiporn.fortress.model.FortressProfile
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.util.Locale

/**
 * Renders the Emergency Fortress hero controls and dynamic custom Fortresses list
 * inside [HomeFragment], keeping 1-second countdown updates flicker-free.
 */
internal class HomeFortressesViewBinder(
    private val contextProvider: () -> Context?,
    private val bindingProvider: () -> FragmentHomeBinding?,
    private val onStartFortress: (FortressProfile, (Boolean) -> Unit) -> Unit,
    private val onExtendFortress: ((Boolean) -> Unit) -> Unit
) {
    private data class CustomRowViews(
        val profile: FortressProfile,
        val btnStart: MaterialButton,
        val layoutActive: LinearLayout,
        val tvCountdown: TextView,
        val btnExtend: MaterialButton
    )

    private var lastTapMs = 0L
    private var confirmDialog: AlertDialog? = null
    private var isOperationPending = false
    private var renderedCustomProfiles: List<FortressProfile> = emptyList()
    private val customRowMap = mutableMapOf<String, CustomRowViews>()
    private var currentState: HomeUiState = HomeUiState()

    fun setupListeners() {
        val b = bindingProvider() ?: return
        b.btnHeroEmergencyFortressStart?.setOnClickListener {
            val emergency = currentState.fortressesConfig.getEmergencyFortress()
            promptAndStartFortress(emergency)
        }
        b.btnEmergencyFortressExtend?.setOnClickListener {
            handleExtendClicked()
        }
    }

    fun render(state: HomeUiState) {
        currentState = state
        val ctx = contextProvider() ?: return
        val b = bindingProvider() ?: return
        val emergency = state.fortressesConfig.getEmergencyFortress()
        val customs = state.fortressesConfig.getCustomFortresses()
        val active = state.activeFortress

        val isEmergencyActive = active != null && (active.isEmergency || active.fortressId == emergency.id)
        val hasAnyActive = active != null

        b.btnHeroEmergencyFortressStart?.apply {
            text = if (isEmergencyActive) {
                emergency.resolveDisplayName(ctx)
            } else {
                ctx.getString(
                    R.string.home_btn_start_emergency_fortress,
                    emergency.resolveDisplayName(ctx),
                    emergency.durationMinutes
                )
            }
            isEnabled = !hasAnyActive && !isOperationPending
            alpha = if (hasAnyActive && !isEmergencyActive) 0.55f else 1.0f
        }

        b.layoutEmergencyFortressActive?.isVisible = isEmergencyActive
        if (isEmergencyActive) {
            val session = active ?: return
            b.tvEmergencyFortressCountdown?.text = ctx.getString(
                R.string.fortress_countdown_format,
                formatCountdown(session.remainingMs)
            )
            b.btnEmergencyFortressExtend?.text = ctx.getString(
                R.string.home_btn_extend_fortress_minutes,
                session.durationMinutes
            )
            b.btnEmergencyFortressExtend?.isEnabled = !isOperationPending
        }

        if (customs != renderedCustomProfiles) {
            rebuildCustomRows(ctx, b.layoutCustomFortressesContainer, customs)
        }
        b.layoutCustomFortressesContainer?.isVisible = customs.isNotEmpty()
        updateCustomRowsState(ctx, active)
    }

    private fun rebuildCustomRows(
        ctx: Context,
        container: LinearLayout?,
        customs: List<FortressProfile>
    ) {
        if (container == null) return
        container.removeAllViews()
        customRowMap.clear()
        renderedCustomProfiles = customs
        val density = ctx.resources.displayMetrics.density

        for (profile in customs) {
            val card = MaterialCardView(ctx).apply {
                radius = 16f * density
                cardElevation = 0f
                setCardBackgroundColor(MaterialColors.getColor(this, com.google.android.material.R.attr.colorSurfaceContainerHigh))
                strokeWidth = (1 * density).toInt()
                strokeColor = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOutlineVariant)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = (8 * density).toInt()
                }
            }

            val root = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                val pad = (12 * density).toInt()
                setPadding(pad, pad, pad, pad)
            }

            val topRow = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }

            val textCol = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginEnd = (10 * density).toInt()
                }
            }

            val tvName = TextView(ctx).apply {
                text = profile.resolveDisplayName(ctx)
                textSize = 14f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurface))
            }
            val netLabel = ctx.getString(
                if (profile.isInternetCutEnabled) R.string.fortress_internet_cut_on else R.string.fortress_internet_cut_off
            )
            val tvMeta = TextView(ctx).apply {
                text = ctx.getString(R.string.fortress_duration_minutes_format, profile.durationMinutes) +
                    " ${ctx.getString(R.string.fortress_meta_separator)} " + netLabel
                textSize = 12f
                setTextColor(MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurfaceVariant))
            }
            textCol.addView(tvName)
            textCol.addView(tvMeta)

            val btnStart = MaterialButton(ctx, null, com.google.android.material.R.attr.materialButtonTonalStyle).apply {
                text = ctx.getString(R.string.home_btn_start_custom_fortress, profile.durationMinutes)
                textSize = 12f
                cornerRadius = (12 * density).toInt()
                minHeight = (48 * density).toInt()
                setOnClickListener { promptAndStartFortress(profile) }
            }
            topRow.addView(textCol)
            topRow.addView(btnStart)

            val activeRow = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                visibility = View.GONE
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = (10 * density).toInt()
                }
            }

            val tvCountdown = TextView(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                setBackgroundResource(R.drawable.bg_active_shields_pill)
                gravity = Gravity.CENTER
                setPadding((10 * density).toInt(), (6 * density).toInt(), (10 * density).toInt(), (6 * density).toInt())
                setTextColor(ContextCompat.getColor(ctx, R.color.status_green))
                textSize = 13f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            }

            val btnExtend = MaterialButton(ctx, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                textSize = 12f
                cornerRadius = (10 * density).toInt()
                minHeight = (48 * density).toInt()
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    marginStart = (8 * density).toInt()
                }
                setOnClickListener { handleExtendClicked() }
            }

            activeRow.addView(tvCountdown)
            activeRow.addView(btnExtend)

            root.addView(topRow)
            root.addView(activeRow)
            card.addView(root)
            container.addView(card)

            customRowMap[profile.id] = CustomRowViews(profile, btnStart, activeRow, tvCountdown, btnExtend)
        }
    }

    private fun updateCustomRowsState(ctx: Context, active: ActiveFortressSession?) {
        val hasAnyActive = active != null
        for ((id, row) in customRowMap) {
            val isThisActive = active != null && active.fortressId == id
            row.btnStart.isEnabled = !hasAnyActive && !isOperationPending
            row.btnStart.alpha = if (hasAnyActive && !isThisActive) 0.5f else 1.0f
            row.btnStart.text = if (isThisActive) {
                ctx.getString(R.string.home_fortress_active_badge)
            } else {
                ctx.getString(R.string.home_btn_start_custom_fortress, row.profile.durationMinutes)
            }
            row.layoutActive.isVisible = isThisActive
            if (isThisActive) {
                val session = active ?: continue
                row.tvCountdown.text = ctx.getString(
                    R.string.fortress_countdown_format,
                    formatCountdown(session.remainingMs)
                )
                row.btnExtend.text = ctx.getString(
                    R.string.home_btn_extend_fortress_minutes,
                    session.durationMinutes
                )
                row.btnExtend.isEnabled = !isOperationPending
            }
        }
    }

    private fun promptAndStartFortress(profile: FortressProfile) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastTapMs < 700L) return
        lastTapMs = now

        val ctx = contextProvider() ?: return
        val act = ctx as? android.app.Activity
        if (act != null && (act.isFinishing || act.isDestroyed)) return
        val appCtx = ctx.applicationContext
        if (FortressSessionController.isFortressActive(appCtx)) {
            Toast.makeText(appCtx, R.string.toast_fortress_another_active, Toast.LENGTH_SHORT).show()
            return
        }
        if (confirmDialog?.isShowing == true) return

        val displayName = profile.resolveDisplayName(ctx)
        val msg = when {
            profile.isEmergency -> ctx.getString(R.string.dialog_fortress_start_msg_emergency, profile.durationMinutes)
            profile.isInternetCutEnabled -> ctx.getString(R.string.dialog_fortress_start_msg_custom_net_cut, profile.durationMinutes)
            else -> ctx.getString(R.string.dialog_fortress_start_msg_custom_no_net, profile.durationMinutes)
        }

        confirmDialog = MaterialAlertDialogBuilder(ctx)
            .setTitle(ctx.getString(R.string.dialog_fortress_start_title, displayName))
            .setMessage(msg)
            .setPositiveButton(android.R.string.ok) { dialog, _ ->
                dialog.dismiss()
                isOperationPending = true
                render(currentState)
                onStartFortress(profile) { success ->
                    isOperationPending = false
                    if (success) {
                        Toast.makeText(
                            appCtx,
                            appCtx.getString(R.string.toast_fortress_started, displayName, profile.durationMinutes),
                            Toast.LENGTH_SHORT
                        ).show()
                    } else {
                        val activeOther = FortressSessionController.getActiveSession(appCtx)?.let {
                            it.fortressId != profile.id
                        } == true
                        val errorRes = when {
                            activeOther -> R.string.toast_fortress_another_active
                            !ProtectionEngine.isDeviceOwner(appCtx) -> R.string.status_no_device_owner_title
                            else -> R.string.status_need_enforce_desc
                        }
                        Toast.makeText(appCtx, errorRes, Toast.LENGTH_SHORT).show()
                    }
                    render(currentState)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .setOnDismissListener { confirmDialog = null }
            .show()
    }

    private fun handleExtendClicked() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastTapMs < 700L) return
        lastTapMs = now

        val ctx = contextProvider() ?: return
        val appCtx = ctx.applicationContext
        if (!FortressSessionController.isFortressActive(appCtx)) return

        isOperationPending = true
        render(currentState)
        onExtendFortress { success ->
            isOperationPending = false
            if (success) {
                Toast.makeText(appCtx, R.string.toast_fortress_extended, Toast.LENGTH_SHORT).show()
            } else {
                val errorRes = if (!ProtectionEngine.isDeviceOwner(appCtx)) {
                    R.string.status_no_device_owner_title
                } else {
                    R.string.status_need_enforce_desc
                }
                Toast.makeText(appCtx, errorRes, Toast.LENGTH_SHORT).show()
            }
            render(currentState)
        }
    }

    fun dismiss() {
        confirmDialog?.dismiss()
        confirmDialog = null
        customRowMap.clear()
        renderedCustomProfiles = emptyList()
    }

    companion object {
        fun formatCountdown(remainingMs: Long): String {
            val totalSec = (remainingMs / 1000L).coerceAtLeast(0L)
            val hours = totalSec / 3600L
            val mins = (totalSec % 3600L) / 60L
            val secs = totalSec % 60L
            return if (hours > 0L) {
                String.format(Locale.US, "%02d:%02d:%02d", hours, mins, secs)
            } else {
                String.format(Locale.US, "%02d:%02d", mins, secs)
            }
        }
    }
}
