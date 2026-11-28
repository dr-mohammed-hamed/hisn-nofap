package com.drmohammed.antiporn.ui.home

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.drmohammed.antiporn.R
import com.drmohammed.antiporn.accessibility.HisnAccessibilityService
import com.drmohammed.antiporn.accessibility.control.AccessibilityPolicyCoordinator
import com.drmohammed.antiporn.accessibility.control.AccessibilitySelfActivator
import com.drmohammed.antiporn.accessibility.ui.AccessibilityOnboardingBottomSheet
import com.drmohammed.antiporn.banking.BankingCloakManager
import com.drmohammed.antiporn.core.engine.ProtectionEngine
import com.drmohammed.antiporn.core.engine.SecondaryProfileDetector
import com.drmohammed.antiporn.curfew.CurfewForegroundWatcher
import com.drmohammed.antiporn.curfew.CurfewVpnDnsBridge
import com.drmohammed.antiporn.data.repository.PolicyRepository
import com.drmohammed.antiporn.databinding.FragmentHomeBinding
import com.drmohammed.antiporn.notification.repository.NotificationRepository
import com.drmohammed.antiporn.provisioning.ui.ProvisioningWizardActivity
import com.drmohammed.antiporn.shields.DnsShield
import com.drmohammed.antiporn.ui.common.SecondaryProfileResolutionDialogFragment
import com.drmohammed.antiporn.ui.common.showSafely
import com.drmohammed.antiporn.ui.notification.NotificationCenterActivity
import com.drmohammed.antiporn.ui.shields.ActiveShieldsBottomSheet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.viewModel
import timber.log.Timber

/**
 * Modern Hero Dashboard Fragment presenting the radiant shield emblem,
 * Emergency Fortress & custom Fortresses controls, and system setup banners.
 */
class HomeFragment : Fragment() {

    interface HomeActionListener {
        fun onEnforceRequested()
    }

    private var actionListener: HomeActionListener? = null
    private var _binding: FragmentHomeBinding? = null
    private val binding: FragmentHomeBinding
        get() = _binding ?: throw IllegalStateException("HomeFragment binding accessed outside view lifecycle")

    private val homeViewModel: HomeViewModel by viewModel()
    private val notificationRepo: NotificationRepository by inject()

    private var fortressesViewBinder: HomeFortressesViewBinder? = null
    private var protectionRenderer: HomeProtectionRenderer? = null

    private var lastShieldClickTime = 0L
    private var lastEnforceClickTime = 0L
    private var lastNoticeClickTime = 0L
    private var lastBellClickTime = 0L
    private var lastProvisioningClickTime = 0L
    private var wasStopped = false

    private val vpnConsentLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val ctx = context ?: return@registerForActivityResult
        val appCtx = ctx.applicationContext
        if (result.resultCode == Activity.RESULT_OK) {
            homeViewModel.toggleProtection { success, errorMsg ->
                if (isAdded && _binding != null) {
                    val message = if (success) {
                        getString(R.string.toast_enforce_success, DnsShield.getEffectiveDnsHost(appCtx))
                    } else {
                        errorMsg ?: getString(R.string.status_need_enforce_desc)
                    }
                    Toast.makeText(appCtx, message, Toast.LENGTH_LONG).show()
                }
            }
        } else if (isAdded) {
            Toast.makeText(appCtx, R.string.toast_emergency_vpn_consent_denied, Toast.LENGTH_LONG).show()
        }
    }

    override fun onAttach(context: Context) {
        super.onAttach(context)
        if (context is HomeActionListener) actionListener = context
    }

    override fun onDetach() {
        super.onDetach()
        actionListener = null
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        protectionRenderer = HomeProtectionRenderer(
            lifecycleOwner = viewLifecycleOwner,
            isAddedProvider = { isAdded },
            contextProvider = { context },
            bindingProvider = { _binding }
        )
        fortressesViewBinder = HomeFortressesViewBinder(
            contextProvider = { context },
            bindingProvider = { _binding },
            onStartFortress = { profile, onDone -> homeViewModel.startFortress(profile, onDone) },
            onExtendFortress = { onDone -> homeViewModel.extendActiveFortress(onDone) }
        ).also { it.setupListeners() }

        setupFragmentResultListeners()
        setupListeners()
        observeViewModel()
    }

    /** dev's rotation-safe profile dialog result handling (replaces main's embedded dialog). */
    private fun setupFragmentResultListeners() {
        childFragmentManager.setFragmentResultListener(
            SecondaryProfileResolutionDialogFragment.REQUEST_KEY,
            viewLifecycleOwner
        ) { _, bundle ->
            val ctx = context ?: return@setFragmentResultListener
            when (bundle.getString(SecondaryProfileResolutionDialogFragment.RESULT_ACTION)) {
                SecondaryProfileResolutionDialogFragment.ACTION_OPEN_SETTINGS -> {
                    try {
                        startActivity(SecondaryProfileDetector.resolveCloneSettingsIntent(ctx))
                    } catch (e: Exception) {
                        Timber.tag(TAG).w(e, "Unable to open clone settings")
                        Toast.makeText(ctx.applicationContext, R.string.toast_open_settings_failed, Toast.LENGTH_SHORT).show()
                    }
                }
                SecondaryProfileResolutionDialogFragment.ACTION_CONFIRM_REMOVE -> {
                    val selectedProfile = SecondaryProfileResolutionDialogFragment.extractConfirmedProfile(bundle)
                        ?: return@setFragmentResultListener
                    val appCtx = ctx.applicationContext
                    homeViewModel.removeSecondaryProfile(selectedProfile) { removedTarget, allCleared ->
                        if (isAdded && _binding != null) {
                            val msgRes = when {
                                allCleared -> R.string.msg_cloned_app_removed_success
                                removedTarget -> R.string.msg_profile_removed_more_remaining
                                else -> R.string.msg_cloned_app_remove_failed
                            }
                            Toast.makeText(appCtx, msgRes, Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        val forceRefresh = wasStopped
        wasStopped = false
        homeViewModel.refreshProtectionState(isEnforcing = false, force = forceRefresh)
        homeViewModel.tickNap()
        updateSmartBanners()
    }

    override fun onStop() {
        wasStopped = true
        super.onStop()
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    homeViewModel.uiState.collect { state -> render(state) }
                }
                launch {
                    notificationRepo.unreadCountFlow.collect { count ->
                        if (count > 0) {
                            binding.tvNotificationBadge.visibility = View.VISIBLE
                            binding.tvNotificationBadge.text = if (count > 99) {
                                getString(R.string.badge_count_overflow)
                            } else {
                                count.toString()
                            }
                        } else {
                            binding.tvNotificationBadge.visibility = View.GONE
                        }
                    }
                }
            }
        }
    }

    private fun render(state: HomeUiState) {
        if (_binding == null) return
        protectionRenderer?.render(state.protectionState, state.isApplying)
        fortressesViewBinder?.render(state)
        updateSmartBanners()
    }

    private fun setupListeners() {
        binding.btnNotificationBell.setOnClickListener {
            val now = SystemClock.elapsedRealtime()
            if (now - lastBellClickTime < 500L) return@setOnClickListener
            lastBellClickTime = now
            val ctx = context ?: return@setOnClickListener
            if (!isAdded || activity?.isFinishing == true || activity?.isDestroyed == true) return@setOnClickListener
            try {
                startActivity(Intent(ctx, NotificationCenterActivity::class.java))
            } catch (e: Exception) {
                Timber.tag(TAG).w(e, "Unable to launch NotificationCenterActivity")
            }
        }

        binding.layoutHeroShield.setOnClickListener {
            val now = SystemClock.elapsedRealtime()
            if (now - lastShieldClickTime >= 600L) {
                lastShieldClickTime = now
                context?.let { ctx -> ActiveShieldsBottomSheet.show(ctx) }
            }
        }

        binding.btnOpenProvisioningWizard.setOnClickListener { launchProvisioningWizard() }
        binding.cardAdbHelp.setOnClickListener { launchProvisioningWizard() }

        binding.cardHomeNotice.setOnClickListener {
            val now = SystemClock.elapsedRealtime()
            if (now - lastNoticeClickTime < 1000L) return@setOnClickListener
            lastNoticeClickTime = now
            val appCtx = context?.applicationContext ?: return@setOnClickListener
            viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                if (!ProtectionEngine.isDeviceOwner(appCtx)) return@launch
                val secondaries = SecondaryProfileDetector.getSecondaryProfiles(appCtx)
                if (secondaries.isNotEmpty()) {
                    withContext(Dispatchers.Main) {
                        val uiCtx = context ?: return@withContext
                        if (isAdded && _binding != null && activity?.isFinishing != true && activity?.isDestroyed != true) {
                            showSelectiveProfileResolutionDialog(uiCtx, secondaries)
                        }
                    }
                }
            }
        }

        binding.btnEnforceNow.setOnClickListener { handleEnforceClick() }

        binding.btnGrantUsageAccess.setOnClickListener {
            val ctx = context ?: return@setOnClickListener
            try {
                startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
            } catch (e: Exception) {
                Timber.tag(TAG).w(e, "Unable to open Usage Access Settings")
                Toast.makeText(ctx.applicationContext, R.string.toast_open_settings_failed, Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnCompleteA11ySetup.setOnClickListener {
            val ctx = context ?: return@setOnClickListener
            if (!isAdded) return@setOnClickListener
            val policy = runCatching { PolicyRepository.getInstance(ctx).getCurrentPolicy() }.getOrNull()
            val reasons = policy?.let { AccessibilityPolicyCoordinator.getRequiredReasons(it) } ?: emptySet()
            val isSupervised = policy?.isSupervised == true
            val reason = AccessibilityOnboardingBottomSheet.reasonFromSet(reasons, isFromGuardian = isSupervised)
            AccessibilityOnboardingBottomSheet.showIfNotShowing(
                fragmentManager = parentFragmentManager,
                reason = reason,
                isFromGuardian = isSupervised
            )
        }
    }

    private fun handleEnforceClick() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastEnforceClickTime < 1000L) return
        lastEnforceClickTime = now

        val ctx = context ?: return
        if (!ProtectionEngine.isDeviceOwner(ctx)) {
            launchProvisioningWizard()
            return
        }

        val appCtx = ctx.applicationContext
        viewLifecycleOwner.lifecycleScope.launch {
            val needsManualConsent = withContext(Dispatchers.IO) {
                CurfewVpnDnsBridge.needsManualEmergencyVpnConsent(appCtx)
            }
            if (!isAdded || _binding == null) return@launch
            if (needsManualConsent) {
                val uiCtx = context ?: return@launch
                val shouldWaitForConsent = try {
                    val prepareIntent = VpnService.prepare(uiCtx)
                    if (prepareIntent != null) {
                        vpnConsentLauncher.launch(prepareIntent)
                        true
                    } else {
                        false
                    }
                } catch (e: Exception) {
                    Timber.tag(TAG).w(e, "Unable to prepare or launch VpnService consent intent")
                    Toast.makeText(appCtx, R.string.toast_emergency_vpn_consent_denied, Toast.LENGTH_LONG).show()
                    true
                }
                if (shouldWaitForConsent) return@launch
            }

            homeViewModel.toggleProtection { success, errorMsg ->
                if (isAdded && _binding != null) {
                    val message = if (success) {
                        getString(R.string.toast_enforce_success, DnsShield.getEffectiveDnsHost(appCtx))
                    } else {
                        errorMsg ?: getString(R.string.status_need_enforce_desc)
                    }
                    Toast.makeText(appCtx, message, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun launchProvisioningWizard() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastProvisioningClickTime < 800L) return
        lastProvisioningClickTime = now
        val ctx = context ?: return
        startActivity(Intent(ctx, ProvisioningWizardActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        })
    }

    private fun updateSmartBanners() {
        checkAccessibilityBanner()
        checkUsageAccess()
    }

    private fun checkAccessibilityBanner() {
        val ctx = context ?: return
        val b = _binding ?: return
        val policy = runCatching { PolicyRepository.getInstance(ctx).getCurrentPolicy() }.getOrNull() ?: run {
            b.cardA11yRequired.visibility = View.GONE
            return
        }
        val isA11yActive = HisnAccessibilityService.instance != null ||
            AccessibilitySelfActivator.isServiceEnabledInSettings(ctx)
        val isA11yRequired = AccessibilityPolicyCoordinator.isAccessibilityRequired(policy)
        val isCloaked = BankingCloakManager.isCloaked(ctx)

        if (isA11yRequired && !isA11yActive && !isCloaked) {
            b.tvA11yRequiredTitle.setText(
                if (policy.isSupervised) R.string.home_a11y_required_title_guardian else R.string.home_a11y_required_title
            )
            b.tvA11yRequiredDesc.setText(
                if (policy.isSupervised) R.string.home_a11y_required_desc_guardian else R.string.home_a11y_required_desc
            )
            b.cardA11yRequired.visibility = View.VISIBLE
        } else {
            b.cardA11yRequired.visibility = View.GONE
        }
    }

    private fun checkUsageAccess() {
        val ctx = context ?: return
        val b = _binding ?: return
        val policy = runCatching { PolicyRepository.getInstance(ctx).getCurrentPolicy() }.getOrNull()
        val isA11yActive = HisnAccessibilityService.instance != null ||
            AccessibilitySelfActivator.isServiceEnabledInSettings(ctx)
        val isUsageRequired = policy != null &&
            AccessibilityPolicyCoordinator.isUsageAccessRequired(policy, isA11yActive)
        val hasUsage = CurfewForegroundWatcher.hasUsageStatsPermission(ctx)
        b.btnGrantUsageAccess.visibility = if (isUsageRequired && !hasUsage) View.VISIBLE else View.GONE
    }

    private fun showSelectiveProfileResolutionDialog(
        ctx: Context,
        secondaries: List<SecondaryProfileDetector.UnaffiliatedProfileInfo>
    ) {
        if (secondaries.isEmpty()) return
        // dev's rotation-safe DialogFragment; results are handled by setupFragmentResultListeners().
        childFragmentManager.showSafely(SecondaryProfileResolutionDialogFragment.TAG_SELECT) {
            SecondaryProfileResolutionDialogFragment.newSelectionInstance(secondaries)
        }
    }

    override fun onDestroyView() {
        protectionRenderer?.cancel()
        protectionRenderer = null
        fortressesViewBinder?.dismiss()
        fortressesViewBinder = null
        super.onDestroyView()
        _binding = null
    }

    companion object {
        private const val TAG = "HomeFragment"
    }
}
