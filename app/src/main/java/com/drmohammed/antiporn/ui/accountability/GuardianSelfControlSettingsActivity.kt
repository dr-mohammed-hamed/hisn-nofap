package com.drmohammed.antiporn.ui.accountability

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.drmohammed.antiporn.R
import com.drmohammed.antiporn.core.model.DevicePolicyBundle
import com.drmohammed.antiporn.core.time.TrustedTimeProvider
import com.drmohammed.antiporn.data.repository.PolicyRepository
import com.drmohammed.antiporn.databinding.ActivityGuardianSelfControlSettingsBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Modern, Minimalist Activity for Supervisors to configure Granular Self-Control
 * permissions for their ward, and manage remote QR lock resets.
 */
class GuardianSelfControlSettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityGuardianSelfControlSettingsBinding
    private lateinit var policyRepository: PolicyRepository

    private var wardId: String = ""
    private var currentPolicy: DevicePolicyBundle = DevicePolicyBundle.defaultPolicy()
    private var isBinding = false
    private var isPushing = false

    companion object {
        const val EXTRA_WARD_ID = "extra_ward_id"
        const val EXTRA_POLICY_JSON = "extra_policy_json"
        private const val TAG = "GuardianSelfControl"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        binding = ActivityGuardianSelfControlSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        applyWindowInsets()

        policyRepository = PolicyRepository.getInstance(applicationContext)

        wardId = intent.getStringExtra(EXTRA_WARD_ID).orEmpty()
        val policyJson = intent.getStringExtra(EXTRA_POLICY_JSON)

        if (wardId.isBlank() || policyJson.isNullOrBlank()) {
            Log.e(TAG, "Refusing launch: missing EXTRA_WARD_ID or EXTRA_POLICY_JSON")
            Toast.makeText(applicationContext, R.string.toast_guardian_policy_save_failed, Toast.LENGTH_LONG).show()
            finish()
            return
        }

        val parsed = DevicePolicyBundle.fromJsonOrNull(policyJson)
        if (parsed == null) {
            Log.e(TAG, "Refusing launch: invalid EXTRA_POLICY_JSON")
            Toast.makeText(applicationContext, R.string.toast_guardian_policy_save_failed, Toast.LENGTH_LONG).show()
            finish()
            return
        }
        currentPolicy = parsed

        setupToolbar()
        bindPolicyToViews(currentPolicy)
        setupListeners()
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
            if (!isPushing) {
                navigateBackWithResult()
            }
        }
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (!isPushing) {
                    navigateBackWithResult()
                }
            }
        })
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
            if (!isPushing) binding.switchMasterSelfControl.performClick()
        }

        binding.cardPermCurfew.setOnClickListener {
            if (!isPushing && binding.switchPermCurfew.isEnabled) binding.switchPermCurfew.performClick()
        }

        binding.cardPermWeb.setOnClickListener {
            if (!isPushing && binding.switchPermWeb.isEnabled) binding.switchPermWeb.performClick()
        }

        binding.cardPermApps.setOnClickListener {
            if (!isPushing && binding.switchPermApps.isEnabled) binding.switchPermApps.performClick()
        }

        binding.cardPermWaqfa.setOnClickListener {
            if (!isPushing && binding.switchPermWaqfa.isEnabled) binding.switchPermWaqfa.performClick()
        }

        binding.cardPermPageShield.setOnClickListener {
            if (!isPushing && binding.switchPermPageShield.isEnabled) binding.switchPermPageShield.performClick()
        }

        binding.cardPermNewApps.setOnClickListener {
            if (!isPushing && binding.switchPermNewApps.isEnabled) binding.switchPermNewApps.performClick()
        }

        binding.switchMasterSelfControl.setOnCheckedChangeListener { _, isChecked ->
            if (isBinding) return@setOnCheckedChangeListener
            updateGranularContainerState(isChecked)
            pushUpdatedPolicy { it.copy(isSelfControlAllowed = isChecked) }
        }

        binding.switchPermCurfew.setOnCheckedChangeListener { _, isChecked ->
            if (isBinding) return@setOnCheckedChangeListener
            pushUpdatedPolicy { it.copy(selfControlCurfewAllowed = isChecked) }
        }

        binding.switchPermWeb.setOnCheckedChangeListener { _, isChecked ->
            if (isBinding) return@setOnCheckedChangeListener
            pushUpdatedPolicy { it.copy(selfControlWebAllowed = isChecked) }
        }

        binding.switchPermApps.setOnCheckedChangeListener { _, isChecked ->
            if (isBinding) return@setOnCheckedChangeListener
            pushUpdatedPolicy { it.copy(selfControlAppsAllowed = isChecked) }
        }

        binding.switchPermWaqfa.setOnCheckedChangeListener { _, isChecked ->
            if (isBinding) return@setOnCheckedChangeListener
            pushUpdatedPolicy { it.copy(selfControlWaqfaAllowed = isChecked) }
        }

        binding.switchPermPageShield.setOnCheckedChangeListener { _, isChecked ->
            if (isBinding) return@setOnCheckedChangeListener
            pushUpdatedPolicy { it.copy(selfControlPageShieldAllowed = isChecked) }
        }

        binding.switchPermNewApps.setOnCheckedChangeListener { _, isChecked ->
            if (isBinding) return@setOnCheckedChangeListener
            pushUpdatedPolicy { it.copy(selfControlNewAppsAllowed = isChecked) }
        }

        binding.btnResetWardQrLock.setOnClickListener {
            showResetWardQrConfirmationDialog()
        }
    }

    private fun pushUpdatedPolicy(updater: (DevicePolicyBundle) -> DevicePolicyBundle) {
        if (wardId.isBlank() || isPushing) return
        val updated = updater(currentPolicy).copy(
            version = currentPolicy.version + 1,
            updatedAt = TrustedTimeProvider.currentTimeMillis(applicationContext)
        )

        isPushing = true
        setControlsEnabled(false)
        lifecycleScope.launch(Dispatchers.IO) {
            val success = policyRepository.pushPolicyToWard(wardId, updated)
            withContext(Dispatchers.Main) {
                isPushing = false
                if (isFinishing || isDestroyed) return@withContext
                setControlsEnabled(true)
                if (success) {
                    currentPolicy = updated
                    Toast.makeText(
                        this@GuardianSelfControlSettingsActivity,
                        R.string.toast_guardian_permissions_saved,
                        Toast.LENGTH_SHORT
                    ).show()
                } else {
                    bindPolicyToViews(currentPolicy)
                    Toast.makeText(
                        this@GuardianSelfControlSettingsActivity,
                        R.string.toast_guardian_policy_save_failed,
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    private fun showResetWardQrConfirmationDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.dialog_reset_ward_qr_title)
            .setMessage(R.string.dialog_reset_ward_qr_msg)
            .setPositiveButton(R.string.btn_reset_ward_qr_lock) { dialog, _ ->
                dialog.dismiss()
                executeRemoteQrReset()
            }
            .setNegativeButton(R.string.dialog_btn_cancel, null)
            .show()
    }

    private fun executeRemoteQrReset() {
        if (wardId.isBlank() || isPushing) return
        isPushing = true
        setControlsEnabled(false)

        lifecycleScope.launch(Dispatchers.IO) {
            val now = TrustedTimeProvider.currentTimeMillis(applicationContext)
            val updated = currentPolicy.copy(
                remoteSelfControlResetRequestedAt = now,
                version = currentPolicy.version + 1,
                updatedAt = now
            )
            val success = policyRepository.pushPolicyToWard(wardId, updated)
            withContext(Dispatchers.Main) {
                isPushing = false
                if (!isFinishing && !isDestroyed) {
                    setControlsEnabled(true)
                    if (success) {
                        currentPolicy = updated
                        Toast.makeText(
                            this@GuardianSelfControlSettingsActivity,
                            R.string.toast_reset_ward_qr_success,
                            Toast.LENGTH_SHORT
                        ).show()
                    } else {
                        Toast.makeText(
                            this@GuardianSelfControlSettingsActivity,
                            R.string.toast_reset_ward_qr_failed,
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
        }
    }

    private fun navigateBackWithResult() {
        val resultIntent = Intent().apply {
            putExtra(EXTRA_POLICY_JSON, currentPolicy.toJson())
        }
        setResult(Activity.RESULT_OK, resultIntent)
        finish()
    }
}
