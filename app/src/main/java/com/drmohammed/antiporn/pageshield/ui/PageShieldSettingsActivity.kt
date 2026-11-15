package com.drmohammed.antiporn.pageshield.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.drmohammed.antiporn.R
import com.drmohammed.antiporn.databinding.ActivityPageShieldSettingsBinding
import com.drmohammed.antiporn.pageshield.model.AppPageShieldPolicy
import com.drmohammed.antiporn.pageshield.model.PageShieldReaction
import com.drmohammed.antiporn.pageshield.registry.PageShieldRegistry
import com.drmohammed.antiporn.ui.control.gate.SelfControlAccessGateRegistry
import com.drmohammed.antiporn.ui.control.gate.SelfControlSection
import kotlinx.coroutines.launch
import org.koin.androidx.viewmodel.ext.android.viewModel

/**
 * شاشة إعدادات «درع الصفحات».
 * تدعم وضع التحكم الذاتي المحلي ووضع المشرف عن بعد بتجانس بصري كامل وسلاسة مطلقة.
 */
class PageShieldSettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPageShieldSettingsBinding
    private val viewModel: PageShieldSettingsViewModel by viewModel()
    private var isBindingUi = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
        setupListeners()
        observeUiState()

        viewModel.initialize(isRemote, wardId, policyJson)
    }

    private fun setupToolbar(isRemote: Boolean) {
        if (isRemote) {
            binding.toolbar.title = getString(R.string.page_shield_guardian_card_title)
        }
        binding.toolbar.setNavigationOnClickListener {
            finish()
        }
    }

    private fun setupListeners() {
        binding.swPageShieldMaster.setOnCheckedChangeListener { _, isChecked ->
            if (isBindingUi) return@setOnCheckedChangeListener
            viewModel.setMasterEnabled(isChecked)
        }

        binding.layoutAppHeader.setOnClickListener {
            viewModel.toggleAppExpanded()
        }

        binding.cbShortsPage.setOnCheckedChangeListener { _, isChecked ->
            if (isBindingUi) return@setOnCheckedChangeListener
            viewModel.setPageEnabled(
                packageName = PageShieldRegistry.PACKAGE_YOUTUBE,
                pageId = PageShieldRegistry.PAGE_YOUTUBE_SHORTS,
                enabled = isChecked
            )
        }

        binding.rgReaction.setOnCheckedChangeListener { _, checkedId ->
            if (isBindingUi) return@setOnCheckedChangeListener
            when (checkedId) {
                R.id.rbExitOnly -> {
                    viewModel.setReaction(PageShieldRegistry.PACKAGE_YOUTUBE, PageShieldReaction.EXIT_ONLY)
                    binding.layoutFreezeDuration.isVisible = false
                }
                R.id.rbExitAndFreeze -> {
                    viewModel.setReaction(PageShieldRegistry.PACKAGE_YOUTUBE, PageShieldReaction.EXIT_AND_FREEZE)
                    binding.layoutFreezeDuration.isVisible = true
                }
            }
        }

        binding.etFreezeDuration.doAfterTextChanged { text ->
            if (isBindingUi) return@doAfterTextChanged
            val str = text?.toString()?.trim().orEmpty()
            if (str.isEmpty()) {
                binding.etFreezeDuration.error = getString(R.string.page_shield_freeze_range_error)
                return@doAfterTextChanged
            }
            val mins = str.toIntOrNull()
            if (mins == null || mins !in AppPageShieldPolicy.MIN_FREEZE_MINUTES..AppPageShieldPolicy.MAX_FREEZE_MINUTES) {
                binding.etFreezeDuration.error = getString(R.string.page_shield_freeze_range_error)
            } else {
                binding.etFreezeDuration.error = null
                viewModel.setFreezeDurationMinutes(PageShieldRegistry.PACKAGE_YOUTUBE, mins)
            }
        }

        binding.btnSavePageShieldSettings.setOnClickListener {
            if (binding.rbExitAndFreeze.isChecked && binding.etFreezeDuration.error != null) {
                binding.etFreezeDuration.requestFocus()
                return@setOnClickListener
            }
            binding.btnSavePageShieldSettings.isEnabled = false
            viewModel.saveSettings { success, updatedPolicy ->
                if (isFinishing || isDestroyed) return@saveSettings
                if (success) {
                    if (updatedPolicy != null) {
                        setResult(
                            Activity.RESULT_OK,
                            Intent().putExtra(EXTRA_POLICY_JSON, updatedPolicy.toJson())
                        )
                    }
                    Toast.makeText(this, getString(R.string.page_shield_saved_toast), Toast.LENGTH_SHORT).show()
                    finish()
                } else {
                    binding.btnSavePageShieldSettings.isEnabled = true
                    Toast.makeText(this, getString(R.string.page_shield_save_failed_toast), Toast.LENGTH_SHORT).show()
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

            // Accordion expand/collapse
            binding.layoutAppDetails.isVisible = state.isAppExpanded
            binding.ivExpandChevron.rotation = if (state.isAppExpanded) 180f else 0f

            val ytPolicy = config.appPolicies[PageShieldRegistry.PACKAGE_YOUTUBE]
            val isShortsEnabled = ytPolicy?.enabledPageIds?.contains(PageShieldRegistry.PAGE_YOUTUBE_SHORTS) ?: false
            if (binding.cbShortsPage.isChecked != isShortsEnabled) {
                binding.cbShortsPage.isChecked = isShortsEnabled
            }

            val reaction = ytPolicy?.reaction ?: PageShieldReaction.EXIT_ONLY
            when (reaction) {
                PageShieldReaction.EXIT_ONLY -> {
                    if (binding.rgReaction.checkedRadioButtonId != R.id.rbExitOnly) {
                        binding.rgReaction.check(R.id.rbExitOnly)
                    }
                    binding.layoutFreezeDuration.isVisible = false
                }
                PageShieldReaction.EXIT_AND_FREEZE -> {
                    if (binding.rgReaction.checkedRadioButtonId != R.id.rbExitAndFreeze) {
                        binding.rgReaction.check(R.id.rbExitAndFreeze)
                    }
                    binding.layoutFreezeDuration.isVisible = true
                }
            }

            val freezeMins = ytPolicy?.freezeDurationMinutes ?: state.freezeDurationMinutes
            val currentText = binding.etFreezeDuration.text?.toString()
            if (!binding.etFreezeDuration.isFocused && currentText != freezeMins.toString()) {
                binding.etFreezeDuration.setText(freezeMins.toString())
            }

            binding.btnSavePageShieldSettings.isEnabled = !state.isLoading
        } finally {
            isBindingUi = false
        }
    }

    companion object {
        const val EXTRA_IS_REMOTE = "extra_is_remote"
        const val EXTRA_WARD_ID = "extra_ward_id"
        const val EXTRA_POLICY_JSON = "extra_policy_json"
    }
}
