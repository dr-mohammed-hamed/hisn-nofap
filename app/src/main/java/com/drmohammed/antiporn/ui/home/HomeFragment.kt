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
import androidx.core.content.ContextCompat
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
import com.drmohammed.antiporn.core.model.ShieldStatus
import com.drmohammed.antiporn.curfew.CurfewForegroundWatcher
import com.drmohammed.antiporn.curfew.CurfewVpnDnsBridge
import com.drmohammed.antiporn.curfew.NapController
import com.drmohammed.antiporn.curfew.UnaffiliatedDnsNoticeState
import com.drmohammed.antiporn.data.repository.PolicyRepository
import com.drmohammed.antiporn.databinding.FragmentHomeBinding
import com.drmohammed.antiporn.provisioning.ui.ProvisioningWizardActivity
import com.drmohammed.antiporn.shields.DnsShield
import com.drmohammed.antiporn.ui.ProtectionUiState
import com.drmohammed.antiporn.ui.common.ConfirmActionDialogFragment
import com.drmohammed.antiporn.ui.common.SecondaryProfileResolutionDialogFragment
import com.drmohammed.antiporn.ui.common.showSafely
import com.drmohammed.antiporn.ui.shields.ActiveShieldsBottomSheet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.androidx.viewmodel.ext.android.viewModel
import org.koin.android.ext.android.inject
import com.drmohammed.antiporn.notification.repository.NotificationRepository
import com.drmohammed.antiporn.ui.notification.NotificationCenterActivity
import timber.log.Timber

/**
 * Modern Hero Dashboard Fragment.
 * Presents the Al-Hosn radiant shield emblem with interactive bottom sheet access,
 * and the centerpiece 1-Hour Nap mode with live countdown synchronization.
 */
class HomeFragment : Fragment() {

    interface HomeActionListener {
        fun onEnforceRequested()
    }

    private var actionListener: HomeActionListener? = null

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding ?: throw IllegalStateException("Binding is only valid between onCreateView and onDestroyView")

    private val homeViewModel: HomeViewModel by viewModel()
    private val notificationRepo: NotificationRepository by inject()

    private var lastShieldClickTime = 0L
    private var lastNapTapTime = 0L
    private var lastEnforceClickTime = 0L
    private var lastNoticeClickTime = 0L
    private var lastBellClickTime = 0L
    private var lastProvisioningClickTime = 0L
    private var wasStopped = false
    private var dnsStatusNoticeJob: Job? = null

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
        if (context is HomeActionListener) {
            actionListener = context
        }
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
        setupFragmentResultListeners()
        setupListeners()
        observeViewModel()
    }

    private fun setupFragmentResultListeners() {
        childFragmentManager.setFragmentResultListener(REQ_NAP_CONFIRM, viewLifecycleOwner) { _, bundle ->
            if (bundle.getString(ConfirmActionDialogFragment.RESULT_BUTTON) == ConfirmActionDialogFragment.BUTTON_POSITIVE) {
                val appCtx = context?.applicationContext ?: return@setFragmentResultListener
                _binding?.btnHeroNapStart?.isEnabled = false
                _binding?.btnHeroNapExtend?.isEnabled = false
                homeViewModel.startNap { success ->
                    if (isAdded && _binding != null) {
                        binding.btnHeroNapExtend.isEnabled = true
                        if (success) {
                            Toast.makeText(appCtx, R.string.toast_nap_started, Toast.LENGTH_SHORT).show()
                            updateNapDisplay()
                        } else {
                            if (!NapController.isNapActive(appCtx) && !HomeViewModel.isEnforcedCurfewActive(appCtx)) {
                                binding.btnHeroNapStart.isEnabled = true
                            }
                            val errorRes = when {
                                HomeViewModel.isEnforcedCurfewActive(appCtx) -> R.string.card_curfew_night_locked_hint
                                !ProtectionEngine.isDeviceOwner(appCtx) -> R.string.status_no_device_owner_title
                                else -> R.string.status_need_enforce_desc
                            }
                            Toast.makeText(appCtx, errorRes, Toast.LENGTH_SHORT).show()
                            updateNapDisplay()
                        }
                    }
                }
            }
        }

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
                        Timber.tag("HomeFragment").w(e, "Unable to open clone settings")
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
                    homeViewModel.uiState.collect { state ->
                        render(state)
                    }
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

        updateProtectionState(state.protectionState, state.isApplying)
        updateNapCountdown(state.napRemainingMs, state.isNapActive, state.isCurfewActive)
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
                Timber.tag("HomeFragment").w(e, "Unable to launch NotificationCenterActivity")
            }
        }

        binding.layoutHeroShield.setOnClickListener {
            val now = SystemClock.elapsedRealtime()
            if (now - lastShieldClickTime >= 600L) {
                lastShieldClickTime = now
                ActiveShieldsBottomSheet.show(parentFragmentManager)
            }
        }

        binding.btnHeroNapStart.setOnClickListener {
            val now = SystemClock.elapsedRealtime()
            if (now - lastNapTapTime < 800L) return@setOnClickListener
            lastNapTapTime = now

            val ctx = context ?: return@setOnClickListener
            if (!isAdded || activity?.isFinishing == true || activity?.isDestroyed == true) return@setOnClickListener
            if (HomeViewModel.isEnforcedCurfewActive(ctx)) {
                Toast.makeText(ctx.applicationContext, R.string.card_curfew_night_locked_hint, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            childFragmentManager.showSafely(TAG_NAP_CONFIRM) {
                ConfirmActionDialogFragment.newInstance(
                    requestKey = REQ_NAP_CONFIRM,
                    titleRes = R.string.dialog_nap_confirm_title,
                    messageRes = R.string.dialog_nap_confirm_msg,
                    positiveRes = android.R.string.ok,
                    negativeRes = android.R.string.cancel
                )
            }
        }

        binding.btnHeroNapExtend.setOnClickListener {
            val now = SystemClock.elapsedRealtime()
            if (now - lastNapTapTime < 800L) return@setOnClickListener
            lastNapTapTime = now

            val ctx = context ?: return@setOnClickListener
            if (!NapController.isNapActive(ctx)) return@setOnClickListener

            val appCtx = ctx.applicationContext
            binding.btnHeroNapExtend.isEnabled = false
            homeViewModel.extendNap { success ->
                if (isAdded && _binding != null) {
                    binding.btnHeroNapExtend.isEnabled = true
                    if (success) {
                        Toast.makeText(appCtx, R.string.toast_nap_extended, Toast.LENGTH_SHORT).show()
                    } else {
                        val errorRes = if (!ProtectionEngine.isDeviceOwner(appCtx)) {
                            R.string.status_no_device_owner_title
                        } else {
                            R.string.status_need_enforce_desc
                        }
                        Toast.makeText(appCtx, errorRes, Toast.LENGTH_SHORT).show()
                    }
                    updateNapDisplay()
                }
            }
        }

        binding.btnOpenProvisioningWizard.setOnClickListener {
            launchProvisioningWizard()
        }

        binding.cardAdbHelp.setOnClickListener {
            launchProvisioningWizard()
        }

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

        binding.btnEnforceNow.setOnClickListener {
            val now = SystemClock.elapsedRealtime()
            if (now - lastEnforceClickTime < 1000L) return@setOnClickListener
            lastEnforceClickTime = now

            val ctx = context ?: return@setOnClickListener
            if (!ProtectionEngine.isDeviceOwner(ctx)) {
                launchProvisioningWizard()
                return@setOnClickListener
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
                        Timber.tag("HomeFragment").w(e, "Unable to prepare or launch VpnService consent intent")
                        Toast.makeText(
                            appCtx,
                            R.string.toast_emergency_vpn_consent_denied,
                            Toast.LENGTH_LONG
                        ).show()
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

        binding.btnGrantUsageAccess.setOnClickListener {
            val ctx = context ?: return@setOnClickListener
            try {
                startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
            } catch (e: Exception) {
                Timber.tag("HomeFragment").w(e, "Unable to open Usage Access Settings")
                Toast.makeText(ctx.applicationContext, R.string.toast_open_settings_failed, Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnCompleteA11ySetup.setOnClickListener {
            val ctx = context ?: return@setOnClickListener
            if (!isAdded) return@setOnClickListener
            val policy = try {
                PolicyRepository.getInstance(ctx).getCurrentPolicy()
            } catch (_: Throwable) {
                null
            }
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

    fun updateProtectionState(state: ProtectionUiState, isApplying: Boolean = false) {
        if (!isAdded || _binding == null) return
        dnsStatusNoticeJob?.cancel()
        dnsStatusNoticeJob = null

        val ctx = context ?: return
        when (state) {
            is ProtectionUiState.Loading -> {
                binding.btnEnforceNow.isEnabled = false
                binding.btnEnforceNow.setText(R.string.btn_enforcing)
            }
            is ProtectionUiState.Error -> {
                binding.btnEnforceNow.isEnabled = !isApplying
                binding.btnEnforceNow.setText(if (isApplying) R.string.btn_enforcing else R.string.btn_enforce_now)
                binding.cardHomeNotice.visibility = View.VISIBLE
                binding.tvHomeNoticeTitle.text = getString(R.string.status_need_enforce_title)
                binding.tvHomeNoticeDesc.text = state.message
                binding.tvHeroShieldStatus.text = getString(R.string.home_shield_inactive)
                binding.tvHeroShieldStatus.setTextColor(ContextCompat.getColor(ctx, R.color.shield_amber_calm))
                binding.tvHeroShieldStatus.setBackgroundResource(R.drawable.bg_shield_status_badge_amber)
                binding.frameHeroShieldGlow.setBackgroundResource(R.drawable.bg_hero_shield_inactive)
            }
            is ProtectionUiState.Success -> {
                val canEnforce = !isApplying && !state.isEnforcing
                binding.btnEnforceNow.isEnabled = canEnforce
                binding.btnEnforceNow.setText(if (canEnforce) R.string.btn_enforce_now else R.string.btn_enforcing)

                if (state.isDeviceOwner) {
                    binding.cardAdbHelp.visibility = View.GONE

                    val isHealthyStatus: (ShieldStatus?) -> Boolean = { status ->
                        status is ShieldStatus.Active || status is ShieldStatus.Unsupported
                    }
                    val allActive = ProtectionEngine.shields.all { isHealthyStatus(state.shields[it.id]) }
                    val appCtx = ctx.applicationContext

                    if (allActive) {
                        binding.tvHeroShieldStatus.text = getString(R.string.home_shield_active)
                        binding.tvHeroShieldStatus.setTextColor(ContextCompat.getColor(ctx, R.color.shield_emerald_radiant))
                        binding.tvHeroShieldStatus.setBackgroundResource(R.drawable.bg_shield_status_badge)
                        binding.frameHeroShieldGlow.setBackgroundResource(R.drawable.bg_hero_shield_glow)
                    } else {
                        binding.cardHomeNotice.visibility = View.VISIBLE

                        val firstFailingShield = ProtectionEngine.shields.firstOrNull { shield ->
                            !isHealthyStatus(state.shields[shield.id])
                        }
                        val failingStatus = firstFailingShield?.let { state.shields[it.id] }
                        val shieldTitle = firstFailingShield?.let { getString(it.titleResId) }

                        val noticeTitle = if (shieldTitle != null) {
                            getString(R.string.status_shield_need_enforce_format, shieldTitle)
                        } else {
                            getString(R.string.status_need_enforce_title)
                        }
                        val noticeDesc = when (failingStatus) {
                            is ShieldStatus.NeedsEnforcement -> failingStatus.reason
                            is ShieldStatus.Disabled -> failingStatus.warning
                            else -> getString(R.string.status_need_enforce_desc)
                        }

                        binding.tvHomeNoticeTitle.text = noticeTitle
                        binding.tvHomeNoticeDesc.text = noticeDesc
                        binding.tvHeroShieldStatus.text = getString(R.string.home_shield_inactive)
                        binding.tvHeroShieldStatus.setTextColor(ContextCompat.getColor(ctx, R.color.shield_amber_calm))
                        binding.tvHeroShieldStatus.setBackgroundResource(R.drawable.bg_shield_status_badge_amber)
                        binding.frameHeroShieldGlow.setBackgroundResource(R.drawable.bg_hero_shield_inactive)
                    }

                    dnsStatusNoticeJob = viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                        val needsManualVpnConsent = if (canEnforce) {
                            CurfewVpnDnsBridge.needsManualEmergencyVpnConsent(appCtx)
                        } else {
                            false
                        }
                        val noticeState = if (allActive) {
                            CurfewVpnDnsBridge.evaluateUnaffiliatedDnsNoticeState(appCtx)
                        } else {
                            UnaffiliatedDnsNoticeState.NONE
                        }
                        val cloneSummary = if (noticeState == UnaffiliatedDnsNoticeState.EMERGENCY_VPN_ACTIVE) {
                            SecondaryProfileDetector.formatClonedAppsSummary(appCtx)
                        } else {
                            null
                        }
                        val currentGlobalHost = if (noticeState == UnaffiliatedDnsNoticeState.DOT_LOCKED_NO_CUSTOMIZE) {
                            Settings.Global.getString(appCtx.contentResolver, "private_dns_specifier")
                        } else {
                            null
                        }
                        withContext(Dispatchers.Main) {
                            if (!isAdded || _binding == null) return@withContext
                            if (canEnforce && needsManualVpnConsent) {
                                binding.btnEnforceNow.setText(R.string.btn_grant_emergency_vpn_consent)
                            }
                            if (allActive) {
                                when (noticeState) {
                                    UnaffiliatedDnsNoticeState.EMERGENCY_VPN_ACTIVE -> {
                                        binding.cardHomeNotice.visibility = View.VISIBLE
                                        val resolvedSummary = cloneSummary ?: getString(R.string.profile_empty_container_hint)
                                        binding.tvHomeNoticeTitle.text = getString(R.string.dialog_cloned_app_resolution_title)
                                        binding.tvHomeNoticeDesc.text = getString(R.string.shield_dns_emergency_vpn_active_notice, resolvedSummary)
                                    }
                                    UnaffiliatedDnsNoticeState.DOT_LOCKED_NO_CUSTOMIZE -> {
                                        binding.cardHomeNotice.visibility = View.VISIBLE
                                        val activeHost = currentGlobalHost?.takeIf { it.isNotBlank() } ?: DnsShield.DEFAULT_DNS_HOST
                                        binding.tvHomeNoticeTitle.text = getString(R.string.dialog_cloned_app_resolution_title)
                                        binding.tvHomeNoticeDesc.text = getString(R.string.shield_dns_unaffiliated_dot_locked_notice, activeHost)
                                    }
                                    UnaffiliatedDnsNoticeState.NONE -> {
                                        binding.cardHomeNotice.visibility = View.GONE
                                    }
                                }
                            }
                        }
                    }
                } else {
                    binding.cardAdbHelp.visibility = View.VISIBLE
                    binding.cardHomeNotice.visibility = View.VISIBLE
                    binding.tvHomeNoticeTitle.text = getString(R.string.status_no_device_owner_title)
                    binding.tvHomeNoticeDesc.text = getString(R.string.status_no_device_owner_desc)
                    binding.btnEnforceNow.setText(if (canEnforce) R.string.btn_activate_device_owner_now else R.string.btn_enforcing)

                    binding.tvHeroShieldStatus.text = getString(R.string.home_shield_inactive)
                    binding.tvHeroShieldStatus.setTextColor(ContextCompat.getColor(ctx, R.color.shield_amber_calm))
                    binding.tvHeroShieldStatus.setBackgroundResource(R.drawable.bg_shield_status_badge_amber)
                    binding.frameHeroShieldGlow.setBackgroundResource(R.drawable.bg_hero_shield_inactive)
                }
            }
        }
    }

    private fun launchProvisioningWizard() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastProvisioningClickTime < 800L) return
        lastProvisioningClickTime = now
        val ctx = context ?: return
        val intent = Intent(ctx, ProvisioningWizardActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        startActivity(intent)
    }

    /**
     * Ticks the countdown timer.
     */
    fun updateNapCountdown(remainingMs: Long) {
        val ctx = context ?: return
        val isNight = HomeViewModel.isEnforcedCurfewActive(ctx)
        val isNap = remainingMs > 0L
        updateNapCountdown(remainingMs, isNap, isNight)
    }

    private fun updateNapCountdown(remainingMs: Long, isNap: Boolean, isNight: Boolean) {
        if (!isAdded || _binding == null) return

        if (isNight) {
            binding.btnHeroNapStart.isEnabled = false
            binding.btnHeroNapStart.setText(R.string.btn_nap_night_locked)
            binding.layoutNapActiveControls.visibility = View.GONE
        } else if (isNap) {
            binding.btnHeroNapStart.isEnabled = false
            binding.btnHeroNapStart.setText(R.string.btn_nap_active)
            binding.layoutNapActiveControls.visibility = View.VISIBLE

            val totalSec = (remainingMs / 1000L).toInt()
            val mm = totalSec / 60
            val ss = totalSec % 60
            binding.tvHeroNapCountdown.text = getString(R.string.nap_countdown_format, mm, ss)
        } else {
            binding.btnHeroNapStart.isEnabled = true
            binding.btnHeroNapStart.setText(R.string.btn_hero_nap_start)
            binding.layoutNapActiveControls.visibility = View.GONE
        }
    }

    private fun updateNapDisplay() {
        homeViewModel.tickNap()
    }

    private fun updateSmartBanners() {
        checkAccessibilityBanner()
        checkUsageAccess()
    }

    private fun checkAccessibilityBanner() {
        val ctx = context ?: return
        val b = _binding ?: return
        val policy = try {
            PolicyRepository.getInstance(ctx).getCurrentPolicy()
        } catch (_: Throwable) {
            null
        } ?: run {
            b.cardA11yRequired.visibility = View.GONE
            return
        }

        val isA11yActive = HisnAccessibilityService.instance != null ||
            AccessibilitySelfActivator.isServiceEnabledInSettings(ctx)
        val isA11yRequired = AccessibilityPolicyCoordinator.isAccessibilityRequired(policy)
        val isCloaked = BankingCloakManager.isCloaked(ctx)

        if (isA11yRequired && !isA11yActive && !isCloaked) {
            if (policy.isSupervised) {
                b.tvA11yRequiredTitle.setText(R.string.home_a11y_required_title_guardian)
                b.tvA11yRequiredDesc.setText(R.string.home_a11y_required_desc_guardian)
            } else {
                b.tvA11yRequiredTitle.setText(R.string.home_a11y_required_title)
                b.tvA11yRequiredDesc.setText(R.string.home_a11y_required_desc)
            }
            b.cardA11yRequired.visibility = View.VISIBLE
        } else {
            b.cardA11yRequired.visibility = View.GONE
        }
    }

    private fun checkUsageAccess() {
        val ctx = context ?: return
        val b = _binding ?: return
        val policy = try {
            PolicyRepository.getInstance(ctx).getCurrentPolicy()
        } catch (_: Throwable) {
            null
        }
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
        childFragmentManager.showSafely(SecondaryProfileResolutionDialogFragment.TAG_SELECT) {
            SecondaryProfileResolutionDialogFragment.newSelectionInstance(secondaries)
        }
    }

    override fun onDestroyView() {
        dnsStatusNoticeJob?.cancel()
        dnsStatusNoticeJob = null
        super.onDestroyView()
        _binding = null
    }

    companion object {
        private const val REQ_NAP_CONFIRM = "req_home_nap_confirm"
        private const val TAG_NAP_CONFIRM = "tag_home_nap_confirm"
    }
}
