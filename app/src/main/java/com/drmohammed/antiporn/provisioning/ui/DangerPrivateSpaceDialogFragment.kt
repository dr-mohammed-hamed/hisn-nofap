package com.drmohammed.antiporn.provisioning.ui

import android.annotation.SuppressLint
import android.app.Dialog
import android.content.DialogInterface
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.core.os.bundleOf
import androidx.fragment.app.DialogFragment
import com.drmohammed.antiporn.R
import com.drmohammed.antiporn.provisioning.engine.OemProvisioningHelper
import com.google.android.material.button.MaterialButton
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * Rotation-safe [DialogFragment] displaying the Private Space / Dual App Device Owner danger
 * warning, retaining acknowledgment state across configuration changes and reporting outcomes via
 * Fragment Result API.
 */
class DangerPrivateSpaceDialogFragment : DialogFragment() {

    private var ackChecked = false
    private var resultDelivered = false
    private var activeAckCheckBox: MaterialCheckBox? = null

    @SuppressLint("InflateParams")
    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val ctx = requireContext()
        ackChecked = savedInstanceState?.getBoolean(STATE_ACK_CHECKED, false) ?: false

        val dialogView = layoutInflater.inflate(R.layout.dialog_private_space_danger, null)
        val copy = OemProvisioningHelper.getDangerDialogStrings(ctx)

        val tvDangerTitle = dialogView.findViewById<TextView>(R.id.tvDangerTitle)
        val tvDangerDesc = dialogView.findViewById<TextView>(R.id.tvDangerDesc)
        val chkAcknowledgeDanger = dialogView.findViewById<MaterialCheckBox>(R.id.chkAcknowledgeDanger)
        val btnDialogProceed = dialogView.findViewById<MaterialButton>(R.id.btnDialogProceed)
        val btnDialogRecheck = dialogView.findViewById<MaterialButton>(R.id.btnDialogRecheck)
        val btnDialogCancel = dialogView.findViewById<MaterialButton>(R.id.btnDialogCancel)

        activeAckCheckBox = chkAcknowledgeDanger
        tvDangerTitle.setText(copy.titleRes)
        tvDangerDesc.setText(copy.descRes)
        btnDialogRecheck.visibility = if (copy.showRecheckButton) View.VISIBLE else View.GONE

        chkAcknowledgeDanger.isChecked = ackChecked
        btnDialogProceed.isEnabled = ackChecked

        chkAcknowledgeDanger.setOnCheckedChangeListener { _, isChecked ->
            ackChecked = isChecked
            btnDialogProceed.isEnabled = isChecked
        }

        btnDialogProceed.setOnClickListener {
            if (resultDelivered || !chkAcknowledgeDanger.isChecked) return@setOnClickListener
            btnDialogProceed.isEnabled = false
            deliverResult(ACTION_PROCEED)
            dismissAllowingStateLoss()
        }

        btnDialogRecheck.setOnClickListener {
            val stillExists = OemProvisioningHelper.hasConflictingProfile(ctx)
            if (!stillExists) {
                Toast.makeText(
                    ctx.applicationContext,
                    R.string.dialog_danger_private_space_cleared,
                    Toast.LENGTH_LONG
                ).show()
                deliverResult(ACTION_CLEARED_PROFILE)
                dismissAllowingStateLoss()
            } else {
                Toast.makeText(
                    ctx.applicationContext,
                    R.string.dialog_danger_private_space_still_exists,
                    Toast.LENGTH_LONG
                ).show()
            }
        }

        btnDialogCancel.setOnClickListener {
            deliverResult(ACTION_CANCEL)
            dismissAllowingStateLoss()
        }

        isCancelable = true
        return MaterialAlertDialogBuilder(ctx)
            .setView(dialogView)
            .setCancelable(true)
            .create()
    }

    override fun onCancel(dialog: DialogInterface) {
        super.onCancel(dialog)
        deliverResult(ACTION_CANCEL)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_ACK_CHECKED, activeAckCheckBox?.isChecked ?: ackChecked)
    }

    override fun onDestroy() {
        activeAckCheckBox = null
        super.onDestroy()
    }

    private fun deliverResult(action: String) {
        if (resultDelivered) return
        resultDelivered = true
        val args = arguments ?: Bundle.EMPTY
        parentFragmentManager.setFragmentResult(
            REQUEST_KEY,
            bundleOf(
                RESULT_ACTION to action,
                RESULT_ACTION_TYPE to args.getString(ARG_ACTION_TYPE).orEmpty(),
                RESULT_PAIRING_CODE to args.getString(ARG_PAIRING_CODE)
            )
        )
    }

    companion object {
        const val TAG = "DangerPrivateSpaceDialog"
        const val REQUEST_KEY = "danger_private_space_request"
        const val RESULT_ACTION = "result_action"
        const val RESULT_ACTION_TYPE = "result_action_type"
        const val RESULT_PAIRING_CODE = "result_pairing_code"

        const val ACTION_PROCEED = "action_proceed"
        const val ACTION_CANCEL = "action_cancel"
        const val ACTION_CLEARED_PROFILE = "action_cleared_profile"

        private const val ARG_ACTION_TYPE = "arg_action_type"
        private const val ARG_PAIRING_CODE = "arg_pairing_code"
        private const val STATE_ACK_CHECKED = "state_ack_checked"

        fun newInstance(actionType: String, pairingCode: String? = null): DangerPrivateSpaceDialogFragment {
            return DangerPrivateSpaceDialogFragment().apply {
                arguments = bundleOf(
                    ARG_ACTION_TYPE to actionType,
                    ARG_PAIRING_CODE to pairingCode
                )
            }
        }
    }
}
