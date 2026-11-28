package com.drmohammed.antiporn.ui.settings

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.drmohammed.antiporn.R
import com.drmohammed.antiporn.core.engine.ProtectionEngine
import com.drmohammed.antiporn.databinding.FragmentSettingsBinding
import com.drmohammed.antiporn.ota.model.OtaUpdateState
import com.drmohammed.antiporn.provisioning.ui.ProvisioningWizardActivity
import com.drmohammed.antiporn.telemetry.AiTelemetryReporter
import com.drmohammed.antiporn.ui.common.TextInputDialogFragment
import com.drmohammed.antiporn.ui.common.showSafely
import com.drmohammed.antiporn.ui.control.qr.QrScannerBottomSheet
import com.drmohammed.antiporn.ui.waqf.WaqfSupportActivity
import com.google.android.material.color.MaterialColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.androidx.viewmodel.ext.android.viewModel
import timber.log.Timber

/**
 * Settings Fragment implementing Material 3 List Preference ergonomics:
 * 1. Screen AI content inspection toggle & live metrics.
 * 2. OTA App update checker & download progress.
 * 3. Google cloud account linking & sync state.
 * 4. Banking Apps launcher & pinned shortcuts bottom sheet.
 * 5. Device Owner system status, deactivation countdown, and diagnostic logs bottom sheet.
 */
class SettingsFragment : Fragment() {

    interface SettingsActionListener {
        fun onToggleAiRequested(enable: Boolean)
        fun onCheckOtaRequested()
        fun onLinkGoogleRequested()
    }

    private var actionListener: SettingsActionListener? = null

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding ?: throw IllegalStateException("Binding is only valid between onCreateView and onDestroyView")

    private val settingsViewModel: SettingsViewModel by viewModel()
    private val viewModel get() = settingsViewModel

    private var lastViewLogsClickTime = 0L
    private var lastEditNameClickTime = 0L
    private var lastDeviceOwnerClickTime = 0L
    private var lastBankingClickTime = 0L
    private var lastWaqfClickTime = 0L

    override fun onAttach(context: Context) {
        super.onAttach(context)
        if (context is SettingsActionListener) {
            actionListener = context
        }
    }

    override fun onDetach() {
        super.onDetach()
        actionListener = null
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupFragmentResultListeners()
        setupListeners()
        observeViewModel()
        settingsViewModel.prefetchWaqfConfigIfStale()
    }

    private fun setupFragmentResultListeners() {
        parentFragmentManager.setFragmentResultListener(DEACTIVATION_QR_REQUEST_KEY, viewLifecycleOwner) { _, bundle ->
            val verified = bundle.getBoolean(QrScannerBottomSheet.RESULT_KEY_VERIFIED, false)
            if (verified) {
                viewModel.requestDeactivation(hasQr = true) { result ->
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
            } else {
                context?.let { Toast.makeText(it, R.string.toast_qr_verify_failed, Toast.LENGTH_SHORT).show() }
            }
        }

        val handleDiagnosticsAction: (String?) -> Unit = { action ->
            when (action) {
                DeviceOwnerDetailsBottomSheet.ACTION_VIEW_LOGS -> openDiagnosticLogViewer()
                DeviceOwnerDetailsBottomSheet.ACTION_SHARE_TELEMETRY -> context?.let { shareDiagnosticReport(it) }
            }
        }
        childFragmentManager.setFragmentResultListener(DeviceOwnerDetailsBottomSheet.REQUEST_KEY, viewLifecycleOwner) { _, bundle ->
            handleDiagnosticsAction(bundle.getString(DeviceOwnerDetailsBottomSheet.RESULT_ACTION))
        }
        childFragmentManager.setFragmentResultListener(DiagnosticLogsBottomSheet.REQUEST_KEY, viewLifecycleOwner) { _, bundle ->
            handleDiagnosticsAction(bundle.getString(DiagnosticLogsBottomSheet.RESULT_ACTION))
        }

        childFragmentManager.setFragmentResultListener(REQ_EDIT_DEVICE_NAME, viewLifecycleOwner) { _, bundle ->
            if (!bundle.getBoolean(TextInputDialogFragment.RESULT_CONFIRMED, false)) return@setFragmentResultListener
            val newName = bundle.getString(TextInputDialogFragment.RESULT_TEXT)?.trim().orEmpty()
            val currentName = viewModel.uiState.value.myDisplayName
            if (newName.isNotBlank() && newName != currentName) {
                viewModel.setMyDisplayName(newName) { result ->
                    if (!isAdded || _binding == null) return@setMyDisplayName
                    result.onSuccess {
                        Toast.makeText(context, R.string.toast_name_saved, Toast.LENGTH_SHORT).show()
                    }.onFailure { e ->
                        Timber.e(e, "Failed to update display name")
                        val errMsg = e.localizedMessage ?: getString(R.string.error_unknown)
                        Toast.makeText(context, getString(R.string.toast_partner_failed, errMsg), Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        settingsViewModel.refreshAll()
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state -> render(state) }
            }
        }
    }

    private fun render(state: SettingsUiState) {
        if (_binding == null) return
        val ctx = context ?: return

        binding.tvDeviceDisplayName.text = state.myDisplayName.ifBlank { getString(R.string.device_name_not_set) }
        renderAiSection(state, ctx)
        renderOtaSection(state)
        renderGoogleSection(state, ctx)
        renderDeviceOwnerSection(state, ctx)
    }

    private fun renderAiSection(state: SettingsUiState, ctx: Context) {
        binding.switchAiShield.isChecked = state.isAiRunning
        binding.switchAiShield.alpha = if (state.isAiControlLocked) 0.55f else 1.0f
        val onSurfaceVariant = MaterialColors.getColor(binding.root, com.google.android.material.R.attr.colorOnSurfaceVariant)

        if (state.isAiRunning) {
            if (state.aiInitError.isNotEmpty()) {
                binding.tvAiStatusSubtext.text = state.aiInitError
                binding.tvAiStatusSubtext.setTextColor(ContextCompat.getColor(ctx, R.color.status_orange))
            } else {
                binding.tvAiStatusSubtext.setText(R.string.ai_shield_status_running_short)
                binding.tvAiStatusSubtext.setTextColor(ContextCompat.getColor(ctx, R.color.status_green))
            }

            val fpsText = getString(R.string.ai_fps_format, state.aiFps)
            val modelText = getString(R.string.ai_model_active_format, state.aiModelName)
            if (state.aiLastDetectionLabel.isNotEmpty()) {
                val confPercent = (state.aiLastDetectionConfidence * 100).toInt()
                val detText = getString(R.string.ai_last_detection_format, state.aiLastDetectionLabel, confPercent)
                binding.tvAiMetrics.text = getString(R.string.ai_metrics_full_format, fpsText, detText, modelText)
                binding.tvAiMetrics.setTextColor(ContextCompat.getColor(ctx, R.color.status_orange))
            } else {
                binding.tvAiMetrics.text = getString(R.string.ai_metrics_simple_format, fpsText, modelText)
                binding.tvAiMetrics.setTextColor(onSurfaceVariant)
            }
            binding.tvAiMetrics.visibility = View.VISIBLE
        } else {
            binding.tvAiStatusSubtext.setText(R.string.ai_shield_status_stopped_short)
            binding.tvAiStatusSubtext.setTextColor(onSurfaceVariant)
            binding.tvAiMetrics.visibility = View.GONE
        }
    }

    private data class OtaDisplaySpec(
        val statusText: String,
        val showProgress: Boolean,
        val indeterminate: Boolean,
        val progress: Int = 0,
        val buttonEnabled: Boolean,
        val buttonTextRes: Int
    )

    private fun renderOtaSection(state: SettingsUiState) {
        binding.tvOtaStatus.visibility = View.GONE
        val spec = when (val ota = state.otaState) {
            is OtaUpdateState.Idle, is OtaUpdateState.NoUpdateAvailable -> OtaDisplaySpec(
                getString(R.string.ota_status_up_to_date_short), false, false, 0, true, R.string.btn_check_update_short
            )
            is OtaUpdateState.Checking -> OtaDisplaySpec(
                getString(R.string.ota_status_checking_short), true, true, 0, false, R.string.btn_checking_update
            )
            is OtaUpdateState.UpdateAvailable -> OtaDisplaySpec(
                getString(R.string.ota_status_available_short, ota.info.versionName), false, false, 0, true, R.string.btn_download_update
            )
            is OtaUpdateState.Downloading -> OtaDisplaySpec(
                getString(R.string.ota_status_downloading_short, ota.progressPercent),
                true, ota.progressPercent < 0, ota.progressPercent.coerceAtLeast(0), false, R.string.btn_download_update
            )
            is OtaUpdateState.Verifying -> OtaDisplaySpec(
                getString(R.string.btn_verifying_update), true, true, 0, false, R.string.btn_verifying_update
            )
            is OtaUpdateState.ReadyToInstall -> OtaDisplaySpec(
                getString(R.string.ota_status_installing_short), false, false, 0, true, R.string.btn_download_update
            )
            is OtaUpdateState.Installing -> OtaDisplaySpec(
                getString(R.string.ota_status_installing_short), true, true, 0, false, R.string.ota_installing
            )
            is OtaUpdateState.Error -> OtaDisplaySpec(
                getString(R.string.ota_status_error_short), false, false, 0, true, R.string.btn_check_update_short
            )
        }
        binding.tvOtaVersion.text = getString(R.string.card_ota_status_compact, state.appVersionName, spec.statusText)
        binding.pbOtaProgress.visibility = if (spec.showProgress) View.VISIBLE else View.GONE
        binding.pbOtaProgress.isIndeterminate = spec.indeterminate
        if (spec.showProgress && !spec.indeterminate) {
            binding.pbOtaProgress.progress = spec.progress
        }
        binding.btnCheckUpdate.isEnabled = spec.buttonEnabled
        binding.btnCheckUpdate.setText(spec.buttonTextRes)
    }

    private fun renderGoogleSection(state: SettingsUiState, ctx: Context) {
        if (state.isGoogleLinked && !state.googleAccountEmail.isNullOrBlank()) {
            binding.tvGoogleAccountStatus.text = getString(R.string.settings_google_linked, state.googleAccountEmail)
            binding.tvGoogleAccountStatus.setTextColor(ContextCompat.getColor(ctx, R.color.status_green))
            binding.btnLinkGoogle.visibility = View.GONE
        } else {
            binding.tvGoogleAccountStatus.setText(R.string.settings_google_unlinked)
            binding.tvGoogleAccountStatus.setTextColor(
                MaterialColors.getColor(ctx, com.google.android.material.R.attr.colorOnSurfaceVariant, ContextCompat.getColor(ctx, R.color.shield_hero_desc))
            )
            binding.btnLinkGoogle.visibility = View.VISIBLE
            binding.btnLinkGoogle.setText(R.string.btn_secure_with_google)
        }
    }

    private fun renderDeviceOwnerSection(state: SettingsUiState, ctx: Context) {
        if (state.isDeviceOwner) {
            binding.tvDeviceOwnerStatus.setText(R.string.card_device_owner_active)
            binding.tvDeviceOwnerStatus.setTextColor(ContextCompat.getColor(ctx, R.color.shield_emerald_radiant))
        } else {
            binding.tvDeviceOwnerStatus.setText(R.string.card_device_owner_inactive)
            binding.tvDeviceOwnerStatus.setTextColor(ContextCompat.getColor(ctx, R.color.shield_amber_calm))
        }
    }

    private fun setupListeners() {
        binding.layoutDeviceNamePreference.setOnClickListener {
            val currentTime = SystemClock.elapsedRealtime()
            if (currentTime - lastEditNameClickTime < 800L) return@setOnClickListener
            lastEditNameClickTime = currentTime
            childFragmentManager.showSafely(TAG_EDIT_DEVICE_NAME) {
                TextInputDialogFragment.newInstance(
                    requestKey = REQ_EDIT_DEVICE_NAME,
                    titleRes = R.string.dialog_edit_device_name_title,
                    hintRes = R.string.dialog_edit_device_name_hint,
                    initialText = viewModel.uiState.value.myDisplayName,
                    positiveRes = R.string.btn_save,
                    negativeRes = R.string.dialog_btn_cancel,
                    maxLength = 28,
                    singleLine = true,
                    validationMode = TextInputDialogFragment.VALIDATION_NONE
                )
            }
        }

        binding.switchAiShield.setOnCheckedChangeListener { buttonView, isChecked ->
            if (buttonView.isPressed) {
                if (viewModel.uiState.value.isAiControlLocked) {
                    buttonView.isChecked = !isChecked
                    context?.let { ctx ->
                        Toast.makeText(ctx, R.string.self_control_section_locked_by_guardian, Toast.LENGTH_SHORT).show()
                    }
                    return@setOnCheckedChangeListener
                }
                actionListener?.onToggleAiRequested(isChecked)
            }
        }

        binding.btnCheckUpdate.setOnClickListener { actionListener?.onCheckOtaRequested() }
        binding.btnLinkGoogle.setOnClickListener { actionListener?.onLinkGoogleRequested() }

        binding.layoutBankingPreference.setOnClickListener {
            val currentTime = SystemClock.elapsedRealtime()
            if (currentTime - lastBankingClickTime < 800L) return@setOnClickListener
            lastBankingClickTime = currentTime
            childFragmentManager.showSafely(BankingAppsBottomSheet.TAG) { BankingAppsBottomSheet.newInstance() }
        }

        binding.layoutDeviceOwnerPreference.setOnClickListener {
            val currentTime = SystemClock.elapsedRealtime()
            if (currentTime - lastDeviceOwnerClickTime < 800L) return@setOnClickListener
            lastDeviceOwnerClickTime = currentTime
            val ctx = context ?: return@setOnClickListener
            if (!ProtectionEngine.isDeviceOwner(ctx)) {
                startActivity(Intent(ctx, ProvisioningWizardActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
                })
            } else {
                childFragmentManager.showSafely(DeviceOwnerDetailsBottomSheet.TAG) {
                    DeviceOwnerDetailsBottomSheet.newInstance()
                }
            }
        }

        binding.layoutDeviceOwnerPreference.setOnLongClickListener { view ->
            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            childFragmentManager.showSafely(DiagnosticLogsBottomSheet.TAG) {
                DiagnosticLogsBottomSheet.newInstance()
            }
            true
        }

        binding.cardWaqfSupport.setOnClickListener {
            val now = SystemClock.elapsedRealtime()
            if (now - lastWaqfClickTime < 800L) return@setOnClickListener
            lastWaqfClickTime = now
            val ctx = context ?: return@setOnClickListener
            startActivity(Intent(ctx, WaqfSupportActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            })
        }
    }

    private fun openDiagnosticLogViewer() {
        val currentTime = SystemClock.elapsedRealtime()
        if (currentTime - lastViewLogsClickTime < 1000L) return
        lastViewLogsClickTime = currentTime
        val currentCtx = context ?: return
        startActivity(Intent(currentCtx, DiagnosticLogViewerActivity::class.java))
    }

    private fun shareDiagnosticReport(context: Context) {
        val appContext = context.applicationContext
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val reportFile = withContext(Dispatchers.IO) {
                    AiTelemetryReporter.exportReportToFile(appContext)
                }
                if (!isAdded || _binding == null) return@launch
                try {
                    startActivity(AiTelemetryReporter.createShareFileIntent(appContext, reportFile))
                } catch (e: Exception) {
                    Timber.tag(TAG).e(e, "Error launching share chooser")
                    Toast.makeText(appContext, R.string.error_share_report_failed, Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Error generating telemetry report file")
                Toast.makeText(appContext, R.string.error_share_report_failed, Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun updateAllStates() = settingsViewModel.refreshAll()
    fun updateAiUi() { if (isAdded) settingsViewModel.refreshAiState() }
    fun updateGoogleUi() = settingsViewModel.refreshGoogleState()
    fun updateDeviceOwnerUi() = settingsViewModel.refreshDeviceOwnerState()
    fun bindOtaState(state: OtaUpdateState, versionName: String, versionCode: Long) = settingsViewModel.refreshAll()

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        private const val TAG = "AntiPorn_SettingsFrag"
        private const val DEACTIVATION_QR_REQUEST_KEY = "deactivation_qr_request"
        private const val REQ_EDIT_DEVICE_NAME = "req_edit_device_name"
        private const val TAG_EDIT_DEVICE_NAME = "tag_edit_device_name"
    }
}
