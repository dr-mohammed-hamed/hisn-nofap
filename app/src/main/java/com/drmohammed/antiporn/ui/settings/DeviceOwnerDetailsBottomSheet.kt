package com.drmohammed.antiporn.ui.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.os.bundleOf
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.drmohammed.antiporn.R
import com.drmohammed.antiporn.databinding.BottomSheetDeviceOwnerDetailsBinding
import com.drmohammed.antiporn.ui.common.ConfirmActionDialogFragment
import com.drmohammed.antiporn.ui.common.applyBottomSheetInsets
import com.drmohammed.antiporn.ui.common.configureLandscapeFriendlySheet
import com.drmohammed.antiporn.ui.common.showSafely
import com.drmohammed.antiporn.ui.control.qr.QrScannerBottomSheet
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import kotlinx.coroutines.launch
import org.koin.androidx.viewmodel.ext.android.viewModel

/**
 * Material 3 BottomSheet showing active Device Owner status, deactivation countdown/actions,
 * and quick links to diagnostic logs and telemetry export.
 */
class DeviceOwnerDetailsBottomSheet : BottomSheetDialogFragment() {

    private var _binding: BottomSheetDeviceOwnerDetailsBinding? = null
    private val binding: BottomSheetDeviceOwnerDetailsBinding
        get() = _binding ?: throw IllegalStateException("Binding is only valid between onCreateView and onDestroyView")

    private val viewModel: SettingsViewModel by viewModel(ownerProducer = { requireParentFragment() })

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetDeviceOwnerDetailsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        configureLandscapeFriendlySheet(enableImeResize = false)
        applyBottomSheetInsets(view, includeIme = false)

        setupConfirmListeners()
        renderDeactivationState(viewModel.uiState.value)

        binding.btnRequestDeactivation.setOnClickListener {
            childFragmentManager.showSafely(TAG_CONFIRM_REQUEST_DEACTIVATION) {
                ConfirmActionDialogFragment.newInstance(
                    requestKey = REQ_CONFIRM_REQUEST_DEACTIVATION,
                    titleRes = R.string.dialog_deactivation_title,
                    messageRes = R.string.dialog_deactivation_message,
                    positiveRes = R.string.btn_start_48h,
                    neutralRes = R.string.btn_scan_qr_24h,
                    negativeRes = android.R.string.cancel
                )
            }
        }

        binding.btnCancelDeactivation.setOnClickListener {
            childFragmentManager.showSafely(TAG_CONFIRM_CANCEL_DEACTIVATION) {
                ConfirmActionDialogFragment.newInstance(
                    requestKey = REQ_CONFIRM_CANCEL_DEACTIVATION,
                    titleRes = R.string.btn_cancel_deactivation,
                    messageRes = R.string.dialog_deactivation_cancel_confirm,
                    positiveRes = R.string.btn_confirm_cancel_deactivation,
                    negativeRes = android.R.string.cancel
                )
            }
        }

        binding.btnExecuteDeactivation.setOnClickListener {
            childFragmentManager.showSafely(TAG_CONFIRM_EXECUTE_DEACTIVATION) {
                ConfirmActionDialogFragment.newInstance(
                    requestKey = REQ_CONFIRM_EXECUTE_DEACTIVATION,
                    titleRes = R.string.btn_execute_deactivation,
                    messageRes = R.string.dialog_deactivation_execute_confirm,
                    positiveRes = R.string.btn_confirm_execute_deactivation,
                    negativeRes = android.R.string.cancel
                )
            }
        }

        binding.cardViewLogs.setOnClickListener {
            deliverAction(ACTION_VIEW_LOGS)
            dismiss()
        }

        binding.cardShareTelemetry.setOnClickListener {
            deliverAction(ACTION_SHARE_TELEMETRY)
            dismiss()
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    if (_binding != null) {
                        renderDeactivationState(state)
                    }
                }
            }
        }
    }

    private fun setupConfirmListeners() {
        childFragmentManager.setFragmentResultListener(REQ_CONFIRM_REQUEST_DEACTIVATION, viewLifecycleOwner) { _, bundle ->
            when (bundle.getString(ConfirmActionDialogFragment.RESULT_BUTTON)) {
                ConfirmActionDialogFragment.BUTTON_POSITIVE -> {
                    viewModel.requestDeactivation(hasQr = false) { result ->
                        val c = context ?: return@requestDeactivation
                        if (!isAdded || _binding == null || viewLifecycleOwner.lifecycle.currentState < Lifecycle.State.STARTED) {
                            return@requestDeactivation
                        }
                        if (result.isSuccess) {
                            Toast.makeText(c, R.string.toast_deactivation_requested, Toast.LENGTH_LONG).show()
                        } else {
                            val err = result.exceptionOrNull()?.message ?: ""
                            Toast.makeText(c, getString(R.string.error_prefix, err), Toast.LENGTH_LONG).show()
                        }
                    }
                }
                ConfirmActionDialogFragment.BUTTON_NEUTRAL -> {
                    requireParentFragment().parentFragmentManager.showSafely(DEACTIVATION_QR_REQUEST_KEY) {
                        QrScannerBottomSheet.newInstance(DEACTIVATION_QR_REQUEST_KEY)
                    }
                }
            }
        }

        childFragmentManager.setFragmentResultListener(REQ_CONFIRM_CANCEL_DEACTIVATION, viewLifecycleOwner) { _, bundle ->
            if (bundle.getString(ConfirmActionDialogFragment.RESULT_BUTTON) == ConfirmActionDialogFragment.BUTTON_POSITIVE) {
                viewModel.cancelDeactivation { result ->
                    val c = context ?: return@cancelDeactivation
                    if (!isAdded || _binding == null || viewLifecycleOwner.lifecycle.currentState < Lifecycle.State.STARTED) {
                        return@cancelDeactivation
                    }
                    if (result.isSuccess) {
                        Toast.makeText(c, R.string.toast_deactivation_cancelled, Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

        childFragmentManager.setFragmentResultListener(REQ_CONFIRM_EXECUTE_DEACTIVATION, viewLifecycleOwner) { _, bundle ->
            if (bundle.getString(ConfirmActionDialogFragment.RESULT_BUTTON) == ConfirmActionDialogFragment.BUTTON_POSITIVE) {
                viewModel.executeDeactivation { result ->
                    val c = context ?: return@executeDeactivation
                    if (!isAdded || _binding == null || viewLifecycleOwner.lifecycle.currentState < Lifecycle.State.STARTED) {
                        return@executeDeactivation
                    }
                    if (result.isSuccess) {
                        Toast.makeText(c, R.string.toast_deactivation_success, Toast.LENGTH_LONG).show()
                        dismissAllowingStateLoss()
                    } else {
                        val err = result.exceptionOrNull()?.message ?: ""
                        Toast.makeText(c, getString(R.string.error_deactivation_execution, err), Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    private fun renderDeactivationState(state: SettingsUiState) {
        val ctx = context ?: return
        if (state.isDeactivationPending) {
            binding.btnRequestDeactivation.visibility = View.GONE
            binding.layoutDeactivationPending.visibility = View.VISIBLE

            binding.pbDeactivationProgress.visibility = if (state.isDeactivating) View.VISIBLE else View.GONE
            binding.btnCancelDeactivation.isEnabled = !state.isDeactivating

            val remaining = state.remainingDeactivationMs
            if (remaining <= 0L) {
                binding.tvDeactivationCountdown.text = getString(R.string.toast_deactivation_success)
                binding.tvDeactivationCountdown.setTextColor(ContextCompat.getColor(ctx, R.color.status_green))
                binding.btnExecuteDeactivation.isEnabled = !state.isDeactivating
            } else {
                val totalMinutes = remaining / (60 * 1000L)
                val hours = totalMinutes / 60
                val minutes = totalMinutes % 60
                val timeStr = if (hours > 0) {
                    getString(R.string.time_format_hours_minutes, hours, minutes)
                } else {
                    getString(R.string.time_format_minutes, minutes)
                }
                binding.tvDeactivationCountdown.text = getString(R.string.deactivation_countdown_format, timeStr)
                binding.tvDeactivationCountdown.setTextColor(ContextCompat.getColor(ctx, R.color.shield_amber_calm))
                binding.btnExecuteDeactivation.isEnabled = false
            }
        } else {
            binding.btnRequestDeactivation.visibility = View.VISIBLE
            binding.layoutDeactivationPending.visibility = View.GONE
        }
    }

    private fun deliverAction(action: String) {
        parentFragmentManager.setFragmentResult(
            REQUEST_KEY,
            bundleOf(RESULT_ACTION to action)
        )
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val TAG = "DeviceOwnerDetailsSheet"
        const val REQUEST_KEY = "device_owner_details_action_request"
        const val RESULT_ACTION = "result_action"
        const val ACTION_VIEW_LOGS = "action_view_logs"
        const val ACTION_SHARE_TELEMETRY = "action_share_telemetry"

        private const val DEACTIVATION_QR_REQUEST_KEY = "deactivation_qr_request"
        private const val REQ_CONFIRM_REQUEST_DEACTIVATION = "req_do_confirm_request_deactivation"
        private const val TAG_CONFIRM_REQUEST_DEACTIVATION = "tag_do_confirm_request_deactivation"
        private const val REQ_CONFIRM_CANCEL_DEACTIVATION = "req_do_confirm_cancel_deactivation"
        private const val TAG_CONFIRM_CANCEL_DEACTIVATION = "tag_do_confirm_cancel_deactivation"
        private const val REQ_CONFIRM_EXECUTE_DEACTIVATION = "req_do_confirm_execute_deactivation"
        private const val TAG_CONFIRM_EXECUTE_DEACTIVATION = "tag_do_confirm_execute_deactivation"

        fun newInstance(): DeviceOwnerDetailsBottomSheet = DeviceOwnerDetailsBottomSheet()
    }
}
