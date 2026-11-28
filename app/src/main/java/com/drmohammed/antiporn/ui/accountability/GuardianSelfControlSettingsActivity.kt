package com.drmohammed.antiporn.ui.accountability

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.drmohammed.antiporn.R
import com.drmohammed.antiporn.core.model.DevicePolicyBundle
import com.drmohammed.antiporn.databinding.ActivityGuardianSelfControlSettingsBinding
import com.drmohammed.antiporn.ui.common.ConfirmActionDialogFragment
import com.drmohammed.antiporn.ui.common.showSafely
import kotlinx.coroutines.launch
import org.koin.androidx.viewmodel.ext.android.viewModel
import timber.log.Timber

/**
 * Modern, Minimalist Activity for Supervisors to configure Granular Self-Control
 * permissions for their ward, and manage remote QR lock resets.
 */
class GuardianSelfControlSettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityGuardianSelfControlSettingsBinding
    private val viewModel: GuardianSelfControlSettingsViewModel by viewModel()

    private var isBinding = false
    private var lastBoundPolicy: DevicePolicyBundle? = null

    companion object {
        const val EXTRA_WARD_ID = "extra_ward_id"
        const val EXTRA_POLICY_JSON = "extra_policy_json"
        private const val TAG = "GuardianSelfControl"
        private const val REQ_RESET_WARD_QR = "req_guardian_reset_ward_qr"
        private const val TAG_RESET_WARD_QR = "tag_guardian_reset_ward_qr"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        binding = ActivityGuardianSelfControlSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        applyWindowInsets()

        val wardId = intent.getStringExtra(EXTRA_WARD_ID).orEmpty()
        val policyJson = intent.getStringExtra(EXTRA_POLICY_JSON)

        if (wardId.isBlank() || policyJson.isNullOrBlank()) {
            Timber.tag(TAG).e("Refusing launch: missing EXTRA_WARD_ID or EXTRA_POLICY_JSON")
            Toast.makeText(applicationContext, R.string.toast_guardian_policy_save_failed, Toast.LENGTH_LONG).show()
            finish()
            return
        }

        val parsed = DevicePolicyBundle.fromJsonOrNull(policyJson)
        if (parsed == null) {
            Timber.tag(TAG).e("Refusing launch: invalid EXTRA_POLICY_JSON")
            Toast.makeText(applicationContext, R.string.toast_guardian_policy_save_failed, Toast.LENGTH_LONG).show()
            finish()
            return
        }

        val newlyInitialized = viewModel.initialize(wardId, parsed)
        if (newlyInitialized && savedInstanceState != null) {
            viewModel.restoreFromSavedState(savedInstanceState)
        }

        setupToolbar()
        setupFragmentResultListeners()
        setupListeners()
        observeViewModel()
    }

    private fun setupFragmentResultListeners() {
        supportFragmentManager.setFragmentResultListener(REQ_RESET_WARD_QR, this) { _, bundle ->
            if (bundle.getString(ConfirmActionDialogFragment.RESULT_BUTTON) == ConfirmActionDialogFragment.BUTTON_POSITIVE) {
                viewModel.executeRemoteQrReset()
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        viewModel.writeToSavedState(outState)
    }

    private fun applyWindowInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.layoutRootSelfControlSettings) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
    }

    private fun setupToolbar() {
        binding.toolbarSelfControlSettings.setNavigationOnClickListener {
            if (!viewModel.uiState.value.isPushing) {
                navigateBackWithResult()
            }
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (!viewModel.uiState.value.isPushing) {
                    navigateBackWithResult()
                }
            }
        })
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.uiState.collect { state ->
                        if (state.currentPolicy != lastBoundPolicy) {
                            lastBoundPolicy = state.currentPolicy
                            bindPolicyToViews(state.currentPolicy)
                        }
                        setControlsEnabled(!state.isPushing)
                    }
                }
                launch {
                    viewModel.events.collect { event ->
                        when (event) {
                            is GuardianSelfControlEvent.ShowToastRes -> {
                                Toast.makeText(
                                    this@GuardianSelfControlSettingsActivity,
                                    event.resId,
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                            is GuardianSelfControlEvent.RevertSwitchesToPolicy -> {
                                bindPolicyToViews(viewModel.uiState.value.currentPolicy)
                            }
                        }
                    }
                }
            }
        }
    }

    private fun bindPolicyToViews(policy: DevicePolicyBundle) {
        isBinding = true
        binding.switchMasterSelfControl.isChecked = policy.isSelfControlAllowed
        binding.switchPermCurfew.isChecked = policy.selfControlCurfewAllowed
        binding.switchPermWeb.isChecked = policy.selfControlWebAllowed
        binding.switchPermApps.isChecked = policy.selfControlAppsAllowed
        binding.switchPermWaqfa.isChecked = policy.selfControlWaqfaAllowed
        binding.switchPermPageShield.isChecked = policy.selfControlPageShieldAllowed
        binding.switchPermNewApps.isChecked = policy.selfControlNewAppsAllowed

        updateGranularContainerState(policy.isSelfControlAllowed)
        isBinding = false
    }

    private fun updateGranularContainerState(isMasterAllowed: Boolean) {
        binding.layoutGranularContainer.alpha = if (isMasterAllowed) 1.0f else 0.45f
        binding.switchPermCurfew.isEnabled = isMasterAllowed
        binding.switchPermWeb.isEnabled = isMasterAllowed
        binding.switchPermApps.isEnabled = isMasterAllowed
        binding.switchPermWaqfa.isEnabled = isMasterAllowed
        binding.switchPermPageShield.isEnabled = isMasterAllowed
        binding.switchPermNewApps.isEnabled = isMasterAllowed

        binding.cardPermCurfew.isEnabled = isMasterAllowed
        binding.cardPermWeb.isEnabled = isMasterAllowed
        binding.cardPermApps.isEnabled = isMasterAllowed
        binding.cardPermWaqfa.isEnabled = isMasterAllowed
        binding.cardPermPageShield.isEnabled = isMasterAllowed
        binding.cardPermNewApps.isEnabled = isMasterAllowed
    }

    private fun setControlsEnabled(enabled: Boolean) {
        binding.switchMasterSelfControl.isEnabled = enabled
        binding.cardMasterSelfControl.isEnabled = enabled
        if (enabled && binding.switchMasterSelfControl.isChecked) {
            updateGranularContainerState(true)
        } else if (!enabled) {
            binding.switchPermCurfew.isEnabled = false
            binding.switchPermWeb.isEnabled = false
            binding.switchPermApps.isEnabled = false
            binding.switchPermWaqfa.isEnabled = false
            binding.switchPermPageShield.isEnabled = false
            binding.switchPermNewApps.isEnabled = false

            binding.cardPermCurfew.isEnabled = false
            binding.cardPermWeb.isEnabled = false
            binding.cardPermApps.isEnabled = false
            binding.cardPermWaqfa.isEnabled = false
            binding.cardPermPageShield.isEnabled = false
            binding.cardPermNewApps.isEnabled = false
        }
        binding.btnResetWardQrLock.isEnabled = enabled
    }

    private fun setupListeners() {
        binding.cardMasterSelfControl.setOnClickListener {
            if (!viewModel.uiState.value.isPushing) binding.switchMasterSelfControl.performClick()
        }

        binding.cardPermCurfew.setOnClickListener {
            if (!viewModel.uiState.value.isPushing && binding.switchPermCurfew.isEnabled) {
                binding.switchPermCurfew.performClick()
            }
        }

        binding.cardPermWeb.setOnClickListener {
            if (!viewModel.uiState.value.isPushing && binding.switchPermWeb.isEnabled) {
                binding.switchPermWeb.performClick()
            }
        }

        binding.cardPermApps.setOnClickListener {
            if (!viewModel.uiState.value.isPushing && binding.switchPermApps.isEnabled) {
                binding.switchPermApps.performClick()
            }
        }

        binding.cardPermWaqfa.setOnClickListener {
            if (!viewModel.uiState.value.isPushing && binding.switchPermWaqfa.isEnabled) {
                binding.switchPermWaqfa.performClick()
            }
        }

        binding.cardPermPageShield.setOnClickListener {
            if (!viewModel.uiState.value.isPushing && binding.switchPermPageShield.isEnabled) {
                binding.switchPermPageShield.performClick()
            }
        }

        binding.cardPermNewApps.setOnClickListener {
            if (!viewModel.uiState.value.isPushing && binding.switchPermNewApps.isEnabled) {
                binding.switchPermNewApps.performClick()
            }
        }

        binding.switchMasterSelfControl.setOnCheckedChangeListener { _, isChecked ->
            if (isBinding) return@setOnCheckedChangeListener
            updateGranularContainerState(isChecked)
            viewModel.pushPolicyChange { it.copy(isSelfControlAllowed = isChecked) }
        }

        binding.switchPermCurfew.setOnCheckedChangeListener { _, isChecked ->
            if (isBinding) return@setOnCheckedChangeListener
            viewModel.pushPolicyChange { it.copy(selfControlCurfewAllowed = isChecked) }
        }

        binding.switchPermWeb.setOnCheckedChangeListener { _, isChecked ->
            if (isBinding) return@setOnCheckedChangeListener
            viewModel.pushPolicyChange { it.copy(selfControlWebAllowed = isChecked) }
        }

        binding.switchPermApps.setOnCheckedChangeListener { _, isChecked ->
            if (isBinding) return@setOnCheckedChangeListener
            viewModel.pushPolicyChange { it.copy(selfControlAppsAllowed = isChecked) }
        }

        binding.switchPermWaqfa.setOnCheckedChangeListener { _, isChecked ->
            if (isBinding) return@setOnCheckedChangeListener
            viewModel.pushPolicyChange { it.copy(selfControlWaqfaAllowed = isChecked) }
        }

        binding.switchPermPageShield.setOnCheckedChangeListener { _, isChecked ->
            if (isBinding) return@setOnCheckedChangeListener
            viewModel.pushPolicyChange { it.copy(selfControlPageShieldAllowed = isChecked) }
        }

        binding.switchPermNewApps.setOnCheckedChangeListener { _, isChecked ->
            if (isBinding) return@setOnCheckedChangeListener
            viewModel.pushPolicyChange { it.copy(selfControlNewAppsAllowed = isChecked) }
        }

        binding.btnResetWardQrLock.setOnClickListener {
            showResetWardQrConfirmationDialog()
        }
    }

    private fun showResetWardQrConfirmationDialog() {
        if (isFinishing || isDestroyed) return
        supportFragmentManager.showSafely(TAG_RESET_WARD_QR) {
            ConfirmActionDialogFragment.newInstance(
                requestKey = REQ_RESET_WARD_QR,
                titleRes = R.string.dialog_reset_ward_qr_title,
                messageRes = R.string.dialog_reset_ward_qr_msg,
                positiveRes = R.string.btn_reset_ward_qr_lock,
                negativeRes = R.string.dialog_btn_cancel
            )
        }
    }

    private fun navigateBackWithResult() {
        val resultIntent = Intent().apply {
            putExtra(EXTRA_POLICY_JSON, viewModel.uiState.value.currentPolicy.toJson())
        }
        setResult(Activity.RESULT_OK, resultIntent)
        finish()
    }
}
