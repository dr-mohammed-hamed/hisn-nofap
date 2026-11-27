package com.drmohammed.antiporn.fortress.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.drmohammed.antiporn.R
import com.drmohammed.antiporn.core.model.DevicePolicyBundle
import com.drmohammed.antiporn.core.time.TrustedTimeProvider
import com.drmohammed.antiporn.data.repository.AccountabilityRepository
import com.drmohammed.antiporn.data.repository.PolicyRepository
import com.drmohammed.antiporn.databinding.ActivityFortressesListBinding
import com.drmohammed.antiporn.fortress.engine.FortressSessionController
import com.drmohammed.antiporn.fortress.model.FortressProfile
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
 * Dual-mode (Local Self-Control / Remote Guardian) screen listing the mandatory
 * Emergency Fortress and all custom Fortresses with active-session edit/delete guards.
 */
class FortressesListActivity : AppCompatActivity() {

    private lateinit var binding: ActivityFortressesListBinding
    private lateinit var adapter: FortressesListAdapter

    private var isRemote = false
    private var wardId: String = ""
    private var wardActiveFortressId: String? = null
    private var wardActiveFortressEndsAtWall: Long = 0L
    private lateinit var currentPolicy: DevicePolicyBundle
    private var isMutating = false
    private var deleteDialog: AlertDialog? = null

    private val editorLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val updatedJson = result.data?.getStringExtra(EXTRA_UPDATED_POLICY_JSON)
            val parsed = updatedJson?.let { DevicePolicyBundle.fromJsonOrNull(it) }
            if (parsed != null) {
                currentPolicy = parsed
                setResult(
                    Activity.RESULT_OK,
                    Intent().putExtra(EXTRA_UPDATED_POLICY_JSON, parsed.toJson())
                )
            } else if (!isRemote) {
                currentPolicy = PolicyRepository.getInstance(applicationContext).getCurrentPolicy()
                setResult(
                    Activity.RESULT_OK,
                    Intent().putExtra(EXTRA_UPDATED_POLICY_JSON, currentPolicy.toJson())
                )
            }
            renderList()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        binding = ActivityFortressesListBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyWindowInsets()

        isRemote = intent.getBooleanExtra(EXTRA_IS_REMOTE, false)
        wardId = intent.getStringExtra(EXTRA_WARD_ID).orEmpty()
        val policyJson = intent.getStringExtra(EXTRA_POLICY_JSON)

        if (!isRemote && !hasLocalFortressPermission()) {
            Toast.makeText(this, R.string.self_control_section_locked_by_guardian, Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        if (isRemote) {
            val parsed = policyJson?.let { DevicePolicyBundle.fromJsonOrNull(it) }
            if (wardId.isBlank() || parsed == null) {
                Timber.tag(TAG).e("Refusing remote FortressesList launch: missing wardId or policyJson")
                Toast.makeText(applicationContext, R.string.toast_guardian_policy_save_failed, Toast.LENGTH_LONG).show()
                finish()
                return
            }
            currentPolicy = parsed
            wardActiveFortressId = intent.getStringExtra(EXTRA_WARD_ACTIVE_FORTRESS_ID)
                ?.takeIf { it.isNotBlank() }
                ?: parsed.wardActiveFortressId
            wardActiveFortressEndsAtWall = intent.getLongExtra(
                EXTRA_WARD_ACTIVE_FORTRESS_ENDS_AT_WALL,
                parsed.wardActiveFortressEndsAtWall
            )
        } else {
            currentPolicy = PolicyRepository.getInstance(applicationContext).getCurrentPolicy()
        }

        setupUi()
        observeLocalActiveSession()
        renderList()
    }

    override fun onResume() {
        super.onResume()
        if (!isRemote && !hasLocalFortressPermission()) {
            Toast.makeText(this, R.string.self_control_section_locked_by_guardian, Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        if (!isRemote) {
            currentPolicy = PolicyRepository.getInstance(applicationContext).getCurrentPolicy()
            renderList()
        } else {
            adapter.refreshActiveStates()
        }
    }

    override fun onDestroy() {
        deleteDialog?.dismiss()
        deleteDialog = null
        super.onDestroy()
    }

    private fun applyWindowInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.layoutRootFortressesList) { view, windowInsets ->
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

    private fun setupUi() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (!isMutating) {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
        binding.toolbarFortressesList.setNavigationOnClickListener {
            if (!isMutating) finish()
        }

        adapter = FortressesListAdapter(
            isActiveChecker = { profile -> isProfileCurrentlyActive(profile.id) },
            onEditClicked = { profile -> handleEditClicked(profile) },
            onDeleteClicked = { profile -> handleDeleteClicked(profile) }
        )
        binding.rvFortresses.layoutManager = LinearLayoutManager(this)
        binding.rvFortresses.adapter = adapter

        binding.btnAddFortress.setOnClickListener {
            if (isMutating) return@setOnClickListener
            launchEditor(fortressId = null)
        }
    }

    private fun observeLocalActiveSession() {
        if (isRemote) return
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    FortressSessionController.activeSessionFlow.collect {
                        adapter.refreshActiveStates()
                    }
                }
                launch {
                    PolicyRepository.getInstance(applicationContext).currentPolicyFlow.collect { fresh ->
                        currentPolicy = fresh
                        renderList()
                    }
                }
            }
        }
    }

    private fun renderList() {
        adapter.submitList(currentPolicy.fortressesConfig.getAllOrdered())
    }

    private fun isProfileCurrentlyActive(profileId: String): Boolean {
        return if (isRemote) {
            !wardActiveFortressId.isNullOrBlank() &&
                wardActiveFortressId == profileId &&
                wardActiveFortressEndsAtWall > TrustedTimeProvider.currentTimeMillis(applicationContext)
        } else {
            FortressSessionController.getActiveSession(this)?.fortressId == profileId
        }
    }

    private fun handleEditClicked(profile: FortressProfile) {
        if (isMutating) return
        if (isProfileCurrentlyActive(profile.id)) {
            Toast.makeText(this, R.string.fortress_active_edit_blocked_toast, Toast.LENGTH_LONG).show()
            return
        }
        launchEditor(fortressId = profile.id)
    }

    private fun handleDeleteClicked(profile: FortressProfile) {
        if (isMutating || profile.isEmergency) return
        if (isProfileCurrentlyActive(profile.id)) {
            Toast.makeText(this, R.string.fortress_active_edit_blocked_toast, Toast.LENGTH_LONG).show()
            return
        }
        if (isFinishing || isDestroyed || deleteDialog?.isShowing == true) return
        deleteDialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.fortress_delete_confirm_title)
            .setMessage(getString(R.string.fortress_delete_confirm_msg, profile.resolveDisplayName(this)))
            .setPositiveButton(R.string.fortress_btn_delete) { dialog, _ ->
                dialog.dismiss()
                performDelete(profile)
            }
            .setNegativeButton(R.string.dialog_btn_cancel, null)
            .setOnDismissListener { deleteDialog = null }
            .show()
    }

    private fun performDelete(profile: FortressProfile) {
        if (isMutating) return
        isMutating = true
        binding.btnAddFortress.isEnabled = false
        val app = applicationContext

        lifecycleScope.launch {
            try {
                val updatedPolicy = withContext(Dispatchers.IO + NonCancellable) {
                    val repo = PolicyRepository.getInstance(app)
                    if (isRemote) {
                        val accRepo = AccountabilityRepository.getInstance(app)
                        repo.mergeAndPushRemotePolicyPatch(
                            wardUid = wardId,
                            fallbackPolicy = currentPolicy,
                            guardianUidFallback = accRepo.getCachedUid(),
                            guardianNameFallback = accRepo.getMyDisplayName()
                        ) { latest ->
                            val now = TrustedTimeProvider.currentTimeMillis(app)
                            latest.copy(
                                fortressesConfig = latest.fortressesConfig.deleteCustom(profile.id),
                                version = latest.version + 1,
                                updatedAt = now
                            )
                        }
                    } else {
                        repo.mergeAndSaveSelfPolicyPatch { latest ->
                            latest.copy(
                                fortressesConfig = latest.fortressesConfig.deleteCustom(profile.id)
                            )
                        }
                    }
                }
                if (isFinishing || isDestroyed) return@launch
                isMutating = false
                binding.btnAddFortress.isEnabled = true

                if (updatedPolicy != null) {
                    currentPolicy = updatedPolicy
                    renderList()
                    setResult(
                        Activity.RESULT_OK,
                        Intent().putExtra(EXTRA_UPDATED_POLICY_JSON, updatedPolicy.toJson())
                    )
                    Toast.makeText(app, R.string.fortress_toast_deleted, Toast.LENGTH_SHORT).show()
                } else {
                    val failRes = if (isRemote) R.string.toast_guardian_policy_save_failed else R.string.toast_self_policy_save_failed
                    Toast.makeText(app, failRes, Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Timber.tag(TAG).e(e, "Failed deleting custom fortress %s", profile.id)
                if (isFinishing || isDestroyed) return@launch
                isMutating = false
                binding.btnAddFortress.isEnabled = true
                val failRes = if (isRemote) R.string.toast_guardian_policy_save_failed else R.string.toast_self_policy_save_failed
                Toast.makeText(app, failRes, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun launchEditor(fortressId: String?) {
        val intent = Intent(this, FortressEditorActivity::class.java).apply {
            putExtra(EXTRA_IS_REMOTE, isRemote)
            putExtra(EXTRA_WARD_ID, wardId)
            putExtra(EXTRA_POLICY_JSON, currentPolicy.toJson())
            putExtra(EXTRA_WARD_ACTIVE_FORTRESS_ID, wardActiveFortressId)
            putExtra(EXTRA_WARD_ACTIVE_FORTRESS_ENDS_AT_WALL, wardActiveFortressEndsAtWall)
            if (!fortressId.isNullOrBlank()) {
                putExtra(FortressEditorActivity.EXTRA_FORTRESS_ID, fortressId)
            }
        }
        editorLauncher.launch(intent)
    }

    companion object {
        private const val TAG = "FortressesList"
        const val EXTRA_IS_REMOTE = "extra_is_remote"
        const val EXTRA_WARD_ID = "extra_ward_id"
        const val EXTRA_POLICY_JSON = "extra_policy_json"
        const val EXTRA_WARD_ACTIVE_FORTRESS_ID = "extra_ward_active_fortress_id"
        const val EXTRA_WARD_ACTIVE_FORTRESS_ENDS_AT_WALL = "extra_ward_active_fortress_ends_at_wall"
        const val EXTRA_UPDATED_POLICY_JSON = "extra_updated_policy_json"
    }
}
