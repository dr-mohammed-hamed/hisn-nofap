package com.drmohammed.antiporn.waqfa.ui.settings

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.drmohammed.antiporn.R
import com.drmohammed.antiporn.accessibility.control.AccessibilityReconcileResult
import com.drmohammed.antiporn.accessibility.ui.AccessibilityOnboardingBottomSheet
import com.drmohammed.antiporn.banking.BankingShortcutPromptHelper
import com.drmohammed.antiporn.databinding.ActivityWaqfaSettingsBinding
import com.drmohammed.antiporn.ui.common.ConfirmActionDialogFragment
import com.drmohammed.antiporn.ui.common.TextInputDialogFragment
import com.drmohammed.antiporn.ui.common.showSafely
import com.drmohammed.antiporn.ui.control.gate.SelfControlAccessGateRegistry
import com.drmohammed.antiporn.ui.control.gate.SelfControlSection
import com.drmohammed.antiporn.waqfa.model.WaqfaTimeoutReactionType
import com.google.android.material.color.MaterialColors
import kotlinx.coroutines.launch
import org.koin.androidx.viewmodel.ext.android.viewModel
import timber.log.Timber

/**
 * شاشة إعدادات وإدارة نظام «وَقْفَة».
 * تمكّن المستخدم في لوحة التحكم الذاتي أو المشرف عن بعد من تخصيص التطبيقات المعترضة وردود الفعل وشروط الدخول.
 */
class WaqfaSettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityWaqfaSettingsBinding
    private val viewModel: WaqfaSettingsViewModel by viewModel()
    private var isBindingUi = false
    private var lastRenderedDurationsSignature: String = ""
    private var awaitingA11yReturn = false
    private var awaitingUsageReturn = false

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        awaitingA11yReturn = savedInstanceState?.getBoolean(KEY_AWAITING_A11Y_RETURN, false) ?: false
        awaitingUsageReturn = savedInstanceState?.getBoolean(KEY_AWAITING_USAGE_RETURN, false) ?: false
        binding = ActivityWaqfaSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val rootView = binding.layoutRootWaqfaSettings
        val baseL = rootView.paddingLeft
        val baseT = rootView.paddingTop
        val baseR = rootView.paddingRight
        val baseB = rootView.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(rootView) { view, windowInsets ->
            val insets = windowInsets.getInsets(
                WindowInsetsCompat.Type.systemBars() or
                    WindowInsetsCompat.Type.displayCutout() or
                    WindowInsetsCompat.Type.ime()
            )
            view.setPadding(baseL + insets.left, baseT + insets.top, baseR + insets.right, baseB + insets.bottom)
            windowInsets
        }

        supportFragmentManager.setFragmentResultListener(
            WaqfaAppPickerBottomSheet.REQUEST_KEY,
            this
        ) { _, bundle ->
            val targets = bundle.getStringArrayList(WaqfaAppPickerBottomSheet.RESULT_TARGETS)?.toSet() ?: emptySet()
            viewModel.setTargetPackages(targets)
        }

        supportFragmentManager.setFragmentResultListener(REQ_ADD_DURATION, this) { _, bundle ->
            if (bundle.getBoolean(TextInputDialogFragment.RESULT_CONFIRMED, false)) {
                val mins = bundle.getString(TextInputDialogFragment.RESULT_TEXT)?.toIntOrNull()
                if (mins != null && mins in 1..180) {
                    viewModel.addQuickDuration(mins)
                }
            }
        }

        supportFragmentManager.setFragmentResultListener(REQ_USAGE_ACCESS_PROMPT, this) { _, bundle ->
            if (bundle.getString(ConfirmActionDialogFragment.RESULT_BUTTON) == ConfirmActionDialogFragment.BUTTON_POSITIVE) {
                openUsageAccessSettings()
            }
        }

        val isRemote = intent.getBooleanExtra(EXTRA_IS_REMOTE, false)
        val wardId = intent.getStringExtra(EXTRA_WARD_ID)
        val policyJson = intent.getStringExtra(EXTRA_POLICY_JSON)

        if (!isRemote && (!SelfControlAccessGateRegistry.getActiveGate().canAccess(this) ||
                !SelfControlAccessGateRegistry.isSectionPermitted(this, SelfControlSection.WAQFA))) {
            Toast.makeText(this, R.string.self_control_section_locked_by_guardian, Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        setupToolbar(isRemote)
        setupListeners()
        observeUiState()

        viewModel.initialize(isRemote, wardId, policyJson)
    }

    override fun onResume() {
        super.onResume()
        val isRemote = intent.getBooleanExtra(EXTRA_IS_REMOTE, false)
        if (!isRemote && (!SelfControlAccessGateRegistry.getActiveGate().canAccess(this) ||
                !SelfControlAccessGateRegistry.isSectionPermitted(this, SelfControlSection.WAQFA))) {
            Toast.makeText(this, R.string.self_control_section_locked_by_guardian, Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        viewModel.refreshEngineStatus()
        val state = viewModel.uiState.value

        if (awaitingA11yReturn && state.isAccessibilityActive) {
            awaitingA11yReturn = false
            Toast.makeText(this, R.string.a11y_onboarding_enabled_success_toast, Toast.LENGTH_SHORT).show()
            BankingShortcutPromptHelper.maybePrompt(this) {}
        }

        if (awaitingUsageReturn && state.hasUsageStatsPermission) {
            awaitingUsageReturn = false
            Toast.makeText(this, R.string.waqfa_usage_access_granted_toast, Toast.LENGTH_SHORT).show()
        }
    }

    private fun setupToolbar(isRemote: Boolean) {
        if (isRemote) {
            binding.toolbar.title = getString(R.string.waqfa_guardian_card_title)
        }
        binding.toolbar.setNavigationOnClickListener {
            finish()
        }
    }

    private fun setupListeners() {
        binding.swWaqfaMaster.setOnCheckedChangeListener { _, isChecked ->
            if (isBindingUi) return@setOnCheckedChangeListener
            viewModel.setWaqfaEnabled(isChecked)
        }

        binding.swWaqfaInstantMode.setOnCheckedChangeListener { _, isChecked ->
            if (isBindingUi) return@setOnCheckedChangeListener
            viewModel.setUseInstantAccessibilityMode(isChecked)
        }

        binding.tvEngineStatus.setOnClickListener {
            val state = viewModel.uiState.value
            if (state.isRemote) {
                Toast.makeText(this, getString(R.string.waqfa_engine_status_remote_toast), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            when (state.engineType) {
                WaqfaEngineType.INSTANT_0MS -> {
                    Toast.makeText(this, getString(R.string.waqfa_engine_status_instant_toast), Toast.LENGTH_SHORT).show()
                }
                WaqfaEngineType.USAGE_STATS_FALLBACK -> {
                    Toast.makeText(this, getString(R.string.waqfa_engine_status_bank_compatible_toast), Toast.LENGTH_SHORT).show()
                }
                WaqfaEngineType.DORMANT -> {
                    if (state.config.useInstantAccessibilityMode || state.isAutoUpgradedByOtherFeature) {
                        awaitingA11yReturn = true
                        AccessibilityOnboardingBottomSheet.showIfNotShowing(
                            fragmentManager = supportFragmentManager,
                            reason = AccessibilityOnboardingBottomSheet.REASON_WAQFA_INSTANT
                        )
                    } else {
                        awaitingUsageReturn = true
                        openUsageAccessSettings()
                    }
                }
            }
        }

        binding.btnSelectApps.setOnClickListener {
            val state = viewModel.uiState.value
            supportFragmentManager.showSafely(WaqfaAppPickerBottomSheet.TAG) {
                WaqfaAppPickerBottomSheet.newInstance(
                    isRemote = state.isRemote,
                    wardId = state.wardId.orEmpty(),
                    initialTargets = state.config.targetPackages
                )
            }
        }

        binding.swReasonRequired.setOnCheckedChangeListener { _, isChecked ->
            if (isBindingUi) return@setOnCheckedChangeListener
            viewModel.setReasonRequired(isChecked)
        }

        binding.rgTimeoutReaction.setOnCheckedChangeListener { _, checkedId ->
            if (isBindingUi) return@setOnCheckedChangeListener
            when (checkedId) {
                R.id.rbExitOnly -> {
                    viewModel.setTimeoutReactionType(WaqfaTimeoutReactionType.EXIT_ONLY)
                    binding.layoutCooldownDuration.visibility = View.GONE
                }
                R.id.rbExitAndFreeze -> {
                    viewModel.setTimeoutReactionType(WaqfaTimeoutReactionType.EXIT_AND_FREEZE)
                    binding.layoutCooldownDuration.visibility = View.VISIBLE
                }
                R.id.rbNotifyOnly -> {
                    viewModel.setTimeoutReactionType(WaqfaTimeoutReactionType.NOTIFY_ONLY)
                    binding.layoutCooldownDuration.visibility = View.GONE
                }
            }
        }

        binding.etCooldownMinutes.doAfterTextChanged {
            if (isBindingUi) return@doAfterTextChanged
            val mins = it?.toString()?.toIntOrNull() ?: 3
            viewModel.setCooldownMinutes(mins)
        }

        binding.swAllowCustomDuration.setOnCheckedChangeListener { _, isChecked ->
            if (isBindingUi) return@setOnCheckedChangeListener
            viewModel.setAllowCustomDuration(isChecked)
        }

        binding.swAllowOpenSession.setOnCheckedChangeListener { _, isChecked ->
            if (isBindingUi) return@setOnCheckedChangeListener
            viewModel.setAllowOpenSession(isChecked)
        }

        binding.btnAddDuration.setOnClickListener {
            showAddDurationDialog()
        }

        binding.btnSaveWaqfaSettings.setOnClickListener {
            binding.btnSaveWaqfaSettings.isEnabled = false
            viewModel.saveSettings()
        }
    }

    private fun handleSaveSucceeded(event: WaqfaSettingsEvent.SaveSucceeded) {
        if (isFinishing || isDestroyed) return
        if (event.updatedPolicy != null) {
            setResult(
                Activity.RESULT_OK,
                Intent().putExtra(EXTRA_POLICY_JSON, event.updatedPolicy.toJson())
            )
        }
        Toast.makeText(this, getString(R.string.waqfa_settings_saved_toast), Toast.LENGTH_SHORT).show()

        if (event.isRemote) {
            finish()
            return
        }

        val reconcileResult = event.reconcileResult
        when {
            reconcileResult is AccessibilityReconcileResult.NeedsManualActivation -> {
                binding.btnSaveWaqfaSettings.isEnabled = true
                awaitingA11yReturn = true
                val reason = AccessibilityOnboardingBottomSheet.reasonFromSet(reconcileResult.reasons)
                AccessibilityOnboardingBottomSheet.showIfNotShowing(
                    fragmentManager = supportFragmentManager,
                    reason = reason
                )
                viewModel.clearPendingPrompts()
            }
            event.pendingUsageAccessPrompt -> {
                binding.btnSaveWaqfaSettings.isEnabled = true
                awaitingUsageReturn = true
                showUsageAccessPromptDialog()
                viewModel.clearPendingPrompts()
            }
            reconcileResult is AccessibilityReconcileResult.EnabledSilently -> {
                val shown = BankingShortcutPromptHelper.maybePrompt(this) {
                    if (!isFinishing && !isDestroyed) finish()
                }
                if (!shown) {
                    finish()
                }
            }
            else -> {
                finish()
            }
        }
    }

    private fun observeUiState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.uiState.collect { state ->
                        renderUi(state)
                    }
                }
                launch {
                    viewModel.events.collect { event ->
                        when (event) {
                            is WaqfaSettingsEvent.SaveSucceeded -> handleSaveSucceeded(event)
                            is WaqfaSettingsEvent.SaveFailed -> {
                                if (!isFinishing && !isDestroyed) {
                                    binding.btnSaveWaqfaSettings.isEnabled = true
                                    Toast.makeText(
                                        this@WaqfaSettingsActivity,
                                        getString(R.string.waqfa_settings_save_failed_toast),
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun renderUi(state: WaqfaSettingsUiState) {
        isBindingUi = true
        try {
            val config = state.config

            if (binding.swWaqfaMaster.isChecked != config.isEnabled) {
                binding.swWaqfaMaster.isChecked = config.isEnabled
            }

            if (binding.swWaqfaInstantMode.isChecked != config.useInstantAccessibilityMode) {
                binding.swWaqfaInstantMode.isChecked = config.useInstantAccessibilityMode
            }

            binding.tvWaqfaInstantModeDesc.text =
                if (!state.isRemote && state.isAutoUpgradedByOtherFeature && !config.useInstantAccessibilityMode) {
                    getString(R.string.waqfa_instant_mode_desc_auto_upgraded)
                } else {
                    getString(R.string.waqfa_instant_mode_desc)
                }

            renderRemoteWarning(state)

            val count = config.targetPackages.size
            binding.tvTargetAppsCount.text = getString(R.string.waqfa_target_apps_desc, count)

            // عرض شارة حالة محرك الرصد اللحظي (0ms) / المتوافق مع البنوك / الخامل
            when (state.engineType) {
                WaqfaEngineType.INSTANT_0MS -> {
                    val statusText = if (!state.isRemote && state.isAutoUpgradedByOtherFeature && !config.useInstantAccessibilityMode) {
                        getString(R.string.waqfa_engine_status_auto_upgraded)
                    } else {
                        getString(R.string.waqfa_engine_status_instant)
                    }
                    binding.tvEngineStatus.text = statusText
                    binding.tvEngineStatus.setTextColor(ContextCompat.getColor(this, R.color.status_green))
                    binding.tvEngineStatus.visibility = View.VISIBLE
                }
                WaqfaEngineType.USAGE_STATS_FALLBACK -> {
                    binding.tvEngineStatus.text = getString(R.string.waqfa_engine_status_bank_compatible)
                    binding.tvEngineStatus.setTextColor(ContextCompat.getColor(this, R.color.status_green))
                    binding.tvEngineStatus.visibility = View.VISIBLE
                }
                WaqfaEngineType.DORMANT -> {
                    val statusText = if (state.isRemote) {
                        getString(R.string.waqfa_engine_status_dormant)
                    } else {
                        getString(R.string.waqfa_engine_status_dormant_clickable)
                    }
                    binding.tvEngineStatus.text = statusText
                    val dormantColor = if (!state.isRemote && config.isEnabled) {
                        ContextCompat.getColor(this, R.color.status_orange)
                    } else {
                        MaterialColors.getColor(
                            binding.tvEngineStatus,
                            com.google.android.material.R.attr.colorOnSurfaceVariant
                        )
                    }
                    binding.tvEngineStatus.setTextColor(dormantColor)
                    binding.tvEngineStatus.visibility = View.VISIBLE
                }
            }

            if (binding.swReasonRequired.isChecked != config.isReasonRequired) {
                binding.swReasonRequired.isChecked = config.isReasonRequired
            }

            when (config.timeoutReactionType) {
                WaqfaTimeoutReactionType.EXIT_ONLY -> {
                    binding.rbExitOnly.isChecked = true
                    binding.layoutCooldownDuration.visibility = View.GONE
                }
                WaqfaTimeoutReactionType.EXIT_AND_FREEZE -> {
                    binding.rbExitAndFreeze.isChecked = true
                    binding.layoutCooldownDuration.visibility = View.VISIBLE
                }
                WaqfaTimeoutReactionType.NOTIFY_ONLY -> {
                    binding.rbNotifyOnly.isChecked = true
                    binding.layoutCooldownDuration.visibility = View.GONE
                }
            }

            val cooldownStr = config.cooldownMinutes.toString()
            if (binding.etCooldownMinutes.text?.toString() != cooldownStr) {
                binding.etCooldownMinutes.setText(cooldownStr)
            }

            if (binding.swAllowCustomDuration.isChecked != config.allowCustomDuration) {
                binding.swAllowCustomDuration.isChecked = config.allowCustomDuration
            }

            if (binding.swAllowOpenSession.isChecked != config.allowOpenSession) {
                binding.swAllowOpenSession.isChecked = config.allowOpenSession
            }

            val durationsSig = config.quickDurationsMinutes.joinToString(",")
            if (durationsSig != lastRenderedDurationsSignature) {
                lastRenderedDurationsSignature = durationsSig
                renderQuickDurationChips(config.quickDurationsMinutes)
            }

            binding.btnSaveWaqfaSettings.isEnabled = !state.isLoading

            if (state.showPermissionDialog) {
                showUsageAccessPromptDialog()
                viewModel.dismissPermissionDialog()
            }
        } finally {
            isBindingUi = false
        }
    }

    private fun renderRemoteWarning(state: WaqfaSettingsUiState) {
        if (!state.isRemote || !state.config.isEnabled) {
            binding.cardWaqfaRemoteWarning.visibility = View.GONE
            return
        }

        val config = state.config
        val warningRes = when {
            config.useInstantAccessibilityMode &&
                state.wardHasAccessibilityService == false &&
                state.wardCanWriteSecureSettings == false -> R.string.waqfa_remote_warning_a11y_qr

            !config.useInstantAccessibilityMode &&
                state.wardHasUsageStats == false &&
                state.wardHasAccessibilityService != true -> R.string.waqfa_remote_warning_usage_access

            else -> null
        }

        if (warningRes != null) {
            binding.tvWaqfaRemoteWarning.setText(warningRes)
            binding.cardWaqfaRemoteWarning.visibility = View.VISIBLE
        } else {
            binding.cardWaqfaRemoteWarning.visibility = View.GONE
        }
    }

    private fun renderQuickDurationChips(durations: List<Int>) {
        binding.cgQuickDurations.removeAllViews()
        val surfaceContainer = MaterialColors.getColor(binding.root, com.google.android.material.R.attr.colorSurfaceContainerHigh)
        val defaultBorder = MaterialColors.getColor(binding.root, com.google.android.material.R.attr.colorOutlineVariant)
        val onSurfaceColor = MaterialColors.getColor(binding.root, com.google.android.material.R.attr.colorOnSurface)

        for (duration in durations) {
            val chip = com.google.android.material.chip.Chip(this).apply {
                id = View.generateViewId()
                text = getString(R.string.waqfa_minutes_format, duration)
                isCloseIconVisible = true
                isCheckable = false
                setTextColor(onSurfaceColor)
                chipBackgroundColor = android.content.res.ColorStateList.valueOf(surfaceContainer)
                chipStrokeColor = android.content.res.ColorStateList.valueOf(defaultBorder)
                chipStrokeWidth = 1f
                setOnCloseIconClickListener {
                    val success = viewModel.removeQuickDuration(duration)
                    if (!success) {
                        Toast.makeText(this@WaqfaSettingsActivity, getString(R.string.waqfa_error_min_one_duration), Toast.LENGTH_SHORT).show()
                    }
                }
            }
            binding.cgQuickDurations.addView(chip)
        }
    }

    private fun showAddDurationDialog() {
        supportFragmentManager.showSafely(TAG_ADD_DURATION) {
            TextInputDialogFragment.newInstance(
                requestKey = REQ_ADD_DURATION,
                titleRes = R.string.waqfa_dialog_add_duration_title,
                hintRes = R.string.waqfa_dialog_add_duration_hint,
                positiveRes = R.string.waqfa_btn_add,
                negativeRes = R.string.waqfa_btn_cancel,
                inputType = InputType.TYPE_CLASS_NUMBER,
                singleLine = true,
                validationMode = TextInputDialogFragment.VALIDATION_INT_RANGE_1_180
            )
        }
    }

    private fun showUsageAccessPromptDialog() {
        if (isFinishing || isDestroyed) return
        supportFragmentManager.showSafely(TAG_USAGE_ACCESS_PROMPT) {
            ConfirmActionDialogFragment.newInstance(
                requestKey = REQ_USAGE_ACCESS_PROMPT,
                titleRes = R.string.waqfa_usage_access_dialog_title,
                messageRes = R.string.waqfa_usage_access_dialog_msg,
                positiveRes = R.string.waqfa_usage_access_dialog_open_btn,
                negativeRes = R.string.waqfa_permission_dialog_skip_btn
            )
        }
    }

    private fun openUsageAccessSettings() {
        try {
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        } catch (e: Exception) {
            Timber.w(e, "Unable to open Usage Access Settings")
            Toast.makeText(this, getString(R.string.waqfa_could_not_open_settings), Toast.LENGTH_SHORT).show()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(KEY_AWAITING_A11Y_RETURN, awaitingA11yReturn)
        outState.putBoolean(KEY_AWAITING_USAGE_RETURN, awaitingUsageReturn)
    }

    companion object {
        const val EXTRA_IS_REMOTE = "extra_is_remote"
        const val EXTRA_WARD_ID = "extra_ward_id"
        const val EXTRA_POLICY_JSON = "extra_policy_json"
        private const val KEY_AWAITING_A11Y_RETURN = "key_awaiting_a11y_return"
        private const val KEY_AWAITING_USAGE_RETURN = "key_awaiting_usage_return"
        private const val REQ_ADD_DURATION = "req_waqfa_add_duration"
        private const val TAG_ADD_DURATION = "tag_waqfa_add_duration"
        private const val REQ_USAGE_ACCESS_PROMPT = "req_waqfa_usage_access"
        private const val TAG_USAGE_ACCESS_PROMPT = "tag_waqfa_usage_access"
    }
}
