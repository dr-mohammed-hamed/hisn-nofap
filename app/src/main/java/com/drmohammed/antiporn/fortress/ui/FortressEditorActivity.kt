package com.drmohammed.antiporn.fortress.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.drmohammed.antiporn.R
import com.drmohammed.antiporn.core.model.DevicePolicyBundle
import com.drmohammed.antiporn.core.time.TrustedTimeProvider
import com.drmohammed.antiporn.data.repository.AccountabilityRepository
import com.drmohammed.antiporn.data.repository.PolicyRepository
import com.drmohammed.antiporn.databinding.ActivityFortressEditorBinding
import com.drmohammed.antiporn.fortress.engine.FortressSessionController
import com.drmohammed.antiporn.fortress.model.FortressProfile
import com.drmohammed.antiporn.fortress.model.FortressesConfig
import com.drmohammed.antiporn.ui.control.curfew.CurfewAppPickerBottomSheet
import com.drmohammed.antiporn.ui.control.gate.SelfControlAccessGateRegistry
import com.drmohammed.antiporn.ui.control.gate.SelfControlSection
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * Dual-mode (Local Self-Control / Remote Guardian) editor for creating, editing,
 * and deleting Fortresses while enforcing Emergency Fortress & active-session guards.
 */
class FortressEditorActivity : AppCompatActivity() {

    private lateinit var binding: ActivityFortressEditorBinding
    private lateinit var viewBinder: FortressEditorViewBinder

    private var isRemote = false
    private var wardId: String = ""
    private var wardActiveFortressId: String? = null
    private var wardActiveFortressEndsAtWall: Long = 0L
    private var targetFortressId: String? = null
    private lateinit var initialPolicy: DevicePolicyBundle
    private var isSaving = false
    private var deleteDialog: AlertDialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        binding = ActivityFortressEditorBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyWindowInsets()

        isRemote = intent.getBooleanExtra(FortressesListActivity.EXTRA_IS_REMOTE, false)
        wardId = intent.getStringExtra(FortressesListActivity.EXTRA_WARD_ID).orEmpty()
        targetFortressId = intent.getStringExtra(EXTRA_FORTRESS_ID)?.takeIf { it.isNotBlank() }

        if (!isRemote && !hasLocalFortressPermission()) {
            Toast.makeText(this, R.string.self_control_section_locked_by_guardian, Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        if (isRemote) {
            val policyJson = intent.getStringExtra(FortressesListActivity.EXTRA_POLICY_JSON)
            val parsed = policyJson?.let { DevicePolicyBundle.fromJsonOrNull(it) }
            if (wardId.isBlank() || parsed == null) {
                Timber.tag(TAG).e("Refusing remote FortressEditor launch: missing wardId or policyJson")
                Toast.makeText(applicationContext, R.string.toast_guardian_policy_save_failed, Toast.LENGTH_LONG).show()
                finish()
                return
            }
            initialPolicy = parsed
            wardActiveFortressId = intent.getStringExtra(FortressesListActivity.EXTRA_WARD_ACTIVE_FORTRESS_ID)
                ?.takeIf { it.isNotBlank() }
                ?: parsed.wardActiveFortressId
            wardActiveFortressEndsAtWall = intent.getLongExtra(
                FortressesListActivity.EXTRA_WARD_ACTIVE_FORTRESS_ENDS_AT_WALL,
                parsed.wardActiveFortressEndsAtWall
            )
        } else {
            initialPolicy = PolicyRepository.getInstance(applicationContext).getCurrentPolicy()
        }

        val existingProfile = targetFortressId?.let { initialPolicy.fortressesConfig.findById(it) }
        if (existingProfile != null && isTargetFortressActive(existingProfile.id)) {
            Toast.makeText(this, R.string.fortress_active_edit_blocked_toast, Toast.LENGTH_LONG).show()
            finish()
            return
        }

        supportFragmentManager.setFragmentResultListener(
            CurfewAppPickerBottomSheet.REQUEST_KEY,
            this
        ) { _, bundle ->
            val allowed = bundle.getStringArrayList(CurfewAppPickerBottomSheet.RESULT_WHITELIST)?.toSet() ?: emptySet()
            val bypass = bundle.getStringArrayList(CurfewAppPickerBottomSheet.RESULT_VPN_BYPASS)?.toSet() ?: emptySet()
            viewBinder.selectedAllowedPackages = allowed
            viewBinder.selectedVpnBypassPackages = if (!viewBinder.isEmergency && viewBinder.isInternetCutEnabled) {
                bypass
            } else {
                emptySet()
            }
            viewBinder.updateAllowedAppsSummary()
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (!isSaving) {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
        viewBinder = FortressEditorViewBinder(this, binding)
        viewBinder.bindInitialProfile(existingProfile, initialPolicy.fortressesConfig, savedInstanceState)
        viewBinder.setupListeners(
            isRemote = isRemote,
            wardId = wardId,
            blacklistPackages = initialPolicy.customBlacklistPackages,
            canGoBack = { !isSaving },
            onSaveClicked = ::handleSave,
            onDeleteClicked = { existingProfile?.let(::confirmDelete) }
        )
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (::viewBinder.isInitialized) {
            viewBinder.saveInstanceState(outState)
        }
    }

    override fun onResume() {
        super.onResume()
        if (!isRemote && !hasLocalFortressPermission()) {
            Toast.makeText(this, R.string.self_control_section_locked_by_guardian, Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    override fun onDestroy() {
        deleteDialog?.dismiss()
        deleteDialog = null
        super.onDestroy()
    }

    private fun applyWindowInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.layoutRootFortressEditor) { view, windowInsets ->
            val insets = windowInsets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            view.setPadding(insets.left, insets.top, insets.right, insets.bottom)
            windowInsets
        }
    }

    private fun hasLocalFortressPermission(): Boolean =
        SelfControlAccessGateRegistry.getActiveGate().canAccess(this) &&
            SelfControlAccessGateRegistry.isSectionPermitted(this, SelfControlSection.FORTRESSES)

    private fun isTargetFortressActive(fortressId: String): Boolean {
        return if (isRemote) {
            !wardActiveFortressId.isNullOrBlank() &&
                wardActiveFortressId == fortressId &&
                wardActiveFortressEndsAtWall > TrustedTimeProvider.currentTimeMillis(applicationContext)
        } else {
            FortressSessionController.getActiveSession(this)?.fortressId == fortressId
        }
    }

    private fun handleSave() {
        if (isSaving) return
        val profile = viewBinder.validateAndBuildProfile() ?: return
        if (profile.id.isNotBlank() && isTargetFortressActive(profile.id)) {
            Toast.makeText(this, R.string.fortress_active_edit_blocked_toast, Toast.LENGTH_LONG).show()
            return
        }
        persistConfigMutation(
            transform = { config -> config.upsert(profile) },
            successToastRes = R.string.fortress_toast_saved
        )
    }

    private fun confirmDelete(profile: FortressProfile) {
        if (isSaving || profile.isEmergency) return
        if (isTargetFortressActive(profile.id)) {
            Toast.makeText(this, R.string.fortress_active_edit_blocked_toast, Toast.LENGTH_LONG).show()
            return
        }
        if (isFinishing || isDestroyed || deleteDialog?.isShowing == true) return
        deleteDialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.fortress_delete_confirm_title)
            .setMessage(getString(R.string.fortress_delete_confirm_msg, profile.resolveDisplayName(this)))
            .setPositiveButton(R.string.fortress_btn_delete) { dialog, _ ->
                dialog.dismiss()
                persistConfigMutation(
                    transform = { config -> config.deleteCustom(profile.id) },
                    successToastRes = R.string.fortress_toast_deleted
                )
            }
            .setNegativeButton(R.string.dialog_btn_cancel, null)
            .setOnDismissListener { deleteDialog = null }
            .show()
    }

    private fun persistConfigMutation(
        transform: (FortressesConfig) -> FortressesConfig,
        successToastRes: Int
    ) {
        isSaving = true
        viewBinder.setBusy(true)
        val app = applicationContext

        lifecycleScope.launch {
            try {
                val updatedPolicy = withContext(Dispatchers.IO + NonCancellable) {
                    val repo = PolicyRepository.getInstance(app)
                    if (isRemote) {
                        val accRepo = AccountabilityRepository.getInstance(app)
                        repo.mergeAndPushRemotePolicyPatch(
                            wardUid = wardId,
                            fallbackPolicy = initialPolicy,
                            guardianUidFallback = accRepo.getCachedUid(),
                            guardianNameFallback = accRepo.getMyDisplayName()
                        ) { latest ->
                            val now = TrustedTimeProvider.currentTimeMillis(app)
                            latest.copy(
                                fortressesConfig = transform(latest.fortressesConfig),
                                version = latest.version + 1,
                                updatedAt = now
                            )
                        }
                    } else {
                        repo.mergeAndSaveSelfPolicyPatch { latest ->
                            latest.copy(fortressesConfig = transform(latest.fortressesConfig))
                        }
                    }
                }
                if (isFinishing || isDestroyed) return@launch
                isSaving = false
                viewBinder.setBusy(false)

                if (updatedPolicy != null) {
                    Toast.makeText(app, successToastRes, Toast.LENGTH_SHORT).show()
                    setResult(
                        Activity.RESULT_OK,
                        Intent().putExtra(FortressesListActivity.EXTRA_UPDATED_POLICY_JSON, updatedPolicy.toJson())
                    )
                    finish()
                } else {
                    val failRes = if (isRemote) R.string.toast_guardian_policy_save_failed else R.string.toast_self_policy_save_failed
                    Toast.makeText(app, failRes, Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Timber.tag(TAG).e(e, "Failed persisting fortress config change")
                if (isFinishing || isDestroyed) return@launch
                isSaving = false
                viewBinder.setBusy(false)
                val failRes = if (isRemote) R.string.toast_guardian_policy_save_failed else R.string.toast_self_policy_save_failed
                Toast.makeText(app, failRes, Toast.LENGTH_LONG).show()
            }
        }
    }

    companion object {
        private const val TAG = "FortressEditor"
        const val EXTRA_FORTRESS_ID = "extra_fortress_id"
    }
}
