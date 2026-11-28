package com.drmohammed.antiporn.ui.accountability

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.drmohammed.antiporn.R
import com.drmohammed.antiporn.data.model.InstalledAppDto
import com.drmohammed.antiporn.data.repository.PolicyRepository
import com.drmohammed.antiporn.ui.control.gate.SelfControlAccessGateRegistry
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.launch
import org.koin.androidx.viewmodel.ext.android.viewModel

/**
 * Activity enabling a Guardian (or Ward in Local Mode) to inspect and manage all installed applications.
 * Refactored to Clean MVVM pattern: delegates state and business logic entirely to [RemoteAppManagerViewModel].
 */
class RemoteAppManagerActivity : AppCompatActivity() {

    private val viewModel: RemoteAppManagerViewModel by viewModel()

    private var isLocalMode = false
    private lateinit var wardId: String
    private lateinit var wardName: String

    private lateinit var toolbar: MaterialToolbar
    private lateinit var tvWardName: TextView
    private lateinit var etSearch: TextInputEditText
    private lateinit var switchLockNewInstalls: MaterialSwitch
    private lateinit var progressBar: ProgressBar
    private lateinit var tvEmptyState: TextView
    private lateinit var rvAppsList: RecyclerView
    private lateinit var btnSaveAppManagerChanges: MaterialButton

    private lateinit var adapter: RemoteAppsAdapter
    private var pendingChangedPosition: Int? = null
    private var activeQuickUnsuspendDialog: androidx.appcompat.app.AlertDialog? = null

    private val backPressedCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            if (viewModel.uiState.value.hasUnsavedChanges) {
                showDiscardConfirmationDialog()
            } else {
                finish()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_remote_app_manager)

        applyWindowInsets()

        isLocalMode = intent.getBooleanExtra(EXTRA_IS_LOCAL, false)
        wardId = intent.getStringExtra(EXTRA_WARD_ID) ?: ""
        wardName = intent.getStringExtra(EXTRA_WARD_NAME) ?: getString(R.string.status_ward_badge)

        if (isLocalMode && (!SelfControlAccessGateRegistry.getActiveGate().canAccess(this) ||
                !SelfControlAccessGateRegistry.isSectionPermitted(this, com.drmohammed.antiporn.ui.control.gate.SelfControlSection.APP_MANAGER))) {
            Toast.makeText(this, R.string.self_control_section_locked_by_guardian, Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        if (!isLocalMode && wardId.isBlank()) {
            finish()
            return
        }

        onBackPressedDispatcher.addCallback(this, backPressedCallback)

        initViews()
        setupListeners()
        observeViewModel()

        viewModel.initialize(isLocalMode, wardId, wardName)
    }

    private fun applyWindowInsets() {
        findViewById<View>(R.id.layoutRootAppManager)?.let { rootView ->
            val innerBottomBar = findViewById<View>(R.id.layoutBottomBarAppManagerInner)
            val baseBottomPadding = innerBottomBar?.paddingBottom ?: 0
            val baseLeftPadding = innerBottomBar?.paddingLeft ?: 0
            val baseRightPadding = innerBottomBar?.paddingRight ?: 0
            val baseTopPadding = innerBottomBar?.paddingTop ?: 0

            ViewCompat.setOnApplyWindowInsetsListener(rootView) { view, windowInsets ->
                val insets = windowInsets.getInsets(
                    WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout() or WindowInsetsCompat.Type.ime()
                )
                view.setPadding(insets.left, insets.top, insets.right, 0)
                innerBottomBar?.setPadding(
                    baseLeftPadding,
                    baseTopPadding,
                    baseRightPadding,
                    baseBottomPadding + insets.bottom
                )
                windowInsets
            }
        }
    }

    private fun initViews() {
        toolbar = findViewById(R.id.toolbarAppManager)
        toolbar.setNavigationOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }

        if (isLocalMode) {
            toolbar.inflateMenu(R.menu.menu_remote_app_manager)
            toolbar.setOnMenuItemClickListener { menuItem ->
                if (menuItem.itemId == R.id.action_repair_stuck_apps) {
                    viewModel.executeRepairStuckApps()
                    true
                } else {
                    false
                }
            }
        }

        tvWardName = findViewById(R.id.tvWardName)
        tvWardName.text = if (isLocalMode) {
            getString(R.string.self_control_local_app_manager_title)
        } else {
            getString(R.string.guardian_control_ward_name, wardName)
        }

        etSearch = findViewById(R.id.etSearch)
        switchLockNewInstalls = findViewById(R.id.switchLockNewInstalls)
        progressBar = findViewById(R.id.progressBar)
        tvEmptyState = findViewById(R.id.tvEmptyState)
        rvAppsList = findViewById(R.id.rvAppsList)
        btnSaveAppManagerChanges = findViewById(R.id.btnSaveAppManagerChanges)

        rvAppsList.layoutManager = LinearLayoutManager(this)
        adapter = RemoteAppsAdapter(
            isLocalMode = isLocalMode,
            onFreezeToggled = { pkg, pos, isFrozen ->
                pendingChangedPosition = pos
                viewModel.setFreeze247(pkg, isFrozen)
            },
            onCurfewWhitelistToggled = { pkg, pos, isAllowed ->
                pendingChangedPosition = pos
                viewModel.setCurfewWhitelist(pkg, isAllowed)
            },
            onCurfewVpnBypassToggled = { pkg, pos, isBypassed ->
                pendingChangedPosition = pos
                viewModel.setCurfewVpnBypass(pkg, isBypassed)
            },
            onProtectToggled = { pkg, pos, isProtected ->
                pendingChangedPosition = pos
                viewModel.setProtectedApp(pkg, isProtected)
            },
            onAiScanToggled = { pkg, pos, isTarget ->
                pendingChangedPosition = pos
                viewModel.setAiScanTarget(pkg, isTarget)
            },
            onQuickUnsuspendClicked = { app -> confirmAndExecuteQuickUnsuspend(app) }
        )
        rvAppsList.adapter = adapter
    }

    private fun setupListeners() {
        etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                viewModel.filter(s?.toString().orEmpty())
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        switchLockNewInstalls.setOnCheckedChangeListener { _, isChecked ->
            onLockNewInstallsSwitched(isChecked)
        }
        tvEmptyState.setOnClickListener { viewModel.loadData() }
        btnSaveAppManagerChanges.setOnClickListener { viewModel.saveChanges() }
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.uiState.collect { state ->
                        renderUiState(state)
                    }
                }
                launch {
                    viewModel.events.collect { event ->
                        handleEvent(event)
                    }
                }
                launch {
                    PolicyRepository.getInstance(applicationContext).currentPolicyFlow.collect {
                        if (isFinishing || isDestroyed) return@collect
                        if (isLocalMode && (!SelfControlAccessGateRegistry.getActiveGate().canAccess(this@RemoteAppManagerActivity) ||
                                !SelfControlAccessGateRegistry.isSectionPermitted(
                                    this@RemoteAppManagerActivity,
                                    com.drmohammed.antiporn.ui.control.gate.SelfControlSection.APP_MANAGER
                                ))
                        ) {
                            Toast.makeText(this@RemoteAppManagerActivity, R.string.gate_access_denied_by_guardian, Toast.LENGTH_SHORT).show()
                            finish()
                        }
                    }
                }
            }
        }
    }

    private fun renderUiState(state: RemoteAppManagerUiState) {
        progressBar.visibility = if (state.isLoading || state.isSaving || state.isRepairing) View.VISIBLE else View.GONE
        tvEmptyState.visibility = if (state.isEmptyState) View.VISIBLE else View.GONE
        rvAppsList.visibility = if (state.isEmptyState) View.GONE else View.VISIBLE

        val dirty = state.hasUnsavedChanges
        btnSaveAppManagerChanges.isEnabled = state.isSaveEnabled
        btnSaveAppManagerChanges.alpha = if (state.isSaveEnabled) 1.0f else 0.5f
        backPressedCallback.isEnabled = dirty && (state.isLocalMode || !state.isPolicyLoadFailed)

        switchLockNewInstalls.isEnabled = state.isLocalMode || !state.isPolicyLoadFailed
        switchLockNewInstalls.setOnCheckedChangeListener(null)
        switchLockNewInstalls.isChecked = state.lockNewInstallsUntilApproved
        switchLockNewInstalls.setOnCheckedChangeListener { _, isChecked ->
            onLockNewInstallsSwitched(isChecked)
        }

        val pos = pendingChangedPosition
        pendingChangedPosition = null
        adapter.submitData(
            apps = state.displayedApps,
            customBlacklist = state.customBlacklist,
            customCurfewWhitelist = state.customCurfewWhitelist,
            customAiScanPackages = state.customAiScanPackages,
            excludedAiScanPackages = state.excludedAiScanPackages,
            curfewVpnBypass = state.curfewVpnBypass,
            protectedApps = state.protectedApps,
            initialCurfewWhitelist = state.initialCurfewWhitelist,
            initialCurfewVpnBypass = state.initialCurfewVpnBypass,
            isScreenAiEnabledGlobally = state.isScreenAiEnabledGlobally,
            changedPosition = pos
        )
    }

    private fun handleEvent(event: RemoteAppManagerEvent) {
        when (event) {
            is RemoteAppManagerEvent.ShowToastRes -> {
                val length = if (event.isLong) Toast.LENGTH_LONG else Toast.LENGTH_SHORT
                Toast.makeText(this, event.resId, length).show()
            }
            is RemoteAppManagerEvent.ShowToastString -> {
                val length = if (event.isLong) Toast.LENGTH_LONG else Toast.LENGTH_SHORT
                Toast.makeText(this, event.message, length).show()
            }
            is RemoteAppManagerEvent.SaveSuccess -> {
                backPressedCallback.isEnabled = false
                setResult(RESULT_OK)
                finish()
            }
        }
    }

    private fun onLockNewInstallsSwitched(isChecked: Boolean) {
        if (isChecked) {
            showFreezeNewInstallsConfirmationDialog()
        } else {
            viewModel.setLockNewInstalls(false)
        }
    }

    private fun showFreezeNewInstallsConfirmationDialog() {
        if (isFinishing || isDestroyed) return
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.dialog_freeze_new_installs_title)
            .setMessage(R.string.dialog_freeze_new_installs_msg)
            .setCancelable(false)
            .setNegativeButton(android.R.string.cancel) { dialog, _ ->
                dialog.dismiss()
                switchLockNewInstalls.setOnCheckedChangeListener(null)
                switchLockNewInstalls.isChecked = false
                switchLockNewInstalls.setOnCheckedChangeListener { _, isChecked ->
                    onLockNewInstallsSwitched(isChecked)
                }
                viewModel.setLockNewInstalls(false)
            }
            .setPositiveButton(R.string.btn_confirm_freeze_new_installs) { dialog, _ ->
                dialog.dismiss()
                viewModel.setLockNewInstalls(true)
            }
            .show()
    }

    private fun showDiscardConfirmationDialog() {
        if (isFinishing || isDestroyed) return
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.dialog_unsaved_changes_title)
            .setMessage(R.string.dialog_unsaved_changes_msg)
            .setNegativeButton(R.string.btn_keep_editing) { dialog, _ ->
                dialog.dismiss()
            }
            .setPositiveButton(R.string.btn_discard_changes) { dialog, _ ->
                dialog.dismiss()
                backPressedCallback.isEnabled = false
                finish()
            }
            .show()
    }

    private fun confirmAndExecuteQuickUnsuspend(app: InstalledAppDto) {
        if (isFinishing || isDestroyed) return
        if (activeQuickUnsuspendDialog?.isShowing == true) return
        val pkg = app.packageName
        val displayName = app.appName.ifBlank { pkg }

        val title = getString(R.string.dialog_quick_unsuspend_title)
        val message = if (isLocalMode) {
            getString(R.string.dialog_quick_unsuspend_msg_local, displayName)
        } else {
            getString(R.string.dialog_quick_unsuspend_msg_remote, displayName, wardName)
        }

        activeQuickUnsuspendDialog = MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setMessage(message)
            .setNegativeButton(android.R.string.cancel) { dialog, _ -> dialog.dismiss() }
            .setPositiveButton(R.string.btn_unsuspend_action) { dialog, _ ->
                dialog.dismiss()
                viewModel.executeQuickUnsuspend(app)
            }
            .setOnDismissListener { activeQuickUnsuspendDialog = null }
            .show()
    }

    override fun onResume() {
        super.onResume()
        if (isFinishing || isDestroyed) return
        if (isLocalMode && (!SelfControlAccessGateRegistry.getActiveGate().canAccess(this) ||
                !SelfControlAccessGateRegistry.isSectionPermitted(this, com.drmohammed.antiporn.ui.control.gate.SelfControlSection.APP_MANAGER))) {
            Toast.makeText(this, R.string.self_control_section_locked_by_guardian, Toast.LENGTH_SHORT).show()
            finish()
            return
        }
    }

    companion object {
        const val EXTRA_WARD_ID = "extra_ward_id"
        const val EXTRA_WARD_NAME = "extra_ward_name"
        const val EXTRA_IS_LOCAL = "extra_is_local"
    }
}
