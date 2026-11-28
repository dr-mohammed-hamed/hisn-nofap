package com.drmohammed.antiporn.ui.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.os.bundleOf
import com.drmohammed.antiporn.databinding.BottomSheetDiagnosticLogsBinding
import com.drmohammed.antiporn.ui.common.applyBottomSheetInsets
import com.drmohammed.antiporn.ui.common.configureLandscapeFriendlySheet
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

/**
 * Material 3 BottomSheet offering quick access to diagnostic event logs and telemetry export.
 */
class DiagnosticLogsBottomSheet : BottomSheetDialogFragment() {

    private var _binding: BottomSheetDiagnosticLogsBinding? = null
    private val binding: BottomSheetDiagnosticLogsBinding
        get() = _binding ?: throw IllegalStateException("Binding is only valid between onCreateView and onDestroyView")

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetDiagnosticLogsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        configureLandscapeFriendlySheet(enableImeResize = false)
        applyBottomSheetInsets(view, includeIme = false)

        binding.cardViewLogs.setOnClickListener {
            parentFragmentManager.setFragmentResult(
                REQUEST_KEY,
                bundleOf(RESULT_ACTION to ACTION_VIEW_LOGS)
            )
            dismiss()
        }

        binding.cardShareTelemetry.setOnClickListener {
            parentFragmentManager.setFragmentResult(
                REQUEST_KEY,
                bundleOf(RESULT_ACTION to ACTION_SHARE_TELEMETRY)
            )
            dismiss()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val TAG = "DiagnosticLogsSheet"
        const val REQUEST_KEY = "diagnostic_logs_action_request"
        const val RESULT_ACTION = "result_action"
        const val ACTION_VIEW_LOGS = "action_view_logs"
        const val ACTION_SHARE_TELEMETRY = "action_share_telemetry"

        fun newInstance(): DiagnosticLogsBottomSheet = DiagnosticLogsBottomSheet()
    }
}
