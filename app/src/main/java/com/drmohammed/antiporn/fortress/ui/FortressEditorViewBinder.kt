package com.drmohammed.antiporn.fortress.ui

import android.os.Bundle
import android.view.View
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import com.drmohammed.antiporn.R
import com.drmohammed.antiporn.databinding.ActivityFortressEditorBinding
import com.drmohammed.antiporn.fortress.model.FortressProfile
import com.drmohammed.antiporn.fortress.model.FortressesConfig
import com.drmohammed.antiporn.ui.control.curfew.CurfewAppPickerBottomSheet

/**
 * View binder managing form inputs, duration chips, emergency invariants,
 * and allowed-apps state for [FortressEditorActivity].
 */
internal class FortressEditorViewBinder(
    private val activity: FortressEditorActivity,
    private val binding: ActivityFortressEditorBinding
) {
    var selectedAllowedPackages: Set<String> = emptySet()
    var selectedVpnBypassPackages: Set<String> = emptySet()
    var isInternetCutEnabled: Boolean = true
    var isEmergency: Boolean = false
    var isNewCustom: Boolean = true
    private var existingSortOrder: Int = 0
    private var existingId: String = ""

    fun bindInitialProfile(
        existingProfile: FortressProfile?,
        config: FortressesConfig,
        savedInstanceState: Bundle?
    ) {
        isNewCustom = existingProfile == null
        isEmergency = existingProfile?.isEmergency == true
        existingId = existingProfile?.id.orEmpty()
        existingSortOrder = existingProfile?.sortOrder
            ?: ((config.getCustomFortresses().maxOfOrNull { it.sortOrder } ?: 0) + 1)

        binding.toolbarFortressEditor.setTitle(
            when {
                isEmergency -> R.string.fortress_editor_title_emergency
                isNewCustom -> R.string.fortress_editor_title_create
                else -> R.string.fortress_editor_title_edit
            }
        )

        val defaultName = if (isEmergency) {
            existingProfile?.resolveDisplayName(activity)
                ?: activity.getString(R.string.fortress_emergency_default_name)
        } else {
            existingProfile?.name.orEmpty()
        }
        val initialDuration = existingProfile?.durationMinutes ?: FortressProfile.DEFAULT_EMERGENCY_DURATION_MINUTES
        isInternetCutEnabled = if (isEmergency) true else (existingProfile?.isInternetCutEnabled ?: true)
        selectedAllowedPackages = existingProfile?.allowedPackages ?: emptySet()
        selectedVpnBypassPackages = if (isEmergency || !isInternetCutEnabled) {
            emptySet()
        } else {
            existingProfile?.vpnBypassPackages ?: emptySet()
        }

        binding.etFortressName.setText(defaultName)
        applyDurationSelection(initialDuration)

        if (savedInstanceState != null && savedInstanceState.containsKey(KEY_INTERNET_CUT)) {
            binding.etFortressName.setText(savedInstanceState.getString(KEY_NAME, defaultName))
            isInternetCutEnabled = if (isEmergency) true else savedInstanceState.getBoolean(KEY_INTERNET_CUT, true)
            selectedAllowedPackages = savedInstanceState.getStringArrayList(KEY_ALLOWED)?.toSet() ?: emptySet()
            selectedVpnBypassPackages = if (isEmergency || !isInternetCutEnabled) {
                emptySet()
            } else {
                savedInstanceState.getStringArrayList(KEY_VPN_BYPASS)?.toSet() ?: emptySet()
            }
            val savedDuration = savedInstanceState.getInt(KEY_DURATION, initialDuration)
            applyDurationSelection(savedDuration)
        }

        binding.switchFortressInternetCut.isChecked = isInternetCutEnabled
        binding.switchFortressInternetCut.isEnabled = !isEmergency
        binding.tvEmergencyInternetNote.isVisible = isEmergency
        binding.tvEmergencyAppsNote.isVisible = isEmergency
        binding.btnDeleteFortress.visibility = if (!isNewCustom && !isEmergency) View.VISIBLE else View.GONE

        updateAllowedAppsSummary()
    }

    fun saveInstanceState(outState: Bundle) {
        outState.putString(KEY_NAME, binding.etFortressName.text?.toString().orEmpty())
        outState.putInt(KEY_DURATION, resolveSelectedDurationMinutes() ?: FortressProfile.DEFAULT_EMERGENCY_DURATION_MINUTES)
        outState.putBoolean(KEY_INTERNET_CUT, isInternetCutEnabled)
        outState.putStringArrayList(KEY_ALLOWED, ArrayList(selectedAllowedPackages))
        outState.putStringArrayList(KEY_VPN_BYPASS, ArrayList(selectedVpnBypassPackages))
    }

    fun setupListeners(
        isRemote: Boolean,
        wardId: String,
        blacklistPackages: Set<String>,
        canGoBack: () -> Boolean,
        onSaveClicked: () -> Unit,
        onDeleteClicked: () -> Unit
    ) {
        binding.toolbarFortressEditor.setNavigationOnClickListener {
            if (canGoBack()) activity.onBackPressedDispatcher.onBackPressed()
        }

        binding.etFortressName.doAfterTextChanged {
            binding.tilFortressName.error = null
        }

        binding.etCustomMinutes.doAfterTextChanged {
            binding.tilCustomMinutes.error = null
        }

        binding.chipGroupDuration.setOnCheckedStateChangeListener { _, checkedIds ->
            val isCustom = checkedIds.contains(R.id.chipDurationCustom)
            binding.tilCustomMinutes.isVisible = isCustom
            if (!isCustom) {
                binding.tilCustomMinutes.error = null
            }
        }

        binding.switchFortressInternetCut.setOnCheckedChangeListener { _, isChecked ->
            if (isEmergency) {
                binding.switchFortressInternetCut.isChecked = true
                isInternetCutEnabled = true
                return@setOnCheckedChangeListener
            }
            isInternetCutEnabled = isChecked
            if (!isChecked) {
                selectedVpnBypassPackages = emptySet()
            }
            updateAllowedAppsSummary()
        }

        binding.btnSelectFortressApps.setOnClickListener {
            if (activity.isFinishing || activity.isDestroyed || activity.supportFragmentManager.isStateSaved) {
                return@setOnClickListener
            }
            if (activity.supportFragmentManager.findFragmentByTag(CurfewAppPickerBottomSheet.TAG) != null) {
                return@setOnClickListener
            }
            val allowNetSelection = !isEmergency && isInternetCutEnabled
            val sheet = CurfewAppPickerBottomSheet.newInstance(
                isRemote = isRemote,
                wardId = wardId,
                initialWhitelist = selectedAllowedPackages,
                initialVpnBypass = if (allowNetSelection) selectedVpnBypassPackages else emptySet(),
                blacklist = blacklistPackages,
                allowNetSelection = allowNetSelection
            )
            sheet.show(activity.supportFragmentManager, CurfewAppPickerBottomSheet.TAG)
        }

        binding.btnSaveFortress.setOnClickListener { onSaveClicked() }
        binding.btnDeleteFortress.setOnClickListener { onDeleteClicked() }
    }

    fun updateAllowedAppsSummary() {
        val allowedCount = selectedAllowedPackages.size
        val netCount = selectedVpnBypassPackages.count { selectedAllowedPackages.contains(it) }
        binding.tvFortressAllowedAppsCount.text = when {
            allowedCount == 0 -> activity.getString(R.string.fortress_allowed_apps_summary_none)
            !isEmergency && isInternetCutEnabled && netCount > 0 ->
                activity.getString(R.string.curfew_allowed_apps_count_with_net_format, allowedCount, netCount)
            else -> activity.getString(R.string.curfew_allowed_apps_count_format, allowedCount)
        }
    }

    fun setBusy(isBusy: Boolean) {
        binding.btnSaveFortress.isEnabled = !isBusy
        binding.btnDeleteFortress.isEnabled = !isBusy
        binding.btnSelectFortressApps.isEnabled = !isBusy
    }

    fun validateAndBuildProfile(): FortressProfile? {
        val rawName = binding.etFortressName.text?.toString()?.trim().orEmpty()
        if (!isEmergency && rawName.isBlank()) {
            binding.tilFortressName.error = activity.getString(R.string.fortress_error_name_required)
            return null
        }

        val duration = resolveSelectedDurationMinutes()
        if (duration == null || duration !in MIN_DURATION_MINUTES..MAX_DURATION_MINUTES) {
            if (binding.chipDurationCustom.isChecked) {
                binding.tilCustomMinutes.error = activity.getString(R.string.fortress_error_duration_range)
            }
            return null
        }

        val effectiveInternetCut = if (isEmergency) true else isInternetCutEnabled
        val effectiveBypass = if (!isEmergency && effectiveInternetCut) {
            selectedVpnBypassPackages.intersect(selectedAllowedPackages)
        } else {
            emptySet()
        }

        return FortressProfile(
            id = if (isEmergency) FortressProfile.EMERGENCY_FORTRESS_ID else existingId,
            name = rawName,
            durationMinutes = duration,
            isEmergency = isEmergency,
            isInternetCutEnabled = effectiveInternetCut,
            allowedPackages = selectedAllowedPackages,
            vpnBypassPackages = effectiveBypass,
            sortOrder = if (isEmergency) 0 else existingSortOrder
        ).normalized()
    }

    private fun applyDurationSelection(minutes: Int) {
        when (minutes) {
            30 -> {
                binding.chipGroupDuration.check(R.id.chipDuration30m)
                binding.tilCustomMinutes.isVisible = false
            }
            60 -> {
                binding.chipGroupDuration.check(R.id.chipDuration1h)
                binding.tilCustomMinutes.isVisible = false
            }
            120 -> {
                binding.chipGroupDuration.check(R.id.chipDuration2h)
                binding.tilCustomMinutes.isVisible = false
            }
            else -> {
                binding.chipGroupDuration.check(R.id.chipDurationCustom)
                binding.tilCustomMinutes.isVisible = true
                binding.etCustomMinutes.setText(minutes.toString())
            }
        }
    }

    private fun resolveSelectedDurationMinutes(): Int? {
        return when (binding.chipGroupDuration.checkedChipId) {
            R.id.chipDuration30m -> 30
            R.id.chipDuration1h -> 60
            R.id.chipDuration2h -> 120
            R.id.chipDurationCustom -> binding.etCustomMinutes.text?.toString()?.trim()?.toIntOrNull()
            else -> null
        }
    }

    companion object {
        const val MIN_DURATION_MINUTES = 1
        const val MAX_DURATION_MINUTES = 720
        private const val KEY_NAME = "draft_fortress_name"
        private const val KEY_DURATION = "draft_fortress_duration"
        private const val KEY_INTERNET_CUT = "draft_fortress_internet_cut"
        private const val KEY_ALLOWED = "draft_fortress_allowed"
        private const val KEY_VPN_BYPASS = "draft_fortress_vpn_bypass"
    }
}
