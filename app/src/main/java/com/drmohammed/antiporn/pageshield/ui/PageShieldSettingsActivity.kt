package com.drmohammed.antiporn.pageshield.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.drmohammed.antiporn.R
import com.drmohammed.antiporn.accessibility.control.AccessibilityReconcileResult
import com.drmohammed.antiporn.accessibility.ui.AccessibilityOnboardingBottomSheet
import com.drmohammed.antiporn.banking.BankingShortcutPromptHelper
import com.drmohammed.antiporn.databinding.ActivityPageShieldSettingsBinding
import com.drmohammed.antiporn.pageshield.registry.PageShieldRegistry
import com.drmohammed.antiporn.pageshield.ui.adapter.PageShieldAppsAdapter
import com.drmohammed.antiporn.ui.control.gate.SelfControlAccessGateRegistry
import com.drmohammed.antiporn.ui.control.gate.SelfControlSection
import com.google.android.material.color.MaterialColors
import kotlinx.coroutines.launch
import org.koin.androidx.viewmodel.ext.android.viewModel

/**
 * شاشة إعدادات «درع الصفحات».
 * تدعم وضع التحكم الذاتي المحلي ووضع المشرف عن بعد بتجانس بصري كامل وسلاسة مطلقة.
 * أصبحت ديناميكية بالكامل وتعتمد على [PageShieldRegistry] لعرض كافة التطبيقات المسجلة.
 */
class PageShieldSettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPageShieldSettingsBinding
    private val viewModel: PageShieldSettingsViewModel by viewModel()
    private lateinit var appsAdapter: PageShieldAppsAdapter
    private var isBindingUi = false
    private var awaitingA11yReturn = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        awaitingA11yReturn = savedInstanceState?.getBoolean(KEY_AWAITING_A11Y_RETURN, false) ?: false
        binding = ActivityPageShieldSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val isRemote = intent.getBooleanExtra(EXTRA_IS_REMOTE, false)
        val wardId = intent.getStringExtra(EXTRA_WARD_ID)
        val policyJson = intent.getStringExtra(EXTRA_POLICY_JSON)

        if (!isRemote && (!SelfControlAccessGateRegistry.isSelfControlPermitted(this) ||
            !SelfControlAccessGateRegistry.isSectionPermitted(this, SelfControlSection.PAGE_SHIELD))) {
            Toast.makeText(this, R.string.self_control_section_locked_by_guardian, Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        setupToolbar(isRemote)
        setupRecyclerView()
        setupListeners()
        observeUiState()

        viewModel.initialize(isRemote, wardId, policyJson)
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshEngineStatus()
        val state = viewModel.uiState.value
        if (awaitingA11yReturn && state.isAccessibilityActive) {
            awaitingA11yReturn = false
            Toast.makeText(this, R.string.a11y_onboarding_enabled_success_toast, Toast.LENGTH_SHORT).show()
            BankingShortcutPromptHelper.maybePrompt(this) {}
        }
    }

    private fun setupToolbar(isRemote: Boolean) {
        if (isRemote) {
            binding.toolbar.title = getString(R.string.page_shield_guardian_card_title)
        }
        binding.toolbar.setNavigationOnClickListener {
            finish()
        }
    }

    private fun setupRecyclerView() {
        appsAdapter = PageShieldAppsAdapter(
            onToggleExpand = { packageName ->
                viewModel.toggleAppExpanded(packageName)
            },
            onPageToggled = { packageName, pageId, isChecked ->
                viewModel.setPageEnabled(packageName, pageId, isChecked)
            },
            onReactionChanged = { packageName, reaction ->
                viewModel.setReaction(packageName, reaction)
            },
            onFreezeDurationChanged = { packageName, minutes ->
                viewModel.setFreezeDurationMinutes(packageName, minutes)
            }
        )
        binding.rvProtectedApps.layoutManager = LinearLayoutManager(this)
        binding.rvProtectedApps.adapter = appsAdapter
    }

    private fun setupListeners() {
        binding.swPageShieldMaster.setOnCheckedChangeListener { _, isChecked ->
            if (isBindingUi) return@setOnCheckedChangeListener
            viewModel.setMasterEnabled(isChecked)
        }

        binding.cardPageShieldEngineStatus.setOnClickListener {
            val state = viewModel.uiState.value
            if (state.isRemote) return@setOnClickListener
            if (state.isAccessibilityActive) {
                Toast.makeText(this, R.string.page_shield_engine_status_active_toast, Toast.LENGTH_SHORT).show()
            } else {
                awaitingA11yReturn = true
                AccessibilityOnboardingBottomSheet.showIfNotShowing(
                    fragmentManager = supportFragmentManager,
                    reason = AccessibilityOnboardingBottomSheet.REASON_PAGE_SHIELD
                )
            }
        }

        binding.btnSavePageShieldSettings.setOnClickListener {
            binding.btnSavePageShieldSettings.isEnabled = false
            viewModel.saveSettings { success, updatedPolicy, reconcileResult ->
                if (isFinishing || isDestroyed) return@saveSettings
                if (!success) {
                    binding.btnSavePageShieldSettings.isEnabled = true
                    Toast.makeText(this, getString(R.string.page_shield_save_failed_toast), Toast.LENGTH_SHORT).show()
                    return@saveSettings
                }

                if (updatedPolicy != null) {
                    setResult(
                        Activity.RESULT_OK,
                        Intent().putExtra(EXTRA_POLICY_JSON, updatedPolicy.toJson())
                    )
                }
                Toast.makeText(this, getString(R.string.page_shield_saved_toast), Toast.LENGTH_SHORT).show()

                if (viewModel.uiState.value.isRemote) {
                    finish()
                    return@saveSettings
                }

                when (reconcileResult) {
                    is AccessibilityReconcileResult.NeedsManualActivation -> {
                        // إبقاء الشاشة مفتوحة حتى يكمل المستخدم التفعيل اليدوي ويعود
                        binding.btnSavePageShieldSettings.isEnabled = true
                        awaitingA11yReturn = true
                        AccessibilityOnboardingBottomSheet.showIfNotShowing(
                            fragmentManager = supportFragmentManager,
                            reason = AccessibilityOnboardingBottomSheet.REASON_PAGE_SHIELD
                        )
                    }
                    is AccessibilityReconcileResult.EnabledSilently -> {
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
        }
    }

    private fun observeUiState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    renderUi(state)
                }
            }
        }
    }

    private fun renderUi(state: PageShieldSettingsUiState) {
        isBindingUi = true
        try {
            val config = state.config

            if (binding.swPageShieldMaster.isChecked != config.isEnabled) {
                binding.swPageShieldMaster.isChecked = config.isEnabled
            }

            renderEngineStatus(state)
            renderRemoteWarning(state)

            appsAdapter.submitData(
                newApps = PageShieldRegistry.registeredApps,
                newExpanded = state.expandedPackages,
                newConfig = config
            )

            binding.btnSavePageShieldSettings.isEnabled = !state.isLoading
        } finally {
            isBindingUi = false
        }
    }

    private fun renderEngineStatus(state: PageShieldSettingsUiState) {
        val greenColor = ContextCompat.getColor(this, R.color.status_green)
        val greenBg = ContextCompat.getColor(this, R.color.status_green_bg)
        val amberColor = ContextCompat.getColor(this, R.color.status_orange)
        val amberBg = ContextCompat.getColor(this, R.color.status_orange_bg)
        val onSecondaryContainer = MaterialColors.getColor(binding.root, com.google.android.material.R.attr.colorOnSecondaryContainer)
        val secondaryBg = MaterialColors.getColor(binding.root, com.google.android.material.R.attr.colorSecondaryContainer)

        if (state.isRemote) {
            when {
                state.wardHasAccessibilityService == true -> {
                    applyEngineStatusStyle(
                        textRes = R.string.page_shield_engine_status_remote_active,
                        tintColor = greenColor,
                        bgColor = greenBg
                    )
                }
                state.wardCanWriteSecureSettings == true -> {
                    applyEngineStatusStyle(
                        textRes = R.string.page_shield_engine_status_remote_auto_ready,
                        tintColor = onSecondaryContainer,
                        bgColor = secondaryBg
                    )
                }
                else -> {
                    applyEngineStatusStyle(
                        textRes = R.string.page_shield_engine_status_remote_needs_manual,
                        tintColor = amberColor,
                        bgColor = amberBg
                    )
                }
            }
        } else {
            when {
                state.isAccessibilityActive -> {
                    applyEngineStatusStyle(
                        textRes = R.string.page_shield_engine_status_active,
                        tintColor = greenColor,
                        bgColor = greenBg
                    )
                }
                state.canWriteSecureSettings -> {
                    applyEngineStatusStyle(
                        textRes = R.string.page_shield_engine_status_auto_ready,
                        tintColor = onSecondaryContainer,
                        bgColor = secondaryBg
                    )
                }
                else -> {
                    applyEngineStatusStyle(
                        textRes = R.string.page_shield_engine_status_needs_manual,
                        tintColor = amberColor,
                        bgColor = amberBg
                    )
                }
            }
        }
    }

    private fun applyEngineStatusStyle(textRes: Int, tintColor: Int, bgColor: Int) {
        binding.tvPageShieldEngineStatus.setText(textRes)
        binding.tvPageShieldEngineStatus.setTextColor(tintColor)
        binding.ivPageShieldEngineStatus.imageTintList = android.content.res.ColorStateList.valueOf(tintColor)
        binding.cardPageShieldEngineStatus.setCardBackgroundColor(bgColor)
        binding.cardPageShieldEngineStatus.strokeColor = tintColor
    }

    private fun renderRemoteWarning(state: PageShieldSettingsUiState) {
        val shouldWarn = state.isRemote &&
            state.config.isEnabled &&
            state.wardHasAccessibilityService == false &&
            state.wardCanWriteSecureSettings == false
        binding.cardPageShieldRemoteWarning.visibility = if (shouldWarn) View.VISIBLE else View.GONE
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(KEY_AWAITING_A11Y_RETURN, awaitingA11yReturn)
    }

    companion object {
        const val EXTRA_IS_REMOTE = "extra_is_remote"
        const val EXTRA_WARD_ID = "extra_ward_id"
        const val EXTRA_POLICY_JSON = "extra_policy_json"
        private const val KEY_AWAITING_A11Y_RETURN = "key_awaiting_a11y_return"
    }
}
