package com.drmohammed.antiporn.ui.accountability

import android.app.Dialog
import android.content.DialogInterface
import android.os.Bundle
import android.os.SystemClock
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.os.bundleOf
import androidx.core.widget.NestedScrollView
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import com.drmohammed.antiporn.R
import com.drmohammed.antiporn.ui.common.showSafely
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Rotation-safe [DialogFragment] presenting two-stage Guardian immediate Device Owner
 * removal warning, 5-second safety countdown, and pending teardown cancellation confirmation.
 */
class RemoteTeardownDialogFragment : DialogFragment() {

    private var mode: Int = MODE_STAGE1_WARNING
    private var wardName: String = ""
    private var requestKey: String = REQUEST_KEY
    private var countdownStartElapsedMs: Long = 0L
    private var ackChecked: Boolean = false
    private var countdownJob: Job? = null

    private var activeAckCheckBox: MaterialCheckBox? = null
    private var activePositiveButton: Button? = null
    private var redColor: Int = 0
    private var resultDelivered: Boolean = false

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val ctx = requireContext()
        val args = arguments ?: Bundle.EMPTY
        mode = savedInstanceState?.getInt(STATE_MODE)
            ?: args.getInt(ARG_MODE, MODE_STAGE1_WARNING)
        wardName = savedInstanceState?.getString(STATE_WARD_NAME)
            ?: args.getString(ARG_WARD_NAME).orEmpty()
        requestKey = args.getString(ARG_REQUEST_KEY) ?: REQUEST_KEY
        countdownStartElapsedMs = savedInstanceState?.getLong(
            STATE_COUNTDOWN_START_ELAPSED_MS,
            SystemClock.elapsedRealtime()
        ) ?: SystemClock.elapsedRealtime()
        ackChecked = savedInstanceState?.getBoolean(STATE_ACK_CHECKED, false) ?: false

        val displayName = wardName.trim().ifBlank { ctx.getString(R.string.status_ward_badge) }
        redColor = MaterialColors.getColor(
            ctx,
            androidx.appcompat.R.attr.colorError,
            ContextCompat.getColor(ctx, R.color.status_red)
        )

        return when (mode) {
            MODE_STAGE1_WARNING -> createStage1Dialog(displayName)
            MODE_STAGE2_COUNTDOWN -> createStage2Dialog(displayName)
            else -> createCancelPendingDialog(displayName)
        }
    }

    private fun createStage1Dialog(displayName: String): AlertDialog {
        val ctx = requireContext()
        val dialog = MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.dialog_remote_teardown_stage1_title)
            .setMessage(ctx.getString(R.string.dialog_remote_teardown_stage1_msg, displayName))
            .setPositiveButton(R.string.btn_remote_teardown_stage1_continue) { d, _ ->
                d.dismiss()
                parentFragmentManager.showSafely(TAG_STAGE2) {
                    newInstance(MODE_STAGE2_COUNTDOWN, displayName, requestKey)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(DialogInterface.BUTTON_POSITIVE)?.setTextColor(redColor)
        }
        return dialog
    }

    private fun createStage2Dialog(displayName: String): AlertDialog {
        val ctx = requireContext()
        val density = resources.displayMetrics.density
        val padH = (24 * density).toInt()
        val padTop = (12 * density).toInt()
        val padBottom = (4 * density).toInt()

        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padH, padTop, padH, padBottom)
        }

        val onSurface = MaterialColors.getColor(
            container,
            com.google.android.material.R.attr.colorOnSurface
        )

        val messageView = TextView(ctx).apply {
            text = ctx.getString(R.string.dialog_remote_teardown_stage2_msg, displayName)
            textSize = 14f
            setTextColor(onSurface)
            setLineSpacing(0f, 1.15f)
        }

        val ackCheckBox = MaterialCheckBox(ctx).apply {
            text = ctx.getString(R.string.checkbox_remote_teardown_acknowledge)
            textSize = 13f
            setTextColor(redColor)
            isChecked = ackChecked
            val topMargin = (14 * density).toInt()
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                this.topMargin = topMargin
            }
        }
        activeAckCheckBox = ackCheckBox

        container.addView(messageView)
        container.addView(ackCheckBox)

        val scrollContainer = NestedScrollView(ctx).apply {
            isFillViewport = true
            addView(container)
        }

        val dialog = MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.dialog_remote_teardown_stage2_title)
            .setView(scrollContainer)
            .setPositiveButton(
                ctx.getString(R.string.btn_remote_teardown_confirm_countdown, COUNTDOWN_SECONDS),
                null
            )
            .setNegativeButton(android.R.string.cancel, null)
            .create()

        dialog.setOnShowListener {
            val positiveBtn = dialog.getButton(DialogInterface.BUTTON_POSITIVE)
            activePositiveButton = positiveBtn
            syncStage2ButtonState()

            ackCheckBox.setOnCheckedChangeListener { _, checked ->
                ackChecked = checked
                syncStage2ButtonState()
            }

            positiveBtn?.setOnClickListener {
                if (resultDelivered || remainingSeconds() > 0 || !ackCheckBox.isChecked) return@setOnClickListener
                resultDelivered = true
                positiveBtn.isEnabled = false
                parentFragmentManager.setFragmentResult(
                    requestKey,
                    bundleOf(RESULT_ACTION to ACTION_CONFIRM_TEARDOWN)
                )
                dismissAllowingStateLoss()
            }
        }
        return dialog
    }

    private fun createCancelPendingDialog(displayName: String): AlertDialog {
        val ctx = requireContext()
        return MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.dialog_cancel_remote_teardown_title)
            .setMessage(ctx.getString(R.string.dialog_cancel_remote_teardown_msg, displayName))
            .setPositiveButton(R.string.btn_confirm_cancel_remote_teardown) { d, _ ->
                parentFragmentManager.setFragmentResult(
                    requestKey,
                    bundleOf(RESULT_ACTION to ACTION_CANCEL_PENDING_TEARDOWN)
                )
                d.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
    }

    override fun onStart() {
        super.onStart()
        if (mode == MODE_STAGE2_COUNTDOWN && remainingSeconds() > 0) {
            countdownJob?.cancel()
            countdownJob = lifecycleScope.launch {
                while (isActive && remainingSeconds() > 0) {
                    delay(1_000L)
                    if (!isAdded) break
                    syncStage2ButtonState()
                }
            }
        }
    }

    override fun onStop() {
        countdownJob?.cancel()
        countdownJob = null
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(STATE_MODE, mode)
        outState.putString(STATE_WARD_NAME, wardName)
        outState.putLong(STATE_COUNTDOWN_START_ELAPSED_MS, countdownStartElapsedMs)
        outState.putBoolean(STATE_ACK_CHECKED, activeAckCheckBox?.isChecked ?: ackChecked)
    }

    override fun onDestroy() {
        countdownJob?.cancel()
        countdownJob = null
        activeAckCheckBox = null
        activePositiveButton = null
        super.onDestroy()
    }

    private fun remainingSeconds(): Int {
        val elapsedSec = ((SystemClock.elapsedRealtime() - countdownStartElapsedMs) / 1000L).toInt()
        return (COUNTDOWN_SECONDS - elapsedSec).coerceAtLeast(0)
    }

    private fun syncStage2ButtonState() {
        val positiveBtn = activePositiveButton ?: return
        val ackBox = activeAckCheckBox ?: return
        val ctx = context ?: return
        val rem = remainingSeconds()
        val ready = rem <= 0 && ackBox.isChecked
        positiveBtn.isEnabled = ready
        positiveBtn.alpha = if (ready) 1.0f else 0.5f
        positiveBtn.setTextColor(redColor)
        positiveBtn.text = if (rem > 0) {
            ctx.getString(R.string.btn_remote_teardown_confirm_countdown, rem)
        } else {
            ctx.getString(R.string.btn_remote_teardown_confirm_final)
        }
    }

    companion object {
        const val TAG_STAGE1 = "RemoteTeardownStage1"
        const val TAG_STAGE2 = "RemoteTeardownStage2"
        const val TAG_CANCEL = "RemoteTeardownCancel"
        const val REQUEST_KEY = "remote_teardown_dialog_request"
        const val RESULT_ACTION = "result_action"
        const val ACTION_CONFIRM_TEARDOWN = "action_confirm_teardown"
        const val ACTION_CANCEL_PENDING_TEARDOWN = "action_cancel_pending_teardown"

        const val MODE_STAGE1_WARNING = 1
        const val MODE_STAGE2_COUNTDOWN = 2
        const val MODE_CANCEL_PENDING = 3

        private const val ARG_MODE = "arg_mode"
        private const val ARG_WARD_NAME = "arg_ward_name"
        private const val ARG_REQUEST_KEY = "arg_request_key"
        private const val STATE_MODE = "state_mode"
        private const val STATE_WARD_NAME = "state_ward_name"
        private const val STATE_COUNTDOWN_START_ELAPSED_MS = "state_countdown_start_elapsed_ms"
        private const val STATE_ACK_CHECKED = "state_ack_checked"
        private const val COUNTDOWN_SECONDS = 5

        fun newInstance(
            mode: Int,
            wardName: String,
            requestKey: String = REQUEST_KEY
        ): RemoteTeardownDialogFragment {
            return RemoteTeardownDialogFragment().apply {
                arguments = bundleOf(
                    ARG_MODE to mode,
                    ARG_WARD_NAME to wardName,
                    ARG_REQUEST_KEY to requestKey
                )
            }
        }
    }
}
