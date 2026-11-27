package com.drmohammed.antiporn.ui.home

import android.content.Context
import android.provider.Settings
import android.view.View
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.drmohammed.antiporn.R
import com.drmohammed.antiporn.core.engine.ProtectionEngine
import com.drmohammed.antiporn.core.engine.SecondaryProfileDetector
import com.drmohammed.antiporn.core.model.ShieldStatus
import com.drmohammed.antiporn.curfew.CurfewVpnDnsBridge
import com.drmohammed.antiporn.curfew.UnaffiliatedDnsNoticeState
import com.drmohammed.antiporn.databinding.FragmentHomeBinding
import com.drmohammed.antiporn.shields.DnsShield
import com.drmohammed.antiporn.ui.ProtectionUiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Renders the hero shield status badge, Device Owner setup notice, and DNS / secondary-profile
 * warning banners inside [HomeFragment].
 */
internal class HomeProtectionRenderer(
    private val lifecycleOwner: LifecycleOwner,
    private val isAddedProvider: () -> Boolean,
    private val contextProvider: () -> Context?,
    private val bindingProvider: () -> FragmentHomeBinding?
) {
    private var dnsStatusNoticeJob: Job? = null

    fun render(state: ProtectionUiState, isApplying: Boolean) {
        if (!isAddedProvider()) return
        val binding = bindingProvider() ?: return
        val ctx = contextProvider() ?: return

        dnsStatusNoticeJob?.cancel()
        dnsStatusNoticeJob = null

        when (state) {
            is ProtectionUiState.Loading -> {
                binding.btnEnforceNow.isEnabled = false
                binding.btnEnforceNow.setText(R.string.btn_enforcing)
            }
            is ProtectionUiState.Error -> {
                binding.btnEnforceNow.isEnabled = !isApplying
                binding.btnEnforceNow.setText(if (isApplying) R.string.btn_enforcing else R.string.btn_enforce_now)
                binding.cardHomeNotice.visibility = View.VISIBLE
                binding.tvHomeNoticeTitle.text = ctx.getString(R.string.status_need_enforce_title)
                binding.tvHomeNoticeDesc.text = state.message
                applyHeroShieldInactive(ctx, binding)
            }
            is ProtectionUiState.Success -> renderSuccess(ctx, binding, state, isApplying)
        }
    }

    private fun renderSuccess(
        ctx: Context,
        binding: FragmentHomeBinding,
        state: ProtectionUiState.Success,
        isApplying: Boolean
    ) {
        val canEnforce = !isApplying && !state.isEnforcing
        binding.btnEnforceNow.isEnabled = canEnforce
        binding.btnEnforceNow.setText(if (canEnforce) R.string.btn_enforce_now else R.string.btn_enforcing)

        if (!state.isDeviceOwner) {
            binding.cardAdbHelp.visibility = View.VISIBLE
            binding.cardHomeNotice.visibility = View.VISIBLE
            binding.tvHomeNoticeTitle.text = ctx.getString(R.string.status_no_device_owner_title)
            binding.tvHomeNoticeDesc.text = ctx.getString(R.string.status_no_device_owner_desc)
            binding.btnEnforceNow.setText(if (canEnforce) R.string.btn_activate_device_owner_now else R.string.btn_enforcing)
            applyHeroShieldInactive(ctx, binding)
            return
        }

        binding.cardAdbHelp.visibility = View.GONE
        val isHealthy: (ShieldStatus?) -> Boolean = { status ->
            status is ShieldStatus.Active || status is ShieldStatus.Unsupported
        }
        val allActive = ProtectionEngine.shields.all { isHealthy(state.shields[it.id]) }
        val appCtx = ctx.applicationContext

        if (allActive) {
            binding.tvHeroShieldStatus.text = ctx.getString(R.string.home_shield_active)
            binding.tvHeroShieldStatus.setTextColor(ContextCompat.getColor(ctx, R.color.shield_emerald_radiant))
            binding.tvHeroShieldStatus.setBackgroundResource(R.drawable.bg_shield_status_badge)
            binding.frameHeroShieldGlow.setBackgroundResource(R.drawable.bg_hero_shield_glow)
        } else {
            binding.cardHomeNotice.visibility = View.VISIBLE
            val failingShield = ProtectionEngine.shields.firstOrNull { !isHealthy(state.shields[it.id]) }
            val failingStatus = failingShield?.let { state.shields[it.id] }
            val shieldTitle = failingShield?.let { ctx.getString(it.titleResId) }
            binding.tvHomeNoticeTitle.text = if (shieldTitle != null) {
                ctx.getString(R.string.status_shield_need_enforce_format, shieldTitle)
            } else {
                ctx.getString(R.string.status_need_enforce_title)
            }
            binding.tvHomeNoticeDesc.text = when (failingStatus) {
                is ShieldStatus.NeedsEnforcement -> failingStatus.reason
                is ShieldStatus.Disabled -> failingStatus.warning
                else -> ctx.getString(R.string.status_need_enforce_desc)
            }
            applyHeroShieldInactive(ctx, binding)
        }

        dnsStatusNoticeJob = lifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val needsVpnConsent = if (canEnforce) CurfewVpnDnsBridge.needsManualEmergencyVpnConsent(appCtx) else false
            val noticeState = if (allActive) CurfewVpnDnsBridge.evaluateUnaffiliatedDnsNoticeState(appCtx) else UnaffiliatedDnsNoticeState.NONE
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
                val b = bindingProvider()
                val uiCtx = contextProvider()
                if (!isAddedProvider() || b == null || uiCtx == null) return@withContext
                if (canEnforce && needsVpnConsent) {
                    b.btnEnforceNow.setText(R.string.btn_grant_emergency_vpn_consent)
                }
                if (allActive) {
                    applyNoticeState(uiCtx, b, noticeState, cloneSummary, currentGlobalHost)
                }
            }
        }
    }

    private fun applyNoticeState(
        ctx: Context,
        binding: FragmentHomeBinding,
        noticeState: UnaffiliatedDnsNoticeState,
        cloneSummary: String?,
        currentGlobalHost: String?
    ) {
        when (noticeState) {
            UnaffiliatedDnsNoticeState.EMERGENCY_VPN_ACTIVE -> {
                binding.cardHomeNotice.visibility = View.VISIBLE
                val resolvedSummary = cloneSummary ?: ctx.getString(R.string.profile_empty_container_hint)
                binding.tvHomeNoticeTitle.text = ctx.getString(R.string.dialog_cloned_app_resolution_title)
                binding.tvHomeNoticeDesc.text = ctx.getString(R.string.shield_dns_emergency_vpn_active_notice, resolvedSummary)
            }
            UnaffiliatedDnsNoticeState.DOT_LOCKED_NO_CUSTOMIZE -> {
                binding.cardHomeNotice.visibility = View.VISIBLE
                val activeHost = currentGlobalHost?.takeIf { it.isNotBlank() } ?: DnsShield.DEFAULT_DNS_HOST
                binding.tvHomeNoticeTitle.text = ctx.getString(R.string.dialog_cloned_app_resolution_title)
                binding.tvHomeNoticeDesc.text = ctx.getString(R.string.shield_dns_unaffiliated_dot_locked_notice, activeHost)
            }
            UnaffiliatedDnsNoticeState.NONE -> {
                binding.cardHomeNotice.visibility = View.GONE
            }
        }
    }

    private fun applyHeroShieldInactive(ctx: Context, binding: FragmentHomeBinding) {
        binding.tvHeroShieldStatus.text = ctx.getString(R.string.home_shield_inactive)
        binding.tvHeroShieldStatus.setTextColor(ContextCompat.getColor(ctx, R.color.shield_amber_calm))
        binding.tvHeroShieldStatus.setBackgroundResource(R.drawable.bg_shield_status_badge_amber)
        binding.frameHeroShieldGlow.setBackgroundResource(R.drawable.bg_hero_shield_inactive)
    }

    fun cancel() {
        dnsStatusNoticeJob?.cancel()
        dnsStatusNoticeJob = null
    }
}
