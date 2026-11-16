package com.drmohammed.antiporn.pageshield.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.drmohammed.antiporn.R
import com.drmohammed.antiporn.databinding.ActivityPageShieldSettingsBinding
import com.drmohammed.antiporn.pageshield.registry.PageShieldRegistry
import com.drmohammed.antiporn.pageshield.ui.adapter.PageShieldAppsAdapter
import com.drmohammed.antiporn.ui.control.gate.SelfControlAccessGateRegistry
import com.drmohammed.antiporn.ui.control.gate.SelfControlSection
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
        setupRecyclerView()
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

        binding.btnSavePageShieldSettings.setOnClickListener {
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

    companion object {
        const val EXTRA_IS_REMOTE = "extra_is_remote"
        const val EXTRA_WARD_ID = "extra_ward_id"
        const val EXTRA_POLICY_JSON = "extra_policy_json"
    }
}
