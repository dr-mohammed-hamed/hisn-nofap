package com.drmohammed.antiporn.ui.control

import android.content.Context
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isVisible
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.LifecycleOwner
import com.drmohammed.antiporn.R
import com.drmohammed.antiporn.databinding.FragmentSelfControlBinding
import com.drmohammed.antiporn.ui.control.qr.QrScannerBottomSheet
import com.drmohammed.antiporn.ui.control.qr.SelfControlQrManager
import com.drmohammed.antiporn.ui.home.HomeFortressesViewBinder
import com.drmohammed.antiporn.ui.shields.ActiveModeInfo
import com.drmohammed.antiporn.ui.shields.ActiveShieldsBottomSheet
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * Renders the Active Modes Unlock alert card in [SelfControlFragment], enforcing the
 * psychological safety valve rule (Emergency Fortress unlock is completely hidden when QR lock is OFF).
 */
internal class SelfControlActiveModesViewBinder(
    private val contextProvider: () -> Context?,
    private val bindingProvider: () -> FragmentSelfControlBinding?,
    private val fragmentManager: FragmentManager,
    private val lifecycleOwner: LifecycleOwner,
    private val viewModel: SelfControlViewModel,
    private val onEmergencyQrBypassTriggered: () -> Unit
) {
    private var confirmDialog: AlertDialog? = null
    private var unlockInFlight = false

    fun registerListeners() {
        fragmentManager.setFragmentResultListener(TAG_UNLOCK_CURFEW, lifecycleOwner) { _, bundle ->
            val verified = bundle.getBoolean(QrScannerBottomSheet.RESULT_KEY_VERIFIED, false)
            val bypass = bundle.getBoolean(QrScannerBottomSheet.RESULT_KEY_EMERGENCY_BYPASS, false)
            val token = bundle.getString(QrScannerBottomSheet.RESULT_KEY_SCANNED_TOKEN).orEmpty()
            if (bypass) {
                onEmergencyQrBypassTriggered()
                viewModel.unlockNightCurfewWithQr("", isEmergencyBypassCompleted = true) { ok ->
                    showUnlockFeedback(ok, R.string.toast_curfew_unlocked)
                }
            } else if (verified) {
                viewModel.unlockNightCurfewWithQr(token, isEmergencyBypassCompleted = false) { ok ->
                    showUnlockFeedback(ok, R.string.toast_curfew_unlocked)
                }
            }
        }

        fragmentManager.setFragmentResultListener(TAG_UNLOCK_FORTRESS, lifecycleOwner) { _, bundle ->
            val verified = bundle.getBoolean(QrScannerBottomSheet.RESULT_KEY_VERIFIED, false)
            val bypass = bundle.getBoolean(QrScannerBottomSheet.RESULT_KEY_EMERGENCY_BYPASS, false)
            val token = bundle.getString(QrScannerBottomSheet.RESULT_KEY_SCANNED_TOKEN).orEmpty()
            if (bypass) {
                onEmergencyQrBypassTriggered()
                viewModel.unlockActiveFortressWithQr("", isEmergencyBypassCompleted = true) { ok ->
                    showUnlockFeedback(ok, R.string.toast_fortress_unlocked)
                }
            } else if (verified) {
                viewModel.unlockActiveFortressWithQr(token, isEmergencyBypassCompleted = false) { ok ->
                    showUnlockFeedback(ok, R.string.toast_fortress_unlocked)
                }
            }
        }
    }

    fun render(state: SelfControlUiState) {
        val b = bindingProvider() ?: return
        val ctx = contextProvider() ?: return
        val snapshot = state.activeModesSnapshot
        val policy = state.policy
        val isSupervised = policy.isSupervised || !policy.guardianUid.isNullOrBlank()
        val isUnlockPermitted = !isSupervised || policy.selfControlFortressUnlockAllowed

        if (!snapshot.isAnyModeActive || !isUnlockPermitted) {
            b.cardActiveModesUnlock.visibility = View.GONE
            return
        }

        val isQrEnabled = SelfControlQrManager.getInstance(ctx.applicationContext).isQrLockEnabled()
        val showCurfewRow = snapshot.isNightCurfewActive
        val activeFortress = snapshot.activeFortress
        // Psychological safety valve: hide Emergency Fortress unlock entirely when QR lock is OFF
        val showFortressRow = activeFortress != null && (!activeFortress.isEmergency || isQrEnabled)

        if (!showCurfewRow && !showFortressRow) {
            b.cardActiveModesUnlock.visibility = View.GONE
            return
        }

        b.cardActiveModesUnlock.visibility = View.VISIBLE

        val fortressSession = activeFortress?.takeIf { showFortressRow }
        val summaryParts = mutableListOf<String>()
        val modes = mutableListOf<ActiveModeInfo>()
        if (showCurfewRow) {
            val curfewStatus = ctx.getString(R.string.self_control_active_curfew_row)
            summaryParts.add(curfewStatus)
            modes.add(
                ActiveModeInfo(
                    ActiveShieldsBottomSheet.MODE_CURFEW,
                    curfewStatus,
                    ctx.getString(R.string.card_curfew_active_desc)
                )
            )
        }
        fortressSession?.let { session ->
            val displayName = session.resolveDisplayName(ctx)
            val fortressDetail = ctx.getString(
                R.string.self_control_active_fortress_row,
                displayName,
                HomeFortressesViewBinder.formatCountdown(session.remainingMs)
            )
            summaryParts.add(fortressDetail)
            modes.add(ActiveModeInfo(ActiveShieldsBottomSheet.MODE_FORTRESS, displayName, fortressDetail))
        }
        b.tvActiveModesSummary.isVisible = summaryParts.isNotEmpty()
        b.tvActiveModesSummary.text = summaryParts.joinToString(ctx.getString(R.string.fortress_meta_separator))

        b.btnManageActiveModesSelfControl.setOnClickListener {
            ActiveShieldsBottomSheet.show(
                ctx,
                onUnlockMode = { key ->
                    // Re-read live state: the sheet can outlive the render() snapshot.
                    val qrNow = SelfControlQrManager.getInstance(ctx.applicationContext).isQrLockEnabled()
                    val liveFortress = viewModel.uiState.value.activeModesSnapshot.activeFortress
                        ?.takeIf { it.fortressId == fortressSession?.fortressId }
                    when (key) {
                        ActiveShieldsBottomSheet.MODE_CURFEW -> {
                            if (qrNow) launchQrScanner(TAG_UNLOCK_CURFEW) else showDirectCurfewConfirm()
                        }
                        ActiveShieldsBottomSheet.MODE_FORTRESS -> {
                            if (qrNow) {
                                launchQrScanner(TAG_UNLOCK_FORTRESS)
                            } else {
                                liveFortress?.takeIf { !it.isEmergency }?.let { session ->
                                    showDirectFortressConfirm(session.resolveDisplayName(ctx))
                                }
                            }
                        }
                    }
                },
                activeModes = modes
            )
        }
    }

    private fun launchQrScanner(tag: String) {
        if (fragmentManager.isStateSaved || fragmentManager.findFragmentByTag(tag) != null) return
        QrScannerBottomSheet.newInstance(tag).show(fragmentManager, tag)
    }

    private fun showDirectCurfewConfirm() {
        val ctx = contextProvider() ?: return
        val act = ctx as? android.app.Activity
        if (act != null && (act.isFinishing || act.isDestroyed)) return
        if (unlockInFlight || confirmDialog?.isShowing == true) return
        confirmDialog = MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.self_control_unlock_curfew_confirm_title)
            .setMessage(R.string.self_control_unlock_curfew_confirm_msg)
            .setPositiveButton(R.string.self_control_btn_unlock_curfew_direct) { d, _ ->
                d.dismiss()
                unlockInFlight = true
                viewModel.unlockNightCurfewDirect { ok ->
                    unlockInFlight = false
                    showUnlockFeedback(ok, R.string.toast_curfew_unlocked)
                }
            }
            .setNegativeButton(R.string.dialog_btn_cancel, null)
            .setOnDismissListener { confirmDialog = null }
            .show()
    }

    private fun showDirectFortressConfirm(displayName: String) {
        val ctx = contextProvider() ?: return
        val act = ctx as? android.app.Activity
        if (act != null && (act.isFinishing || act.isDestroyed)) return
        if (unlockInFlight || confirmDialog?.isShowing == true) return
        confirmDialog = MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.self_control_unlock_fortress_confirm_title)
            .setMessage(ctx.getString(R.string.self_control_unlock_fortress_confirm_msg, displayName))
            .setPositiveButton(R.string.self_control_btn_unlock_fortress_direct) { d, _ ->
                d.dismiss()
                unlockInFlight = true
                viewModel.unlockActiveFortressDirect { ok ->
                    unlockInFlight = false
                    showUnlockFeedback(ok, R.string.toast_fortress_unlocked)
                }
            }
            .setNegativeButton(R.string.dialog_btn_cancel, null)
            .setOnDismissListener { confirmDialog = null }
            .show()
    }

    private fun showUnlockFeedback(success: Boolean, successToastRes: Int) {
        val appCtx = contextProvider()?.applicationContext ?: return
        val msgRes = if (success) successToastRes else R.string.toast_mode_unlock_failed
        Toast.makeText(appCtx, msgRes, Toast.LENGTH_SHORT).show()
    }

    fun dismiss() {
        confirmDialog?.dismiss()
        confirmDialog = null
        unlockInFlight = false
        // The sheet is Activity-scoped; the Fragment view can die while the Activity lives.
        ActiveShieldsBottomSheet.dismiss()
    }

    companion object {
        private const val TAG_UNLOCK_CURFEW = "qr_unlock_curfew"
        private const val TAG_UNLOCK_FORTRESS = "qr_unlock_fortress"
    }
}
