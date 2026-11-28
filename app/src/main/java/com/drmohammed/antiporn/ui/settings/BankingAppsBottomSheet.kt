package com.drmohammed.antiporn.ui.settings

import android.os.Bundle
import android.os.SystemClock
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.drmohammed.antiporn.R
import com.drmohammed.antiporn.accessibility.HisnAccessibilityService
import com.drmohammed.antiporn.accessibility.control.AccessibilitySelfActivator
import com.drmohammed.antiporn.banking.BankingAppRegistry
import com.drmohammed.antiporn.banking.BankingCloakManager
import com.drmohammed.antiporn.banking.BankingShortcutHelper
import com.drmohammed.antiporn.databinding.BottomSheetBankingAppsBinding
import com.drmohammed.antiporn.ui.common.applyBottomSheetInsets
import com.drmohammed.antiporn.ui.common.configureLandscapeFriendlySheet
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.color.MaterialColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Material 3 BottomSheet presenting installed banking apps with cloak-safe launch
 * and pinned home-screen shortcut creation.
 */
class BankingAppsBottomSheet : BottomSheetDialogFragment() {

    private var _binding: BottomSheetBankingAppsBinding? = null
    private val binding: BottomSheetBankingAppsBinding
        get() = _binding ?: throw IllegalStateException("Binding is only valid between onCreateView and onDestroyView")

    private var lastBankingLaunchClickTime = 0L
    private var lastPinShortcutClickTime = 0L

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetBankingAppsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        configureLandscapeFriendlySheet(enableImeResize = false)
        applyBottomSheetInsets(view, includeIme = false)

        val ctx = requireContext()
        val appCtx = ctx.applicationContext

        val adapter = BankingAppsAdapter(
            onLaunchClick = { app ->
                val currentTime = SystemClock.elapsedRealtime()
                if (currentTime - lastBankingLaunchClickTime < 1000L) return@BankingAppsAdapter
                lastBankingLaunchClickTime = currentTime
                val currentContext = context ?: return@BankingAppsAdapter
                dismiss()
                BankingCloakManager.launchSafely(
                    context = currentContext,
                    targetPackage = app.packageName,
                    settlingDelayMs = BankingCloakManager.DEFAULT_SETTLING_DELAY_MS
                )
            },
            onPinShortcutClick = { app ->
                val currentTime = SystemClock.elapsedRealtime()
                if (currentTime - lastPinShortcutClickTime < 1000L) return@BankingAppsAdapter
                lastPinShortcutClickTime = currentTime
                val currentContext = context ?: return@BankingAppsAdapter
                BankingShortcutHelper.pinBankingShortcut(currentContext, app.packageName, app.appName)
            }
        )

        binding.rvBankingApps.layoutManager = LinearLayoutManager(ctx)
        binding.rvBankingApps.adapter = adapter

        viewLifecycleOwner.lifecycleScope.launch {
            val loaded = withContext(Dispatchers.IO) {
                val isA11yActiveOrEnabled = HisnAccessibilityService.instance != null ||
                    AccessibilitySelfActivator.isServiceEnabledInSettings(appCtx)
                val canWriteSecure = AccessibilitySelfActivator.canWriteSecureSettings(appCtx)
                val installedApps = BankingAppRegistry.getInstalledBankingApps(appCtx)
                Triple(isA11yActiveOrEnabled, canWriteSecure, installedApps)
            }
            if (!isAdded || _binding == null) return@launch

            val (isA11yActiveOrEnabled, canWriteSecure, installedApps) = loaded
            bindBankingModeHint(isA11yActiveOrEnabled, canWriteSecure)

            if (installedApps.isEmpty()) {
                binding.layoutEmptyState.visibility = View.VISIBLE
                binding.rvBankingApps.visibility = View.GONE
            } else {
                binding.layoutEmptyState.visibility = View.GONE
                binding.rvBankingApps.visibility = View.VISIBLE
                adapter.submitList(installedApps)
            }
        }
    }

    private fun bindBankingModeHint(isA11yActiveOrEnabled: Boolean, canWriteSecure: Boolean) {
        val ctx = context ?: return
        when {
            !isA11yActiveOrEnabled -> {
                val green = ContextCompat.getColor(ctx, R.color.status_green)
                val greenBg = ContextCompat.getColor(ctx, R.color.status_green_bg)
                binding.tvBankingModeHint.setText(R.string.banking_mode_hint_a11y_off)
                binding.tvBankingModeHint.setTextColor(green)
                binding.cardBankingModeHint.setCardBackgroundColor(greenBg)
                binding.cardBankingModeHint.strokeColor = green
            }
            canWriteSecure -> {
                val onSecondaryContainer = MaterialColors.getColor(
                    binding.root,
                    com.google.android.material.R.attr.colorOnSecondaryContainer
                )
                val containerBg = MaterialColors.getColor(
                    binding.root,
                    com.google.android.material.R.attr.colorSecondaryContainer
                )
                val outline = MaterialColors.getColor(
                    binding.root,
                    com.google.android.material.R.attr.colorOutlineVariant
                )
                binding.tvBankingModeHint.setText(R.string.banking_mode_hint_a11y_on_adb)
                binding.tvBankingModeHint.setTextColor(onSecondaryContainer)
                binding.cardBankingModeHint.setCardBackgroundColor(containerBg)
                binding.cardBankingModeHint.strokeColor = outline
            }
            else -> {
                val onSurface = MaterialColors.getColor(
                    binding.root,
                    com.google.android.material.R.attr.colorOnSurface
                )
                val amber = ContextCompat.getColor(ctx, R.color.status_orange)
                val amberBg = ContextCompat.getColor(ctx, R.color.status_orange_bg)
                binding.tvBankingModeHint.setText(R.string.banking_mode_hint_a11y_on_qr)
                binding.tvBankingModeHint.setTextColor(onSurface)
                binding.cardBankingModeHint.setCardBackgroundColor(amberBg)
                binding.cardBankingModeHint.strokeColor = amber
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val TAG = "BankingAppsSheet"

        fun newInstance(): BankingAppsBottomSheet = BankingAppsBottomSheet()
    }
}
