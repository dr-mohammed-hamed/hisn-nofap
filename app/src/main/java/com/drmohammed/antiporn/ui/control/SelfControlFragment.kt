package com.drmohammed.antiporn.ui.control

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.drmohammed.antiporn.MainActivity
import com.drmohammed.antiporn.R
import com.drmohammed.antiporn.data.repository.ApprovalRepository
import com.drmohammed.antiporn.databinding.FragmentSelfControlBinding
import com.drmohammed.antiporn.fortress.ui.FortressesListActivity
import com.drmohammed.antiporn.pageshield.ui.PageShieldSettingsActivity
import com.drmohammed.antiporn.ui.accountability.RemoteAppManagerActivity
import com.drmohammed.antiporn.ui.control.curfew.CurfewSettingsActivity
import com.drmohammed.antiporn.ui.control.gate.SelfControlAccessGateRegistry
import com.drmohammed.antiporn.ui.control.gate.SelfControlSection
import com.drmohammed.antiporn.ui.control.other.OtherSettingsActivity
import com.drmohammed.antiporn.ui.control.qr.QrExportDialog
import com.drmohammed.antiporn.ui.control.qr.QrScannerBottomSheet
import com.drmohammed.antiporn.ui.control.qr.SelfControlQrManager
import com.drmohammed.antiporn.ui.control.web.WebFilterSettingsActivity
import com.drmohammed.antiporn.waqfa.ui.settings.WaqfaSettingsActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.androidx.viewmodel.ext.android.viewModel

/**
 * Self-Control Hub Fragment providing QR-gated access to protection sub-screens
 * and active Fortress / Night Curfew unlock controls.
 */
class SelfControlFragment : Fragment() {

    private var _binding: FragmentSelfControlBinding? = null
    private val binding: FragmentSelfControlBinding
        get() = _binding ?: throw IllegalStateException("SelfControlFragment binding accessed outside view lifecycle")

    private val selfControlViewModel: SelfControlViewModel by viewModel()
    private var approvalsViewBinder: SelfControlApprovalsViewBinder? = null
    private var activeModesViewBinder: SelfControlActiveModesViewBinder? = null
    private var isQrPolicyLoading = false
    private var lastLockState: Boolean? = null
    private var lastRenderedApprovalsCount: Int? = null
    private var lastTileClickTime = 0L

    private val subScreenLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            selfControlViewModel.checkGateAndRefresh()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = FragmentSelfControlBinding.inflate(inflater, container, false).also { _binding = it }.root

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        initViews()
        registerPersistentQrResultListeners()
        setupListeners()
        observeViewModel()
    }

    override fun onResume() {
        super.onResume()
        if (!isHidden) selfControlViewModel.checkGateAndRefresh()
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden) selfControlViewModel.checkGateAndRefresh()
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    selfControlViewModel.uiState.collect { state -> render(state) }
                }
                launch {
                    while (isActive) {
                        val delayMs = if (selfControlViewModel.uiState.value.activeModesSnapshot.activeFortress != null) {
                            1_000L
                        } else {
                            5_000L
                        }
                        delay(delayMs)
                        if (!isHidden) {
                            selfControlViewModel.refreshActiveModesSnapshot()
                        }
                    }
                }
            }
        }
    }

    private fun render(state: SelfControlUiState) {
        if (_binding == null) return

        updateLockState(state.isLocked)
        if (state.isLocked) return

        val policy = state.policy
        val isSupervised = policy.isSupervised || !policy.guardianUid.isNullOrBlank()

        activeModesViewBinder?.render(state)

        if (state.pendingApprovalsCount == 0 || (isSupervised && !policy.selfControlNewAppsAllowed)) {
            lastRenderedApprovalsCount = 0
            binding.cardPendingApps.visibility = View.GONE
            binding.tvNoPendingApps.visibility = View.GONE
        } else {
            binding.cardPendingApps.visibility = View.VISIBLE
            if (lastRenderedApprovalsCount != state.pendingApprovalsCount) {
                lastRenderedApprovalsCount = state.pendingApprovalsCount
                loadPendingApprovals()
            }
        }

        binding.tileFortresses.alpha = if (isSupervised && !policy.selfControlFortressesAllowed) 0.45f else 1.0f
        binding.tileCurfew.alpha = if (isSupervised && !policy.selfControlCurfewAllowed) 0.45f else 1.0f
        binding.tileWebFilter.alpha = if (isSupervised && !policy.selfControlWebAllowed) 0.45f else 1.0f
        binding.tileAppManager.alpha = if (isSupervised && !policy.selfControlAppsAllowed) 0.45f else 1.0f
        binding.tileWaqfa.alpha = if (isSupervised && !policy.selfControlWaqfaAllowed) 0.45f else 1.0f
        binding.tilePageShield.alpha = if (isSupervised && !policy.selfControlPageShieldAllowed) 0.45f else 1.0f

        val ctx = context?.applicationContext ?: return
        val isQrEnabled = SelfControlQrManager.getInstance(ctx).isQrLockEnabled()
        syncQrSwitchUi(isQrEnabled)
    }

    private fun syncQrSwitchUi(isQrEnabled: Boolean) {
        isQrPolicyLoading = true
        _binding?.switchQrLockEnabled?.isChecked = isQrEnabled
        _binding?.layoutQrActionButtons?.isVisible = isQrEnabled
        _binding?.btnShowQrCode?.isVisible = false
        _binding?.btnRegenerateQrCode?.isVisible = isQrEnabled
        isQrPolicyLoading = false
    }

    private fun updateLockState(isLocked: Boolean) {
        if (lastLockState == isLocked) return
        lastLockState = isLocked
        _binding?.let { b ->
            b.layoutSelfControlLocked.isVisible = isLocked
            b.scrollSelfControl.isVisible = !isLocked
        }
    }

    private fun initViews() {
        binding.toolbarSelfControl.navigationIcon = null
        binding.btnGoToAccountability.setOnClickListener {
            (activity as? MainActivity)?.navigateToTab(R.id.nav_accountability)
        }

        (activity as? AppCompatActivity)?.let { act ->
            approvalsViewBinder = SelfControlApprovalsViewBinder(
                activity = act,
                layoutPendingApps = binding.layoutPendingApps,
                tvNoPendingApps = binding.tvNoPendingApps,
                cardPendingApps = binding.cardPendingApps,
                onReloadRequested = { loadPendingApprovals() },
                lifecycleOwner = viewLifecycleOwner
            )
        }

        activeModesViewBinder = SelfControlActiveModesViewBinder(
            contextProvider = { context },
            bindingProvider = { _binding },
            fragmentManager = childFragmentManager,
            lifecycleOwner = viewLifecycleOwner,
            viewModel = selfControlViewModel,
            onEmergencyQrBypassTriggered = { syncQrSwitchUi(false) }
        ).also { it.registerListeners() }
    }

    private fun setupListeners() {
        binding.switchQrLockEnabled.setOnCheckedChangeListener { buttonView, isChecked ->
            if (isQrPolicyLoading || !buttonView.isPressed) return@setOnCheckedChangeListener
            val ctx = context?.applicationContext ?: return@setOnCheckedChangeListener
            val qrManager = SelfControlQrManager.getInstance(ctx)

            if (isChecked) {
                if (qrManager.isQrLockEnabled()) {
                    syncQrSwitchUi(true)
                    return@setOnCheckedChangeListener
                }
                syncQrSwitchUi(false)
                if (!qrManager.hasToken()) qrManager.generateAndSaveNewToken()
                if (!childFragmentManager.isStateSaved && childFragmentManager.findFragmentByTag(QrExportDialog.TAG) == null) {
                    QrExportDialog.newInstance().show(childFragmentManager, QrExportDialog.TAG)
                }
            } else if (qrManager.isQrLockEnabled()) {
                syncQrSwitchUi(true)
                showQrScannerWithFallback(TAG_DISABLE_QR_BARRIER)
            } else {
                _binding?.layoutQrActionButtons?.isVisible = false
            }
        }

        binding.btnRegenerateQrCode.setOnClickListener {
            val ctx = context?.applicationContext ?: return@setOnClickListener
            if (SelfControlQrManager.getInstance(ctx).isQrLockEnabled()) {
                showQrScannerWithFallback(TAG_REGENERATE_QR_AUTH)
            } else {
                performAuthorizedQrAction(TAG_REGENERATE_QR_AUTH)
            }
        }

        binding.tileFortresses.setOnClickListener { executeWithQrProtection(TAG_NAV_FORTRESSES) }
        binding.tileCurfew.setOnClickListener { executeWithQrProtection(TAG_NAV_CURFEW) }
        binding.tileWebFilter.setOnClickListener { executeWithQrProtection(TAG_NAV_WEB) }
        binding.tileAppManager.setOnClickListener { executeWithQrProtection(TAG_NAV_APPS) }
        binding.tileWaqfa.setOnClickListener { executeWithQrProtection(TAG_NAV_WAQFA) }
        binding.tilePageShield.setOnClickListener { executeWithQrProtection(TAG_NAV_PAGE_SHIELD) }
        binding.tileOtherSettings.setOnClickListener { executeWithQrProtection(TAG_NAV_OTHER) }
    }

    private fun registerPersistentQrResultListeners() {
        childFragmentManager.setFragmentResultListener(
            QrExportDialog.REQUEST_KEY_SETUP_COMPLETED,
            viewLifecycleOwner
        ) { _, bundle ->
            val enabled = bundle.getBoolean(QrExportDialog.RESULT_KEY_ENABLED, false)
            val ctx = context?.applicationContext ?: return@setFragmentResultListener
            syncQrSwitchUi(SelfControlQrManager.getInstance(ctx).isQrLockEnabled() && enabled)
        }

        val allTags = listOf(
            TAG_DISABLE_QR_BARRIER, TAG_SHOW_QR_CODE, TAG_REGENERATE_QR_AUTH,
            TAG_NAV_FORTRESSES, TAG_NAV_CURFEW, TAG_NAV_WEB,
            TAG_NAV_APPS, TAG_NAV_WAQFA, TAG_NAV_PAGE_SHIELD, TAG_NAV_OTHER
        )
        for (tag in allTags) {
            childFragmentManager.setFragmentResultListener(tag, viewLifecycleOwner) { _, bundle ->
                val verified = bundle.getBoolean(QrScannerBottomSheet.RESULT_KEY_VERIFIED, false)
                val bypass = bundle.getBoolean(QrScannerBottomSheet.RESULT_KEY_EMERGENCY_BYPASS, false)
                if (bypass) syncQrSwitchUi(false)
                if (verified || bypass) performAuthorizedQrAction(tag)
            }
        }
    }

    private fun executeWithQrProtection(tag: String) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastTileClickTime < 600L) return
        lastTileClickTime = now

        val policy = selfControlViewModel.uiState.value.policy
        val section = when (tag) {
            TAG_NAV_FORTRESSES -> SelfControlSection.FORTRESSES
            TAG_NAV_CURFEW -> SelfControlSection.CURFEW
            TAG_NAV_WEB -> SelfControlSection.WEB_FILTER
            TAG_NAV_APPS -> SelfControlSection.APP_MANAGER
            TAG_NAV_WAQFA -> SelfControlSection.WAQFA
            TAG_NAV_PAGE_SHIELD -> SelfControlSection.PAGE_SHIELD
            else -> SelfControlSection.GENERAL
        }
        if (!SelfControlAccessGateRegistry.isSectionPermitted(policy, section)) {
            Toast.makeText(requireContext(), R.string.self_control_section_locked_by_guardian, Toast.LENGTH_SHORT).show()
            return
        }

        val ctx = context?.applicationContext ?: return
        if (SelfControlQrManager.getInstance(ctx).isQrLockEnabled()) {
            showQrScannerWithFallback(tag)
        } else {
            performAuthorizedQrAction(tag)
        }
    }

    private fun showQrScannerWithFallback(tag: String) {
        if (childFragmentManager.isStateSaved || childFragmentManager.findFragmentByTag(tag) != null) return
        QrScannerBottomSheet.newInstance(tag).show(childFragmentManager, tag)
    }

    private fun performAuthorizedQrAction(tag: String) {
        val ctx = context?.applicationContext ?: return
        when (tag) {
            TAG_DISABLE_QR_BARRIER -> {
                SelfControlQrManager.getInstance(ctx).setQrLockEnabled(false)
                syncQrSwitchUi(false)
                selfControlViewModel.refreshActiveModesSnapshot()
                Toast.makeText(context ?: ctx, R.string.self_control_qr_disabled_toast, Toast.LENGTH_SHORT).show()
            }
            TAG_SHOW_QR_CODE -> {
                if (!childFragmentManager.isStateSaved && childFragmentManager.findFragmentByTag(QrExportDialog.TAG) == null) {
                    QrExportDialog.newInstance().show(childFragmentManager, QrExportDialog.TAG)
                }
            }
            TAG_REGENERATE_QR_AUTH -> {
                val act = activity ?: return
                val qrManager = SelfControlQrManager.getInstance(ctx)
                MaterialAlertDialogBuilder(act)
                    .setTitle(R.string.self_control_qr_regenerate_confirm_title)
                    .setMessage(R.string.self_control_qr_regenerate_confirm_message)
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(R.string.self_control_qr_btn_regenerate_confirm) { _, _ ->
                        qrManager.generateAndSaveNewToken()
                        qrManager.setQrLockEnabled(false)
                        syncQrSwitchUi(false)
                        if (!childFragmentManager.isStateSaved && childFragmentManager.findFragmentByTag(QrExportDialog.TAG) == null) {
                            QrExportDialog.newInstance().show(childFragmentManager, QrExportDialog.TAG)
                        }
                    }
                    .show()
            }
            TAG_NAV_FORTRESSES -> launchSubScreen(FortressesListActivity::class.java)
            TAG_NAV_CURFEW -> launchSubScreen(CurfewSettingsActivity::class.java)
            TAG_NAV_WEB -> launchSubScreen(WebFilterSettingsActivity::class.java)
            TAG_NAV_APPS -> {
                val intent = Intent(requireContext(), RemoteAppManagerActivity::class.java).apply {
                    putExtra(RemoteAppManagerActivity.EXTRA_IS_LOCAL, true)
                }
                subScreenLauncher.launch(intent)
            }
            TAG_NAV_WAQFA -> launchSubScreen(WaqfaSettingsActivity::class.java)
            TAG_NAV_PAGE_SHIELD -> launchSubScreen(PageShieldSettingsActivity::class.java)
            TAG_NAV_OTHER -> launchSubScreen(OtherSettingsActivity::class.java)
        }
    }

    private fun launchSubScreen(targetClass: Class<*>) {
        val intent = Intent(requireContext(), targetClass).apply {
            putExtra(CurfewSettingsActivity.EXTRA_IS_REMOTE, false)
            putExtra(CurfewSettingsActivity.EXTRA_POLICY_JSON, selfControlViewModel.uiState.value.policy.toJson())
        }
        subScreenLauncher.launch(intent)
    }

    private fun loadPendingApprovals() {
        val ctx = context?.applicationContext ?: return
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val pendingList = ApprovalRepository.getInstance(ctx).getLocalPendingApprovals()
                .filter { !it.isApproved && !it.isRejected }
            val pm = ctx.packageManager
            val items = pendingList.map { entity ->
                val appName = runCatching {
                    pm.getApplicationLabel(pm.getApplicationInfo(entity.packageName, 0)).toString()
                }.getOrDefault(entity.packageName)
                entity.packageName to appName
            }
            withContext(Dispatchers.Main) {
                if (!isAdded || _binding == null) return@withContext
                approvalsViewBinder?.renderPendingApprovals(items)
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        activeModesViewBinder?.dismiss()
        activeModesViewBinder = null
        approvalsViewBinder = null
        _binding = null
    }

    companion object {
        private const val TAG_DISABLE_QR_BARRIER = "qr_scanner_disable_barrier"
        private const val TAG_SHOW_QR_CODE = "qr_scanner_show_code"
        private const val TAG_REGENERATE_QR_AUTH = "qr_scanner_regenerate_auth"
        private const val TAG_NAV_FORTRESSES = "qr_nav_fortresses"
        private const val TAG_NAV_CURFEW = "qr_nav_curfew"
        private const val TAG_NAV_WEB = "qr_nav_web"
        private const val TAG_NAV_APPS = "qr_nav_apps"
        private const val TAG_NAV_WAQFA = "qr_nav_waqfa"
        private const val TAG_NAV_PAGE_SHIELD = "qr_nav_page_shield"
        private const val TAG_NAV_OTHER = "qr_nav_other"
    }
}
