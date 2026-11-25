package com.drmohammed.antiporn.ui.control.web

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.view.View
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
import com.drmohammed.antiporn.R
import com.drmohammed.antiporn.core.engine.ProtectionEngine
import com.drmohammed.antiporn.core.engine.SecondaryProfileDetector
import com.drmohammed.antiporn.core.model.DevicePolicyBundle
import com.drmohammed.antiporn.curfew.CurfewVpnDnsBridge
import com.drmohammed.antiporn.curfew.WebFilterCapabilityResult
import com.drmohammed.antiporn.data.repository.PolicyRepository
import com.drmohammed.antiporn.databinding.ActivityWebFilterSettingsBinding
import com.drmohammed.antiporn.telemetry.SafeDpmGateway
import com.drmohammed.antiporn.ui.common.SecondaryProfileResolutionDialogHelper
import com.drmohammed.antiporn.ui.control.gate.SelfControlAccessGateRegistry
import com.drmohammed.antiporn.ui.control.gate.SelfControlSection
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.viewModel
import timber.log.Timber

class WebFilterSettingsActivity : AppCompatActivity() {

    private val policyRepository: PolicyRepository by inject()
    private val viewModel: WebFilterViewModel by viewModel()
    private lateinit var binding: ActivityWebFilterSettingsBinding

    private enum class VpnConsentSource { FULL_SYSTEM, AD_BLOCKING }

    private var isProgrammaticUiUpdate = false
    private var pendingVpnConsentSource: VpnConsentSource? = null
    private var activeDialog: AlertDialog? = null

    private val vpnConsentLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        val source = pendingVpnConsentSource ?: VpnConsentSource.FULL_SYSTEM
        pendingVpnConsentSource = null
        if (isFinishing || isDestroyed || viewModel.uiState.value.isRemote) return@registerForActivityResult
        val granted = runCatching { VpnService.prepare(this) == null }.getOrDefault(false)
        if (source == VpnConsentSource.FULL_SYSTEM) {
            viewModel.setFullSystemFiltering(granted)
            if (granted) {
                val updated = viewModel.uiState.value
                if (updated.isWhitelistOnlyMode) {
                    showAlert(getString(R.string.dialog_whitelist_full_system_warning_title), getString(R.string.dialog_whitelist_full_system_warning_msg))
                } else if (!updated.isBlockExternalVpn) {
                    showAlert(getString(R.string.dialog_external_vpn_coexistence_title), getString(R.string.dialog_external_vpn_coexistence_msg))
                }
            }
        } else {
            viewModel.setAdBlocking(granted)
        }
        if (!granted) Toast.makeText(this, R.string.vpn_permission_denied_msg, Toast.LENGTH_SHORT).show()
    }

    private val backPressedCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            val state = viewModel.uiState.value
            if (state.isSaving) return
            if (state.hasUnsavedChanges()) showDiscardConfirmationDialog() else finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        binding = ActivityWebFilterSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ViewCompat.setOnApplyWindowInsetsListener(binding.layoutRootWebFilterSettings) { view, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(insets.left, insets.top, insets.right, insets.bottom)
            windowInsets
        }

        val isRemote = intent.getBooleanExtra(EXTRA_IS_REMOTE, false)
        val wardId = intent.getStringExtra(EXTRA_WARD_ID).orEmpty()
        val policyJson = intent.getStringExtra(EXTRA_POLICY_JSON)
        if (!isRemote && !hasSelfControlAccess()) {
            Toast.makeText(this, R.string.self_control_section_locked_by_guardian, Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        val initialPolicy = resolveInitialPolicy(isRemote, wardId, policyJson) ?: return
        val newlyInitialized = viewModel.initialize(isRemote, wardId, initialPolicy)
        savedInstanceState?.let {
            if (it.containsKey(STATE_PENDING_VPN_CONSENT_SOURCE)) {
                pendingVpnConsentSource = VpnConsentSource.entries.getOrNull(it.getInt(STATE_PENDING_VPN_CONSENT_SOURCE, -1))
            }
            if (newlyInitialized) viewModel.restoreFromSavedState(it)
        }
        onBackPressedDispatcher.addCallback(this, backPressedCallback)
        setupFragmentResultListeners()
        setupListeners()
        observeViewModel()
    }

    private fun resolveInitialPolicy(isRemote: Boolean, wardId: String, policyJson: String?): DevicePolicyBundle? {
        if (!isRemote) return policyRepository.getCurrentPolicy()
        val parsed = if (wardId.isNotBlank() && !policyJson.isNullOrBlank()) DevicePolicyBundle.fromJsonOrNull(policyJson) else null
        if (parsed == null) {
            Timber.e("Refusing remote launch: missing/invalid wardId or EXTRA_POLICY_JSON")
            Toast.makeText(applicationContext, R.string.toast_guardian_policy_save_failed, Toast.LENGTH_LONG).show()
            finish()
        }
        return parsed
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        viewModel.writeToSavedState(outState)
        pendingVpnConsentSource?.let { outState.putInt(STATE_PENDING_VPN_CONSENT_SOURCE, it.ordinal) }
    }

    override fun onResume() {
        super.onResume()
        if (!viewModel.uiState.value.isRemote) {
            if (!hasSelfControlAccess()) {
                Toast.makeText(this, R.string.self_control_section_locked_by_guardian, Toast.LENGTH_SHORT).show()
                finish()
                return
            }
            viewModel.refreshUnaffiliatedStatusAsync()
        }
    }

    private fun hasSelfControlAccess(): Boolean =
        SelfControlAccessGateRegistry.getActiveGate().canAccess(this) &&
            SelfControlAccessGateRegistry.isSectionPermitted(this, SelfControlSection.WEB_FILTER)

    private fun setupFragmentResultListeners() {
        supportFragmentManager.setFragmentResultListener(DnsPickerBottomSheet.REQUEST_KEY, this) { _, bundle ->
            val host = bundle.getString(DnsPickerBottomSheet.RESULT_DNS_HOST) ?: return@setFragmentResultListener
            viewModel.updateDnsHost(host, bundle.getBoolean(DnsPickerBottomSheet.RESULT_VALIDATED, false))
        }
        supportFragmentManager.setFragmentResultListener(WebsiteRulesBottomSheet.REQUEST_KEY, this) { _, bundle ->
            val blocked = bundle.getStringArrayList(WebsiteRulesBottomSheet.RESULT_BLOCKED_URLS)?.toSet() ?: emptySet()
            val allowed = bundle.getStringArrayList(WebsiteRulesBottomSheet.RESULT_ALLOWED_URLS)?.toSet() ?: emptySet()
            viewModel.updateWebsiteRules(blocked, allowed)
        }
    }

    private fun setupListeners() = with(binding) {
        toolbarWebFilter.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }
        val openDnsPicker = View.OnClickListener {
            if (supportFragmentManager.findFragmentByTag(DnsPickerBottomSheet.TAG) == null) {
                DnsPickerBottomSheet.newInstance(viewModel.uiState.value.dnsHost).show(supportFragmentManager, DnsPickerBottomSheet.TAG)
            }
        }
        layoutDnsSummaryRow.setOnClickListener(openDnsPicker)
        btnChangeDns.setOnClickListener(openDnsPicker)
        switchBlockExternalVpn.setOnCheckedChangeListener { _, isChecked ->
            if (isProgrammaticUiUpdate) return@setOnCheckedChangeListener
            viewModel.setBlockExternalVpn(isChecked)
            if (!isChecked && viewModel.uiState.value.isFullSystemFiltering) {
                showAlert(getString(R.string.dialog_external_vpn_coexistence_title), getString(R.string.dialog_external_vpn_coexistence_msg))
            }
        }
        switchBlockIncognito.setOnCheckedChangeListener { _, c -> if (!isProgrammaticUiUpdate) viewModel.setIncognitoBlocked(c) }
        switchBlockTelegramWeb.setOnCheckedChangeListener { _, c -> if (!isProgrammaticUiUpdate) viewModel.setTelegramWebBlocked(c) }
        switchYouTubeRestricted.setOnCheckedChangeListener { _, c -> if (!isProgrammaticUiUpdate) viewModel.setYouTubeRestricted(c) }
        toggleGroupSiteMode.addOnButtonCheckedListener { _, id, c -> if (!isProgrammaticUiUpdate && c) viewModel.setWhitelistOnlyMode(id == R.id.btnModeWhitelist) }
        btnManageWebsites.setOnClickListener {
            if (supportFragmentManager.findFragmentByTag(WebsiteRulesBottomSheet.TAG) == null) {
                val s = viewModel.uiState.value
                WebsiteRulesBottomSheet.newInstance(s.blockedUrls, s.allowedUrls, s.isWhitelistOnlyMode)
                    .show(supportFragmentManager, WebsiteRulesBottomSheet.TAG)
            }
        }
        btnDisableFullSystemFromWarning.setOnClickListener { viewModel.setFullSystemFiltering(false) }
        switchFullSystemFilter.setOnCheckedChangeListener { _, isChecked ->
            if (isProgrammaticUiUpdate) return@setOnCheckedChangeListener
            val state = viewModel.uiState.value
            if (isChecked && !state.isRemote && !ensureVpnConsentOrPrompt(VpnConsentSource.FULL_SYSTEM)) {
                resetSwitchUnchecked(switchFullSystemFilter)
                return@setOnCheckedChangeListener
            }
            viewModel.setFullSystemFiltering(isChecked)
            if (isChecked && state.isWhitelistOnlyMode) {
                showAlert(getString(R.string.dialog_whitelist_full_system_warning_title), getString(R.string.dialog_whitelist_full_system_warning_msg))
            } else if (isChecked && !state.isBlockExternalVpn) {
                showAlert(getString(R.string.dialog_external_vpn_coexistence_title), getString(R.string.dialog_external_vpn_coexistence_msg))
            }
        }
        switchBlockAds.setOnCheckedChangeListener { _, isChecked ->
            if (isProgrammaticUiUpdate) return@setOnCheckedChangeListener
            if (isChecked && !viewModel.uiState.value.isRemote && !ensureVpnConsentOrPrompt(VpnConsentSource.AD_BLOCKING)) {
                resetSwitchUnchecked(switchBlockAds)
                return@setOnCheckedChangeListener
            }
            viewModel.setAdBlocking(isChecked)
        }
        btnResolveSecondaryProfile.setOnClickListener { if (!viewModel.uiState.value.isRemote) openSelectiveProfileResolutionFromWebFilter() }
        btnSaveWebFilterChanges.setOnClickListener { viewModel.saveChanges() }
    }

    private fun resetSwitchUnchecked(sw: MaterialSwitch) {
        isProgrammaticUiUpdate = true; sw.isChecked = false; isProgrammaticUiUpdate = false
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.uiState.collect { renderState(it) } }
                launch { viewModel.events.collect { handleEvent(it) } }
            }
        }
    }

    private fun renderState(state: WebFilterUiState) = with(binding) {
        renderEngineBanner(state)
        tvDnsActiveSummary.text = when (state.selectedDnsPreset) {
            DnsPreset.CLOUDFLARE -> getString(R.string.web_dns_summary_cloudflare)
            DnsPreset.ADGUARD -> getString(R.string.web_dns_summary_adguard)
            DnsPreset.CONTROLD -> getString(R.string.web_dns_summary_controld)
            DnsPreset.CUSTOM -> getString(R.string.web_dns_summary_custom_format, state.dnsHost)
        }
        tvDnsActiveHost.text = state.dnsHost.ifBlank { DevicePolicyBundle.CLOUDFLARE_FAMILY_DNS_HOST }
        val scopeRes = if (state.isFullSystemFiltering) R.string.web_scope_badge_all_device else R.string.web_scope_badge_browsers
        tvBadgeTelegramScope.setText(scopeRes)
        tvBadgeYouTubeScope.setText(scopeRes)

        isProgrammaticUiUpdate = true
        switchBlockExternalVpn.isChecked = state.isBlockExternalVpn
        switchBlockIncognito.isChecked = state.isIncognitoBlocked
        switchBlockTelegramWeb.isChecked = state.isTelegramWebBlocked
        switchYouTubeRestricted.isChecked = state.isYouTubeRestricted
        switchFullSystemFilter.isChecked = state.isFullSystemFiltering
        switchBlockAds.isChecked = state.isAdBlocking
        toggleGroupSiteMode.check(if (state.isWhitelistOnlyMode) R.id.btnModeWhitelist else R.id.btnModeBlocklist)
        isProgrammaticUiUpdate = false

        tvWebsitesSummary.text = getString(R.string.web_sites_summary_format, state.blockedUrls.size, state.allowedUrls.size)
        layoutWhitelistWarning.visibility = if (state.showWhitelistFullSystemWarning) View.VISIBLE else View.GONE
        layoutUnaffiliatedWarning.visibility = if (state.showUnaffiliatedLocalWarning) View.VISIBLE else View.GONE
        tvAdsDnsHint.visibility = if (state.adBlockDnsHint == AdBlockDnsHint.NONE) View.GONE else View.VISIBLE
        if (state.adBlockDnsHint == AdBlockDnsHint.CONFIRMED_BY_DNS) tvAdsDnsHint.setText(R.string.web_ads_dns_confirmed_hint)
        else if (state.adBlockDnsHint == AdBlockDnsHint.POSSIBLE_CUSTOM_DNS) tvAdsDnsHint.setText(R.string.web_ads_dns_custom_hint)

        val dirty = state.hasUnsavedChanges()
        btnSaveWebFilterChanges.isEnabled = dirty && !state.isSaving
        btnSaveWebFilterChanges.alpha = if (dirty && !state.isSaving) 1.0f else 0.5f
        backPressedCallback.isEnabled = dirty || state.isSaving
        listOf(
            layoutDnsSummaryRow, btnChangeDns, switchBlockExternalVpn, switchBlockIncognito,
            switchBlockTelegramWeb, switchYouTubeRestricted, btnModeBlocklist, btnModeWhitelist,
            btnManageWebsites, btnDisableFullSystemFromWarning, switchFullSystemFilter,
            switchBlockAds, btnResolveSecondaryProfile
        ).forEach { it.isEnabled = !state.isSaving }
    }

    private fun renderEngineBanner(state: WebFilterUiState) = with(binding) {
        val isLight = state.engineMode == EngineMode.LIGHT_PRIVATE_DNS
        val bg = if (isLight) getColor(R.color.status_green_bg) else MaterialColors.getColor(root, com.google.android.material.R.attr.colorSecondaryContainer)
        val accent = if (isLight) getColor(R.color.status_green) else MaterialColors.getColor(root, com.google.android.material.R.attr.colorSecondary)
        cardEngineStatus.setCardBackgroundColor(bg)
        cardEngineStatus.strokeColor = accent
        ivEngineStatusIcon.setImageResource(if (isLight) R.drawable.ic_shield_dns else R.drawable.ic_shield_vpn)
        ivEngineStatusIcon.setColorFilter(accent)
        tvEngineStatusTitle.setText(if (isLight) R.string.web_engine_status_light_title else R.string.web_engine_status_vpn_title)
        tvEngineStatusDesc.setText(
            if (isLight) R.string.web_engine_status_light_desc
            else when (state.localVpnReason) {
                LocalVpnReason.BOTH -> R.string.web_engine_status_vpn_reason_both
                LocalVpnReason.AD_BLOCKING -> R.string.web_engine_status_vpn_reason_ads
                else -> R.string.web_engine_status_vpn_reason_full
            }
        )
    }

    private fun handleEvent(event: WebFilterUiEvent) {
        when (event) {
            is WebFilterUiEvent.SaveSuccess -> {
                val res = if (viewModel.uiState.value.isRemote) R.string.toast_guardian_policy_saved else R.string.toast_self_policy_saved
                Toast.makeText(applicationContext, res, Toast.LENGTH_SHORT).show()
                setResult(Activity.RESULT_OK, Intent().putExtra(EXTRA_UPDATED_POLICY_JSON, event.policyJson))
                backPressedCallback.isEnabled = false
                finish()
            }
            is WebFilterUiEvent.SaveFailed -> Toast.makeText(applicationContext, if (event.isRemote) R.string.toast_guardian_policy_save_failed else R.string.toast_self_policy_save_failed, Toast.LENGTH_LONG).show()
            is WebFilterUiEvent.ValidationErrorDns -> showAlert(event.title ?: getString(R.string.dns_probe_dialog_failed_title), event.message)
            is WebFilterUiEvent.InvalidDnsHostFormat -> Toast.makeText(this, R.string.error_invalid_dns_host, Toast.LENGTH_SHORT).show()
            is WebFilterUiEvent.CapabilityBlocked -> if (!viewModel.uiState.value.isRemote) showWebFilterSaveBlockedDialog(event.capability)
            is WebFilterUiEvent.EmptyWhitelistError -> showAlert(getString(R.string.dialog_empty_whitelist_title), getString(R.string.dialog_empty_whitelist_desc))
        }
    }

    private fun ensureVpnConsentOrPrompt(source: VpnConsentSource): Boolean {
        if (SafeDpmGateway.isDeviceOwner(this)) return true
        if (pendingVpnConsentSource != null) return false
        val prepareIntent = runCatching { VpnService.prepare(this) }.getOrElse {
            Toast.makeText(this, R.string.vpn_permission_denied_msg, Toast.LENGTH_SHORT).show()
            return false
        }
        if (prepareIntent != null) {
            pendingVpnConsentSource = source
            runCatching { vpnConsentLauncher.launch(prepareIntent) }.onFailure {
                pendingVpnConsentSource = null
                Toast.makeText(this, R.string.vpn_permission_denied_msg, Toast.LENGTH_SHORT).show()
            }
            return false
        }
        return true
    }

    private fun showWebFilterSaveBlockedDialog(capability: WebFilterCapabilityResult) {
        if (isFinishing || isDestroyed) return
        val message = when (capability) {
            is WebFilterCapabilityResult.BlockedDnsHostChange -> getString(R.string.dialog_web_filter_save_blocked_dns_msg, capability.requestedHost, capability.lockedHost)
            is WebFilterCapabilityResult.BlockedLocalFilterByLockedDot -> getString(R.string.dialog_web_filter_save_blocked_filter_msg, capability.lockedHost)
            is WebFilterCapabilityResult.BlockedHostilePrivateDns -> getString(R.string.dialog_web_filter_save_blocked_hostile_msg, capability.hostileHost)
            is WebFilterCapabilityResult.Allowed -> return
        }
        showDialog(
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.dialog_web_filter_save_blocked_title)
                .setMessage(message)
                .setPositiveButton(R.string.btn_resolve_secondary_profile_now) { _, _ -> openSelectiveProfileResolutionFromWebFilter() }
                .setNegativeButton(android.R.string.cancel, null)
                .create()
        )
    }

    private fun openSelectiveProfileResolutionFromWebFilter() {
        val appCtx = applicationContext
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val secondaries = SecondaryProfileDetector.getSecondaryProfiles(appCtx)
                withContext(Dispatchers.Main) {
                    if (isFinishing || isDestroyed) return@withContext
                    if (secondaries.isEmpty()) { openCloneSettingsFallback(); return@withContext }
                    activeDialog?.dismiss()
                    SecondaryProfileResolutionDialogHelper.showProfileSelectionDialog(
                        context = this@WebFilterSettingsActivity, secondaries = secondaries,
                        onOpenSettings = { openCloneSettingsFallback() },
                        onConfirmRemoveSingleProfile = { executeSingleProfileRemovalFromWebFilter(it) },
                        onActiveDialogChanged = { activeDialog = it }
                    )
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Timber.e(e, "Failed loading secondary profiles from WebFilterSettingsActivity")
                withContext(Dispatchers.Main) {
                    if (!isFinishing && !isDestroyed) openCloneSettingsFallback()
                }
            }
        }
    }

    private fun openCloneSettingsFallback() = runCatching { startActivity(SecondaryProfileDetector.resolveCloneSettingsIntent(this)) }
        .onFailure { Toast.makeText(applicationContext, R.string.toast_open_settings_failed, Toast.LENGTH_SHORT).show() }

    private fun executeSingleProfileRemovalFromWebFilter(profile: SecondaryProfileDetector.UnaffiliatedProfileInfo) {
        val appCtx = applicationContext
        lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO + NonCancellable) {
                    val removal = SecondaryProfileDetector.removeSecondaryProfileAndAwait(appCtx, profile)
                    if (removal.allSecondariesCleared) CurfewVpnDnsBridge.updatePrivateDnsBlockedByUnaffiliatedUsers(appCtx, false)
                    if (removal.removedTarget) ProtectionEngine.applyAll(appCtx)
                    viewModel.refreshUnaffiliatedStatusAsync()
                    removal
                }
                if (isFinishing || isDestroyed) return@launch
                val msgRes = when {
                    result.allSecondariesCleared -> R.string.msg_cloned_app_removed_success
                    result.removedTarget -> R.string.msg_profile_removed_more_remaining
                    else -> R.string.msg_cloned_app_remove_failed
                }
                Toast.makeText(appCtx, msgRes, Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Timber.e(e, "Failed removing secondary profile userId=%d", profile.userId)
                if (!isFinishing && !isDestroyed) {
                    Toast.makeText(appCtx, R.string.msg_cloned_app_remove_failed, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun showDiscardConfirmationDialog() {
        showDialog(
            MaterialAlertDialogBuilder(this).setTitle(R.string.dialog_unsaved_changes_title).setMessage(R.string.dialog_unsaved_changes_msg)
                .setNegativeButton(R.string.btn_keep_editing) { d, _ -> d.dismiss() }
                .setPositiveButton(R.string.btn_discard_changes) { d, _ -> d.dismiss(); backPressedCallback.isEnabled = false; finish() }
                .create()
        )
    }

    private fun showAlert(title: String, message: String) = showDialog(MaterialAlertDialogBuilder(this).setTitle(title).setMessage(message).setPositiveButton(android.R.string.ok, null).create())

    private fun showDialog(dialog: AlertDialog) {
        if (isFinishing || isDestroyed) return
        activeDialog?.dismiss()
        dialog.setOnDismissListener { if (activeDialog === dialog) activeDialog = null }
        activeDialog = dialog
        dialog.show()
    }

    override fun onDestroy() { activeDialog?.dismiss(); activeDialog = null; super.onDestroy() }

    companion object {
        const val EXTRA_IS_REMOTE = "extra_is_remote"
        const val EXTRA_WARD_ID = "extra_ward_id"
        const val EXTRA_POLICY_JSON = "extra_policy_json"
        const val EXTRA_UPDATED_POLICY_JSON = "extra_updated_policy_json"
        private const val STATE_PENDING_VPN_CONSENT_SOURCE = "state_pending_vpn_consent_source"
    }
}
